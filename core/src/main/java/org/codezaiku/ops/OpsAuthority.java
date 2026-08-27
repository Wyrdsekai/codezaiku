package org.codezaiku.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.codezaiku.redact.Redactor;
import org.codezaiku.Config;

/**
 * The AUTHORITY LADDER runtime (DESIGN.md R0–R4) — decides HOW MUCH the loop may do for a given incident,
 * instead of always auto-remediating. A fault-class earns its rung per the R4 gate; the runtime then operates
 * it at that rung, capped by a global ceiling and a kill-switch, with every action written to an audit log.
 *
 * <ul>
 *   <li><b>OBSERVE</b> — sense the stack, report. No localization decision, no action.</li>
 *   <li><b>LOCALIZE</b> — name the root-cause service, report. No action.</li>
 *   <li><b>PROPOSE</b> — localize + match a fix card + surface the exact fix COMMAND for a human to approve.
 *       No mutation. This is where an un-validated (candidate) card, or a low-confidence localization, stops.</li>
 *   <li><b>GUARDED</b> — auto-remediate WITH the full guard stack (blast-radius, closed-loop verify, R3
 *       rollback, harm check). A human is expected to review the audit trail.</li>
 *   <li><b>UNATTENDED</b> — auto-remediate, no human in the loop. Same mechanics as GUARDED; the difference is
 *       policy: the class has earned trust, so it is not flagged for review.</li>
 * </ul>
 *
 * <p>The effective rung = min(global ceiling, what this incident has EARNED). A validated card can reach
 * UNATTENDED; an unvalidated (candidate) card is capped at PROPOSE (an unproven fix must never auto-apply —
 * the same do-no-harm rule as {@code OpsKnowledge}'s candidate→rescue); no card at all caps at LOCALIZE (we
 * can name the root but have no vetted fix to propose). The kill-switch forces OBSERVE regardless.
 */
public final class OpsAuthority {
    private static final Logger log = LoggerFactory.getLogger(OpsAuthority.class);

    public enum Rung { OBSERVE, LOCALIZE, PROPOSE, GUARDED, UNATTENDED }

    private final Rung ceiling;
    private final boolean halted;
    private final boolean trial;    // measurement lane: auto-apply a CANDIDATE card (under full guards) to A/B it
    private final Path auditPath;   // nullable
    private final String alertUrl;  // nullable — webhook for high-priority events (harm/rollback)

    private OpsAuthority(Rung ceiling, boolean halted, boolean trial, Path auditPath, String alertUrl) {
        this.ceiling = ceiling;
        this.halted = halted;
        this.trial = trial;
        this.auditPath = auditPath;
        this.alertUrl = alertUrl;
    }

    /**
     * Build from the environment:
     * <ul>
     *   <li>{@code CODEZAIKU_OPS_AUTHORITY} = observe|localize|propose|guarded|unattended — the global ceiling.
     *       Absent: falls back to the legacy {@code CODEZAIKU_OPS_REMEDIATE=on} → GUARDED, else LOCALIZE.</li>
     *   <li>{@code CODEZAIKU_OPS_HALT=on} OR a {@code CODEZAIKU_OPS_HALT_FILE} that exists → kill-switch.</li>
     *   <li>{@code CODEZAIKU_OPS_AUDIT} = path → append a JSON-line audit record per action.</li>
     * </ul>
     */
    public static OpsAuthority fromEnv() { return fromEnv(null); }

    /** As {@link #fromEnv()}, but {@code ceilingOverride} (e.g. from a `fix <scope> <ceiling>` arg) wins over
     *  the {@code CODEZAIKU_OPS_AUTHORITY} env when non-blank. */
    public static OpsAuthority fromEnv(String ceilingOverride) {
        String a = (ceilingOverride != null && !ceilingOverride.isBlank())
                ? ceilingOverride : Config.get("CODEZAIKU_OPS_AUTHORITY");
        Rung ceiling;
        if (a != null && !a.isBlank()) {
            ceiling = parse(a, Rung.GUARDED);
        } else {
            ceiling = "on".equalsIgnoreCase(Config.get("CODEZAIKU_OPS_REMEDIATE")) ? Rung.GUARDED : Rung.LOCALIZE;
        }
        boolean halted = "on".equalsIgnoreCase(Config.get("CODEZAIKU_OPS_HALT"));
        String hf = Config.get("CODEZAIKU_OPS_HALT_FILE");
        if (!halted && hf != null && !hf.isBlank() && Files.exists(Path.of(hf))) halted = true;
        boolean trial = "on".equalsIgnoreCase(Config.get("CODEZAIKU_OPS_TRIAL"));
        String au = Config.get("CODEZAIKU_OPS_AUDIT");
        Path audit = (au == null || au.isBlank()) ? null : Path.of(au);
        String alert = Config.get("CODEZAIKU_OPS_ALERT_WEBHOOK");
        return new OpsAuthority(ceiling, halted, trial, audit, (alert == null || alert.isBlank()) ? null : alert);
    }

