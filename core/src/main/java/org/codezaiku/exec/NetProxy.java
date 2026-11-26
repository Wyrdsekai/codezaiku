package org.codezaiku.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.Config;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The recording proxy a sandboxed command's network goes through, where its network is closed: inside CodeZaiku's own
 * process. Each project has a keeper (the sandbox helper with {@code --net-keep}) that holds the project's network and
 * passes each connection a command makes to its port on 127.0.0.1 to this proxy, through a socket file outside every
 * folder a command can reach. The proxy reads the request's first lines, HTTP {@code CONNECT host:port} (a tunnel whose
 * encrypted traffic it does not read) or a plain HTTP request to an absolute address, decides by host and port
 * ({@link NetRules}), and writes down every try, let through or refused, with the command that made it. On macOS there is no
 * keeper: a command's sandbox there lets it reach this Mac's own addresses and nothing else, and this process listens on 127.0.0.1
 * for each project itself ({@link #listener}).
 */
public final class NetProxy {

    /** One try: when, which project, the command (or what is known of it), where, and what was decided by which rule. */
    public record Try(long at, Path project, String command, String host, int port, boolean let, String rule) { }

    /**
     * A project's network: its keeper, the port the keeper listens on, its network namespace's inode, and its rules. On macOS no
     * keeper: {@code listener} is this process's own socket on 127.0.0.1 for the project.
     */
    public static final class Job {
        final Path project;
        final NetRules rules;
        final String token;
        final List<Try> tries = new ArrayList<>();
        volatile Process keeper;
        volatile ServerSocketChannel listener;
        volatile int port;
        volatile int preferred;
        volatile long inode;
        volatile boolean host;

        Job(Path project, NetRules rules, String token) { this.project = project; this.rules = rules; this.token = token; }

        public int port() { return port; }
        /** macOS: the project's own port ({@link #portFor}); {@link #port()} differs from it when that one was taken. */
        public int preferred() { return preferred; }
        public long pid() { Process k = keeper; return k == null ? -1 : k.pid(); }
        public long inode() { return inode; }
        public NetRules rules() { return rules; }
        boolean alive(boolean wantHost) { Process k = keeper; return k != null && k.isAlive() && host == wantHost && port > 0; }
        boolean listening() { ServerSocketChannel l = listener; return l != null && l.isOpen() && port > 0; }

        /** Its keeper ended, or its listener closed. */
        void end() {
            Process k = keeper;
            if (k != null) k.destroy();
            ServerSocketChannel l = listener;
            if (l != null) try { l.close(); } catch (IOException e) { /* closed */ }
        }
    }

    private record Cmd(int id, Path project, String text) { }

    /** The proxy's port on the loopback of a project's own network. */
    public static final int PORT_INSIDE = 3128;

    /**
     * macOS: where a project's port for the proxy is taken from, below the ports the system hands out by itself (49152 and up), so
     * that it is the same every time CodeZaiku works in the project: a Gradle daemon a command left running keeps the port in its
     * settings, and finds the proxy there again.
     */
    static final int PORT_FIRST = 47000, PORT_COUNT = 1000;

    /** A project's own port on macOS: a fixed function of its folder (its real path) into the range above. */
    static int portFor(Path project) {
        int h = Integer.parseUnsignedInt(hash(key(project)).substring(0, 7), 16);
        return PORT_FIRST + h % PORT_COUNT;
    }

    private static volatile NetProxy running;
    private static final Map<String, NetRules> RULES = new ConcurrentHashMap<>();

    private volatile Path socket;
    private volatile ServerSocketChannel server;
    private final Map<String, Job> byToken = new ConcurrentHashMap<>();
    private final Map<String, Job> byProject = new ConcurrentHashMap<>();
    private final Map<Integer, Cmd> commands = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    private NetProxy() { }

    /** The proxy of this process, made on first use. */
    public static NetProxy get() throws IOException {
        NetProxy p = running;
        if (p != null) return p;
        synchronized (NetProxy.class) {
            if (running == null) running = new NetProxy();
            return running;
        }
    }

    /**
     * The socket file the keepers pass their connections through, opened at the first keeper: macOS has no keeper and opens
     * none (its proxy listens on the loopback, {@link #listener}).
     */
    private synchronized Path unixSocket() throws IOException {
        ServerSocketChannel had = server;
        if (had != null && had.isOpen()) return socket;
        Path path = socketPath();
        Files.deleteIfExists(path);
        ServerSocketChannel made = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        made.bind(UnixDomainSocketAddress.of(path));
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); } catch (IOException | UnsupportedOperationException e) { /* the folder is the person's own */ }
        path.toFile().deleteOnExit();
        socket = path;
        server = made;
        Thread.ofVirtual().name("net-proxy").start(() -> accept(made));
        return path;
    }

    /** Under CodeZaiku's own folder, which no command reaches; under the system's temp folder when that path is too long for a socket file. */
    private static Path socketPath() throws IOException {
        String name = "net-" + ProcessHandle.current().pid() + ".sock";
        Path dir = Config.home().resolve("sandbox");
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        if (p.toString().getBytes(StandardCharsets.UTF_8).length <= 100) return p;
        Path base = Path.of(System.getProperty("java.io.tmpdir", "/tmp"));
        Path own = base.resolve("cz-net-" + hash(System.getProperty("user.name", "") + "\n" + dir));
        if (!Files.exists(own, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(own, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (!Files.isDirectory(own, LinkOption.NOFOLLOW_LINKS) || !Files.getOwner(own, LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(dir)))
            throw new IOException("no folder of our own for the proxy's socket file: " + own);
        return own.resolve(name);
    }

    /** The keepers' socket file; null until the first keeper. */
    public Path socket() { return socket; }

    // ── projects, their rules and their keepers ─────────────────────────────────────────────────────────────────────

    /** The project's rules, read when the job begins ({@link Sandbox#model}); kept for this process. */
    static NetRules rules(Path project) {
        return RULES.computeIfAbsent(key(project), k -> NetRules.at(project));
    }

    /** Read the project's remotes now, if they were not read yet in this process. */
    static void began(Path project) { if (project != null) rules(project); }

    private static String key(Path project) { return SandboxRules.real(project).toString(); }

    /**
     * The project's keeper, started when there is none or it ended. {@code host}: no namespace, the keeper listens on this
     * machine's loopback (where the machine gives no user namespace).
     */
    public synchronized Job keeper(Path project, String python, Path helper, boolean host) throws IOException {
        String k = key(project);
        Job job = byProject.get(k);
        if (job != null && job.alive(host)) return job;
        if (job != null) job.end();
        String token = HexFormat.of().formatHex(bytes(12));
        job = new Job(project, rules(project), token);
        List<String> cmd = new ArrayList<>(List.of(python, "-I", "-S", helper.toString(), "--net-keep", unixSocket().toString(), token));
        // in a network of its own every port is free, so the proxy's address is the same for every project and every keeper: a Gradle
        // daemon or a Maven settings file that holds it stays right; on the machine's own loopback the kernel picks one
        if (host) cmd.add("--net-host"); else cmd.addAll(List.of("--net-port", String.valueOf(PORT_INSIDE)));
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String ready = readLine(p.getInputStream(), 10_000);
        String[] w = ready == null ? new String[0] : ready.strip().split("\\s+");
        if (w.length < 3 || !w[0].equals("ready")) {
            p.destroyForcibly();
            throw new IOException("the job's network could not be made" + (ready == null || ready.isBlank() ? "" : " (" + ready.strip() + ")"));
        }
        job.keeper = p;
        job.port = Integer.parseInt(w[1]);
        job.inode = Long.parseLong(w[2]);
        job.host = host;
        if (byProject.get(k) != null) byToken.remove(byProject.get(k).token);
        byProject.put(k, job);
        byToken.put(token, job);
        return job;
    }

    /**
     * The project's proxy where no keeper can be made: macOS, whose sandbox gives a command no network of its own and lets it reach
     * this Mac's own addresses. This process listens on 127.0.0.1 at the project's own port ({@link #portFor}), or the next free one
     * of the range when another program has it ({@link Job#preferred()} then says which it would have been), for as long as it runs,
     * and serves each connection with that project's rules: the project is known by the port, the command by the user name in its
     * proxy address, as through a keeper. Opened again when it was closed.
     */
    public synchronized Job listener(Path project) throws IOException {
        String k = key(project);
        Job job = byProject.get(k);
        if (job != null && job.listening()) return job;
        if (job != null) { job.end(); byToken.remove(job.token); }
        Job made = new Job(project, rules(project), HexFormat.of().formatHex(bytes(12)));
        int own = portFor(project);
        ServerSocketChannel l = null;
        for (int i = 0; i < PORT_COUNT && l == null; i++) {
            int p = PORT_FIRST + (own - PORT_FIRST + i) % PORT_COUNT;
            if (answers(p)) continue;                              // another program listens there, maybe on every address: not ours to take
            ServerSocketChannel c = ServerSocketChannel.open(StandardProtocolFamily.INET);
            try {
                c.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), p), 128);
                l = c;
            } catch (IOException e) {
                c.close();                                         // taken: the next one
            }
        }
        if (l == null) throw new IOException("every port from " + PORT_FIRST + " to " + (PORT_FIRST + PORT_COUNT - 1) + " on 127.0.0.1 is taken");
        made.listener = l;
        made.port = ((InetSocketAddress) l.getLocalAddress()).getPort();
        made.preferred = own;
        made.host = true;
        byProject.put(k, made);
        ServerSocketChannel bound = l;
        Thread.ofVirtual().name("net-proxy-" + made.port).start(() -> {
            while (bound.isOpen()) {
                try {
                    SocketChannel ch = bound.accept();
                    Thread.ofVirtual().name("net-proxy-conn").start(() -> serveFor(made, ch));
                } catch (IOException e) {
                    if (!bound.isOpen()) return;
                }
            }
        });
        return made;
    }

    /**
     * Whether something answers on 127.0.0.1 at this port. A bind can succeed on a port another program holds on every address (the
     * system lets a socket take one address of a port bound to all of them), and that program's own connections to 127.0.0.1 would
     * then come to the proxy; so a port where anything answers is taken.
     */
    private static boolean answers(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The process numbers of every project's keeper in this process: not a command of a job. */
    public static Set<Long> keeperPids() {
        NetProxy p = running;
        Set<Long> out = new HashSet<>();
        if (p != null) for (Job j : p.byProject.values()) { long pid = j.pid(); if (pid > 0) out.add(pid); }
        return out;
    }

    /** The keeper of a project, if one runs. */
    public Job job(Path project) { return byProject.get(key(project)); }

    /** Ends every keeper and closes every listener: for tests. */
    public synchronized void endKeepers() {
        for (Job j : byProject.values()) {
            j.end();
            if (j.keeper != null) try { j.keeper.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        byProject.clear();
        byToken.clear();
    }

    /** For tests: the keepers ended, the proxy closed and its socket file removed; the next use starts it again, under the home then in force. */
    public static void stopForTests() {
        synchronized (NetProxy.class) {
            NetProxy p = running;
            running = null;
            RULES.clear();
            if (p == null) return;
            p.endKeepers();
            ServerSocketChannel s = p.server;
            if (s != null) try { s.close(); } catch (IOException e) { /* closed */ }
            Path f = p.socket;
            if (f != null) try { Files.deleteIfExists(f); } catch (IOException e) { /* gone with its folder */ }
        }
    }

    /** A command about to start in a project: the number its proxy address carries, so its tries name it. */
    public int register(Path project, String text) {
        int id = ids.incrementAndGet();
        String t = text == null ? "" : text.strip();
        if (t.length() > 300) t = t.substring(0, 300) + "…";
        Cmd c = new Cmd(id, project, t);
        commands.put(id, c);
        last.put(key(project), c);
        if (commands.size() > 5000) commands.keySet().removeIf(n -> n < id - 4000);
        return id;
    }

    private final Map<String, Cmd> last = new ConcurrentHashMap<>();

    /** The tries of a project since {@code sinceMillis}, oldest first. */
    public static List<Try> tries(Path project, long sinceMillis) {
        NetProxy p = running;
        if (p == null || project == null) return List.of();
        Job j = p.byProject.get(key(project));
        if (j == null) return List.of();
        synchronized (j.tries) {
            List<Try> out = new ArrayList<>();
            for (Try t : j.tries) if (t.at() >= sinceMillis) out.add(t);
            return out;
        }
    }

    // ── the proxy ───────────────────────────────────────────────────────────────────────────────────────────────────

    private void accept(ServerSocketChannel on) {
        while (on.isOpen()) {
            try {
                SocketChannel ch = on.accept();
                Thread.ofVirtual().name("net-proxy-conn").start(() -> serve(ch));
            } catch (IOException e) {
                if (!on.isOpen()) return;
            }
        }
    }

    private void serve(SocketChannel ch) {
        try (ch) {
            InputStream in = new BufferedInputStream(Channels.newInputStream(ch), 16384);
            OutputStream out = Channels.newOutputStream(ch);
            String token = asciiLine(in, 64);
            Job job = token == null ? null : byToken.get(token.strip());
            if (job == null) return;                                   // not one of our keepers
            proxy(job, ch, in, out);
        } catch (IOException e) {
            // the connection ended
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A connection to a project's own listener (macOS): its project is the listener's, and no token comes first. */
    private void serveFor(Job job, SocketChannel ch) {
        try (ch) {
            proxy(job, ch, new BufferedInputStream(Channels.newInputStream(ch), 16384), Channels.newOutputStream(ch));
        } catch (IOException e) {
            // the connection ended
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One request of a project's command: read, decided by the project's rules, written down, and passed on or answered. */
    private void proxy(Job job, SocketChannel ch, InputStream in, OutputStream out) throws IOException, InterruptedException {
        Request r = Request.read(in);
        String who = r == null ? "a program of this project" : whoAsked(job, r.proxyAuth);
        if (r == null || r.host == null) {
            record(job, who, r == null ? "?" : String.valueOf(r.target), 0, false, "not a proxy request");
            answer(out, 400, "Bad Request", "codezaiku: this is CodeZaiku's proxy; it takes CONNECT host:port or a plain http:// address.");
            return;
        }
        NetRules.Decision d = job.rules.decide(r.host, r.port);
        if (!d.let()) {
            record(job, who, r.host, r.port, false, d.rule());
            answer(out, 403, "Not let in by CodeZaiku (sandbox.hosts)", refusal(r.host, r.port));
            return;
        }
        InetAddress to;
        try {
            to = usable(job.rules, r.host, r.port);
        } catch (UnknownHostException e) {
            record(job, who, r.host, r.port, false, "no such host");
            answer(out, 502, "No such host", "codezaiku: " + r.host + " was not found.");
            return;
        } catch (IOException e) {
            record(job, who, r.host, r.port, false, e.getMessage());
            answer(out, 403, "Not let in by CodeZaiku (sandbox.hosts)", "codezaiku: " + r.host + " leads to " + e.getMessage() + ".");
            return;
        }
        Socket up = new Socket();
        try {
            up.connect(new InetSocketAddress(to, r.port), 10_000);
        } catch (IOException e) {
            up.close();
            record(job, who, r.host, r.port, false, "did not answer");
            answer(out, 502, "Did not answer", "codezaiku: " + r.host + ":" + r.port + " did not answer.");
            return;
        }
        record(job, who, r.host, r.port, true, d.rule());
        try (up) {
            OutputStream upOut = up.getOutputStream();
            if (r.connect) {
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } else {
                upOut.write(r.originHead());
                upOut.flush();
            }
            Thread back = Thread.ofVirtual().start(() -> {
                try { up.getInputStream().transferTo(out); } catch (IOException e) { /* ended */ }
                try { ch.shutdownOutput(); } catch (IOException e) { /* closed */ }
            });
            try { in.transferTo(upOut); } catch (IOException e) { /* ended */ }
            try { up.shutdownOutput(); } catch (IOException e) { /* closed */ }
            back.join();
        }
    }

    /**
     * The command that made a connection: the one whose number the proxy address carried; for a program that sends no proxy
     * credentials (Java's properties carry none), the project's latest command, said as a likelihood.
     */
    private String whoAsked(Job job, String proxyAuth) {
        Integer id = idFrom(proxyAuth);
        Cmd c = id == null ? null : commands.get(id);
        if (c != null && key(c.project()).equals(key(job.project))) return c.text();
        Cmd latest = last.get(key(job.project));
        return latest == null ? "a program of this project that named no command"
                : "a program that named no command, most likely of the latest: " + latest.text();
    }

    static Integer idFrom(String proxyAuth) {
        if (proxyAuth == null) return null;
        String v = proxyAuth.strip();
        if (!v.regionMatches(true, 0, "Basic ", 0, 6)) return null;
        try {
            String userPass = new String(Base64.getDecoder().decode(v.substring(6).strip()), StandardCharsets.UTF_8);
            if (!userPass.startsWith("cz:")) return null;
            return Integer.parseInt(userPass.substring(3).strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The address to connect to: the first the name gives that is not this machine or the link, unless the person named the host. */
    private static InetAddress usable(NetRules rules, String host, int port) throws IOException {
        InetAddress[] all = InetAddress.getAllByName(NetRules.norm(host));
        boolean named = rules.named(host, port);
        String why = null;
        for (InetAddress a : all) {
            String no = NetRules.unusable(a);
            if (no == null || named) return a;
            why = no;
        }
        throw new IOException(why == null ? "no address" : why);
    }

    static String refusal(String host, int port) {
        return "codezaiku: " + host + ":" + port + " is not among the addresses this job's commands may reach. Commands reach the package "
                + "registries and the hosts the person named; the person can let another host in with: codezaiku config set sandbox.hosts " + host + "\n";
    }

    private static void answer(OutputStream out, int code, String reason, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    // ── the record ──────────────────────────────────────────────────────────────────────────────────────────────────

    private void record(Job job, String who, String host, int port, boolean let, String rule) {
        Try t = new Try(System.currentTimeMillis(), job.project, who, host, port, let, rule);
        synchronized (job.tries) {
            job.tries.add(t);
            if (job.tries.size() > 2000) job.tries.subList(0, 500).clear();
        }
        try {
            Path f = recordFile(job.project);
            Files.createDirectories(f.getParent());
            ObjectNode o = JSON.createObjectNode();
            o.put("at", t.at()).put("project", job.project.toString()).put("command", who).put("host", host).put("port", port)
                    .put("let", let).put("rule", rule);
            synchronized (NetProxy.class) {
                Files.writeString(f, JSON.writeValueAsString(o) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException | RuntimeException e) {
            // the in-memory record stands
        }
    }

    /** Where a project's tries are written: under CodeZaiku's own folder, which no command can write. */
    public static Path recordFile(Path project) {
        Path real = SandboxRules.real(project);
        String name = real.getFileName() == null ? "root" : real.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
        return Config.home().resolve("sandbox").resolve("network").resolve(name + "-" + hash(real.toString()) + ".jsonl");
    }

    // ── HTTP, as much as a proxy reads ──────────────────────────────────────────────────────────────────────────────

    /** The first lines of a request: where it goes, and the head to send on for a plain HTTP one. */
    static final class Request {
        boolean connect;
        String target, host, proxyAuth, method, version;
        int port;
        List<String> headers = new ArrayList<>();
        String path;

        static Request read(InputStream in) throws IOException {
            String first = asciiLine(in, 8192);
            if (first == null || first.isBlank()) return null;
            Request r = new Request();
            String[] w = first.strip().split(" ");
            if (w.length != 3) { r.target = first.strip(); return r; }
            r.method = w[0];
            r.target = w[1];
            r.version = w[2];
            int total = 0;
            while (true) {
                String h = asciiLine(in, 16384);
                if (h == null) return null;
                if (h.isEmpty()) break;
                total += h.length();
                if (total > 65536) return null;
                if (h.regionMatches(true, 0, "Proxy-Authorization:", 0, 20)) { r.proxyAuth = h.substring(20).strip(); continue; }
                r.headers.add(h);
            }
            if (r.method.equalsIgnoreCase("CONNECT")) {
                r.connect = true;
                hostPort(r, r.target, -1);
            } else if (r.target.regionMatches(true, 0, "http://", 0, 7)) {
                String rest = r.target.substring(7);
                int slash = rest.indexOf('/');
                String authority = slash < 0 ? rest : rest.substring(0, slash);
                r.path = slash < 0 ? "/" : rest.substring(slash);
                if (authority.contains("@")) authority = authority.substring(authority.lastIndexOf('@') + 1);
                hostPort(r, authority, 80);
            }
            return r;
        }

        private static void hostPort(Request r, String authority, int defaultPort) {
            String a = authority.strip();
            String host;
            String port = null;
            if (a.startsWith("[")) {
                int end = a.indexOf(']');
                if (end < 0) return;
                host = a.substring(1, end);
                if (a.length() > end + 1 && a.charAt(end + 1) == ':') port = a.substring(end + 2);
            } else {
                int colon = a.lastIndexOf(':');
                host = colon < 0 ? a : a.substring(0, colon);
                if (colon >= 0) port = a.substring(colon + 1);
            }
            int p = defaultPort;
            if (port != null) { try { p = Integer.parseInt(port); } catch (NumberFormatException e) { return; } }
            if (p < 1 || p > 65535 || host.isBlank()) return;
            r.host = host;
            r.port = p;
        }

        /** The request as the server receives it: in origin form, its connection closed after the answer. */
        byte[] originHead() {
            StringBuilder b = new StringBuilder(method).append(' ').append(path).append(' ').append(version).append("\r\n");
            for (String h : headers) {
                String lower = h.toLowerCase(Locale.ROOT);
                if (lower.startsWith("proxy-connection:") || lower.startsWith("connection:") || lower.startsWith("keep-alive:")) continue;
                b.append(h).append("\r\n");
            }
            b.append("Connection: close\r\n\r\n");
            return b.toString().getBytes(StandardCharsets.ISO_8859_1);
        }
    }

    /** One line of bytes up to LF, without its CR LF; null at the end of the stream or past {@code most} bytes. */
    static String asciiLine(InputStream in, int most) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c < 0) return b.size() == 0 ? null : b.toString(StandardCharsets.ISO_8859_1);
            if (c == '\n') break;
            if (b.size() >= most) return null;
            b.write(c);
        }
        String s = b.toString(StandardCharsets.ISO_8859_1);
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    private static String readLine(InputStream in, long millis) throws IOException {
        String[] got = new String[1];
        Thread t = Thread.ofVirtual().start(() -> {
            try { got[0] = asciiLine(in, 200); } catch (IOException e) { /* null */ }
        });
        try { t.join(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return got[0];
    }

    private static byte[] bytes(int n) { byte[] b = new byte[n]; RANDOM.nextBytes(b); return b; }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
