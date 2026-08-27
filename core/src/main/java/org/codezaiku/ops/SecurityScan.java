package org.codezaiku.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SECURITY POSTURE SENSOR — the security sibling of {@link ProactiveScan}. Deterministic checks only,
 * no model: each finding is a fact a machine computed, so it cannot be argued with, hallucinated, or
 * steered by anything an attacker wrote into a log.
 *
 * <p>Scope is DEFENSIVE and local: misconfiguration and exposure on infrastructure the operator owns.
 * Checks map to CIS-Docker-Benchmark items (the survey's recommended oracle family) but are implemented
 * inline so a scan needs no extra tooling installed; where a real scanner IS present (trivy/grype) its
 * machine-readable output is preferred over our approximation.
 *
 * <p>AUTHORITY — findings are REPORTED, never auto-fixed, and the caller must keep them at PROPOSE.
 * Security remediation is qualitatively more destructive than reliability remediation: a wrong reliability
 * fix restarts a service, a wrong containment action firewalls your own load balancer or deletes a key.
 * Until we have a measured false-positive rate, a human decides.
 */
public final class SecurityScan {

    /** {@code severity} = high|med|low; {@code cis} = the CIS Docker Benchmark item where one applies. */
    public record Finding(String check, String subject, String severity, String detail, String cis) {
        public String line() {
            return "[" + severity + "] " + check + " " + subject + " — " + detail
                    + (cis.isBlank() ? "" : "  (CIS " + cis + ")");
        }
    }

    private final Exec exec;

    public SecurityScan(Exec exec) { this.exec = exec; }

    public List<Finding> scan() {
        List<Finding> out = new ArrayList<>();
        privilegedContainers(out);
        dockerSocketMounts(out);
        exposedPorts(out);
        weakEnvSecrets(out);
        rootUserContainers(out);
        dangerousCapabilities(out);
        exposedDockerApi(out);
        writableHostMounts(out);
        imageVulnerabilities(out);
        return out;
    }

    /** Capabilities that are equivalent to host root even without --privileged. */
    private void dangerousCapabilities(List<Finding> out) {
        Exec.Result r = exec.run("docker ps --format '{{.Names}}' | while read c; do "
                + "caps=$(docker inspect -f '{{join .HostConfig.CapAdd \",\"}}' \"$c\" 2>/dev/null); "
                + "case \"$caps\" in *SYS_ADMIN*|*SYS_PTRACE*|*SYS_MODULE*|*DAC_READ_SEARCH*|*ALL*) "
                + "echo \"$c $caps\";; esac; done", 30);
        if (r.out() == null || r.out().isBlank()) return;
        for (String line : nonEmptyLines(r.out())) {
            String[] f = line.split("\\s+", 2);
            out.add(new Finding("dangerous-capability", f[0], "high",
                    "granted " + (f.length > 1 ? f[1] : "?") + " — equivalent to host root without needing "
                    + "--privileged (SYS_ADMIN mounts, SYS_MODULE loads kernel code, SYS_PTRACE reads other "
                    + "processes' memory)", "5.3"));
        }
    }

    /** The Docker daemon listening on TCP: unauthenticated remote container creation = host takeover. */
    private void exposedDockerApi(List<Finding> out) {
        // ss is Linux-only; lsof is the macOS equivalent and reports the same fact.
        Exec.Result r = exec.run(TargetOs.listeningProbe(exec, ":(2375|2376)\\b",
                "-iTCP:2375 -iTCP:2376"), 20);
        if (r.out() == null || r.out().isBlank()) return;
        for (String line : nonEmptyLines(r.out())) {
            boolean tls = line.contains(":2376");
            boolean allIface = line.contains("0.0.0.0:") || line.contains("[::]:");
            if (!allIface) continue;
            out.add(new Finding("docker-api-exposed", tls ? "tcp/2376" : "tcp/2375",
                    tls ? "med" : "high",
                    tls ? "Docker API on all interfaces (TLS port) — verify client-cert auth is enforced"
                        : "Docker API on all interfaces WITHOUT TLS — anyone who can reach this port can "
                          + "create a privileged container and own the host", "2.x"));
        }
    }

    /** Host paths bind-mounted read-write into a container — a container-to-host escape route. */
    private void writableHostMounts(List<Finding> out) {
        Exec.Result r = exec.run("docker ps --format '{{.Names}}' | while read c; do "
                + "docker inspect -f '{{range .Mounts}}{{if and (eq .Type \"bind\") .RW}}"
                + "{{$.Name}}|{{.Source}}\n{{end}}{{end}}' \"$c\" 2>/dev/null; done", 40);
        if (r.out() == null || r.out().isBlank()) return;
        Set<String> sensitive = Set.of("/", "/etc", "/usr", "/bin", "/sbin", "/boot",
                "/var/lib/docker", "/root", "/home", "/var/run");
        for (String line : nonEmptyLines(r.out())) {
            int bar = line.indexOf('|');
            if (bar < 0) continue;
            String container = line.substring(0, bar).replaceFirst("^/", "");
            String src = line.substring(bar + 1).trim();
            if (!sensitive.contains(src)) continue;
            out.add(new Finding("writable-host-mount", container, "high",
                    "mounts host path " + src + " READ-WRITE — a compromised process in this container can "
                    + "modify the host filesystem", "5.5"));
        }
    }

    // ---- container privilege ---------------------------------------------------

    // NB: every probe below is judged on its OUTPUT, never its exit code. These pipelines end in a test
    // (`[ "$p" = true ] && echo`) that legitimately exits non-zero when the LAST item doesn't match, so a
    // scan full of real findings still returns 1. Gating on exit code silently discarded them — measured:
    // 7 privileged containers reported as none. Same shape as the documented `grep -c` exit-1 bug.
    private void privilegedContainers(List<Finding> out) {
        Exec.Result r = exec.run("docker ps --format '{{.Names}}' | while read c; do "
                + "p=$(docker inspect -f '{{.HostConfig.Privileged}}' \"$c\" 2>/dev/null); "
                + "[ \"$p\" = true ] && echo \"$c\"; done", 30);
        if (r.out() == null || r.out().isBlank()) return;
        for (String c : nonEmptyLines(r.out()))
            out.add(new Finding("privileged-container", c, "high",
                    "runs --privileged: full host capabilities, effectively root on the host if compromised",
                    "5.4"));
    }

    private void dockerSocketMounts(List<Finding> out) {
        Exec.Result r = exec.run("docker ps --format '{{.Names}}' | while read c; do "
                + "docker inspect -f '{{range .Mounts}}{{.Source}} {{end}}' \"$c\" 2>/dev/null "
                + "| grep -q docker.sock && echo \"$c\"; done", 30);
        if (r.out() == null || r.out().isBlank()) return;
        for (String c : nonEmptyLines(r.out()))
            out.add(new Finding("docker-socket-mount", c, "high",
                    "mounts /var/run/docker.sock: container can create privileged containers = host takeover",
                    "5.31"));
    }

    private void rootUserContainers(List<Finding> out) {
        Exec.Result r = exec.run("docker ps --format '{{.Names}}' | while read c; do "
                + "u=$(docker inspect -f '{{.Config.User}}' \"$c\" 2>/dev/null); "
                + "[ -z \"$u\" ] || [ \"$u\" = root ] || [ \"$u\" = 0 ] && echo \"$c\"; done", 30);
        if (r.out() == null || r.out().isBlank()) return;
        List<String> names = nonEmptyLines(r.out());
        if (names.size() > 3)   // a whole-stack finding, not one line per container
            out.add(new Finding("containers-run-as-root", names.size() + " containers", "med",
                    "no USER set — processes run as uid 0 inside the container: " + String.join(", ",
                            names.subList(0, Math.min(6, names.size()))) + (names.size() > 6 ? ", …" : ""),
                    "4.1"));
        else
            for (String c : names)
                out.add(new Finding("container-runs-as-root", c, "med",
                        "no USER set — processes run as uid 0 inside the container", "4.1"));
    }

    // ---- exposure --------------------------------------------------------------

    private void exposedPorts(List<Finding> out) {
        // Published on ALL interfaces (0.0.0.0/::) = reachable from off-box. Datastore ports are the
        // dangerous ones: an exposed database with default creds is the classic breach path.
        Exec.Result r = exec.run("docker ps --format '{{.Names}}\t{{.Ports}}'", 25);
        if (r.out() == null || r.out().isBlank()) return;
        for (String line : nonEmptyLines(r.out())) {
            String[] f = line.split("\t", 2);
            if (f.length < 2 || !f[1].contains("0.0.0.0:")) continue;
            for (String part : f[1].split(",")) {
                Matcher m = Pattern
                        .compile("0\\.0\\.0\\.0:(\\d+)->(\\d+)").matcher(part);
                if (!m.find()) continue;
                int container = Integer.parseInt(m.group(2));
                String svc = datastorePort(container);
                if (svc != null)
                    out.add(new Finding("datastore-exposed", f[0], "high",
                            svc + " port " + container + " published on 0.0.0.0:" + m.group(1)
                            + " — reachable from outside the host; bind to 127.0.0.1 unless it must be remote",
                            "5.7"));
            }
        }
    }

    private static String datastorePort(int p) {
        return switch (p) {
            case 5432 -> "postgres"; case 3306 -> "mysql"; case 6379 -> "redis";
            case 27017 -> "mongodb"; case 9200 -> "elasticsearch/opensearch"; case 7687 -> "neo4j";
            case 5672, 15672 -> "rabbitmq"; case 9092 -> "kafka"; case 2379 -> "etcd";
            case 11211 -> "memcached"; case 6333 -> "qdrant"; case 8123, 9000 -> "clickhouse";
            default -> null;
        };
    }

    // ---- credentials -----------------------------------------------------------

    /** Default / empty / trivially-guessable secrets in container env — the single most exploited
     *  misconfiguration. Values are NEVER echoed; only the variable name and the reason. */
    private void weakEnvSecrets(List<Finding> out) {
        Exec.Result r = exec.run("docker ps --format '{{.Names}}' | while read c; do "
                + "docker inspect -f '{{range .Config.Env}}{{$.Name}}|{{.}}"
                + "\n{{end}}' \"$c\" 2>/dev/null; done", 40);
        if (r.out() == null || r.out().isBlank()) return;
        for (String line : nonEmptyLines(r.out())) {
            int bar = line.indexOf('|'), eq = line.indexOf('=', bar + 1);
            if (bar < 0 || eq < 0) continue;
            String container = line.substring(0, bar).replaceFirst("^/", "");
            String key = line.substring(bar + 1, eq).toUpperCase(Locale.ROOT);
            String val = line.substring(eq + 1).trim();
            if (!key.matches(".*(PASSWORD|PASSWD|SECRET|TOKEN|APIKEY|API_KEY|ACCESS_KEY).*")) continue;
            String why = weakReason(key, val);
            if (why != null)
                out.add(new Finding("weak-credential", container + " " + key, "high", why, "5.10"));
        }
    }

    private static String weakReason(String key, String val) {
        if (val.isEmpty()) return "empty value — authentication effectively disabled";
        String v = val.toLowerCase(Locale.ROOT);
        Set<String> common = Set.of("password", "passwd", "secret", "changeme",
                "admin", "root", "test", "postgres", "mysql", "redis", "guest", "123456", "password123",
                "letmein", "default", "example", "dev", "local");
        if (common.contains(v)) return "a default/common value — trivially guessable";
        if (v.length() < 8) return "shorter than 8 characters — brute-forceable";
        if (v.matches("[a-z]+") || v.matches("\\d+")) return "single character class — weak";
        return null;
    }

    // ---- known vulnerabilities -------------------------------------------------

    /** Prefer a real scanner when the box has one (its DB is current); otherwise say so rather than
     *  guessing — a model's CVE knowledge is stale by construction and "no CVEs" from memory is a
     *  dangerous answer. */
    private void imageVulnerabilities(List<Finding> out) {
        // Resolve the binary rather than trusting PATH: scanners are commonly dropped in a local bin dir
        // that a non-interactive shell never sources (measured: a trivy in a local bin dir was invisible
        // `command -v`, so the scan silently reported "no scanner" while one was installed).
        Exec.Result which = exec.run("command -v trivy || command -v grype || "
                + "for p in /usr/local/bin/trivy /opt/bin/trivy $HOME/bin/trivy /opt/tools/trivy; do "
                + "[ -x \"$p\" ] && { echo \"$p\"; break; }; done", 15);
        String bin = which.ok() ? which.out().trim().split("\n")[0].trim() : "";
        if (bin.isEmpty()) {
            out.add(new Finding("no-vulnerability-scanner", "host", "low",
                    "no trivy/grype installed — image CVEs are NOT being checked. Install trivy for "
                    + "machine-readable image scanning; do not infer CVE status from model knowledge", ""));
            return;
        }
        Exec.Result imgs = exec.run("docker ps --format '{{.Image}}' | sort -u | head -12", 20);
        for (String img : nonEmptyLines(imgs.out())) {
            Exec.Result r = exec.run(bin + " image --quiet --scanners vuln --severity CRITICAL "
                    + "--format json " + img + " 2>/dev/null | head -c 400000", 420);
            if (!r.ok() || r.out().isBlank()) continue;
            int crit = countMatches(r.out(), "\"Severity\": \"CRITICAL\"");
            if (crit > 0) {
                // name the worst CVE so the finding is actionable without re-running the scanner
                Matcher m = Pattern
                        .compile("\"VulnerabilityID\"\\s*:\\s*\"(CVE-[0-9-]+)\"").matcher(r.out());
                String first = m.find() ? m.group(1) : "";
                out.add(new Finding("image-critical-cves", img, "high",
                        crit + " CRITICAL CVE(s) in the running image"
                        + (first.isEmpty() ? "" : ", e.g. " + first)
                        + " — rebuild/update the base image (" + bin + " image " + img + " for the list)", ""));
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------

    private static List<String> nonEmptyLines(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String l : s.split("\n")) {
            String t = l.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static int countMatches(String hay, String needle) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }
}
