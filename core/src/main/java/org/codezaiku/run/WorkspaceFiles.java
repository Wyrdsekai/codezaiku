package org.codezaiku.run;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Works out which files a run changed, for hosts that drive CodeZaiku as a subprocess and sync on
 * the answer.
 *
 * <p>The write ledger on {@link org.codezaiku.tools.PathScope} is the primary source and is exact for
 * tool-mediated writes. It cannot see a {@code sed -i} or {@code mv} issued through the shell, which
 * is unscoped by design. This class closes that gap where it can, by asking git.
 *
 * <p>Two things make the naive version wrong, and both are handled here:
 *
 * <ul>
 *   <li><b>A single end-of-run {@code git status} over-reports.</b> It lists every dirty file in the
 *       workspace, including changes that were already there when the run started — attributing a
 *       developer's uncommitted work to the agent. So the status is snapshotted BEFORE the run and
 *       the reported set is the DELTA: paths that became dirty, or whose status changed.
 *   <li><b>Porcelain paths are repo-root-relative, not CWD-relative.</b> When the workspace is a
 *       subdirectory of a larger repo, using them directly yields paths that do not resolve against
 *       the workspace. They are rebased onto the workspace here, and anything outside it is dropped.
 * </ul>
 *
 * <p>When the workspace is not a git repository, {@link #snapshot} reports unavailable and the file
 * list falls back to the ledger alone — which the caller reports as possibly incomplete rather than
 * presenting a lower bound as the whole truth.
 */
public final class WorkspaceFiles {

    /** A git status reading, or an explicit "git could not tell us". */
    public record Snapshot(Map<String, String> statusByPath, boolean available) {
        static Snapshot unavailable() {
            return new Snapshot(Map.of(), false);
        }
    }

    /** {@code git status --porcelain} for {@code workspace}, or unavailable if it is not a repo. */
    public static Snapshot snapshot(Path workspace) {
        Path top = repoTop(workspace);
        if (top == null) return Snapshot.unavailable();
        // --untracked-files=all is REQUIRED, not a refinement: by default git collapses a wholly
        // untracked directory into one entry ending in `/` (`?? services/`). A run that creates
        // src/newpkg/thing.py would then report the DIRECTORY and lose the file, and a workspace that
        // is a subdirectory would drop the entry entirely as being outside itself.
        String out = git(workspace, "status", "--porcelain", "--untracked-files=all");
        if (out == null) return Snapshot.unavailable();

        Map<String, String> byPath = new LinkedHashMap<>();
        for (String line : out.split("\n")) {
            if (line.length() < 4) continue;
            String code = line.substring(0, 2);
            String raw = line.substring(3).trim();
            // A rename is reported as `old -> new`; the new path is the one that exists now.
            int arrow = raw.indexOf(" -> ");
            if (arrow >= 0) raw = raw.substring(arrow + 4);
            raw = unquote(raw);
            String rel = rebase(top, workspace, raw);
            if (rel != null) byPath.put(rel, code);
        }
        return new Snapshot(byPath, true);
    }

    /**
     * The files that changed between {@code before} and now: newly dirty paths, and paths whose status
     * changed. Empty when git is unavailable on either side.
     */
    public static List<String> changedSince(Path workspace, Snapshot before) {
        Snapshot after = snapshot(workspace);
        excluded = 0;
        if (!before.available() || !after.available()) return List.of();
        List<String> changed = new ArrayList<>();
        for (var e : after.statusByPath().entrySet()) {
            String was = before.statusByPath().get(e.getKey());
            if (was == null || !was.equals(e.getValue())) {
                if (installedDependency(e.getKey(), e.getValue())) { excluded++; continue; }
                changed.add(e.getKey());
            }
        }
        // A path that was dirty before and is clean now was also touched (e.g. reverted, or committed).
        for (String p : before.statusByPath().keySet()) {
            if (!after.statusByPath().containsKey(p)) changed.add(p);
        }
        return new ArrayList<>(new TreeSet<>(changed));
    }

    /**
     * Directory names a package manager or build fills in. Matched as a whole path SEGMENT, so a real
     * file called {@code build.py} or {@code src/target_selection.py} is untouched.
     */
    private static final Set<String> DEPENDENCY_DIRS = Set.of(
            "node_modules", ".venv", "venv", "__pycache__", ".pytest_cache", ".mypy_cache",
            ".tox", ".gradle", ".next", ".nuxt", "vendor", "target", "build", "dist", ".cargo");

    /** How many paths the last {@link #changedSince} call held back. Reported, never silent. */
    private static volatile int excluded = 0;

    /** Paths held back by the most recent delta, so a caller is never told a pruned list is whole. */
    public static int lastExcludedCount() {
        return excluded;
    }

    /**
     * True for a path a package manager installed rather than the run authoring it.
     *
     * <p>The reason there is no general filter list here is sound and unchanged: git's ignore rules are
     * the project's own statement about what is noise, and a list of ours would eventually drop a file
     * someone wanted. This is narrower than that, and it answers the same objection three ways.
     *
     * <p>It only ever holds back a path that is <b>untracked</b> ({@code ??}). A file the project TRACKS
     * is never touched, however it is named — tracking it is the project's statement that it matters,
     * including a vendored dependency directory someone deliberately committed. A file the project
     * IGNORES never reaches here at all, because git does not report it. So this fires in exactly one
     * situation: the project has said nothing, and a tool put the file there.
     *
     * <p>Measured cost of not having it: a request for ONE JavaScript file returned 4,324 paths,
     * 4,320 of them {@code node_modules}, because the model ran {@code npm install} in a workspace with
     * no {@code .gitignore}. A host that syncs on this list would copy a dependency tree.
     */
    static boolean installedDependency(String path, String status) {
        if (status == null || !status.trim().startsWith("??")) return false;  // tracked: not ours to judge
        for (String seg : path.split("/")) {
            if (DEPENDENCY_DIRS.contains(seg)) return true;
        }
        return false;
    }

    /** The ledger and the git delta, merged and sorted — the ledger is exact, git catches the shell. */
    public static List<String> merge(List<String> ledger, List<String> fromGit) {
        var all = new TreeSet<String>();
        all.addAll(ledger);
        all.addAll(fromGit);
        return new ArrayList<>(all);
    }

    /** Repo top-level for {@code dir}, or null when it is not inside a work tree. */
    private static Path repoTop(Path dir) {
        String out = git(dir, "rev-parse", "--show-toplevel");
        if (out == null || out.isBlank()) return null;
        return Path.of(out.trim());
    }

    /** The real path, symlinks resolved — or the plain absolute one if it cannot be resolved.
     *
     *  Both sides of the containment test below must be canonical or the test silently fails open.
     *  `git rev-parse --show-toplevel` always answers with symlinks RESOLVED, while the workspace is
     *  whatever the caller passed in. On macOS that is routinely different for the same directory:
     *  /tmp and /var are symlinks into /private, so a workspace under either compared
     *  /private/var/... against /var/... , every path fell "outside" the workspace, and the run
     *  reported ZERO changed files — a host syncing on that list would copy nothing and see success. */
    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    /** Re-express a repo-root-relative path against the workspace; null if it falls outside. */
    private static String rebase(Path repoTop, Path workspace, String repoRelative) {
        try {
            Path abs = real(repoTop).resolve(repoRelative).normalize();
            Path ws = real(workspace);
            if (!abs.startsWith(ws)) return null;
            return ws.relativize(abs).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Git quotes paths containing unusual bytes; take the literal inside rather than the quotes. */
    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s;
    }

    /** Run git in {@code dir}; null on any failure, so "no git" and "not a repo" collapse to unavailable. */
    private static String git(Path dir, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(dir.toFile());
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.getErrorStream().readAllBytes();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    private WorkspaceFiles() { }
}
