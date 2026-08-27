package org.codezaiku.ops;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic TELEMETRY anomaly localization — the telemetry analog of {@link Precheck}.
 *
 * <p><b>MEASURED VERDICT: this barely beats chance. Do not rely on it, and do not enable it believing it
 * works.</b> Harness-side recall of the TRUE component in the shortlist (no model involved,
 * {@code bench/ops-eval/external/openrca/loc_eval.py}, Telecom n=43, 55 candidate components):
 * <pre>
 *   detector      recall@25   shortlist   random at that length   LIFT
 *   mad-extreme     67.4%       25.0            45.5%             1.48x
 *   shift           32.6%       10.0            18.3%             1.78x
 * </pre>
 * The 67.4% is an illusion — it lists 25 of 55 components, so chance alone gives 45.5%. Raw recall is
 * meaningless without the list length; only lift-over-random means anything, and 1.5-1.8x will not deliver
 * the oracle's 2.2x.
 *
 * <p><b>Why it fails, and what that taught us.</b> The premise was right: evidence localization IS the lever.
 * On OpenRCA/Telecom, oracle-localized evidence with <i>no agent at all</i> scored mean 0.180 vs our full
 * agent loop's 0.082 on the same model (2.2x), while the 2x2 showed our conclusion gates buy nothing over
 * their lean reference agent. But the ORACLE IS NOT AN ANOMALY DETECTOR — read
 * {@code rca/baseline/oracle_kpis.py}: it is a DOMAIN MAP, fault-class to the KPIs that evidence it
 * ("cpu" to container_cpu_used, "mem" to Memory_used_pct). Its lift comes from KNOWING which eleven signals
 * of hundreds matter. This class tried to derive that semantics statistically, and statistics does not carry
 * semantics. The lever is a KNOWLEDGE artifact — the library — not a detector.
 *
 * <p>Kept, disabled by default, because the measurement is the point: it is the evidence that the naive
 * statistical route is a dead end, and the next attempt should not rediscover it.
 *
 * <p><b>Why it REDUCES rather than selects.</b> The oracle's own evidence for ONE incident is 111,428
 * tokens — 3.4× a 32k window. At the local tier the model physically cannot read the right data even when
 * told exactly which KPIs it is. So this never emits raw series: it emits a ranked shortlist of
 * (component, kpi, when, deviation).
 *
 * <p><b>What it does NOT do.</b> It does not conclude, and it does not name a root cause — same contract as
 * {@link Precheck}: pure narrowing. A high deviation is a candidate, not an answer; the model still has to
 * reason about which candidate actually explains the symptom. The oracle KPIs are hand-curated by the
 * benchmark authors and are a CHEAT (an upper bound); this derives candidates from the data instead, so it
 * should land somewhere between the un-helped agent and that bound.
 *
 * <p>Enabled with {@code CODEZAIKU_OPS_TELEMETRY=<root>} (the directory holding {@code <YYYY_MM_DD>/metric/}).
 * If the window cannot be parsed or the scan fails, it returns nothing and the loop runs exactly as before.
 */
public final class TelemetryPrecheck {

    private final Exec exec;
    private final String telemetryRoot;
    private final int topN;

    public TelemetryPrecheck(Exec exec, String telemetryRoot, int topN) {
        this.exec = exec;
        this.telemetryRoot = telemetryRoot;
        this.topN = topN;
    }

    /** An incident window in epoch seconds, plus the calendar day used to pick the telemetry directory. */
    public record Window(String day, long startEpoch, long endEpoch) { }

    private static final String[] MONTHS = {"january", "february", "march", "april", "may", "june", "july",
            "august", "september", "october", "november", "december"};