    private static Rung parse(String s, Rung dflt) {
        try { return Rung.valueOf(s.strip().toUpperCase(Locale.ROOT)); }
        catch (Exception e) { log.warn("unknown CODEZAIKU_OPS_AUTHORITY '{}' — defaulting to {}", s, dflt); return dflt; }
    }

    public Rung ceiling() { return ceiling; }
    public boolean halted() { return halted; }

    /**
     * The rung this incident may operate at: {@code min(ceiling, earned)}, forced to OBSERVE by the kill-switch.
     * {@code earned} = UNATTENDED if a VALIDATED fix card matched, PROPOSE if only a candidate card matched,
     * LOCALIZE if no card matched. {@code confident} gates auto-action: a low-confidence localization is
     * capped at PROPOSE so a shaky root never triggers a mutation.
     */
    public Rung effective(boolean haveCard, boolean cardValidated, boolean confident) {
        if (halted) return Rung.OBSERVE;
        // A validated card earns auto-remediation; a candidate only PROPOSES — UNLESS we're in the trial lane,
        // where a candidate auto-applies (still under the full guard stack) so a controlled A/B can measure it
        // and promote it. Production (trial=false) never auto-applies an unvalidated fix.
        boolean autoEligible = haveCard && (cardValidated || trial);
        Rung earned = !haveCard ? Rung.LOCALIZE : (autoEligible ? Rung.UNATTENDED : Rung.PROPOSE);
        Rung r = min(ceiling, earned);
        if (!confident && r.ordinal() > Rung.PROPOSE.ordinal()) r = Rung.PROPOSE;   // uncertain ⇒ don't auto-act
        return r;
    }

    /** True when the trial (measurement) lane is enabled — a candidate card may auto-apply to be A/B'd. */
    public boolean trial() { return trial; }

    public static Rung min(Rung a, Rung b) { return a.ordinal() <= b.ordinal() ? a : b; }

    /** Does this rung auto-apply a fix? (GUARDED and UNATTENDED do; OBSERVE/LOCALIZE/PROPOSE do not.) */
    public static boolean autoRemediates(Rung r) { return r.ordinal() >= Rung.GUARDED.ordinal(); }

    /** Append a structured audit record (JSON line). No-op if no audit path configured. Never throws. */
    public void audit(String event, String detail) {
        // The audit trail is the one artifact guaranteed to outlive the run, and `detail` carries
        // command lines and service output — the places a password shows up. Mask before it is logged
        // AND before it is written: an audit file is exactly what gets attached to a ticket.
        detail = Redactor.scrub(detail);
        log.info("AUDIT {} — {}", event, detail);
        if (auditPath == null) return;
        try {
            String ts = safeNow();
            String line = "{\"ts\":\"" + ts + "\",\"event\":\"" + esc(event) + "\",\"detail\":\"" + esc(detail) + "\"}\n";
            Files.writeString(auditPath, line, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("audit write failed: {}", e.getMessage());
        }
    }

    /** Fire a HIGH-priority ALERT (also audited): POST it to the alert webhook if configured. Fire-and-forget,
     *  never blocks the loop. Use for events an operator must SEE — a broken bystander (harm) or a rollback. */
    public void alert(String event, String detail) {
        audit("ALERT:" + event, detail);
        if (alertUrl == null) return;
        try {
            String body = "{\"ts\":\"" + safeNow() + "\",\"severity\":\"alert\",\"event\":\"" + esc(event)
                    + "\",\"detail\":\"" + esc(detail) + "\"}";
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(4)).build();
            var req = HttpRequest.newBuilder(URI.create(alertUrl))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            client.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("alert POST failed: {}", e.getMessage());
        }
    }

    private static String safeNow() {
        try { return Instant.now().toString(); } catch (Exception e) { return "unknown"; }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}
