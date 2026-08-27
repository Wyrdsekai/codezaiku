package org.codezaiku.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.drive.DriveClient;
import org.codezaiku.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.codezaiku.Config;

/**
 * The GATED remediation phase (PLAN_CODEZAIKU_OPS.md §6) — SEPARATE from diagnosis, runs only when
 * explicitly enabled, and mutation is allowed here (the read-only guard is lifted). Given the diagnosed
 * root cause, the model applies the smallest fix and verifies resolution.
 *
 * <p>The differentiator is the CLOSED LOOP: after the model claims resolved, the harness re-runs an
 * OBJECTIVE check ({@code harnessVerifyCmd}) and records {@code harnessVerified} — the model's own claim
 * is never trusted. This is the author↔operate + closed-loop verification the SRE landscape says nobody
 * does; CodeZaiku can both diagnose AND act AND prove the fix.
 */
public final class RemediationLoop {
    private static final Logger log = LoggerFactory.getLogger(RemediationLoop.class);

    private final DriveClient drive;
    private final Exec exec;
    private final ToolRegistry tools;
    private final RemediationDoneTool done;
    private final String incident;
    private final InvestigationResult diagnosis;
    private final String harnessVerifyCmd;   // nullable — an objective "is it resolved?" command (exit 0 = yes)
    private final int maxIterations;
    private final ObjectMapper j = new ObjectMapper();
    // The environment grounding from the diagnosis phase (compose roster, container names, compose file
    // path, …). WITHOUT it the remediator is blind to the box it must act on — observed: it burned every
    // turn running `find / -name docker-compose.yml` and never applied a fix.
    private String precheckBlock = "";

    public RemediationLoop context(String precheckBlock) {
        this.precheckBlock = (precheckBlock == null) ? "" : precheckBlock;
        return this;
    }

    public RemediationLoop(DriveClient drive, Exec exec, ToolRegistry tools, RemediationDoneTool done,
                           String incident, InvestigationResult diagnosis, String harnessVerifyCmd,
                           int maxIterations) {
        this.drive = drive;
        this.exec = exec;
        this.tools = tools;
        this.done = done;
        this.incident = incident;
        this.diagnosis = diagnosis;
        this.harnessVerifyCmd = harnessVerifyCmd;
        this.maxIterations = maxIterations;
    }

