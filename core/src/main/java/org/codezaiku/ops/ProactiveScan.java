package org.codezaiku.ops;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.codezaiku.Config;

/**
 * P3 PROACTIVE MAINTENANCE — act BEFORE the outage. Deterministic sensors only (no model): each scan
 * reads cheap host/container facts, and trends are projected from the PREVIOUS scan's sample, so a
 * resource that will exhaust inside the horizon is named hours ahead of the incident it would become.
 *
 * <p>Design rules (same spine as the reactive operator):
 * <ul>
 *   <li>The scan COMPUTES, the authority rung decides what may be DONE about it: predictions surface at
 *       PROPOSE via the normal audit/alert path — a trend is a forecast, not a verified fault, and an
 *       unproven fix never auto-applies.</li>
 *   <li>Sensors are harness-computed signals (df, MemAvailable, docker stats, cert dates) — the model is
 *       never asked to guess a trend.</li>
 *   <li>Stateless callers get NOW-threshold detection; a long-lived {@code watch} loop that keeps one
 *       ProactiveScan instance also gets trend PROJECTION for free (samples held in-memory).</li>
 * </ul>
 */
public final class ProactiveScan {

    /** One prediction: {@code severity} = "now" (threshold already crossed) or "predicted" (trend crosses
     *  inside the horizon); {@code hoursToImpact} < 0 means already there. */
    public record Prediction(String kind, String subject, String severity, double hoursToImpact, String detail) {
        public String line() {
            return "[" + severity + "] " + kind + " " + subject
                    + (hoursToImpact >= 0 ? String.format(Locale.ROOT, " — ~%.1fh to impact", hoursToImpact) : "")
                    + " — " + detail;
        }
    }

    private final Exec exec;
    // previous samples for trend projection: key -> {value, epochMillis}
    private final Map<String, double[]> lastSample = new HashMap<>();

    // Tunables (env-overridable; defaults are conservative).
    private final int diskWarnPct = envInt("CODEZAIKU_OPS_DISK_WARN_PCT", 85);
    private final double horizonHours = envInt("CODEZAIKU_OPS_HORIZON_HOURS", 48);
    private final int memWarnPct = envInt("CODEZAIKU_OPS_MEM_WARN_PCT", 90);
    private final int certWarnDays = envInt("CODEZAIKU_OPS_CERT_WARN_DAYS", 14);

    private static int envInt(String k, int dflt) {
        try { return Integer.parseInt(System.getenv().getOrDefault(k, String.valueOf(dflt))); }
        catch (Exception e) { return dflt; }
    }

    public ProactiveScan(Exec exec) { this.exec = exec; }

    /** Run every sensor; cheap enough for every watch tick. */
    public List<Prediction> scan() {
        List<Prediction> out = new ArrayList<>();
        disks(out);
        hostMemory(out);
        containerMemory(out);
        certs(out);
        return out;
    }

    // ---- disk ------------------------------------------------------------------

