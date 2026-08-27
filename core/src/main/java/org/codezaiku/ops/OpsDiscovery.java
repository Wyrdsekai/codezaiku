package org.codezaiku.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SCOPE DISCOVERY — makes "point CodeZaiku at a stack and go" work on ANY compose project, not just a
 * hand-wired one. The two things that were stack-specific — the app readiness endpoint and the verify
 * command — are DISCOVERED/DERIVED here from the compose project itself, so the trigger only needs the
 * scope (which project) and the authority ceiling.
 */
public final class OpsDiscovery {
    private static final Logger log = LoggerFactory.getLogger(OpsDiscovery.class);
    private OpsDiscovery() { }

    // A web/edge service likely to expose a readiness endpoint. First-choice for the app-health probe.
    private static final Pattern WEBISH = Pattern.compile(
            "\\b(app|web|api|gateway|frontend|edge|http|server|backend|service|nginx|caddy|traefik)\\b",
            Pattern.CASE_INSENSITIVE);
    // /ready (a DEEP end-to-end readiness that catches 'up but wrong') is preferred over the binary /health —
    // it surfaces degraded-but-passing-write-probes faults the shallow probe misses.
    private static final String[] HEALTH_PATHS = {"/ready", "/readyz", "/health", "/healthz", "/status", "/"};
    // published host port from a compose Ports cell like "0.0.0.0:28000->8000/tcp, :::28000->8000/tcp"
    private static final Pattern PUB_PORT = Pattern.compile("(?:0\\.0\\.0\\.0|127\\.0\\.0\\.1|\\[::\\]):(\\d+)->");

    /**
     * Discover a readiness endpoint for {@code project}: probe the published HTTP ports of its web-ish
     * services (then any service) for a health-like path that answers 2xx. Returns the URL or null.
     */
    public static String appHealthUrl(Exec exec, String project) {
        // An explicit setting wins — CONFIGURATION.md promises exactly that, and this method was
        // breaking the promise. The fix loop asks here for the endpoint that decides whether the stack
        // is still degraded, so ignoring the operator's URL let a round end with "stack GREEN" while
        // the endpoint they nominated was returning 503. Measured on a live stack.
        String explicit = org.codezaiku.Config.get("CODEZAIKU_OPS_APP_HEALTH");
        if (explicit != null && !explicit.isBlank()) return explicit;
        if (project == null || project.isBlank()) return null;
        Exec.Result r = exec.run("docker ps --filter label=com.docker.compose.project=" + project
                + " --format '{{.Label \"com.docker.compose.service\"}}\\t{{.Ports}}'", 15);
        if (!r.ok() || r.out().isBlank()) return null;
        List<String[]> webish = new ArrayList<>(), other = new ArrayList<>();   // [service, hostPort]
        for (String line : r.out().split("\n")) {
            String[] p = line.split("\t", 2);
            if (p.length < 2 || p[0].isBlank()) continue;
            Matcher m = PUB_PORT.matcher(p[1]);
            while (m.find()) {
                String[] sp = {p[0].trim(), m.group(1)};
                (WEBISH.matcher(p[0]).find() ? webish : other).add(sp);
            }
        }
        List<String[]> ordered = new ArrayList<>(webish); ordered.addAll(other);
        for (String[] sp : ordered) {
            for (String path : HEALTH_PATHS) {
                String url = "http://localhost:" + sp[1] + path;
                Exec.Result h = exec.run("curl -s -m5 -o /dev/null -w '%{http_code}' " + url, 8);
                if (h.ok() && h.out().trim().startsWith("2")) {
                    log.info("discovered app-health endpoint: {} (service {})", url, sp[0]);
                    return url;
                }
            }
        }
        return null;
    }

    /**
     * Derive an objective verify command for "is the incident resolved?". With a readiness endpoint, check
     * its body for a healthy status (and absence of a failing one) — an app may answer HTTP 200 while
     * reporting {@code "status":"degraded"} in the body, so the code alone is not enough. Without one, fall
     * back to "no container in the project is unhealthy". The caller may always override.
     */
    public static String deriveVerify(String appHealthUrl, String project) {
        if (appHealthUrl != null && !appHealthUrl.isBlank()) {
            return "bash -c 'b=$(curl -s -m8 " + appHealthUrl + "); "
                    + "echo \"$b\" | grep -qiE \"\\\"status\\\" *: *\\\"(ok|up|healthy|pass|green|running)\\\"\" "
                    + "|| { [ -n \"$b\" ] && ! echo \"$b\" | grep -qiE \"down|degraded|unhealthy|error|fail\"; }'";
        }
        if (project != null && !project.isBlank()) {
            return "bash -c '[ \"$(docker ps -a --filter label=com.docker.compose.project=" + project
                    + " --format \"{{.Status}}\" | grep -ci unhealthy)\" = 0 ]'";
        }
        return null;
    }

    /**
     * A verify SCOPED TO THE ROOT service, derived after localization. Critical for multi-fault stacks: a
     * whole-app verify stays red while ANY other fault remains, so a successful per-service fix would be
     * judged failed and rolled back. This checks only that THE ROOT recovered: its dependency is "ok" in the
     * app-health report (or, without one, its container is healthy/running).
     */
    public static String deriveVerifyForRoot(String appHealthUrl, String root, String container) {
        if (appHealthUrl != null && !appHealthUrl.isBlank() && root != null && !root.isBlank()) {
            String dep = root.equals("localstack") ? "s3|sqs" : root;   // the app names s3/sqs, not localstack
            return "bash -c 'curl -s -m8 " + appHealthUrl + " | grep -qE \"\\\"(" + dep + ")\\\" *: *\\\"ok\\\"\"'";
        }
        if (container != null && !container.isBlank()) {
            return "bash -c 'docker inspect -f \"{{if .State.Health}}{{.State.Health.Status}}{{else}}"
                    + "{{.State.Status}}{{end}}\" " + container + " | grep -qE \"healthy|running\"'";
        }
        return null;
    }

    /** Normalize a scope arg to a compose project name: {@code compose:<p>} / {@code <p>} → {@code <p>}. */
    public static String composeProject(String scope) {
        if (scope == null) return null;
        String s = scope.strip();
        if (s.toLowerCase(Locale.ROOT).startsWith("compose:")) return s.substring(8).strip();
        return s.isBlank() ? null : s;
    }

    /** WHERE + WHAT the trigger acts on. {@code target} is the {@link Exec} spec (local or ssh://…); the whole
     *  pipeline (docker ps, curl localhost:port/health, docker exec fixes) runs THROUGH it, so a remote host is
     *  just a different exec — the operator/LLM still runs centrally. {@code project} is the compose project
     *  ON that box. */
    public record Scope(String target, String project) { }

    /**
     * Parse a scope arg:
     * <pre>
     *   &lt;project&gt; | compose:&lt;project&gt;          → local docker, that compose project
     *   ssh://[user@]host[:port]/&lt;project&gt;           → that compose project on a remote host over ssh
     * </pre>
     */
    public static Scope parseScope(String scope) {
        if (scope == null || scope.isBlank()) return new Scope("local", null);
        String s = scope.strip();
        if (s.startsWith("ssh://")) {
            int slash = s.indexOf('/', "ssh://".length());
            if (slash > 0) return new Scope(s.substring(0, slash), composeProject(s.substring(slash + 1)));
            return new Scope(s, null);            // ssh://host with no /project → whole-box (no compose scope)
        }
        return new Scope("local", composeProject(s));
    }
}
