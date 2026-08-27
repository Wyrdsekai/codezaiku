package org.codezaiku.ops;

import java.util.ArrayList;
import java.util.List;

/**
 * The RECON phase — a jailed step that runs BEFORE the main loop and learns the stack.
 *
 * <p>This is CodeZaiku's §5.4 project-shape snapshot, generalized from structure (symbols, manifest) to the
 * DOMAIN knowledge that is the measured lever: what this system IS, how it is wired, and where its evidence
 * lives. On OpenRCA, handing the model correctly-localized evidence with no agent scored 2.2× our full loop —
 * information is the lever, orchestration is not. Recon acquires that information at runtime instead of the
 * dev hand-authoring it, which is what makes it sound: it reads the stack through a {@link ReconJail} and
 * never the grader's answer, so a card it produces cannot leak a task's answer by construction.
 *
 * <p>Like {@link Precheck} it is DETERMINISTIC harness code — a fixed recipe of read-only probes, not a
 * model-driven sub-agent — so there is no injection surface and the jail is a hard guarantee. It ADDS
 * INFORMATION; it is not a loop gate (those were measured worthless and deleted). The output is a
 * "SYSTEM MAP" card injected into the loop's first prompt, plus a manifest of exactly what was read, so the
 * step is observable and a leak would be visible rather than silent.
 *
 * <p>v1 assembles the map deterministically. A later iteration may distil on-box docs/runbooks into failure-
 * mode knowledge (still through the same jail); the card builder is structured so that slots in without
 * changing the contract.
 */
public final class ReconPhase {

    private final Exec jailed;
    private final List<String> readManifest = new ArrayList<>();

    public ReconPhase(Exec box, ReconJail jail) {
        this.jailed = jail.wrap(box);
    }

    public record Card(String map, List<String> readManifest) {
        public boolean isEmpty() { return map.isBlank(); }
    }

    /** Learn the stack and return the SYSTEM MAP card. Never throws — a probe that fails is simply omitted. */
    public Card run() {
        StringBuilder m = new StringBuilder();
        osRelease(m);
        services(m);
        listeningPorts(m);
        configLocations(m);
        logLocations(m);
        docsAndRunbooks(m);
        if (m.length() == 0) return new Card("", readManifest);

        String card = "## SYSTEM MAP (harness recon — what this box IS, read-only; not a diagnosis)\n"
                + "Learned from the box before investigating, so you start oriented instead of discovering the\n"
                + "topology by hand. These are stable facts about the system, not claims about what is wrong.\n\n"
                + m;
        return new Card(card, readManifest);
    }

    // ---- probes: each is read-only and records what it consulted -------------------------------------

    private String probe(String label, String cmd, int timeoutSec) {
        Exec.Result r = jailed.run(cmd, timeoutSec);
        // Judge a probe by its OUTPUT, not its exit code. Read-only pipelines routinely exit non-zero with
        // perfectly good output — grep with no match exits 1, and `for p in …; do [ -f $p ] && echo $p; done`
        // exits on the LAST test even after echoing real hits. Gating on exit==0 silently threw away the
        // config-file list (the manifest showed "(no data)" for a probe that had found /etc/nginx/nginx.conf).
        String out = (r.out() == null) ? "" : r.out().trim();
        // A jail refusal is NOT data — it must never land in the card as if the box reported it. Treat a
        // refused probe as empty and flag it distinctly so a mis-tightened jail is visible in the manifest.
        boolean refused = out.startsWith("[recon-jail]");
        if (refused) out = "";
        readManifest.add(label + " :: " + cmd + (refused ? "  (REFUSED by jail)" : out.isEmpty() ? "  (no data)" : ""));
        return out;
    }

