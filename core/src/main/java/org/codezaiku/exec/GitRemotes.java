package org.codezaiku.exec;

import org.codezaiku.Config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * What CodeZaiku knows of a project's git remotes, recorded when the job begins and never from the project again: the project's
 * git folder, each remote's name and address as the project's config file held them then, and the commits the project's
 * remote-tracking branches pointed at then. A push or a fetch of the job goes to the address recorded here, so a remote the job
 * adds or changes, a {@code pushurl} it writes, or an address rewrite it puts into the project's settings, changes nothing.
 *
 * <p>The config file is read as data, with {@code git config --file}, which reads "only from the specified file" and follows no
 * includes when a file is given (git-config(1)), from a folder of CodeZaiku's own so that git finds no repository around it. Kept
 * for this process, like the network's rules ({@link NetRules}): a chat's record is the one of its start.
 */
public final class GitRemotes {
    private GitRemotes() { }

    /** A remote as recorded: its name, where a fetch and a push go, or why CodeZaiku uses neither. */
    public record Remote(String name, GitAddress fetch, GitAddress push, String refused, String shownRaw) {
        public boolean usable() { return refused == null; }
    }

    /**
     * The record of a project: its git folder (the common one for a worktree; null when it has none), its remotes by name, and its
     * remote-tracking branches at the start ({@code refs/remotes/<remote>/<branch>} to a commit).
     */
    public record Record(Path project, Path gitDir, Map<String, Remote> remotes, Map<String, String> tracking, long at) {
        public List<Remote> usable() { List<Remote> out = new ArrayList<>(); for (Remote r : remotes.values()) if (r.usable()) out.add(r); return out; }
        public Remote remote(String name) { return name == null ? null : remotes.get(name); }
        /** The usable remotes on github.com. */
        public List<Remote> onGitHub() { List<Remote> out = new ArrayList<>(); for (Remote r : usable()) if (r.fetch().gitHub() != null) out.add(r); return out; }
    }

    private static final Map<String, Record> RECORDS = new ConcurrentHashMap<>();

    /** Record the project's remotes now, if this process has not yet: called when a job begins ({@link Sandbox#model}). */
    public static void began(Path project) {
        if (project == null) return;
        RECORDS.computeIfAbsent(key(project), k -> read(project));
    }

    /** Read the record again: after the person kept, in a chat, a change a command made to the remotes (their yes makes it theirs). */
    public static void reread(Path project) {
        if (project != null) RECORDS.put(key(project), read(project));
    }

    /** The record made when the job began; an empty one where none was made. */
    public static Record of(Path project) {
        if (project == null) return new Record(null, null, Map.of(), Map.of(), 0);
        Record r = RECORDS.get(key(project));
        return r != null ? r : new Record(project, null, Map.of(), Map.of(), 0);
    }

    /** For tests: every record forgotten. */
    public static void forgetForTests() { RECORDS.clear(); }

    private static String key(Path project) { return SandboxRules.real(project).toString(); }

    // ── reading the record ──────────────────────────────────────────────────────────────────────────────────────────

    private static Record read(Path project) {
        Path gitDir = gitDirOf(project);
        if (gitDir == null) return new Record(project, null, Map.of(), Map.of(), System.currentTimeMillis());
        Map<String, Remote> remotes = remotesIn(gitDir.resolve("config"));
        Map<String, String> tracking = remotes.isEmpty() ? Map.of() : GitOutside.trackingAtStart(project, gitDir);
        return new Record(project, gitDir, remotes, tracking, System.currentTimeMillis());
    }

    /** The project's git folder: {@code .git} when it is a folder, else the common folder a worktree's {@code .git} file leads to. */
    static Path gitDirOf(Path project) {
        Path dotGit = project.resolve(".git");
        if (Files.isDirectory(dotGit)) return SandboxRules.real(dotGit);
        List<Path> dirs = SandboxRules.gitDirs(project);
        if (dirs.isEmpty()) return null;
        return dirs.get(dirs.size() - 1);                 // the common folder when there is one, else the worktree's own
    }

