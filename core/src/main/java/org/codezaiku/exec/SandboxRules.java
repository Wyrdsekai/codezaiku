package org.codezaiku.exec;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a sandboxed command may reach: the folders it reads and writes, the folders it only reads and runs programs
 * from, and the device files it may use. Everything else on the file system answers "permission denied".
 *
 * <p>Read and write: the project folder, the run's own folders (its home, its caches, its temp folder), and for a
 * project that is a git worktree the repository its {@code .git} file points at, since every git command there writes
 * into it. Read only: the system's folders, and the toolchains in use, found from PATH and from the variables that
 * name a toolchain's home. A toolchain installed under the person's home is opened by its own folders only: for
 * {@code ~/tools/python/bin} on PATH that is {@code bin} and its neighbours {@code lib}, {@code share} and the like,
 * never the home around it. What a project needs beyond that the person names in {@code sandbox.read}.
 */
public record SandboxRules(List<Path> readWrite, List<Path> readOnly, List<Path> devices, boolean socketsClosed, List<Path> spelling) {

    static final List<String> SYSTEM_LINUX = List.of("/usr", "/bin", "/sbin", "/lib", "/lib32", "/lib64", "/libx32", "/etc", "/opt", "/sys",
            "/snap", "/nix", "/gnu", "/run/current-system", "/home/linuxbrew",
            // of /proc only what describes the machine: a process's own folder there cannot be opened apart from every other process's
            "/proc/meminfo", "/proc/cpuinfo", "/proc/stat", "/proc/loadavg", "/proc/uptime", "/proc/version", "/proc/filesystems",
            "/proc/devices", "/proc/vmstat", "/proc/sys", "/proc/driver");

    static final List<String> DEVICES_LINUX = List.of("/dev/null", "/dev/zero", "/dev/full", "/dev/random", "/dev/urandom", "/dev/tty",
            "/dev/ptmx", "/dev/pts", "/dev/dri");

    /** macOS: the folders every program starts from, and the ones the Xcode shims behind git, python3 and cc read. */
    static final List<String> SYSTEM_MAC = List.of("/usr", "/bin", "/sbin", "/System", "/Library/Java", "/Library/Developer", "/Library/Frameworks",
            "/Library/Apple", "/opt", "/private/etc", "/private/var/db/timezone", "/private/var/db/xcode_select_link", "/private/var/db/dyld",
            "/private/var/select", "/Applications/Xcode.app", "/Library/Preferences/com.apple.dt.Xcode.plist");

    static final List<String> DEVICES_MAC = List.of("/dev/null", "/dev/zero", "/dev/random", "/dev/urandom", "/dev/dtracehelper");

    /** Variables that name a toolchain's own folder. */
    static final List<String> HOMES = List.of("JAVA_HOME", "JDK_HOME", "GRADLE_HOME", "MAVEN_HOME", "M2_HOME", "ANT_HOME", "GOROOT", "CONDA_PREFIX",
            "VIRTUAL_ENV", "NODE_HOME", "DOTNET_ROOT", "ANDROID_HOME", "ANDROID_SDK_ROOT", "FLUTTER_ROOT");

    private static final Set<String> BIN_NAMES = Set.of("bin", "sbin", "shims", "condabin", "Scripts");

    /** The folders of an installed toolchain beside its {@code bin}. */
    private static final List<String> BESIDE_BIN = List.of("bin", "sbin", "lib", "lib32", "lib64", "libexec", "share", "include", "etc", "ssl",
            "conf", "jmods", "jre", "legal", "release", "man", "conda-meta", "condabin");

