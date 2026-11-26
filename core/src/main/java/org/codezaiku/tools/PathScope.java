package org.codezaiku.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves model-supplied relative paths inside the project root; refuses escapes.
 *
 * <p>LITERAL resolution for in-project paths — NO silent relocation of a path the model means literally.
 * The write/edit/read tools and the shell ALL resolve relative to this one root, so they can never
 * disagree about where a file is (the reference-harness rule: one cwd, one resolver, no magic —
 * opencode/pi/smallcode/goose). An earlier version SILENTLY stripped an invented {@code output/<project>/}
 * wrapper to keep the build target at root; but the shell tool did NOT strip it, so the two disagreed and
 * the 9B spun forever hunting its own files. The cure is consistency, not magic.
 *
 * <p>The ONE relocation we DO perform is rejecting an out-of-tree ABSOLUTE path rather than nesting it: the
 * field rule (opencode/MCP/Positron) is canonicalize-then-reject, never silent re-root. The old
 * strip-leading-slash-and-rejoin turned a hallucinated {@code /home/.../x.py} into a {@code <root>/home/.../x.py}
 * ghost tree (battery44 split-brain). Now an absolute system path outside the project is REJECTED with a
 * corrective error the model acts on; a genuine in-project absolute path is accepted; a bare {@code /app/x.py}
 * is treated as the root-relative intent it is. (Shell is unscoped — the tier-3 gap; a kernel write-jail
 * would be the next layer if the model ever scatters via the shell.)
 */
public final class PathScope {
    private final Path root;

    // System-directory prefixes that mark an ABSOLUTE path as a real filesystem location the model
    // confused for a project path — NOT a root-relative path. The old code stripped the leading slash
    // and re-rooted EVERY absolute path, so `/home/<user>/battery37/app/x.py` became
    // `<root>/home/<user>/battery37/app/x.py` — a nested GHOST TREE inside the project (battery44).
    // The field's rule (opencode/MCP/Positron): canonicalize, then REJECT an out-of-tree absolute path
    // with a corrective error — never silently re-root it. We reject only the system-prefix shapes (the
    // damaging case); a plain `/app/main.py` stays the model's harmless root-relative intent.
    private static final List<String> SYSTEM_PREFIXES = List.of(
            "/home/", "/Users/", "/root/", "/etc/", "/tmp/", "/var/", "/usr/", "/opt/", "/mnt/", "/media/",
            "/proc/", "/sys/", "/dev/", "/bin/", "/sbin/", "/lib/", "/lib64/", "/run/", "/srv/", "/private/");
    // Unambiguous system-dir names for the RELATIVE foreign-ghost guard (never a legit relative project
    // root). Excludes bin/lib/tmp/sbin/run — those can be real project dirs.
    private static final Set<String> FOREIGN_HEADS = Set.of(
            "home", "Users", "usr", "etc", "var", "opt", "mnt", "media", "proc", "sys", "dev", "root",
            "private", "srv");

    public PathScope(Path root) {
        // The REAL root, not the lexical one: every confinement check compares against this, so if the
        // root itself is reached through a symlink the comparison must already be canonical or every
        // resolved path looks like an escape.
        this.root = realOf(root.toAbsolutePath().normalize());
        Job running = Job.RUNNING.get();
        this.job = running != null ? running : new Job();
    }

    // ---- the job: when it began, and what it wrote -----------------------------------------------
    /**
     * A job, for what its files may give ({@link GivenAddresses}): a file of the project gives the addresses written in it only as it was
     * before the job began. A file the job wrote or changed gives nothing, or a model would write an address into a file and read it back;
     * so does one changed after the job began by any means (a command's {@code sed}, {@code git checkout}), which its modification time
     * shows; so does what CodeZaiku keeps in the project's {@code .codezaiku} folder, which is the model's own output (a delegated job's
     * log, the chat's records).
     *
     * <p>One job may span several registries: the chat's turns are one conversation, and a job a model starts from inside another (a
     * delegated one, a research pass) is part of it. A loop that runs marks its job on the thread ({@link #enter}), and a scope made on
     * that thread or a thread it starts takes the same job.
     */
    public static final class Job {
        static final InheritableThreadLocal<Job> RUNNING = new InheritableThreadLocal<>();
        /** How close to the job's start a file's time may be and still count as changed by it: file systems keep times to a second or two. */
        static final long MARGIN_MS = 2_000;
        final long began;
        private final Set<Path> wrote = Collections.synchronizedSet(new LinkedHashSet<>());

