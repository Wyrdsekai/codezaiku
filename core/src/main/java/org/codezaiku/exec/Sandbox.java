package org.codezaiku.exec;

import org.codezaiku.Config;
import org.codezaiku.tools.ProjectEnv;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The one way the coding loop starts a process: in an operating-system sandbox that opens the project folder and the
 * run's own folders, lets the command read the system and the toolchains in use, and closes the rest of the file
 * system to it.
 *
 * <p>The tools' path check cannot see a path a script builds while it runs: a {@code python3 -c} that joined the
 * pieces of a path listed a folder outside the project, and with a hosted model whatever a command prints goes to the
 * host. Only the operating system can close that. On Linux a small helper applies Landlock rules to itself and then
 * becomes the command ({@code sandbox/landlock.py}, 10 ms a command); on macOS the command runs under
 * {@code sandbox-exec} with a profile written from the same rules. Windows has no such thing a program can use
 * without administrator rights that Git Bash starts under, so there is none.
 *
 * <p>Where there is no sandbox, a job on a local model runs its commands as before and its first lines say they are
 * not sandboxed; a job on a hosted model does not run them at all ({@link #commandsRun}), and its first lines say why
 * and what to do.
 *
 * <p>The ops surface does not come through here: it operates a machine on purpose, behind its own guards.
 */
public final class Sandbox {

    private Sandbox() { }

    public static final String SETTING = "CODEZAIKU_SANDBOX";
    /** A folder of the person's choosing for package caches, shared by every project's sandbox. */
    public static final String CACHE = "CODEZAIKU_SANDBOX_CACHE";
    /** Variables that pass into the sandbox although their names look like credentials: names, comma-separated. */
    public static final String ENV = "CODEZAIKU_SANDBOX_ENV";
    /** More folders a sandboxed command may read, and may read and write: paths, comma-separated. */
    public static final String READ = "CODEZAIKU_SANDBOX_READ";
    public static final String WRITE = "CODEZAIKU_SANDBOX_WRITE";
    /** {@code open} lets a sandboxed command make UNIX-domain sockets on Linux; anything else keeps them closed. */
    public static final String SOCKETS = "CODEZAIKU_SANDBOX_SOCKETS";
    /**
     * A command's network: {@code auto} (closed for a hosted model, open for a local one), {@code closed} (through CodeZaiku's
     * recording proxy only, whatever the model), {@code open} (as before, whatever the model).
     */
    public static final String NETWORK = "CODEZAIKU_SANDBOX_NETWORK";
    /** More hosts a command may reach through the proxy where its network is closed: {@code host}, {@code host:port}, {@code *.example.com}. */
    public static final String HOSTS = "CODEZAIKU_SANDBOX_HOSTS";
    /**
     * macOS, where a command's network is closed: {@code open} (the default) lets a command listen for connections, so a test's server
     * works and a server a command starts on every address can be reached from the network; {@code closed} refuses listening.
     */
    public static final String LISTEN = "CODEZAIKU_SANDBOX_LISTEN";
    /** {@code off} takes the path of a machine that gives no user namespace, for trying that path on one that does. Not a setting for people. */
    static final String NETNS = "CODEZAIKU_SANDBOX_NETNS";

    public enum Kind { LANDLOCK, SEATBELT, NONE }

    /**
     * What this machine gives. {@code why}: for NONE, the reason in words a person reads. {@code level}: the Landlock
     * version of the kernel. {@code socketFilter}: whether UNIX-domain sockets can be closed here too. {@code launcher}:
     * the program that applies the sandbox (the Python that runs the helper, or sandbox-exec). {@code netns}: whether a
     * command can be given a network of its own (a user and a network namespace).
     */
    public record Found(Kind kind, String why, int level, boolean socketFilter, String launcher, boolean netns) {
        public Found(Kind kind, String why, int level, boolean socketFilter, String launcher) { this(kind, why, level, socketFilter, launcher, false); }
        public boolean on() { return kind != Kind.NONE; }
    }

    /**
     * How a command reaches the network. OPEN: as the machine allows. NAMESPACE: the project's own network, loopback only, and
     * CodeZaiku's proxy. PORT: no namespace here, so TCP to the proxy's port only (of any address: a narrow door, not a closed
     * one). NONE: no network at all, where Landlock has no network rules either. LOOPBACK: macOS, whose sandbox gives a command no
     * network of its own: this Mac's own addresses only (its services, the job's servers, CodeZaiku's proxy), no other machine and no
     * name lookups.
     */
    public enum Net { OPEN, NAMESPACE, PORT, NONE, LOOPBACK }

    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final String SEATBELT = "/usr/bin/sandbox-exec";

    private static final String OFF = "it is switched off in the settings (sandbox = off)";

    private static volatile Found probed;
    private static volatile Found forTests;

    /** For tests: the state every caller sees from here on; null returns to what the settings and the machine say. */
    public static void forTests(Found f) { forTests = f; }

    /** The sandbox in force: the machine's, or none when the settings switch it off. */
    public static Found found() {
        Found t = forTests;
        if (t != null) return t;
        String v = Config.get(SETTING, "on").strip().toLowerCase(Locale.ROOT);
        if (v.equals("off") || v.equals("false") || v.equals("no") || v.equals("0"))
            return new Found(Kind.NONE, OFF, 0, false, null);
        return probe();
    }

    /** What the machine gives, whatever the settings say; asked once. */
    public static Found probe() {
        Found f = probed;
        if (f == null) {
            synchronized (Sandbox.class) {
                if (probed == null) probed = look();
                f = probed;
            }
        }
        return f;
    }

    private static Found look() {
        if (WINDOWS) return none("Windows has no sandbox that a program can apply without administrator rights and that Git Bash starts under");
        if (MAC) {
            if (!Files.isExecutable(Path.of(SEATBELT))) return none("this macOS has no " + SEATBELT);
            Ran r = run(List.of(SEATBELT, "-p", "(version 1)(allow default)", "/usr/bin/true"), 10);
            return r.exit() == 0 ? new Found(Kind.SEATBELT, null, 0, true, SEATBELT)
                    : none(SEATBELT + " did not run a command (" + oneLine(r.out()) + ")");
        }
        Path helper;
        try { helper = helper(); } catch (IOException e) { return none("its helper could not be written (" + e.getMessage() + ")"); }
        for (String python : new String[]{"/usr/bin/python3", "/usr/local/bin/python3", "/bin/python3", "python3"}) {
            if (python.startsWith("/") && !Files.isExecutable(Path.of(python))) continue;
            Ran r = run(List.of(python, "-I", "-S", helper.toString(), "--probe"), 10);
            String out = r.out().strip();
            if (!out.startsWith("landlock=")) continue;       // not a Python that can run the helper: the next one
            int level = 0;
            try { level = Integer.parseInt(out.substring("landlock=".length()).split("\\s+")[0]); } catch (NumberFormatException e) { /* 0 */ }
            if (level < 1) return none("this kernel has no Landlock (Linux 5.13 and later have it, where it is not switched off)");
            boolean netns = out.contains("netns=yes") && !Config.get(NETNS, "on").strip().equalsIgnoreCase("off");
            return new Found(Kind.LANDLOCK, null, level, out.contains("filter=yes"), python, netns);
        }
        return none("setting up Landlock needs python3, and none here could run the helper");
    }

    private static Found none(String why) { return new Found(Kind.NONE, why, 0, false, null); }

    // ── the job's model ─────────────────────────────────────────────────────────────────────────────────────────────

    private static final Map<String, Boolean> HOSTED = new ConcurrentHashMap<>();

    /**
     * Said by a job when it has chosen its model: whether what the project's commands print goes to a hosted API.
     * Asked again by every launch for that project, so a chat that switches models is held to the model it is on.
     */
    public static void model(Path project, boolean hosted) {
        if (project != null) HOSTED.put(key(project), hosted);
        NetProxy.began(project);                 // the project's git remotes as they are now: one the job adds later lets nothing in
        if (project != null) WANTED.computeIfAbsent(key(project), k -> wantedNow(project));   // what the first command fetches, as the files are now
        GitRemotes.began(project);               // where a push or a fetch of the job goes: the remotes' addresses as they are now
        GitDirGuard.began(project);              // the repository's own settings, hooks and info as they are now: what a command changes is put back
    }

    private static boolean hosted(Path project) {
        return project != null && Boolean.TRUE.equals(HOSTED.get(key(project)));
    }

    /** How a command of this project reaches the network: by the setting, the job's model and what the machine gives. */
    public static Net net(Path project) {
        Found f = found();
        if (!f.on()) return Net.OPEN;                                    // no sandbox: no closing either
        String v = Config.get(NETWORK, "auto").strip().toLowerCase(Locale.ROOT);
        boolean closed = v.equals("closed") || (!v.equals("open") && hosted(project));
        if (!closed) return Net.OPEN;
        if (f.kind() == Kind.SEATBELT) return Net.LOOPBACK;
        if (f.netns()) return Net.NAMESPACE;
        return f.level() >= 4 ? Net.PORT : Net.NONE;
    }

    /** macOS, on a closed network: whether a command may listen for connections ({@link #LISTEN}; open unless the setting says closed). */
    static boolean listens() {
        return !Config.get(LISTEN, "open").strip().equalsIgnoreCase("closed");
    }

    /** The line a job prints at its start about its commands' network, where the machine sandboxes them. */
    static String netLine(Path project) {
        Found f = found();
        Net n = net(project);
        String letIn = " To let a host in: codezaiku config set sandbox.hosts <host>." + remotesLine(project);
        return switch (n) {
            case LOOPBACK -> "sandbox: network closed to the outside. A command reaches the package registries (PyPI, npm, Maven Central, Gradle, "
                    + "crates.io, Go modules) through CodeZaiku, and the hosts you named in the task or the chat; CodeZaiku writes down every "
                    + "address a command tries. Nothing else outside this Mac is reachable: no other address, no name lookups, no git over ssh. "
                    + "On macOS a command still reaches every service of this Mac (anything listening on 127.0.0.1 or on another of its "
                    + "addresses, such as a local model server or a database). "
                    + (listens()
                       ? "A command may start a server, and while it listens on every address, anyone on a network that reaches this Mac can "
                         + "connect to it; codezaiku config set sandbox.listen closed refuses listening (a test that starts a server then fails)."
                       : "A command may not listen for connections (sandbox.listen is closed), so a test that starts a server fails here.")
                    + letIn;
            case NAMESPACE -> "sandbox: network closed. A command reaches the package registries (PyPI, npm, Maven Central, Gradle, crates.io, "
                    + "Go modules) through CodeZaiku, and the hosts you named in the task or the chat; CodeZaiku "
                    + "writes down every address a command tries, and servers a command starts on 127.0.0.1 are reachable from the next. Nothing "
                    + "else is reachable: no other address, no name lookups, no git over ssh." + letIn;
            case PORT -> "sandbox: network narrowed, not closed. " + whyNoNetwork() + " So a command may still connect to one port, the one "
                    + "CodeZaiku listens on, of any address. Through it a command reaches the package registries and the hosts you named, and "
                    + "CodeZaiku writes down every address tried. A Gradle build and a test that starts a server on 127.0.0.1 fail here, and names "
                    + "are not looked up." + letIn + " " + toCloseIt();
            case NONE -> "sandbox: network closed, and the package registries cannot be reached either: this machine gives neither a network "
                    + "of its own nor network rules (Landlock version " + f.level() + ", network rules came with version 4). Install packages "
                    + "before the job, or use a local model.";
            // a local model's job says nothing new: its commands run as they always have
            case OPEN -> f.on() && hosted(project)
                    ? "sandbox: network open: a command reaches the internet and this machine's ports, because sandbox.network is open in the "
                      + "settings. With the model a hosted one, what a command sends can be anything it read."
                    : null;
        };
    }

    /** What a project's first command on a closed network fetches, from its files as they were when the job began. */
    private record Wanted(String gradleSettings, UvPython.Wanted python) { }

    private static final Map<String, Wanted> WANTED = new ConcurrentHashMap<>();

    private static Wanted wantedNow(Path project) { return new Wanted(GradleDistribution.settingsOf(project), UvPython.wanted(project)); }

    /**
     * Where its commands' network is closed: what the project's toolchains would download from a host that is not let in, got by
     * CodeZaiku before the job's first command (the Gradle distribution the wrapper names, {@link GradleDistribution}; the Python a
     * uv project asks for, {@link UvPython}). The lines to print; empty when nothing was done.
     */
    static List<String> prepareFirstDownloads(Path project) {
        List<String> said = new ArrayList<>();
        if (project == null || net(project) == Net.OPEN) return said;
        Wanted w = WANTED.computeIfAbsent(key(project), k -> wantedNow(project));
        try {
            Folders at = folders(project);
            String gradle = GradleDistribution.prepareFrom(w.gradleSettings(), at.cache().resolve("gradle"));
            if (gradle != null) said.add(gradle);
            if (w.python() != null) {
                Map<String, String> env = SandboxEnv.build(loginEnv(), at, keptNames(), homes());
                String uv = UvPython.prepare(w.python(), env, List.of(SandboxRules.real(at.dir()), SandboxRules.real(at.cache()), SandboxRules.real(at.home())), UvPython.REAL);
                if (uv != null) said.add(uv);
            }
        } catch (IOException | RuntimeException e) {
            said.add("sandbox: the run's own folders could not be made (" + e.getMessage() + ").");
        }
        return said;
    }

    private static final Map<String, Consumer<String>> SAY = new ConcurrentHashMap<>();
    private static final Set<String> FIRST_DONE = ConcurrentHashMap.newKeySet();

    /** Where a job prints a line about its commands (the Gradle fetch): set when the job begins, null takes it away. */
    public static void say(Path project, Consumer<String> sink) {
        if (project == null) return;
        if (sink == null) SAY.remove(key(project)); else SAY.put(key(project), sink);
    }

    /**
     * Said by what runs a command of a job (the shell tool, a background task, the project's tests, the boot check) just before it
     * starts one: on the project's first command with its network closed, the Gradle distribution is fetched, once per project in
     * this process. A job that runs no command fetches nothing.
     */
    public static void firstCommand(Path project) {
        if (project == null || !found().on() || net(project) == Net.OPEN || !FIRST_DONE.add(key(project))) return;
        Consumer<String> sink = SAY.get(key(project));
        for (String line : prepareFirstDownloads(project)) if (sink != null) sink.accept(line);
    }

    /** For tests: the next command of every project is its first again, and its files are read again. */
    static void firstCommandForTests() { FIRST_DONE.clear(); WANTED.clear(); }

    /** The project's git remotes, which are not let in: named, with the setting that lets one in. Empty when it has none over https. */
    static String remotesLine(Path project) {
        if (project == null) return "";
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        for (NetRules.HostPort r : NetProxy.rules(project).remotes()) hosts.add(r.port() == 443 || r.port() == 80 ? r.host() : r.host() + ":" + r.port());
        if (hosts.isEmpty()) return "";
        return " This project's git remotes (" + String.join(", ", hosts) + ") are not let in until you name " + (hosts.size() == 1 ? "it" : "them") + " there.";
    }

    /** Where the machine's own settings are read: for tests, another folder. */
    static volatile Path procSys = Path.of("/proc/sys");

    private static String sysctl(String name) {
        try { return Files.readString(procSys.resolve(name.replace('.', '/'))).strip(); } catch (IOException | RuntimeException e) { return null; }
    }

    /** Why this machine gives a command no network of its own, in a sentence. */
    static String whyNoNetwork() {
        if (Config.get(NETNS, "on").strip().equalsIgnoreCase("off")) return "User namespaces are switched off for CodeZaiku here (sandbox.netns = off).";
        if ("1".equals(sysctl("kernel.apparmor_restrict_unprivileged_userns")))
            return "This system restricts user namespaces through AppArmor (kernel.apparmor_restrict_unprivileged_userns = 1).";
        if ("0".equals(sysctl("user.max_user_namespaces"))) return "This system allows no user namespaces (user.max_user_namespaces = 0).";
        if ("0".equals(sysctl("kernel.unprivileged_userns_clone"))) return "This kernel keeps user namespaces from programs without privileges (kernel.unprivileged_userns_clone = 0).";
        return "This machine does not let a program make a network of its own (a user namespace could not be made).";
    }

    /**
     * How to get the closed network on such a machine, from documentation read on 2026-11-24, or a plain statement that none was found.
     * Ubuntu's specification of its restriction (discourse.ubuntu.com/t/37626) names the file and the value; the kernel's documentation
     * of user.max_user_namespaces says what the limit is.
     */
    static String toCloseIt() {
        if (Config.get(NETNS, "on").strip().equalsIgnoreCase("off")) return "To close it: codezaiku config unset sandbox.netns.";
        if ("1".equals(sysctl("kernel.apparmor_restrict_unprivileged_userns")))
            return "To close it, an administrator turns the restriction off as Ubuntu's specification of it says "
                    + "(discourse.ubuntu.com/t/37626): kernel.apparmor_restrict_unprivileged_userns = 0 in /etc/sysctl.d/20-apparmor.conf. "
                    + "That takes away a hardening Ubuntu ships, for every program.";
        if ("0".equals(sysctl("user.max_user_namespaces")))
            return "To close it, an administrator raises user.max_user_namespaces above 0 (the most user namespaces a user may create: "
                    + "docs.kernel.org/admin-guide/sysctl/user.html).";
        return "No documentation read for CodeZaiku says how to allow them on this system, so no command is given.";
    }

    private static String key(Path project) { return SandboxRules.real(project).toString(); }

    /**
     * Whether this job runs commands at all: yes in a sandbox, yes on a local model, no for a hosted model with no
     * sandbox, since a command could then read any file of the person's and its output would go to the host.
     */
    public static boolean commandsRun(Path project) {
        return found().on() || !hosted(project);
    }

    /** Why this job runs no commands, for the model that calls one anyway and for the tool's own error. */
    public static String whyNoCommands() {
        return "commands are not run in this job: there is no sandbox for them (" + found().why() + ") and the model is a hosted one, "
                + "so a command could read files outside the project and what it printed would go to the host. Work with the tools "
                + "that read and write the project's files, and say in your summary which commands the person should run.";
    }

    /** The lines a job prints at its start about how its commands run. */
    public static List<String> lines(Path project, boolean hosted) {
        Found f = found();
        List<String> out = new ArrayList<>();
        if (f.on()) {
            Folders at = null;
            try { at = folders(project); } catch (IOException e) { /* said by the first command */ }
            String own = at == null ? "its own folders" : "its own folders (" + at.dir() + ", " + at.tmp() + ")";
            out.add("sandbox: on (" + (f.kind() == Kind.LANDLOCK ? "Landlock" : "sandbox-exec") + "). A command this job runs reads and writes "
                    + SandboxRules.real(project) + " and " + own + ", reads the system's and the toolchains' folders, and is refused the rest of "
                    + "this machine's files. Keys and tokens in the environment are not passed to it.");
            boolean socketsOpen = f.kind() == Kind.LANDLOCK && !socketsClosed(f);
            if (socketsOpen) {
                out.add("sandbox: local socket services stay reachable from a command (the desktop session's bus, the Docker socket, anything that "
                        + "listens on a socket file): " + (f.socketFilter() ? "sandbox.sockets is open in the settings." : "this processor type has no filter for them."));
            }
            if (f.kind() == Kind.SEATBELT) {
                // found on macOS 26: no rule of the profile stops a program from asking the system for another program's arguments
                // and environment, when both run under the same account; launchctl print shows a launchd job's environment too (2026-11-25)
                List<String> keys = new ArrayList<>();
                for (Map.Entry<String, String> e : System.getenv().entrySet())
                    if (SandboxEnv.looksLikeCredential(e.getKey(), e.getValue())) keys.add(e.getKey());
                out.add("sandbox: on macOS a command can still read the arguments and the environment of the other programs you are running, and of the "
                        + "services launchd runs for you. "
                        + "A key in a settings file is out of its reach; a key exported in a running program's environment is not"
                        + (keys.isEmpty() ? "." : ", and the environment this program was started from holds " + keys.size() + " that look like one ("
                        + String.join(", ", keys.subList(0, Math.min(4, keys.size()))) + (keys.size() > 4 ? ", …" : "") + ")."));
            }
            boolean holdsHome = false;
            if (project != null) for (Path home : homes()) if (home.startsWith(SandboxRules.real(project))) holdsHome = true;
            if (holdsHome) {
                out.add("sandbox: the project folder is your home folder or holds it, so the sandbox leaves all of it open. Run from the project's own folder.");
            }
            String net = netLine(project);
            if (net != null) out.add(net);
            out.addAll(GitDirGuard.startLines(project));
        } else if (hosted) {
            out.add("sandbox: none (" + f.why() + "). The model is a hosted one, so this job runs no commands: the shell and the other tools that "
                    + "run a program are not offered, and the project's tests are not run. Without a sandbox a command could read any file of "
                    + "yours, and what it printed would go to the host.");
            out.add("sandbox: to have commands, " + whatToDo() + ", or use a local model.");
        } else {
            out.add("sandbox: none (" + f.why() + "). The commands this job runs are not sandboxed: they can read and write whatever your account can.");
        }
        return out;
    }

    /** How a person gets a sandbox where this job has none. */
    public static String whatToDo() {
        if (OFF.equals(found().why())) return "switch the sandbox back on (codezaiku config set sandbox on)";
        if (WINDOWS) return "run CodeZaiku inside WSL2, where Linux sandboxes them";
        return "run on Linux 5.13 or later with python3, or on macOS, where they are sandboxed";
    }

    /** One line for the doctor. */
    public static String describe() {
        Found f = found();
        if (!f.on()) return "none (" + f.why() + ")";
        if (f.kind() == Kind.SEATBELT) return "sandbox-exec; a closed network reaches this Mac's own services and CodeZaiku's proxy, not the outside"
                + (listens() ? ", and a command may listen for connections (sandbox.listen open)" : ", and a command may not listen for connections (sandbox.listen closed)");
        return "Landlock, version " + f.level() + (socketsClosed(f) ? ", UNIX sockets closed" : ", UNIX sockets open")
                + (f.netns() ? ", a network of its own for a command" : f.level() >= 4 ? ", no user namespaces here: a closed network is narrowed to one port"
                   : ", no user namespaces and no network rules here: a closed network is no network");
    }

    private static boolean socketsClosed(Found f) {
        return f.socketFilter() && !Config.get(SOCKETS, "closed").strip().equalsIgnoreCase("open");
    }

    // ── the run's own folders ───────────────────────────────────────────────────────────────────────────────────────

    /**
     * Where a project's sandboxed commands keep what is theirs. {@code dir}: the project's folder under CodeZaiku's
     * home (the one its installs already go to). {@code home}: their HOME. {@code tmp}: their temp folder, under the
     * system's so that its path stays short enough for a socket file's 104 characters. {@code cache}: the package
     * caches, in {@code dir} unless the person set {@code sandbox.cache}.
     */
    public record Folders(Path dir, Path home, Path tmp, Path cache) { }

    /** The folders for a project, made if they are not there. */
    public static Folders folders(Path project) throws IOException {
        Path root = project == null ? Path.of(".") : project;
        Path dir = ProjectEnv.dirFor(root);
        Path home = dir.resolve("home");
        Files.createDirectories(home);
        Path named = dir.resolve(ProjectEnv.PROJECT_FILE);
        if (!Files.exists(named)) Files.writeString(named, root.toAbsolutePath().normalize().toString(), StandardCharsets.UTF_8);
        String chosen = Config.get(CACHE);
        Path cache = chosen == null || chosen.isBlank() ? dir.resolve("cache") : Path.of(expand(chosen.strip()));
        Files.createDirectories(cache);
        if (chosen != null && !chosen.isBlank()) linkMavenInto(home, cache);
        return new Folders(dir, home, tmpFor(dir), cache);
    }

    /** Maven finds its repository under the home folder alone, so with a cache folder of the person's the home's {@code .m2} is a link into it. */
    private static void linkMavenInto(Path home, Path cache) {
        try {
            Path m2 = home.resolve(".m2");
            if (Files.exists(m2, LinkOption.NOFOLLOW_LINKS)) return;
            Path target = cache.resolve("m2");
            Files.createDirectories(target);
            Files.createSymbolicLink(m2, target);
        } catch (IOException | UnsupportedOperationException e) {
            // Maven then keeps its repository in the run's home
        }
    }

    /** A folder of our own under the system's temp folder, private to this account; the run's own folder when that cannot be had. */
    private static Path tmpFor(Path dir) throws IOException {
        Path fallback = dir.resolve("tmp");
        try {
            Path base = Path.of(System.getProperty("java.io.tmpdir", "/tmp"));
            Path tmp = base.resolve("cz-" + hash(System.getProperty("user.name", "") + "\n" + dir));
            if (!Files.exists(tmp, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.getFileStore(base).supportsFileAttributeView("posix"))
                    Files.createDirectory(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                else Files.createDirectory(tmp);
            }
            // another account could have made a folder or a link of that name first: ours is a real folder that we own
            if (Files.isDirectory(tmp, LinkOption.NOFOLLOW_LINKS) && Files.isWritable(tmp)
                    && Files.getOwner(tmp, LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(dir))) return tmp;
        } catch (IOException | RuntimeException e) {
            // the fallback below
        }
        Files.createDirectories(fallback);
        return fallback;
    }

    /**
     * The person's home folders: the one this program keeps its own files under, and the account's own as the login
     * shell names it. They are one folder except where the program was started with a home of its own.
     */
    static List<Path> homes() {
        List<Path> out = new ArrayList<>();
        out.add(SandboxRules.real(Path.of(System.getProperty("user.home", "."))));
        String login = loginEnv().get("HOME");
        if (login != null && !login.isBlank()) {
            try {
                Path p = SandboxRules.real(Path.of(login));
                if (p.isAbsolute() && !out.contains(p)) out.add(p);
            } catch (RuntimeException e) {
                // not a path
            }
        }
        return out;
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static String expand(String path) {
        String home = System.getProperty("user.home", "");
        return path.equals("~") ? home : path.startsWith("~/") ? home + path.substring(1) : path;
    }

    private static List<Path> listed(String setting) {
        List<Path> out = new ArrayList<>();
        String v = Config.get(setting);
        if (v == null || v.isBlank()) return out;
        for (String s : v.split("[," + File.pathSeparator + "]")) {
            if (s.isBlank()) continue;
            try { out.add(Path.of(expand(s.strip()))); } catch (RuntimeException e) { /* not a path */ }
        }
        return out;
    }

    private static Set<String> keptNames() {
        Set<String> out = new LinkedHashSet<>();
        String v = Config.get(ENV);
        if (v != null) for (String s : v.split("[,\\s]+")) if (!s.isBlank()) out.add(s.strip());
        return out;
    }

    // ── the environment the person's login shell gives ──────────────────────────────────────────────────────────────

    private static volatile Map<String, String> login;
    private static volatile Map<String, String> loginForTests;

    /** For tests: the environment sandboxed commands start from, in place of this machine's login shell's. */
    public static void loginForTests(Map<String, String> env) { loginForTests = env; }

    /**
     * The environment of the person's login shell, read once. A sandboxed command cannot read the person's profile,
     * which is where a toolchain's folder is put on PATH, so the profile is read here, outside, and the command runs
     * in a plain shell with the result. That is also quicker: a login shell took 51 ms to start on the machine this
     * was measured on, the helper 10.
     */
    static Map<String, String> loginEnv() {
        Map<String, String> t = loginForTests;
        if (t != null) return t;
        Map<String, String> env = login;
        if (env == null) {
            synchronized (Sandbox.class) {
                if (login == null) login = readLoginEnv();
                env = login;
            }
        }
        return env;
    }

    private static Map<String, String> readLoginEnv() {
        for (String printer : new String[]{"env -0", "perl -e 'print \"$_=$ENV{$_}\\0\" for keys %ENV'"}) {
            try {
                Process p = Shell.pb(printer).redirectError(ProcessBuilder.Redirect.DISCARD).redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null"))).start();
                byte[] out;
                try (InputStream in = p.getInputStream()) { out = in.readAllBytes(); }
                if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); continue; }
                if (p.exitValue() != 0) continue;
                Map<String, String> env = new LinkedHashMap<>();
                // a profile that prints something does so before the list: the list is the NUL-separated part
                for (String pair : new String(out, StandardCharsets.UTF_8).split("\0")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0 && pair.substring(0, eq).matches("[A-Za-z_][A-Za-z0-9_]*")) env.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
                if (env.containsKey("PATH")) return env;
            } catch (IOException e) {
                // the next way
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new LinkedHashMap<>(System.getenv());
    }

    // ── the launcher ────────────────────────────────────────────────────────────────────────────────────────────────

    /** A launch in {@code project}: the folder the command starts in, reads and writes. */
    public static Launch in(Path project) {
        return new Launch(project);
    }

    /** One command about to start. What a caller adds here is added to the sandbox's rules before the command is built. */
    public static final class Launch {
        private final Path project;
        private final List<Path> read = new ArrayList<>(), write = new ArrayList<>();
        private Consumer<Map<String, String>> edit;
        private boolean fixed;
        private String named;

        private Launch(Path project) { this.project = project == null ? Path.of(".").toAbsolutePath().normalize() : project; }

        /** Folders or files this one command may also read: a grader's script beside the project, a program's own files. */
        public Launch read(Path... more) { read.addAll(Arrays.asList(more)); return this; }

        /** Folders this one command may also write. */
        public Launch write(Path... more) { write.addAll(Arrays.asList(more)); return this; }

        /**
         * Changes to the command's environment, made before the rules are worked out: a folder a caller puts on PATH
         * here is opened for reading like the rest of PATH.
         */
        public Launch env(Consumer<Map<String, String>> change) { edit = edit == null ? change : edit.andThen(change); return this; }

        /**
         * For a command of the harness's own whose words no model and no file of the project shapes, and which only
         * reads (the search behind search_code, a look for a program on PATH): it runs in the sandbox where there is
         * one, and where there is none it runs for a hosted model too.
         */
        public Launch fixed() { fixed = true; return this; }

        /** The command as the network's record names it, where the script run holds more (the shell tool's prelude before the model's words). */
        public Launch named(String text) { named = text; return this; }

        /** {@code script} through the shell, as the tools run a command. */
        public ProcessBuilder shell(String script) throws IOException {
            Found f = found();
            if (!f.on()) {
                refuseIfHosted();
                ProcessBuilder pb = Shell.pb(script).directory(project.toFile());
                if (edit != null) edit.accept(pb.environment());
                return pb;
            }
            return sandboxed(f, List.of(Shell.command(), "-c", script), named != null ? named : script);
        }

        /** A program and its arguments, with no shell between. */
        public ProcessBuilder command(List<String> argv) throws IOException {
            Found f = found();
            if (!f.on()) {
                refuseIfHosted();
                ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(argv)).directory(project.toFile());
                if (edit != null) edit.accept(pb.environment());
                return pb;
            }
            return sandboxed(f, argv, named != null ? named : String.join(" ", argv));
        }

        public ProcessBuilder command(String... argv) throws IOException { return command(Arrays.asList(argv)); }

        private void refuseIfHosted() throws IOException {
            if (!fixed && hosted(project)) throw new IOException(whyNoCommands());
        }

        /** The rules this launch would run under; for the tests, and for a caller that says what they are. */
        public SandboxRules rules() throws IOException {
            Folders at = folders(project);
            return rules(found(), at, environment(at));
        }

        private Map<String, String> environment(Folders at) {
            Map<String, String> env = SandboxEnv.build(loginEnv(), at, keptNames(), homes());
            identityInto(env, identity(project));
            if (edit != null) edit.accept(env);
            return env;
        }

        private SandboxRules rules(Found f, Folders at, Map<String, String> env) {
            List<Path> r = new ArrayList<>(read);
            r.addAll(listed(READ));
            r.add(Path.of(System.getProperty("java.home")));         // the JDK this program runs on: a project's build may use it
            Path install = installDir();
            if (install != null) r.add(install);
            List<Path> w = new ArrayList<>(write);
            w.addAll(listed(WRITE));
            return SandboxRules.of(project, at, env, r, w, homes(), f.kind() == Kind.SEATBELT, socketsClosed(f));
        }

        private ProcessBuilder sandboxed(Found f, List<String> argv, String text) throws IOException {
            Folders at = folders(project);
            gitIdentity(project, at.home());
            Map<String, String> env = environment(at);
            SandboxRules rules = rules(f, at, env);
            Net n = net(project);
            List<String> cmd = new ArrayList<>();
            if (f.kind() == Kind.LANDLOCK) {
                cmd.addAll(List.of(f.launcher(), "-I", "-S", helper().toString()));
                List<String> args = new ArrayList<>(rules.landlockArgs());
                args.addAll(args.size() - 1, network(f, n, at, env, text));   // before the "--" that ends the options
                cmd.addAll(args);
            } else {
                network(f, n, at, env, text);
                SandboxRules.Network profileNet = n != Net.LOOPBACK ? SandboxRules.Network.OPEN : listens() ? SandboxRules.Network.CLOSED : SandboxRules.Network.CLOSED_NO_LISTEN;
                cmd.addAll(List.of(f.launcher(), "-f", profile(rules, profileNet).toString()));
            }
            cmd.addAll(argv);
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(project.toFile());
            pb.environment().clear();
            pb.environment().putAll(env);
            return pb;
        }

        /**
         * The helper's options for the command's network, and its environment's proxy variables. Where the network is closed and
         * the project's keeper (on macOS: the proxy's listener for the project) cannot be had, the command is not run: an
         * IOException, never a command left on the open network.
         */
        private List<String> network(Found f, Net n, Folders at, Map<String, String> env, String text) throws IOException {
            if (n == Net.OPEN) return List.of();
            if (n == Net.NONE) return List.of("--no-inet");
            NetProxy proxy;
            NetProxy.Job job;
            try {
                proxy = NetProxy.get();
                job = n == Net.LOOPBACK ? proxy.listener(project) : proxy.keeper(project, f.launcher(), helper(), n == Net.PORT);
            } catch (IOException | RuntimeException e) {
                throw new IOException("the command was not run: its network is closed, and the way through CodeZaiku could not be set up ("
                        + e.getMessage() + ")", e);
            }
            if (n == Net.LOOPBACK) movedSaid(project, job);
            SandboxEnv.proxied(env, job.port(), proxy.register(project, text));
            SandboxEnv.mavenSettings(at.home(), job.port());
            if (n == Net.LOOPBACK) SandboxEnv.ipv4ForJava(env);   // macOS refuses Java's IPv4-mapped form of 127.0.0.1
            if (n == Net.NAMESPACE) return List.of("--net-join", job.pid() + ":" + job.inode());
            return n == Net.PORT ? List.of("--net-port", String.valueOf(job.port())) : List.of();
        }
    }

    private static final Set<String> MOVED_SAID = ConcurrentHashMap.newKeySet();

    /**
     * macOS: once per project and port in this process, the line that says the project's proxy port was taken and which one is used. A
     * Gradle daemon left running from an earlier session went on downloading through the new port (a trial, 2026-11-25: Gradle gives
     * each build the Java settings of the client it starts with, which carry the port); a program that read the address from its
     * environment when it started cannot.
     */
    private static void movedSaid(Path project, NetProxy.Job job) {
        if (job.port() == job.preferred() || !MOVED_SAID.add(key(project) + ":" + job.port())) return;
        Consumer<String> sink = SAY.get(key(project));
        if (sink != null) sink.accept(movedLine(job.preferred(), job.port()));
    }

    static String movedLine(int preferred, int port) {
        return "sandbox: this project's port for CodeZaiku's proxy, " + preferred + ", is taken by another program, so its commands use " + port
                + " this time. A Gradle daemon a command left running takes the new port with its next build; any other program a command left "
                + "running keeps the address it was started with, and has to be started again to download anything.";
    }

    /**
     * Who may let a refused host in while a job runs: the person in a chat, asked once per host ({@code ChatConsent}). A yes lasts
     * until the asker is taken away ({@link #asker(Path, Asker)} with null), which a chat does when it ends.
     */
    public interface Asker {
        /** Whether the person lets {@code host} on {@code port} in; {@code command} is the one that asked, {@code remote} whether it is a git remote of the project. */
        boolean letIn(String host, int port, String command, boolean remote);
    }

    private record Asking(Asker asker, Set<String> asked, List<NetRules.HostPort> letIn) { }

    private static final Map<String, Asking> ASKERS = new ConcurrentHashMap<>();

    /** The chat's asker for a project; null takes it away and lets out every host it let in. */
    public static void asker(Path project, Asker a) {
        if (project == null) return;
        Asking old = a == null ? ASKERS.remove(key(project)) : ASKERS.put(key(project), new Asking(a, ConcurrentHashMap.newKeySet(), new ArrayList<>()));
        if (old != null) synchronized (old.letIn()) { for (NetRules.HostPort h : old.letIn()) NetProxy.rules(project).letOut(h.host(), h.port()); }
    }

    /**
     * What the proxy refused this project's commands since {@code sinceMillis}, as one note for the model; empty when nothing. Where a
     * chat asks the person, each refused host is put to them first, once, and a yes is said in the note: the command can run again.
     */
    public static String refusedNote(Path project, long sinceMillis) {
        LinkedHashMap<String, NetProxy.Try> refused = new LinkedHashMap<>();
        for (NetProxy.Try t : NetProxy.tries(project, sinceMillis)) if (!t.let()) refused.putIfAbsent(t.host() + ":" + t.port(), t);
        if (refused.isEmpty()) return "";
        NetRules rules = NetProxy.rules(project);
        Asking asking = ASKERS.get(key(project));
        // a git remote, where the job has the git tools: CodeZaiku pushes and fetches there itself with the person's credentials, which a
        // command lacks even with the host let in, so the person is not asked to open it to the commands
        boolean gitTools = GitOutside.offered(project, GitOutside.personAsks(project));
        List<String> nowIn = new ArrayList<>(), still = new ArrayList<>(), viaTools = new ArrayList<>();
        for (Map.Entry<String, NetProxy.Try> e : refused.entrySet()) {
            NetProxy.Try t = e.getValue();
            boolean remote = rules.remote(t.host(), t.port());
            if (remote && gitTools) { viaTools.add(e.getKey()); continue; }
            boolean yes = false;
            if (asking != null && asking.asked().add(e.getKey())) {
                try { yes = asking.asker().letIn(t.host(), t.port(), t.command(), remote); } catch (RuntimeException ex) { yes = false; }
                if (yes) {
                    rules.letIn(t.host(), t.port());
                    synchronized (asking.letIn()) { asking.letIn().add(new NetRules.HostPort(NetRules.norm(t.host()), t.port())); }
                }
            }
            if (yes) nowIn.add(e.getKey());
            else still.add(e.getKey() + (remote ? " (this project's git remote)" : ""));
        }
        StringBuilder b = new StringBuilder("\n[note: this job's commands reach the network through CodeZaiku only, which lets through the "
                + "package registries and the hosts the person named.");
        if (!nowIn.isEmpty()) b.append(" The person let ").append(String.join(", ", nowIn)).append(" in for this chat: run the command again.");
        if (!still.isEmpty()) b.append(" It refused ").append(String.join(", ", still)).append(". That stays so for this job: work with what is "
                + "reachable, and if the task needs one of these hosts, say so in your summary; the person can let it in with the "
                + "sandbox.hosts setting.");
        if (!viaTools.isEmpty()) b.append(" ").append(String.join(", ", viaTools)).append(viaTools.size() == 1 ? " is" : " are")
                .append(" this project's git remote: CodeZaiku reaches it itself, with git_fetch").append(GitOutside.personAsks(project) || GitOutside.pushesUnasked() ? " and git_push" : "")
                .append(".");
        return b.append("]").toString();
    }

    /** The lines a job prints at its end about what its commands were refused; empty when nothing was. */
    public static List<String> netLines(Path project, long sinceMillis) {
        List<NetProxy.Try> refused = new ArrayList<>();
        for (NetProxy.Try t : NetProxy.tries(project, sinceMillis)) if (!t.let()) refused.add(t);
        if (refused.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        out.add("network: commands of this job tried " + refused.size() + " connection" + (refused.size() == 1 ? "" : "s")
                + " that were refused (written in " + NetProxy.recordFile(project) + "):");
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (NetProxy.Try t : refused) {
            String line = "  " + t.host() + ":" + t.port() + " (" + t.rule() + "), by: " + oneLine(t.command());
            if (seen.add(line) && seen.size() <= 10) out.add(line);
        }
        if (seen.size() > 10) out.add("  and " + (seen.size() - 10) + " more");
        out.add("network: to let a host in: codezaiku config set sandbox.hosts <host>");
        return out;
    }

    private static List<String> refusedHosts(Path project, long sinceMillis) {
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        for (NetProxy.Try t : NetProxy.tries(project, sinceMillis)) if (!t.let()) hosts.add(t.host() + ":" + t.port());
        return new ArrayList<>(hosts);
    }

    // ── the person's git identity ───────────────────────────────────────────────────────────────────────────────────

    private static final Map<String, String[]> IDENTITY = new ConcurrentHashMap<>();
    private static final Set<String> IDENTITY_WRITTEN = ConcurrentHashMap.newKeySet();

    /**
     * The person's name and address for a commit, as git reads them outside the sandbox for this project (the project's own setting
     * first, then the person's), written into git's per-user settings file of the run's own home ({@code $XDG_CONFIG_HOME/git/config},
     * the home's {@code .gitconfig} left to the job): so {@code git commit} works in a sandboxed command as it did before the sandbox,
     * and {@code git config user.name} answers there too, which four variables in the environment alone would not; the variables are
     * passed as well ({@link #identityInto}), so that a command's own {@code -c user.name=} does not win. Nothing else of the
     * person's git settings is passed: no credential helper, no signing key, no address rewrites. Where the person set no identity
     * none is written, and git's own message stands.
     *
     * <p>The same file empties git's list of credential helpers ({@code credential.helper} set to the empty string: gitcredentials(7),
     * "If credential.helper is configured to the empty string, this resets the helper list to empty"). git reads it after the system's
     * settings (git-config(1) FILES: the system file, then {@code $XDG_CONFIG_HOME/git/config}, "last value found taking precedence"),
     * so no helper of the system's is asked from inside the sandbox, where a command has no credential to give or to get: on macOS the
     * settings that come with Apple's git name the keychain's helper, and git handed it the proxy's credentials after every fetch
     * through the proxy ("failed to store: -50", the sandbox refusing the keychain). The person's own settings are not read inside at
     * all (the command's home is the run's). Written once per project and home in this process.
     */
    static void gitIdentity(Path project, Path runHome) {
        String[] id = identity(project);
        Path file = runHome.resolve(".config/git/config");
        String done = file + "\n" + (id == null ? "" : id[0] + "\n" + id[1]);
        if (!IDENTITY_WRITTEN.add(done)) return;
        try {
            Files.createDirectories(file.getParent());
            List<List<String>> writes = new ArrayList<>();
            writes.add(List.of("--replace-all", "credential.helper", ""));
            if (id != null) for (String[] kv : new String[][]{{"user.name", id[0]}, {"user.email", id[1]}}) if (!kv[1].isEmpty()) writes.add(List.of(kv[0], kv[1]));
            for (List<String> w : writes) {
                List<String> cmd = new ArrayList<>(List.of("git", "config", "--file", file.toString()));
                cmd.addAll(w);
                if (run(cmd, 10).exit() != 0) { IDENTITY_WRITTEN.remove(done); return; }
            }
        } catch (IOException | RuntimeException e) {
            IDENTITY_WRITTEN.remove(done);
        }
    }

    /** The person's name and address for the project, as git reads them outside ({@link #gitIdentity}); null when neither is set. Read once per project and home. */
    static String[] identity(Path project) {
        Map<String, String> login = loginEnv();
        String home = login.get("HOME");
        if (project == null || home == null || home.isBlank()) return null;
        String[] id = IDENTITY.computeIfAbsent(key(project) + "\n" + home, k -> new String[]{gitConfig(project, login, "user.name"), gitConfig(project, login, "user.email")});
        return id[0].isEmpty() && id[1].isEmpty() ? null : id;
    }

    /**
     * The person's identity in the four variables git reads before any setting: git-commit's "COMMIT INFORMATION" takes the author and
     * committer from GIT_AUTHOR_NAME, GIT_AUTHOR_EMAIL, GIT_COMMITTER_NAME and GIT_COMMITTER_EMAIL first, and git-config's user.* says
     * "All of these can be overridden by" them. So a command's {@code git -c user.name=Fixer commit} still commits as the person (a
     * hosted model wrote exactly that on 2026-11-24), while {@code --author=}, which a person may ask for, still sets the author as git
     * says it does. A variable the person's own environment already sets stays theirs; where they set no identity nothing is passed.
     */
    static void identityInto(Map<String, String> env, String[] id) {
        if (id == null) return;
        if (!id[0].isEmpty()) { env.putIfAbsent("GIT_AUTHOR_NAME", id[0]); env.putIfAbsent("GIT_COMMITTER_NAME", id[0]); }
        if (!id[1].isEmpty()) { env.putIfAbsent("GIT_AUTHOR_EMAIL", id[1]); env.putIfAbsent("GIT_COMMITTER_EMAIL", id[1]); }
    }

    /**
     * One value of git's settings for the project, read outside the sandbox with the person's own home: {@code git config --get} in
     * the project, which runs nothing of the project's; where git will not read the project (another account owns it), the person's
     * own setting alone. Empty when unset or git is not there.
     */
    private static String gitConfig(Path project, Map<String, String> login, String key) {
        for (List<String> cmd : List.of(List.of("git", "-C", project.toString(), "config", "--get", key), List.of("git", "config", "--global", "--get", key))) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
                Map<String, String> env = pb.environment();
                env.keySet().removeIf(k -> k.startsWith("GIT_") || k.equals("HOME") || k.equals("XDG_CONFIG_HOME"));
                env.put("HOME", login.get("HOME"));
                for (String k : List.of("XDG_CONFIG_HOME", "GIT_CONFIG_GLOBAL", "GIT_CONFIG_SYSTEM", "GIT_CONFIG_NOSYSTEM")) if (login.get(k) != null) env.put(k, login.get(k));
                Process p = pb.start();
                String out;
                try (InputStream in = p.getInputStream()) { out = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip(); }
                if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return ""; }
                if (p.exitValue() == 0) return out;
                if (p.exitValue() == 1) return "";            // read, and not set
            } catch (IOException e) {
                return "";                                    // no git here
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
        }
        return "";
    }

    /** For tests: the identity is read again. */
    static void gitIdentityForTests() { IDENTITY.clear(); IDENTITY_WRITTEN.clear(); }

    /** The folder CodeZaiku is installed in, when it runs from an install: its launcher and jars. */
    private static Path installDir() {
        try {
            Path code = Path.of(Sandbox.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path lib = Files.isRegularFile(code) ? code.getParent() : null;        // …/lib/core.jar
            return lib != null && lib.getParent() != null && lib.getFileName().toString().equals("lib") ? lib.getParent() : lib;
        } catch (Exception e) {
            return null;
        }
    }

    // ── the helper and the profile, written out ─────────────────────────────────────────────────────────────────────

    private static volatile Path helperFile;

    /** The Landlock helper, written once under a name made from its content, so a newer CodeZaiku never runs an older copy. */
    static Path helper() throws IOException {
        Path h = helperFile;
        if (h != null && Files.isRegularFile(h)) return h;
        byte[] content;
        try (InputStream in = Sandbox.class.getResourceAsStream("/sandbox/landlock.py")) {
            if (in == null) throw new IOException("this build of CodeZaiku does not carry sandbox/landlock.py");
            content = in.readAllBytes();
        }
        Path f = written("landlock-", ".py", content);
        helperFile = f;
        return f;
    }

    private static Path profile(SandboxRules rules, SandboxRules.Network net) throws IOException {
        return written("profile-", ".sb", rules.seatbeltProfile(net).getBytes(StandardCharsets.UTF_8));
    }

    private static Path written(String prefix, String suffix, byte[] content) throws IOException {
        Path dir = Config.home().resolve("sandbox");
        Files.createDirectories(dir);
        String name;
        try {
            name = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        Path f = dir.resolve(prefix + name + suffix);
        if (!Files.isRegularFile(f) || Files.size(f) != content.length) {
            Path part = Files.createTempFile(dir, prefix, ".part");
            Files.write(part, content);
            try { Files.move(part, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException e) { Files.deleteIfExists(part); if (!Files.isRegularFile(f)) throw e; }
        }
        return f;
    }

    // ── small things ────────────────────────────────────────────────────────────────────────────────────────────────

    private record Ran(int exit, String out) { }

    private static Ran run(List<String> cmd, int seconds) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null"))).start();
            String out;
            try (InputStream in = p.getInputStream()) { out = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) { p.destroyForcibly(); return new Ran(-1, out); }
            return new Ran(p.exitValue(), out);
        } catch (IOException e) {
            return new Ran(-1, e.getMessage() == null ? "" : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Ran(-1, "");
        }
    }

    private static String oneLine(String s) {
        String t = s == null ? "" : s.strip().replaceAll("\\s+", " ");
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }
}
