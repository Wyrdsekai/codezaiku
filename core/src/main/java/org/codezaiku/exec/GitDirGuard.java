package org.codezaiku.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.Config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * What a job's commands write into the places of a project's git folders that git reads as instructions does not outlive the
 * command: the settings ({@code config}, {@code config.worktree}), {@code hooks/}, {@code info/}, {@code objects/info/alternates},
 * {@code remotes/}, {@code branches/}, {@code commondir}, a linked worktree's pointers, and the {@code .git} entry itself, for the
 * project's own git folder, each submodule's under {@code .git/modules/} and a repository a job makes in a folder of the project.
 * The sandbox has to let a command write {@code .git} (commits, branches and tags live there), and the person's own git, run by hand
 * after the job, obeys those places outside the sandbox (a trial: a {@code core.fsmonitor} a job wrote ran on the person's next
 * {@code git status}). The owner's decision of 2026-11-24: put them back.
 *
 * <p>The rule is "back to what it was when the job began", not a list of bad keys: a list is always one key short (git's own pages
 * now document {@code hook.<name>.command}, hooks set in the settings). The exceptions are what git itself writes in ordinary work,
 * found by trial: {@code branch.<b>.remote}, {@code .merge}, {@code .rebase} (an upstream), {@code info/refs} ({@code git gc}), a
 * new linked worktree's own folder, and {@code hooks/*.sample}. A repository the job makes has, as its "as it was", what
 * {@code git init} writes with nobody's settings.
 *
 * <p>Reading and putting back are done by {@code sandbox/gitguard.py} run through the sandbox with the job's own rules: it never follows
 * a link, and whatever a job does with links it cannot read or write anything the job's commands could not. Nothing put back is
 * thrown away: CodeZaiku cannot tell who wrote a file (the sandbox reports nothing, and a file's times say when, not who), so the
 * version taken out is kept in {@code ~/.codezaiku/git-put-back/} and said; in a chat the person is asked whether to keep it.
 */
public final class GitDirGuard {
    private GitDirGuard() { }

    private static final ObjectMapper JSON = new ObjectMapper();
    /** How often a running job's places are looked at, by their sizes, times and inodes. */
    static volatile long pollMillis = 2000;

    /** One entry as read: what it is, its mode, its identity, its bytes (a file), its target (a link). */
    record Entry(String kind, int mode, long ino, long dev, long mtime, long size, byte[] bytes, String target, boolean big) {
        boolean same(Entry o) {
            if (o == null || !kind.equals(o.kind)) return false;
            if (kind.equals("file")) return big || o.big ? size == o.size && mtime == o.mtime : Arrays.equals(bytes, o.bytes) && (mode & 0111) == (o.mode & 0111);
            if (kind.equals("link")) return String.valueOf(target).equals(String.valueOf(o.target));
            if (kind.equals("dir")) return true;
            return true;
        }
    }

    /** The places as read: entries by their path in the project, the git folders, and the project's top-level folders' identities. */
    record State(Map<String, Entry> entries, List<String> gitdirs, List<String> dotgits, Map<String, long[]> top, boolean complete) { }

    /** Something put back: when, which project, the path, the change in words, where the job's version is kept, and that version. */
    public record PutBack(long at, Path project, String path, String change, String copy, boolean whileCommand, Entry jobs) { }

    /** Whoever is asked whether to keep a change: the person in a chat. Null means keep it. */
    public interface Asker { String ask(String key, String what, List<String> preview); }

    private static final class Job {
        final Path project;
        volatile State start;
        volatile boolean active;
        volatile Thread poller;
        volatile String signature = "";
        volatile boolean retry;                  // something could not be put back: look again after the next command and at the end
        final List<PutBack> pending = new CopyOnWriteArrayList<>();
        final List<PutBack> all = new CopyOnWriteArrayList<>();
        final List<String> startLines = new CopyOnWriteArrayList<>();
        volatile int running;
        volatile Asker asker;
        Job(Path project) { this.project = project; }
    }

    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>();

    private static String key(Path project) { return SandboxRules.real(project).toString(); }

    // ── the job's start and end ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Said when a job begins ({@link Sandbox#model}, which a chat also says at every turn): the first time for this project while no
     * job runs for it, the places are read and are "as it was", and compared with what CodeZaiku left here last time. Where this
     * job runs no commands there is nothing to guard.
     */
    public static void began(Path project) {
        if (project == null || !Sandbox.commandsRun(project)) return;
        Job j = JOBS.computeIfAbsent(key(project), k -> new Job(project));
        synchronized (j) {
            if (j.active) return;
            State s = read(project);
            if (s == null) return;
            j.start = s;
            j.active = true;
            j.signature = signature(project, s);
            String since = sinceLastTime(project, s);
            if (since != null) j.startLines.add(since);
            Thread t = Thread.ofVirtual().name("git-guard").start(() -> poll(j));
            j.poller = t;
        }
    }

    /**
     * The job's end: one last look, the poller stopped, what was left written down for next time. The lines to print: everything put back
     * since {@code sinceMillis} ({@code all}: a job that asked nobody), or only what nobody was asked about yet (a chat); empty when nothing.
     */
    public static List<String> ended(Path project, long sinceMillis, boolean all) {
        Job j = project == null ? null : JOBS.get(key(project));
        if (j == null) return List.of();
        synchronized (j) {
            if (j.active) {
                if (j.retry || !signature(project, j.start).equals(j.signature)) check(j, false);
                j.active = false;
                if (j.poller != null) j.poller.interrupt();
                remember(project, j.start);
            }
            if (!all) {
                List<PutBack> left = new ArrayList<>(j.pending);
                j.pending.clear();
                return left.isEmpty() ? List.of() : linesOf(left);
            }
            j.pending.clear();
        }
        return lines(project, sinceMillis);
    }

    /** The first lines a job prints about its git folders; said once. */
    public static List<String> startLines(Path project) {
        Job j = project == null ? null : JOBS.get(key(project));
        if (j == null) return List.of();
        List<String> out = new ArrayList<>(j.startLines);
        j.startLines.clear();
        return out;
    }

    /** The person in a chat, asked whether to keep a change; null takes them away. */
    public static void asker(Path project, Asker a) {
        if (project == null) return;
        Job j = JOBS.computeIfAbsent(key(project), k -> new Job(project));
        j.asker = a;
    }

    /** A command of the job starts and ends: a change found while one runs is said to be the job's. */
    public static void commandStarted(Path project) { Job j = project == null ? null : JOBS.get(key(project)); if (j != null) j.running++; }

    /** Whether a process this program started is alive, besides the network keepers: a command, a background task, a language server, a build. */
    static boolean jobProcessesAlive() {
        Set<Long> keepers = NetProxy.keeperPids();
        return ProcessHandle.current().descendants().anyMatch(p -> p.isAlive() && !keepers.contains(p.pid()));
    }

    /**
     * After a command of the job, before its result goes back: the places are looked at, a change is put back (its version kept), and
     * in a chat the person is asked whether to keep it. The note for the model; empty when nothing changed.
     */
    public static String afterCommand(Path project) {
        Job j = project == null ? null : JOBS.get(key(project));
        if (j == null) return "";
        List<PutBack> found;
        synchronized (j) {
            if (j.running > 0) j.running--;
            if (!j.active) return "";
            if (j.retry || !signature(project, j.start).equals(j.signature)) check(j, true);
            found = new ArrayList<>(j.pending);
            j.pending.clear();
        }
        if (found.isEmpty()) return "";
        Asker a = j.asker;
        boolean kept = false;
        if (a != null) {
            String no;
            try { no = a.ask("git settings", "keep this change to the project's own git settings", question(found)); } catch (RuntimeException e) { no = "no answer"; }
            if (no == null) kept = keep(j, found);
        }
        return note(found, kept);
    }

    // ── looking ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The look while a job runs: a change is acted on once the places have stayed the same for one interval, so a git command still
     * writing (git init copies its template file by file) is not caught half way.
     */
    private static void poll(Job j) {
        String seen = null;
        while (j.active) {
            try { Thread.sleep(pollMillis); } catch (InterruptedException e) { return; }
            try {
                synchronized (j) {
                    if (!j.active) return;
                    String now = signature(j.project, j.start);
                    boolean settled = now.equals(seen);
                    seen = now;
                    if (!now.equals(j.signature) && settled) check(j, j.running > 0 || jobProcessesAlive());
                }
            } catch (RuntimeException e) {
                // the next look
            }
        }
    }

    /**
     * The places' sizes, times, inodes and the names in their folders, read outside without following a link and without reading a
     * file: a change starts a real look. The project's top-level folder's entries count too (a new folder may hold a repository).
     */
    static String signature(Path project, State s) {
        StringBuilder b = new StringBuilder();
        List<String> look = new ArrayList<>(s.entries().keySet());
        for (String g : s.gitdirs()) for (String sub : List.of("", "/hooks", "/info", "/worktrees", "/modules", "/remotes", "/branches", "/objects/info", "/config", "/config.worktree", "/commondir"))
            look.add(g + sub);
        look.add(".git");
        for (String rel : new LinkedHashSet<>(look)) {
            Path p = project.resolve(rel);
            try {
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                b.append(rel).append(' ').append(a.size()).append(' ').append(a.lastModifiedTime().toMillis()).append(' ').append(a.fileKey()).append(a.isSymbolicLink() ? " L" : "")
                        .append(' ').append(ctime(p)).append('\n');
                if (a.isDirectory()) try (var list = Files.list(p)) { list.map(x -> x.getFileName().toString()).sorted().forEach(n -> b.append("  ").append(n).append('\n')); }
            } catch (IOException | RuntimeException e) {
                b.append(rel).append(" -\n");
            }
        }
        try (var list = Files.list(project)) { list.map(x -> x.getFileName().toString()).sorted().forEach(n -> b.append("top ").append(n).append('\n')); } catch (IOException e) { /* - */ }
        return b.toString();
    }

    /** The time a file's inode last changed, which no program can set back (a job's {@code touch -r} sets the time it was written): "" where the system has none. */
    private static String ctime(Path p) {
        try { return String.valueOf(Files.getAttribute(p, "unix:ctime", LinkOption.NOFOLLOW_LINKS)); } catch (IOException | RuntimeException e) { return ""; }
    }

    /**
     * Reads the places, with the helper outside the sandbox: it reaches every folder from the project one at a time with O_NOFOLLOW,
     * so it reads nothing through a link and nothing outside the project, whatever a job swaps while it runs. Null when it could not run.
     */
    static State read(Path project) {
        try {
            Path helper = helper();
            ProcessBuilder pb = new ProcessBuilder(python(), "-I", "-S", helper.toString(), "read", SandboxRules.real(project).toString());
            pb.environment().clear();
            pb.environment().putAll(GitRemotes.quietEnv());
            pb.redirectError(ProcessBuilder.Redirect.DISCARD).redirectInput(ProcessBuilder.Redirect.from(new File(GitRemotes.nullFile())));
            Process p = pb.start();
            byte[] out;
            try (InputStream in = p.getInputStream()) { out = in.readAllBytes(); }
            if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            if (p.exitValue() != 0) return null;
            return parse(JSON.readTree(out));
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static State parse(JsonNode d) {
        Map<String, Entry> entries = new TreeMap<>();
        d.path("entries").fields().forEachRemaining(e -> {
            JsonNode n = e.getValue();
            if (n == null || n.isNull()) return;
            entries.put(e.getKey(), new Entry(n.path("kind").asText("missing"), n.path("mode").asInt(0), n.path("ino").asLong(0), n.path("dev").asLong(0),
                    n.path("mtime").asLong(0), n.path("size").asLong(0), n.has("b64") ? Base64.getDecoder().decode(n.path("b64").asText()) : null,
                    n.has("target") ? n.path("target").asText() : null, n.path("big").asBoolean(false) || n.path("unread").asBoolean(false)));
        });
        List<String> gitdirs = new ArrayList<>(), dotgits = new ArrayList<>();
        d.path("gitdirs").forEach(x -> gitdirs.add(x.asText()));
        d.path("dotgits").forEach(x -> dotgits.add(x.asText()));
        Map<String, long[]> top = new LinkedHashMap<>();
        d.path("top").fields().forEachRemaining(e -> top.put(e.getKey(), new long[]{e.getValue().path(0).asLong(), e.getValue().path(1).asLong()}));
        return new State(entries, gitdirs, dotgits, top, d.path("complete").asBoolean(true));
    }

    // ── comparing and putting back ──────────────────────────────────────────────────────────────────────────────────

    /** One look: what differs from the start, put back, kept, recorded. Must hold the job's lock. */
    private static void check(Job j, boolean whileCommand) {
        State now = read(j.project);
        if (now == null) return;                                         // not read: the flag to look again stays as it was
        j.retry = false;
        Path project = j.project;
        State start = j.start;
        Map<String, Entry> template = null;
        List<Map<String, Object>> removes = new ArrayList<>(), dirs = new ArrayList<>(), writes = new ArrayList<>();
        List<String[]> changes = new ArrayList<>();                     // path, words
        Map<String, Entry> jobs = new LinkedHashMap<>();                // the job's version of each path put back (null entry: it had none)
        Map<String, Entry> newStart = new TreeMap<>(start.entries());
        List<String> gitdirs = new ArrayList<>(start.gitdirs());
        for (String g : now.gitdirs()) if (!gitdirs.contains(g)) gitdirs.add(g);
        TreeMap<String, Entry> both = new TreeMap<>();
        both.putAll(start.entries());
        both.putAll(now.entries());
        Set<String> paths = new LinkedHashSet<>(both.keySet());
        paths.addAll(start.entries().keySet());
        for (String path : paths) {
            Entry was = start.entries().get(path), is = now.entries().get(path);
            String g = gitdirOf(path, gitdirs, now.dotgits());
            boolean fresh = g != null && !start.gitdirs().contains(g) && !start.entries().containsKey(path);
            if (fresh && was == null) {
                if (template == null) template = template();
                String inside = g.equals(path) ? "" : path.substring(g.length());
                was = template.get(".git" + inside);
                if (inside.isEmpty()) was = is != null && is.kind().equals("dir") ? is : null;       // the new folder itself is the job's to make
            }
            if (was == null && is == null) continue;
            if (path.equals(".git") && was != null && is != null && was.kind().equals("dir") && (!is.kind().equals("dir") || is.ino() != was.ino())) {
                Map<String, Object> op = new LinkedHashMap<>();
                op.put("op", "swapback"); op.put("path", ".git"); op.put("ino", was.ino()); op.put("dev", was.dev());
                writes.add(0, op);
                changes.add(new String[]{".git", "the project's .git is not the folder it was when the job began"});
                jobs.put(".git", is);
                continue;
            }
            if (was != null && was.same(is)) { if (fresh) newStart.put(path, is); continue; }        // a new repository's part as git init writes it
            // ordinary work, found by trial; a folder by itself runs nothing (what is put in it is looked at on its own)
            if (path.endsWith("/info/refs")) { newStart.put(path, is); continue; }
            if (was == null && is != null && is.kind().equals("dir")) { newStart.put(path, is); continue; }
            // a new .git file in a folder of the project (a linked worktree's, a submodule's): the folder it names is a git folder looked at on its own
            if (was == null && is != null && is.kind().equals("file") && (path.equals(".git") || path.endsWith("/.git")) && now.dotgits().contains(path)) { newStart.put(path, is); continue; }
            if (was == null && is != null && path.contains("/hooks/") && path.endsWith(".sample") && is.kind().equals("file")) { newStart.put(path, is); continue; }
            if (isWorktreeAdmin(path, g) && was == null && is != null && worktreeAdminOk(path, is)) { newStart.put(path, is); continue; }
            if ((path.endsWith("/config") || path.equals("config")) && is != null && is.kind().equals("file") && (was == null || was.kind().equals("file")) && isSettings(path, gitdirs)) {
                Settings c = settings(path, was == null ? null : was.bytes(), is.bytes(), fresh, start);
                if (c.notAllowed().isEmpty()) { newStart.put(path, is); continue; }
                writes.add(op("write", path, c.desired(), was == null ? 0644 : was.mode()));
                for (String w : c.notAllowed()) changes.add(new String[]{path, w});
                jobs.put(path, is);
                newStart.put(path, new Entry("file", was == null ? 0644 : was.mode(), 0, 0, 0, c.desired().length, c.desired(), null, false));
                continue;
            }
            jobs.put(path, is);
            if (was == null) {
                if (!isUnderNew(path, jobs.keySet(), now)) removes.add(op("remove", path, null, 0));
                changes.add(new String[]{path, describeAdded(path, is)});
                newStart.remove(path);
            } else if (was.kind().equals("dir")) {
                if (is == null || !is.kind().equals("dir")) { dirs.add(op("mkdir", path, null, was.mode())); changes.add(new String[]{path, is == null ? "removed" : "replaced by a " + is.kind()}); }
            } else if (was.kind().equals("file") && !was.big()) {
                writes.add(op("write", path, was.bytes(), was.mode()));
                changes.add(new String[]{path, is == null ? "removed" : describeChanged(path, is)});
            } else if (was.kind().equals("link")) {
                Map<String, Object> o = op("link", path, null, 0);
                o.put("target", was.target());
                writes.add(o);
                changes.add(new String[]{path, is == null ? "removed" : "changed"});
            } else {
                changes.add(new String[]{path, "changed, and too large to put back"});
            }
        }
        if (changes.isEmpty()) {
            j.start = new State(newStart, gitdirs, now.dotgits(), start.top(), now.complete());
            j.signature = signature(project, j.start);
            return;
        }
        removes.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("path")).length()).reversed());
        dirs.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("path")).length()));
        List<Map<String, Object>> ops = new ArrayList<>();
        for (Map<String, Object> w : writes) if ("swapback".equals(w.get("op"))) ops.add(w);
        ops.addAll(removes);
        ops.addAll(dirs);
        for (Map<String, Object> w : writes) if (!"swapback".equals(w.get("op"))) ops.add(w);
        Map<String, String> failed = apply(project, ops);
        String copyDir = keepCopies(project, jobs);
        long at = System.currentTimeMillis();
        for (String[] c : changes) {
            String path = c[0];
            String words = failed.containsKey(path) ? c[1] + " (could not be put back: " + failed.get(path) + ")" : c[1];
            PutBack pb = new PutBack(at, project, path, words, copyDir == null ? null : copyDir + "/" + path, whileCommand, jobs.get(path));
            j.pending.add(pb);
            j.all.add(pb);
        }
        for (String path : failed.keySet()) if (!newStart.containsKey(path) && start.entries().containsKey(path)) newStart.put(path, start.entries().get(path));
        j.start = new State(newStart, gitdirs, now.dotgits(), start.top(), now.complete());
        j.signature = signature(project, j.start);
        // what could not be put back is still there, and its signature does not change by itself: the next command's look and the end's
        // read the places again rather than take that signature for "nothing new" (the poller does not, or it would try every 2 s)
        j.retry = !failed.isEmpty();
    }

    private static Map<String, Object> op(String kind, String path, byte[] bytes, int mode) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("op", kind);
        o.put("path", path);
        if (bytes != null) o.put("b64", Base64.getEncoder().encodeToString(bytes));
        if (mode != 0) o.put("mode", mode);
        return o;
    }

    /** Whether a new path lies under another new path that is removed whole (its own remove would be done twice). */
    private static boolean isUnderNew(String path, Set<String> taken, State now) {
        for (String t : taken) if (!t.equals(path) && path.startsWith(t + "/")) { Entry e = now.entries().get(t); if (e != null && e.kind().equals("dir")) return true; }
        return false;
    }

    /** The git folder a path belongs to: the longest one that holds it, or the path itself when it is one. */
    static String gitdirOf(String path, List<String> gitdirs, List<String> dotgits) {
        String best = null;
        for (String g : gitdirs) if ((path.equals(g) || path.startsWith(g + "/")) && (best == null || g.length() > best.length())) best = g;
        return best;
    }

    private static boolean isSettings(String path, List<String> gitdirs) {
        for (String g : gitdirs) if (path.equals(g + "/config")) return true;
        return false;
    }

    private static boolean isWorktreeAdmin(String path, String g) {
        return path.matches("(.*/)?worktrees/[^/]+") || path.matches("(.*/)?worktrees/[^/]+/(gitdir|commondir)");
    }

    /** A linked worktree's folder that {@code git worktree add} made: its commondir is "../..", its gitdir anything (a pointer back). */
    private static boolean worktreeAdminOk(String path, Entry is) {
        if (path.endsWith("/commondir")) return is.kind().equals("file") && is.bytes() != null && new String(is.bytes(), StandardCharsets.UTF_8).strip().equals("../..");
        if (path.endsWith("/gitdir")) return is.kind().equals("file");
        return is.kind().equals("dir");
    }

    // ── settings files, key by key ──────────────────────────────────────────────────────────────────────────────────

    /** A settings file put back: the bytes to write, and every change that is not git's own ordinary work, in words. */
    record Settings(byte[] desired, List<String> notAllowed) { }

    /** The keys {@code git init} writes, which a repository the job makes may hold with any value. */
    static final Set<String> INIT_KEYS = Set.of("core.repositoryformatversion", "core.filemode", "core.bare", "core.logallrefupdates", "core.symlinks",
            "core.ignorecase", "core.precomposeunicode", "extensions.objectformat", "extensions.refstorage");

    static Settings settings(String path, byte[] was, byte[] is, boolean fresh, State start) {
        byte[] base = was != null ? was : templateConfig();
        Map<String, List<String>> before = list(base), after = list(is);
        if (after == null) return new Settings(base, List.of("the settings file was made unreadable"));
        if (before == null) before = new LinkedHashMap<>();
        Set<String> remotes = new LinkedHashSet<>(List.of("."));
        for (String k : before.keySet()) if (k.startsWith("remote.") && k.endsWith(".url")) remotes.add(k.substring(7, k.length() - 4));
        List<String> notAllowed = new ArrayList<>();
        List<String[]> keep = new ArrayList<>();                         // key, value (null: unset)
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String k : keys) {
            List<String> b = before.getOrDefault(k, List.of()), a = after.getOrDefault(k, List.of());
            if (b.equals(a)) continue;
            if (ordinary(k, a, remotes) || (fresh && INIT_KEYS.contains(k) && a.size() <= 1)) { keep.add(new String[]{k, a.isEmpty() ? null : a.get(a.size() - 1)}); continue; }
            String shown = a.isEmpty() ? k + " (removed)" : k + " = " + GitRemotes.clean(a.get(a.size() - 1), 200) + (b.isEmpty() ? " (added)" : " (changed)");
            String what = explain(k, a.isEmpty() ? null : a.get(a.size() - 1));
            notAllowed.add(what == null ? shown : shown + ": " + what);
        }
        if (notAllowed.isEmpty()) return new Settings(is, notAllowed);
        byte[] desired = base;
        if (!keep.isEmpty()) {
            try {
                Path tmp = Files.createTempFile(GitRemotes.neutralDir().getParent(), "settings-", ".tmp");
                try {
                    Files.write(tmp, base);
                    for (String[] kv : keep) {
                        List<String> cmd = kv[1] == null ? List.of(GitRemotes.git(), "config", "--file", tmp.toString(), "--unset-all", kv[0])
                                : List.of(GitRemotes.git(), "config", "--file", tmp.toString(), "--replace-all", kv[0], kv[1]);
                        GitRemotes.run(cmd, GitRemotes.neutralDir(), GitRemotes.quietEnv(), 10);
                    }
                    desired = Files.readAllBytes(tmp);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            } catch (IOException e) {
                desired = base;
            }
        }
        return new Settings(desired, notAllowed);
    }

    /** What git writes in ordinary work: an upstream ({@code branch.<b>.remote} a remote the repository had or ".", {@code .merge} a branch, {@code .rebase} a mode). */
    static boolean ordinary(String key, List<String> values, Set<String> remotes) {
        if (!key.startsWith("branch.") || values.size() > 1) return false;
        String v = values.isEmpty() ? null : values.get(0);
        if (key.endsWith(".remote")) return v == null || remotes.contains(v);
        if (key.endsWith(".merge")) return v == null || v.matches("refs/heads/[^\\s]+");
        if (key.endsWith(".rebase")) return v == null || List.of("true", "false", "merges", "interactive", "i", "m").contains(v.toLowerCase(Locale.ROOT));
        return false;
    }

    /** A settings file's keys and values, as data ({@code git config --file … --list -z}, no includes); null when git cannot read it. */
    static Map<String, List<String>> list(byte[] content) {
        if (content == null) return new LinkedHashMap<>();
        try {
            Path tmp = Files.createTempFile(GitRemotes.neutralDir().getParent(), "settings-", ".tmp");
            try {
                Files.write(tmp, content);
                GitRemotes.Ran r = GitRemotes.run(List.of(GitRemotes.git(), "config", "--file", tmp.toString(), "--no-includes", "--list", "-z"), GitRemotes.neutralDir(), GitRemotes.quietEnv(), 10);
                if (r.exit() != 0) return null;
                Map<String, List<String>> out = new LinkedHashMap<>();
                for (String e : r.out().split("\0")) {
                    if (e.isEmpty()) continue;
                    int nl = e.indexOf('\n');
                    String k = (nl < 0 ? e : e.substring(0, nl)).toLowerCase(Locale.ROOT), v = nl < 0 ? "" : e.substring(nl + 1);
                    out.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
                }
                return out;
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            return null;
        }
    }

    // ── what git does with a change, in words ───────────────────────────────────────────────────────────────────────

    /** What git on this machine does with a setting that names a program or a place; null for one that does neither. */
    static String explain(String key, String value) {
        String k = key.toLowerCase(Locale.ROOT), v = value == null ? "" : GitRemotes.clean(value, 120);
        if (value == null) return null;
        if (k.equals("core.fsmonitor")) return "git will run " + v + " whenever you run git status, git diff, git commit and most other git commands here";
        if (k.equals("core.hookspath")) return "git will run the hooks in " + v + " in place of this repository's own, when you commit, merge, push or check out here";
        if (k.equals("core.sshcommand")) return "git will run " + v + " in place of ssh when you push to or fetch from an ssh address";
        if (k.equals("core.pager") || k.startsWith("pager.")) return "git will run " + v + " to show what it prints (git log, git diff)";
        if (k.equals("core.editor") || k.equals("sequence.editor")) return "git will run " + v + " when it opens an editor (a commit without -m, a rebase)";
        if (k.equals("core.askpass") || k.startsWith("credential.")) return "git will run " + v + " when it needs a password, and give it your credentials";
        if (k.equals("core.gitproxy")) return "git will run " + v + " to reach git:// addresses";
        if (k.startsWith("alias.")) return v.startsWith("!") ? "git " + k.substring(6) + " will run the shell command " + v.substring(1) : null;
        if (k.startsWith("filter.")) return "git will run " + v + " on the files the attributes give this filter, when you add, check out or diff them";
        if (k.startsWith("diff.") && (k.endsWith(".command") || k.endsWith(".textconv")) || k.startsWith("merge.") && k.endsWith(".driver") || k.equals("interactive.difffilter"))
            return "git will run " + v + " when it compares or merges files here";
        if (k.startsWith("gpg.")) return "git will run " + v + " when it signs or checks a signature";
        if (k.startsWith("hook.")) return k.endsWith(".command") ? "git will run " + v + " on the hook events its settings name" : "a hook event for a program in the settings";
        if (k.equals("include.path") || k.startsWith("includeif.")) return "git will also read the settings in " + v + ", which can name programs";
        if (k.startsWith("url.") && (k.endsWith(".insteadof") || k.endsWith(".pushinsteadof")))
            return "git will send what you fetch or push to addresses beginning " + v + " to " + k.substring(4, k.lastIndexOf('.')) + " instead";
        if (k.startsWith("remote.") && (k.endsWith(".url") || k.endsWith(".pushurl"))) return "git push and fetch for this remote will go to " + v;
        if (k.startsWith("remote.") && (k.endsWith(".uploadpack") || k.endsWith(".receivepack") || k.endsWith(".proxy"))) return "git will run or go through " + v + " for this remote";
        if (k.startsWith("submodule.") && k.endsWith(".update") && v.startsWith("!")) return "git submodule update will run the shell command " + v.substring(1);
        if (k.startsWith("submodule.") && k.endsWith(".url")) return "git submodule will fetch from " + v;
        if (k.equals("core.attributesfile")) return "git will read attributes, which can switch on filter programs, from " + v;
        if (k.equals("core.excludesfile")) return "git will leave out of git status what " + v + " lists";
        if (k.equals("core.worktree")) return "git will take " + v + " as this repository's working folder";
        if (k.startsWith("protocol.")) return "git will allow this way of reaching a remote (ext runs a command)";
        return null;
    }

    static String describeAdded(String path, Entry is) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (path.contains("/hooks/") && is != null && is.kind().equals("file")) return "a new hook: " + hookWhen(name);
        if (path.endsWith("/info/attributes")) return "attributes for this repository, which can make git run a filter or driver program";
        if (path.endsWith("/info/exclude")) return "files git status here leaves out";
        if (path.endsWith("/objects/info/alternates") || path.endsWith("/http-alternates")) return "git here will borrow objects from another place";
        if (path.contains("/remotes/") || path.contains("/branches/")) return "a remote's address for git fetch and push";
        if (path.endsWith("/commondir")) return "where git here finds this repository's settings and history";
        if (path.endsWith("/config.worktree")) return "settings for a working folder, which can name programs";
        if (path.endsWith("/hooks") && is != null && is.kind().equals("link")) return "the hooks folder is now a link to " + GitRemotes.clean(is.target(), 120);
        if (path.endsWith(".git") || path.equals(".git")) return "a repository in a folder of the project, whose settings git here would obey";
        return "added";
    }

    static String describeChanged(String path, Entry is) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (path.contains("/hooks/")) return "a hook changed: " + hookWhen(name);
        return describeAdded(path, is).replace("a new hook", "a hook").replace("added", "changed");
    }

    static String hookWhen(String name) {
        return switch (name) {
            case "pre-commit", "prepare-commit-msg", "commit-msg", "post-commit" -> "git will run it whenever you commit here";
            case "pre-push" -> "git will run it whenever you push from here";
            case "post-checkout" -> "git will run it whenever you check out or switch branches here";
            case "post-merge" -> "git will run it after every merge or pull here";
            case "pre-rebase", "post-rewrite" -> "git will run it when you rebase or amend here";
            case "reference-transaction" -> "git will run it whenever a branch or tag changes here";
            case "fsmonitor-watchman" -> "git will run it when the settings switch on a file monitor";
            case "pre-auto-gc" -> "git will run it when git tidies the repository";
            default -> name.endsWith(".sample") ? "a sample git does not run" : "git will run it when it reaches that step here";
        };
    }

    // ── putting back, keeping, asking ───────────────────────────────────────────────────────────────────────────────

    /** For tests: a program in place of the Python that puts back (one that answers nothing), null for the usual one. */
    static volatile String pythonForApplyTests;

    /**
     * Runs the operations through the helper in the sandbox; the paths that failed, with why. The helper is given the project's real
     * path, as the reading outside is: the sandbox's rules hold real paths. An answer that is not one result per operation (the helper
     * could not run, or stopped) fails every operation, with the helper's own last line: put back is said only of what was.
     */
    static Map<String, String> apply(Path project, List<Map<String, Object>> ops) {
        Map<String, String> failed = new LinkedHashMap<>();
        if (ops.isEmpty()) return failed;
        try {
            Path helper = helper();
            ProcessBuilder pb = Sandbox.in(project).fixed().read(helper).named("git settings guard (CodeZaiku)")
                    .command(pythonForApplyTests != null ? pythonForApplyTests : python(), "-I", "-S", helper.toString(), "apply", SandboxRules.real(project).toString());
            Process p = pb.start();
            byte[] in = JSON.writeValueAsBytes(ops);
            Thread.ofVirtual().start(() -> { try (var o = p.getOutputStream()) { o.write(in); } catch (IOException e) { /* ended */ } });
            StringBuilder err = new StringBuilder();
            Thread errs = Thread.ofVirtual().start(() -> { try (InputStream es = p.getErrorStream()) { err.append(new String(es.readAllBytes(), StandardCharsets.UTF_8)); } catch (IOException e) { /* ended */ } });
            byte[] out;
            try (InputStream is = p.getInputStream()) { out = is.readAllBytes(); }
            if (!p.waitFor(60, TimeUnit.SECONDS)) p.destroyForcibly();
            errs.join(2000);
            JsonNode r = out.length == 0 ? null : JSON.readTree(out);
            if (r == null || !r.isArray() || r.size() != ops.size()) {
                String last = "";
                for (String line : err.toString().split("\n")) if (!line.isBlank() && !line.contains("xcodebuild") && !line.contains("xcrun")) last = line.strip();
                String why = "the helper did not answer" + (last.isEmpty() ? "" : " (" + (last.length() > 200 ? last.substring(0, 200) + "…" : last) + ")");
                for (Map<String, Object> o : ops) failed.put(String.valueOf(o.get("path")), why);
                return failed;
            }
            for (JsonNode x : r) if (!x.path("ok").asBoolean()) failed.put(x.path("path").asText(), x.path("error").asText("?"));
        } catch (IOException e) {
            for (Map<String, Object> o : ops) failed.put(String.valueOf(o.get("path")), e.getMessage() == null ? "the helper did not run" : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return failed;
    }

    /** The job's versions of what was put back, kept whole under CodeZaiku's folder, never executable. The folder, or null. */
    private static String keepCopies(Path project, Map<String, Entry> jobs) {
        try {
            Path real = SandboxRules.real(project);
            String name = real.getFileName() == null ? "root" : real.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
            Path dir = Config.home().resolve("git-put-back").resolve(name + "-" + hash(real.toString()))
                    .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")));
            for (Map.Entry<String, Entry> e : jobs.entrySet()) {
                Entry v = e.getValue();
                Path to = dir.resolve(e.getKey()).normalize();
                if (!to.startsWith(dir)) continue;
                Files.createDirectories(to.getParent());
                if (v == null) Files.writeString(to.resolveSibling(to.getFileName() + ".removed-by-the-job"), "");
                else if (v.kind().equals("file") && v.bytes() != null) Files.write(to, v.bytes());
                else if (v.kind().equals("link")) Files.writeString(to.resolveSibling(to.getFileName() + ".link-to"), String.valueOf(v.target()));
                else if (v.kind().equals("dir")) Files.createDirectories(to);
            }
            return dir.toString();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A yes: the job's version of each change put back in place, and taken as the start. True when every one was. */
    private static boolean keep(Job j, List<PutBack> found) {
        synchronized (j) {
            List<Map<String, Object>> ops = new ArrayList<>();
            Map<String, Entry> start = new TreeMap<>(j.start.entries());
            Set<String> done = new LinkedHashSet<>();
            for (PutBack pb : found) {
                if (!done.add(pb.path())) continue;
                Entry v = pb.jobs();
                if (pb.path().equals(".git")) continue;                          // a swapped .git is not taken back on a yes
                if (v == null) { ops.add(op("remove", pb.path(), null, 0)); start.remove(pb.path()); }
                else if (v.kind().equals("file") && v.bytes() != null) { ops.add(op("write", pb.path(), v.bytes(), v.mode())); start.put(pb.path(), v); }
                else if (v.kind().equals("dir")) { ops.add(op("mkdir", pb.path(), null, v.mode())); start.put(pb.path(), v); }
                else if (v.kind().equals("link")) { Map<String, Object> o = op("link", pb.path(), null, 0); o.put("target", v.target()); ops.add(o); start.put(pb.path(), v); }
            }
            Map<String, String> failed = apply(j.project, ops);
            j.start = new State(start, j.start.gitdirs(), j.start.dotgits(), j.start.top(), j.start.complete());
            j.signature = signature(j.project, j.start);
            for (PutBack pb : found) if (pb.path().endsWith("/config") && (pb.change().startsWith("remote.") || pb.change().startsWith("url."))) GitRemotes.reread(j.project);
            return failed.isEmpty();
        }
    }

    /** The question in a chat: what was put back, what git would do with it, and what a yes does. */
    static List<String> question(List<PutBack> found) {
        List<String> q = new ArrayList<>();
        boolean during = found.stream().anyMatch(PutBack::whileCommand), between = found.stream().anyMatch(p -> !p.whileCommand());
        q.add("This project's own git settings were changed" + (during && !between ? " by a command of this chat" : between && !during
                ? " while no command of this chat was running (perhaps by you)" : ", during and between this chat's commands") + ". git on this machine obeys them "
                + "whenever you run it here, outside the sandbox, so CodeZaiku has put them back as they were:");
        for (PutBack p : found) q.add("  " + p.path() + ": " + p.change());
        String copy = found.get(0).copy();
        q.add("With a yes, CodeZaiku puts the changed version back in place, and git here will do what is said above. With a no they stay as they were."
                + (copy == null ? "" : " The changed version is kept in " + copy.substring(0, copy.length() - found.get(0).path().length() - 1) + " either way."));
        return q;
    }

    /** The note the model reads after the command. */
    static String note(List<PutBack> found, boolean kept) {
        LinkedHashSet<String> where = new LinkedHashSet<>();
        for (PutBack p : found) where.add(p.path() + (p.change().contains(" = ") || p.change().contains(" (removed)") ? " (" + p.change().split("[ :]")[0] + ")" : ""));
        if (kept) return "\n[note: the person kept the change to the repository's own settings (" + String.join(", ", where) + ").]";
        return "\n[note: CodeZaiku put back what changed in the repository's own git settings (" + String.join(", ", where) + "): those settings switch "
                + "programs on for git on this machine, which is the person's to decide. A hook or setting the project shares goes into a file of the "
                + "project that you commit (.githooks/, .pre-commit-config.yaml, .gitattributes); say in your summary what the person should switch on, and how.]";
    }

    /** What was put back since {@code sinceMillis}, for the job's last lines and the run's document. */
    public static List<PutBack> since(Path project, long sinceMillis) {
        Job j = project == null ? null : JOBS.get(key(project));
        if (j == null) return List.of();
        List<PutBack> out = new ArrayList<>();
        for (PutBack p : j.all) if (p.at() >= sinceMillis) out.add(p);
        return out;
    }

    /** The job's last lines; empty when nothing was put back. */
    public static List<String> lines(Path project, long sinceMillis) {
        List<PutBack> all = since(project, sinceMillis);
        if (all.isEmpty()) return List.of();
        return linesOf(all);
    }

    private static List<String> linesOf(List<PutBack> all) {
        List<String> out = new ArrayList<>();
        out.add("git settings: commands of this job changed this project's own git settings, which git on this machine obeys; CodeZaiku put back:");
        for (PutBack p : all) out.add("  " + p.path() + ": " + p.change());
        String copy = all.get(0).copy();
        if (copy != null) out.add("git settings: the changed versions are kept in " + copy.substring(0, copy.length() - all.get(0).path().length() - 1));
        return out;
    }

    /** For the run's document: {@code [{"path", "change", "copy"}]}, or null when nothing. */
    public static ArrayNode document(List<PutBack> all) {
        if (all == null || all.isEmpty()) return null;
        ArrayNode a = JSON.createArrayNode();
        for (PutBack p : all) {
            ObjectNode o = a.addObject();
            o.put("path", p.path()).put("change", p.change());
            if (p.copy() != null) o.put("copy", p.copy()); else o.putNull("copy");
        }
        return a;
    }

    // ── between runs ────────────────────────────────────────────────────────────────────────────────────────────────

    private static Path lastFile(Path project) {
        Path real = SandboxRules.real(project);
        return Config.home().resolve("git-guard").resolve(hash(real.toString()) + ".json");
    }

    /** What CodeZaiku left here, by path and digest, for the next job's start. */
    private static void remember(Path project, State s) {
        try {
            ObjectNode o = JSON.createObjectNode();
            ObjectNode e = o.putObject("entries");
            for (Map.Entry<String, Entry> x : s.entries().entrySet()) e.put(x.getKey(), digest(x.getValue()));
            Files.createDirectories(lastFile(project).getParent());
            Files.write(lastFile(project), JSON.writeValueAsBytes(o));
        } catch (IOException e) {
            // the next start says nothing of the time between
        }
    }

    /** A line for the first lines when the places changed since CodeZaiku last left them here; null when not, or when it never did. */
    private static String sinceLastTime(Path project, State now) {
        Path f = lastFile(project);
        if (!Files.isRegularFile(f)) return null;
        try {
            JsonNode was = JSON.readTree(Files.readAllBytes(f)).path("entries");
            List<String> changed = new ArrayList<>();
            Set<String> paths = new LinkedHashSet<>();
            was.fieldNames().forEachRemaining(paths::add);
            paths.addAll(now.entries().keySet());
            for (String p : paths) {
                String a = was.path(p).asText(null), b = now.entries().containsKey(p) ? digest(now.entries().get(p)) : null;
                if (a == null ? b != null : !a.equals(b)) changed.add(p);
            }
            if (changed.isEmpty()) return null;
            return "git settings: since CodeZaiku last worked here, " + String.join(", ", changed.subList(0, Math.min(8, changed.size())))
                    + (changed.size() > 8 ? " and " + (changed.size() - 8) + " more" : "") + " changed. CodeZaiku did not make these changes and takes them as yours; "
                    + "if you did not make them either, look at them before you run git here.";
        } catch (IOException e) {
            return null;
        }
    }

    private static String digest(Entry e) {
        if (e == null) return "-";
        String body = e.kind() + " " + (e.mode() & 0111) + " " + (e.bytes() != null ? HexFormat.of().formatHex(sha256(e.bytes())) : e.target() != null ? e.target() : e.size() + "/" + e.mtime());
        return e.kind().equals("dir") ? "dir" : body;
    }

    // ── what git init writes ────────────────────────────────────────────────────────────────────────────────────────

    private static volatile Map<String, Entry> template;

    /** What {@code git init} writes with nobody's settings, read once: the "as it was" of a repository a job makes. */
    static Map<String, Entry> template() {
        Map<String, Entry> t = template;
        if (t != null) return t;
        Map<String, Entry> out = new TreeMap<>();
        try {
            Path dir = Files.createTempDirectory(GitRemotes.neutralDir().getParent(), "init-");
            try {
                GitRemotes.run(List.of(GitRemotes.git(), "init", "-q", dir.resolve("t").toString()), GitRemotes.neutralDir(), GitRemotes.quietEnv(), 30);
                ProcessBuilder pb = new ProcessBuilder(python(), "-I", "-S", helper().toString(), "read", dir.resolve("t").toString()).redirectError(ProcessBuilder.Redirect.DISCARD);
                Process p = pb.start();
                byte[] o;
                try (InputStream in = p.getInputStream()) { o = in.readAllBytes(); }
                p.waitFor(30, TimeUnit.SECONDS);
                out.putAll(parse(JSON.readTree(o)).entries());
            } finally {
                try (var walk = Files.walk(dir)) { for (Path x : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x); }
            }
        } catch (IOException e) {
            // an empty one: everything a new repository holds is put back
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        template = out;
        return out;
    }

    static byte[] templateConfig() {
        Entry e = template().get(".git/config");
        return e == null || e.bytes() == null ? new byte[0] : e.bytes();
    }

    // ── small things ────────────────────────────────────────────────────────────────────────────────────────────────

    private static volatile Path helperFile;

    static Path helper() throws IOException {
        Path h = helperFile;
        if (h != null && Files.isRegularFile(h)) return h;
        byte[] content;
        try (InputStream in = GitDirGuard.class.getResourceAsStream("/sandbox/gitguard.py")) {
            if (in == null) throw new IOException("this build of CodeZaiku does not carry sandbox/gitguard.py");
            content = in.readAllBytes();
        }
        Path dir = Config.home().resolve("sandbox");
        Files.createDirectories(dir);
        Path f = dir.resolve("gitguard-" + HexFormat.of().formatHex(sha256(content)).substring(0, 16) + ".py");
        if (!Files.isRegularFile(f) || Files.size(f) != content.length) {
            Path part = Files.createTempFile(dir, "gitguard-", ".part");
            Files.write(part, content);
            Files.move(part, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        helperFile = f;
        return f;
    }

    /** The Python that runs the sandbox's own helper; python3 on PATH otherwise. */
    static String python() {
        Sandbox.Found f = Sandbox.found();
        if (f.kind() == Sandbox.Kind.LANDLOCK && f.launcher() != null) return f.launcher();
        for (String p : List.of("/usr/bin/python3", "/usr/local/bin/python3", "/opt/homebrew/bin/python3")) if (Files.isExecutable(Path.of(p))) return p;
        return "python3";
    }

    private static byte[] sha256(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); } catch (NoSuchAlgorithmException e) { return new byte[0]; }
    }

    private static String hash(String s) { return HexFormat.of().formatHex(sha256(s.getBytes(StandardCharsets.UTF_8))).substring(0, 12); }

    /** For tests: every job forgotten, its poller stopped. */
    public static void forgetForTests() {
        for (Job j : JOBS.values()) { j.active = false; if (j.poller != null) j.poller.interrupt(); }
        JOBS.clear();
    }
}