        public Job() { this(System.currentTimeMillis()); }
        Job(long began) { this.began = began; }

        /** A job marked on a thread, until it is closed. */
        public interface Entered extends AutoCloseable { @Override void close(); }

        /** This job, on this thread and the threads it starts, until the returned handle is closed. */
        public static Entered enter(Job j) {
            Job was = RUNNING.get();
            RUNNING.set(j);
            return () -> { if (was == null) RUNNING.remove(); else RUNNING.set(was); };
        }

        void wrote(Path real) { wrote.add(real); }

        /** Whether this file is as it was before the job began: the job did not write it, it was not changed since, and it is no record of CodeZaiku's own. */
        public boolean asItWas(Path file) {
            try {
                if (file == null || !Files.isRegularFile(file)) return false;
                Path real = file.toRealPath();
                if (wrote.contains(real)) return false;
                for (Path p : real) if (p.toString().equals(".codezaiku")) return false;
                return Files.getLastModifiedTime(real).toMillis() < began - MARGIN_MS;
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }
    }

    private volatile Job job;

    /** The job this scope's writes belong to. */
    public Job job() { return job; }

    /** Makes this scope part of {@code j}: the chat hands each turn's scope its conversation. */
    public PathScope job(Job j) { if (j != null) this.job = j; return this; }

    /** Whether a file is as it was before this scope's job began ({@link Job#asItWas}). */
    public boolean asItWas(Path file) { return job.asItWas(file); }

    /** {@code toRealPath()} where the path exists, the closest existing ancestor otherwise. */
    private static Path realOf(Path p) {
        Path existing = p;
        int climbed = 0;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
            climbed++;
        }
        if (existing == null) return p;                 // nothing on this path exists; lexical is all we have
        try {
            Path real = existing.toRealPath();
            // Re-attach the segments that do not exist yet (the common case for a file about to be
            // CREATED). They contain no symlinks precisely because they do not exist.
            return climbed == 0 ? real : real.resolve(existing.relativize(p)).normalize();
        } catch (IOException e) {
            return p;
        }
    }

    /**
     * The single confinement gate. Canonicalises through symlinks and returns the REAL path, so callers
     * act on the resolved location rather than on an alias that could be re-pointed underneath them.
     *
     * <p>This exists because {@code normalize()} is a pure STRING operation — it collapses {@code ..}
     * textually and never touches the filesystem. A symlink stored inside the project but pointing out of
     * it therefore satisfied a lexical {@code startsWith(root)}, and the write followed it out of the tree,
     * defeating blast-radius and the host-write guard. Verified live before this was added.
     *
     * <p>A symlink whose target stays INSIDE the root is legitimate and is allowed — the property being
     * enforced is "the operation lands inside the root", not "no symlinks exist".
     *
     * <p>Residual: an attacker who swaps a symlink between this check and the caller's open() is not
     * defeated here — closing that needs O_NOFOLLOW, which the JDK file APIs do not expose. Returning the
     * resolved real path (rather than the alias) shrinks the window to the segments above the file.
     */
    private Path confine(Path candidate, String asWritten) {
        Path real = realOf(candidate.toAbsolutePath().normalize());
        if (!real.startsWith(root)) {
            throw new IllegalArgumentException("'" + asWritten + "' resolves to " + real + ", which is OUTSIDE "
                    + "the project root " + root + " — a symlink or parent reference leaves the project. Write "
                    + "to a path relative to the project root instead (e.g. 'app/main.py').");
        }
        return real;
    }

    public Path root() {
        return root;
    }

    public Path resolve(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("empty path");
        }
        String r = relative.replace('\\', '/').strip();

        if (r.startsWith("/")) {
            // (a) A genuine absolute path INSIDE the project root — the model echoed back the real path of
            //     a project file (the redirect notes show these). Accept it literally.
            Path abs = Path.of(r).normalize();
            // Lexically in-root is NOT enough — `<root>/link/x` where `link` points out still starts with
            // root as a string. confine() canonicalises before deciding.
            if (abs.startsWith(root)) return confine(abs, relative);
            // (b) An absolute path under a SYSTEM directory, pointing OUTSIDE the project — the ghost-tree /
            //     escape case. Reject with a correction the model can act on (surfaced as the tool ERROR).
            String normalized = abs.toString();
            for (String sys : SYSTEM_PREFIXES) {
                if (normalized.startsWith(sys) || (normalized + "/").startsWith(sys)) {
                    throw new IllegalArgumentException("'" + relative + "' is an absolute system path OUTSIDE "
                            + "this project. The project root is " + root + " — every file for this project lives "
                            + "under it. Write to a path RELATIVE to the project root instead (e.g. 'app/main.py'), "
                            + "not an absolute /home/... or /tmp/... path.");
                }
            }
            // (c) Any other leading-slash path (e.g. '/main.py', '/app/x.py') — the model's root-relative
            //     intent written with a stray leading slash. Re-root it under the project (lands harmlessly
            //     in a normal subdir, never a system-named ghost tree).
            r = r.replaceFirst("^/+", "");
        }

