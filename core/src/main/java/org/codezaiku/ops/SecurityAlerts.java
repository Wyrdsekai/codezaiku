package org.codezaiku.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.codezaiku.Config;

/**
 * RUNTIME INTRUSION SENSOR — the runtime sibling of {@link SecurityScan} (which reads static posture).
 * Reads a Falco JSON alert stream: machine-computed, rule-named detections of real syscall-level
 * attacker behaviour (reverse shells, execution from /dev/shm, credential file reads, fileless
 * execution, container escape attempts).
 *
 * <p>WHY FALCO IS THE ORACLE AND THE MODEL IS NOT: a detection must be a fact, not a judgment. Falco's
 * rule engine fires on syscalls the kernel actually saw, so a finding here cannot be hallucinated and —
 * unlike a log line — cannot be authored by the attacker.
 *
 * <p>BUT THE ALERT TEXT STILL CAN BE. An alert's {@code output} embeds attacker-controlled fields:
 * file paths, process names, command lines. An attacker who knows a detector is watching can name a
 * file so that instruction-shaped text rides into whatever reads the alert — the same indirect-injection
 * channel measured on container logs (100% steer before hardening). So the RULE NAME and priority (from
 * Falco, trustworthy) are kept structured, and the free-text fields are neutralized before they can
 * reach a model.
 *
 * <p>REPORT-ONLY. Containment (kill, isolate, firewall) is destructive and a false positive is a
 * self-inflicted outage, so findings surface at PROPOSE for a human. Detection is also not proof: Falco
 * default rules include noisy ones, which is why {@code minPriority} defaults to a high bar.
 */
public final class SecurityAlerts {

    /** One deduplicated detection: {@code count} occurrences of {@code rule} on {@code subject}. */
    public record Detection(String rule, String priority, String subject, int count, String detail) {
        public String line() {
            return "[" + priority.toLowerCase(Locale.ROOT) + "] " + rule
                    + (subject.isBlank() ? "" : " on " + subject)
                    + (count > 1 ? " (x" + count + ")" : "") + (detail.isBlank() ? "" : " — " + detail);
        }
    }

    /** Falco priorities, most severe first. Anything below the configured floor is dropped as noise. */
    private static final List<String> PRIORITY = List.of(
            "EMERGENCY", "ALERT", "CRITICAL", "ERROR", "WARNING", "NOTICE", "INFORMATIONAL", "DEBUG");

    /**
     * Which detector is feeding us. Both produce rule-NAMED detections from a ruleset someone else
     * maintains and validates — that is the property that makes a finding here a fact rather than a
     * judgement of ours. They do NOT have equal reach, and {@link #coverage()} says so.
     */
    public enum Sensor {
        /** Syscall-level, via eBPF or a kernel module. Linux only. */
        FALCO,
        /** Log/ULS analysis, file integrity, and polled command output. Cross-platform, shallower. */
        WAZUH,
        NONE
    }

    private final Exec exec;
    private final String falcoFile;
    private final String wazuhFile;
    private final String minPriority;
    private final ObjectMapper j = new ObjectMapper();

    public SecurityAlerts(Exec exec) {
        this(exec, Config.get("CODEZAIKU_OPS_FALCO_ALERTS", "/var/log/falco/alerts.json"),
             Config.get("CODEZAIKU_OPS_FALCO_MIN_PRIORITY", "WARNING"),
             Config.get("CODEZAIKU_OPS_WAZUH_ALERTS", "/var/ossec/logs/alerts/alerts.json"));
    }

    public SecurityAlerts(Exec exec, String alertFile, String minPriority) {
        this(exec, alertFile, minPriority, Config.get("CODEZAIKU_OPS_WAZUH_ALERTS",
                "/var/ossec/logs/alerts/alerts.json"));
    }

    public SecurityAlerts(Exec exec, String falcoFile, String minPriority, String wazuhFile) {
        this.exec = exec;
        this.falcoFile = falcoFile;
        this.wazuhFile = wazuhFile;
        this.minPriority = minPriority.toUpperCase(Locale.ROOT);
    }

    /** Falco is preferred where both exist: it sees syscalls, which log analysis cannot reconstruct. */
    public Sensor sensor() {
        if (exec.run("test -s " + falcoFile, 10).ok()) return Sensor.FALCO;
        if (exec.run("test -s " + wazuhFile, 10).ok()) return Sensor.WAZUH;
        return Sensor.NONE;
    }

    /**
     * What the active sensor can and cannot see, for a report that must not overstate itself.
     *
     * <p>Wazuh is NOT a Falco equivalent and saying so would be the overclaim this codebase keeps
     * having to retract: on macOS its process monitoring runs commands periodically and reads the
     * unified log, so it observes what was LOGGED, not what the kernel saw. A process that executed
     * and exited between polls leaves no trace. It is still a real detector with a maintained
     * ruleset — just a shallower one.
     */
    public String coverage() {
        return switch (sensor()) {
            case FALCO -> "falco — syscall-level detection";
            case WAZUH -> "wazuh — log/ULS analysis, file integrity and polled command output; "
                    + "NOT syscall-level, so activity that was never logged is not seen";
            case NONE -> "none";
        };
    }

    private boolean severeEnough(String p) {
        int want = PRIORITY.indexOf(minPriority), got = PRIORITY.indexOf(p.toUpperCase(Locale.ROOT));
        return got >= 0 && want >= 0 && got <= want;
    }

