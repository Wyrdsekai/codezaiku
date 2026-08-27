package org.codezaiku.verify;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.codezaiku.exec.Shell;

/**
 * Harness-owned END-TO-END BOOT CHECK, run on the model's real task_done attempt (NOT a write
 * interceptor — it certifies on real execution, the line from feedback-no-action-interceptor-gates).
 *
 * <p>The diagnosis (battery25, read from produced code): a local-9B reliably writes per-concern
 * modules then ships an app that never starts — an unregistered FastAPI startup hook, an Express
 * server that never calls {@code listen()}, an empty {@code __init__} that re-exports nothing, a
 * Spring context that aborts on an unqualified bean. Every one of these is invisible to the model's
 * own in-process unit tests and would surface the instant something actually booted the assembled
 * app. The model only knows how to write code and run shell tools; it cannot be expected to know the
 * boot recipe for each stack — so the HARNESS owns the boot and hands back only the error to fix.
 *
 * <p>Scope (deliberately narrow, to avoid overfitting): this gates the HTTP-service archetype only,
 * where "doesn't boot" is the dominant, model-invisible failure. TUI/CLI/library archetypes already
 * get real signal from the model's own {@code cargo build}/{@code cargo test} loop, so they SKIP.
 * The check is "the assembled app comes up and serves its primary route without a server error" — NOT
 * "it returns the right data" (that stays external measurement; an in-loop data oracle would be the
 * benchmark-gaming bias). Two spec-driven refinements keep it from passing an empty shell: when the
 * spec demands a web service and NO app object exists, a missing entrypoint is a FAIL (not a silent
 * SKIP); and when the spec asks for a rendered PAGE, a 404 on both {@code /} and {@code /dashboard} is a
 * FAIL (a bound server with no page route). Both gate on generic web/page signals in the spec, never a
 * per-fixture match, and never fire on a non-web archetype (a TUI "dashboard" stays a SKIP). Port
 * discovery is by {@code ss} on the launched process tree, so it catches both never-listen and a
 * hardcoded port, with no per-fixture knowledge.
 */
public final class BootCheck {

    private static final Logger log = LoggerFactory.getLogger(BootCheck.class);

    /** Overall wall budget for one boot attempt (cold gradle bootRun is the slow case). */
    private static final int BOOT_BUDGET_MS = 120_000;
    /** How long to wait for the app to bind a port after launch (Spring context + Tomcat can be ~40s). */
    private static final int BIND_WAIT_MS = 90_000;

    public enum Status { PASS, FAIL, SKIP }

    public record Result(Status status, String summary) {
        public boolean failed() { return status == Status.FAIL; }
        public String oneLine() {
            String s = summary == null ? "" : summary.strip();
            int nl = s.indexOf('\n');
            return nl < 0 ? s : s.substring(0, nl);
        }
    }

    private static final Result SKIP = new Result(Status.SKIP, "");