        // RELATIVE-GHOST GUARD (battery50 split-brain): a RELATIVE path that REPEATS the project's own
        // absolute location nests a ghost tree inside the project (the model wrote the run-root's path —
        // `home/<user>/.../<project>/app/config.py` — without a leading slash, so it resolves to
        // `<root>/home/<user>/.../<project>/app/config.py`). Strip it back to the in-project tail; the
        // write LANDS at the real location and the tool's redirect-note corrects the model.
        String rootRel = root.toString().replaceFirst("^/+", "");
        if (r.equals(rootRel)) {
            throw new IllegalArgumentException("'" + relative + "' is the project's own location written as a "
                    + "relative path. Write a path RELATIVE to the project root (e.g. 'app/main.py'), not the "
                    + "root itself.");
        }
        if (r.startsWith(rootRel + "/")) {
            r = r.substring(rootRel.length() + 1);
        } else {
            // FOREIGN-GHOST GUARD: a relative path whose first segment is an unambiguous SYSTEM directory
            // (home/, usr/, etc/ …) — the model mirrored a system path relative, which would nest a junk
            // tree. Reject with a correction (these dir names are never a legitimate relative project root;
            // bin/lib/tmp are deliberately NOT in the set since they can be real project dirs).
            int slash = r.indexOf('/');
            String head = slash < 0 ? r : r.substring(0, slash);
            if (FOREIGN_HEADS.contains(head)) {
                throw new IllegalArgumentException("'" + relative + "' starts with a system directory ('" + head
                        + "/') written as a relative path — that nests a junk tree inside the project. Write a "
                        + "path relative to the project root instead (e.g. 'app/main.py').");
            }
        }

        return confine(root.resolve(r), relative);
    }

    public String rel(Path p) {
        return root.relativize(p.toAbsolutePath().normalize()).toString();
    }

    // ---- write ledger ---------------------------------------------------------------------------
    // A host driving CodeZaiku as a subprocess needs to know which files a run touched; nothing
    // tracked that. It is recorded HERE because every write tool already shares exactly one PathScope
    // per run (ToolRegistry:61) — so the ledger is per-run by construction, with no static state to
    // leak between calls in a long-lived JVM (the `mcp` verb serves many runs from one process).
    //
    // What it CANNOT see: a `sed -i`, `mv` or `rm` issued through ShellTool, which is unscoped by
    // design (see the class note above — the tier-3 gap). Under-reporting a modified file is the
    // dangerous direction for a caller that syncs on this list, so a shell command does not silently
    // produce a short list: it marks the ledger INCOMPLETE and the caller reconciles against git and
    // says which mechanism it trusted.
    private final Set<String> written =
            Collections.synchronizedSet(new LinkedHashSet<>());
    private volatile boolean shellRan;

    /** Record a file this run created or modified, as a path relative to the root. */
    public void recordWrite(String relative) {
        if (relative != null && !relative.isBlank()) {
            written.add(relative.replace('\\', '/'));
            job.wrote(realOf(root.resolve(relative.replace('\\', '/')).toAbsolutePath().normalize()));
        }
    }

    /** Record an absolute path; ignored if it somehow lies outside the root rather than being coerced. */
    public void recordWrite(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        if (abs.startsWith(root)) recordWrite(rel(abs));
    }

    /** Note that an unscoped shell command ran, so the ledger may be missing files it wrote. */
    public void noteShellRan() {
        this.shellRan = true;
    }

    /** True when a shell command ran, meaning {@link #written()} is a LOWER BOUND, not the whole set. */
    public boolean mayBeIncomplete() {
        return shellRan;
    }

    /** Files this run created or modified through the tools, in a stable order. */
    public List<String> written() {
        synchronized (written) {
            return new ArrayList<>(new TreeSet<>(written));
        }
    }
}