    /** Detections from the last {@code sinceMinutes} of the alert stream, deduplicated by rule+subject. */
    public List<Detection> recent(int sinceMinutes) {
        List<Detection> out = new ArrayList<>();
        Sensor sensor = sensor();
        if (sensor == Sensor.NONE) return out;
        String file = sensor == Sensor.FALCO ? falcoFile : wazuhFile;
        // tail-bounded: an alert file can be huge and we only ever want the recent window
        Exec.Result r = exec.run("tail -n 2000 " + file + " 2>/dev/null", 20);
        if (!r.ok() || r.out().isBlank()) return out;

        long cutoff = System.currentTimeMillis() - sinceMinutes * 60_000L;
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String[]> meta = new LinkedHashMap<>();
        for (String line : r.out().split("\n")) {
            if (line.isBlank()) continue;
            try {
                JsonNode a = j.readTree(line);
                String rule, prio, subject, proc, cmd;
                if (sensor == Sensor.WAZUH) {
                    // Wazuh: the trustworthy parts are rule.description and rule.level, which come
                    // from the ruleset. Everything else on the record — full_log, data.* — is the
                    // attacker's text verbatim and is neutralized below, exactly as Falco's is.
                    JsonNode rl = a.path("rule");
                    rule = rl.path("description").asText("");
                    prio = wazuhPriority(rl.path("level").asInt(0));
                    if (rule.isEmpty() || !severeEnough(prio)) continue;
                    if (a.hasNonNull("timestamp")
                            && !withinWindow(a.path("timestamp").asText(""), cutoff)) continue;
                    subject = a.path("agent").path("name").asText("");
                    if (subject.isBlank()) subject = "host";
                    proc = StackLocalizer.neutralizeLogText(a.path("decoder").path("name").asText(""));
                    cmd = StackLocalizer.neutralizeLogText(a.path("full_log").asText(""));
                } else {
                    rule = a.path("rule").asText("");
                    prio = a.path("priority").asText("");
                    if (rule.isEmpty() || !severeEnough(prio)) continue;
                    if (a.hasNonNull("time") && !withinWindow(a.path("time").asText(""), cutoff)) continue;
                    JsonNode f = a.path("output_fields");
                    String container = f.path("container.name").asText("");
                    if (container.isEmpty() || "host".equals(container)) container = "host";
                    subject = container;
                    // Free-text, attacker-influenced: neutralize instruction-shaped content before it can
                    // ever be rendered into a model prompt (same channel as the log-injection finding).
                    proc = StackLocalizer.neutralizeLogText(f.path("proc.name").asText(""));
                    cmd = StackLocalizer.neutralizeLogText(f.path("proc.cmdline").asText(""));
                }
                String key = rule + "|" + subject;
                counts.computeIfAbsent(key, k -> new int[1])[0]++;
                meta.putIfAbsent(key, new String[]{prio, subject, trim(proc), trim(cmd)});
            } catch (Exception ignored) { }
        }
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            String[] m = meta.get(e.getKey());
            String rule = e.getKey().substring(0, e.getKey().lastIndexOf('|'));
            String detail = m[2].isBlank() ? "" : "proc " + m[2] + (m[3].isBlank() ? "" : ": " + m[3]);
            out.add(new Detection(rule, m[0], m[1], e.getValue()[0], detail));
        }
        out.sort((x, y) -> {
            int p = PRIORITY.indexOf(x.priority().toUpperCase(Locale.ROOT))
                  - PRIORITY.indexOf(y.priority().toUpperCase(Locale.ROOT));
            return p != 0 ? p : y.count() - x.count();
        });
        return out;
    }

    private static boolean withinWindow(String iso, long cutoffMillis) {
        try {
            return Instant.parse(normalizeOffset(iso)).toEpochMilli() >= cutoffMillis;
        } catch (Exception e) {
            return true;   // unparseable timestamp: keep it rather than silently drop a detection
        }
    }

    /**
     * Accept a numeric UTC offset written WITHOUT a colon.
     *
     * <p>Falco emits {@code ...Z}; Wazuh emits {@code 2026-08-16T12:03:52.853+0000}, which
     * {@code Instant.parse} rejects. The failure was invisible: the catch above keeps an alert whose
     * timestamp will not parse, so nothing was ever dropped — the time window simply stopped applying
     * to Wazuh, and `recent(60)` quietly returned alerts of any age. Fixtures could not show this
     * because a hand-written one uses Instant.toString(). A live manager did.
     */
    static String normalizeOffset(String ts) {
        if (ts == null) return "";
        return ts.matches(".*[+-]\\d{4}$")
                ? ts.substring(0, ts.length() - 2) + ":" + ts.substring(ts.length() - 2)
                : ts;
    }

    private static String trim(String s) {
        s = s.replaceAll("\\s+", " ").strip();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    /**
     * Wazuh grades 0-15; we speak Falco's names so one severity floor governs both sensors.
     *
     * <p>The boundaries follow Wazuh's own guidance — 13+ severe, 10-12 high, 7-9 attack patterns,
     * 4-6 minor — so the default WARNING floor admits level 7 and above. A level-5 "login attempt by
     * a non-existent user" stays below it, which matches the intent of Falco's floor: keep the
     * chatter of a busy host out of a report someone is meant to act on.
     */
    static String wazuhPriority(int level) {
        if (level >= 13) return "CRITICAL";
        if (level >= 10) return "ERROR";
        if (level >= 7) return "WARNING";
        if (level >= 4) return "NOTICE";
        return "INFORMATIONAL";
    }

    /** True when SOME detector is present — otherwise the absence of detections means only
     *  that nothing is watching, which must never be reported as "no intrusion". */
    public boolean available() {
        return sensor() != Sensor.NONE;
    }
}
