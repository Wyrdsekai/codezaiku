package org.codezaiku.ops;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Whole-machine triage — "explore your environment and see what needs fixing", boundary = THIS box.
 *
 * <p>Where the {@code fix} verb targets one known scope, this enumerates EVERYTHING on the machine reachable
 * through the {@link Exec} seam — docker compose projects, failed systemd units, host disk/memory pressure —
 * and reports what is unhealthy. The caller ({@code FamiliarMain.triage}) then runs the autonomous-SRE
 * operator on each unhealthy compose stack (the operator's proven strength), leaving the machine boundary
 * intact (nothing cross-box) and each per-stack fix bounded by the existing blast-radius + authority ladder.
 *
 * <p>v1 auto-fixes compose stacks; systemd/host issues are surfaced for attention, not auto-remediated
 * (host-level cleanup is higher-risk and stack-specific).
 */
public final class MachineTriage {
    private MachineTriage() { }

    /** One unhealthy thing found on the box. {@code kind} = compose|systemd|host; {@code name} identifies it
     *  (compose project / unit / "host"); {@code symptom} is the observed problem. */
    public record Issue(String kind, String name, String symptom) { }

    /** Enumerate the box and return only the UNHEALTHY items (empty list = the machine looks healthy). */
    public static List<Issue> explore(Exec exec) {
        List<Issue> issues = new ArrayList<>();
        for (String project : composeProjects(exec)) {
            String sym = composeUnhealthy(exec, project);
            if (sym != null) issues.add(new Issue("compose", project, sym));
        }
        for (String unit : failedUnits(exec)) {
            issues.add(new Issue("systemd", unit, "failed unit"));
        }
        String host = hostPressure(exec);
        if (host != null) issues.add(new Issue("host", "host", host));
        return issues;
    }

    /** How many compose projects were seen (for the "scanned N stacks" line), regardless of health. */
    public static int composeProjectCount(Exec exec) {
        return composeProjects(exec).size();
    }

    // ---- docker compose ----
    static List<String> composeProjects(Exec exec) {
        Exec.Result r = exec.run(
                "docker ps -a --format '{{.Label \"com.docker.compose.project\"}}' 2>/dev/null | sort -u", 20);
        if (!r.ok() || r.out().isBlank()) return List.of();
        return Arrays.stream(r.out().strip().split("\n"))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    /** A short symptom string if any container in the project is unhealthy/exited/restarting/dead; else null. */
    static String composeUnhealthy(Exec exec, String project) {
        Exec.Result r = exec.run("docker ps -a --filter label=com.docker.compose.project=" + project
                + " --format '{{.Names}} {{.Status}}'", 20);
        if (!r.ok()) return null;
        List<String> bad = new ArrayList<>();
        for (String line : r.out().split("\n")) {
            if (line.isBlank()) continue;
            String s = line.toLowerCase();
            if (s.contains("unhealthy") || s.contains("exited") || s.contains("restarting") || s.contains("dead"))
                bad.add(line.trim());
        }
        return bad.isEmpty() ? null : String.join("; ", bad);
    }

    // ---- systemd ----
    static List<String> failedUnits(Exec exec) {
        Exec.Result r = exec.run("systemctl --failed --no-legend --plain 2>/dev/null | awk '{print $1}'", 15);
        if (!r.ok() || r.out().isBlank()) return List.of();
        return Arrays.stream(r.out().strip().split("\n"))
                .map(String::trim).filter(s -> !s.isEmpty() && s.endsWith(".service")).distinct().toList();
    }

    // ---- host ----
    /** Disk >=90% on any real fs, or MemAvailable <5% of MemTotal → a symptom string; else null. */
    static String hostPressure(Exec exec) {
        List<String> hits = new ArrayList<>();
        // BSD df has neither -x nor -T, so this reported nothing at all on macOS.
        Exec.Result df = exec.run(TargetOs.diskProbe(exec, 90), 15);
        if (df.ok() && !df.out().isBlank())
            for (String l : df.out().strip().split("\n")) if (!l.isBlank()) hits.add("disk " + l.trim());
        Exec.Result mem = exec.run(
                "awk '/MemTotal/{t=$2}/MemAvailable/{a=$2}END{if(t>0 && a*100/t<5) print int(a*100/t)}' /proc/meminfo 2>/dev/null", 10);
        if (mem.ok() && !mem.out().isBlank()) hits.add("memory available " + mem.out().strip() + "%");
        return hits.isEmpty() ? null : String.join("; ", hits);
    }
}