    /** The remotes in a config file, by name: {@code url} (the first) and {@code pushurl} (the first), as data. */
    static Map<String, Remote> remotesIn(Path config) {
        Map<String, Remote> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(config)) return out;
        Ran r = run(List.of(git(), "config", "--file", config.toString(), "--null", "--get-regexp", "^remote\\..*\\.(url|pushurl)$"), neutralDir(), quietEnv(), 10);
        if (r.exit() != 0) return out;
        Map<String, List<String>> urls = new LinkedHashMap<>(), pushUrls = new LinkedHashMap<>();
        for (String entry : r.out().split("\0")) {
            int nl = entry.indexOf('\n');
            if (nl < 0) continue;
            String name = entry.substring(0, nl), value = entry.substring(nl + 1);
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("remote.")) continue;
            int last = name.lastIndexOf('.');
            String remote = name.substring("remote.".length(), last);
            (lower.endsWith(".pushurl") ? pushUrls : urls).computeIfAbsent(remote, k -> new ArrayList<>()).add(value);
        }
        for (Map.Entry<String, List<String>> e : urls.entrySet()) {
            String name = e.getKey();
            List<String> push = pushUrls.getOrDefault(name, e.getValue());
            String refused = null;
            GitAddress fetch = null, pushTo = null;
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,99}") || name.contains("..")) refused = "its name is not one CodeZaiku passes on";
            else if (e.getValue().size() > 1 || push.size() > 1) refused = "it has more than one address, and CodeZaiku uses one";
            else {
                GitAddress.Read f = GitAddress.read(e.getValue().get(0)), p = GitAddress.read(push.get(0));
                if (f.address() == null) refused = "its address is not used: " + f.refused();
                else if (p.address() == null) refused = "its push address is not used: " + p.refused();
                else { fetch = f.address(); pushTo = p.address(); }
            }
            out.put(name, new Remote(name, fetch, pushTo, refused, GitAddress.shownRaw(e.getValue().get(0))));
        }
        return out;
    }

    // ── git and gh, as the person has them ──────────────────────────────────────────────────────────────────────────

    private static volatile Map<String, String> personForTests;

    /** For tests: the environment the person's git and gh run with, in place of this process's; null returns to it. */
    public static void personForTests(Map<String, String> env) { personForTests = env; }

    /**
     * The person's own environment, as this program was started with it, for the git and gh CodeZaiku runs outside the sandbox: their
     * HOME, ssh agent, credentials and settings. Taken out: every {@code GIT_*} variable that would point git at another repository,
     * index or object store, or add settings ({@code GIT_DIR}, {@code GIT_CONFIG_PARAMETERS}, {@code GIT_CONFIG_COUNT} and the rest),
     * except the person's choice of ssh program, askpass and settings files; and CodeZaiku's own settings.
     */
    static Map<String, String> personEnv() {
        Map<String, String> base = personForTests != null ? personForTests : System.getenv();
        Map<String, String> env = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : base.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("CODEZAIKU_")) continue;
            if (k.startsWith("GIT_") && !List.of("GIT_SSH", "GIT_SSH_COMMAND", "GIT_SSH_VARIANT", "GIT_ASKPASS", "GIT_CONFIG_GLOBAL",
                    "GIT_CONFIG_SYSTEM", "GIT_CONFIG_NOSYSTEM").contains(k)) continue;
            env.put(k, e.getValue());
        }
        env.put("GIT_TERMINAL_PROMPT", "0");                // no question on the terminal the chat holds: a credential comes from a helper or the agent
        return env;
    }

    /** An environment that reads no settings of anyone's: for git commands of CodeZaiku's own on its own repository and on data. */
    static Map<String, String> quietEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        Map<String, String> base = personForTests != null ? personForTests : System.getenv();
        for (String k : List.of("PATH", "LANG", "LC_ALL", "TZ", "SYSTEMROOT")) if (base.get(k) != null) env.put(k, base.get(k));
        env.put("HOME", neutralDir().toString());
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", nullFile());
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_ATTR_NOSYSTEM", "1");
        return env;
    }

    static String nullFile() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "NUL" : "/dev/null"; }

    private static volatile String gitForTests, ghForTests;

    /** For tests: the gh program (a stand-in of the test's own), "" for none installed; null returns to the one on the person's PATH. */
    public static void ghForTests(String path) { ghForTests = path; }

    /** git on the person's PATH, by an absolute folder; "git" when none is found (the error then says so). */
    static String git() {
        if (gitForTests != null) return gitForTests;
        String g = onPath("git", null);
        return g == null ? "git" : g;
    }

    /** gh on the person's PATH, by an absolute folder that is not the project's or one a command writes; null when not installed. */
    static String gh(Path project) {
        if (ghForTests != null) return ghForTests.isEmpty() ? null : ghForTests;
        return onPath("gh", project);
    }

    /** A program on the person's PATH, in an absolute folder outside the project and the run's own folders. */
    static String onPath(String name, Path project) {
        Map<String, String> env = personForTests != null ? personForTests : System.getenv();
        String path = env.getOrDefault("PATH", "");
        List<Path> notFrom = new ArrayList<>();
        if (project != null) {
            notFrom.add(SandboxRules.real(project));
            try { notFrom.add(SandboxRules.real(Sandbox.folders(project).dir())); } catch (IOException e) { /* none made */ }
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            Path d;
            try { d = Path.of(dir); } catch (RuntimeException e) { continue; }
            if (!d.isAbsolute()) continue;
            Path real = SandboxRules.real(d);
            boolean inside = false;
            for (Path n : notFrom) if (real.startsWith(n)) inside = true;
            if (inside) continue;
            for (String ext : windows ? new String[]{".exe", ".cmd", ""} : new String[]{""}) {
                Path p = d.resolve(name + ext);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) return p.toString();
            }
        }
        return null;
    }

    /** An empty folder of CodeZaiku's own: where its git and gh run, so that neither finds a repository around them. */
    static Path neutralDir() {
        Path d = Config.home().resolve("git").resolve("empty");
        try { Files.createDirectories(d); } catch (IOException e) { /* the run says so */ }
        return d;
    }

    // ── running ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** A finished program: its exit code, standard output and standard error. -1 when it could not run or ran out of time. */
    record Ran(int exit, String out, String err) { }

    static Ran run(List<String> cmd, Path dir, Map<String, String> env, int seconds) { return run(cmd, dir, env, seconds, null); }

    static Ran run(List<String> cmd, Path dir, Map<String, String> env, int seconds, String stdin) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
            pb.environment().clear();
            pb.environment().putAll(env);
            if (stdin == null) pb.redirectInput(ProcessBuilder.Redirect.from(new File(nullFile())));
            Process p = pb.start();
            if (stdin != null) {
                Thread.ofVirtual().start(() -> {
                    try (var o = p.getOutputStream()) { o.write(stdin.getBytes(StandardCharsets.UTF_8)); } catch (IOException e) { /* ended */ }
                });
            }
            StringBuilder err = new StringBuilder();
            Thread e = Thread.ofVirtual().start(() -> {
                try (InputStream in = p.getErrorStream()) { err.append(new String(in.readAllBytes(), StandardCharsets.UTF_8)); } catch (IOException x) { /* ended */ }
            });
            String out;
            try (InputStream in = p.getInputStream()) { out = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                e.join(2000);
                return new Ran(-1, out, err + "\n(stopped after " + seconds + " s)");
            }
            e.join(5000);
            return new Ran(p.exitValue(), out, err.toString());
        } catch (IOException x) {
            return new Ran(-1, "", x.getMessage() == null ? "could not start " + cmd.get(0) : x.getMessage());
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
            return new Ran(-1, "", "interrupted");
        }
    }

    /**
     * What git or gh printed, as a person or a model may read it: every control character but newline and tab taken out (a remote's
     * messages reach the terminal through git, and escape sequences in them "can be used by a malicious actor to hide and misrepresent
     * information": CVE-2024-52005), cut to {@code most} characters.
     */
    public static String clean(String s, int most) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(Math.min(s.length(), most + 16));
        for (int i = 0; i < s.length() && b.length() < most; i++) {
            char c = s.charAt(i);
            if (c == '\r') continue;
            if ((c < 0x20 && c != '\n' && c != '\t') || c == 0x7f || (c >= 0x80 && c < 0xa0)) continue;
            b.append(c);
        }
        if (s.length() > most && b.length() >= most) b.append("\n…");
        return b.toString().strip();
    }
}