    /**
     * Pull the incident window out of the natural-language task, e.g.
     * "April 11, 2020, from 00:00 to 00:30". Returns null if the phrasing does not match — the caller then
     * degrades to no telemetry precheck rather than scanning a guessed window, because a confidently WRONG
     * window is worse than none: it would point the model at the wrong minutes with the harness's authority.
     */
    public static Window parseWindow(String instruction) {
        if (instruction == null) return null;
        String s = instruction.toLowerCase(Locale.ROOT);
        Matcher m = Pattern.compile(
                "([a-z]+)\\s+(\\d{1,2}),\\s*(\\d{4}).{0,40}?from\\s+(\\d{1,2}):(\\d{2})\\s*(?:to|-|until)\\s*(\\d{1,2}):(\\d{2})",
                Pattern.DOTALL).matcher(s);
        if (!m.find()) return null;
        int mon = -1;
        for (int i = 0; i < MONTHS.length; i++) if (MONTHS[i].startsWith(m.group(1))) { mon = i + 1; break; }
        if (mon < 0) return null;
        try {
            int day = Integer.parseInt(m.group(2)), year = Integer.parseInt(m.group(3));
            int h1 = Integer.parseInt(m.group(4)), m1 = Integer.parseInt(m.group(5));
            int h2 = Integer.parseInt(m.group(6)), m2 = Integer.parseInt(m.group(7));
            // OpenRCA timestamps are UTC+8, so build the epoch in that zone rather than the box's locale.
            ZoneOffset z = ZoneOffset.ofHours(8);
            LocalDate d = LocalDate.of(year, mon, day);
            long start = d.atTime(h1, m1).toEpochSecond(z);
            long end = d.atTime(h2, m2).toEpochSecond(z);
            if (end <= start) end = start + 1800;
            return new Window(String.format("%04d_%02d_%02d", year, mon, day), start, end);
        } catch (Exception e) {
            return null;
        }
    }

    /** Ranked candidates, most deviant first. Empty when the scan cannot run — never throws. */
    public List<Precheck.Finding> run(Window w) {
        if (w == null || telemetryRoot == null || telemetryRoot.isBlank()) return List.of();
        String py = script(w);
        exec.write("/tmp/cp_telemetry_scan.py", py);
        Exec.Result r = exec.run("python3 /tmp/cp_telemetry_scan.py 2>&1", 600);
        List<Precheck.Finding> out = new ArrayList<>();
        if (r.out() == null) return out;
        for (String line : r.out().split("\n")) {
            if (!line.startsWith("CAND|")) continue;
            String[] p = line.split("\\|", 5);
            if (p.length < 5) continue;
            // name = "component kpi", evidence carries the deviation + when it peaked
            out.add(new Precheck.Finding(p[1] + " / " + p[2], "anomaly",
                    "peak deviation " + p[3] + "σ from its own baseline at " + p[4]));
        }
        return out;
    }

