package org.codezaiku.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.codezaiku.exec.Shell;

/**
 * Certifies an HTTP service by REAL execution: boot it on a freshly-allocated free port, wait
 * until it serves the expected CONTENT shape (not merely a 200 — a stray server on a busy box
 * would fool a status-only check; a dev box's :8080 is often already taken), run the ordered behavior
 * claims, then tear the process tree down. Network is on; nothing is pre-cached.
 */
public final class BehaviorDoorGate implements Gate {
    private static final Logger log = LoggerFactory.getLogger(BehaviorDoorGate.class);

    private final GateSpec spec;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private Path certifyRoot; // set per certify() — for STUB-PROOF derive commands (run in the project)

    public BehaviorDoorGate(GateSpec spec) {
        this.spec = spec;
    }

    @Override
    public GateResult certify(Path projectRoot) {
        this.certifyRoot = projectRoot;
        int port = freePort();
        String cmd = spec.bootCommand().replace("{port}", Integer.toString(port));
        Process proc = null;
        StringBuilder bootLog = new StringBuilder();
        StringBuilder ev = new StringBuilder();
        int total = spec.claims().size();
        try {
            log.info("gate[{}] booting on free port {} : {}", spec.name(), port, cmd);
            proc = Shell.pb(cmd)
                    .directory(projectRoot.toFile())
                    .redirectErrorStream(true)
                    .start();
            drainAsync(proc, bootLog);

            if (!awaitReady(port, proc)) {
                String why = (proc.isAlive() ? "did not respond (<400) at " + spec.readinessPath()
                        + " within " + spec.bootTimeoutSec() + "s"
                        : "boot process exited (code " + safeExit(proc) + ") before serving");
                return new GateResult(false, 0, total,
                        "BEHAVIOR DOOR: BOOT FAILED — app " + why + " on port " + port
                                + "\n--- boot log (tail) ---\n" + tail(bootLog.toString(), 4000));
            }

            Map<String, String> vars = new HashMap<>();
            int passed = 0;
            for (HttpClaim c : spec.claims()) {
                ClaimRun r = runClaim(port, c, vars);
                ev.append(r.line).append('\n');
                // A 5xx is meaningless to the model without the exception that produced it. Attach
                // THIS claim's server-side cause (the request-failure ERROR line embeds the message).
                if (r.status >= 500) {
                    sleep(200); // let the async log drain catch the just-logged exception
                    String cause = conciseCause(bootLog.toString());
                    if (!cause.isBlank()) ev.append("        ↳ server cause: ").append(cause).append('\n');
                }
                if (r.pass) {
                    passed++;
                    if (c.captureField() != null) {
                        String v = fieldOf(r.body, c.captureField());
                        if (v != null) vars.put(c.captureField(), v);
                    }
                }
            }
            boolean pass = passed == total;
            String head = "BEHAVIOR DOOR: " + (pass ? "PASS" : "FAIL") + " " + passed + "/" + total
                    + " (live, port " + port + ")\n";
            return new GateResult(pass, passed, total, head + ev);
        } catch (Exception e) {
            return new GateResult(false, 0, total,
                    "BEHAVIOR DOOR: GATE ERROR — " + e + "\n" + ev
                            + "\n--- boot log (tail) ---\n" + tail(bootLog.toString(), 2000));
        } finally {
            teardown(proc, port);
        }
    }

    private record ClaimRun(boolean pass, int status, String line, String body) {
    }