    /**
     * The rules for a command in {@code project}. {@code env} is the environment the command will have (its PATH and
     * toolchain variables are read); {@code moreRead} and {@code moreWrite} are what the caller and the person's
     * settings add; {@code homes} are the person's home folders (the account's, and the one this program was told to use
     * when that differs), which no rule made from PATH or a variable may open as a whole.
     */
    public static SandboxRules of(Path project, Sandbox.Folders folders, Map<String, String> env, Collection<Path> moreRead,
                                  Collection<Path> moreWrite, Collection<Path> homes, boolean mac, boolean socketsClosed) {
        List<Path> home = new ArrayList<>();
        for (Path h : homes) if (h != null && !home.contains(real(h))) home.add(real(h));
        LinkedHashSet<Path> rw = new LinkedHashSet<>();
        if (project != null) {
            rw.add(real(project));
            rw.addAll(gitDirs(project));
        }
        rw.add(real(folders.dir()));
        rw.add(real(folders.tmp()));
        rw.add(real(folders.cache()));
        if (!mac) rw.add(Path.of("/dev/shm"));               // POSIX semaphores and shared memory: Python's process pools make them
        for (Path p : moreWrite) if (p != null && Files.exists(p)) rw.add(real(p));

        LinkedHashSet<Path> ro = new LinkedHashSet<>();
        List<String> system = mac ? SYSTEM_MAC : SYSTEM_LINUX;
        // macOS matches a rule against the real path, so a link in the list (Xcode.app can be one) is followed first
        for (String s : system) if (Files.exists(Path.of(s))) ro.add(mac ? real(Path.of(s)) : Path.of(s));
        if (mac) {
            // git, python3 and cc are shims that run the tools of the Xcode the machine has selected, wherever that one is
            Path selected = real(Path.of("/private/var/db/xcode_select_link"));
            for (Path p = selected; p != null && p.getFileName() != null; p = p.getParent()) {
                if (p.getFileName().toString().endsWith(".app") && Files.isDirectory(p)) { ro.add(p); break; }
            }
        }
        if (!mac) {
            // name lookups: /etc/resolv.conf is a link out of /etc on most systems, and without it every download fails
            Path resolv = real(Path.of("/etc/resolv.conf"));
            if (!resolv.startsWith("/etc") && resolv.getParent() != null && Files.isRegularFile(resolv)) ro.add(resolv.getParent());
        }
        for (String dir : env.getOrDefault("PATH", "").split(File.pathSeparator)) addFromPath(ro, dir, home);
        for (String name : HOMES) addHome(ro, env.get(name), home);
        for (String[] manager : SandboxEnv.MANAGERS) addHome(ro, env.get(manager[0]), home);
        for (Path p : moreRead) if (p != null && Files.exists(p)) ro.add(real(p));

        LinkedHashSet<Path> dev = new LinkedHashSet<>();
        for (String d : mac ? DEVICES_MAC : DEVICES_LINUX) if (Files.exists(Path.of(d))) dev.add(Path.of(d));
        if (!mac) {
            try (var list = Files.list(Path.of("/dev"))) {
                // a graphics card for the project's own computing: the nodes its driver opens
                for (Path p : list.toList()) if (p.getFileName().toString().startsWith("nvidia")) dev.add(p);
            } catch (IOException e) {
                // no /dev to list: the fixed names stand
            }
        }
        List<Path> write = pruned(rw, List.of());
        // a project opened through a link (a folder of the person's reached by a link, as /var and /tmp are on macOS): the rules hold
        // real paths, and a command that names the project as it was opened walks the links on the way, which macOS's sandbox refuses
        // unless it may read their metadata (a trial: "deny(1) file-read-metadata <the link>", and a cd there "Not a directory")
        List<Path> spelling = new ArrayList<>();
        if (project != null) {
            Path given = project.toAbsolutePath().normalize();
            // each entry on the way as written and in its real folder: macOS names a link by the folder it lies in, resolved
            // ("deny(1) file-read-metadata /private/var/…/the-link" for a project opened as /var/…/the-link)
            if (!given.equals(real(project))) for (Path p = given; p != null && p.getParent() != null; p = p.getParent()) {
                spelling.add(p);
                Path in = real(p.getParent()).resolve(p.getFileName());
                if (!spelling.contains(in)) spelling.add(in);
            }
        }
        return new SandboxRules(write, pruned(ro, write), new ArrayList<>(dev), socketsClosed, spelling);
    }

    /** Whether a folder is a home or holds one: such a folder is never opened. */
    private static boolean holdsAHome(Path dir, List<Path> homes) {
        for (Path home : homes) if (home.startsWith(dir)) return true;
        return false;
    }

    private static boolean inAHome(Path dir, List<Path> homes) {
        for (Path home : homes) if (dir.startsWith(home)) return true;
        return false;
    }

    /** A PATH entry: the folder itself, and when it is a toolchain's {@code bin}, the toolchain's folders beside it. */
    private static void addFromPath(Set<Path> ro, String entry, List<Path> homes) {
        if (entry == null || entry.isBlank()) return;
        Path dir;
        try { dir = Path.of(entry); } catch (RuntimeException e) { return; }
        if (!dir.isAbsolute() || !Files.isDirectory(dir)) return;
        dir = real(dir);
        if (holdsAHome(dir, homes)) return;
        ro.add(dir);
        Path parent = dir.getParent();
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
        if (parent == null || parent.getParent() == null || !BIN_NAMES.contains(name)) return;
        if (holdsAHome(parent, homes)) return;
        if (!inAHome(parent, homes)) { ro.add(parent); return; }       // a toolchain outside the home: all of it
        boolean shared = false;                                        // ~/.local/share and ~/.local/etc hold the person's own data
        for (Path home : homes) if (parent.equals(home.resolve(".local"))) shared = true;
        for (String beside : BESIDE_BIN) {
            if (shared && (beside.equals("share") || beside.equals("etc"))) continue;
            Path p = parent.resolve(beside);
            if (Files.exists(p)) ro.add(real(p));
        }
    }