    /** Render the shortlist for the kickoff prompt. Empty string when there is nothing to say. */
    public String block(Window w) {
        List<Precheck.Finding> f = run(w);
        if (f.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## TELEMETRY SCAN (computed by the harness, deterministic — not a diagnosis)\n");
        sb.append("Each metric series was scored against ITS OWN baseline earlier that day (robust z, median/MAD).\n");
        sb.append("These are the strongest deviations inside the incident window, ranked. They are CANDIDATES:\n");
        sb.append("a large deviation can be a downstream effect rather than the cause, so verify against the\n");
        sb.append("reported symptom before concluding.\n\n");
        for (Precheck.Finding x : f) sb.append("  ").append(x).append("\n");
        return sb.toString();
    }

    /**
     * The scan. Long-format metric CSVs ({@code itemid,name,bomc_id,timestamp,value,cmdb_id}) are the shape
     * across OpenRCA's systems, but column names vary, so the component/kpi/time/value columns are DETECTED
     * (mirroring the candidate-name lists in their own extractors) rather than hardcoded.
     *
     * <p>Robust z (median/MAD), not mean/stddev: an outlier is exactly what we are hunting, and it would
     * inflate a stddev enough to hide itself. MAD is scaled by 1.4826 so σ is comparable to a normal one.
     * Series whose baseline never moves (MAD 0) are skipped unless the window leaves that constant — a flat
     * line that stays flat is the single most common series here and would otherwise dominate by dividing
     * by ~0.
     */
    private String script(Window w) {
        return """
            import os, sys, glob
            import pandas as pd, numpy as np
            ROOT = %s
            DAY  = %s
            T0, T1, TOPN = %d, %d, %d

            day_dir = os.path.join(ROOT, DAY)
            if not os.path.isdir(day_dir):
                cand = [d for d in glob.glob(os.path.join(ROOT, "*")) if os.path.basename(d) == DAY]
                if not cand: sys.exit(0)
                day_dir = cand[0]

            COMP = ["cmdb_id", "serviceName", "service", "tc", "host", "instance"]
            KPI  = ["name", "kpi_name", "metric", "kpi"]
            TIME = ["timestamp", "startTime", "time", "ts"]
            VAL  = ["value", "val"]
            def pick(cols, opts):
                for o in opts:
                    if o in cols: return o
                return None

            rows = []
            for f in sorted(glob.glob(os.path.join(day_dir, "metric", "*.csv"))):
                try:
                    head = pd.read_csv(f, nrows=1)
                except Exception:
                    continue
                c, k, t, v = (pick(head.columns, COMP), pick(head.columns, KPI),
                              pick(head.columns, TIME), pick(head.columns, VAL))
                if not all([c, k, t, v]):
                    continue                      # wide-format files (e.g. metric_app) are skipped, not guessed at
                try:
                    df = pd.read_csv(f, usecols=[c, k, t, v])
                except Exception:
                    continue
                df[t] = pd.to_numeric(df[t], errors="coerce")
                df[v] = pd.to_numeric(df[v], errors="coerce")
                df = df.dropna(subset=[t, v])
                if df.empty: continue
                # timestamps are ms in some files, s in others — normalise by magnitude
                if df[t].max() > 1e11: df[t] = df[t] / 1000.0
                df = df.rename(columns={c: "comp", k: "kpi", t: "ts", v: "val"})
                rows.append(df[["comp", "kpi", "ts", "val"]])

            if not rows: sys.exit(0)
            d = pd.concat(rows, ignore_index=True)
            win  = d[(d.ts >= T0) & (d.ts <= T1)]
            base = d[(d.ts <  T0) | (d.ts >  T1)]
            if win.empty or base.empty: sys.exit(0)

            bstat = base.groupby(["comp", "kpi"])["val"].agg(
                med="median", mad=lambda x: np.median(np.abs(x - np.median(x))))
            out = []
            for (comp, kpi), g in win.groupby(["comp", "kpi"]):
                if (comp, kpi) not in bstat.index: continue
                med = bstat.loc[(comp, kpi), "med"]; mad = bstat.loc[(comp, kpi), "mad"]
                scale = 1.4826 * mad
                if scale <= 0:
                    # baseline never varied; only interesting if the window LEAVES that constant
                    dev = g.val[g.val != med]
                    if dev.empty: continue
                    z = 99.0
                    at = g.ts[g.val != med].iloc[0]
                else:
                    zs = (g.val - med).abs() / scale
                    i = zs.values.argmax()
                    z = float(zs.values[i]); at = float(g.ts.values[i])
                    if z < 3.0: continue
                out.append((z, comp, kpi, at))

            out.sort(key=lambda r: -r[0])
            for z, comp, kpi, at in out[:TOPN]:
                when = pd.to_datetime(at, unit="s") + pd.Timedelta(hours=8)   # OpenRCA reports UTC+8
                print("CAND|%%s|%%s|%%.1f|%%s" %% (comp, kpi, z, when.strftime("%%Y-%%m-%%d %%H:%%M:%%S")))
            """.formatted(q(telemetryRoot), q(w.day()), w.startEpoch(), w.endEpoch(), topN);
    }

    private static String q(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
