package org.codezaiku.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.codezaiku.Config;

/**
 * Deterministic pre-checks (k8sgpt's analyzer pattern + opensre's seed-calls, PLAN_CODEZAIKU_OPS.md §3):
 * a handful of cheap rules run ONCE against the box BEFORE the model spends a turn, each surfacing a
 * {@code Finding} with the evidence that triggered it. The findings block is injected into the loop's
 * first prompt so the 9B starts already pointed at the anomalies (and doesn't burn turns rediscovering
 * "disk is full"). Pure narrowing — it never concludes; the model still has to verify and reason.
 */
public final class Precheck {

    public record Finding(String name, String severity, String evidence) {
        @Override public String toString() { return "[" + severity + "] " + name + " — " + evidence; }
    }

    private final Exec exec;

    public Precheck(Exec exec) { this.exec = exec; }

    // When set, the box runs a docker-compose stack — enumerate its services' health (the compose analog of
    // systemctl --failed, and the single highest-value localizer for a multi-service box).
    private static final String COMPOSE_PROJECT = Config.get("CODEZAIKU_OPS_COMPOSE_PROJECT");

    public List<Finding> run() {
        List<Finding> f = new ArrayList<>();
        composeHealth(f);
        recentChanges(f);
        diskFull(f);
        servicesDown(f);
        portConflicts(f);
        oom(f);
        certExpiry(f);
        badConfig(f);
        return f;
    }

    /**
     * Compose-stack health enumeration (scoped to CODEZAIKU_OPS_COMPOSE_PROJECT so unrelated containers on
     * the host are ignored). Flags every service that is not running-and-healthy, and pulls its LAST
     * healthcheck output — which typically names the failing dependency ("backend unhealthy: cannot reach
     * redis:6379"), localizing a 29-service stack to one service in one step. Also surfaces the declared
     * depends_on graph as topology grounding.
     */
    private void composeHealth(List<Finding> f) {
        if (COMPOSE_PROJECT == null || COMPOSE_PROJECT.isBlank()) return;
        String filt = "--filter label=com.docker.compose.project=" + COMPOSE_PROJECT;
        Exec.Result r = exec.run("docker ps -a " + filt
                + " --format '{{.Label \"com.docker.compose.service\"}}\\t{{.State}}\\t{{.Status}}\\t{{.Names}}'", 25);
        if (!r.ok() || r.out().isBlank()) return;
        StringBuilder roster = new StringBuilder();
        int n = 0, sickN = 0;
        for (String line : r.out().trim().split("\n")) {
            String[] p = line.split("\t");
            if (p.length < 4) continue;
            String svc = p[0].trim(), state = p[1].trim(), status = p[2].trim(), cname = p[3].trim();
            n++;
            roster.append("    - ").append(svc).append(": ").append(status).append('\n');
            boolean sick = !state.equals("running") || status.contains("unhealthy")
                    || status.contains("Restarting") || state.equals("exited") || state.equals("dead");
            if (!sick) continue;
            sickN++;
            String detail = svc + " is " + state + " (" + status + ")";
            // Pull the last healthcheck output — it usually names WHY (which dependency failed).
            Exec.Result hl = exec.run("docker inspect --format "
                    + "'{{if .State.Health}}{{range .State.Health.Log}}{{.Output}}{{end}}{{end}}' "
                    + cname + " 2>/dev/null | tr -d '\\r' | grep . | tail -1", 15);
            if (hl.ok() && !hl.out().isBlank()) detail += " — last health: " + hl.out().trim();
            f.add(new Finding("service_unhealthy", "critical", detail));
        }
        if (n > 0) {
            // Where the stack is DECLARED. Docker records the compose file + working dir as labels; surface
            // them, because without the file path the model cannot run `docker compose ...` to act, and it
            // will burn its whole budget doing `find / -name docker-compose.yml` (observed: remediation spent
            // all 26 turns hunting for the file and never applied a fix). Diagnosis never needed it —
            // ps/logs/inspect work by container name — but remediation does.
            Exec.Result meta = exec.run("docker ps -a " + filt + " --format "
                    + "'{{.Label \"com.docker.compose.project.config_files\"}}' | head -1", 15);
            // NOTE: state the compose file as a neutral FACT only. Do NOT put an action hint ("you can
            // docker start <name>") here — this block is also shown during the READ-ONLY diagnosis phase,
            // where mutation is blocked; telling the diagnostician it may act is incoherent and measurably
            // hurt diagnosis (3/3 -> 1/3). Action guidance belongs in the remediation prompt.
            String where = (meta.ok() && !meta.out().isBlank())
                    ? "\n  compose file: " + meta.out().trim()
                    : "";
            // Always surface the full roster (ground truth) — for a HEALTHY stack this shows all green, so
            // the model can correctly conclude "healthy" instead of inventing a fault.
            f.add(0, new Finding("compose_stack", sickN == 0 ? "info" : "warning",
                    "docker-compose project '" + COMPOSE_PROJECT + "' — " + n + " services, " + sickN
                    + " unhealthy/down:\n" + roster.toString().stripTrailing() + where));
        }
    }