    private void disks(List<Prediction> out) {
        // real filesystems only; -P = POSIX single-line rows
        Exec.Result r = exec.run("df -P -x tmpfs -x devtmpfs -x overlay -x squashfs 2>/dev/null | tail -n +2", 15);
        if (!r.ok()) return;
        for (String line : r.out().trim().split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 6) continue;
            String mount = f[5];
            double usedPct, usedKb, sizeKb;
            try {
                usedPct = Double.parseDouble(f[4].replace("%", ""));
                usedKb = Double.parseDouble(f[2]);
                sizeKb = Double.parseDouble(f[1]);
            } catch (NumberFormatException e) { continue; }
            if (sizeKb <= 0) continue;
            if (usedPct >= diskWarnPct) {
                out.add(new Prediction("disk-full", mount, "now", -1,
                        String.format(Locale.ROOT, "%.0f%% used (warn at %d%%) — free space before writes start failing", usedPct, diskWarnPct)));
                continue;
            }
            // trend: project when usage crosses the warn threshold
            Double hours = project("disk:" + mount, usedKb, sizeKb * diskWarnPct / 100.0);
            if (hours != null && hours <= horizonHours) {
                out.add(new Prediction("disk-full", mount, "predicted", hours,
                        String.format(Locale.ROOT, "%.0f%% used and growing — projected to cross %d%% within the horizon", usedPct, diskWarnPct)));
            }
        }
    }

    // ---- memory ----------------------------------------------------------------

    private void hostMemory(List<Prediction> out) {
        // /proc does not exist on macOS; TargetOs picks the probe for the TARGET, which may be a
        // remote Linux host operated from a Mac or the Mac itself.
        Exec.Result r = exec.run(TargetOs.memoryProbe(exec), 10);
        if (!r.ok()) return;
        String[] v = r.out().trim().split("\n");
        if (v.length < 2) return;
        double total, avail;
        try { total = Double.parseDouble(v[0].trim()); avail = Double.parseDouble(v[1].trim()); }
        catch (NumberFormatException e) { return; }
        if (total <= 0) return;
        double usedPct = 100.0 * (total - avail) / total;
        if (usedPct >= memWarnPct) {
            out.add(new Prediction("host-memory", "host", "now", -1,
                    String.format(Locale.ROOT, "%.0f%% of RAM used (warn at %d%%) — OOM-killer risk", usedPct, memWarnPct)));
            return;
        }
        Double hours = project("mem:host", total - avail, total * memWarnPct / 100.0);
        if (hours != null && hours <= horizonHours) {
            out.add(new Prediction("host-memory", "host", "predicted", hours,
                    String.format(Locale.ROOT, "%.0f%% used and growing — projected to cross %d%%", usedPct, memWarnPct)));
        }
    }

    private void containerMemory(List<Prediction> out) {
        // containers with an explicit memory LIMIT near it (limitless containers show the host total — skip
        // those; the host sensor covers them)
        Exec.Result r = exec.run("docker stats --no-stream --format '{{.Name}} {{.MemPerc}} {{.MemUsage}}' 2>/dev/null", 25);
        if (!r.ok()) return;
        for (String line : r.out().trim().split("\n")) {
            String[] f = line.trim().split("\\s+", 3);
            if (f.length < 3 || !f[2].contains("/")) continue;
            double pct;
            try { pct = Double.parseDouble(f[1].replace("%", "")); } catch (NumberFormatException e) { continue; }
            boolean limited = !f[2].toLowerCase(Locale.ROOT).split("/")[1].trim().startsWith("0");
            if (limited && pct >= memWarnPct) {
                out.add(new Prediction("container-memory", f[0], "now", -1,
                        String.format(Locale.ROOT, "%.0f%% of its memory LIMIT (%s) — the next spike is an OOM-kill", pct, f[2].trim())));
            }
        }
    }

    // ---- TLS expiry ------------------------------------------------------------

    private void certs(List<Prediction> out) {
        // Explicitly-listed endpoints only (CODEZAIKU_OPS_TLS_ENDPOINTS=host:port,host:port) — proactive
        // scans must never surprise-probe things the operator wasn't pointed at.
        String eps = Config.get("CODEZAIKU_OPS_TLS_ENDPOINTS");
        if (eps == null || eps.isBlank()) return;
        for (String ep : eps.split(",")) {
            ep = ep.trim();
            if (ep.isEmpty()) continue;
            Exec.Result r = exec.run("echo | openssl s_client -connect " + ep + " -servername " + ep.split(":")[0]
                    + " 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null", 20);
            if (!r.ok() || !r.out().contains("notAfter=")) continue;
            String end = r.out().trim().replace("notAfter=", "");
            Exec.Result d = exec.run("echo $(( ($(date -d '" + end + "' +%s) - $(date +%s)) / 86400 ))", 10);
            if (!d.ok()) continue;
            try {
                int days = Integer.parseInt(d.out().trim());
                if (days < certWarnDays) {
                    out.add(new Prediction("tls-cert-expiry", ep, days <= 0 ? "now" : "predicted", days * 24.0,
                            days <= 0 ? "certificate EXPIRED (" + end + ")"
                                      : "certificate expires in " + days + " days (" + end + ") — renew before clients start failing"));
                }
            } catch (NumberFormatException ignored) { }
        }
    }

    // ---- trend projection ------------------------------------------------------

    /** Hours until {@code value} (growing linearly at the rate observed since the last sample of
     *  {@code key}) reaches {@code threshold}; null = first sample, shrinking, or flat. */
    private Double project(String key, double value, double threshold) {
        double now = System.currentTimeMillis();
        double[] prev = lastSample.put(key, new double[]{value, now});
        if (prev == null) return null;
        double dv = value - prev[0], dtHours = (now - prev[1]) / 3_600_000.0;
        if (dv <= 0 || dtHours <= 0) return null;
        double rate = dv / dtHours;                    // units per hour
        double remaining = threshold - value;
        if (remaining <= 0) return 0.0;
        return remaining / rate;
    }
}
