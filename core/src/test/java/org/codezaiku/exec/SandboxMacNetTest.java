package org.codezaiku.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.codezaiku.Config;
import org.codezaiku.tools.PathScope;
import org.codezaiku.tools.ShellTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * macOS, where a job's commands have no network of their own: sandbox-exec cannot make one, so on a closed network a command reaches
 * this Mac's own addresses only (CodeZaiku's proxy listens there, in its own process) and looks up no names. What decides it, what
 * the first lines and the doctor say, the profile for each setting, the command's environment and the proxy's listener are checked
 * on any machine. The tests named "underSandboxExec" start real commands under the Mac's own sandbox-exec and are skipped, with the
 * reason said, on a machine without it.
 *
 * <p>No outside address is contacted: the addresses tried are from the ranges kept for documentation (192.0.2.0/24, 2001:db8::/32)
 * and names under {@code .invalid}; the host let in is a server of this test on 127.0.0.1, named in sandbox.hosts.
 */
class SandboxMacNetTest {

    @TempDir Path home;
    @TempDir Path project;
    String realHome;
    Path madeTmp;
    HttpServer server;

    /**
     * The proxy's ports, below the system's ephemeral ones on Linux, where the product's range (47000 to 47999) lies inside them and
     * any program's outgoing connection could hold the test's port for a moment (seen once, 2026-11-26). The Mac's ephemeral ports
     * start at 49152, above the product's range, which the test then keeps.
     */
    static int portsBelowEphemeral() {
        try {
            String[] r = Files.readString(Path.of("/proc/sys/net/ipv4/ip_local_port_range")).strip().split("\\s+");
            int first = Integer.parseInt(r[0]);
            if (first <= NetProxy.PORT_FIRST + NetProxy.PORT_COUNT && first - 2 * NetProxy.PORT_COUNT >= 1024) return first - 2 * NetProxy.PORT_COUNT;
        } catch (IOException | RuntimeException notLinux) { }
        return NetProxy.PORT_FIRST;
    }

    @BeforeEach
    void before() throws IOException {
        NetProxy.portFirst = portsBelowEphemeral();
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        madeTmp = Sandbox.folders(project).tmp();          // made by the first lines too: removed after each test
    }