    private ClaimRun runClaim(int port, HttpClaim c, Map<String, String> vars) {
        String path = interpolate(c.path(), vars);
        if (path.contains("{")) {
            // A needed value (e.g. {id}) was never captured because an earlier claim failed.
            // Say so plainly instead of firing a malformed request.
            return new ClaimRun(false, -1, "  [✗] " + c.id() + " " + c.method() + " " + path
                    + " → SKIPPED: a prior claim failed to provide " + path.substring(path.indexOf('{')), "");
        }
        String url = "http://localhost:" + port + path;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
            switch (c.method()) {
                case "GET" -> b.GET();
                case "DELETE" -> b.DELETE();
                case "POST" -> b.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(c.bodyJson() == null ? "" : c.bodyJson()));
                default -> b.method(c.method(), HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            String body = resp.body() == null ? "" : resp.body();
            // STUB-PROOF: real-input ground-truth(s) (derived at gate-time) the model couldn't hardcode
            // must ALL appear in the response — defeats canned/empty responses; a list = "real means all
            // N fields" for a multi-concern endpoint.
            List<String> found = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String cmd : c.deriveCommands()) {
                String v = runCapture(cmd);
                if (v == null || v.isBlank()) continue;
                if (body.contains(v)) found.add(v); else missing.add(v + " (`" + cmd + "`)");
            }
            boolean statusOk = resp.statusCode() == c.expectStatus();
            boolean bodyOk = c.bodyCheck().test().test(body);
            boolean derivedOk = missing.isEmpty();
            boolean ok = statusOk && bodyOk && derivedOk;
            String detail;
            if (ok) {
                detail = "OK" + (found.isEmpty() ? "" : " (shows real " + found + ")");
            } else if (!statusOk) {
                detail = "FAIL expected " + c.expectStatus() + " got " + resp.statusCode();
            } else if (!bodyOk) {
                detail = "FAIL body check [" + c.bodyCheck().label() + "] on: " + snippet(body);
            } else {
                detail = "FAIL missing REAL ground-truth " + missing
                        + (found.isEmpty() ? "" : " (found " + found + ")")
                        + " — response looks hardcoded/empty, not derived from the real input";
            }
            return new ClaimRun(ok, resp.statusCode(), "  [" + (ok ? "✓" : "✗") + "] " + c.id() + " "
                    + c.method() + " " + path + " → " + detail, body);
        } catch (Exception e) {
            return new ClaimRun(false, -1, "  [✗] " + c.id() + " " + c.method() + " " + path
                    + " → FAIL request error: " + e.getMessage(), "");
        }
    }

    /** Derive a real-input ground-truth at gate-time (run in the project) — first non-blank output line. */
    private String runCapture(String cmd) {
        try {
            Process p = Shell.pb(cmd)
                    .directory((certifyRoot == null ? Path.of(".") : certifyRoot).toFile())
                    .redirectErrorStream(true).start();
            String out;
            try (var in = p.getInputStream()) { out = new String(in.readAllBytes()); }
            p.waitFor(30, TimeUnit.SECONDS);
            for (String line : out.split("\\R")) {
                String t = line.strip();
                if (!t.isEmpty()) return t;
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private boolean awaitReady(int port, Process proc) {
        long deadline = System.nanoTime() + Duration.ofSeconds(spec.bootTimeoutSec()).toNanos();
        String url = "http://localhost:" + port + spec.readinessPath();
        while (System.nanoTime() < deadline) {
            if (!proc.isAlive()) return false;
            try {
                http.send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                // ANY HTTP response means the server is up and accepting requests (the port is ours,
                // exclusively, so even a 404 is our app — the path just differs). "Up" is separate
                // from "the readiness path works": the claims report the honest per-path status, so a
                // moved endpoint surfaces as "expected 200 got 404", never a misleading BOOT FAILED.
                return true;
            } catch (Exception ignored) {
                // not up yet
            }
            sleep(1000);
        }
        return false;
    }

    private void teardown(Process proc, int port) {
        if (proc != null) {
            proc.descendants().forEach(ProcessHandle::destroyForcibly);
            proc.destroyForcibly();
            try {
                proc.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // backstop: free the port in case the app JVM detached from our tree
        try {
            Shell.pb("fuser -k " + port + "/tcp")
                    .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // best effort
        }
    }

    private static void drainAsync(Process proc, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (var in = proc.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    synchronized (sink) {
                        sink.append(new String(buf, 0, n));
                        if (sink.length() > 16_000) sink.delete(0, sink.length() - 16_000);
                    }
                }
            } catch (Exception ignored) {
                // process ended
            }
        }, "gate-boot-drain");
        t.setDaemon(true);
        t.start();
    }

    private String fieldOf(String body, String field) {
        try {
            var node = json.readTree(body).get(field);
            return node == null ? null : node.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static String interpolate(String path, Map<String, String> vars) {
        String out = path;
        for (var e : vars.entrySet()) out = out.replace("{" + e.getKey() + "}", e.getValue());
        return out;
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException("could not allocate a free port", e);
        }
    }

    private static int safeExit(Process p) {
        try {
            return p.exitValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private static String snippet(String s) {
        s = s.replaceAll("\\s+", " ").strip();
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    /**
     * The most recent request-failure cause, concise. Spring logs failed requests at ERROR with the
     * exception message embedded ("...: not-null property references a null...] with root cause"),
     * followed by the root exception class+message — return those two lines. Anchors on the actual
     * failure marker, never a blind tail.
     */
    private static String conciseCause(String log) {
        if (log == null || log.isBlank()) return "";
        int i = log.lastIndexOf(" ERROR ");
        if (i < 0) i = log.lastIndexOf("Exception");
        if (i < 0) return "";
        int start = log.lastIndexOf('\n', i) + 1;
        int line1End = log.indexOf('\n', i);
        if (line1End < 0) line1End = log.length();
        int line2End = log.indexOf('\n', line1End + 1);
        if (line2End < 0) line2End = log.length();
        String s = log.substring(start, line2End).strip();
        return s.length() > 600 ? s.substring(0, 600) + "…" : s;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