    public RemediationResult run() {
        OpsContext ctx = new OpsContext(drive, j, drive.contextWindow());
        ArrayNode toolSchemas = tools.toolsArray(j);
        ArrayNode history = j.createArrayNode();
        history.addObject().put("role", "user").put("content", kickoff());
        // REPETITION GUARD: at temp 0 (deterministic, to follow the fix procedure) a small model FIXATES —
        // it re-ran the identical probe 12+ times and never applied the fix. On an exact repeat, don't run it
        // again; nudge toward the procedure's exact command / its alternative. (The measured refstack driver
        // needed this; without it temp-0 remediation was 0/3, with it it followed the card.) The guard is
        // STATE-RELATIVE — see ActionLedger: a mutation invalidates earlier observations, so destroying a
        // service no longer suppresses the command that would recreate it.
        ActionLedger ledger = new ActionLedger();
        int repeats = 0;

        boolean finished = false;
        for (int iter = 1; iter <= maxIterations && !finished; iter++) {
            ctx.compact(history, systemPrompt());
            ArrayNode messages = j.createArrayNode();
            messages.addObject().put("role", "system").put("content", systemPrompt());
            messages.addAll(history);
            // Temp 0.7 (the loop default): on THIS tool-calling path, temp-0 made the 9B deterministically
            // LOCK onto one diagnostic command and re-emit it every turn (the repetition guard could suppress
            // but not un-stick it) — measured 0/3 vs 1/3 at 0.7. Exploration is what lets it escape a bad
            // first action; the grounding levers (grounded card, creds, symptom) + the repetition guard carry
            // the "follow the fix" job instead. (Deterministic sampling is still right for the one-shot
            // localizer classifier — see DriveClient.classify — just not for multi-turn remediation here.)
            ObjectNode assistant = drive.chat(
                    messages, toolSchemas, ctx.outputBudget(messages, systemPrompt()), "required");
            history.add(assistant);
            JsonNode calls = assistant.path("tool_calls");
            if (!calls.isArray() || calls.isEmpty()) {
                history.addObject().put("role", "user").put("content",
                        "Act by calling run_command to apply/verify the fix, or remediation_done when resolved.");
                continue;
            }
            for (JsonNode call : calls) {
                String id = call.path("id").asText("call_" + iter);
                String name = call.path("function").path("name").asText("");
                JsonNode args = parseArgs(call.path("function").path("arguments").asText("{}"));
                if (name.equals("remediation_done")) {
                    tools.execute("remediation_done", args);
                    log.info("iter {}: remediation_done — steps={}", iter, done.appliedSteps().size());
                    toolResult(history, id, name, "recorded");
                    finished = true;
                    break;
                }
                String peek = args.path("command").asText("");
                if (name.equals("run_command") && !peek.isBlank() && ledger.isPointlessRepeat(peek)) {
                    repeats++;
                    log.info("iter {}: run_command REPEAT suppressed ({})", iter,
                            peek.length() > 80 ? peek.substring(0, 80) + "…" : peek);
                    toolResult(history, id, name, "You have ALREADY run that exact command and it did not "
                            + "resolve the incident — its result is above. Do NOT repeat it. Apply the FIX "
                            + "PROCEDURE's exact command now (run it verbatim, substituting the real "
                            + "user/db/container shown above), or its alternative (e.g. a restart clears an "
                            + "in-memory setting). Then verify.");
                    continue;
                }
                String obs = tools.execute(name, args);
                // WHAT WE SAW IT DO, not what it says it did. `applied_steps` is the model's own account,
                // reported only if it calls remediation_done at all — so a run that restarted, stopped and
                // then DELETED the root container recorded `applied: []` (measured on macOS). An audit trail
                // that omits the most destructive action of the run is the wrong shape, so the harness keeps
                // its own list of the mutating commands it actually executed.
                if (name.equals("run_command") && !peek.isBlank()) ledger.executed(peek);
                log.info("iter {}: run_command({})", iter, peek.length() > 80 ? peek.substring(0, 80) + "…" : peek);
                obs = obs + turnFeedback();
                toolResult(history, id, name, obs);
            }
        }

        // CLOSED-LOOP VERIFY: re-run the objective check ourselves — the model's claim is not trusted.
        Boolean harnessVerified = null;
        if (harnessVerifyCmd != null && !harnessVerifyCmd.isBlank()) {
            Exec.Result r = exec.run(harnessVerifyCmd, 30);
            harnessVerified = r.ok();
            log.info("harness verify [{}] → exit {} ({})", harnessVerifyCmd, r.exit(),
                    harnessVerified ? "RESOLVED" : "NOT resolved");
        }
        return new RemediationResult(done.appliedSteps(), done.called(), done.verification(), harnessVerified,
                ledger.mutations());
    }

    // PER-TURN RESOLVED-STATE FEEDBACK (the verify lever, measured on AIOpsLab): after each action, run the
    // objective resolution probe and append the result AS A FACT. The dominant remediation failure mode is
    // not knowing WHEN TO STOP — the A/B showed this feedback halves steps on successful runs (16→7, 10→5,
    // 26→7) with no success-rate harm. Facts only, and the line names exactly what was checked — a signal
    // that is silent about a fault class must say so, or its silence reads as health (the recon lesson:
    // a pod-level map made auth faults WORSE via false reassurance). Here the probe IS the incident's own
    // resolution check, so its scope is the right one.
    //
    // DEFAULT OFF — measured in the product (4 remediation fixtures, K=3, 9B): net neutral (11/12 off vs
    // 10/12 on) with one cell DOWN (cascade-007 remediation 3/3 -> 1/3; wrong-diagnosis flail resolved the
    // probe without feedback, didn't with it — variance or real, K=3 can't tell). A new feature enters the
    // product default-off until it beats the certified baseline (the 12/12 was earned without it). The
    // AIOpsLab-measured upside (halved steps on successful runs) stays available: CODEZAIKU_OPS_TURN_FEEDBACK=on.
    private static final boolean TURN_FEEDBACK =
            "on".equalsIgnoreCase(Config.get("CODEZAIKU_OPS_TURN_FEEDBACK"));
    private Boolean lastProbe = null;