    /** `var = FastAPI(` — the app object to hand uvicorn. */
    private static final Pattern FASTAPI_APP = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*FastAPI\\s*\\(");
    private static final Pattern FLASK_APP = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*Flask\\s*\\(");
    /** ss LISTEN line → local port + owning pid: ...127.0.0.1:8090 ... pid=12345,... */
    private static final Pattern SS_LISTEN = Pattern.compile(":(\\d{2,5})\\s.*pid=(\\d+)");

    private BootCheck() {}

    /**
     * Boot the app end-to-end if (and only if) it is an HTTP-service archetype. Never throws —
     * any internal failure degrades to SKIP so the gate can only ever ADD a fixable error, never
     * wedge a finish on harness trouble.
     */
    public static Result run(Path projectRoot, String goal) {
        try {
            return check(projectRoot, goal);
        } catch (Exception e) {
            log.info("boot-check internal error → SKIP: {}", e.toString());
            return SKIP;
        }
    }

    private static Result check(Path projectRoot, String goal) {
        Http kind = detect(projectRoot, goal);
        if (kind == null) {
            // No bootable web app found. If the SPEC plainly demands an HTTP service (names a web framework
            // or a web interface), a missing app is a real, model-invisible failure — NOT a reason to SKIP
            // silently (battery26 py-n3: the model built the service modules, then declared done on "pyflakes
            // is clean", never writing a server, and the gate said nothing). Surface it so the bounded boot-
            // bounce drives the model to build the entrypoint. Anything short of an explicit web demand stays
            // SKIP — a library/CLI/TUI spec that merely says "endpoint" or "dashboard" must NOT trip this.
            if (wantsWebService(goal)) {
                String p = specPort(goal);
                return new Result(Status.FAIL, "Boot recipe: none found. The spec requires a running web "
                        + "service" + (p == null ? "" : " (on port " + p + ")") + ", but there is no app to boot: "
                        + "I found no FastAPI()/Flask()/Express/Spring-Boot application object anywhere in the "
                        + "tree. Create the web entrypoint that constructs the app and starts the server (e.g. "
                        + "`app = FastAPI()` with the routes, served by uvicorn), then call task_done again.");
            }
            return SKIP;          // not a web spec → leave it to the model's build/test loop
        }

        Path root = kind.root;
        int port = freePort();
        String cmd = kind.bootCommand(port);
        if (cmd == null) return SKIP;           // could not form a boot recipe (e.g. no FastAPI app found)

        // LOAD THE APP'S OWN BUNDLED FIXTURE so the boot exercises the REAL data path, not an empty store.
        // A pipeline app that serves "/" → 200 on empty data can still 500 the moment real records flow
        // through it (battery34: graaljs/py dashboards 500'd on the golden mbox). Pointing the data-source
        // env at the model's OWN fixture (the spec requires bundling ./fixtures/sample.mbox) runs the real
        // pipeline using the model's data — NOT the held-out golden, so no eval leak and nothing to game; it
        // just has to not crash on its own sample. Residual (crashes only on richer-than-own data) stays the
        // held-out grader's job. Bounded: only fires when the fixture actually exists.
        Path mbox = root.resolve("fixtures/sample.mbox");
        if (Files.isRegularFile(mbox)) {
            cmd = "EMAIL_MBOX=" + sh(mbox.toAbsolutePath().toString()) + " " + cmd;
        }

        log.info("boot-check: {} archetype at {} → port {}", kind.label, root, port);
        Process proc = null;
        long deadline = System.currentTimeMillis() + BOOT_BUDGET_MS;
        StringBuilder out = new StringBuilder();
        try {
            // `exec env <cmd>`: env makes any VAR=val prefix in cmd valid (exec alone rejects it), and exec
            // lets destroyForcibly + the descendants kill reach the real server, not just a wrapping shell.
            proc = Shell.pb("cd " + sh(root.toString()) + " && exec env " + cmd)
                    .redirectErrorStream(true)
                    .start();
            Thread drain = drain(proc, out);

            int bound = waitForBind(proc, port, Math.min(deadline, System.currentTimeMillis() + BIND_WAIT_MS));
            if (bound < 0) {
                int ec = safeExit(proc);
                String why = !proc.isAlive()
                        ? (ec == 0
                            ? "the process exited cleanly (code 0) without ever binding a port — it never starts a "
                              + "server (e.g. it builds the app object but never calls listen()/uvicorn.run())"
                            : "the process exited (code " + ec + ") before binding any port — it crashed on startup "
                              + "(see the error below)")
                        : "no TCP port was ever bound within " + (BIND_WAIT_MS / 1000) + "s — the app never started its "
                          + "server (it never calls listen()/uvicorn.run(), or it hangs before binding)";
                return fail(kind, why, out, drain);
            }

            // Bound. Hit "/" — the gate certifies "serves without a server error", not "returns the right
            // data" (data stays the external grader). A 5xx or a dropped connection is always a boot failure.
            HttpResult r = get(bound, "/", deadline);
            if (r.refused) {
                return fail(kind, "the app bound :" + bound + " but GET / refused the connection (it bound then died, "
                        + "or is not actually serving HTTP there)", out, drain);
            }
            if (r.status >= 500) {
                return fail(kind, "GET / returned HTTP " + r.status + " — the app boots but errors on its own root route:\n"
                        + indent(r.body), out, drain);
            }
            // The behavior-door is intentionally MINIMAL: the app boots, binds, and serves "/" without a
            // server error (5xx/refused). Per-concern realness — every route wired, the dashboard renders
            // REAL data — is measured EXTERNALLY by the held-out grader, NOT enforced as an in-loop bounce.
            // Piling route-probes / spec-concern coverage / dashboard-404 onto the gate turned it into a
            // treadmill: it bounced the model and slowed every finish without lifting real quality
            // (battery38-41 vs the battery10 high-water mark of 11/18 task_done). Keep the door, not the pile.
            log.info("boot-check PASS: {} bound :{}, GET / → {}", kind.label, bound, r.status);
            return new Result(Status.PASS, "boot OK: app started and GET / → HTTP " + r.status);
        } catch (Exception e) {
            log.info("boot-check launch error → SKIP: {}", e.toString());
            return SKIP;
        } finally {
            teardown(proc, port);
        }
    }

    private static Result fail(Http kind, String why, StringBuilder out, Thread drain) {
        try { if (drain != null) drain.join(800); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        String tail = tail(out.toString(), 24);
        String msg = "Boot recipe: `" + kind.label + "`. " + why
                + (tail.isBlank() ? "" : "\n\nLast output from the boot attempt:\n" + indent(tail));
        return new Result(Status.FAIL, msg);
    }

    // ---- archetype detection -------------------------------------------------------------------

    /** An HTTP service archetype plus the REAL project root (deepest matching manifest, not a stub). */
    private static final class Http {
        final String label;        // "FastAPI/uvicorn", "Express", "Spring Boot"
        final String framework;    // fastapi | flask | express | spring
        final Path root;           // directory to cd into and boot from
        final String target;       // python "module:var" (relative to root); null for express/spring
        Http(String label, String framework, Path root, String target) {
            this.label = label; this.framework = framework; this.root = root; this.target = target;
        }

        String bootCommand(int port) {
            return switch (framework) {
                case "fastapi" -> target == null ? null : "python3 -m uvicorn " + target
                        + " --host 127.0.0.1 --port " + port + " --log-level warning";
                // flask run respects --port; FLASK_APP points it at module:var.
                case "flask" -> target == null ? null
                        : "FLASK_APP=" + target + " python3 -m flask run --host 127.0.0.1 --port " + port;
                case "express" -> "PORT=" + port + " npm start --silent";
                // --no-daemon so the app JVM is a DESCENDANT of our launched process (the gradle daemon would
                // fork the app in a process outside our tree — undetectable via pid-match and un-killable on
                // teardown). The direct-port probe handles detection either way; this keeps teardown clean.
                case "spring"  -> "gradle bootRun -q --console=plain --no-daemon --args='--server.port=" + port + "'";
                default -> null;
            };
        }
    }

    /**
     * Decide the archetype. HTTP only when a server framework is actually present in a manifest/source;
     * we do NOT infer "HTTP" from goal keywords alone (a spec can mention "endpoint" for a library).
     * Returns the deepest matching project root so a split-brain stub at the run-root is never booted.
     */
    private static Http detect(Path projectRoot, String goal) {
        // Spring: deepest build.gradle whose subtree declares spring-boot.
        Path spring = deepestManifestWith(projectRoot, List.of("build.gradle", "build.gradle.kts"), "org.springframework.boot");
        if (spring != null) return new Http("Spring Boot", "spring", spring, null);
        // Express: deepest package.json that depends on express AND has a start script.
        Path express = deepestManifestWith(projectRoot, List.of("package.json"), "\"express\"");
        if (express != null && readSafe(express.resolve("package.json")).contains("\"start\"")) {
            return new Http("Express", "express", express, null);
        }
        // FastAPI / Flask: the file that constructs the app; derive run-dir + module:var.
        PyTarget fastapi = pyTarget(projectRoot, FASTAPI_APP);
        if (fastapi != null) return new Http("FastAPI/uvicorn", "fastapi", fastapi.runDir, fastapi.target);
        PyTarget flask = pyTarget(projectRoot, FLASK_APP);
        if (flask != null) return new Http("Flask", "flask", flask.runDir, flask.target);
        return null;
    }

    /** The spec plainly asks for a running HTTP service — a named web framework or an explicit web/HTTP
     *  interface. Generic web-archetype signals, NOT a per-fixture match. Deliberately EXCLUDES the bare
     *  word "dashboard" (a TUI can be a "dashboard" too — rust-monitor): only used to decide whether a
     *  MISSING app is a failure, so it must never fire on a non-web archetype. */
    private static boolean wantsWebService(String goal) {
        if (goal == null) return false;
        String g = goal.toLowerCase();
        return g.contains("fastapi") || g.contains("flask") || g.contains("express")
                || g.contains("spring boot") || g.contains("spring-boot") || g.contains("django")
                || g.contains("web interface") || g.contains("web service") || g.contains("http service")
                || g.contains("http server") || g.contains("rest api");
    }

    /** First port the spec names (for an actionable message); null if none. */
    private static String specPort(String goal) {
        if (goal == null) return null;
        Matcher m = Pattern.compile("\\bport\\s+(\\d{2,5})", Pattern.CASE_INSENSITIVE).matcher(goal);
        return m.find() ? m.group(1) : null;
    }

    /** Deepest directory containing one of {@code names} whose content includes {@code needle}. */
    private static Path deepestManifestWith(Path root, List<String> names, String needle) {
        Path best = null;
        int bestDepth = -1;
        try (Stream<Path> walk = Files.walk(root, 8)) {
            for (Path p : (Iterable<Path>) walk.filter(BootCheck::notVendor)::iterator) {
                if (!Files.isRegularFile(p)) continue;
                if (!names.contains(p.getFileName().toString())) continue;
                if (!readSafe(p).contains(needle)) continue;
                int d = p.getNameCount();
                if (d > bestDepth) { bestDepth = d; best = p.getParent(); }
            }
        } catch (IOException ignored) {
            // unreadable tree → no match
        }
        return best;
    }

    /** A python boot target: the directory to run from + the {@code module:var} uvicorn/flask imports. */
    private record PyTarget(Path runDir, String target) {}

    /**
     * Find the deepest non-test source file constructing the app, and derive [runDir, module:var]:
     * the boot must run from the package's PARENT so {@code python -m uvicorn pkg.mod:app} resolves the
     * same way the app's own intra-package imports do.
     */
    private static PyTarget pyTarget(Path root, Pattern appPattern) {
        Path appFile = null;
        int bestDepth = -1;
        try (Stream<Path> walk = Files.walk(root, 10)) {
            for (Path p : (Iterable<Path>) walk.filter(BootCheck::notVendor)::iterator) {
                if (!Files.isRegularFile(p)) continue;
                String fn = p.getFileName().toString();
                if (!fn.endsWith(".py")) continue;
                if (fn.startsWith("test_") || fn.equals("conftest.py")) continue;
                if (!appPattern.matcher(readSafe(p)).find()) continue;
                int d = p.getNameCount();
                if (d > bestDepth) { bestDepth = d; appFile = p; }
            }
        } catch (IOException ignored) {
            // unreadable tree → no match
        }
        if (appFile == null) return null;
        Matcher m = appPattern.matcher(readSafe(appFile));
        if (!m.find()) return null;
        String var = m.group(1);
        // Climb to the top of the package (highest ancestor still holding __init__.py).
        Path top = appFile.getParent();
        while (top.getParent() != null && Files.isRegularFile(top.getParent().resolve("__init__.py"))) {
            top = top.getParent();
        }
        Path runDir = top.getParent() != null ? top.getParent() : top;
        String rel = runDir.relativize(appFile).toString();
        rel = rel.substring(0, rel.length() - 3);                  // strip .py
        String module = rel.replace('/', '.').replace('\\', '.');
        if (module.endsWith(".__init__")) module = module.substring(0, module.length() - ".__init__".length());
        return new PyTarget(runDir, module + ":" + var);
    }

    private static boolean notVendor(Path p) {
        for (Path seg : p) {
            String s = seg.toString();
            if (s.equals("node_modules") || s.equals(".git") || s.equals("build") || s.equals("target")
                    || s.equals(".venv") || s.equals("venv") || s.equals("__pycache__") || s.equals(".gradle")) {
                return false;
            }
        }
        return true;
    }

    // ---- process / port plumbing ---------------------------------------------------------------

    /** Wait until the app serves HTTP on a port (returns it), or the process dies / time runs out. */
    private static int waitForBind(Process proc, int hintPort, long deadline) {
        while (System.currentTimeMillis() < deadline) {
            // PRIMARY, pid-INDEPENDENT: the app was told to use hintPort (uvicorn --port / PORT= /
            // --server.port) and hintPort is a fixed sub-ephemeral port, so a listener there is ours —
            // seen even when served from the gradle daemon (outside our tree). Require a real HTTP
            // response, not just a TCP accept, so a transient non-HTTP socket (gradle's internal worker
            // port) never counts as "up".
            if (httpResponds(hintPort)) return hintPort;
            // FALLBACK: an app that ignored the hint and hardcoded a port — find it on our process tree.
            int p = listeningPort(treePids(proc), hintPort);
            if (p > 0 && httpResponds(p)) return p;
            // Process gone and nothing serving → one last probe, then give up (never-listen / crash).
            if (!proc.isAlive() && treeDead(proc)) {
                return httpResponds(hintPort) ? hintPort : -1;
            }
            sleep(700);
        }
        return -1;
    }

    /** A quick GET to {@code port}/ — true if ANY HTTP status came back (it's a live HTTP server). */
    private static boolean httpResponds(int port) {
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            c.send(req, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (Exception e) {
            return false;   // connection refused / non-HTTP socket / timeout → not (yet) a live server
        }
    }

    /** The set of pids in the launched process tree (bash + its children). */
    private static List<Long> treePids(Process proc) {
        List<Long> pids = new ArrayList<>();
        if (proc == null) return pids;
        try {
            pids.add(proc.pid());
            proc.descendants().forEach(h -> pids.add(h.pid()));
        } catch (Exception ignored) {
            // process already gone
        }
        return pids;
    }

    private static boolean treeDead(Process proc) {
        try {
            return proc.descendants().findAny().isEmpty();
        } catch (Exception e) {
            return true;
        }
    }

    /** Parse `ss -ltnpH` for a LISTEN socket owned by one of {@code pids}; prefer the hinted port. */
    private static int listeningPort(List<Long> pids, int hintPort) {
        if (pids.isEmpty()) return -1;
        String ss = capture("ss -ltnpH 2>/dev/null");
        if (ss.isBlank()) return -1;
        int any = -1;
        for (String line : ss.split("\\R")) {
            Matcher m = SS_LISTEN.matcher(line);
            while (m.find()) {
                long pid = Long.parseLong(m.group(2));
                if (!pids.contains(pid)) continue;
                int port = Integer.parseInt(m.group(1));
                if (port == hintPort) return port;     // exact: the app honored our hint
                any = port;
            }
        }
        return any;                                     // else whatever port the tree actually bound
    }

    // Fixed SUB-EPHEMERAL boot ports: outside the OS ephemeral range (32768+), so gradle's internal worker
    // sockets never collide with the port we hand the app; and clear of 8080/8090 (the model's own test/
    // smoke servers). Pick the first currently-free one.
    private static final int[] BOOT_PORTS = {8137, 8237, 8337, 8437, 8537, 8637, 8737};

    private static int freePort() {
        for (int p : BOOT_PORTS) {
            if (!portInUse(p)) return p;
        }
        return BOOT_PORTS[0];
    }

    private static boolean portInUse(int port) {
        try (ServerSocket s = new ServerSocket(port)) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private record HttpResult(int status, String body, boolean refused) {}

    private static HttpResult get(int port, String path, long deadline) {
        long left = Math.max(1500, deadline - System.currentTimeMillis());
        // First make sure the socket actually accepts (bound ≠ accepting).
        if (!tcpAccepts(port, (int) Math.min(left, 4000))) return new HttpResult(0, "", true);
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return new HttpResult(resp.statusCode(), resp.body() == null ? "" : resp.body(), false);
        } catch (Exception e) {
            return new HttpResult(0, "", true);
        }
    }

    private static boolean tcpAccepts(int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void teardown(Process proc, int port) {
        if (proc != null) {
            try {
                proc.descendants().forEach(ProcessHandle::destroyForcibly);
                proc.destroyForcibly();
                proc.waitFor(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                Thread.currentThread().interrupt();
            }
        }
        // belt-and-suspenders: free the port in case a grandchild outlived its parent group
        capture("fuser -k -9 " + port + "/tcp 2>/dev/null");
    }

    // ---- small utilities -----------------------------------------------------------------------

    private static Thread drain(Process proc, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (var in = proc.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    synchronized (sink) {
                        sink.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        if (sink.length() > 64_000) sink.delete(0, sink.length() - 48_000); // keep a tail
                    }
                }
            } catch (IOException ignored) {
                // stream closed on teardown
            }
        }, "bootcheck-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Run a quick shell command via the login shell, return stdout (best-effort, 8s cap). */
    private static String capture(String command) {
        try {
            Process p = Shell.pb(command).redirectErrorStream(true).start();
            String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(8, TimeUnit.SECONDS);
            return s;
        } catch (Exception e) {
            return "";
        }
    }

    private static String readSafe(Path p) {
        try {
            return Files.readString(p);
        } catch (Exception e) {
            return "";
        }
    }

    private static int safeExit(Process p) {
        try { return p.exitValue(); } catch (Exception e) { return -1; }
    }

    private static String tail(String s, int lines) {
        if (s == null || s.isBlank()) return "";
        ArrayDeque<String> dq = new ArrayDeque<>();
        for (String l : s.strip().split("\\R")) {
            dq.addLast(l);
            if (dq.size() > lines) dq.removeFirst();
        }
        return String.join("\n", dq);
    }

    private static String indent(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (String l : s.split("\\R")) b.append("    ").append(l).append('\n');
        return b.toString();
    }

    private static String sh(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