    @AfterEach
    void after() throws IOException {
        NetProxy.portFirst = NetProxy.PORT_FIRST;
        if (server != null) server.stop(0);
        Sandbox.asker(project, null);
        NetProxy.stopForTests();
        Sandbox.forTests(null);
        Sandbox.loginForTests(null);
        Sandbox.model(project, false);
        if (madeTmp != null && Files.isDirectory(madeTmp) && madeTmp.getFileName().toString().startsWith("cz-")) {
            try (var walk = Files.walk(madeTmp)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
        System.setProperty("user.home", realHome);
        Config.invalidate();
    }

    private static final Sandbox.Found SEATBELT = new Sandbox.Found(Sandbox.Kind.SEATBELT, null, 0, true, "/usr/bin/sandbox-exec");

    private void login() throws IOException {
        Map<String, String> login = new LinkedHashMap<>();
        login.put("PATH", "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin");
        login.put("LANG", "C.UTF-8");
        login.put("HTTPS_PROXY", "http://a-proxy-of-the-persons.invalid:3128");
        Sandbox.loginForTests(login);
        madeTmp = Sandbox.folders(project).tmp();
    }

    private String last(List<String> lines) { return lines.get(lines.size() - 1); }

    // ── what decides it, and what is said ──────────────────────────────────────────────────────────────────────────

    @Test
    void onMacOsAHostedModelsCommandsReachThisMacOnlyAndTheFirstLineSaysWhatStaysReachable() throws Exception {
        Sandbox.forTests(SEATBELT);
        Sandbox.model(project, true);
        assertEquals(Sandbox.Net.LOOPBACK, Sandbox.net(project), "hosted, auto: closed");
        String line = last(Sandbox.lines(project, true));
        assertTrue(line.startsWith("sandbox: network closed to the outside.") && line.contains("no name lookups")
                && line.contains("every service of this Mac") && line.contains("anyone on a network that reaches this Mac can connect to it")
                && line.contains("codezaiku config set sandbox.listen closed") && line.contains("sandbox.hosts"), line);

        Config.set("sandbox.listen", "closed");
        line = last(Sandbox.lines(project, true));
        assertTrue(line.contains("A command may not listen for connections (sandbox.listen is closed), so a test that starts a server fails here."), line);
        assertFalse(line.contains("anyone on a network"), line);
        Config.set("sandbox.listen", "");

        Sandbox.model(project, false);
        assertEquals(Sandbox.Net.OPEN, Sandbox.net(project), "local, auto: as before");
        assertTrue(Sandbox.lines(project, false).stream().noneMatch(l -> l.startsWith("sandbox: network")), "and nothing new is said: " + Sandbox.lines(project, false));
        Config.set("sandbox.network", "closed");
        assertEquals(Sandbox.Net.LOOPBACK, Sandbox.net(project), "closed in the settings closes it for a local model too");
        Config.set("sandbox.network", "open");
        Sandbox.model(project, true);
        assertEquals(Sandbox.Net.OPEN, Sandbox.net(project), "open in the settings opens it for a hosted one");
        assertTrue(last(Sandbox.lines(project, true)).startsWith("sandbox: network open"), "and that is said: " + Sandbox.lines(project, true));
    }

    @Test
    void theDoctorSaysWhatAClosedNetworkReachesOnMacOsAndWhetherACommandMayListen() throws Exception {
        Sandbox.forTests(SEATBELT);
        assertEquals("sandbox-exec; a closed network reaches this Mac's own services and CodeZaiku's proxy, not the outside, and a command may "
                + "listen for connections (sandbox.listen open)", Sandbox.describe());
        Config.set("sandbox.listen", "closed");
        assertTrue(Sandbox.describe().endsWith("a command may not listen for connections (sandbox.listen closed)"), Sandbox.describe());
    }

    @Test
    void theProfileClosesTheNetworkAndTheResolverAndListeningAsTheSettingsSay() throws Exception {
        Sandbox.Folders f = Sandbox.folders(project);
        madeTmp = f.tmp();
        SandboxRules r = SandboxRules.of(project, f, Map.of("PATH", "/usr/bin"), List.of(), List.of(), List.of(home), true, true);
        String own = "(allow network-bind network-outbound network-inbound\n    (subpath \"" + SandboxRules.real(project) + "\")";

        String open = r.seatbeltProfile();
        assertEquals(open, r.seatbeltProfile(SandboxRules.Network.OPEN));
        for (String l : List.of("(allow network-outbound (literal \"/private/var/run/mDNSResponder\"))", "(allow network-outbound (remote ip))",
                "(allow network-inbound (local ip))", "(allow network-bind (local ip))", own))
            assertTrue(open.contains(l), "open, as before: " + l + "\n" + open);

        String closed = r.seatbeltProfile(SandboxRules.Network.CLOSED);
        for (String l : List.of("(allow network-outbound (remote ip \"localhost:*\"))", "(allow network-inbound (local ip \"localhost:*\"))",
                "(allow network-bind (local ip \"localhost:*\"))", own))
            assertTrue(closed.contains(l), "closed: " + l + "\n" + closed);
        for (String l : List.of("mDNSResponder", "(remote ip)", "(local ip)", "(remote ip \"*"))
            assertFalse(closed.contains(l), "closed has no " + l + "\n" + closed);

        String noListen = r.seatbeltProfile(SandboxRules.Network.CLOSED_NO_LISTEN);
        assertTrue(noListen.contains("(allow network-outbound (remote ip \"localhost:*\"))") && noListen.contains(own), noListen);
        for (String l : List.of("mDNSResponder", "(allow network-inbound (local", "(allow network-bind (local"))
            assertFalse(noListen.contains(l), "no listening: no " + l + "\n" + noListen);
        assertEquals(closed.replace("(allow network-inbound (local ip \"localhost:*\"))\n(allow network-bind (local ip \"localhost:*\"))\n",
                "; no listening for connections (sandbox.listen closed)\n"), noListen, "and nothing else differs");
    }

    @Test
    void onMacOsAClosedNetworksCommandGetsTheProxyOfItsProjectAndTheClosedProfile() throws Exception {
        Sandbox.forTests(SEATBELT);
        login();
        Sandbox.model(project, true);
        ProcessBuilder pb = Sandbox.in(project).shell("echo hi");
        List<String> cmd = pb.command();
        assertEquals(List.of("/usr/bin/sandbox-exec", "-f"), cmd.subList(0, 2), cmd.toString());
        String profile = Files.readString(Path.of(cmd.get(2)));
        assertTrue(profile.contains("(allow network-outbound (remote ip \"localhost:*\"))") && !profile.contains("mDNSResponder"), profile);
        NetProxy.Job job = NetProxy.get().job(project);
        assertEquals(-1, job.pid(), "no keeper: the proxy listens in this process");
        assertTrue(NetProxy.get().socket() == null, "and no socket file is made for keepers: " + NetProxy.get().socket());
        String proxy = pb.environment().get("HTTPS_PROXY");
        assertTrue(proxy.matches("http://cz:\\d+@127\\.0\\.0\\.1:" + job.port()), "the person's own proxy is replaced by the project's: " + proxy);
        assertEquals("localhost,127.0.0.1,::1", pb.environment().get("NO_PROXY"));
        assertTrue(pb.environment().get("JAVA_TOOL_OPTIONS").contains("-Dhttps.proxyPort=" + job.port()), pb.environment().get("JAVA_TOOL_OPTIONS"));
        assertTrue(pb.environment().get("JAVA_TOOL_OPTIONS").contains("-Djava.net.preferIPv4Stack=true"), "Java on an IPv4 stack: " + pb.environment().get("JAVA_TOOL_OPTIONS"));
        String maven = Files.readString(Sandbox.folders(project).home().resolve(".m2/settings.xml"));
        assertTrue(maven.startsWith(SandboxEnv.MAVEN_MARK) && maven.contains("<port>" + job.port() + "</port>"), maven);
        ProcessBuilder again = Sandbox.in(project).shell("echo again");
        assertEquals(job.port(), NetProxy.get().job(project).port(), "one listener for the project's commands");
        assertFalse(again.environment().get("HTTPS_PROXY").equals(proxy), "each command its own number");

        Config.set("sandbox.listen", "closed");
        String closedListen = Files.readString(Path.of(Sandbox.in(project).shell("echo x").command().get(2)));
        assertFalse(closedListen.contains("(allow network-inbound (local"), closedListen);

        Sandbox.model(project, false);
        ProcessBuilder local = Sandbox.in(project).shell("echo local");
        assertEquals("http://a-proxy-of-the-persons.invalid:3128", local.environment().get("HTTPS_PROXY"), "a local model's command: as before");
        assertFalse(local.environment().get("JAVA_TOOL_OPTIONS").contains("preferIPv4Stack"), local.environment().get("JAVA_TOOL_OPTIONS"));
        assertTrue(Files.readString(Path.of(local.command().get(2))).contains("(allow network-outbound (remote ip))"), "and the open profile");
    }

    /** A server of this test on 127.0.0.1, named in sandbox.hosts: the host let in. */
    private int letInServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", x -> {
            byte[] b = "reached-through-the-proxy".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        Config.set("sandbox.hosts", "127.0.0.1:" + port);
        return port;
    }

    private static String ask(int proxyPort, String request) throws IOException {
        try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), proxyPort)) {
            s.setSoTimeout(10_000);
            OutputStream out = s.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theProxysListenerForAProjectDecidesAndWritesDownAsTheKeeperDoes() throws Exception {
        Sandbox.forTests(SEATBELT);
        login();
        Sandbox.model(project, true);
        int port = letInServer();
        long start = System.currentTimeMillis();
        String proxy = Sandbox.in(project).named("curl https://not-let-in.invalid/").shell("curl https://not-let-in.invalid/").environment().get("HTTP_PROXY");
        String user = proxy.substring("http://".length(), proxy.indexOf('@'));
        String auth = "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString(user.getBytes(StandardCharsets.US_ASCII)) + "\r\n";
        int proxyPort = NetProxy.get().job(project).port();

        String refused = ask(proxyPort, "CONNECT not-let-in.invalid:443 HTTP/1.1\r\nHost: not-let-in.invalid:443\r\n" + auth + "\r\n");
        assertTrue(refused.startsWith("HTTP/1.1 403 Not let in by CodeZaiku (sandbox.hosts)") && refused.contains("codezaiku config set sandbox.hosts not-let-in.invalid"), refused);
        String through = ask(proxyPort, "GET http://127.0.0.1:" + port + "/ HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n" + auth + "\r\n");
        assertTrue(through.startsWith("HTTP/1.1 200") && through.endsWith("reached-through-the-proxy"), "the host the person named is reached: " + through);

        List<NetProxy.Try> tries = NetProxy.tries(project, start);
        NetProxy.Try no = tries.stream().filter(t -> !t.let()).findFirst().orElseThrow();
        assertEquals("not-let-in.invalid", no.host());
        assertEquals("curl https://not-let-in.invalid/", no.command(), "the command its proxy address named");
        assertTrue(tries.stream().anyMatch(t -> t.let() && t.rule().equals("sandbox.hosts")), tries.toString());
        String record = Files.readString(NetProxy.recordFile(project));
        assertTrue(record.contains("\"host\":\"not-let-in.invalid\"") && record.contains("\"let\":false"), record);
        assertTrue(Sandbox.refusedNote(project, start).contains("not-let-in.invalid:443"), Sandbox.refusedNote(project, start));
        assertTrue(String.join("\n", Sandbox.netLines(project, start)).contains("not-let-in.invalid:443 (not let in), by: curl"), Sandbox.netLines(project, start).toString());

        NetProxy.get().job(project).listener.close();
        Sandbox.in(project).shell("true");
        assertTrue(NetProxy.get().job(project).listening(), "a listener that closed is opened again at the next command");
    }

    @Test
    void onMacOsAProjectsProxyPortIsItsOwnEveryTimeAndMovesOnlyWhenAnotherProgramHasIt() throws Exception {
        Sandbox.forTests(SEATBELT);
        login();
        Sandbox.model(project, true);
        int own = NetProxy.portFor(project);
        assertTrue(own >= NetProxy.portFirst && own < NetProxy.portFirst + NetProxy.PORT_COUNT, "in the range: " + own);
        Sandbox.in(project).shell("true");
        int first = NetProxy.get().job(project).port();
        NetProxy.stopForTests();                                    // CodeZaiku ends; a new one works in the same project
        Sandbox.in(project).shell("true");
        assertEquals(first, NetProxy.get().job(project).port(), "the same port again, so a Gradle daemon left running finds the proxy");
        assertEquals(own, NetProxy.get().job(project).preferred());

        NetProxy.stopForTests();
        List<String> said = new ArrayList<>();
        Sandbox.say(project, said::add);
        try (ServerSocket other = new ServerSocket()) {
            other.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), own));    // another program has the project's port
            ProcessBuilder pb = Sandbox.in(project).shell("true");
            NetProxy.Job job = NetProxy.get().job(project);
            assertTrue(job.port() != own && job.port() >= NetProxy.portFirst && job.port() < NetProxy.portFirst + NetProxy.PORT_COUNT, "moved, in the range: " + job.port());
            assertTrue(pb.environment().get("HTTPS_PROXY").endsWith(":" + job.port()), pb.environment().get("HTTPS_PROXY"));
            assertEquals(List.of(Sandbox.movedLine(own, job.port())), said, "said once");
            Sandbox.in(project).shell("true");
            assertEquals(1, said.size(), "and not again: " + said);
        } finally {
            Sandbox.say(project, null);
        }
    }

    // ── real commands under the Mac's sandbox-exec ──────────────────────────────────────────────────────────────────

    private record Ran(int exit, String out) { }

    private static final String PYTHON = "/usr/bin/python3";

    private void assumeSeatbelt() {
        Sandbox.Found real = Sandbox.probe();
        assumeTrue(real.kind() == Sandbox.Kind.SEATBELT, "skipped: no sandbox-exec on this machine (it runs on macOS only)");
        assumeTrue(Files.isExecutable(Path.of(PYTHON)), "skipped: this Mac has no " + PYTHON);
        Sandbox.forTests(real);
    }

    private Ran sh(String script) throws Exception {
        Process p = Sandbox.in(project).shell(script).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the command ended");
        return new Ran(p.exitValue(), out);
    }

    /** Each way out, tried from Python, one line each: "name: made ..." or the exception and its words. argv: the let-in port. */
    private static final String WAYS_OUT = """
            import os, socket, sys, urllib.request
            def tcp(host, port, family=socket.AF_INET):
                s = socket.socket(family, socket.SOCK_STREAM); s.settimeout(3); s.connect((host, port)); s.close(); return "connected"
            def udp():
                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.sendto(b"x", ("192.0.2.1", 53)); return "sent"
            def through(url):
                for k in ("no_proxy", "NO_PROXY"): os.environ.pop(k, None)
                return urllib.request.build_opener(urllib.request.ProxyHandler({"http": os.environ["HTTP_PROXY"]})).open(url, timeout=10).read().decode()
            def listen():
                s = socket.socket(); s.bind(("127.0.0.1", 0)); s.listen(1); s.close(); return "listening"
            ways = [
                ("tcp-number", lambda: tcp("192.0.2.1", 443)),
                ("tcp-name", lambda: tcp("cz-closed-test.invalid", 443)),
                ("tcp6", lambda: tcp("2001:db8::1", 443, socket.AF_INET6)),
                ("udp", udp),
                ("dns", lambda: socket.getaddrinfo("cz-closed-test.invalid", 443)),
                ("ping", lambda: socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_ICMP).sendto(b"x", ("192.0.2.1", 0))),
                ("unix", lambda: socket.socket(socket.AF_UNIX).connect("/private/var/run/mDNSResponder")),
                ("localhost", lambda: sorted({a[4][0] for a in socket.getaddrinfo("localhost", 80, proto=socket.IPPROTO_TCP)})),
                ("this-mac", lambda: tcp("127.0.0.1", int(sys.argv[1]))),
                ("listen", listen),
                ("let-in", lambda: through("http://127.0.0.1:%s/" % sys.argv[1])),
                ("refused", lambda: urllib.request.urlopen("https://not-let-in.invalid/", timeout=10).read()),
            ]
            for name, f in ways:
                try: print(name + ": made " + str(f()))
                except Exception as e: print(name + ": " + type(e).__name__ + " " + str(e))
            """;

    private Map<String, String> waysOut(int port) throws Exception {
        Files.writeString(project.resolve("ways_out.py"), WAYS_OUT);
        Ran r = sh(PYTHON + " ways_out.py " + port);
        Map<String, String> said = new LinkedHashMap<>();
        for (String line : r.out().split("\n")) {
            int colon = line.indexOf(": ");
            if (colon > 0 && !line.startsWith(" ")) said.put(line.substring(0, colon), line.substring(colon + 2));
        }
        System.out.println("ways out, as the command saw them:\n" + r.out());
        assertTrue(said.keySet().containsAll(List.of("tcp-number", "refused")), r.out());
        return said;
    }

    @Test
    void underSandboxExecEveryWayOffThisMacIsRefusedAndTheProxyLetsInWhatTheRulesDo() throws Exception {
        assumeSeatbelt();
        login();
        Sandbox.model(project, true);
        long start = System.currentTimeMillis();
        Map<String, String> said = waysOut(letInServer());
        for (String way : List.of("tcp-number", "tcp6", "udp", "ping", "unix"))
            assertTrue(said.get(way).contains("PermissionError [Errno 1] Operation not permitted"), way + ": " + said);
        assertTrue(said.get("tcp-name").contains("gaierror"), "no name is looked up: " + said);
        assertTrue(said.get("dns").contains("gaierror"), said.toString());
        assertTrue(said.get("localhost").contains("127.0.0.1"), "localhost still resolves: " + said);
        assertEquals("made connected", said.get("this-mac"), "this Mac's own services stay reachable, as the docs say: " + said);
        assertEquals("made listening", said.get("listen"), "listening is allowed unless sandbox.listen is closed: " + said);
        assertEquals("made reached-through-the-proxy", said.get("let-in"), "the host the person named, through the proxy: " + said);
        assertTrue(said.get("refused").contains("403"), said.toString());
        NetProxy.Try refused = NetProxy.tries(project, start).stream().filter(t -> !t.let()).findFirst().orElseThrow();
        assertEquals("not-let-in.invalid", refused.host());
        assertTrue(refused.command().contains("ways_out.py"), "the command that tried: " + refused.command());

        Config.set("sandbox.listen", "closed");
        Ran listen = sh(PYTHON + " -c \"import socket; s = socket.socket(); s.bind(('127.0.0.1', 0)); s.listen(1)\"");
        assertTrue(listen.out().contains("PermissionError: [Errno 1] Operation not permitted"), "with sandbox.listen closed a command may not listen: " + listen.out());
    }

    @Test
    void underSandboxExecAServerOneCommandStartsIsReachedByTheNextAndATestsOwnServerWorks() throws Exception {
        assumeSeatbelt();
        login();
        Sandbox.model(project, true);
        Files.writeString(project.resolve("serve_once.py"), """
                import socket
                s = socket.socket(); s.bind(("127.0.0.1", 0)); s.listen(1); s.settimeout(20)
                open("port.txt", "w").write(str(s.getsockname()[1]))
                c, _ = s.accept(); c.sendall(b"from-the-first-command"); c.close()
                """);
        Ran first = sh(PYTHON + " serve_once.py > /dev/null 2>&1 & for i in $(seq 50); do [ -s port.txt ] && break; sleep 0.1; done; echo started");
        assertTrue(first.out().contains("started") && Files.exists(project.resolve("port.txt")), first.out());
        Ran second = sh(PYTHON + " -c \"import socket; s = socket.create_connection(('127.0.0.1', int(open('port.txt').read())), 5); print(s.recv(100).decode())\"");
        assertTrue(second.out().contains("from-the-first-command"), second.out());
        Files.writeString(project.resolve("round_trip.py"), """
                import socket, threading
                srv = socket.socket(); srv.bind(("127.0.0.1", 0)); srv.listen(1)
                def serve():
                    c, _ = srv.accept(); c.sendall(b"pong"); c.close()
                threading.Thread(target=serve, daemon=True).start()
                cl = socket.create_connection(("127.0.0.1", srv.getsockname()[1]), 5); print(cl.recv(10).decode())
                """);
        assertEquals("pong", sh(PYTHON + " round_trip.py").out().strip(), "a test's server and its client in one command");
    }

    @Test
    void underSandboxExecAJavaProgramsOwnServerOnTheLoopbackAndItsClientWork() throws Exception {
        assumeSeatbelt();
        login();
        Sandbox.model(project, true);
        Files.writeString(project.resolve("JavaLoop.java"), """
                import java.io.InputStream;
                import java.io.OutputStream;
                import java.net.InetAddress;
                import java.net.ServerSocket;
                import java.net.Socket;

                public class JavaLoop {
                    public static void main(String[] a) throws Exception {
                        InetAddress lo = InetAddress.getByName("127.0.0.1");
                        try (ServerSocket s = new ServerSocket(0, 1, lo)) {
                            Thread t = new Thread(() -> { try (Socket c = s.accept(); OutputStream o = c.getOutputStream()) { o.write("pong".getBytes()); } catch (Exception e) { System.out.println(e); } });
                            t.start();
                            try (Socket c = new Socket(lo, s.getLocalPort()); InputStream in = c.getInputStream()) { System.out.println(new String(in.readAllBytes())); }
                            t.join();
                        }
                    }
                }
                """);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Ran r = sh(java + " JavaLoop.java 2>&1 | grep -v 'Picked up JAVA_TOOL_OPTIONS'");
        assertEquals("pong", r.out().strip(), "as a test's own server and Gradle's daemon need: " + r.out());
    }

    @Test
    void underSandboxExecTheShellToolsResultSaysWhatWasRefused() throws Exception {
        assumeSeatbelt();
        login();
        Sandbox.model(project, true);
        long start = System.currentTimeMillis();
        String command = PYTHON + " -c \"import urllib.request; urllib.request.urlopen('https://not-let-in.invalid/', timeout=10)\"";
        String said = new ShellTool(new PathScope(project)).execute(new ObjectMapper().createObjectNode().put("command", command));
        assertTrue(said.contains("403") && said.contains("[note: this job's commands reach the network through CodeZaiku only")
                && said.contains("not-let-in.invalid:443") && said.contains("sandbox.hosts"), said);
        assertEquals(command, NetProxy.tries(project, start).get(0).command(), "the model's words, not the shell tool's prelude before them");
    }
}