    /** A folder a variable names as a toolchain's home: all of it, unless it is the person's home or holds it. */
    private static void addHome(Set<Path> ro, String value, List<Path> homes) {
        if (value == null || value.isBlank()) return;
        Path dir;
        try { dir = Path.of(value); } catch (RuntimeException e) { return; }
        if (!dir.isAbsolute() || !Files.isDirectory(dir)) return;
        dir = real(dir);
        if (holdsAHome(dir, homes)) return;
        ro.add(dir);
    }

    /**
     * For a git worktree or a submodule, whose {@code .git} is a file: the folder it points at and the repository
     * that folder belongs to. Git keeps the worktree's index and HEAD in the first and the objects and branches in
     * the second, and writes both on a commit.
     */
    static List<Path> gitDirs(Path project) {
        List<Path> out = new ArrayList<>();
        Path dotGit = project.resolve(".git");
        try {
            if (!Files.isRegularFile(dotGit) || Files.size(dotGit) > 4096) return out;
            String text = Files.readString(dotGit, StandardCharsets.UTF_8).strip();
            if (!text.startsWith("gitdir:")) return out;
            Path gitDir = project.resolve(text.substring("gitdir:".length()).strip()).normalize();
            if (!Files.isDirectory(gitDir)) return out;
            out.add(real(gitDir));
            Path common = gitDir.resolve("commondir");
            if (Files.isRegularFile(common) && Files.size(common) <= 4096) {
                Path shared = gitDir.resolve(Files.readString(common, StandardCharsets.UTF_8).strip()).normalize();
                if (Files.isDirectory(shared)) out.add(real(shared));
            }
        } catch (IOException | RuntimeException e) {
            // not a worktree we can read: git in the sandbox will say so itself
        }
        return out;
    }

    /**
     * A path with the links in it followed, as the operating system's rules see it. For a path that is not there yet,
     * the real path of the nearest folder that is, and the rest as written: on macOS a temp folder is under
     * {@code /var}, a link to {@code /private/var}, and a file about to be made there has to compare as inside it.
     */
    static Path real(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        try { return abs.toRealPath(); } catch (IOException | RuntimeException e) { /* not there: below */ }
        Path there = abs.getParent();
        while (there != null && !Files.exists(there)) there = there.getParent();
        if (there == null) return abs;
        try { return there.toRealPath().resolve(there.relativize(abs)); } catch (IOException | RuntimeException e) { return abs; }
    }

    /** Without the paths that lie beneath another of the same list, or beneath one of {@code covering}. */
    private static List<Path> pruned(Collection<Path> paths, Collection<Path> covering) {
        List<Path> out = new ArrayList<>();
        for (Path p : paths) {
            boolean covered = false;
            for (Path other : paths) if (!other.equals(p) && p.startsWith(other)) { covered = true; break; }
            if (!covered) for (Path other : covering) if (p.startsWith(other)) { covered = true; break; }
            if (!covered) out.add(p);
        }
        return out;
    }

    /** Whether a path is one the command may write. */
    public boolean writes(Path p) {
        Path r = real(p);
        for (Path w : readWrite) if (r.startsWith(w)) return true;
        return false;
    }

    /** Whether a path is one the command may read. */
    public boolean reads(Path p) {
        if (writes(p)) return true;
        Path r = real(p);
        for (Path w : readOnly) if (r.startsWith(w)) return true;
        return false;
    }

    // ── Linux ───────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The rules as the Landlock helper's options, up to and including the {@code --} before the command. */
    public List<String> landlockArgs() {
        List<String> a = new ArrayList<>();
        for (Path p : readOnly) { a.add("--ro"); a.add(p.toString()); }
        for (Path p : readWrite) { a.add("--rw"); a.add(p.toString()); }
        for (Path p : devices) { a.add("--dev"); a.add(p.toString()); }
        if (socketsClosed) a.add("--no-unix");
        a.add("--fds");
        a.add("4096,65536");
        a.add("--");
        return a;
    }

    // ── macOS ───────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The rules as a profile for {@code sandbox-exec}: everything refused, then what is allowed. Each allowance below
     * was found on macOS 26 by taking it away and reading what stopped: reading "/" itself (every program aborts at
     * start without it), all of {@code sysctl-read} (the same), stat on the folders above the allowed ones and no
     * others (so {@code realpath} works and {@code test -e} on a file of the home does not), the name service for user
     * and group names, named semaphores for Python's process pools, the resolver's socket for name lookups, and a
     * terminal the command opens for itself. Of socket files only the run's own may be bound or connected, so the
     * Docker socket and the system's are refused. The network is open: {@link #seatbeltProfile(Network)} closes it.
     */
    public String seatbeltProfile() {
        return seatbeltProfile(Network.OPEN);
    }