    private String turnFeedback() {
        if (!TURN_FEEDBACK || harnessVerifyCmd == null || harnessVerifyCmd.isBlank()) return "";
        Exec.Result r = exec.run(harnessVerifyCmd, 30);
        boolean ok = r.ok();
        String delta = (lastProbe != null && lastProbe != ok)
                ? (ok ? "  (changed: was failing last turn)" : "  (changed: was passing last turn)") : "";
        lastProbe = ok;
        return "\n[harness check — this one probe only] `" + harnessVerifyCmd + "` → exit " + r.exit()
                + (ok ? " (resolution probe PASSES)" : " (resolution probe still failing)") + delta;
    }

    // POSITIVE directives only (state the action to take). Negative phrasing makes a model attend to the very
    // thing it is told to avoid, and this 9B is sensitive to instruction volume.
    private String systemPrompt() {
        return "You are CodeZaiku's ops remediator, connected to " + exec.describe() + ". This is the APPROVED "
             + "remediation phase — you may change the system. Apply the SMALLEST fix that resolves the "
             + "diagnosed root cause, keeping your changes limited to that cause. Then run a command that "
             + "CONFIRMS the incident is resolved (the service is up and serving, the port is free, the config "
             + "validates). Call remediation_done with what you changed and the proof. Act every turn.\n\n"
             + "ACTING ON CONTAINERS: `docker start <name>` / `docker restart <name>` is enough to bring a "
             + "service back. Use a container name exactly as it appears in the list above; to confirm a real "
             + "name, run `docker ps -a`. Verify against the REAL end state — curl the service and check it "
             + "actually serves.";
    }

    private String kickoff() {
        StringBuilder sb = new StringBuilder();
        sb.append("INCIDENT:\n").append(incident.strip()).append("\n\n");
        if (precheckBlock != null && !precheckBlock.isBlank()) {
            sb.append(precheckBlock).append('\n');   // the box's state: services, container names, compose file
        }
        sb.append("CONFIRMED DIAGNOSIS:\n");
        sb.append("  root cause (").append(diagnosis.rootCauseCategory()).append("): ")
          .append(diagnosis.rootCause()).append('\n');
        // Lead with the EXACT fix command as the mandated FIRST action. Both model tiers, given only a prose
        // procedure, restart/improvise instead of running the fix (measured: postgres read-only ~1/3); handing
        // them the concrete command to run verbatim is what makes the fix land. Restart is called out as wrong
        // because it is the default wrong move (it does not clear a persisted setting like ALTER DATABASE).
        boolean haveCmd = diagnosis.remediationSteps() != null && !diagnosis.remediationSteps().isEmpty();
        if (haveCmd) {
            sb.append("\nRUN THIS EXACT COMMAND FIRST (verbatim, as your first action — do NOT restart the ")
              .append("service or improvise a different command; a restart does not clear a persisted setting):\n");
            for (String step : diagnosis.remediationSteps()) sb.append("    ").append(step).append('\n');
        }
        sb.append(haveCmd ? "\nRun it, then verify the incident is resolved and call remediation_done."
                          : "\nApply the fix now, verify the incident is resolved, then call remediation_done.");
        return sb.toString();
    }

    private void toolResult(ArrayNode history, String id, String name, String content) {
        ObjectNode m = history.addObject();
        m.put("role", "tool");
        m.put("tool_call_id", id);
        m.put("name", name);
        m.put("content", content);
    }

    private JsonNode parseArgs(String raw) {
        try {
            return j.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (Exception e) {
            return j.createObjectNode();
        }
    }
}