    private void osRelease(StringBuilder m) {
        String os = probe("os", "cat /etc/os-release 2>/dev/null | grep -E '^(PRETTY_NAME|VERSION)=' | head -2", 15);
        String kern = probe("kernel", "uname -sr", 10);
        if (!os.isBlank() || !kern.isBlank()) {
            m.append("### Platform\n");
            for (String l : os.split("\n")) if (!l.isBlank()) m.append("  ").append(l.replace("\"", "")).append('\n');
            if (!kern.isBlank()) m.append("  kernel: ").append(kern).append('\n');
            m.append('\n');
        }
    }

    private void services(StringBuilder m) {
        // systemd units first; fall back to docker/compose if there is no systemd (a container box).
        String units = probe("services.systemd",
                "systemctl list-units --type=service --state=running --no-legend --no-pager 2>/dev/null "
              + "| awk '{print $1}' | sed 's/\\.service$//' | head -30", 20);
        String dock = probe("services.docker",
                "docker ps --format '{{.Names}}\\t{{.Image}}\\t{{.Status}}' 2>/dev/null | head -30", 20);
        if (units.isBlank() && dock.isBlank()) return;
        m.append("### Services running\n");
        if (!units.isBlank()) for (String s : units.split("\n")) if (!s.isBlank()) m.append("  - ").append(s).append('\n');
        if (!dock.isBlank()) for (String s : dock.split("\n")) if (!s.isBlank()) m.append("  - [container] ").append(s.replace("\t", "  ")).append('\n');
        m.append('\n');
    }

    private void listeningPorts(StringBuilder m) {
        String ports = probe("ports",
                "ss -ltnp 2>/dev/null | awk 'NR>1{print $4, $6}' | sed 's/users:.*(\"//; s/\".*//' "
              + "| sort -u | head -25", 20);
        if (ports.isBlank()) return;
        m.append("### Listening ports (addr → process)\n");
        for (String l : ports.split("\n")) if (!l.isBlank()) m.append("  ").append(l).append('\n');
        m.append('\n');
    }

    private void configLocations(StringBuilder m) {
        // Where the common services keep their config — so the model reads the RIGHT file instead of guessing.
        String cfg = probe("configs",
                "for p in /etc/nginx/nginx.conf /etc/apache2/apache2.conf /etc/httpd/conf/httpd.conf "
              + "/etc/postgresql/*/main/postgresql.conf /etc/redis/redis.conf /etc/mysql/my.cnf "
              + "/etc/haproxy/haproxy.cfg /etc/ssh/sshd_config; do [ -f $p ] && echo $p; done 2>/dev/null", 15);
        if (cfg.isBlank()) return;
        m.append("### Config files present\n");
        for (String l : cfg.split("\n")) if (!l.isBlank()) m.append("  ").append(l).append('\n');
        m.append('\n');
    }

    private void logLocations(StringBuilder m) {
        String logs = probe("logs",
                "ls -1d /var/log/nginx /var/log/apache2 /var/log/postgresql /var/log/redis "
              + "/var/log/syslog /var/log/messages 2>/dev/null | head -12", 15);
        if (logs.isBlank()) return;
        m.append("### Log locations\n");
        for (String l : logs.split("\n")) if (!l.isBlank()) m.append("  ").append(l).append('\n');
        m.append("  (also: journalctl -u <service> for systemd units)\n\n");
    }

    private void docsAndRunbooks(StringBuilder m) {
        // Genuine domain knowledge on the box: a README or runbook the operators left. This is the closest
        // thing to "how this stack fails", and reading it is exactly the lever — provided it is not the
        // grader's answer file, which the jail denies.
        String docs = probe("docs",
                "find / -maxdepth 4 -type f \\( -iname 'README*' -o -iname 'RUNBOOK*' -o -iname '*.runbook' "
              + "-o -ipath '*/runbooks/*' \\) 2>/dev/null | grep -vE '/(node_modules|\\.git|site-packages)/' "
              + "| head -8", 30);
        if (docs.isBlank()) return;
        m.append("### On-box docs / runbooks (read these when the incident matches)\n");
        for (String l : docs.split("\n")) if (!l.isBlank()) m.append("  ").append(l).append('\n');
        m.append('\n');
    }
}