    /**
     * How a profile leaves the network to a command. OPEN: every address, and name lookups. CLOSED: this Mac's own addresses only,
     * where CodeZaiku's proxy listens, and no name lookups; a command may listen for connections. CLOSED_NO_LISTEN: the same, and a
     * command may not listen.
     */
    public enum Network { OPEN, CLOSED, CLOSED_NO_LISTEN }

    /**
     * The profile with the network as {@code net} says. What the profile language can say was found on macOS 26.5 by trial: a host in
     * a rule is {@code *} or {@code localhost} only ("host must be * or localhost in network address"), and {@code localhost} is
     * every address of this Mac, its network addresses and 0.0.0.0 too, not the loopback alone; a port cannot be taken out of
     * {@code localhost:*} (a deny after the allow, before it, or a require-not inside it each left the port reachable). Listening
     * is {@code network-inbound} and binding a TCP port is not checked at all; a connection a listening command accepts is not
     * checked by where it comes from, so while a command may listen, a server it starts on every address can be reached from the
     * network. Closing the resolver's socket closes name lookups; {@code localhost} still resolves, from /etc/hosts.
     */
    public String seatbeltProfile(Network net) {
        StringBuilder s = new StringBuilder();
        s.append("(version 1)\n(deny default)\n\n");
        s.append("(allow process-fork)\n(allow signal (target same-sandbox))\n(allow process-info* (target same-sandbox))\n(allow sysctl-read)\n\n");
        s.append("(allow file-read* file-map-executable process-exec\n");
        for (Path p : readOnly) s.append("    (").append(Files.isDirectory(p) ? "subpath " : "literal ").append(quoted(p)).append(")\n");
        s.append(")\n\n(allow file-read* file-write* file-map-executable process-exec\n");
        for (Path p : readWrite) s.append("    (subpath ").append(quoted(p)).append(")\n");
        s.append(")\n\n(allow file-read-metadata\n");
        LinkedHashSet<Path> above = new LinkedHashSet<>();
        for (String link : List.of("/etc", "/tmp", "/var", "/private", "/private/var", "/private/tmp")) above.add(Path.of(link));
        List<Path> all = new ArrayList<>(readWrite);
        all.addAll(readOnly);
        for (Path p : all) for (Path a = p.getParent(); a != null && a.getParent() != null; a = a.getParent()) above.add(a);
        above.addAll(spelling);                                     // the project as it was opened, when that is through a link
        for (Path p : above) s.append("    (literal ").append(quoted(p)).append(")\n");
        s.append(")\n(allow file-read* (literal \"/dev\") (subpath \"/dev/fd\"))\n(allow file-read* (literal \"/\"))\n\n");
        s.append("(allow file-read* file-write-data file-ioctl\n");
        for (Path p : devices) s.append("    (literal ").append(quoted(p)).append(")\n");
        s.append(")\n\n");
        s.append("""
                (allow mach-lookup
                    (global-name "com.apple.system.opendirectoryd.libinfo")
                    (global-name "com.apple.system.notification_center"))
                (allow ipc-posix-shm-read-data (ipc-posix-name "apple.shm.notification_center"))
                (allow ipc-posix-sem)
                (allow sysctl-write (sysctl-name "kern.grade_cputype"))
                (allow file-read* (literal "/dev/autofs_nowait"))
                """);
        if (net == Network.OPEN) s.append("(allow network-outbound (literal \"/private/var/run/mDNSResponder\"))\n");
        s.append("""
                (allow pseudo-tty)
                (allow file-read* file-write* file-ioctl (literal "/dev/ptmx"))
                (allow file-read* file-write* file-ioctl (require-all (regex #"^/dev/ttys[0-9]+") (extension "com.apple.sandbox.pty")))

                """);
        if (net == Network.OPEN) {
            s.append("(allow network-outbound (remote ip))\n(allow network-inbound (local ip))\n(allow network-bind (local ip))\n");
        } else {
            s.append("; the network is closed: \"localhost\" is every address of this Mac, where CodeZaiku's proxy listens; no name lookups\n");
            s.append("(allow network-outbound (remote ip \"localhost:*\"))\n");
            if (net == Network.CLOSED) s.append("(allow network-inbound (local ip \"localhost:*\"))\n(allow network-bind (local ip \"localhost:*\"))\n");
            else s.append("; no listening for connections (sandbox.listen closed)\n");
        }
        s.append("(allow network-bind network-outbound network-inbound\n");
        for (Path p : readWrite) s.append("    (subpath ").append(quoted(p)).append(")\n");
        s.append(")\n");
        return s.toString();
    }

    /** A path as a string of the profile's language. */
    private static String quoted(Path p) {
        return "\"" + p.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
