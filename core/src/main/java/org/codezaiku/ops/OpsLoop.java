package org.codezaiku.ops;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.drive.DriveClient;
import org.codezaiku.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The LEAN ops diagnosis loop — a minimal ReAct over read-only tools ({@code run_command} + {@code conclude})
 * with context management. Deliberately NOT the heavy coding decompose loop (over-fitted to coding; adds
 * nothing on ops), and — as of the OpenRCA ablation — deliberately WITHOUT the conclusion-gate stack it used
 * to carry.
 *
 * <p><b>Why the gates are gone.</b> The loop previously rejected-and-nudged conclusions through six gates
 * (answer-fidelity, false-negative guard, two depth nudges, relevance, grounding verify) plus end-game
 * pressure and a forced conclusion. Every one was validated only on self-authored fixtures. Measured on a
 * benchmark we do not control (OpenRCA/Telecom, same model), turning ALL of them off scored the same as
 * leaving them on — mean 0.085 vs 0.082, n=51 — with three that could not even fire. Combined with the 2×2
 * (our full loop matched OpenRCA's lean reference agent) and the standing result that added instruction
 * volume hurts this model class (+rules 4/18 → 3/18), the gates were dead weight, so they were deleted.
 * Diagnosis quality is set by the model and the evidence it is given, not by loop nudges.
 *
 * <p>What remains earns its place: deterministic prechecks (narrow before the model spends a turn),
 * faithful compaction + context-overflow recovery ({@link OpsContext} — the robustness that let this loop
 * complete a run the reference agent crashed on), and soft duplicate-command suppression. Bounded by
 * {@code maxIterations}.
 */
public final class OpsLoop {
    private static final Logger log = LoggerFactory.getLogger(OpsLoop.class);

    private final DriveClient drive;
    private final ToolRegistry tools;
    private final ConcludeTool conclude;
    private final String goal;
    private final String precheckBlock;
    private final int maxIterations;
    private final Exec exec;
    private final ObjectMapper j = new ObjectMapper();

    private String runbookListing = "";   // when set, offer the runbook catalog + fetch_runbook (gated)
    private String runbookInject = "";    // when set, PUSH this matched runbook procedure into the kickoff

    // KNOWLEDGE (measured mechanism, see OpsKnowledge): a deferred bounded probe (logs + state bundle)
    // matches each card's fault signature, GATED by the card's match keywords against the stack text;
    // immediate-policy cards inject at the scan, rescue-policy cards are HELD for the rescue iteration.
    // TWO-SHOT: a first miss keeps the cards armed and rescans once (+4 iters) — signatures were measured
    // to lag fault onset by minutes (SREGym DNS family: single-shot missed, the rescan hit).
    private List<OpsKnowledge.Card> knowledgeCards = List.of();
    private String knowledgeStack = "";                     // env+workload identity for match-kw gating
    private Supplier<String> logProbe;   // platform probe: recent logs + STATE, ≤30s
    private List<String> heldCards;               // rescue-matched bodies awaiting the rescue turn
    private static final int SIG_SCAN_ITER = 5;             // after traffic has exercised the broken path
    private static final int RESCAN_ITER = SIG_SCAN_ITER + 4;  // second shot, then disarmed for the run
    private static final int RESCUE_ITER = 20;              // wins conclude at median 16-28; procedure needs ~11

    private OpsContext ctx;               // keeps the growing conversation inside the drive's window

    public OpsLoop(DriveClient drive, Exec exec, ToolRegistry tools, ConcludeTool conclude,
                   String goal, String precheckBlock, int maxIterations) {
        this.drive = drive;
        this.exec = exec;
        this.tools = tools;
        this.conclude = conclude;
        this.goal = goal;
        this.precheckBlock = precheckBlock;
        this.maxIterations = maxIterations;
    }

    /** Offer the runbook catalog in the system prompt (the fetch_runbook tool must also be registered). */
    public OpsLoop runbooks(String listing) {
        this.runbookListing = (listing == null) ? "" : listing;
        return this;
    }

    /** PUSH a matched runbook procedure into the first turn (for the 9B, which does not fetch on its own). */
    public OpsLoop runbookInject(String body) {
        this.runbookInject = (body == null) ? "" : body;
        return this;
    }

    /**
     * Arm signature-triggered knowledge cards. {@code stackText} is the environment+workload identity
     * (platform name, service/container names and images, precheck text) that a card's {@code match:}
     * keywords must hit before its signature is even considered — the two-layer trigger that keeps
     * host-tier and platform-tier cards with identical fault strings from co-firing (measured: the
     * host-DNS and k8s-DNS cards share "temporary failure in name resolution"; gating separated them).
     * {@code logProbe} is the wiring's bounded (≤30s) probe of recent logs PLUS a cluster/host STATE
     * bundle — three fault classes were measured log-quiet (scaled-to-zero, consumer lag, config
     * poisoning) with their evidence only in state.
     */
    public OpsLoop knowledge(List<OpsKnowledge.Card> cards, String stackText,
                             Supplier<String> logProbe) {
        this.knowledgeCards = (cards == null) ? List.of() : cards;
        this.knowledgeStack = (stackText == null) ? "" : stackText.toLowerCase(Locale.ROOT);
        this.logProbe = logProbe;
        return this;
    }

    public record Outcome(boolean concluded, int iterations, InvestigationResult result) { }

    /** The tool array reduced to `conclude` alone — the deadline turn (keeps tool_choice valid). */
    private ArrayNode onlyConclude(ArrayNode all) {
        ArrayNode one = all.arrayNode();
        for (JsonNode t : all)
            if (conclude.name().equals(t.path("function").path("name").asText())) one.add(t);
        return one.isEmpty() ? all : one;
    }

    public Outcome run() {
        this.ctx = new OpsContext(drive, j, drive.contextWindow());
        ArrayNode toolSchemas = tools.toolsArray(j);
        ArrayNode history = j.createArrayNode();
        // Seed the conversation with the incident as a USER message — the 9B's chat template requires at
        // least one user turn ("No user query found in messages."), and this is the right shape anyway:
        // system = role/method, user = the concrete incident + prechecks + kickoff.
        history.addObject().put("role", "user").put("content", kickoff());
        Set<String> ranCommands = new HashSet<>();

        // Rescue must leave the procedure room to run (~11 iterations measured) inside a shrunk budget.
        int rescueIter = Math.max(SIG_SCAN_ITER + 1, Math.min(RESCUE_ITER, maxIterations - 10));

        for (int iter = 1; iter <= maxIterations; iter++) {
            if (iter == SIG_SCAN_ITER || iter == RESCAN_ITER) signatureScan(history, iter);
            if (iter >= rescueIter && heldCards != null && !heldCards.isEmpty()) {
                pushCards(history, heldCards, "rescue, iter " + iter);
                heldCards = null;
            }
            ctx.compact(history, systemPrompt());
            // DEADLINE TURN: on the LAST iteration offer only `conclude`, so the budget is spent stating a
            // finding instead of opening one more command. Measured on the research loop, where the same
            // change took conclusion 33% -> 100% (p<0.0001): telling a small model its budget is nearly
            // gone does not work — it reads the warning and investigates again. Monotonic by construction:
            // it can only turn a no-conclusion into a conclusion, never undo an earlier one.
            ArrayNode turnTools = (iter == maxIterations) ? onlyConclude(toolSchemas) : toolSchemas;
            if (iter == maxIterations)
                history.addObject().put("role", "user").put("content",
                        "This is your LAST turn — no more commands. State your finding now with conclude(), "
                        + "based on what you have already seen. An honest partial finding that names what "
                        + "you could not determine is far better than no finding at all.");
            ObjectNode assistant = chatWithinWindow(history, turnTools);
            history.add(assistant);

            JsonNode calls = assistant.path("tool_calls");
            if (!calls.isArray() || calls.isEmpty()) {
                history.addObject().put("role", "user")
                        .put("content", "You must act by calling a tool: run_command to investigate, or "
                                + "conclude when you have the grounded root cause.");
                continue;
            }

            boolean concludedThisTurn = false;
            for (JsonNode call : calls) {
                String id = call.path("id").asText("call_" + iter);
                String name = call.path("function").path("name").asText("");
                JsonNode args = parseArgs(call.path("function").path("arguments").asText("{}"));

                // Soft duplicate suppression for run_command.
                if (name.equals("run_command")) {
                    String cmd = args.path("command").asText("").trim();
                    if (!cmd.isBlank() && !ranCommands.add(cmd)) {
                        toolResult(history, id, name, "(already ran this exact command earlier — its result is "
                                + "above. Run a DIFFERENT command or conclude.)");
                        continue;
                    }
                }

                if (name.equals("conclude")) {
                    // Bare ReAct: accept the model's first grounded conclusion. The stack of conclusion gates
                    // that used to sit here (answer-fidelity nudge, false-negative guard, two depth nudges,
                    // relevance nudge, grounding verify) was DELETED — an OpenRCA ablation showed all six OFF
                    // scored the same as all six ON (mean 0.085 vs 0.082, n=51) with three never firing, and
                    // added instruction volume is measured harmful on this model class (+rules 4/18 → 3/18).
                    // Diagnosis quality is set by the model and the evidence it is given, not by loop nudges.
                    tools.execute("conclude", args);
                    var cap = conclude.captured();
                    log.info("iter {}: conclude ACCEPTED — category='{}'", iter,
                            cap == null ? null : cap.rootCauseCategory());
                    toolResult(history, id, name, "conclusion accepted");
                    concludedThisTurn = true;
                    break;
                }

                String obs = tools.execute(name, args);
                String peek = name.equals("run_command") ? args.path("command").asText("") : args.toString();
                log.info("iter {}: {}({}) → {} chars", iter, name,
                        peek.length() > 80 ? peek.substring(0, 80) + "…" : peek, obs.length());
                toolResult(history, id, name, obs);
            }

            if (concludedThisTurn && conclude.called()) {
                return new Outcome(true, iter, conclude.captured());
            }
        }
        // Budget spent. Return whatever was captured (possibly nothing) — the bare loop does not force a
        // final conclusion. The forced-conclusion turn that used to be here lifted completion 48→51/51 but
        // did NOT improve accuracy (the ablation's higher mean was WITHOUT it), so it was dead weight too.
        return new Outcome(conclude.called(), maxIterations, conclude.captured());
    }

    /**
     * The deferred signature scan, TWO-SHOT: probe the stack's recent logs + state bundle and match each
     * card's fault signature, gated by the card's match keywords against the stack text. Deferred because
     * at init the signature is often not in the logs yet (the init-time scan fired 0/8); two-shot because
     * signatures were also measured to lag a few minutes past the first scan (first miss keeps the cards
     * armed; the rescan at {@link #RESCAN_ITER} disarms either way). A hit disarms immediately — the scan
     * fires at most one push per policy per run.
     * Every outcome is printed — treatment must be verifiable from the run log, never inferred from absence.
     */
    private void signatureScan(ArrayNode history, int iter) {
        if (knowledgeCards.isEmpty() || logProbe == null) return;
        String probe;
        try {
            probe = String.valueOf(logProbe.get()).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            System.out.println("[ops-knowledge] signature scan FAILED — " + e.getMessage());
            return;
        }
        // Push AT MOST ONE card — the strongest signature match. The 9B's context window is small; two
        // verbose cards crowd it (verbose-card-kills-convergence) and can give conflicting procedures.
        // Rank fired cards by how many of their signature strings are actually present.
        OpsKnowledge.Card best = null;
        long bestHits = 0;
        for (OpsKnowledge.Card c : knowledgeCards) {
            // Two-layer trigger: the card's stack keywords must hit the environment text before its
            // signature is considered — this is what keeps same-fault-string cards from different tiers
            // (host DNS vs cluster DNS) from co-firing.
            if (!knowledgeStack.isEmpty() && c.match().stream().noneMatch(knowledgeStack::contains)) continue;
            if (c.signature().isEmpty()) continue;
            // A procedure that cannot run on THIS target is worse than no card — the model follows it
            // into "command not found". The keyword gate cannot catch this: host-tier cards declare
            // `host`, which is true everywhere.
            String missing = OpsKnowledge.withheldTool(c, TargetOs.stackTokens(exec));
            if (missing != null) {
                System.out.println("[ops-knowledge] " + c.path() + " skipped — needs " + missing
                        + ", which this target does not have");
                continue;
            }
            long hits = c.signature().stream().filter(probe::contains).count();
            if (hits > bestHits) { bestHits = hits; best = c; }
        }
        List<String> now = new ArrayList<>(), held = new ArrayList<>();
        if (best != null) (best.immediate() ? now : held).add(best.body());
        boolean hit = !now.isEmpty() || !held.isEmpty();
        if (hit || iter >= RESCAN_ITER) knowledgeCards = List.of();   // disarm on hit or 2nd miss
        if (!now.isEmpty()) pushCards(history, now, "immediate, iter " + iter);
        if (!held.isEmpty()) {
            heldCards = held;
            System.out.println("[ops-knowledge] signature matched — " + held.size() + " card(s) HELD for rescue");
        }
        if (!hit) {
            System.out.println("[ops-knowledge] scan iter " + iter + ": no match (" + probe.length()
                    + " chars)" + (iter < RESCAN_ITER ? " — will rescan" : " — disarmed"));
        }
    }

    private void pushCards(ArrayNode history, List<String> bodies, String when) {
        String kb = "## OPS KNOWLEDGE (matched this fault signature in the stack's logs — fix procedure)\n\n"
                + String.join("\n\n", bodies);
        history.addObject().put("role", "user").put("content", kb);
        System.out.println("[ops-knowledge] signature card PUSHED (" + when + ", " + kb.length() + " chars)");
        log.info("knowledge PUSHED ({}, {} chars)", when, kb.length());
    }

    private String systemPrompt() {
        StringBuilder sb = new StringBuilder();
        // Written as POSITIVE directives (state the action to take). Negative phrasing makes a model attend
        // to the very thing it is told to avoid, and this 9B is sensitive to instruction volume.
        sb.append("You are CodeZaiku's ops diagnostician, connected to ONE real machine (")
          .append(exec.describe()).append("). Find the TRUE root cause of the reported problem and PROVE it. "
          + "This is a READ-ONLY investigation; remediation is a separate approved step.\n\n");
        sb.append("HOW TO WORK:\n");
        sb.append("  1. Investigate with run_command: inspect services (systemctl status / journalctl -u, or "
                + "ps if there is no systemd), resources (df -h, free -m, top -bn1), ports (ss -ltnp), logs, "
                + "and configs. One focused command at a time.\n");
        sb.append("  2. Form a hypothesis, then RUN A COMMAND THAT WOULD CONFIRM OR REFUTE IT. Treat error "
                + "messages and exit codes as exact evidence.\n");
        sb.append("  3. Distinguish CAUSE from SYMPTOM. Ask 'why' until you reach the underlying cause, and "
                + "verify which finding is the cause and which is its effect before you blame it.\n");
        sb.append("  4. Once the root cause is grounded in actual command output, call conclude. Trace EVERY "
                + "claim to a specific command output you saw and put those outputs in `evidence`; mark "
                + "anything you could not verify as unverified.\n");
        sb.append("  5. When your checks show the system is serving correctly and the reported symptom does "
                + "not reproduce, conclude with root_cause_category \"healthy\" and state what you verified — "
                + "a false alarm is a correct, valid outcome.\n\n");
        if (runbookListing != null && !runbookListing.isBlank()) {
            sb.append("\n\nRUNBOOKS — when the incident matches one of these, call fetch_runbook(id) EARLY and "
                    + "follow its procedure to reach the ROOT cause (it overrides the default steps):\n")
              .append(runbookListing);
        }
        sb.append("\nBe economical: each command should gather NEW information. Act by calling a tool every "
                + "turn.");
        return sb.toString();
    }

    /** The first USER turn: the concrete incident + deterministic prechecks + a kickoff to begin. */
    private String kickoff() {
        StringBuilder sb = new StringBuilder();
        sb.append("INCIDENT:\n").append(goal.strip()).append("\n\n");
        if (precheckBlock != null && !precheckBlock.isBlank()) {
            sb.append(precheckBlock).append('\n');
        }
        if (runbookInject != null && !runbookInject.isBlank()) {
            sb.append("RELEVANT RUNBOOK — follow this procedure to reach the ROOT cause:\n")
              .append(runbookInject.strip()).append("\n\n");
        }
        sb.append("Begin your read-only investigation now. Call run_command to gather evidence, then conclude "
                + "with the grounded root cause.");
        return sb.toString();
    }

    /**
     * Ask the drive, and treat its context-overflow 400 as the authority on how big the prompt really is.
     *
     * <p>We can only estimate tokens from characters, and telemetry output tokenizes densely enough that the
     * estimate can be wrong right at the ceiling (a run died at 32775 tokens against a 32768 window — seven
     * over). Rather than let an arithmetic miss kill an investigation, each overflow shrinks the history
     * harder and retries. An investigation that forgets its early turns still answers; one that throws does
     * not, and on the benchmark a thrown run was indistinguishable from a wrong diagnosis.
     */
    private ObjectNode chatWithinWindow(ArrayNode history, ArrayNode toolSchemas) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            ArrayNode messages = j.createArrayNode();
            messages.addObject().put("role", "system").put("content", systemPrompt());
            messages.addAll(history);
            try {
                return drive.chat(messages, toolSchemas,
                        ctx.outputBudget(messages, systemPrompt()), "required");
            } catch (RuntimeException e) {
                if (!OpsContext.isOverflow(e)) throw e;
                last = e;
                ctx.shrinkHard(history);
            }
        }
        throw last;
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
            try {
                return JsonMapper.builder()
                        .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                        .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                        .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                        .build().readTree(raw);
            } catch (Exception e2) {
                return j.createObjectNode();
            }
        }
    }
}