    /** Render the findings as the prompt-injected block (empty string when nothing tripped). */
    public String block() {
        List<Finding> f = run();
        if (f.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("AUTOMATED PRE-CHECKS (deterministic, run before you started — "
                + "each is a SIGNAL to verify, not a conclusion; some may be red herrings):\n");
        for (Finding x : f) sb.append("  • ").append(x).append('\n');
        return sb.toString();
    }

    /**
     * THE "WHAT CHANGED?" AXIS. Most real incidents are caused by a recent change — a config edit, a package
     * upgrade, a container recreated — and correlating the outage with the change is the fastest path to the
     * root cause (and the one a human reaches for first). The model does not pull this on its own, so we PUSH
     * it (same lesson as the runbooks). Deliberately HIGH-PRECISION: system churn (/etc/hosts, resolv.conf,
     * caches, locks) is filtered out, and the list is capped — a noisy precheck actively misleads a 9B.
     * Toggle off with CODEZAIKU_OPS_CHANGES=off (used to measure this signal's lift).
     */
    private void recentChanges(List<Finding> f) {
        if ("off".equalsIgnoreCase(Config.get("CODEZAIKU_OPS_CHANGES"))) return;

        // 1) Config/app files modified in the last 24h, newest first, with timestamps to correlate against.
        Exec.Result r = exec.run(
            "find /etc /opt /srv /usr/local/etc -xdev -type f -mmin -1440 "
          + "-printf '%TY-%Tm-%Td %TH:%TM  %p\\n' 2>/dev/null "
          + "| grep -vE '/(hosts|hostname|resolv\\.conf|mtab|ld\\.so\\.cache|\\.pwd\\.lock|machine-id)$' "
          + "| grep -vE '/(cache|tmp|run|lib/systemd)/' "
          + "| sort -r | head -10", 25);
        if (r.ok() && !r.out().isBlank()) {
            StringBuilder sb = new StringBuilder("files changed in the last 24h (a recent change is the "
                    + "most common cause of a new outage — check whether one of these explains it):\n");
            for (String line : r.out().trim().split("\n")) {
                if (!line.isBlank()) sb.append("    - ").append(line.trim()).append('\n');
            }
            f.add(new Finding("recent_change", "info", sb.toString().stripTrailing()));
        }

        // 2) Packages installed/upgraded recently.
        Exec.Result p = exec.run("grep -hE ' (install|upgrade) ' /var/log/dpkg.log 2>/dev/null | tail -5", 15);
        if (p.ok() && !p.out().isBlank()) {
            f.add(new Finding("recent_change", "info",
                    "packages recently installed/upgraded:\n    " + p.out().trim().replace("\n", "\n    ")));
        }

        // 3) Containers (re)started recently — a restarted/recreated service is a change too.
        Exec.Result c = exec.run("docker ps --format '{{.Names}}\\t{{.RunningFor}}' 2>/dev/null "
                + "| grep -iE 'second|minute' | head -6", 20);
        if (c.ok() && !c.out().isBlank()) {
            f.add(new Finding("recent_change", "info",
                    "containers started very recently:\n    " + c.out().trim().replace("\n", "\n    ")));
        }
    }

    private void diskFull(List<Finding> f) {
        // Flag a mount at >=90%, but suppress the false signals a container introduces:
        //  - by TYPE: overlay/squashfs/devtmpfs (the container's own root reflects the HOST docker disk).
        //  - by MOUNTPOINT: the ephemeral pseudo-mounts (/dev,/proc,/sys,/run,/dev/shm,/tmp) and the docker
        //    single-file bind mounts (/etc/hosts,hostname,resolv.conf) — df reports those against the host.
        // A full APPLICATION-DATA filesystem (even a bounded tmpfs at /var/lib/…) is a real disk-full and
        // IS flagged; a real box's ext4/xfs root is still checked.
        // BSD df has no -T, so the type-based suppression above cannot apply on macOS — TargetOs
        // suppresses the equivalent pseudo-mounts by mount point there instead. Without this the
        // probe produced NOTHING on a Mac rather than degrading to a partial answer.
        String probe = TargetOs.isMac(exec)
                ? TargetOs.diskProbe(exec, 90) + " | awk '{print $1\" \"$2\" used\"}'"
                : "df -PT | awk 'NR>1{t=$2; u=$6; gsub(/%/,\"\",u); m=$7; "
                + "if(u+0<90) next; "
                + "if(t==\"overlay\"||t==\"squashfs\"||t==\"devtmpfs\") next; "
                + "if(m~/^\\/(dev|proc|sys|run)($|\\/)/||m==\"/dev/shm\"||m==\"/tmp\") next; "
                + "if(m~/^\\/etc\\/(hosts|hostname|resolv.conf)$/) next; "
                + "print m\" \"u\"% used\"}'";
        Exec.Result r = exec.run(probe, 20);
        if (r.ok() && !r.out().isBlank()) {
            for (String line : r.out().trim().split("\n")) {
                if (!line.isBlank()) f.add(new Finding("disk_full", "critical", "filesystem " + line.trim()));
            }
        }
    }

    private void servicesDown(List<Finding> f) {
        Exec.Result r = exec.run("systemctl list-units --type=service --state=failed --no-legend --no-pager 2>/dev/null | awk '{print $1}'", 20);
        if (r.ok() && !r.out().isBlank()) {
            for (String line : r.out().trim().split("\n")) {
                String unit = line.trim();
                if (!unit.isBlank() && unit.endsWith(".service")) f.add(new Finding("service_down", "critical", unit + " is failed"));
            }
        }
    }

    private void portConflicts(List<Finding> f) {
        // A REAL conflict = one port bound by two DIFFERENT programs. Dual IPv4+IPv6 (or multiple addrs) of
        // the SAME process is normal, not a conflict — so key on (port → distinct process names) and only
        // flag when >1 distinct name shares a port. Needs the process column (ss -p, best-effort under sudo).
        Exec.Result r = exec.run(
            "ss -H -ltnp 2>/dev/null | awk '{ n=split($4,a,\":\"); port=a[n]; "
          + "prog=\"?\"; if (match($0,/users:\\(\\(\"[^\"]+\"/)) { prog=substr($0,RSTART+9,RLENGTH-9); } "
          + "print port\"\\t\"prog }' | sort -u | awk -F'\\t' '{c[$1]++} END{for(p in c) if(c[p]>1) print p}'", 20);
        if (r.ok() && !r.out().isBlank()) {
            for (String p : r.out().trim().split("\n")) {
                if (!p.isBlank()) f.add(new Finding("port_conflict", "warning",
                        "port " + p.trim() + " is bound by more than one program"));
            }
        }
    }

    private void oom(List<Finding> f) {
        Exec.Result r = exec.run("dmesg 2>/dev/null | grep -iE 'killed process|out of memory|oom-killer' | tail -3", 20);
        if (r.ok() && !r.out().isBlank()) {
            f.add(new Finding("oom", "critical", "kernel OOM activity: " + firstLine(r.out())));
        }
    }

    private void certExpiry(List<Finding> f) {
        // Find TLS certs in the common locations (find is reliable; a **-glob needs globstar) and flag any
        // expired or expiring within 7 days.
        Exec.Result r = exec.run(
            "find /etc/nginx /etc/ssl /etc/letsencrypt /etc/pki 2>/dev/null "
          + "\\( -name '*.pem' -o -name '*.crt' \\) -type f | while read c; do "
          + "openssl x509 -in \"$c\" -noout -checkend 604800 >/dev/null 2>&1 || echo \"$c\"; done", 25);
        if (r.ok() && !r.out().isBlank()) {
            for (String c : r.out().trim().split("\n")) {
                if (!c.isBlank() && !c.contains("No such")) {
                    f.add(new Finding("cert_expired", "critical", "certificate expired/expiring: " + c.trim()));
                }
            }
        }
    }

    private void badConfig(List<Finding> f) {
        if (exec.run("command -v nginx >/dev/null 2>&1", 10).ok()) {
            Exec.Result r = exec.run("nginx -t 2>&1", 20);
            if (!r.ok()) f.add(new Finding("bad_config", "critical", "nginx -t failed: " + firstLine(r.out())));
        }
        if (exec.run("command -v sshd >/dev/null 2>&1", 10).ok()) {
            Exec.Result r = exec.run("sshd -t 2>&1", 15);
            // "could not read the config" is not "the config is bad". Run as a non-root user — the
            // normal case on macOS and on any unprivileged operator — sshd -t exits non-zero with
            // "no hostkeys available" or a permission error, and reporting that as a misconfiguration
            // put a fabricated warning in front of the model on every single run.
            if (!r.ok() && !r.out().isBlank() && !cannotInspect(r.out())) {
                f.add(new Finding("bad_config", "warning", "sshd -t: " + firstLine(r.out())));
            }
        }
    }

    /** True when the probe could not READ what it needed, as opposed to finding it invalid. */
    private static boolean cannotInspect(String out) {
        String o = out.toLowerCase(Locale.ROOT);
        return o.contains("no hostkeys available")
                || o.contains("permission denied")
                || o.contains("could not open")
                || o.contains("operation not permitted")
                || o.contains("must be run as root");
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return (nl < 0 ? s : s.substring(0, nl)).trim();
    }
}
