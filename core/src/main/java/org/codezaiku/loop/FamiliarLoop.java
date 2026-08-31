package org.codezaiku.loop;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.drive.DriveClient;
import org.codezaiku.library.Library;
import org.codezaiku.library.LibraryIndex;
import org.codezaiku.shape.ProjectShape;
import org.codezaiku.tools.TaskBlockedTool;
import org.codezaiku.tools.TaskDoneTool;
import org.codezaiku.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.codezaiku.library.ErrorQuery;
import org.codezaiku.library.LibraryScope;
import org.codezaiku.lsp.LspClient;
import org.codezaiku.shape.ProjectFacts;
import org.codezaiku.redact.Redactor;
import org.codezaiku.verify.BootCheck;
import org.codezaiku.verify.ProjectTests;
import org.codezaiku.Config;
import org.codezaiku.exec.Shell;

/**
 * The execute → observe → act loop. ONE growing conversation — no episodic wipe (L1: the conversation
 * IS the memory). Every turn the derived structure map + goal are re-pinned into a fresh system message
 * (ground truth that can't drift). The model can only act ({@code tool_choice=required}); it decides
 * when it's done by calling {@code task_done} — taken at its word (the field norm: model-decides; the
 * harness MEASURES success externally, it does not in-loop-gate completion on a build/test reject).
 *
 * <p>This is single-loop, model-driven (the reference-harness consensus): no separate planner call, no
 * mechanical step-gate, no test-reject. Planning is smallcode-style — for a multi-concern task we ask
 * the model to emit a numbered plan in its first response and re-inject it as a SOFT anchor; if it
 * doesn't parse we continue plan-less (never abort). The levers are faithful compaction, ACI robustness
 * (forgiving parse, spin-guard), and grounding (read_dep_source / LSP / library).
 */
public final class FamiliarLoop {
    private static final Logger log = LoggerFactory.getLogger(FamiliarLoop.class);

    private final DriveClient drive;
    private final ToolRegistry tools;
    private final Path projectRoot;
    private final String goal;
    private final int maxTurns;
    private final Library library; // nullable — pushed worked-examples, language/framework gated
    private final LibraryIndex index; // nullable — framework-knowledge retrieval, harness-pushed
    // TASK-TYPE gate (objective, fixture-agnostic): true if the project already holds a substantial
    // pre-existing codebase at loop start (MAINTENANCE), so the error-grounding injection is suppressed.
    private final boolean maintenanceProject;
    private boolean multiConcern; // whether to ask for a plan anchor (multi-step tasks only)
    private final String proactiveIdioms; // STABLE small idiom block, computed ONCE (order, not stream)
    private final String wiringSketch; // per-spec end-to-end wiring skeleton from the 30B, computed ONCE
    private final int nctx;
    /**
     * Characters per token for every budgeting decision here.
     *
     * <p>Three, not four. A host measured 78,637 request characters arriving as 23,461 tokens —
     * chars/3.35 — because paths and code tokenize far denser than prose, and the densest content is
     * exactly what fills the prompt on a large repository. A /4 estimate therefore under-counts by
     * around 20% precisely when the window is tightest, so a request that "fits" is refused. The ops
     * surface reached the same constant the same way.
     */
    static final int CHARS_PER_TOKEN = 3;
    private final ObjectMapper j;
    // Per-project working memory: external, harness-owned, tracks persistent build errors so the
    // compiler-suggested fix stays pinned across compaction.
    private final FamiliarMemory memory;
    // Language server (rust-analyzer/pyright/…): SHARED with the replace_symbol tool, supplied by the
    // caller via lsp(...). The loop owns its close (the run lifecycle). Nullable → LSP degrades to off.
    private LspClient lsp;

    /** Share the (already-created) language-server client for per-edit diagnostics. */
    public FamiliarLoop lsp(LspClient client) {
        this.lsp = client;
        return this;
    }

    // Cooperative cancellation, for a protocol host that can cancel a turn in flight (ACP
    // `session/cancel`). Checked at the TOP of each turn rather than enforced by interrupting the
    // thread: an interrupt lands in the middle of an HTTP call or a file write, and the whole point of
    // a cancel is to stop cleanly, not to leave a half-written file behind. The cost is that a cancel
    // takes effect at the next turn boundary, which the protocol layer reports honestly.
    private BooleanSupplier cancelled = () -> false;

    /** Ask {@code check} before each turn; when it answers true the run stops and reports not-done. */
    public FamiliarLoop cancelIf(BooleanSupplier check) {
        if (check != null) this.cancelled = check;
        return this;
    }

    // ── PLAN ANCHOR (smallcode plan-then-execute) ──────────────────────────────────────────────────
    // No separate planner LLM call (that was brittle — a malformed JSON array aborted the whole run with
    // "PLAN FAILED"). Instead, for a multi-concern task we ask the model to emit a numbered PLAN in its
    // FIRST response (alongside starting work), parse it leniently from that same text, and — if it
    // doesn't parse — just continue plan-less (NEVER abort, the smallcode `catch{}` rule). The plan is
    // re-injected every turn as a SOFT anchor (✓/→ markers) so the model stays oriented after compaction
    // trims early turns. NO green-gate, NO auto-advance, NO step_done ("auto-advance leads to drift" —
    // smallcode). The model decides done; the harness measures externally.
    private List<String> planSteps = List.of();
    private final Set<Integer> completedSteps = new HashSet<>();
    private boolean planResolved = false; // one-shot: true once the first-turn plan is parsed or given up on
    // HARNESS-DRIVEN done gate: the model is the coder, the harness is the DRIVER. It does not accept the
    // model's word that it's done — at task_done it re-runs the tests itself and refuses while they're red
    // (then the boot-gate + mutation gate run, driving the model to a verified-real done). The model's plan
    // is orientation only — we do NOT gate on a "step N done" marker, because this 9B doesn't emit one (it
    // plans, builds in one pass, calls done); a marker-driven queue just bounce-burned. Off: CODEZAIKU_DRIVE=off.
    private final boolean driveGateOnEnv = !"off".equalsIgnoreCase(Config.get("CODEZAIKU_DRIVE"));
    private boolean driveGate = true;   // research() clears this
    private boolean bootGate = true;    // research() clears this
    private int driveBounces = 0;
    // SELF-VERIFICATION REFLECTION (2026-06, research-backed: Reflexion / in-execution self-debugging /
    // ReVeal; runtime-structured-decomposition's "validation failure → targeted repair, not full rerun") — a
    // POSITIVE BOUNDED-ITERATIVE loop step, NOT a gate: on task_done the harness asks the model to RUN its own
    // deliverable end-to-end on real inputs and inspect the ACTUAL output for stub/placeholder/constant/hollow
    // results — the failure mode where shape/unit tests pass a stubbed last link (fine-tune predict()->"Unknown";
    // rag placeholder context; ner nested-output). The MODEL verifies + repairs. The harness computes no oracle
    // and rejects nothing; it re-verifies ONLY while the model keeps repairing (edited a file since the last
    // pass), capped at MAX_SELFVERIFY — so a multi-link pipeline gets peeled link-by-link, while a clean run
    // gets exactly ONE pass then is accepted. Off: CODEZAIKU_SELFVERIFY=off.
    private boolean selfVerifyOn = !"off".equalsIgnoreCase(Config.get("CODEZAIKU_SELFVERIFY"));

    /** RESEARCH mode: the deliverable is a written, cited ANSWER — not code or an artifact. Turn off the
     *  self-verify reflection + drive/boot gates, which ask the model to "run your pipeline end-to-end" and
     *  bounce task_done for a project that does not exist here (observed: research never concluded), and turn
     *  ON the gap-reflection loop (the research analogue of self-verify). */
    public FamiliarLoop research() {
        this.selfVerifyOn = false;
        this.driveGate = false;
        this.bootGate = false;
        this.researchMode = true;
        this.deadlineTurn = true;
        this.gapReflect = !"off".equalsIgnoreCase(Config.get("CODEZAIKU_GAPREFLECT"));
        return this;
    }
    private boolean researchMode = false;

    /** REPORT mode (review): the deliverable is a findings LIST — read-only, nothing built, no tests.
     *  The coding gates fire anyway on the default loop and bounce {@code task_done} forever: a measured
     *  review run spent all 30 turns being bounced with "task_done but tests RED" and "[fake core]" on a
     *  READ-ONLY task where no test could be green and no core could exist, and produced no findings at
     *  all. Same diagnosis research() already carries; review needed it too, including the deadline turn
     *  — a review that runs out of turns silently is worth exactly as much as a research run that does. */
    public FamiliarLoop report() {
        this.selfVerifyOn = false;
        this.driveGate = false;
        this.bootGate = false;
        this.deadlineTurn = true;
        return this;
    }
    /**
     * CHAT mode: a person is on the other end, and one {@code task_done} ends the turn.
     *
     * <p>Fourth instance of the disease {@link #research()}, {@link #report()} and
     * {@link #artifact()} carry, and the worst-mannered of the four. The self-verify reflection is
     * injected as a user-role message, so in a conversation the model attributes it to the PERSON —
     * measured live, first real planning chat: the operator asked one question; the model answered it well
     * and called {@code task_done} at turn 3; the reflection then injected the coding-verification
     * protocol, and the model responded <i>"the user has provided a massive, highly specific
     * verification protocol"</i>, apologised to the operator for instructions never sent, burned
     * three more turns re-reading files it had already read, and finally manufactured a
     * {@code gradle init} nobody asked for — leaving a person at an approval prompt for an action
     * with no author.
     *
     * <p>In chat the human IS the verifier: they are looking at the answer, and the next thing they
     * type is the reflection. The deadline turn stays on — a turn that ends with nothing said is
     * still the worst outcome.
     */
    public FamiliarLoop chat() {
        this.selfVerifyOn = false;
        this.driveGate = false;
        this.bootGate = false;
        this.deadlineTurn = true;
        this.chatMode = true;
        return this;
    }
    private boolean chatMode = false;

    /**
     * ARTIFACT mode: the deliverable is a single named file, and there is nothing to verify beyond
     * its existence and content.
     *
     * <p>Third instance of the disease {@link #research()} and {@link #report()} already carry. The
     * drive gate re-runs tests on {@code task_done} and refuses while they are red — sound for a
     * codebase, wrong for a task whose whole deliverable is one file, because the model satisfies
     * the gate by INVENTING a test suite. Measured by a caller: a request for one JavaScript file
     * spent its entire turn budget writing a Python test module and a fetcher package alongside it.
     *
     * <p>The deadline turn stays on for the same reason it does elsewhere: a budget spent with
     * nothing written is the failure that matters most, and it is the one this mode must not trade
     * away while removing the others.
     */
    public FamiliarLoop artifact() {
        this.selfVerifyOn = false;
        this.driveGate = false;
        this.bootGate = false;
        this.deadlineTurn = true;
        this.multiConcern = false;      // no plan-of-slices prompt: there is one slice
        return this;
    }

    /** Last turn offers only task_done. Set by research() and report() — NOT tied to researchMode, because
     *  the failure it prevents (budget spent, nothing written) is not specific to research. */
    private boolean deadlineTurn = false;
    private final Set<Integer> budgetWarned = new HashSet<>();

    private boolean artifactBounced = false;
    private boolean findingsBounced = false;
    /** How many findings the review has reported so far. -1 means this run does not report findings,
     *  which disables the bounce entirely — only a review supplies a real counter. */
    private IntSupplier findingCount = () -> -1;

    /** Let the loop see the review's finding count, so finishing with none can be questioned once. */
    public FamiliarLoop findings(IntSupplier count) {
        this.findingCount = count;
        return this;
    }
    private boolean researchBlockBounced = false;
    private int consecutiveDriveFailures = 0;  // reset on any successful drive call
    /** Consecutive drive failures before the run stops instead of retrying. A failing endpoint
     *  under "unlimited turns" is an unbounded tight retry loop — measured 2026-08-29: a
     *  misconfigured HOSTED drive 404'd and the loop spun 2,600+ turns in seconds. Against a
     *  paid API that pattern is a money pump; against any drive it is noise. */
    static final int MAX_CONSECUTIVE_DRIVE_FAILURES = 8;
    private boolean mutatingCallRan = false;   // any write_file/edit_file/shell this run
    private boolean falseWriteBounced = false; // the chat false-write bounce fires once
    private boolean proseAnswerNext = false;   // next turn is tool_choice="none"; its prose IS the answer
    // EPILOGUE (research): when the DEADLINE turn's forced task_done arrives without the artifact the
    // question demands, the normal artifact bounce can't fire (no turns left) and the run used to end with
    // a status description instead of the table (ws_en_028 regression: 40 turns of gathering, zero table).
    // Grant exactly ONE bonus prose turn to write the final answer from what was gathered. Bounded, once.
    private int epilogueTurns = 0;
    private boolean epilogueGranted = false;
    // Draft supplier (research): content the model saved incrementally via add_to_answer. Merged in front
    // of the final answer — the model is told never to re-type saved content, so the summary/prose holds
    // only what ISN'T in the draft.
    private Supplier<String> answerDraft = () -> "";
    private boolean draftUsed = false;
    private boolean saveNudged = false;
    private int fetchesUnsaved = 0;

    public FamiliarLoop answerDraft(Supplier<String> draft) {
        this.answerDraft = draft;
        return this;
    }

    /** Final answer = saved draft + closing text (either part may be empty). */
    private String withDraft(String closing) {
        String d = answerDraft.get();
        if (d == null || d.isBlank()) return closing;
        return closing == null || closing.isBlank() ? d : d + "\n\n" + closing;
    }

    /** Strip qwen-style tool-call markup a model sometimes emits as text on a prose turn, keeping the
     *  parameter payload (the intended answer). Text without the markup passes through untouched. */
    static String unwrapToolMarkup(String s) {
        if (!s.contains("<tool_call>") && !s.contains("<function=")) return s;
        Matcher m = Pattern
                .compile("(?s)<parameter=[^>]+>\\s*(.*?)\\s*(?:</parameter>|</function>|</tool_call>|$)")
                .matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(m.group(1));
        }
        return sb.length() > 0 ? sb.toString()
                : s.replaceAll("(?s)</?tool_call>|</?function[^>]*>|</?parameter[^>]*>", "").strip();
    }

    /** Does the question demand a structured artifact IN the answer (table / fenced block / CSV / JSON)? */
    private static boolean wantsStructured(String goal) {
        String g = goal.toLowerCase();
        return g.contains("```") || g.contains("markdown table") || g.contains("table format")
                || g.contains("in a table") || g.contains("as a table") || g.contains("csv format")
                || g.contains("json format");
    }

    /** Does the answer literally contain one — a fenced block, or a markdown table row (2+ pipes)? */
    private static boolean hasStructured(String answer) {
        if (answer.contains("```")) return true;
        for (String line : answer.split("\n")) {
            int pipes = 0;
            for (int i = 0; i < line.length(); i++) if (line.charAt(i) == '|') pipes++;
            if (pipes >= 2) return true;
        }
        return false;
    }

    /** The tool array reduced to task_done alone (deadline turn) — keeps tool_choice="required" valid. */
    private ArrayNode onlyTaskDone(ArrayNode all) {
        ArrayNode one = all.arrayNode();
        for (JsonNode t : all)
            if (TaskDoneTool.NAME.equals(t.path("function").path("name").asText())) one.add(t);
        return one.isEmpty() ? all : one;
    }

    /** 90 / 70 / 0 — which turn-budget notice (if any) this turn crosses. */
    private int budgetMark(int turn) {
        if (turn >= Math.max(2, (int) Math.ceil(maxTurns * 0.9))) return 90;
        if (turn >= Math.max(2, (int) Math.ceil(maxTurns * 0.7))) return 70;
        return 0;
    }
    // GAP-REFLECTION LOOP (local-deep-researcher's core structure, the field's #1 lever for a SMALL model doing
    // research: query → search → summarize → REFLECT ON WHAT IS STILL UNKNOWN → query THAT gap → repeat).
    // Without it a 9B does one search, reads the first readable page, and writes an answer to the part it
    // happened to land on — "browsing tools alone" (BrowseComp: 1.9%) rather than research. The harness supplies
    // the PERSISTENCE the model lacks: after every couple of sources it asks what the question still has no
    // sourced answer for, and once at task_done it asks the same before accepting the finish. Informational
    // (a user message), sparse (2 sources apart, capped), and SHORT — a long prompt here re-creates the
    // context-starvation failure that made research never conclude. Off for the A/B arm: CODEZAIKU_GAPREFLECT=off.
    private boolean gapReflect = false;
    private int gapChecks = 0;
    private static final int MAX_GAP_CHECKS = 3;
    private int sourcesRead = 0;        // successful web_fetch calls this run
    private int sourcesSinceGap = 0;
    private boolean finalGapChecked = false;
    private int selfVerifyCount = 0;
    private static final int MAX_SELFVERIFY = 3;
    private boolean editedSinceSelfVerify = false;
    // ESCALATION (research-backed #2 lever — route the hard link to a stronger model when retry-self isn't
    // converging): when self-verify is exhausted but the builder is STILL repairing, hand the remaining weak
    // link to the 30B localizer ONCE. Off with the localizer (CODEZAIKU_DISTILLER_URL unset).
    private boolean selfVerifyEscalated = false;
    private boolean stallEscalated = false; // one 30B next-step nudge when a weak driver spins without producing output
    private int lastDevNudgeTurn = -100;     // repeated "produce dev_predictions" nudge for a dev-gated service deliverable
    private int devNudges = 0;
    // STUCK-ON-BROKEN-DELIVERABLE detector (AIDE depth-cap + LAMBDA fresh-inspector + AutoMind reset-to-known-good):
    // the no-progress-FAILURE detector only catches exit!=0 loops; a weak driver also spins on a script that EXITS 0
    // but writes BROKEN output (e.g. 80 rows of parse-errors) — the deliverable exists, so nothing fires, and it
    // edits the broken file for ~60 turns. Track the deliverable's broken-row ratio; when stuck, RE-escalate the
    // localize step to the 30B with fresh context (repeatable, bounded) and restore the best-seen version if the
    // current one regressed. General + additive; a process mechanism, not an output-content interceptor.
    private int stuckTurns = 0;
    private int stuckEscalations = 0;
    private double lastBrokenRatio = 1.0;
    private double bestBrokenRatio = 1.0;
    private String bestCheckpoint = null;
    private String bestGreenCheckpoint = null; // keep-best-GREEN: last on-disk state where the harness own tests passed
    /** Identifies THIS run's checkpoint directory. Round numbers are deterministic (1000+turn,
     *  9000+turn, selfVerifyCount), so before this existed a second run against the same project
     *  found round-N already on disk, skipped the snapshot, and handed back the PREVIOUS run's
     *  copy — which restore-on-regression and keep-best-green then wrote over the working tree.
     *  Timestamp first so the directory names sort chronologically for pruning. */
    private final String runId = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            + "-" + Integer.toHexString(System.identityHashCode(this));
    private int bestGreenTurn = -1;
    private int restores = 0;                 // restore-on-regression, decoupled from the bounded 30B re-localization
    private int lastRestoreTurn = -100;
    private static final int MAX_RESTORES = 6;
    private static final int STUCK_TURNS = 25;              // turns of no broken-ratio improvement → re-escalate
    private static final int MAX_STUCK_ESCALATIONS = 5;     // tries for the weak model to APPLY the fix (sv9 ran out at 3)
    private String lastShellObs = ""; // most recent shell output = the builder's own end-to-end run, for the localizer
    // AIDE-style OBJECTIVE val-metric check: set true once any run prints a metric computed on a validation/holdout
    // set (NOT training loss). The model rationalizes past the "measure it" instruction ("demonstrates improvement");
    // the harness requires the actual measurement to have happened for a beat-the-baseline task before accepting done.
    private boolean validationMetricSeen = false;
    // HARNESS-COMPUTED dev metric (AIDE/ML-Master: the harness is the arbiter on a LABELED dev slice, not the
    // agent's self-report or a fragile fabrication-shape detector). GATED on CODEZAIKU_DEV_ANSWERS (a harness-only
    // path of {id,fields} dev labels the agent never sees) — unset for EVERY other fixture/run → the check is a
    // no-op → zero regression risk. When set: score the agent's dev_predictions vs the hidden dev answers
    // (generic per-field exact-match); a fabricated/placeholder pipeline scores ~0 here and is caught, by
    // construction, no matter HOW it faked. Baseline via CODEZAIKU_DEV_BASELINE (default 0.5).
    private final String devAnswersPath = Config.get("CODEZAIKU_DEV_ANSWERS");
    private final double devBaseline = parseEnvDouble("CODEZAIKU_DEV_BASELINE", 0.5);
    private int devBounces = 0;
    private static final int MAX_DEV_BOUNCES = 5; // own budget so the dev gate isn't starved by the soft self-verify rounds
    private int probeBounces = 0;
    private static final int MAX_PROBE_BOUNCES = 3; // artifact-probe gate (dev-gated runs only), bounded like the others
    private String lastProbedStamp = "";      // artifact identity (path|mtime|size) at the last mid-run probe
    private int midrunProbes = 0;
    private double bestScore = -1;            // best HARNESS-SCORED artifact quality this run (keep-best)
    private String bestArtifactRel = null;    // artifact dir's path relative to projectRoot at the best snapshot
    private String lastScoredStamp = "";      // artifact stamp at the last artifact-score run (score only on change)
    private int artifactScores = 0;
    private static final int MAX_ARTIFACT_SCORES = 12;  // bound the GPU/CPU inference cost of scoring
    private static final int MAX_MIDRUN_PROBES = 4; // total mid-run probe RUNS (cost bound; CPU-only, ~1min each)
    private int blockedBounces = 0;
    private static final int MAX_BLOCKED_BOUNCES = 2; // challenge premature task_blocked (dev-gated), then accept
    private static final int MAX_DRIVE_BOUNCES = 4;
    private static final int PLAN_MIN_STEPS = 2;
    private static final int PLAN_MAX_STEPS = 8;

    // ── SPEC COVERAGE (smallcode-contract / SDD-checklist, minus the gate) ────────────────────────
    // The model's self-written plan UNDER-COVERS the spec (measured: the web-dashboard section quietly
    // falls off the plan, gets built as a husk, or a simpler design is silently substituted — keyword
    // map where the spec demands TF-IDF). Fix = durable VISIBILITY of every requirement, re-pinned
    // every turn. Derivation ladder, each layer degrading gracefully (no format dependency, never
    // aborts): (1) mechanical structure when the goal visibly has it — markdown headers, else numbered
    // items, else bullets (structure-when-present is ground truth, zero extraction error); (2) model
    // extraction at the forced-prose planning turn otherwise (works for ANY format — extraction is far
    // easier than planning); (3) nothing — the run proceeds exactly as before. Pure context, no gate,
    // no tracking machinery: the model simply SEES the full list every turn instead of only its plan.
    private List<String> specReqs = List.of();
    private static final int SPEC_REQ_MAX = 12;

    /** Layer 1: mechanical extraction from visible structure. Empty when the goal has none. */
    static List<String> extractStructuredReqs(String goal) {
        if (goal == null || goal.isBlank()) return List.of();
        String[] lines = goal.split("\n");
        // A leading header is the document TITLE, not a requirement (general doc convention).
        String firstNonBlank = null;
        for (String l : lines) { if (!l.isBlank()) { firstNonBlank = l.strip(); break; } }
        List<String> heads = new ArrayList<>();
        for (String l : lines) {
            Matcher m = Pattern.compile("^##+\\s+(.+)$").matcher(l.strip());
            if (m.find() && !l.strip().equals(firstNonBlank)) heads.add(clip(m.group(1)));
        }
        if (heads.size() >= 2) return cap(heads);
        List<String> nums = new ArrayList<>();
        for (String l : lines) {
            Matcher m = Pattern.compile("^(\\d{1,2})[.)]\\s+(.+)$").matcher(l.strip());
            if (m.find()) nums.add(clip(m.group(2)));
        }
        if (nums.size() >= 2) return cap(nums);
        List<String> bullets = new ArrayList<>();
        for (String l : lines) {
            Matcher m = Pattern.compile("^[-*•]\\s+(.+)$").matcher(l.strip());
            if (m.find()) bullets.add(clip(m.group(1)));
        }
        return bullets.size() >= 2 ? cap(bullets) : List.of();
    }

    /** Layer 2: parse a REQUIREMENTS: list the model emitted on the planning turn. Empty if absent. */
    static List<String> parseReqsFromResponse(String text) {
        if (text == null || text.isBlank()) return List.of();
        Matcher h = Pattern.compile(
                "(?i)(?:^|\\n)requirements:?\\s*\\n([\\s\\S]+?)(?=\\n\\s*\\n[A-Z]|$)").matcher(text);
        if (!h.find()) return List.of();
        List<String> out = new ArrayList<>();
        for (String l : h.group(1).split("\n")) {
            Matcher m = Pattern
                    .compile("^(?:\\d{1,2}[.)]|[-*•])\\s+(.+)$").matcher(l.strip());
            if (m.find()) out.add(clip(m.group(1)));
        }
        return out.size() >= 2 ? cap(out) : List.of();
    }

    private static String clip(String s) {
        s = s.strip().replace("**", "");
        return s.length() > 110 ? s.substring(0, 110) + "…" : s;
    }

    private static List<String> cap(List<String> l) {
        return l.size() <= SPEC_REQ_MAX ? l : new ArrayList<>(l.subList(0, SPEC_REQ_MAX));
    }

    /** The pinned coverage block — durable visibility of every spec requirement. "" when none derived. */
    private String specCoverage() {
        if (specReqs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\nSPEC COVERAGE — the goal demands ALL of these; each must be "
                + "genuinely implemented (working, wired-in code — not stubs, placeholders, or a simpler "
                + "substitute design) before task_done:");
        for (String r : specReqs) sb.append("\n - ").append(r);
        return sb.append('\n').toString();
    }

    /** One-time instruction asking the model to emit a numbered plan up front (multi-concern tasks only). */
    private String planRequestInstruction() {
        // Layer 2 of spec-coverage derivation: when the goal had no visible structure to extract
        // mechanically, have the model ENUMERATE the requirements in the same forced-prose turn
        // (extraction is far easier than planning — it just lists what the text says).
        String reqAsk = specReqs.isEmpty()
                ? "First emit REQUIREMENTS: a numbered list of every distinct thing the goal demands "
                + "(features, qualities, tests, delivery artifacts — in the goal's own words). Then "
                : "Then ";
        return "\n\nThis is a multi-step task. In your FIRST message, " + reqAsk
                + "emit a numbered plan of vertical slices (each a real, runnable increment), in this "
                + "format:\n\nPLAN:\n1. <step>\n2. <step>\n3. <step>\n\nKeep it to " + PLAN_MAX_STEPS
                + " steps or fewer. Then IMMEDIATELY start executing step 1 with a tool call — do NOT stop "
                + "after writing the plan; the plan is just a header for your work. Do ALL the steps before "
                + "calling task_done. When you call task_done the HARNESS runs your tests itself — if any fail "
                + "it sends you back to fix them — and it will not accept done on tests that pass on broken code, "
                + "so make each concern genuinely real (working code + tests that would FAIL if the code were wrong).";
    }

    /** Lenient extraction of a numbered/bulleted plan from the model's first response. Null if none. */
    private static List<String> parsePlan(String text) {
        if (text == null || text.isBlank()) return null;
        String clean = text.replaceAll("```[\\w]*\\n?|\\n?```", "").replace("**", "");
        String body = clean;
        Matcher h = Pattern.compile(
                "(?:^|\\n)(?:plan|steps?|approach):?\\s*\\n([\\s\\S]+?)(?=\\n\\n[A-Z]|$)",
                Pattern.CASE_INSENSITIVE).matcher(clean);
        if (h.find()) body = h.group(1);
        List<String> lines = new ArrayList<>();
        for (String l : body.split("\\n")) { String t = l.strip(); if (!t.isEmpty()) lines.add(t); }
        Pattern num = Pattern.compile("^(\\d{1,2})[.\\)\\-:]\\s+(.+)$");
        List<String> numbered = new ArrayList<>();
        for (String line : lines) {
            Matcher m = num.matcher(line);
            if (m.find()) numbered.add(m.group(2).strip());
        }
        if (numbered.size() >= PLAN_MIN_STEPS) return trimPlan(numbered);
        Pattern bul = Pattern.compile("^[-*•]\\s+(.+)$");
        List<String> bullet = new ArrayList<>();
        for (String line : lines) {
            Matcher m = bul.matcher(line);
            if (m.find()) bullet.add(m.group(1).strip());
        }
        return bullet.size() >= PLAN_MIN_STEPS ? trimPlan(bullet) : null;
    }

    private static List<String> trimPlan(List<String> steps) {
        List<String> out = new ArrayList<>();
        for (String s : steps) {
            out.add(s.length() > 200 ? s.substring(0, 200) + "…" : s);
            if (out.size() >= PLAN_MAX_STEPS) break;
        }
        return out;
    }

    private static final Pattern STEP_DONE = Pattern.compile(
            "step\\s+(\\d{1,2})\\s+(?:is\\s+)?(?:done|complete|finished)", Pattern.CASE_INSENSITIVE);

    /** Parse the plan from the first multi-concern response (once), then softly mark steps the model says it
     *  finished. No gate, no build check — just orientation. */
    private void observePlan(String content) {
        if (content == null || content.isBlank()) return;
        if (multiConcern && !planResolved) {
            // Layer 2 spec-coverage: pick up the REQUIREMENTS list from the same first response.
            if (specReqs.isEmpty()) {
                List<String> r = parseReqsFromResponse(content);
                if (!r.isEmpty()) { specReqs = r; log.info("  ↳ parsed spec requirements: {} items", r.size()); }
            }
            List<String> p = parsePlan(content);
            if (p != null) { planSteps = p; log.info("  ↳ parsed plan: {} steps", p.size()); }
            planResolved = true; // one shot — parsed or not, never abort, never retry
        }
        if (!planSteps.isEmpty()) {
            Matcher m = STEP_DONE.matcher(content);
            while (m.find()) {
                int idx = Integer.parseInt(m.group(1)) - 1;
                if (idx >= 0 && idx < planSteps.size()) completedSteps.add(idx); // soft display marking only
            }
        }
    }

    /** Type/scope-check a just-edited file via the language server; "" if unavailable/clean. */
    private String lspCheck(String rel) {
        if (lsp == null || rel == null || rel.isBlank()) return "";
        try {
            Path f = projectRoot.resolve(rel);
            if (!Files.isRegularFile(f)) return "";
            return lsp.check(f, Files.readString(f));
        } catch (Exception e) {
            return "";
        }
    }

    // The LSP-after-edit hook (lspCheck above) fires on write_file/edit_file, but a SHELL mutation of a
    // source file bypasses it: battery40 py-n1 was CLEAN (pyright 0 errors), then the model `sed -i`'d a
    // still-used import out of email_source.py → `NameError: Optional is not defined` that pyright never
    // re-saw, so the app never booted across 448 turns. These two helpers close that hole.
    private static final Pattern SHELL_MUTATION = Pattern.compile(
            "\\bsed\\s+-i|\\bperl\\s+-i|\\bautoflake\\b|\\bisort\\b|\\bruff\\b[^|;&]*--fix|\\bblack\\b\\s+[\\w./-]|"
            + ">>?\\s*[\\w./-]+\\.(?:py|js|ts|jsx|tsx|java|go|rs)|\\btee\\b\\s+[\\w./-]+\\.(?:py|js|ts|java|go|rs)");
    private static final Pattern SHELL_CD = Pattern.compile("\\bcd\\s+([\\w./-]+)\\s*&&");
    private static final Pattern SOURCE_FILE_TOKEN = Pattern.compile(
            "[\\w][\\w./-]*\\.(?:py|js|ts|jsx|tsx|java|go|rs)\\b");
    private static final Pattern PY_TRACE_FILE = Pattern.compile(
            "File \"([^\"]+\\.py)\", line \\d+");
    private static final Pattern IMPORT_NAME_ERR = Pattern.compile(
            "ModuleNotFoundError|ImportError|NameError|name '[^']+' is not defined|cannot import name");

    /** Resolve a path token (from a shell command or a traceback) to a project-relative file that EXISTS.
     *  Tries the cd-base, then the raw token, then suffix-matches files the model actually wrote. */
    private String resolveTouchedFile(String token, String cdBase) {
        if (token == null || token.isBlank()) return null;
        List<String> cands = new ArrayList<>();
        if (cdBase != null && !cdBase.isEmpty()) cands.add(cdBase + "/" + token);
        cands.add(token);
        for (String c : cands) {
            try { if (Files.isRegularFile(projectRoot.resolve(c))) return c; } catch (Exception ignored) { }
        }
        String tail = token.contains("/") ? token.substring(token.lastIndexOf('/') + 1) : token;
        for (String fm : filesModified) {
            if (fm.equals(token) || fm.endsWith("/" + token) || token.endsWith(fm) || fm.endsWith("/" + tail)) {
                try { if (Files.isRegularFile(projectRoot.resolve(fm))) return fm; } catch (Exception ignored) { }
            }
        }
        return null;
    }

    /** #1: re-run the LSP on source files a SHELL command rewrote in place (sed -i / redirect / autoflake / …)
     *  — the edit-tool hook never sees these. Returns the precise diagnostics for any now-broken file, or "". */
    private String lspAfterShell(String command) {
        if (lsp == null || command == null || command.isBlank() || !SHELL_MUTATION.matcher(command).find()) return "";
        String base = "";
        Matcher cd = SHELL_CD.matcher(command);
        if (cd.find()) base = cd.group(1);
        StringBuilder out = new StringBuilder();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Matcher m = SOURCE_FILE_TOKEN.matcher(command);
        while (m.find() && seen.size() < 4) {
            String rel = resolveTouchedFile(m.group(), base);
            if (rel == null || !seen.add(rel)) continue;
            String diag = lspCheck(rel);
            if (!diag.isBlank()) {
                out.append("LSP re-check after your shell edit of `").append(rel).append("` (shell edits are NOT "
                        + "auto-checked — your change broke it):\n").append(diag).append("\n\n");
            }
        }
        return out.toString().strip();
    }

    /** #2: when a run's output carries a Python import/undefined-name traceback, re-run the LSP on the
     *  offending file so the model gets the EXACT "X is not defined at file:line" (e.g. add the missing
     *  import) instead of skimming a raw traceback. Returns the diagnostic note, or "". */
    private String importErrorLsp(String observation) {
        if (lsp == null || observation == null || !IMPORT_NAME_ERR.matcher(observation).find()) return "";
        String file = null;
        Matcher m = PY_TRACE_FILE.matcher(observation);
        while (m.find()) {                                   // the LAST app frame is where it fired
            String p = m.group(1);
            if (!p.contains("/site-packages/") && !p.contains("/.venv/") && !p.contains("/dist-packages/")) file = p;
        }
        if (file == null) return "";
        String rel = resolveTouchedFile(file, "");
        if (rel == null) return "";
        String diag = lspCheck(rel);
        return diag.isBlank() ? "" : ("the run failed on an import/undefined-name error — the LSP pinpoints it "
                + "in `" + rel + "` (fix exactly this; e.g. add the missing import):\n" + diag);
    }

    /** The recently-edited SOURCE files (path + content), newest first, as context for the stall-localizer.
     *  Capped count; non-source/binary/oversized files skipped. */
    private List<Localizer.FileCtx> recentSourceFiles(int max) {
        List<Localizer.FileCtx> out = new ArrayList<>();
        List<String> mods = new ArrayList<>(filesModified);
        Collections.reverse(mods);   // filesModified is a LinkedHashSet (insertion order) → newest last
        for (String rel : mods) {
            if (out.size() >= max) break;
            if (!rel.matches(".*\\.(py|js|ts|jsx|tsx|java|go|rs|gd)$")) continue;
            try {
                Path p = projectRoot.resolve(rel);
                if (Files.isRegularFile(p) && Files.size(p) < 60_000) {
                    out.add(new Localizer.FileCtx(rel, Files.readString(p)));
                }
            } catch (Exception ignored) {
                // unreadable / vanished → skip
            }
        }
        return out;
    }

    /** A few SAMPLE lines of the project's TRAINING/INPUT data files, as extra localizer context. The classic SFT
     *  bug — inference prompts the tuned model with a DIFFERENT system prompt than the training data used, so it goes
     *  off-distribution and emits garbage — is INVISIBLE from the code alone (confirmed: 3 re-localizations couldn't
     *  fix it because the localizer only saw the .py files, never train.jsonl's actual system message to compare).
     *  The data is the ground truth for the format. General for any data/format-sensitive task. */
    private List<Localizer.FileCtx> dataSamples(int maxFiles, int linesPerFile) {
        List<Localizer.FileCtx> out = new ArrayList<>();
        try (Stream<Path> w = Files.walk(projectRoot, 6)) {
            List<Path> files = w.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return (n.endsWith(".jsonl") || n.endsWith(".csv"))
                                && n.matches(".*(train|input|corpus|sft|example|question).*");  // input data (incl dev_questions), not outputs
                    })
                    .filter(p -> !projectRoot.relativize(p).toString().contains(".cp-checkpoints"))
                    // train* first — it carries the system+assistant turns (the format inference must replay)
                    .sorted((a, b) -> Boolean.compare(
                            !b.getFileName().toString().toLowerCase().startsWith("train"),
                            !a.getFileName().toString().toLowerCase().startsWith("train")))
                    .limit(maxFiles).collect(Collectors.toList());
            for (Path p : files) {
                try (Stream<String> s = Files.lines(p)) {
                    String sample = s.filter(l -> !l.isBlank()).limit(linesPerFile)
                            .map(l -> l.length() > 700 ? l.substring(0, 700) + "…" : l)
                            .collect(Collectors.joining("\n"));
                    if (!sample.isBlank())
                        out.add(new Localizer.FileCtx(projectRoot.relativize(p)
                                + "  (TRAINING/INPUT DATA SAMPLE — your inference must replay THIS exact format and "
                                + "system prompt; an off-format prompt makes a tuned model emit unparseable output)",
                                sample));
                } catch (Exception ignore) { /* skip */ }
            }
        } catch (Exception ignore) { /* unreadable */ }
        return out;
    }

    /** Localizer file context = recently-edited SOURCE files + a sample of the TRAINING/INPUT data, so the 30B can
     *  compare the code's format/prompt against the data the model was actually trained on (code alone hides it). */
    private List<Localizer.FileCtx> localizerFiles(int maxSource) {
        List<Localizer.FileCtx> out = new ArrayList<>(recentSourceFiles(maxSource));
        out.addAll(dataSamples(2, 2));
        return out;
    }

    /** LEVER-3 CHECKPOINT (research: a State-Manager that persists validated state so a downstream edit can't
     *  silently regress it): snapshot the project's SOURCE files to a sibling dir OUTSIDE the project tree (so
     *  the copies can never confuse boot/grader app-root detection), so a thrashing self-verify edit can be
     *  diffed/restored against the pass that was working. Code/manifest files only, best-effort; returns the
     *  absolute snapshot path or null. */
    /** How many past runs' checkpoints to keep. These are whole source trees per round, so they add
     *  up fast — one project's directory reached 24MB before anything pruned it. */
    private static final int KEEP_RUNS = 3;

    /** This run's checkpoint directory: {@code <project>.cp-checkpoints/run-<id>}, a SIBLING of the
     *  project so the copies can never confuse boot or app-root detection. */
    static Path checkpointRoot(Path projectRoot, String runId) {
        return projectRoot.resolveSibling(projectRoot.getFileName() + ".cp-checkpoints")
                .resolve("run-" + runId);
    }

    /** Best-effort: keep the newest {@code keep} run directories, never touching {@code current}.
     *  Only {@code run-*} is considered — a directory that is not ours is not ours to delete. */
    static void pruneOldRuns(Path allRuns, Path current, int keep) {
        if (allRuns == null) return;
        try (Stream<Path> s = Files.list(allRuns)) {
            List<Path> runs = s.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("run-"))
                    .filter(p -> !p.equals(current))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
            for (int i = keep - 1; i < runs.size(); i++) deleteTree(runs.get(i));
        } catch (Exception ignore) { /* pruning must never fail a run */ }
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> w = Files.walk(dir)) {
            w.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignore) { /* skip one entry */ }
            });
        } catch (Exception ignore) { /* best effort */ }
    }

    private String snapshotCheckpoint(int round) {
        try {
            Path runRoot = checkpointRoot(projectRoot, runId);
            Path cp = runRoot.resolve("round-" + round);
            // Reuse is correct WITHIN a run (the same round can be snapshotted twice) and was the
            // bug ACROSS runs; the per-run root is what makes the distinction.
            if (Files.exists(cp)) return cp.toAbsolutePath().toString();
            Files.createDirectories(cp);
            pruneOldRuns(runRoot.getParent(), runRoot, KEEP_RUNS);
            try (Stream<Path> w = Files.walk(projectRoot)) {
                w.filter(Files::isRegularFile).forEach(p -> {
                    String rel = projectRoot.relativize(p).toString();
                    if (rel.contains("build/") || rel.contains("node_modules/") || rel.contains(".venv/")
                            || rel.contains("__pycache__/") || rel.contains(".git/") || rel.contains(".cp-checkpoints"))
                        return;
                    String n = p.getFileName().toString();
                    boolean code = n.matches(".*\\.(py|java|js|ts|jsx|tsx|rs|go|gd|kt|rb|c|cpp|h)$")
                            || n.equals("build.gradle") || n.equals("settings.gradle") || n.endsWith(".toml")
                            || n.equals("requirements.txt") || n.equals("package.json");
                    if (!code) return;
                    try {
                        Path d = cp.resolve(rel);
                        Files.createDirectories(d.getParent());
                        Files.copy(p, d, StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception ignore) { /* skip one file */ }
                });
            }
            return cp.toAbsolutePath().toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** The harness-computed dev accuracy (0..1) on the hidden labels, or -1 if not a dev-gated run / no dev
     *  predictions yet. The ROBUST, mode-agnostic quality signal — null fields, prompt-echo, and collapse all score
     *  ~0 — so the in-loop stuck signal is the REAL metric, not a marker heuristic that has to chase each broken mode. */
    private double devAccuracyValue() {
        if (devAnswersPath == null || devAnswersPath.isBlank()) return -1;
        try {
            Path ans = Path.of(devAnswersPath);
            if (!Files.exists(ans)) return -1;
            Path pred = findFileByName("dev_predictions.jsonl");
            if (pred == null) return -1;
            Map<String, JsonNode> A = indexById(ans), P = indexById(pred);
            if (A.isEmpty()) return -1;
            // RAG-shaped dev (questions → gold docs + reference answer): score citation-hit + answer token-F1
            // instead of per-field exact match. Detected from the dev-answer rows, so one dev-gate serves both
            // fixture shapes (extraction {id,fields} AND retrieval {id,gold_docs,answers}).
            // Retrieval-shaped dev (embedding fine-tune: dev queries → RANKED retrieved doc ids vs hidden gold
            // docs). Distinguished from RAG-QA by having gold_docs but NO answer field — score is mean nDCG@10,
            // so an under-trained encoder (low recall) scores low and trips the same retrain feedback as the
            // other shapes, WITHOUT relying on the model's own flaky unit tests. Checked before RAG (RAG also
            // keys on gold_docs) so a pure-retrieval deliverable gets the ranking metric, not cite+F1.
            boolean retrieval = A.values().stream().anyMatch(n -> n != null && n.has("gold_docs"))
                    && A.values().stream().noneMatch(n -> n != null && (n.has("answer") || n.has("answers")));
            if (retrieval) return retrievalDevScore(A, P);
            boolean rag = A.values().stream().anyMatch(n -> n != null
                    && (n.has("gold_docs") || n.has("answers") || n.has("citations") || n.has("answer")));
            if (rag) return ragDevScore(A, P);
            // Preference-shaped dev (reward-model / KTO / ORPO: unlabeled a/b pairs the agent must rank with its
            // OWN trained artifact): accuracy = fraction whose predicted `preferred` side matches the hidden one.
            // Forces an end-to-end load+score of the saved artifact inside the loop — an unloadable adapter or an
            // untrained head both surface as dev ~0 and trip the same metric anti-thrash as the other shapes.
            boolean pref = A.values().stream().anyMatch(n -> n != null && n.has("preferred"));
            if (pref) return prefDevScore(A, P);
            int fields = 0, correct = 0;
            for (var e : A.entrySet()) {
                JsonNode af = fieldsNode(e.getValue());
                JsonNode pf = P.containsKey(e.getKey()) ? fieldsNode(P.get(e.getKey())) : null;
                if (af == null || !af.isObject()) continue;
                var it = af.fields();
                while (it.hasNext()) {
                    var fe = it.next(); fields++;
                    if (pf != null && pf.has(fe.getKey())
                            && pf.get(fe.getKey()).asText("").trim().equals(fe.getValue().asText("").trim())) correct++;
                }
            }
            return fields == 0 ? -1 : (double) correct / fields;
        } catch (Exception e) {
            return -1;
        }
    }

    /** RAG dev score (0..1) = mean over questions of (citation-hit + answer token-F1)/2. citation-hit = any gold
     *  doc id appears in the prediction's citations; token-F1 = SQuAD-style overlap of the predicted answer vs the
     *  reference answer(s). Lets the dev-gate + anti-thrash catch a PLAUSIBLE-BUT-WRONG retrieval (docs-rag's
     *  failure: reads correct but returns the wrong docs) the same way the field-exact path catches a bad extractor. */
    private double ragDevScore(Map<String, JsonNode> A,
                               Map<String, JsonNode> P) {
        double total = 0; int n = 0;
        for (var e : A.entrySet()) {
            JsonNode a = e.getValue(), p = P.get(e.getKey());
            Set<String> gold = idSet(a.get("gold_docs"));
            if (gold.isEmpty()) gold = idSet(a.get("citations"));
            Set<String> cited = p == null ? Set.of() : idSet(p.get("citations"));
            double cite = !gold.isEmpty() && gold.stream().anyMatch(cited::contains) ? 1.0 : 0.0;
            List<String> refs = new ArrayList<>();
            JsonNode ra = a.has("answers") ? a.get("answers") : a.get("answer");
            if (ra != null && ra.isArray()) ra.forEach(x -> refs.add(x.asText("")));
            else if (ra != null) refs.add(ra.asText(""));
            String predAns = p != null && p.has("answer") ? p.get("answer").asText("") : "";
            double f1 = 0;
            for (String r : refs) f1 = Math.max(f1, tokenF1(predAns, r));
            total += (cite + f1) / 2.0; n++;
        }
        return n == 0 ? -1 : total / n;
    }

    /** Retrieval dev score (0..1) = mean nDCG@10 over dev queries. The prediction row supplies a RANKED list of
     *  retrieved doc ids ("retrieved" or "citations"); the hidden answer row supplies the gold ids ("gold_docs").
     *  A well-tuned encoder ranks the gold docs high (nDCG→1); an under-trained one (base distilbert ≈0.13) ranks
     *  them low — so the dev-gate + anti-thrash arbitrate an embedding/retrieval deliverable on hidden labels the
     *  same way the field-exact path arbitrates an extractor, with NO dependence on the model's own unit tests. */
    private double retrievalDevScore(Map<String, JsonNode> A,
                                     Map<String, JsonNode> P) {
        double total = 0; int n = 0;
        for (var e : A.entrySet()) {
            JsonNode a = e.getValue(), p = P.get(e.getKey());
            Set<String> gold = idSet(a.get("gold_docs"));
            if (gold.isEmpty()) continue;
            JsonNode rn = p == null ? null
                    : (p.has("retrieved") ? p.get("retrieved") : p.get("citations"));
            List<String> ranked = new ArrayList<>();
            if (rn != null && rn.isArray()) rn.forEach(x -> ranked.add(x.asText("").trim()));
            double dcg = 0;
            for (int r = 0; r < Math.min(10, ranked.size()); r++)
                if (gold.contains(ranked.get(r))) dcg += 1.0 / (Math.log(r + 2) / Math.log(2));
            double idcg = 0;
            for (int r = 0; r < Math.min(gold.size(), 10); r++) idcg += 1.0 / (Math.log(r + 2) / Math.log(2));
            total += idcg > 0 ? dcg / idcg : 0; n++;
        }
        return n == 0 ? -1 : total / n;
    }

    /** Runs the fixture's label-free artifact probe (artifact_probe.py sitting NEXT TO the dev answers file), if
     *  any: it loads the saved artifact exactly as the verifier will and exits non-zero if that fails. Returns null
     *  when the probe passes / doesn't exist / can't run (never blocks a finish on harness trouble); otherwise the
     *  tail of the probe's output — the REAL load error, fed back to the model. */
    private String runArtifactProbe(boolean cpuOnly) {
        try {
            Path probe = Path.of(devAnswersPath).getParent().resolve("artifact_probe.py");
            if (!Files.exists(probe)) return null;
            ProcessBuilder pb = Shell.pb("timeout 240 python3 " + probe.toAbsolutePath() + " " + projectRoot.toAbsolutePath());
            if (cpuOnly) pb.environment().put("CUDA_VISIBLE_DEVICES", ""); // mid-run: never contend with training
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean done = p.waitFor(250, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return null; }
            if (p.exitValue() == 0) return null;
            String[] lines = out.strip().split("\n");
            int from = Math.max(0, lines.length - 12);
            return String.join("\n", Arrays.copyOfRange(lines, from, lines.length));
        } catch (Exception e) {
            return null; // harness trouble never blocks a finish
        }
    }

    /** Identity stamp (path|mtime|size) of the first artifact (adapter_config.json + adapter_model.safetensors)
     *  under output/, or null if none yet — cheap change-detection for the mid-run probe. */
    private String artifactStamp() {
        Path dir = artifactDir();
        if (dir == null) return null;
        try {
            Path st = dir.resolve("adapter_model.safetensors");
            return st + "|" + Files.getLastModifiedTime(st).toMillis() + "|" + Files.size(st);
        } catch (Exception e) {
            return null;
        }
    }

    /** The directory holding the agent's saved LoRA adapter (adapter_config.json + adapter_model.safetensors),
     *  excluding training-checkpoint subdirs and our own best-snapshot sidecar. Null if none saved yet. */
    private Path artifactDir() {
        try (Stream<Path> s = Files.walk(projectRoot.resolve("output"), 6)) {
            return s.filter(p -> p.getFileName().toString().equals("adapter_config.json"))
                    .map(Path::getParent)
                    .filter(p -> Files.exists(p.resolve("adapter_model.safetensors")))
                    .filter(p -> !p.toString().contains("checkpoint-") && !p.toString().contains(".cp-best-artifact"))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** ARTIFACT KEEP-BEST (dev-gated runs, fixture ships grader/artifact_score.py): a thrashing agent that "improves"
     *  its model into a WORSE one ships the LATEST adapter, not its BEST (distillation sv1: 0.89→retrain 33×→0.50).
     *  keep-best snapshots the best-scoring adapter and restores it at run end. CRITICAL: the score comes from the
     *  HARNESS running artifact_score.py on the ACTUAL on-disk adapter (load it + run the hidden dev set) — NOT from
     *  the agent's dev_predictions, which decouple from the adapter when overwritten without regeneration (RM kbb
     *  snapshotted a "0.95" that was 0.17 on test because dev_predictions were stale-good). Scored only when the
     *  artifact stamp changes, bounded. AIDE/MLE-STAR keep-best on the model artifact, with a ground-truth score. */
    private void snapshotBestIfImproved() {
        if (devAnswersPath == null || artifactScores >= MAX_ARTIFACT_SCORES) return;
        String stamp = artifactStamp();
        if (stamp == null || stamp.equals(lastScoredStamp)) return;   // no new artifact to score
        lastScoredStamp = stamp;
        artifactScores++;
        double score = scoreArtifact();       // TRUE quality of the adapter on disk right now (hidden dev set)
        if (score < 0 || score <= bestScore + 1e-9) return;
        Path dir = artifactDir();
        if (dir == null) return;
        try {
            copyDirReplacing(dir, projectRoot.resolve(".cp-best-artifact"));
            bestScore = score;
            bestArtifactRel = projectRoot.relativize(dir).toString();
            log.info("  ↳ artifact keep-best: snapshot at artifact-score {} ({})", String.format("%.3f", score), bestArtifactRel);
        } catch (Exception ignore) { /* best-effort; never break the run */ }
    }

    /** At run end: if the CURRENT on-disk adapter scores worse than the best we snapshotted, restore the best — so the
     *  external grader sees the run's BEST adapter, not whatever the agent last overwrote. Both scores are harness-
     *  computed on the actual artifacts (artifact_score.py), so no dev_predictions staleness. */
    private void restoreBestArtifact() {
        if (bestArtifactRel == null || bestScore < 0) return;
        try {
            double cur = scoreArtifact();     // score the artifact currently on disk (may be a thrashed-worse one)
            if (cur >= bestScore - 1e-9) return;  // current is as-good-or-better; keep it
            Path snap = projectRoot.resolve(".cp-best-artifact");
            Path dest = projectRoot.resolve(bestArtifactRel);
            if (!Files.isDirectory(snap)) return;
            copyDirReplacing(snap, dest);
            log.info("  ↳ artifact keep-best: RESTORED best adapter (score {} > current {})",
                    String.format("%.3f", bestScore), String.format("%.3f", cur));
        } catch (Exception ignore) { }
    }

    /** Run the fixture's grader/artifact_score.py (next to dev_answers) on the on-disk artifact: it loads the adapter
     *  exactly as the verifier will, runs the HIDDEN dev set, and prints a single float 0..1 (the last float on stdout
     *  is taken). CPU-only to never contend with training. Returns -1 if absent/failed — keep-best then no-ops. */
    private double scoreArtifact() {
        try {
            Path scorer = Path.of(devAnswersPath).getParent().resolve("artifact_score.py");
            if (!Files.exists(scorer)) return -1;
            ProcessBuilder pb = Shell.pb("timeout 300 python3 " + scorer.toAbsolutePath() + " " + projectRoot.toAbsolutePath());
            pb.environment().put("CUDA_VISIBLE_DEVICES", "");   // CPU: a 0.5B on ~20 dev rows is seconds, no GPU contention
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(320, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1; }
            if (p.exitValue() != 0) return -1;
            double last = -1;
            for (String tok : out.split("\\s+")) {
                try { last = Double.parseDouble(tok.trim()); } catch (Exception ignore) {}
            }
            return last;
        } catch (Exception e) {
            return -1;
        }
    }

    private void copyDirReplacing(Path src, Path dst) throws IOException {
        if (Files.exists(dst)) {
            try (Stream<Path> w = Files.walk(dst)) {
                w.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (Exception ignore) {} });
            }
        }
        Files.createDirectories(dst);
        try (Stream<Path> w = Files.walk(src)) {
            for (Path p : (Iterable<Path>) w::iterator) {
                Path rel = src.relativize(p);
                Path target = dst.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else { Files.createDirectories(target.getParent()); Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
    }

    /** Preference dev score (0..1) = fraction of pairs whose predicted `preferred` ("a"/"b") matches the hidden
     *  label. Random/untrained ~0.5; unloadable artifact → no predictions → -1 handled by the caller as no-signal,
     *  but a crashed scorer that writes nothing keeps the deliverable un-done, so the loop keeps pushing. */
    private double prefDevScore(Map<String, JsonNode> A,
                                Map<String, JsonNode> P) {
        int n = 0, correct = 0;
        for (var e : A.entrySet()) {
            JsonNode a = e.getValue();
            if (a == null || !a.has("preferred")) continue;
            n++;
            JsonNode p = P.get(e.getKey());
            if (p != null && p.has("preferred")
                    && p.get("preferred").asText("").trim().equalsIgnoreCase(a.get("preferred").asText("").trim())) correct++;
        }
        return n == 0 ? -1 : (double) correct / n;
    }

    private static Set<String> idSet(JsonNode arr) {
        Set<String> s = new HashSet<>();
        if (arr != null && arr.isArray()) arr.forEach(x -> s.add(x.asText("").trim()));
        return s;
    }

    /** SQuAD-style token-F1 of two short answers (lowercase, drop punctuation + articles, bag-of-tokens overlap). */
    private static double tokenF1(String pred, String ref) {
        List<String> pt = normTokens(pred), gt = normTokens(ref);
        if (pt.isEmpty() || gt.isEmpty()) return (pt.isEmpty() && gt.isEmpty()) ? 1.0 : 0.0;
        Map<String, Integer> gc = new HashMap<>();
        for (String t : gt) gc.merge(t, 1, Integer::sum);
        int overlap = 0;
        for (String t : pt) { Integer c = gc.get(t); if (c != null && c > 0) { overlap++; gc.put(t, c - 1); } }
        if (overlap == 0) return 0;
        double prec = (double) overlap / pt.size(), rec = (double) overlap / gt.size();
        return 2 * prec * rec / (prec + rec);
    }

    private static List<String> normTokens(String s) {
        if (s == null) return List.of();
        String n = s.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\b(a|an|the)\\b", " ");
        List<String> out = new ArrayList<>();
        for (String t : n.split("\\s+")) if (!t.isBlank()) out.add(t);
        return out;
    }

    /** A few SAMPLE rows of the BROKEN output (dev_predictions, else the deliverable) so the localizer SEES the
     *  symptom, not just a broken-ratio number. The actual bad content is what lets the 30B diagnose the cause —
     *  e.g. a RAG answer that says "the context is random alphanumeric strings" reveals the code fed doc IDS to the
     *  LLM instead of the doc TEXT (docs-rag sv4: 3 re-localizations couldn't fix it because the 30B never saw it). */
    private String brokenOutputSample() {
        try {
            Path p = findFileByName("dev_predictions.jsonl");
            if (p == null) {
                for (String name : new String[]{"predictions.jsonl", "submission.csv", "results.jsonl", "output.jsonl"}) {
                    p = findFileByName(name);
                    if (p != null) break;
                }
            }
            if (p == null) return "(no output file found)";
            try (Stream<String> s = Files.lines(p)) {
                return s.filter(l -> !l.isBlank()).limit(3)
                        .map(l -> l.length() > 400 ? l.substring(0, 400) + "…" : l)
                        .collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** "Broken ratio" of the deliverable (0..1, higher = worse), or -1 if no deliverable yet. PREFERS the real
     *  dev metric (1 - dev-acc) when this is a dev-gated run — mode-agnostic, can't be gamed, no marker whack-a-mole;
     *  observed: each 9B run failed a DIFFERENT way (collapse → prompt-echo → all-null), which a marker list can't
     *  keep up with but the metric catches uniformly. Falls back to a content-marker scan for non-dev fixtures. */
    private double deliverableBrokenRatio() {
        double dev = devAccuracyValue();
        if (dev >= 0) return 1.0 - dev;     // robust path: the real harness-computed metric
        try {
            if (goal == null) return -1;
            Matcher m = Pattern
                    .compile("[\\w./-]*\\.(jsonl|csv)").matcher(goal);
            Path f = null;
            while (m.find()) {
                String g = m.group();
                String base = g.substring(g.lastIndexOf('/') + 1).toLowerCase();
                if (base.matches(".*(prediction|recommend|submission|assignment|result|output|forecast|alert).*")) {
                    f = findFileByName(base);
                    if (f != null) break;
                }
            }
            if (f == null) return -1;
            List<String> lines;
            try (Stream<String> s = Files.lines(f)) {
                lines = s.filter(l -> !l.isBlank()).limit(500).collect(Collectors.toList());
            }
            if (lines.isEmpty()) return 1.0;
            int broken = 0;
            for (String l : lines) {
                String lc = l.toLowerCase();
                boolean bad = lc.contains("\"error\"") || lc.contains("failed to parse") || lc.contains("no json")
                        || lc.contains("<|") || lc.contains("traceback")
                        || lc.matches(".*\"\\w+\"\\s*:\\s*\\{\\s*\\}.*")    // empty-object value, e.g. "fields":{}
                        || lc.split(":\\s*null", -1).length - 1 >= 2;       // all-null extraction (≥2 null fields)
                if (bad) broken++;
            }
            return (double) broken / lines.size();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Restore a snapshot (made by snapshotCheckpoint) back into the project — copy the saved code files over the
     *  current ones, so a thrashing run resumes from its best-working state instead of an accumulated-broken file
     *  (AutoMind session-reset-to-last-good). Best-effort; returns whether it copied anything. */
    /** Keep-best-green: at run exit, if we snapshotted a state where the harness tests passed and the CURRENT
     *  on-disk state does NOT pass, restore the green snapshot -- so a post-task_done self-verify thrash cannot ship a
     *  broken end-state over a solved one. Ground-truth = the test suite, not model self-judgment. */
    private void restoreBestGreen() {
        if (bestGreenCheckpoint == null) return;
        if (ProjectTests.testsGreen(projectRoot)) return; // current state already green
        if (restoreCheckpoint(bestGreenCheckpoint))
            log.info("  keep-best-green RESTORE at exit: current state regressed (red) -> restored green snapshot from turn {}", bestGreenTurn);
    }

    private boolean restoreCheckpoint(String cpPath) {
        if (cpPath == null) return false;
        try {
            Path cp = Path.of(cpPath);
            if (!Files.isDirectory(cp)) return false;
            boolean[] any = {false};
            try (Stream<Path> w = Files.walk(cp)) {
                w.filter(Files::isRegularFile).forEach(p -> {
                    try {
                        Path d = projectRoot.resolve(cp.relativize(p).toString());
                        Files.createDirectories(d.getParent());
                        Files.copy(p, d, StandardCopyOption.REPLACE_EXISTING);
                        any[0] = true;
                    } catch (Exception ignore) { /* skip one file */ }
                });
            }
            return any[0];
        } catch (Exception e) {
            return false;
        }
    }

    /** AutoMind-style HARNESS ARTIFACT CHECK (CodeML ref: automind/agent.py:429 submission.csv.exists()): the goal
     *  names a deliverable output file; verify it actually EXISTS and isn't a stub/sample — a real filesystem check
     *  the model can't rationalize past ("the pipeline will generate predictions when executed" → no file → not done).
     *  Returns a finding to surface in the self-verify reflection, or null if the deliverable looks produced / can't
     *  be determined. Best-effort, never throws. */
    private String deliverableArtifactFinding() {
        try {
            if (goal == null) return null;
            Matcher m = Pattern
                    .compile("[\\w./-]*\\.(jsonl|csv|tsv|parquet)").matcher(goal);
            LinkedHashSet<String> deliverables = new LinkedHashSet<>();
            while (m.find()) {
                String f = m.group();
                String base = f.substring(f.lastIndexOf('/') + 1).toLowerCase();
                // deliverable-ish names only — skip input/data files (train.csv, test_inputs.jsonl, corpus.jsonl, …)
                if (base.matches(".*(prediction|recommend|submission|assignment|result|output|forecast|alert).*"))
                    deliverables.add(base);
            }
            for (String name : deliverables) {
                Path found = findFileByName(name);
                if (found == null)
                    return "I checked the filesystem and your deliverable `" + name + "` does not exist yet — the "
                         + "pipeline has not actually produced its output. Run it end-to-end to generate the real file.";
                long lines = countLines(found);
                long expected = expectedInputCount();
                if (lines >= 0 && lines <= 2)
                    return "Your deliverable `" + name + "` exists but has only " + lines + " line(s) — that's a stub "
                         + "or a single spot-check, not the full output. Run your pipeline over ALL inputs.";
                if (expected > 0 && lines >= 0 && lines < expected * 0.8)
                    return "Your deliverable `" + name + "` has " + lines + " entries but there are about " + expected
                         + " inputs — you produced a SAMPLE, not the full set. Loop over ALL inputs and regenerate it.";
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** First regular file with this name anywhere under the project (model often nests output/X/output/X/…). */
    private Path findFileByName(String name) {
        try (Stream<Path> w = Files.walk(projectRoot)) {
            return w.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(name))
                    .filter(p -> { String s = projectRoot.relativize(p).toString();
                        return !s.contains(".cp-checkpoints") && !s.contains("/data/") && !s.startsWith("data/"); })
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private long countLines(Path p) {
        try (Stream<String> s = Files.lines(p)) {
            return s.filter(l -> !l.isBlank()).count();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Best-effort count of held-out inputs: a data file named like test_inputs/inputs/queries/holdout. */
    private long expectedInputCount() {
        try (Stream<Path> w = Files.walk(projectRoot)) {
            Path in = w.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase()
                            .matches("(test_inputs|test_input|inputs|queries|holdout)\\.(jsonl|csv|tsv)"))
                    .findFirst().orElse(null);
            return in == null ? -1 : countLines(in);
        } catch (Exception e) {
            return -1;
        }
    }

    /** OBJECTIVE val-metric detector (AIDE: the agent must PRINT a metric on held-out data). Did this run output
     *  report a metric computed on a validation / eval / holdout set — NOT training loss (the exact thing the model
     *  cited as fake "improvement")? */
    private static boolean looksLikeValidationMetric(String out) {
        if (out == null) return false;
        String s = out.toLowerCase();
        boolean ctx = s.contains("valid") || s.contains("holdout") || s.contains("held-out") || s.contains("held out")
                || s.contains("eval") || s.contains("baseline") || s.contains("vs base") || s.contains("dev set")
                || s.contains("base model");
        if (!ctx) return false;
        return Pattern.compile(
                "(accuracy|\\bacc\\b|f1|score|metric|rmse|mae|auc|precision|recall|\\br2\\b|error)[^\\n]{0,24}?[01]?\\.\\d{2,}")
                .matcher(s).find();
    }

    /** Is a validation-metric-vs-baseline expected? ONLY when the goal explicitly asks to beat a baseline / base
     *  model with a named metric — conservative, to avoid false findings on unsupervised / no-label tasks
     *  (clustering, RAG without QA labels). */
    private boolean expectsValidationMetric() {
        if (goal == null) return false;
        String g = goal.toLowerCase();
        boolean beat = g.contains("beat") || g.contains("better than") || g.contains("baseline")
                || g.contains("base model") || g.contains("outperform") || g.contains("exceed");
        boolean metric = g.contains("accuracy") || g.contains("f1") || g.contains("auc") || g.contains("rmse")
                || g.contains("mae") || g.contains(" r2") || g.contains("recall") || g.contains("precision");
        return beat && metric;
    }

    /** AIDE-style OBJECTIVE finding: a beat-the-baseline task where the harness has seen NO validation metric computed
     *  in any run. The model rationalizes past the "measure it" instruction; this states the harness-verified FACT. */
    private String validationMetricFinding() {
        if (!expectsValidationMetric() || validationMetricSeen) return null;
        return "You have not MEASURED your pipeline on labeled validation data — this task requires beating a "
             + "baseline / base model, but no validation metric has been computed in any run yet. Hold out a labeled "
             + "slice of your training data, run your FINISHED pipeline on it, compute the actual metric AND the "
             + "baseline's, and PRINT both numbers. If yours does not beat the baseline that is a real bug to fix "
             + "before finishing (training loss dropping is NOT the same as beating the baseline on held-out data).";
    }

    /** FAKE-CORE check (anti reward-hacking / specification-gaming — research: held-out is the ground truth; CodeML:
     *  RD-Agent static evidence-of-real-work, AIDE keep-best-by-metric). Scans the deliverable's CORE source for
     *  code that ADMITS it SIMULATES / STUBS the real work instead of doing it — the sv6 failure: a fine_tune_model()
     *  that returns a placeholder + a regex "extraction" produced complete, plausible output that passed the
     *  completeness + format checks but did no real ML. GENERAL (phrase-based "the code says it's faking", NOT
     *  ML-call-specific, to avoid overfit) + OBJECTIVE (the model can't rationalize past its own source). Returns a
     *  finding or null. */
    private String fakeCoreFinding() {
        try {
            // STRONG: the code explicitly admits it isn't doing the real thing (very low false-positive).
            Pattern strong = Pattern.compile(
                    "in a real implementation|for (this )?demo|demo purposes|(not|doesn'?t|does not) actually "
                  + "(train|run|use|call|fine|do|implement|generate)|real implementation would|placeholder to "
                  + "indicate|return(s)? a placeholder|would (do|use|be|call|run) the (actual|real)|in practice");
            // WEAK: stand-in markers; require >=2 distinct to fire (reduces false positives on legit uses).
            String[] weak = {"simulat", "placeholder", "mock", " stub", "dummy", "heuristic", "for now", "hardcod"};
            List<Path> srcs = new ArrayList<>();
            try (Stream<Path> w = Files.walk(projectRoot)) {
                w.filter(Files::isRegularFile).forEach(p -> {
                    String rel = projectRoot.relativize(p).toString().toLowerCase();
                    if (rel.contains("build/") || rel.contains("node_modules/") || rel.contains(".venv/")
                            || rel.contains("__pycache__/") || rel.contains(".git/") || rel.contains(".cp-checkpoints")
                            || rel.contains("/data/") || rel.startsWith("data/") || rel.contains("test")) return;
                    if (p.getFileName().toString().matches(".*\\.(py|js|ts|jsx|tsx|rs|go|java|kt|rb)$")) srcs.add(p);
                });
            }
            for (Path p : srcs) {
                String code;
                try { code = Files.readString(p).toLowerCase(); } catch (Exception e) { continue; }
                String rel = projectRoot.relativize(p).toString();
                if (strong.matcher(code).find())
                    return "Your code in `" + rel + "` appears to FAKE the real work — it says it simulates / is a "
                         + "demo / does not actually do the real computation. The deliverable must come from the REAL "
                         + "implementation (actually train / call / run the model or pipeline), not a simulated or "
                         + "placeholder stand-in. Replace the faked core with the real one and re-run.";
                int hits = 0; for (String wk : weak) if (code.contains(wk)) hits++;
                if (hits >= 2)
                    return "Your code in `" + rel + "` looks like it STUBS or simulates the real work (placeholder / "
                         + "mock / heuristic / hardcoded values in place of the real computation). Implement the real "
                         + "core — actually train / call / run the model or pipeline — not a stand-in, then re-run.";
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** HARNESS-COMPUTED dev-accuracy check (the robust anti-fake — AIDE/ML-Master "harness is the arbiter", not a
     *  fabrication-shape detector which is just whack-a-mole). GATED on CODEZAIKU_DEV_ANSWERS → no-op (returns null)
     *  for every fixture that doesn't set it, so it cannot regress anything else. When active: score the agent's
     *  `dev_predictions.jsonl` against the HIDDEN dev answers (per-field exact match); a fake/placeholder/constant
     *  pipeline scores ~0 here and is caught by construction. The agent can't fake the number (harness computes it)
     *  or echo the labels (it never sees them — they live in the fixture dir, passed only to the harness). */
    private String devAccuracyFinding() {
        if (devAnswersPath == null || devAnswersPath.isBlank()) return null; // gate: no-op for all other fixtures
        try {
            Path ans = Path.of(devAnswersPath);
            if (!Files.exists(ans)) return null;
            Path pred = findFileByName("dev_predictions.jsonl");
            if (pred == null)
                return "Run your FINISHED pipeline on the dev inputs (`data/dev_inputs.jsonl` or "
                     + "`data/dev_questions.jsonl`) and write `dev_predictions.jsonl` — a labeled held-out check the "
                     + "harness scores to confirm your pipeline produces real results.";
            double acc = devAccuracyValue();   // unified: per-field exact (extraction) OR cite+token-F1 (retrieval)
            if (acc < 0) return null;          // not scorable yet
            if (acc < devBaseline) {
                // Two bands: near/below chance = the pipeline is fake/broken (the classic anti-fake catch).
                // Above chance but under the bar = the pipeline is REAL but the training is WEAK — a draw-variance
                // miss (RM sv11/sv12: valid artifacts at dev 0.65-0.75). Telling that model its output is
                // "fabricated" misdirects it into rewriting a working pipeline; tell it to retrain STRONGER instead.
                if (acc >= 0.5)
                    return String.format("Your pipeline works but scores %.2f on the harness's labeled dev check — "
                         + "below the %.2f bar. The pipeline is producing REAL results; the model is just UNDER-TRAINED "
                         + "(a weak draw). Do NOT rewrite the pipeline. RETRAIN stronger — use the reference's exact "
                         + "hyperparameters (its learning rate and epochs as written), then re-save the artifact and "
                         + "regenerate dev_predictions.jsonl.", acc, devBaseline);
                return String.format("Your pipeline scores %.2f on the harness's labeled dev check (you need to beat "
                     + "%.2f). A score this low means it is NOT producing real results — fabricated/placeholder/"
                     + "constant output, or (for retrieval) the WRONG documents, scores ~0 here. Fix the pipeline so it "
                     + "produces real results on each input, then regenerate BOTH the deliverable and dev_predictions.jsonl.",
                     acc, devBaseline);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Index {id,...} JSONL rows by their "id" (as text). */
    private Map<String, JsonNode> indexById(Path p) {
        Map<String, JsonNode> m = new HashMap<>();
        try (Stream<String> s = Files.lines(p)) {
            s.filter(l -> !l.isBlank()).forEach(l -> {
                try { JsonNode n = j.readTree(l); if (n.has("id")) m.put(n.get("id").asText(), n); }
                catch (Exception ignore) { /* skip bad line */ }
            });
        } catch (Exception ignore) { /* unreadable */ }
        return m;
    }

    private static JsonNode fieldsNode(JsonNode n) {
        return n != null && n.has("fields") ? n.get("fields") : n;
    }

    private static double parseEnvDouble(String env, double def) {
        try { String v = System.getenv(env); return v == null ? def : Double.parseDouble(v.trim()); }
        catch (Exception e) { return def; }
    }

    /** Does this command look like a genuinely-LONG ML step (training / full-dataset generation) rather than a
     *  deadlock? Such a step is SLOW, not hung — it needs background+poll+subset+batch, NOT deadlock-hunting.
     *  (CodeML: RD-Agent debug-on-subset → full-run w/ huge timeout; AutoMind 30min/step; MLE-bench 24h/task.) */
    private static boolean looksHeavyTraining(String cmd) {
        if (cmd == null) return false;
        String c = cmd.toLowerCase();
        return c.contains("finetune") || c.contains("fine_tune") || c.contains("fine-tune") || c.contains("sft")
            || c.contains("peft") || c.contains("lora") || c.contains("trainer.train") || c.contains(".fit(")
            || c.contains("train.py") || c.contains("epoch") || c.contains("model.generate") || c.contains("generate(")
            || c.contains("from_pretrained") || c.contains("accelerate launch") || c.contains("torchrun")
            || c.contains("training") || c.contains(" train ") || c.contains("trainer");
    }

    // Deterministic file tracking for compaction (mechanical, never left to the summarizer — pi pattern).
    private final LinkedHashSet<String> filesModified = new LinkedHashSet<>();
    private final LinkedHashSet<String> filesRead = new LinkedHashSet<>();
    // The iteratively-updated structured checkpoint of compacted-away history.
    private String checkpoint = "";
    // SPIN GUARD — ONE mechanism: (1) block a command repeated >3× (normalized key); (2) REFRAME once per
    // stuck key (a salient contextual user message — change the approach, smallcode-style); (3) CAP total
    // blocks and END the run. Blocking alone does NOT move a stuck weak model (it re-emits, ignoring the
    // block — goose/opencode confirm); the cut-losses cap is the piece that actually bounds the damage.
    // callCounts resets on a successful edit (re-running a build/test after a change isn't a spin);
    // reframedKeys + totalSpinBlocks persist across the run.
    private final Map<String, Integer> callCounts = new HashMap<>();
    private static final int MAX_IDENTICAL_CALLS = 3;
    private final Set<String> reframedKeys = new HashSet<>();
    private int totalSpinBlocks = 0;
    private static final int MAX_TOTAL_SPINS = 40;
    // Error-grounding push: ONE per error, sparse (over-injection dilutes a weak model's attention).
    private final Set<String> pushedErrorQueries = new HashSet<>();
    private static final int MAX_ERROR_PUSHES = 3;
    private int vacuousPushes = 0; // salient zero-tests-executed notes pushed (cap 2/run)
    // EVIDENCE-FRESHNESS at the finish line (the no-gate counterpart to smallcode's done-guard — a gap
    // the references leave open): the model declares done citing a test run that predates its last edits
    // (measured: declared at the end-game note with a broken test compile; the green it remembered was
    // 40 turns stale). Track whether files changed since the last verification run; on task_done, state
    // that FACT once (informational — no oracle, no judgment of results); a second task_done is accepted
    // unconditionally. Bounded at one bounce by design.
    private boolean dirtySinceVerify = false; // files modified after the last build/test verification run
    private boolean staleNotePushed = false;
    // BOOT-GATE (battery25 needle-mover): on task_done the HARNESS boots the assembled app end-to-end
    // for the HTTP-service archetype (the model can't be expected to know each stack's boot recipe — it
    // only writes code + runs shell). "Doesn't boot" was the dominant, model-INVISIBLE failure (never
    // calls listen()/uvicorn.run(), unregistered startup hook, Spring context abort) — its own unit tests
    // pass while the app never starts. This CERTIFIES ON REAL EXECUTION (not a write interceptor): it runs
    // on the genuine finish attempt and only ADDS the boot error as a salient message, bounded, then lets
    // done through. Off-switch for A/B: CODEZAIKU_BOOTGATE=off.
    private int bootBounces = 0;
    private static final int MAX_BOOT_BOUNCES = 2;
    private final boolean bootGateOn = !"off".equalsIgnoreCase(Config.get("CODEZAIKU_BOOTGATE"));
    // (The in-loop MUTATION GATE was DELETED 2026-06-16: across batteries 29/30/31 the 9B hit its bounce
    // cap EVERY run — it cannot satisfy an in-loop mutation gate even with concrete feedback and 8 chances.
    // Mutation testing is a MEASUREMENT consumed by a capable agent, not an in-loop blocker; the held-out
    // grader is the measurement of record. Removed engine = MutationCheck.java; testsGreen lives on in ProjectTests.)
    // REWRITE-LOOP detection: consecutive write/edit calls to the SAME path with nothing in between.
    // Invisible to the spin guard by construction — each write has different CONTENT (different args →
    // different key) and the reset-on-edit clears counters on every successful write. Measured wedge:
    // 273 of 274 calls rewriting one __init__.py, zero builds/tests, the whole run burned.
    private String lastWritePath = null;
    private int sameFileWrites = 0;
    private static final int REWRITE_LOOP_THRESHOLD = 6;
    // ROTATING-SET variant of the same wedge: a chain of writes that CYCLES across several paths defeats
    // the single-path counter (each path change resets it). Measured: 160 consecutive write_file calls
    // rotating over 6 source files, zero verification in between (battery22 graaljs-n1). Path-agnostic
    // chain length catches every permutation; larger threshold since multi-file bursts are sometimes
    // legitimate scaffolding (a fresh project lays down ~6-10 files before the first build).
    private int unbrokenWrites = 0;
    private final LinkedHashSet<String> writeChainPaths = new LinkedHashSet<>();
    private static final int WRITE_CHAIN_THRESHOLD = 12;
    // NO-PROGRESS-FAILURE detection (battery33 library-api-n3: 174 gradle runs, 300 turns, never converged).
    // The spin guard above is blind to this by construction — it keys on the COMMAND and clears on every
    // SUCCESSFUL edit, so "edit (lands but doesn't fix it) → re-run the SAME failing test → edit → re-run …"
    // never accumulates (each landed edit wipes the counter). This is OpenHands' "repeating action-error
    // cycle" pattern: key on the FAILURE itself (normalized signature of the build/test output), counted
    // ACROSS intervening edits and never reset by them — only a CHANGED failure or a passing run is progress.
    // A failure that recurs IDENTICALLY despite edits means the edits aren't addressing it; reframe once at
    // SOFT, end the run at HARD (the model's stuck on a fix that isn't working — usually a hallucinated one).
    private final Map<String, Integer> sameFailureCounts = new HashMap<>();
    private static final int SAME_FAILURE_SOFT = 3;   // reframe: same build/test failure 3× despite edits
    private static final int SAME_FAILURE_HARD = 10;  // end run: 10× identical failure = hopeless
    // STALL-LOCALIZER (battery49 follow-up): on a recurring failure the 9B can't trace, escalate the LOCALIZE
    // step to the 30B (the documented weak-model wall — they fix given the location but can't find it). Off
    // by default (no CODEZAIKU_DISTILLER_URL → unavailable → keep the generic reframe). Grounding, not a gate.
    private final Localizer localizer = Localizer.fromEnv();

    // REPEATED-TIMEOUT (hang) detection — a blind spot of ALL the guards above. A command that times out is
    // HANGING (a deadlock/blocking call), not slow; re-running it (even with a bigger timeout) never converges.
    // The spin guard misses it because the model VARIES the timeout value (`timeout 30 X` → `timeout 60 X`),
    // so its exact-arg key differs each time; the no-progress detector misses it because a model shell-call
    // isn't the harness verify gate. battery38 py-n1 burned ~120 turns / 30× exit=124 on ONE hanging test in
    // this gap. Normalize the command (drop the timeout/env prefix so value-bumps collapse to one key) and count.
    private final Map<String, Integer> timeoutCounts = new HashMap<>();
    private static final int TIMEOUT_SOFT = 2;   // reframe: same command timed out twice → it hangs
    private static final int TIMEOUT_HARD = 4;   // end run: keeps hanging, the model can't diagnose it

    // Targeted error feedback fused with on-demand source reading: when the build can't resolve a symbol,
    // the harness reads the real installed signature and pushes it. Sparse + deduped.
    private final Set<String> nudgedSymbols = new HashSet<>();
    private static final int MAX_API_PUSHES = 5;
    private static final Pattern API_ERR = Pattern.compile(
            "no method named `([^`]+)`"
            + "|cannot find (?:function|value|type|macro) `([^`]+)`"
            // version-drift phrasings: a removed/relocated associated fn (sysinfo System::disks/networks
            // → standalone Disks/Networks types) reports as "no associated function or constant named".
            + "|no (?:function or associated item|associated function or constant|associated function"
            + "|associated item|associated constant|variant) named `([^`]+)`"
            + "|no field `([^`]+)`");
    private static final Pattern CRATE_IN_PATH =
            Pattern.compile("registry/src/[^/]+/([a-zA-Z0-9_]+?)-\\d");
    // A crate-qualified path in an error (sysinfo::DiskExt, sysinfo::CpuExt) — the E0432 version-drift form.
    private static final Pattern CRATE_PATH =
            Pattern.compile("`([a-z][a-z0-9_]{1,30})::([A-Za-z_][\\w:]{0,40})`");
    // Missing-METHOD form: the crate is the RECEIVER TYPE's, e.g. "...found for struct `sysinfo::System`" —
    // the method symbol itself is NOT crate-qualified, so it must be paired with the receiver's crate.
    private static final Pattern RECEIVER_CRATE =
            Pattern.compile("found for \\w+ `&?(?:mut )?([a-z][a-z0-9_]{1,30})::");
    private static final Set<String> STD_CRATES =
            Set.of("std", "core", "alloc", "self", "crate", "super");

    /**
     * Settle version-drift on read_dep_source (always-on, library-independent, version-CORRECT by
     * construction). On an unresolved-symbol/import build error, the HARNESS itself reads the REAL
     * installed dependency source for that symbol and PUSHES the actual API back — instead of merely
     * nudging the 9B to call a tool it often won't. Falls back to a nudge when the crate can't be resolved.
     */
    private String depSourcePush(String obs) {
        if (obs == null || obs.isBlank() || nudgedSymbols.size() >= MAX_API_PUSHES) return "";
        String low = obs.toLowerCase();
        boolean errish = low.contains("error") || low.contains("unresolved") || low.contains("cannot find");
        if (!errish) return "";
        String dep = null, sym = null;
        // The PRECISE missing symbol from the compiler message takes priority (e.g. `disks` in "no
        // associated function or constant named `disks` found for struct `sysinfo::System`") — otherwise a
        // crate-qualified path would grab the struct name (`System`) and look up an API that's still there.
        Matcher m = API_ERR.matcher(obs);
        if (m.find()) {
            for (int g = 1; g <= m.groupCount(); g++) if (m.group(g) != null) { sym = m.group(g); break; }
        }
        // Pair the symbol with its OWNING crate — NOT just any crate mentioned in the build output (that
        // mispaired `Area`→sysinfo / `total_swap_usage`→ratatui, pushing the wrong crate's API).
        Matcher um = CRATE_PATH.matcher(obs);
        while (um.find()) {
            if (STD_CRATES.contains(um.group(1))) continue;
            String[] seg = um.group(2).split("::");
            String tail = seg[seg.length - 1];
            if (sym == null) { dep = um.group(1); sym = tail; break; }   // import/type drift: crate + its symbol
            if (tail.equals(sym)) { dep = um.group(1); break; }          // symbol known: only the path that OWNS it
        }
        // Missing-method form: the crate comes from the receiver TYPE (`found for struct `sysinfo::System``),
        // since the method symbol isn't crate-qualified.
        if (dep == null && sym != null) {
            Matcher rm = RECEIVER_CRATE.matcher(obs);
            if (rm.find() && !STD_CRATES.contains(rm.group(1))) dep = rm.group(1);
        }
        if (dep == null) {
            Matcher cm = CRATE_IN_PATH.matcher(obs);
            if (cm.find()) dep = cm.group(1);
        }
        if (sym == null || sym.isBlank() || !nudgedSymbols.add(sym)) return "";
        if (dep == null || dep.isBlank()) {
            log.info("  ↳ dep-source: crate unknown for `{}` → nudge", sym);
            return "[LOOK IT UP] The build cannot resolve `" + sym + "`. Do NOT guess — call "
                    + "read_dep_source(dependency=\"<the crate that owns it>\", query=\"" + sym + "\") to read "
                    + "the REAL current signature, then use exactly that.";
        }
        ObjectNode a = j.createObjectNode();
        a.put("dependency", dep);
        a.put("query", sym);
        String real;
        try {
            real = tools.execute("read_dep_source", a);
        } catch (Exception e) {
            return "";
        }
        if (real == null || real.isBlank() || real.startsWith("ERROR")) return "";
        if (real.length() > 1200) real = real.substring(0, 1200) + "\n…";
        log.info("  ↳ dep-source PUSH for `{}` in {}", sym, dep);
        return "[REAL API] The build can't resolve `" + sym + "` — the installed version of `" + dep
                + "` likely moved or renamed it. Here is its ACTUAL current API, read from the installed "
                + "source; use EXACTLY this (do not reach for an older API):\n" + real;
    }

    private static final String SUMMARIZE_PROMPT = """
            You are compacting a coding session into a checkpoint that lets the work continue WITHOUT
            the full transcript. Output ONLY the sections below, as terse bullets. PRESERVE EXACT file
            paths, commands, identifiers, and error strings verbatim. If a PRIOR CHECKPOINT is given,
            carry every fact forward and update it (move In progress → Done as warranted); never drop
            a decision or a path.

            ## Goal
            ## Constraints
            ## Progress
            - Done:
            - In progress:
            - Blocked:
            ## Key decisions
            ## Next steps
            ## Critical context (signatures, contracts, exact errors to remember)
            """;

    public FamiliarLoop(DriveClient drive, ToolRegistry tools, Path projectRoot, String goal,
                        int maxTurns, Library library) {
        this(drive, tools, projectRoot, goal, maxTurns, library, null);
    }

    public FamiliarLoop(DriveClient drive, ToolRegistry tools, Path projectRoot, String goal,
                        int maxTurns, Library library, LibraryIndex index) {
        this.drive = drive;
        this.tools = tools;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.goal = goal;
        this.maxTurns = maxTurns;
        this.library = library;
        this.index = index;
        this.multiConcern = isMultiConcern(goal);
        this.specReqs = extractStructuredReqs(goal); // layer 1: mechanical, when the goal has structure
        this.proactiveIdioms = computeProactiveIdioms();
        this.wiringSketch = computeWiringSketch();
        this.nctx = drive.contextWindow();
        this.j = drive.json();
        this.memory = new FamiliarMemory(this.projectRoot);
        this.maintenanceProject = computePreexistingCodebase();
        log.info("task-type: {} -> error-grounding injection {}",
                maintenanceProject ? "MAINTENANCE (pre-existing codebase)" : "greenfield (empty/skeleton start)",
                maintenanceProject ? "OFF (bug is in existing code to READ; injected API-fix chunks mislead a weak "
                        + "model -- measured M1 30B: grounding ON 3/8 vs OFF 8/8, p=0.026)" : "ON");
    }

    private static final Set<String> SOURCE_EXTS = Set.of(
            "py","java","kt","rs","go","ts","js","tsx","jsx","c","cc","cpp","h","hpp","rb","cs","swift","scala","php");

    /**
     * TASK-TYPE detection (general + objective, deliberately NOT keyed to any fixture, goal keyword, or file
     * name): does the project ALREADY contain a real pre-existing codebase at loop start? If so this is a
     * MAINTENANCE task (fix/extend existing code) where the error-grounding injection's premise -- "YOUR code
     * used a wrong API, replace it with these signatures" -- is FALSE: the bug is pre-existing code the model
     * must READ, and the injected API chunks dilute a weak model's attention (measured: M1 30B error-grounding
     * ON 3/8 vs OFF 8/8, Fisher p=0.026). Greenfield (empty/skeleton start, per this repo's convention) has ~0
     * real source and keeps grounding ON (the model writes new code against APIs it may genuinely misuse).
     * The thresholds are GENEROUS skeleton-vs-real-app separators, not tuned to a fixture.
     */
    private boolean computePreexistingCodebase() {
        int files = 0; long bytes = 0;
        try (var w = Files.walk(projectRoot, 12)) {
            for (Path p : (Iterable<Path>) w.filter(Files::isRegularFile)::iterator) {
                String rel = projectRoot.relativize(p).toString().replace('\\','/');
                if (rel.contains(".venv/") || rel.contains("node_modules/") || rel.contains("/target/")
                        || rel.contains("/build/") || rel.contains(".git/") || rel.contains("__pycache__/")
                        || rel.contains("/dist/")) continue;
                if (rel.contains("test") || rel.endsWith("__init__.py")) continue;   // exclude tests + trivial pkg markers
                String name = p.getFileName().toString();
                int dot = name.lastIndexOf('.'); if (dot < 0) continue;
                if (!SOURCE_EXTS.contains(name.substring(dot + 1).toLowerCase())) continue;
                long sz;
                try { sz = Files.size(p); } catch (Exception e) { continue; }
                if (sz < 60) continue;                          // skip empty/skeleton stubs
                files++; bytes += sz;
            }
        } catch (Exception e) { return false; }
        return files >= 3 && bytes >= 1500;
    }

    /**
     * STABLE proactive order: compute a SMALL, FIXED idiom block ONCE (not re-queried per turn). Gives the
     * model the stack's "do it right" up front to reduce variance — without the per-turn changing stream
     * that diluted attention ("context rot").
     */
    private String computeProactiveIdioms() {
        if (index == null || !index.available()) return "";
        var frameworks = LibraryScope.frameworks(projectRoot,
                ProjectFacts.language(projectRoot));
        var idioms = index.searchTier(goal, frameworks, "evergreen-idiom", 2); // 2×350: 16K-ctx prompt-weight cap
        if (idioms.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "\n\nSTACK IDIOMS — write it right the first time (curated conventions for this stack; follow them):\n");
        for (var c : idioms) {
            String t = c.content() == null ? "" : c.content().strip();
            if (t.length() > 350) t = t.substring(0, 350) + "…";
            sb.append("\n— ").append(c.title()).append(":\n").append(t).append('\n');
        }
        return sb.toString();
    }

    /**
     * Per-spec WIRING SKELETON, generated ONCE at intake by the 30B (strong-weak collaboration,
     * arXiv:2505.20182). The coder problem read from produced code: a ~9B builds multi-stage apps by
     * HARDCODING canned endpoint data instead of wiring the real flow, because the full pipeline is above its
     * reach — faking is the only completable path (Kevin arXiv:2507.11948; NVIDIA SLM arXiv:2506.02153). A
     * STATIC library example can't cover infinite problem shapes; the 30B reading THIS spec and laying out the
     * stages + signatures + entry-point-serves-computed-values does, for any shape. Pinned worked example, NOT
     * a gate. Off unless CODEZAIKU_DISTILLER_URL is set; only for multi-concern (multi-stage) tasks where the
     * hardcoding shortcut actually bites — a single-endpoint task doesn't need a wiring map.
     */
    private String computeWiringSketch() {
        if (!multiConcern) return "";
        WiringPlanner planner = WiringPlanner.fromEnv();
        if (!planner.available()) return "";
        String sketch = planner.plan(goal, ProjectFacts.language(projectRoot));
        if (sketch == null || sketch.isBlank()) return "";
        return "\n\nWIRING SKELETON for this spec (the end-to-end data flow — implement each stage's REAL logic; "
                + "every served value must be DERIVED by running these stages on the real input, NEVER a "
                + "hardcoded sample):\n" + sketch.strip() + "\n";
    }

    /** A task with many concerns benefits from a plan anchor. Detect it from the goal. */
    private static boolean isMultiConcern(String goal) {
        if (goal == null) return false;
        if (goal.length() > 700) return true;
        String g = goal.toLowerCase();
        int signals = 0;
        for (String s : new String[]{"get /", "post /", "endpoint", " panel", " tab", " system (", "concern"}) {
            int i = 0;
            while ((i = g.indexOf(s, i)) >= 0) { signals++; i += s.length(); }
        }
        return signals >= 3;
    }

    /** True when the server refused because the request is larger than the context window. */
    static boolean contextOverflow(String message) {
        if (message == null) return false;
        String m = message.toLowerCase(java.util.Locale.ROOT);
        return m.contains("exceed_context_size_error")
                || m.contains("exceeds the available context size")
                || m.contains("context length exceeded")
                || m.contains("maximum context length")
                || (m.contains("context") && m.contains("token") && m.contains("exceed"));
    }

    /** The server's own numbers if it gave any — they are more use than anything we could restate. */
    static String overflowDetail(String message) {
        if (message == null) return "size not reported";
        var m = java.util.regex.Pattern
                .compile("(\\d+)\\s*tokens?[^0-9]{0,40}?(\\d+)\\s*tokens?").matcher(message);
        if (m.find()) return m.group(1) + " tokens into a " + m.group(2) + "-token window";
        var n = java.util.regex.Pattern.compile("n_ctx[\"'\\s:=]+(\\d+)").matcher(message);
        if (n.find()) return "window is " + n.group(1) + " tokens";
        return "size not reported";
    }

    public record Result(boolean done, String summary, int turns) {
    }

    public Result run() {
        ArrayNode toolSchemas = tools.toolsArray(j);

        // The growing conversation: the action/observation transcript. The pinned ground truth
        // (instructions + current shape + goal) is regenerated into a fresh system message each turn.
        ArrayNode history = j.createArrayNode();
        // The kickoff names what finishing MEANS, and that differs by deliverable. The coding form
        // says "met and verified (build + tests pass)" — for a conversational turn that goal can
        // never be met by talking, so a model told to work toward it concludes it must implement.
        // Measured live, twice: asked to DISCUSS a plan, the model asked excellent clarifying
        // questions, then said "since I cannot wait, I will make a reasonable assumption", answered
        // its own questions, and started writing entity classes into the host repository. The chat
        // kickoff makes the reply itself the deliverable, and open questions the way a turn ends.
        history.addObject().put("role", "user")
                .put("content", chatMode
                        ? "This is one turn of a conversation; the person replies after it. Your "
                          + "deliverable THIS TURN is your reply — an answer, a plan, or the "
                          + "questions you need answered. Deliver exactly what was asked and then "
                          + "finish the turn: when the person asks for a plan, the plan IS the "
                          + "deliverable, and implementing it is the NEXT turn's work, after they "
                          + "have read it. Use tools only where the reply needs "
                          + "them. When the reply is ready, call task_done with the FULL reply as "
                          + "the summary. Anything you still need from the person belongs in the "
                          + "reply as a question — asking and finishing the turn IS completing it."
                        : "Begin. Work toward the goal using the tools. When it is met and you have "
                          + "verified it (build + your own tests pass), call task_done."
                          + (multiConcern ? planRequestInstruction() : ""));

        try {
        for (int turn = 1; turn <= maxTurns + epilogueTurns; turn++) {
            turnNow = turn;
            if (cancelled.getAsBoolean()) {
                log.info("  ↳ cancelled by the host at turn {}", turn);
                return new Result(false, "cancelled by the host after " + (turn - 1) + " turns", turn);
            }
            // END-GAME pressure (once, ~20 turns before the cap): agents otherwise hit the budget wall with
            // no warning — battery15 java-n1 died at turn 300 mid-edit with a broken file. One salient user
            // message redirects the model from adding scope to converging what exists.
            if (turn == Math.max(1, maxTurns - 20) && maxTurns > 60) {
                log.info("  ↳ end-game pressure note (turn {}/{})", turn, maxTurns);
                history.addObject().put("role", "user").put("content",
                        "FINAL STRETCH — about 20 turns remain. Finish the change you are on, then run your "
                        + "build and tests ONE FINAL TIME and call task_done only when that final run is "
                        + "green. Prefer completing what exists over starting anything new.");
            }
            // STALL ESCALATION (help a weak driver that's SPINNING without producing the deliverable — observed:
            // a 9B burned 226 turns on a multi-stage fine-tune pipeline without ever running training). A third of
            // the way in, if the goal's named deliverable still doesn't exist, ask the 30B distiller (localizer) for
            // the single concrete next step and push it. One-shot; only when a deliverable is named+missing and the
            // distiller is available. A healthy driver usually has output by then, so this rarely fires on it.
            if (!stallEscalated && turn == Math.max(40, maxTurns / 3) && maxTurns > 120
                    && localizer.available() && deliverableArtifactFinding() != null) {
                stallEscalated = true;
                String diag = localizer.localize(goal,
                        "the builder has used " + turn + " turns and has NOT produced the deliverable yet",
                        "no output file exists; it is spinning on a multi-stage pipeline (e.g. train→generate)",
                        localizerFiles(6));
                if (diag != null && !diag.isBlank()) {
                    log.info("  ↳ stall escalation (turn {}/{}) → 30B distiller next-step nudge", turn, maxTurns);
                    history.addObject().put("role", "user").put("content",
                            "You've spent a while and still have no real output. A senior engineer read your goal and "
                            + "code and named the concrete next step — do exactly this next, on a SMALL subset first to "
                            + "make it run end-to-end, then scale up:\n\n" + diag);
                }
            }
            // REPEATED DEV-OUTPUT NUDGE (dev-gated): the dev-gate only engages once dev_predictions exists, but for
            // a SERVICE deliverable (RAG) nothing forces it — the 9B perfected the service for 237 turns and never
            // produced dev output (sv7), so the dev-gate never scored anything. Every ~30 turns while
            // dev_predictions is missing, push it to produce them as a plain SCRIPT (no running service needed).
            if (devAnswersPath != null && turn >= 40 && turn - lastDevNudgeTurn >= 30 && devNudges < 5
                    && findFileByName("dev_predictions.jsonl") == null) {
                lastDevNudgeTurn = turn; devNudges++;
                log.info("  ↳ dev-output nudge (turn {}) — no dev_predictions yet (#{}/5)", turn, devNudges);
                history.addObject().put("role", "user").put("content",
                        "You have NOT written `dev_predictions.jsonl` yet. You do NOT need a perfect or running "
                        + "service to produce it — write a small SCRIPT that runs your retrieve+generate logic on each "
                        + "line of `data/dev_questions.jsonl` and writes `dev_predictions.jsonl` "
                        + "(`{\"id\":..,\"answer\":..,\"citations\":[..]}`). The harness scores THAT (labeled cite/F1), "
                        + "NOT your service or your unit tests. Produce it NOW, then improve from the score.");
            }
            // MID-RUN ARTIFACT PROBE (dev-gated + fixture ships artifact_probe.py): the task_done probe can't help
            // a run that never claims done (RM sv9 saved a full model to a doubled path and thrashed to the turn cap
            // without ever calling task_done). So probe the artifact when it FIRST APPEARS and whenever it CHANGES —
            // the model learns its save is broken while it can still react. CPU-only (never contends with training
            // on the GPU), every 5th turn at most, bounded total runs; a pass is silent and just records the stamp.
            if (devAnswersPath != null && turn % 5 == 0 && midrunProbes < MAX_MIDRUN_PROBES) {
                String stamp = artifactStamp();
                if (stamp != null && !stamp.equals(lastProbedStamp)) {
                    lastProbedStamp = stamp;
                    midrunProbes++;
                    String probeErr = runArtifactProbe(true);
                    if (probeErr != null) {
                        log.info("  ↳ mid-run artifact probe (#{}/{}): FAIL — {}", midrunProbes, MAX_MIDRUN_PROBES, preview(probeErr));
                        history.addObject().put("role", "user").put("content",
                                "HARNESS CHECK — your saved artifact FAILS to load/score under the interface the "
                                + "verifier uses (checked just now, while you still have turns to fix it). The real "
                                + "error from loading it exactly as the goal specifies:\n\n" + probeErr
                                + "\n\nFix how the artifact is trained/saved (the exact path in the goal, the "
                                + "task_type, the save including every declared module), re-save, and re-check.");
                    } else {
                        log.info("  ↳ mid-run artifact probe (#{}/{}): OK", midrunProbes, MAX_MIDRUN_PROBES);
                    }
                }
            }
            // ARTIFACT KEEP-BEST: on every dev-gated turn, if the dev metric improved and an adapter is on disk,
            // snapshot it — so a later thrash that ships a worse adapter is recoverable (restored at run end).
            if (devAnswersPath != null && turn % 3 == 0) snapshotBestIfImproved();
            // STUCK ON A BROKEN-BUT-PRESENT DELIVERABLE → repeated FRESH 30B re-localization (+ reset-to-known-good).
            // Complements the one-shot stall (missing deliverable) and the no-progress-FAILURE detector (exit!=0):
            // this fires when the output FILE exists but most rows are broken (errors/empty/raw-template) and edits
            // aren't reducing that — the 9B's 60-turn inference-bug spin. Bounded re-escalation with fresh context.
            if (maxTurns > 120 && localizer.available()) {
                double br = deliverableBrokenRatio();          // -1 = no deliverable file yet (stall block owns that)
                // Keep the BEST-so-far regardless of the absolute threshold (proper AIDE/MLE-STAR keep-best): the
                // 9B's best is often PARTIAL (e.g. dev 0.40 = br 0.60), and it then regresses while "improving" —
                // snapshotting only near-perfect states (br<0.25) left nothing to restore (docs-rag sv5: 0.01→0.40
                // real answers → broke back to "I cannot determine", no checkpoint of the 0.40 state).
                if (br >= 0 && br < bestBrokenRatio) {
                    bestBrokenRatio = br; bestCheckpoint = snapshotCheckpoint(1000 + turn);
                }
                // RESTORE-ON-REGRESSION (decoupled from the bounded 30B re-localization — restoring is cheap+safe):
                // if the deliverable REGRESSED well below the best-seen state, restore the best regardless of the
                // re-localization budget. sv8: dev climbed 0.41→0.43 then fell back to "I cannot tell" AFTER the 3
                // re-localizations were spent, so the restore (trapped in that bounded block) fired 0×. Rate-limited
                // (≥15 turns apart) + bounded so it can't thrash.
                if (br >= 0 && bestCheckpoint != null && br > bestBrokenRatio + 0.2
                        && turn - lastRestoreTurn >= 15 && restores < MAX_RESTORES && restoreCheckpoint(bestCheckpoint)) {
                    restores++; lastRestoreTurn = turn; stuckTurns = 0;
                    log.info("  ↳ restore-on-regression (turn {}): fell to ~{}% broken (best ~{}%) → restored best #{}",
                            turn, Math.round(br * 100), Math.round(bestBrokenRatio * 100), restores);
                    history.addObject().put("role", "user").put("content",
                            "Your output REGRESSED — it was BETTER a few edits ago. I restored your best-working "
                            + "version. Do NOT rewrite from scratch; make only SMALL targeted improvements (e.g. shorter, "
                            + "more exact answers) and re-check the dev output stays good after each change.");
                }
                if (br >= 0 && br < 0.25) {                     // deliverable is in good shape → real progress
                    stuckTurns = 0; lastBrokenRatio = br;
                } else if (br >= 0.25) {                        // deliverable EXISTS but is mostly broken
                    if (br < lastBrokenRatio - 0.1) { stuckTurns = 0; lastBrokenRatio = br; }   // improving → reset
                    else stuckTurns++;
                    if (stuckTurns >= STUCK_TURNS && stuckEscalations < MAX_STUCK_ESCALATIONS) {
                        stuckEscalations++; stuckTurns = 0;
                        log.info("  ↳ stuck on broken deliverable (~{}% broken rows) → fresh 30B re-localization #{}/{}",
                                Math.round(br * 100), stuckEscalations, MAX_STUCK_ESCALATIONS);
                        String sample = brokenOutputSample();
                        String diag = localizer.localize(goal,
                                "your output file exists but is BROKEN — most rows are errors/empty, not real results",
                                "broken-row ratio ~" + Math.round(br * 100) + "% — several edits have not reduced it. "
                                + "Here is an ACTUAL SAMPLE of the broken output (diagnose the cause from it — e.g. an "
                                + "answer describing the context as random strings/ids means the code fed doc IDS to the "
                                + "model instead of the doc TEXT; empty/placeholder rows mean the core didn't run):\n"
                                + sample,
                                localizerFiles(6));
                        String restored = "";
                        if (bestCheckpoint != null && bestBrokenRatio < br - 0.1 && restoreCheckpoint(bestCheckpoint))
                            restored = "\n\nI also restored your best-working version of the code (it produced better "
                                    + "output) — apply the fix on top of THAT, do not start over.";
                        if (diag != null && !diag.isBlank())
                            history.addObject().put("role", "user").put("content",
                                    "Your output file is full of broken rows and several edits have not fixed it. A "
                                    + "senior debugger looked FRESH at your code and the broken output and localized the "
                                    + "cause — apply exactly this, then re-run:\n\n" + diag + restored);
                    }
                }
            }
            elideRedundant(history);
            compact(history);
            ObjectNode system = j.createObjectNode();
            system.put("role", "system").put("content", systemPrompt());

            ArrayNode messages = j.createArrayNode();
            messages.add(system);
            messages.addAll(history);

            int outBudget = outputBudget(messages);
            log.info("turn {}/{}  (out_budget={})", turn, maxTurns, outBudget);

            // TURN-BUDGET AWARENESS (research). Measured on SimpleQA: 21 of 30 baseline runs produced NO
            // answer at all — not because they were starved (out_budget stayed full) but because the model
            // searches and fetches until the turns simply run out, never deciding it has enough to write.
            // It cannot see the budget, so tell it: a well-sourced partial answer beats no answer. Two
            // pushes only (70% and 90%), and never a rejection — the model still decides when it's done.
            if (researchMode && !budgetWarned.contains(budgetMark(turn))) {
                int mark = budgetMark(turn);
                if (mark > 0) {
                    budgetWarned.add(mark);
                    int left = maxTurns - turn + 1;
                    log.info("  ↳ turn-budget notice at {}% ({} turns left)", mark, left);
                    history.addObject().put("role", "user").put("content",
                            "TURN BUDGET: you have " + left + " of " + maxTurns + " turns left. "
                            + (mark >= 90
                               ? "Write the answer NOW from the sources you have already read and call "
                                 + "task_done this turn — searching more will just run out the clock."
                               : "Start converging: at most one or two more sources, then write the answer "
                                 + "and call task_done.")
                            + " Say plainly in the answer what you could not verify — a partial answer with "
                            + "its sources and its gaps stated is worth far more than no answer at all.");
                }
            }

            // Hybrid tool_choice: force PROSE on the planning turn so the model emits the numbered PLAN
            // (smallcode plan-then-execute — `required` would forbid the prose), then REQUIRE a tool on
            // every work turn (force action, no prose runaways — the original reason for `required`). We
            // open the prose door only on the one turn we want it.
            boolean planTurn = multiConcern && !planResolved && turn == 1;
            boolean proseAnswerTurn = proseAnswerNext;
            proseAnswerNext = false;
            // DEADLINE TURN (research): telling the model the budget is nearly gone is not enough — measured,
            // it reads the notice and searches again anyway, and the run ends with NO answer at all (the
            // dominant SimpleQA failure: 21/30 baseline runs produced nothing). On the last turn the only
            // tool offered is task_done, so the turn spends itself writing the answer instead of opening one
            // more page. Nothing the model wanted to do is rejected — the exam is simply over.
            ArrayNode turnTools = (deadlineTurn && turn >= maxTurns) ? onlyTaskDone(toolSchemas) : toolSchemas;
            ObjectNode assistant;
            // Last line of defence: never SEND a request that cannot fit. Compaction is a threshold
            // (70%) and the pinned block is budgeted, but a single large observation lands after both
            // — a host watched a mid-run file read take the request to 30,167 tokens against a
            // 16,384-token window. The tell was already in the log: outputBudget() had bottomed out
            // at its 512 floor, which only happens when the input alone has overrun the window, and
            // the request went out anyway.
            int trimmed = fitToWindow(messages);
            if (trimmed > 0) {
                log.info("turn {}: trimmed {} chars of older observations so the request fits {} tokens",
                        turn, trimmed, nctx);
                outBudget = outputBudget(messages);
            }
            try {
                assistant = drive.chat(messages, turnTools, outBudget,
                        (planTurn || proseAnswerTurn) ? "none" : "required");
            } catch (RuntimeException e) {
                // A single bad model response (e.g. malformed tool-call JSON the server rejected) must NOT
                // abort the run. Nudge and skip the turn; the re-sample next turn almost always succeeds.
                String msg = e.getMessage() == null ? "" : e.getMessage();
                // A PROMPT THAT DOES NOT FIT cannot be nudged into fitting. The other failures here
                // are things the model can do differently next turn; this one is a property of the
                // request we just built, so resending it fails identically until the budget is gone.
                // Reported by a host that spent 40 turns and seven minutes on identical 400s and got
                // `files=[]` with no reason — the WARN line was the only thing that said why.
                if (contextOverflow(msg)) {
                    log.error("turn {}: the assembled request does not fit the model's context window "
                            + "({}). Stopping rather than resending it {} more times.",
                            turn, overflowDetail(msg), maxTurns - turn);
                    return new Result(false, org.codezaiku.run.ResultDocument.UNRECOVERABLE + " the assembled request exceeds the "
                            + "model's context window (" + overflowDetail(msg) + "). The task itself may be "
                            + "small — in a large repository the project-shape block dominates the prompt. "
                            + "Use a model server with a bigger window, set CODEZAIKU_CTX to the real one, "
                            + "or run against a narrower directory.", turn);
                }
                if (++consecutiveDriveFailures >= MAX_CONSECUTIVE_DRIVE_FAILURES) {
                    log.error("turn {}: {} consecutive drive failures — the endpoint is not serving "
                            + "this request shape. Stopping instead of retrying forever. Last: {}",
                            turn, consecutiveDriveFailures, e.getMessage());
                    return new Result(false, org.codezaiku.run.ResultDocument.UNRECOVERABLE
                            + " the drive failed " + consecutiveDriveFailures + " times in a row ("
                            + String.valueOf(e.getMessage()).replaceAll("\s+", " ")
                            + "). Check CODEZAIKU_DRIVE — for hosted APIs the base URL takes no "
                            + "path (use https://api.anthropic.com, not .../v1).", turn);
                }
                log.warn("turn {}: drive call failed — nudging and continuing: {}", turn, e.getMessage());
                // DISTINGUISH the two failure modes. llama.cpp's tool-call parser CRASHES on an oversized
                // argument (a write_file with a big content blob — a whole data fixture, a long file): the
                // error echoes the giant string back and says "parse error … missing closing quote". The old
                // nudge ("resend VALID JSON") was WRONG for this — the JSON wasn't invalid, it was too BIG,
                // so the model just resent the same huge write and crashed again (battery42 email-intel-py:
                // a ~5KB sample.mbox write failed turns 7-9+, burned the budget, never reached the web layer).
                // When the failure looks oversized, tell it to SPLIT the write into small pieces instead.
                String em = e.getMessage() == null ? "" : e.getMessage();
                boolean oversized = em.length() > 2000 || em.contains("missing closing quote")
                        || (em.contains("parse error") && em.contains("column"));
                history.addObject().put("role", "user").put("content", oversized
                        ? "Your last tool call was TOO LARGE to parse — a single write_file/edit_file carrying "
                        + "a big content blob (a whole data fixture, a long file) overflows the tool-call "
                        + "parser. Build the file in SMALL PIECES instead: first a short write_file to create "
                        + "it (a few lines), then ADD the rest with several small edit_file calls — or append "
                        + "in chunks via shell (`cat >> FILE <<'EOF' … EOF`). Keep each tool call's content "
                        + "under ~2KB."
                        : "Your last reply could not be processed (likely malformed tool-call JSON). Resend it "
                        + "with VALID JSON: keep every string value properly quoted and escaped, no stray "
                        + "newlines or quotes inside a value.");
                continue;
            }
            history.add(assistant);
            consecutiveDriveFailures = 0;
            observePlan(assistant.path("content").asText("")); // parse the plan (orientation only)

            if (proseAnswerTurn) {
                // The prose written on this turn IS the final answer (see the artifact bounce above).
                // Some runs emit their tool-call SYNTAX as text here (<tool_call><function=task_done>
                // <parameter=summary>…) — habit from every other turn. Unwrap it: the parameter text is
                // the intended answer; the markup is not.
                String prose = unwrapToolMarkup(assistant.path("content").asText("").strip());
                if (!prose.isBlank()) {
                    String full = withDraft(prose);
                    log.info("task_done (prose answer) at turn {}: {} chars{}", turn, full.length(),
                            hasStructured(full) ? ", artifact present" : ", STILL no artifact");
                    return new Result(true, full, turn);
                }
                log.warn("  ↳ prose-answer turn returned nothing — one more prose try");
                proseAnswerNext = true; // re-arm once more; a blank turn under "none" is a hiccup, not a refusal
                history.addObject().put("role", "user").put("content",
                        "You wrote nothing. Write the complete final answer with the table now, as text.");
                continue;
            }

            if (planTurn) {
                // Intentionally prose-only — the plan is now parsed; switch to execution next turn.
                //
                // EXCEPT in chat. This line was the plan-restraint bug the conversation battery
                // kept failing on THREE models identically (2026-08-29): after the person said
                // "write ONLY PLAN.md — no code", the models wrote a prose plan here — and then
                // THIS instruction ordered them to execute it. Three K=3 prompt-lever flips all
                // failed because the pressure was never in the prompts; it was this harness line.
                // In chat the person is the executor's trigger: hand the turn back to what THEY
                // asked for, and let implementation wait for the turn where they ask.
                log.info("  ↳ planning turn → {} steps parsed; {}", planSteps.size(),
                        chatMode ? "chat: back to the ask" : "executing");
                history.addObject().put("role", "user").put("content", chatMode
                        ? "That is the plan. Now finish THIS turn in order: FIRST, when the "
                          + "message asked for the plan in a file, create that file with "
                          + "write_file and the full plan as its content. THEN call task_done "
                          + "with the plan as the reply. (task_done reports what already "
                          + "happened — a file only exists after a write_file call succeeds.) "
                          + "Start building only if the message asked you to build."
                        : "Now execute the plan — start with step 1, using the tools.");
                continue;
            }

            var calls = assistant.path("tool_calls");
            if (!calls.isArray() || calls.isEmpty()) {
                // Under tool_choice=required this is rare; treat as an honest anomaly and re-prompt.
                log.warn("turn {}: assistant returned no tool_calls — content=[{}]", turn,
                        assistant.path("content").asText(""));
                history.addObject().put("role", "user")
                        .put("content", "You must act by calling a tool.");
                continue;
            }

            int batchStart = history.size();
            for (var call : calls) {
                String name = call.path("function").path("name").asText();
                String id = call.path("id").asText();
                String argsRaw = call.path("function").path("arguments").asText("{}");
                var args = parseArgs(argsRaw);
                trackFiles(name, args);

                String observation;
                long callStart = 0; // set just before tool execution (zero-executed test detection)
                // Repetition guard (ACI robustness, NOT a completion gate): a small model can wedge on the
                // SAME command forever (curl a dead port 283×, pip install 291×). Block the duplicate to
                // force a different action. task_done is exempt (model decides done).
                boolean control = TaskDoneTool.NAME.equals(name) || TaskBlockedTool.NAME.equals(name);
                if (!control && ("write_file".equals(name) || "edit_file".equals(name)
                        || "shell".equals(name))) {
                    mutatingCallRan = true;   // ground truth for the chat false-write bounce
                }
                String sig = spinKey(name, args, argsRaw);
                int spins = control ? 0 : callCounts.merge(sig, 1, Integer::sum);
                String spinReframe = null; // contextual recovery to push once, after the tool result
                if (spins > MAX_IDENTICAL_CALLS) {
                    observation = "BLOCKED — you've run the same command " + spins + "× with no new result; the "
                            + "harness refused the repeat. Do something DIFFERENT.";
                    log.info("  ↳ {}({}) → SPIN-BLOCK x{}", name, preview(argsRaw), spins);
                    // CAP + END: a stuck weak model just re-emits the blocked command forever. After
                    // MAX_TOTAL_SPINS cumulative blocks it's hopeless — end the run, don't burn the budget.
                    if (++totalSpinBlocks > MAX_TOTAL_SPINS) {
                        log.warn("ending run early at turn {}: {} spin-blocks (model hopelessly stuck)",
                                turn, totalSpinBlocks);
                        return new Result(false, "ended early — stuck re-emitting blocked commands ("
                                + totalSpinBlocks + " blocks)", turn);
                    }
                    // REFRAME once per stuck key (change the approach, not just refuse) as a salient USER
                    // message — the 9B skims tool-result blocks.
                    if (reframedKeys.add(sig)) spinReframe = spinEscalation(name, args, spins);
                } else {
                    callStart = System.currentTimeMillis();
                    observation = tools.execute(name, args);
                    // MASK SECRETS BEFORE THE MODEL SEES THEM. Every tool result lands in the growing
                    // conversation and is sent to the drive; service logs carry connection strings with
                    // inline passwords and a config read for context carries whatever is in it. The note
                    // is appended so the model knows a value was withheld rather than treating the mask
                    // as the literal secret. Separate job from neutralizeLogText, which defuses
                    // instruction-shaped text — this one stops secrets travelling.
                    Redactor.Result red = Redactor.redact(observation);
                    if (red.redacted()) {
                        observation = red.text() + red.note();
                        log.info("  ↳ redacted {} secret/PII value(s) from {} output", red.count(), name);
                    }
                    log.info("  ↳ {}({}) → {}", name, preview(argsRaw), preview(observation));

                    // Any non-write tool call (a build, a read, a test run) is real interleaved progress —
                    // the rewrite-loop counter only measures UNBROKEN write chains to one path.
                    if (!name.equals("write_file") && !name.equals("edit_file")) {
                        sameFileWrites = 0;
                        lastWritePath = null;
                        unbrokenWrites = 0;
                        writeChainPaths.clear();
                    }
                    // Evidence freshness: a shell verification run (build/test/check) SEES the current state;
                    // any later successful edit makes that evidence stale again.
                    if (name.equals("shell")) {
                        lastShellObs = observation; // keep the latest run output for the self-verify localizer hand-off
                        if (!validationMetricSeen && looksLikeValidationMetric(observation)) validationMetricSeen = true;
                        String vc = args.path("command").asText("").toLowerCase();
                        if (vc.contains("test") || vc.contains("build") || vc.contains("check")
                                || vc.contains("compile") || vc.contains("pytest") || vc.contains("classes")
                                || vc.contains("--headless")
                                || (vc.contains("python") && vc.contains(".py"))) {  // running the agent's own
                                // script (python sft.py / main.py) IS a verification — its repeated failures were
                                // invisible to the no-progress detector, so a script-run thrash got no 30B recovery
                                // (qlora sv1: 24× identical SyntaxError on `python sft.py`, never escalated, ran out of turns)
                            dirtySinceVerify = false;
                            // A failing build/test run resurfaces the full worked example (drift bites late).
                            String ol = observation.toLowerCase();
                            boolean failing = ol.contains("error[") || ol.contains("error:") || ol.contains("build failed")
                                    || ol.contains("traceback") || ol.contains("failed") || ol.contains("npm err");
                            // A clearly-successful run can still contain the word "failed" ("0 failed",
                            // "failures: 0") — don't let the no-progress detector count a PASS as a failure
                            // (it would wrongly end a done-but-undeclared run). lastBuildErrorTurn keeps its
                            // tolerant heuristic; the detector requires a real, non-success failure.
                            boolean passing = ol.contains("build successful") || ol.contains("build succeeded")
                                    || ol.contains("0 failed") || ol.contains("0 failures") || ol.contains("failures: 0")
                                    || ol.contains("all tests passed") || ol.contains("tests passed");
                            if (failing) {
                                lastBuildErrorTurn = turnNow;
                                // NO-PROGRESS-FAILURE detector: a failure that recurs IDENTICALLY despite the
                                // edits in between (per-signature count, never reset by edits — only a changed
                                // failure or a pass clears it). Catches the ping-pong wedge the spin guard misses.
                                if (passing) sameFailureCounts.clear();   // a real pass = progress, reset
                                String fsig = passing ? "" : failureSignature(observation);
                                if (!fsig.isEmpty()) {
                                    int fc = sameFailureCounts.merge(fsig, 1, Integer::sum);
                                    if (fc >= SAME_FAILURE_HARD) {
                                        if (devAnswersPath != null) {
                                            // DEV-GATED: the dev-gate (labeled cite/F1 on the deliverable) is the
                                            // arbiter, NOT the model's own unit tests. Don't TERMINATE on a recurring
                                            // failure — it's usually the 9B fixating on its OWN test (sv6 died at
                                            // turn 76 re-running `pytest test_chat.py` 10×). Reset + redirect it to
                                            // the dev-scored deliverable; the dev-gate + turn cap still bound the run.
                                            sameFailureCounts.remove(fsig);
                                            if (reframedKeys.add("devredirect:" + fsig)) {
                                                log.info("  ↳ no-progress on own test ({}×) → redirect to deliverable (dev-gated)", fc);
                                                history.addObject().put("role", "user").put("content",
                                                    "STOP iterating that failing command — the harness scores your "
                                                    + "DELIVERABLE on a hidden labeled dev set (dev_predictions), NOT your "
                                                    + "own unit tests. Stop fixing tests; run your finished pipeline "
                                                    + "end-to-end on `data/dev_questions.jsonl` and write "
                                                    + "`dev_predictions.jsonl` — that is what is measured.");
                                            }
                                        } else {
                                            log.warn("ending run early at turn {}: same build/test failure recurred {}× "
                                                    + "despite edits (no-progress loop)", turn, fc);
                                            return new Result(false, "ended early — same failure recurred " + fc
                                                    + "× with no progress (edits not addressing it)", turn);
                                        }
                                    }
                                    if (fc == SAME_FAILURE_SOFT && reframedKeys.add("fail:" + fsig)) {
                                        // STALL → escalate the LOCALIZE step to the 30B (the one thing a weak
                                        // model reliably can't do). It reads the real failing output + the
                                        // recently-edited source and pinpoints the root cause (esp. a cross-stage
                                        // producer/consumer contract mismatch); we surface that, the 9B applies it.
                                        // Bounded: once per failure-signature. Falls back to the generic reframe.
                                        String diag = null;
                                        if (localizer.available()) {
                                            log.info("  ↳ no-progress failure x{} → escalating localization to the 30B", fc);
                                            diag = localizer.localize(goal, args.path("command").asText(""),
                                                    observation, localizerFiles(6));
                                        }
                                        if (diag != null && !diag.isBlank()) {
                                            log.info("  ↳ localizer root-cause diagnosis ({} chars) → salient push", diag.length());
                                            history.addObject().put("role", "user").put("content",
                                                    "A senior debugger analyzed your repeatedly-failing run and LOCALIZED the "
                                                    + "root cause for you. Trust this and apply exactly the fix it names — do "
                                                    + "NOT start over or redesign:\n\n" + diag
                                                    + "\n\nRead the named file, make exactly that change, then re-run the command.");
                                        } else {
                                            log.info("  ↳ no-progress failure x{} → salient reframe", fc);
                                            history.addObject().put("role", "user").put("content",
                                                    "STOP — you have edited several times but `" + preview(vc) + "` keeps "
                                                    + "failing with the SAME error. Your changes are not addressing the "
                                                    + "actual cause. Re-read the EXACT error text above, confirm the symbol/"
                                                    + "import/attribute you're relying on actually EXISTS (read the file or "
                                                    + "the dependency source — do not assume), and change your APPROACH. "
                                                    + "Repeating the same fix will not converge.");
                                        }
                                    }
                                }
                            } else {
                                sameFailureCounts.clear();   // a passing build/test = real progress
                            }
                        }
                    }
                    // REPEATED-TIMEOUT (hang) detector. A timeout shows as exit=124 (the model's own
                    // `timeout N ...` killed the inner command) OR our hard-timeout error. Key on the
                    // NORMALIZED command (timeout/env prefix stripped) so a value-bump still counts as the
                    // same hang. SOFT → reframe (it's a deadlock, find the blocking call); HARD → end.
                    if (name.equals("shell")) {
                        boolean timedOut = observation.contains("exit=124")
                                || observation.startsWith("ERROR: command timed out");
                        String tk = normalizeShellCmd(args.path("command").asText(""));
                        if (timedOut && !tk.isBlank()) {
                            int tc = timeoutCounts.merge(tk, 1, Integer::sum);
                            if (tc >= TIMEOUT_HARD) {
                                log.warn("ending run early at turn {}: command keeps hanging (timed out {}×): {}",
                                        turn, tc, preview(tk));
                                return new Result(false, "ended early — `" + preview(tk) + "` hung/timed out "
                                        + tc + "× (a deadlock the model could not diagnose)", turn);
                            }
                            if (tc == TIMEOUT_SOFT && reframedKeys.add("timeout:" + tk)) {
                                if (looksHeavyTraining(tk)) {
                                    // A real train / full-dataset generation step is SLOW, not hung — the opposite of
                                    // deadlock advice. Guide background+poll+subset+batch so it can actually complete.
                                    log.info("  ↳ repeated timeout x{} → heavy-step (train/generate) reframe", tc);
                                    history.addObject().put("role", "user").put("content",
                                            "`" + preview(tk) + "` has timed out " + tc + " times — but a real TRAINING or "
                                            + "full-dataset GENERATION step is SLOW, not hung, so treat it as a long job, not "
                                            + "a deadlock. Run it in the FOREGROUND; heavy steps already get a generous "
                                            + "timeout and the harness cleans them up. To make it FIT the budget: (1) validate "
                                            + "FAST on a SMALL subset (a few rows, 1 epoch) first; (2) SEPARATE training — run "
                                            + "it ONCE and SAVE the model/adapter to disk — from inference that LOADS the saved "
                                            + "adapter, so each run stays cheap; (3) generate over ALL inputs in ONE batched "
                                            + "pass. Keep it foreground — backgrounding a heavy step (`nohup`/`&`) is refused "
                                            + "because a detached run can exhaust the host's memory.");
                                } else {
                                    log.info("  ↳ repeated timeout x{} → hang reframe", tc);
                                    history.addObject().put("role", "user").put("content",
                                            "STOP — `" + preview(tk) + "` has now TIMED OUT " + tc + " times. A command "
                                            + "that times out is HANGING (a deadlock / blocking call), NOT slow — raising "
                                            + "the timeout will not help. Find what blocks: a module-level "
                                            + "`TestClient(app)` or app object that blocks at import, a server started in "
                                            + "the foreground instead of backgrounded, or a command waiting on input. Run "
                                            + "ONLY the piece that hangs in isolation to locate the blocking call and fix "
                                            + "it — do NOT re-run this command as-is.");
                                }
                            }
                        } else if (!tk.isBlank() && observation.contains("exit=0")) {
                            timeoutCounts.remove(tk);   // it succeeds now → clear the hang count for this command
                        }
                        // #1 LSP-after-shell-mutation + #2 import-error pinpoint — surface the precise
                        // pyright/tsserver/… diagnostic the model needs to fix a shell-introduced break.
                        String cmd = args.path("command").asText("");
                        String shellLsp = lspAfterShell(cmd);
                        if (shellLsp.isBlank()) shellLsp = importErrorLsp(observation);
                        if (!shellLsp.isBlank()) observation = observation + "\n\n" + shellLsp;
                    }
                    // Harness-observed working memory: persist/escalate build errors across compaction.
                    memory.observeBuild(observation);
                    if ("read_dep_source".equals(name)) {
                        memory.observeLookup(args.path("dependency").asText(""), observation);
                    }
                    // Per-edit LSP diagnostics (the field's real fix for API-faking), and reset the spin
                    // block-counts: the project just CHANGED, so re-running build/test after an edit is
                    // legitimate iteration, not a spin (only NO-edit repetition is a wedge). reframedKeys +
                    // totalSpinBlocks deliberately survive (the cap/reframe track stuck-ness across the run).
                    if ((name.equals("write_file") || name.equals("edit_file")) && !observation.startsWith("ERROR")) {
                        // Rewrite-loop tracking: consecutive writes to one path (any other tool resets below).
                        String wp = args.path("path").asText("");
                        sameFileWrites = wp.equals(lastWritePath) ? sameFileWrites + 1 : 1;
                        lastWritePath = wp;
                        unbrokenWrites++;
                        writeChainPaths.add(wp);
                        dirtySinceVerify = true; // edits invalidate prior build/test evidence
                        editedSinceSelfVerify = true; // a changed file warrants another self-verify pass
                        callCounts.clear();
                        // WORKING-LOCATION anchor: mechanically derive where the work lives (longest common
                        // dir prefix of all modified files) and persist+pin it via project memory — the
                        // anti-amnesia anchor (a compacted small model migrated its whole app to a second
                        // tree mid-run; this line keeps it oriented). Never left to the summarizer.
                        memory.observeWorkingRoot(commonDirPrefix(filesModified));
                        memory.observeTreeSplit(treeSplitNote(filesModified));
                        String diag = lspCheck(args.path("path").asText(""));
                        if (!diag.isBlank()) observation = observation + "\n\n" + diag;
                        // PATH-SPLICE grounding (battery22: three split-brain runs in one battery —
                        // a nested <rootname>/app beside app/, a hallucinated absolute path materialized
                        // as home/<user>/..., and the real app under a foreign-named subtree beside the
                        // root stub). Field answer = grounding, not interception (SWE-agent state echo;
                        // aider single-namespace): the write LANDS exactly as addressed, the observation
                        // names the fork. Name-shape rules only — no language or fixture knowledge.
                        String splice = spliceNote(wp);
                        if (!splice.isEmpty()) {
                            log.info("  ↳ path-splice note on {}", wp);
                            observation = observation + splice;
                        }
                        String idiom = writeMarkerPush(wp);
                        if (!idiom.isEmpty()) history.addObject().put("role", "user").put("content", idiom);
                    }
                }

                // Sources actually READ (a fetch that errored or was refused taught the run nothing).
                if (gapReflect && name.equals("web_fetch") && !observation.startsWith("ERROR")) {
                    sourcesRead++;
                    sourcesSinceGap++;
                }
                if (name.equals("add_to_answer") && !observation.startsWith("ERROR")) draftUsed = true;
                // SAVE-NUDGE (research, once): a small model ignores a NOVEL tool no matter what the goal
                // says (measured: 109-row task, add_to_answer advertised + instructed, zero calls — then
                // the answer didn't fit in one completion and the run shipped nothing). After the 3rd
                // source read with nothing saved on a table-shaped task, say it at the moment it applies.
                if (researchMode && !draftUsed && !saveNudged && wantsStructured(goal)
                        && name.equals("web_fetch") && !observation.startsWith("ERROR") && ++fetchesUnsaved >= 3) {
                    saveNudged = true;
                    log.info("  ↳ save-nudge: {} sources read, nothing saved", fetchesUnsaved);
                    history.addObject().put("role", "user").put("content",
                            "You have read several sources and SAVED NOTHING. Before fetching anything else, "
                            + "call add_to_answer NOW with the table rows you have already extracted (header "
                            + "first). Saved rows are automatically part of your final answer — without saving, "
                            + "a long table will not fit in your final message and the work is lost. Then "
                            + "continue: read the next source, save its rows, repeat.");
                }

                ObjectNode toolMsg = j.createObjectNode();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", id);
                toolMsg.put("name", name);
                toolMsg.put("content", observation);
                history.add(toolMsg);

                // REWRITE-LOOP escalation: an unbroken chain of writes to ONE file with no build/read/test
                // in between is churn, not progress. Reframe once per path (salient user message), and count
                // each over-threshold write toward the total-spin cap so a hopeless loop ENDS the run early
                // instead of burning every turn (measured: 273 consecutive rewrites of one file).
                if (sameFileWrites >= REWRITE_LOOP_THRESHOLD) {
                    if (++totalSpinBlocks > MAX_TOTAL_SPINS) {
                        log.warn("ending run early at turn {}: rewrite loop on {} ({} consecutive writes, {} total spins)",
                                turn, lastWritePath, sameFileWrites, totalSpinBlocks);
                        return new Result(false, "ended early — stuck rewriting " + lastWritePath
                                + " (" + sameFileWrites + " consecutive writes)", turn);
                    }
                    if (reframedKeys.add("rewrite:" + lastWritePath)) {
                        log.info("  ↳ rewrite loop on {} (x{}) → salient reframe", lastWritePath, sameFileWrites);
                        history.addObject().put("role", "user").put("content",
                                "STOP — you have rewritten `" + lastWritePath + "` " + sameFileWrites + " times in "
                                + "a row without running anything. Rewriting it again will not converge. Run the "
                                + "build/tests NOW (see the PROJECT SHAPE iterate command), read the actual "
                                + "errors, fix only what they name, and then move on to the NEXT file or step.");
                    }
                } else if (unbrokenWrites >= WRITE_CHAIN_THRESHOLD) {
                    // Rotating-set wedge (see field comment). Skipped when the single-path branch above is
                    // already escalating the same chain — one economics track per write, never double-counted.
                    if (++totalSpinBlocks > MAX_TOTAL_SPINS) {
                        log.warn("ending run early at turn {}: write chain across {} paths ({} consecutive writes, {} total spins)",
                                turn, writeChainPaths.size(), unbrokenWrites, totalSpinBlocks);
                        return new Result(false, "ended early — stuck rewriting the same set of files "
                                + writeChainPaths + " (" + unbrokenWrites + " consecutive writes)", turn);
                    }
                    if (unbrokenWrites == WRITE_CHAIN_THRESHOLD) { // reframe once per chain, at the crossing
                        log.info("  ↳ write chain across {} paths (x{}) → salient reframe", writeChainPaths.size(), unbrokenWrites);
                        history.addObject().put("role", "user").put("content",
                                "STOP — you have written files " + unbrokenWrites + " times in a row ("
                                + String.join(", ", writeChainPaths.stream().limit(6).toList())
                                + ") without running anything in between. More rewriting will not converge. Run the "
                                + "build/tests NOW (see the PROJECT SHAPE iterate command), read the actual "
                                + "output, and fix only what it names.");
                    }
                }

                // HONEST FEEDBACK on a vacuous test run, pushed as a USER message (the 9B skims tool
                // results — battery12-n1 received the in-observation note and declared done the next turn;
                // user turns are what actually redirect it, per the measured salience finding). Still
                // informational, never a gate. Sparse: at most twice per run. Two silent-green shapes:
                // (a) zero test SOURCES (gradle NO-SOURCE / pytest "no tests ran" — text-detectable);
                // (b) tests compile but ZERO EXECUTE (e.g. JUnit-5 tests without useJUnitPlatform() —
                //     gradle exits green with no result XML; only the filesystem shows the truth).
                if (name.equals("shell") && vacuousPushes < 2) {
                    String vac = vacuousTestNote(observation);
                    if (vac.isEmpty()) vac = zeroExecutedNote(args.path("command").asText(""), observation, callStart);
                    if (!vac.isEmpty()) {
                        vacuousPushes++;
                        log.info("  ↳ vacuous test run → salient note ({}/2)", vacuousPushes);
                        history.addObject().put("role", "user").put("content", vac);
                    }
                }

                // ONE grounding push per error (sparse — over-injection dilutes a weak model's attention):
                // the real installed dep signature (depSourcePush; library-OFF, version-correct). Only if
                // that didn't fire, fall back to the library doc push (errorReference; library-ON). Never both.
                String nudge = depSourcePush(observation);
                if (nudge.isEmpty()) nudge = errorReference(observation);
                if (!nudge.isEmpty()) history.addObject().put("role", "user").put("content", nudge);

                // Spin reframe (once per stuck key) — the contextual recovery, as a salient USER message.
                if (spinReframe != null) history.addObject().put("role", "user").put("content", spinReframe);

                // GAP REFLECTION (mid-run): every 2 sources read, make the model name what is STILL unsourced
                // and aim the next query at THAT, instead of drifting to whatever the last page mentioned.
                if (gapReflect && sourcesSinceGap >= 2 && gapChecks < MAX_GAP_CHECKS) {
                    gapChecks++;
                    sourcesSinceGap = 0;
                    log.info("  ↳ gap reflection ({}/{}) after {} sources", gapChecks, MAX_GAP_CHECKS, sourcesRead);
                    history.addObject().put("role", "user").put("content",
                            "GAP CHECK (" + sourcesRead + " sources read so far). In 2-3 sentences: which part of the "
                            + "QUESTION do you still have NO fetched source for? Then run ONE web_search aimed "
                            + "squarely at that gap — a different query than the ones you've already run, using the "
                            + "specific terms your sources taught you. Don't re-summarize what you already have.");
                }

                if (TaskDoneTool.NAME.equals(name)) {
                    // CHAT FALSE-WRITE BOUNCE: the summary claims a file was written, and the run's
                    // own ledger says nothing that can write ever ran. Measured 2026-08-29 (plan
                    // flips 4-5, K=3 each): after the chat planning turn the 27B answered "Wrote
                    // PLAN.md with the full plan" having called ONLY task_done — the person would
                    // read a confident claim about a file that does not exist. Machine-computed
                    // evidence outranks the model's account: bounce once with the fact, and let the
                    // model either do the write or drop the claim. Same family as the findings and
                    // artifact bounces below — it asks the model to use the door it was given.
                    if (chatMode && !falseWriteBounced && !mutatingCallRan && turn < maxTurns
                            && observationClaimsWrite(args.path("summary").asText(""))) {
                        falseWriteBounced = true;
                        log.info("  ↳ task_done claims a write; no mutating tool ran → one bounce");
                        history.addObject().put("role", "user").put("content",
                                "Your reply says a file was written, but no file-writing tool ran this "
                                + "turn — the file does not exist. Either create it now with write_file "
                                + "and then call task_done, or call task_done with the reply corrected "
                                + "to not claim a file was written.");
                        break;
                    }
                    // EPILOGUE: the DEADLINE turn's forced task_done arrived without the artifact — the
                    // normal bounce below has no turn left to act in, so the run used to ship a status
                    // description instead of the table (ws_en_028 regression: 40 turns of gathering, zero
                    // table). Grant exactly ONE bonus prose turn to write the answer from what was gathered.
                    if (researchMode && !epilogueGranted && turn >= maxTurns
                            && wantsStructured(goal) && !hasStructured(withDraft(observation))) {
                        epilogueGranted = true;
                        epilogueTurns = 1;
                        proseAnswerNext = true;
                        log.info("  ↳ deadline task_done without the artifact → one epilogue prose turn");
                        history.addObject().put("role", "user").put("content",
                                "Time is up — write your COMPLETE final answer now as plain text. Include, "
                                + "literally, the table/formatted output the question asks for, built from what "
                                + "you actually found; use the question's unavailable-marker (nan / NA / -) for "
                                + "every cell you could not verify, and end with your sources. Do not call any "
                                + "tool.");
                        break;
                    }
                    // DELIVERABLE-IN-ANSWER (research, one-shot, objective): when the QUESTION demands a
                    // structured artifact (a markdown table, a fenced block, CSV/JSON), the answer must
                    // CONTAIN it — not describe it. Observed on WideSearch ws_en_028: the model did the
                    // research, then finished with "The final Markdown table includes all brands…" and no
                    // table. Same failure shape as the echoed ANSWER-line: the deliverable exists in the
                    // model's head, not in the answer. Checked mechanically (does the summary hold a table
                    // row / fence?), bounced ONCE, then whatever comes back is accepted.
                    // (turn < maxTurns: bouncing on the deadline turn would end the run with NO answer —
                    //  a described table beats none.)
                    // The retry is a PROSE turn, not another tool call: the first attempt failed because the
                    // artifact must be written inside task_done's JSON string argument, and a multi-row
                    // markdown table with \n-escaped newlines inside JSON is precisely what a small model
                    // can't produce (measured: bounced with explicit "include the table" instructions, it
                    // described the table again). tool_choice="none" lets it write the table as free text —
                    // the same prose door the planning turn uses — and that prose IS the final answer.
                    // REVIEW analogue of the artifact bounce: task_done arrived without a single finding
                    // having been REPORTED. Measured — offered report_finding alongside the usual tools,
                    // the model went read_file → task_done and described the change in prose instead,
                    // exactly as it did when asked for a text format. Nothing in the loop makes reporting
                    // the way to finish, so the cheapest exit is a summary. One bounce, then whatever comes
                    // back is accepted: this asks the model to use the door it was given, it does not
                    // reject anything it did.
                    if (findingCount.getAsInt() == 0 && !findingsBounced && turn < maxTurns) {
                        findingsBounced = true;
                        log.info("  ↳ task_done with no finding reported → one bounce");
                        history.addObject().put("role", "user").put("content",
                                "You finished without reporting a single finding. A description of what the "
                                + "change does is not a review. If you found defects, call `report_finding` "
                                + "now — once per defect, passing the offending line of code copied exactly "
                                + "as `existing_code` — and then call task_done. If the code really is sound, "
                                + "call task_done again and say so plainly.");
                        break;
                    }
                    if (researchMode && !artifactBounced && turn < maxTurns
                            && wantsStructured(goal) && !hasStructured(withDraft(observation))) {
                        artifactBounced = true;
                        proseAnswerNext = true;
                        log.info("  ↳ task_done without the requested artifact → prose-answer turn (once)");
                        history.addObject().put("role", "user").put("content",
                                "Your answer DESCRIBES the result but does not CONTAIN it. Write your COMPLETE "
                                + "final answer now as plain text — including, literally, the table/formatted "
                                + "output the question asks for (every row, every column, exact format), then "
                                + "your sources. This text will be your final answer; do not call any tool.");
                        break;
                    }
                    // GAP REFLECTION (final, one-shot — the research analogue of the self-verify reflection):
                    // before the answer is accepted, ask once whether the QUESTION is actually covered. The
                    // observed small-model finish is an answer to the facet it happened to land on; one pass
                    // asking "what did the question ask that this doesn't answer" is what turns that into
                    // research. Exactly ONE bounce, and only if it read something — never trap a finish.
                    // turn < maxTurns: bouncing the DEADLINE turn's forced task_done leaves no turn to act
                    // and the run dies answerless (measured: ws_en_018, 40 turns of research, zero output).
                    if (gapReflect && !finalGapChecked && sourcesRead > 0 && turn < maxTurns) {
                        finalGapChecked = true;
                        log.info("  ↳ final gap reflection (once) after {} sources", sourcesRead);
                        history.addObject().put("role", "user").put("content",
                                "Before you finish — ONE gap check. Re-read the QUESTION and name anything it asks "
                                + "that your answer does not yet cover with a source you actually fetched. If there "
                                + "is a real gap and you can close it, run ONE more targeted search/fetch and then "
                                + "call task_done with the improved answer. If there is no real gap, call task_done "
                                + "again now — but say plainly in the answer what remains uncertain or unverified.");
                        break; // one informational bounce; the next task_done ends the run regardless
                    }
                    // KEEP-BEST-GREEN (coding-maintenance runs; ML dev-gated runs have their own artifact keep-best):
                    // the model claims done -- if the harness own test suite is green RIGHT NOW, snapshot this state.
                    // The self-verify reflection below re-engages the model and can thrash a solved task to red (30B M1:
                    // reached 12/12 + task_done, then reflection rounds broke it to an IndentationError). restoreBestGreen()
                    // at exit makes the FINAL on-disk state the best green one. Ground-truth = tests, not self-judgment.
                    if (devAnswersPath == null && ProjectTests.testsGreen(projectRoot)) {
                        String g = snapshotCheckpoint(9000 + turn);
                        if (g != null) { bestGreenCheckpoint = g; bestGreenTurn = turn;
                            log.info("  keep-best-green: tests pass at task_done -> snapshot saved (turn {})", turn); }
                    }
                    // SELF-VERIFICATION REFLECTION (research-backed, positive): before any harness check, ask the
                    // model to RUN its deliverable end-to-end on real inputs and inspect the ACTUAL output — the
                    // one thing that catches a stubbed/hollow last link (its own shape-tests can't). One-shot.
                    String artifactFinding = deliverableArtifactFinding(); // AutoMind filesystem check (objective)
                    String fakeCore = fakeCoreFinding();                    // anti reward-hacking: real work, not stub (objective)
                    String metricFinding = validationMetricFinding();       // AIDE val-metric check (objective)
                    // Priority: completeness (is there output) → realness (is the core real) → quality (beats baseline).
                    // NB: the harness-computed DEV-METRIC check is a separate hard gate BELOW (own bounce budget) so it
                    // isn't starved by these soft rounds sharing one cap (sv13 bug: 3× artifact-gap consumed the cap).
                    String harnessFinding = artifactFinding != null ? artifactFinding
                            : (fakeCore != null ? fakeCore : metricFinding);
                    if (selfVerifyOn && selfVerifyCount < MAX_SELFVERIFY
                            && (selfVerifyCount == 0 || editedSinceSelfVerify || harnessFinding != null)) {
                        selfVerifyCount++;
                        editedSinceSelfVerify = false;
                        String cp = snapshotCheckpoint(selfVerifyCount); // lever-3: protect this pass from a later thrash
                        String tag = artifactFinding != null ? " [artifact gap]"
                                : (fakeCore != null ? " [fake core]" : (metricFinding != null ? " [no val metric]" : ""));
                        log.info("  ↳ self-verify reflection (round {}/{}){}{}", selfVerifyCount, MAX_SELFVERIFY,
                                cp != null ? " [checkpoint saved]" : "", tag);
                        // Objective harness finding FIRST (the model can't rationalize past a missing file / unmeasured metric).
                        String harness = harnessFinding != null ? "HARNESS CHECK — " + harnessFinding + " " : "";
                        // Anti-thrash (rounds 2+): a mid model asked to "re-run everything" tends to re-edit a link
                        // that already works and break it (observed: docs-rag fixed the LLM call but broke retrieval).
                        // Keep verified links; fix only the still-hollow ones.
                        String protect = selfVerifyCount > 1
                                ? "You're iterating — KEEP everything that already produces real output and fix ONLY the "
                                  + "parts whose output is still hollow / placeholder / incomplete; do not re-edit code "
                                  + "that already works. "
                                : "";
                        String restore = cp != null
                                ? "(A snapshot of your code as it is right now is saved at " + cp + " — if a fix "
                                  + "accidentally breaks something that currently works, restore just that file: "
                                  + "`cp " + cp + "/<path> <path>`, or compare with `diff -ru " + cp + " .`.) "
                                : "";
                        history.addObject().put("role", "user").put("content",
                                "You've done the work — VERIFY it before finishing. " + harness + protect + restore
                                + "FIRST — re-read the ORIGINAL task and list EVERY explicit requirement it states: each "
                                + "file and its exact path/name, each exact output format (a date format like YYYY-MM-DD, "
                                + "a JSON key, an exact string a script must print), each permission, each distinct "
                                + "sub-task. For EACH requirement, run a command that checks your ACTUAL result meets it "
                                + "EXACTLY, not approximately — `ls -l`/`stat` for files+permissions, `cat`/`grep` for exact "
                                + "content and formats, and actually RUN any script the task requires and read its real "
                                + "stdout to confirm it matches character-for-character. Most failures are the right "
                                + "approach with ONE exact detail missed (a date in the wrong format, a file at the wrong "
                                + "path, a sub-requirement skipped, a change made to the working tree but not to git "
                                + "history) — this per-requirement check is what catches them; fix any mismatch and re-check "
                                + "that item. THEN, if your deliverable is a data/ML pipeline, also RUN your full "
                                + "deliverable on a few real inputs (boot the service and curl it, or run your script on "
                                + "the real data) and read the ACTUAL output it produces. If you boot a service to check "
                                + "it, start it ONCE in the background and POLL the port until it responds, allowing 30s+ "
                                + "for a model or embeddings to finish loading before deciding it's up. "
                                + "Confirm FOUR things: (1) the output is real and VARIES across different inputs "
                                + "(genuinely correct for the task); (2) your final step (predict / generate / serve / "
                                + "forecast / recommend / tag) returns the real output of the model or pipeline you built; "
                                + "(3) if your deliverable produces one output per input (a prediction / row / answer "
                                + "per input), it COVERS EVERY input — count your output entries and compare to the number "
                                + "of inputs; if you have fewer, you only ran a sample, so loop over ALL inputs and "
                                + "regenerate the full set; and (4) MEASURE it, don't eyeball it: if your training data has "
                                + "LABELS (a supervised task), hold out a small slice as a validation set, run your FINISHED "
                                + "pipeline on it, and compute the ACTUAL metric against the known labels (accuracy / F1 / "
                                + "error) — PRINT the number and confirm it beats a trivial baseline (majority-class, and the "
                                + "base model if the task says to beat one). Output that LOOKS plausible but scores poorly or "
                                + "worse than the baseline is a real BUG, not done — trust the measured number, not that it "
                                + "looks right. If a step still returns fixed or placeholder values, fix it IN "
                                + "your pipeline (not just a one-off check) and RE-RUN to regenerate your COMPLETE output "
                                + "for ALL inputs — your saved deliverable should be the full, real output, not a few "
                                + "spot-checked rows. Seeing real, varied, correct, COMPLETE output from running it "
                                + "yourself is your green light to call task_done.");
                        break;
                    }
                    // ESCALATION (#2 lever): the builder used up its self-verify rounds but is STILL repairing —
                    // i.e. it keeps fixing yet the deliverable isn't producing real output (the hard link it can't
                    // crack alone, like fine-tune's parse robustness). Hand that one link to the 30B localizer
                    // ONCE, using the builder's OWN last end-to-end run as the failing output, and push the
                    // targeted fix. Positive capability hand-off (not a gate); bounded to one; falls through if
                    // the localizer is absent or returns nothing.
                    if (selfVerifyOn && selfVerifyCount >= MAX_SELFVERIFY && editedSinceSelfVerify
                            && localizer.available() && !selfVerifyEscalated) {
                        selfVerifyEscalated = true;
                        editedSinceSelfVerify = false;
                        log.info("  ↳ self-verify stalled at cap (still repairing) → escalating the remaining weak link to the 30B localizer");
                        String diag = localizer.localize(goal, "your own end-to-end run of the finished deliverable",
                                lastShellObs, localizerFiles(6));
                        if (diag != null && !diag.isBlank()) {
                            log.info("  ↳ localizer diagnosis ({} chars) → salient push", diag.length());
                            history.addObject().put("role", "user").put("content",
                                    "A senior debugger read your pipeline and the output of your own end-to-end run and "
                                    + "localized the remaining weak link for you. Apply EXACTLY the fix it names (do not "
                                    + "redesign or start over), then re-run your deliverable end-to-end to confirm the "
                                    + "output is now real and complete, and call task_done:\n\n" + diag);
                            break;
                        }
                    }
                    // DEV-METRIC GATE (the robust anti-fake — harness computes a real metric on a LABELED dev slice;
                    // gated on CODEZAIKU_DEV_ANSWERS → inert for every other fixture). A fabricated/placeholder/constant
                    // pipeline scores ~0 on the hidden dev labels → bounce it back. OWN budget (not the shared self-verify
                    // cap, which sv13 exhausted on artifact-gap). Bounded: after MAX_DEV_BOUNCES it's accepted (never trap
                    // forever — the held-out grader is the final measure). The agent can't fake the number or echo the labels.
                    if (devBounces < MAX_DEV_BOUNCES) {
                        String dev = devAccuracyFinding();
                        if (dev != null) {
                            devBounces++;
                            log.info("  ↳ dev-metric gate: bounce ({}/{}) — {}", devBounces, MAX_DEV_BOUNCES, preview(dev));
                            history.addObject().put("role", "user").put("content", "HARNESS CHECK — " + dev);
                            break;
                        }
                    }
                    // ARTIFACT-PROBE GATE (dev-gated runs only): the dev metric binds to whatever produced
                    // dev_predictions, which for an ARTIFACT fixture can diverge from the artifact's load contract
                    // (RM sv7: dev-acc 1.0 via a CausalLM-loaded side scorer while the saved adapter was unloadable
                    // under the verifier's SEQ_CLS interface). If the fixture ships a label-free probe script next
                    // to the dev answers (artifact_probe.py = "load the artifact exactly as the verifier will"),
                    // run it at task_done and bounce on failure with the real error. Certifies on real execution;
                    // bounded like every other gate (after MAX_PROBE_BOUNCES done is accepted; held-out grader is
                    // still the final measure).
                    if (devAnswersPath != null && probeBounces < MAX_PROBE_BOUNCES) {
                        String probeErr = runArtifactProbe(false);
                        if (probeErr != null) {
                            probeBounces++;
                            log.info("  ↳ artifact-probe gate: bounce ({}/{}) — {}", probeBounces, MAX_PROBE_BOUNCES, preview(probeErr));
                            history.addObject().put("role", "user").put("content",
                                    "HARNESS CHECK — your saved artifact FAILS to load/score under the interface the "
                                    + "verifier uses. This is the real error from loading it exactly as specified in "
                                    + "the goal:\n\n" + probeErr + "\n\nFix the SAVED ARTIFACT (how it is trained/saved"
                                    + " — e.g. the task_type, or the save including every declared module), re-save, "
                                    + "confirm your dev scoring still works, then call task_done again.");
                            break;
                        }
                    }
                    // HARNESS-DRIVEN done gate: the harness is the driver — it does not take the model's word
                    // that it's done; it RE-RUNS the project's tests itself and refuses done while they're red,
                    // pointing the model back to fix them. (Boot-gate + mutation gate follow below; together they
                    // drive the model to a verified-real done: tests pass, not theater, and it boots.) Bounded so
                    // a genuinely-stuck run still ends. The model is the coder; the harness decides when it's real.
                    // EXCEPTION: a dev-gated ML run (CODEZAIKU_DEV_ANSWERS) has a STRONGER measure — the harness-
                    // computed dev metric on hidden labels. The model's OWN unit tests being red is not the real
                    // signal there, and bouncing on them OVERRODE an already-good deliverable and induced a thrash
                    // (sv14 reached a real pass @0.7375/dev 0.819, then the drive-gate sent it to fix tests and it
                    // broad-edited the pipeline to empty). So skip the drive-gate when the dev-gate is the arbiter.
                    if (driveGateOnEnv && driveGate && devAnswersPath == null && driveBounces < MAX_DRIVE_BOUNCES
                            && !ProjectTests.testsGreen(projectRoot)) {
                        driveBounces++;
                        log.info("  ↳ drive: task_done but tests RED → bounce ({}/{})", driveBounces, MAX_DRIVE_BOUNCES);
                        history.addObject().put("role", "user").put("content",
                                "Not done yet — the harness ran your tests and they do NOT pass. Make every test green "
                                + "(fix the code or the test, whichever is wrong), then call task_done again.");
                        break;
                    }
                    // EVIDENCE-FRESHNESS note, ONCE: if files changed since the last verification run, the
                    // model's "tests pass" memory is stale — state that fact and let it re-verify. NOT the
                    // removed test-gate (no oracle runs, no judgment of results, bounded to a single bounce):
                    // a second task_done is accepted unconditionally, fresh or not.
                    if (dirtySinceVerify && !staleNotePushed) {
                        staleNotePushed = true;
                        log.info("  ↳ task_done with stale evidence → freshness note (once)");
                        history.addObject().put("role", "user").put("content",
                                "Before finishing: files have CHANGED since your last build/test run, so that "
                                + "result no longer reflects the current code. Run your build and tests once "
                                + "more, fix anything they name, then call task_done again.");
                        break; // one informational bounce; the next task_done ends the run regardless
                    }
                    // BOOT-GATE: harness-owned end-to-end boot of the assembled app (HTTP archetype only;
                    // TUI/CLI/library SKIP — they get real signal from the model's own build/test loop).
                    // Bounded: at most MAX_BOOT_BOUNCES failures feed the error back, then done is accepted
                    // (honest — never trap a finish forever; the bench/held-out layer still measures truth).
                    if (bootGateOn && bootGate && bootBounces < MAX_BOOT_BOUNCES) {
                        BootCheck.Result br = BootCheck.run(projectRoot, goal);
                        if (br.failed()) {
                            bootBounces++;
                            log.info("  ↳ boot-gate FAIL ({}/{}) → bounce: {}", bootBounces, MAX_BOOT_BOUNCES, br.oneLine());
                            history.addObject().put("role", "user").put("content",
                                    "The harness tried to BOOT your app end-to-end (the real check, not your "
                                    + "in-process tests) and it did NOT come up:\n\n" + br.summary()
                                    + "\n\nA passing unit test does not prove the assembled app starts. Fix the "
                                    + "startup/wiring problem above — make the app actually run and serve — then "
                                    + "call task_done again.");
                            break; // continue the loop so the model can repair the boot failure
                        }
                        if (br.status() == BootCheck.Status.PASS) {
                            log.info("  ↳ boot-gate PASS");
                        }
                    }
                    // MODEL DECIDES DONE — taken at its word (the field norm: model-decides; the harness does
                    // NOT in-loop-gate completion on a build/test reject). Whether it's actually correct is
                    // judged EXTERNALLY by reading the code + running the tests, not by blocking here.
                    restoreBestGreen();      // keep-best-green: a solved-then-thrashed run ships its green state
                    restoreBestArtifact();   // ship the run's BEST adapter, not whatever was last overwritten
                    log.info("task_done at turn {}: {}", turn, observation);
                    return new Result(true, withDraft(observation), turn);
                }
                if (TaskBlockedTool.NAME.equals(name)) {
                    // RESEARCH BLOCKED-BOUNCE (one-shot): in research mode there is nothing to be blocked
                    // ON — no files need writing (the surface is read-only BY DESIGN), no build must pass;
                    // the web tools report their own failures in-band. Observed (WideSearch ws_en_028): the
                    // model fabricated a file-writing requirement the question never made, hit the read-only
                    // shell, and quit — with the research already done. The block is a claim; challenge it
                    // once with the concrete reason it isn't blocked, then accept an insistent second block.
                    if (researchMode && !researchBlockBounced && turn < maxTurns) {
                        researchBlockBounced = true;
                        log.info("  ↳ task_blocked in research mode → bounce (once): nothing to be blocked on");
                        history.addObject().put("role", "user").put("content",
                                "You are NOT blocked. Research needs NO files written and nothing installed — "
                                + "the deliverable is the WRITTEN ANSWER you pass to task_done, nothing else. "
                                + "The read-only shell is intentional and does not stand between you and "
                                + "finishing. Write the complete answer now — including, literally inside it, "
                                + "any table or formatted output the question asks for, plus your sources — "
                                + "and call task_done with it.");
                        break;
                    }
                    // TASK_BLOCKED BOUNCE (dev-gated runs, bounded): two observed premature-quit modes —
                    // (a) a fabricated blocker before any real work (RM sv4: "pip install still running" at
                    // turn 2, nothing was running), (b) an honest give-up on a FIXABLE quality problem with
                    // most of the turn budget unspent (ORPO sv6: "adapter prefers padded" at turn 79/250 —
                    // that's what retraining is FOR). Both are claims, and like task_done claims they get
                    // challenged, not obeyed: bounce with the concrete reason it isn't blocked. Bounded ×2,
                    // then the block is accepted (a genuinely impossible task can still exit honestly).
                    if (devAnswersPath != null && blockedBounces < MAX_BLOCKED_BOUNCES) {
                        boolean noWorkYet = lastShellObs == null || lastShellObs.isBlank();
                        boolean budgetLeft = turn < (int) (maxTurns * 0.8);
                        boolean artifactExists = artifactStamp() != null;
                        if (noWorkYet || (budgetLeft && artifactExists)) {
                            blockedBounces++;
                            log.info("  ↳ task_blocked bounce ({}/{}) at turn {} — {}", blockedBounces,
                                    MAX_BLOCKED_BOUNCES, turn, noWorkYet ? "no work attempted yet" : "budget+artifact remain");
                            history.addObject().put("role", "user").put("content", noWorkYet
                                    ? "You are NOT blocked — no command has actually run yet, so there is nothing "
                                      + "waiting on. Nothing here runs in the background; every shell command has "
                                      + "already finished when you see its output. Start the work now."
                                    : "You are NOT blocked — you have a trained artifact on disk and most of your "
                                      + "turn budget left. A weak/wrong-direction model is EXACTLY what retraining "
                                      + "fixes: retrain with the reference's hyperparameters on the FULL provided "
                                      + "data (fresh run, not resumed), re-save, regenerate dev_predictions, and "
                                      + "only call task_blocked if a genuine external impossibility remains.");
                            break;
                        }
                    }
                    // Honest abort (ImpossibleBench): an explicit blocked exit instead of fake-green. Ends
                    // the run as NOT done, with a distinct status the bench layer can record/continue on.
                    log.info("task_blocked at turn {}: {}", turn, observation);
                    return new Result(false, "task_blocked: " + observation, turn);
                }
            }
            // PROTOCOL REPAIR, one site instead of twenty-eight: every path inside the loop above
            // may interject a user-role message (nudges, reframes, library pushes), and in a
            // PARALLEL tool batch that lands BETWEEN tool responses. llama.cpp tolerates it;
            // Anthropic's compat layer refuses the whole request ("assistant message with
            // 'tool_calls' must be followed by tool messages") — measured 2026-08-29: a 4-write
            // fable-5 turn wedged the run at turn 6 and every later request 400'd on the same
            // history. Stable-partition what the batch appended: tool responses first (their
            // original order), then the interjections (theirs).
            if (history.size() > batchStart) {
                var batch = new java.util.ArrayList<JsonNode>();
                for (int bi = batchStart; bi < history.size(); bi++) batch.add(history.get(bi));
                boolean mixed = false;
                for (int bi = 1; bi < batch.size(); bi++) {
                    if ("tool".equals(batch.get(bi).path("role").asText())
                            && !"tool".equals(batch.get(bi - 1).path("role").asText())) {
                        mixed = true;
                        break;
                    }
                }
                if (mixed) {
                    while (history.size() > batchStart) history.remove(history.size() - 1);
                    for (JsonNode m : batch) if ("tool".equals(m.path("role").asText())) history.add(m);
                    for (JsonNode m : batch) if (!"tool".equals(m.path("role").asText())) history.add(m);
                }
            }
        }
        restoreBestGreen();      // capped run: if it hit green then thrashed, ship the green state
        restoreBestArtifact();   // capped run: still ship the best adapter, not the last thrash
        // A run that saved a draft never returns empty-handed — the accumulated rows ARE an answer,
        // whatever happened to the final turn.
        String finalDraft = answerDraft.get();
        if (finalDraft != null && !finalDraft.isBlank())
            return new Result(true, finalDraft + "\n\n[run ended at the turn limit — the saved draft above "
                    + "is the answer as gathered]", maxTurns);
        return new Result(false, "max turns (" + maxTurns + ") reached without task_done", maxTurns);
        } finally {
            if (lsp != null) lsp.close();
        }
    }

    /** Does this summary tell the person a file was created? Conservative on purpose: a named
     *  file with an extension next to a write-verb. Misses cost nothing (no bounce); false
     *  positives cost one bounce turn. */
    static boolean observationClaimsWrite(String summary) {
        return java.util.regex.Pattern.compile(
                "(?i)\\b(wrote|created|saved|written to|added)\\b[^.\\n]{0,40}?\\b[\\w][\\w./-]*\\.[A-Za-z]{1,6}\\b")
                .matcher(summary).find();
    }

    private String systemPrompt() {
        // In CHAT the mission line changes, and it must change HERE: the opening "make every
        // message a tool call that does real work" out-shouts any later kickoff. Measured
        // 2026-08-29: with only a user-role kickoff amendment, three different models asked to
        // "write ONLY PLAN.md — no code" built and tested the whole service (the battery's plan
        // trio, identical fails on all three). The rules below stay: they govern HOW to act on
        // the turns where acting is what was asked.
        String mission = chatMode
                ? """
                You are a coding familiar in a conversation with a person. This turn's deliverable is
                your REPLY — an answer, a plan, cited findings, or the questions you need answered.
                Match the work to the ask: do exactly what THIS message asks and finish the turn. When
                it asks for a plan or an opinion, produce that and stop — code is written on the turn
                the person asks for it. When it asks you to build or change something, act with tools.
                When it asks a question whose answer needs outside facts, research it: web_search and
                web_fetch for a focused lookup, or delegate with kind=research for a broad question —
                and give your findings WITH the source URLs. When the person says to build what the
                conversation has decided, compose the complete task from the DECISIONS and notes in
                your context — every constraint they stated — and implement it, or delegate it when
                it is large and self-contained. When their request conflicts with a recorded
                decision, a project memory, a working agreement, or evidence you have seen, SAY
                the conflict plainly at the start of your reply — then do what they ask; they
                decide, but never silently. When a durable fact surfaces — a stated preference, a
                trap that cost real time, a decision with lasting scope — offer it to the remember
                tool so future sessions start knowing it.
                """
                : """
                You are a coding familiar. You build and repair real software by calling tools.
                Act with tools — make every message a tool call that does real work on disk or runs a real command.
                """;
        // The rules below are all implementation pressure ("verify your own work", "write the
        // tests"). Right for a goal-shaped run; in chat they must apply only to the turns that
        // ask for code — left unscoped they overrode BOTH mission variants above: K=3 measured,
        // "write ONLY PLAN.md" still produced a tested FastAPI app every time.
        String rulesHeader = chatMode
                ? "\nRules for turns where the person asked you to build or change code (a turn "
                  + "that asks for a plan, an answer or an opinion is finished by REPLYING):\n"
                : "\nRules:\n";
        return mission + rulesHeader + """
                - Use paths RELATIVE to the project root, where the build runs and every shell command
                  starts. Keep the WHOLE app in ONE directory tree and build from there: by default the
                  project root and the standard source layout for your stack. When the spec names a delivery
                  directory (e.g. "write everything to output/<dir>/"), put every file under THAT one
                  directory and run your build/test commands from inside it. One tree.
                - Make ONE concrete change or check per step, then observe the result before the next.
                - Write real, runnable code. Resolve dependencies via the build tool (network is on).
                - Read a file before editing it. Prefer edit_file for small changes; write_file for new files.
                - If unsure of a library's real API, call read_dep_source and implement against the exact
                  installed signatures it shows — use the library's real API in code (rather than recall, or
                  shelling out to a system command for the same result).
                - When you call a method or import a name from one of YOUR OWN modules, use the exact
                  names shown in FILE SIGNATURES below — every method you call and name you import must be
                  one that actually exists there (a call to a nonexistent method crashes at runtime, and in
                  JS/Python nothing warns you until it runs). If STRUCTURE CONFLICTS flags an import/method
                  mismatch, fix it at once.
                - VERIFY YOUR OWN WORK by running the build and tests via shell (e.g. `cargo build` then
                  `cargo test`, or the project's equivalent) and fixing what they report. WRITE the tests
                  the goal asks for — including at least one INTEGRATION test that runs the real pipeline
                  end-to-end (assert it returns REAL records with actual values) — and make them pass.
                - To verify a WEB SERVER / API, exercise the endpoints with an IN-PROCESS integration test
                  (Spring: @SpringBootTest + MockMvc or TestRestTemplate; FastAPI: starlette TestClient;
                  Express: supertest) — that's how to check routes here, since a blocking server curled on
                  localhost can't run in this environment. For a TUI or CLI, confirm it starts with a brief
                  timed run, e.g. `timeout 3 cargo run` (exit 124 = it ran).
                - For a web service, when you call task_done the HARNESS will BOOT the assembled app for
                  real and request `/`. The assembled app must actually start and serve (a booting server is
                  the bar; passing unit tests is the floor): provide ONE real entry point that calls
                  listen()/uvicorn (or a Spring @SpringBootApplication that boots), register the startup
                  wiring (e.g. attach the FastAPI startup hook), and serve a route at `/`. If the harness
                  reports a boot failure, read the error and fix the startup/wiring it names. Wrap any server
                  or long-running process in a timeout (e.g. `timeout 5 ...`) so it returns. Produce REAL
                  computed outputs so every check passes on genuine behavior.
                - MEET EVERY EXPLICIT REQUIREMENT EXACTLY — keep this in mind from the START, not just at the
                  end. Many tasks state precise requirements: a file at an exact path, an exact output format (a
                  date like YYYY-MM-DD, a JSON key, an exact string a script must print), a permission (chmod 600),
                  a sub-task that's easy to skip, or a change that must reach not just the working tree but git
                  history / a persisted store. Before task_done, go requirement-by-requirement and run a command
                  that confirms your ACTUAL result meets EACH one EXACTLY, not approximately — `ls -l`/`stat` for
                  files+permissions, `cat`/`grep` for exact content and format, and actually RUN any required
                  script and read its real stdout to confirm it matches character-for-character. Most failures are
                  the right overall approach with ONE exact detail missed; the per-requirement check catches them.
                - Call task_done when the whole goal is built and your own build + tests pass. If the task
                  truly cannot be completed — the spec contradicts itself, a needed capability is missing,
                  or the build genuinely will not compile after real effort — call task_blocked with the
                  concrete reason. An honest task_blocked report is the right call there; reserve task_done
                  for a goal that is genuinely built and working.
                """
                + specCoverage()
                + planSection()
                + memory.pinned()
                + workedExamples()
                + proactiveIdioms
                + wiringSketch
                + canonicalRootAnchor()
                // Budgeted against the window, not a fixed entry count: this block is rebuilt every
                // turn and compaction never touches it, so on a big repository an unbounded one
                // consumed the context before the task was even read. A fifth of the window is
                // enough to place a file and leaves room for the work.
                + "\n\n" + ProjectShape.render(projectRoot, nctx / 5 * CHARS_PER_TOKEN)
                + "\n\nGOAL:\n" + goal;
    }

    /**
     * ONE-TREE anchor, emitted EVERY turn just above the live structure map (the field's strongest
     * small-model lever for structural consistency: externalize the canonical layout — Aider repo-map —
     * so the 9B writes into the real tree instead of inventing a second one). Names the single root every
     * file must go under (the spec's delivery dir if it names one, else the project root) and the exact
     * split-brain anti-patterns we hit in battery50.
     */
    private String canonicalRootAnchor() {
        Matcher m = Pattern
                .compile("\\boutput/([A-Za-z0-9._-]+)").matcher(goal == null ? "" : goal);
        String root = m.find() ? "`output/" + m.group(1) + "/`" : "the project root";
        return "\n\nONE-TREE RULE (re-read every turn): every file for this project — sources, tests, AND the "
                + "build manifest — lives under a SINGLE root: " + root + ". The PROJECT FILES tree below is "
                + "your REAL project; ADD to that tree, never start a second one. Specifically: do NOT create "
                + "two parallel trees (e.g. files under BOTH `src/…` and `output/<name>/src/…`); do NOT write a "
                + "path that repeats the project's own folder inside itself (e.g. `home/…/<project>/app/x`). If "
                + "STRUCTURE CONFLICTS reports a SPLIT-BRAIN, consolidate to one root before doing anything else.";
    }

    /** The plan re-injected as a SOFT anchor (smallcode formatForPrompt) — orientation, not a gate. */
    private String planSection() {
        if (planSteps.isEmpty()) return "";
        int total = planSteps.size();
        int cur = total;
        for (int i = 0; i < total; i++) if (!completedSteps.contains(i)) { cur = i; break; }
        boolean allDone = completedSteps.size() >= total;
        StringBuilder sb = new StringBuilder(allDone
                ? "\n\nPLAN (all " + total + " steps marked done — verify the whole goal, then call task_done):"
                : "\n\nPLAN (do them ALL before task_done; currently around step " + (cur + 1) + " of " + total + "):");
        for (int i = 0; i < total; i++) {
            String mark = completedSteps.contains(i) ? "✓" : (!allDone && i == cur ? "→" : " ");
            sb.append("\n").append(mark).append(' ').append(i + 1).append(". ").append(planSteps.get(i));
        }
        return sb.append('\n').toString();
    }

    /**
     * Error-driven library push. If {@code observation} is a build/compile/parse error, extract the named
     * symbols, retrieve the current authoritative API (framework-scoped), and format it for injection.
     */
    private String errorReference(String observation) {
        if (maintenanceProject) return ""; // TASK-TYPE gate: no API-fix injection into a pre-existing codebase
        if (index == null || !index.available()) return "";
        if (pushedErrorQueries.size() >= MAX_ERROR_PUSHES) return "";
        String query = ErrorQuery.fromObservation(observation);
        if (query == null || query.isBlank() || !pushedErrorQueries.add(query)) return "";
        var frameworks = LibraryScope.frameworks(projectRoot,
                ProjectFacts.language(projectRoot));
        var chunks = index.search(query, frameworks, 2);
        if (chunks.isEmpty()) return "";
        log.info("  ↳ library push for error symbols [{}] → {} chunks", query, chunks.size());
        StringBuilder sb = new StringBuilder(
                "[API FIX] Your code used a wrong or outdated API. Replace it with the CURRENT signature(s) "
                + "below — do not guess, use exactly these:\n");
        for (var c : chunks) {
            sb.append("• ").append(c.framework()).append(": ").append(firstLines(c.content(), 3)).append('\n');
        }
        sb.append("(If this does not match the actual error, ignore it.)\n");
        return sb.toString();
    }

    private static String firstLines(String s, int n) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        int taken = 0;
        for (String line : s.strip().split("\\R")) {
            String l = line.strip();
            if (l.isEmpty()) continue;
            if (taken > 0) out.append("  ·  ");
            out.append(l);
            if (++taken >= n || out.length() > 240) break;
        }
        return out.length() > 260 ? out.substring(0, 260) + "…" : out.toString();
    }

    private int turnNow = 1; // current loop turn — drives the worked-example collapse below
    private static final int EXAMPLE_FULL_TURNS = 20;
    // RESURFACE the full worked example when the stack is actively failing to build PAST the scaffolding
    // window (battery25: Rust fought version-drift for 73–239 build cycles — far past turn 20 — so the
    // API-shape teaching that names the drift, e.g. sysinfo Disks/Networks split, had collapsed to a title
    // exactly when it was needed). Bounded window so a stuck run doesn't re-pin ~3.3KB forever.
    private int lastBuildErrorTurn = -100;
    private static final int RESURFACE_WINDOW = 6;

    /**
     * Worked examples: FULL text during the scaffolding phase (their measured effects — version pins,
     * test wiring, layer shapes — all land in the first ~20 turns), then COLLAPSED to title lines —
     * EXCEPT briefly resurfaced to full after a build error (drift bites late; that's when the idiom's
     * concrete API shapes earn their context cost).
     * Prompt-weight audit: the full examples are ~3.3KB re-pinned every turn, ≈22% of the pinned block
     * on a 16K-ctx box, and the heavy runs compacted up to 31×/run. The collapse frees that headroom
     * for conversation history on every box — the 16GB-tier alternative to a bigger context window.
     */
    private String workedExamples() {
        String worked = (library == null) ? "" : library.select(projectRoot, goal);
        if (worked.isBlank()) return "";
        boolean resurface = (turnNow - lastBuildErrorTurn) <= RESURFACE_WINDOW;
        if (turnNow <= EXAMPLE_FULL_TURNS || resurface) {
            return "\n\nWORKED EXAMPLES — reference idioms for this stack (adapt names; do not copy verbatim):\n"
                    + worked;
        }
        StringBuilder titles = new StringBuilder();
        for (String line : worked.split("\n")) {
            if (line.startsWith("# ")) titles.append("\n - ").append(line.substring(2).strip());
        }
        return titles.length() == 0 ? ""
                : "\n\nWORKED EXAMPLES (shown in full earlier — keep following their idioms):" + titles;
    }

    // Forgiving tool-call argument parser (smallcode: tolerant parsing is the highest-ROI small-model ACI
    // fix). Strict first (lossless); then progressively forgiving fallbacks.
    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .build();

    private JsonNode parseArgs(String raw) {
        if (raw == null || raw.isBlank()) return j.createObjectNode();
        try {
            return j.readTree(raw);
        } catch (Exception ignored) {
        }
        String s = raw.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.strip();
        }
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a >= 0 && b > a) s = s.substring(a, b + 1);
        try {
            var n = LENIENT.readTree(s);
            log.info("  ↳ recovered malformed tool args via lenient parse");
            return n;
        } catch (Exception ignored) {
        }
        try {
            var asString = j.readTree("\"" + raw.replace("\"", "\\\"") + "\"");
            return LENIENT.readTree(asString.asText());
        } catch (Exception ignored) {
        }
        return j.createObjectNode().put("_parse_error", raw);
    }

    /** Smallest reply worth asking for; below this a turn cannot say anything useful. */
    private static final int MIN_OUTPUT_TOKENS = 512;

    /**
     * Shrink the request until input + a minimum reply fits the window. Returns characters removed.
     *
     * <p>Trims the OLDEST tool observations first and leaves the system prompt and the two most
     * recent messages alone: the old observations are the bulk and the least load-bearing, while the
     * recent ones are what the model is answering. Each trimmed body says it was trimmed, so the
     * model does not read a truncated file as the whole file — a silent cut here would be worse than
     * the overflow, because the model would act on it.
     */
    private int fitToWindow(ArrayNode messages) {
        int limit = nctx - MIN_OUTPUT_TOKENS - 256;
        if (limit <= 0 || estimateTokens(messages) <= limit) return 0;

        String note = "\n...[trimmed to fit the context window — re-read a narrower range if you need more]";
        int removed = 0;
        // Repeat, shrinking harder each pass. One pass is not enough and quietly leaves the request
        // over the window — measured: four observations trimmed to a quarter each still came to 8,384
        // tokens against 8,192, which the server would refuse exactly as before.
        for (int keepChars = 2000; keepChars >= 120 && estimateTokens(messages) > limit; keepChars /= 4) {
            for (int i = 1; i < messages.size() - 2 && estimateTokens(messages) > limit; i++) {
                JsonNode m = messages.get(i);
                if (!m.isObject() || !m.path("content").isTextual()) continue;
                String body = m.path("content").asText();
                if (body.length() <= keepChars + note.length()) continue;   // already at or under
                ((ObjectNode) m).put("content", body.substring(0, keepChars) + note);
                removed += body.length() - keepChars - note.length();
            }
        }
        return removed;
    }

    /** Reserve ~half the window for output, but never let input+output overflow n_ctx. */
    private int outputBudget(ArrayNode messages) {
        int inputTokens = estimateTokens(messages);
        int budget = Math.min(nctx / 2, nctx - inputTokens - 256);
        return Math.max(512, budget);
    }

    private void trackFiles(String tool, JsonNode args) {
        String p = args.path("path").asText("");
        if (p.isBlank()) return;
        // Normalize like PathScope.resolve does (leading "/" is root-relative here) so the tracked lists
        // and the derived WORKING LOCATION are clean relative paths, not "/output/…".
        p = p.replace('\\', '/').strip();
        while (p.startsWith("/")) p = p.substring(1);
        while (p.startsWith("./")) p = p.substring(2);
        if (tool.equals("write_file") || tool.equals("edit_file")) filesModified.add(p);
        else if (tool.equals("read_file")) filesRead.add(p);
    }

    /**
     * Factual note when a test invocation executed ZERO tests — those runs exit green and read as
     * verification when nothing was verified. Cross-stack: gradle NO-SOURCE, pytest "no tests ran",
     * cargo all-sections-zero, node:test pass 0/fail 0. Empty when tests actually ran (or not a test run).
     */
    static String vacuousTestNote(String obs) {
        if (obs == null || obs.isEmpty()) return "";
        boolean vacuous =
                obs.contains("compileTestJava NO-SOURCE") || obs.contains("Task :test NO-SOURCE")
                || obs.contains("no tests ran")
                || (obs.contains("test result:")
                        && !Pattern.compile("running [1-9]\\d* tests?").matcher(obs).find())
                || (obs.contains("# pass 0") && obs.contains("# fail 0"));
        return vacuous
                ? "[note: this test run executed 0 tests — there are no test sources, so nothing was "
                + "verified. Write the tests (including the integration test the goal asks for), then run "
                + "them again.]"
                : "";
    }

    /**
     * Zero-EXECUTED detection for runners that exit green without running anything and without a text
     * marker (gradle/maven: JUnit-5 tests present but no useJUnitPlatform() → the :test task completes,
     * no result XML is written, BUILD SUCCESSFUL). Mechanical filesystem check: a test invocation that
     * "succeeded" must leave a test-results XML fresher than the command start. Empty when not a
     * gradle/maven test invocation, on failure exits (real errors speak for themselves), or when fresh
     * results exist.
     */
    private String zeroExecutedNote(String cmd, String obs, long callStart) {
        if (callStart <= 0 || cmd == null || obs == null) return "";
        String lc = cmd.toLowerCase();
        boolean gradleTest = (lc.contains("gradle") && lc.contains("test")) || (lc.contains("mvn") && lc.contains("test"));
        if (!gradleTest || !obs.startsWith("exit=0")) return "";
        // Skip ONLY the shapes the text-based detector covers. Do NOT bail on any NO-SOURCE: gradle
        // prints "processTestResources NO-SOURCE" in nearly every run, which self-disabled this guard
        // exactly when it was needed (battery14-n2: gradle test green twice, zero executed, no note).
        if (obs.contains("compileTestJava NO-SOURCE") || obs.contains("Task :test NO-SOURCE")) return "";
        try (var walk = Files.walk(projectRoot, 8)) {
            boolean fresh = walk.anyMatch(p -> {
                String s = p.toString();
                if (!s.endsWith(".xml") || !s.contains("test-results")) return false;
                try {
                    return Files.getLastModifiedTime(p).toMillis() >= callStart;
                } catch (Exception e) {
                    return false;
                }
            });
            if (fresh) return "";
        } catch (Exception e) {
            return ""; // can't verify → say nothing
        }
        return "[note: that test task exited green but EXECUTED 0 tests — no test results were produced. "
                + "If your tests are JUnit 5, build.gradle needs `tasks.named('test') { useJUnitPlatform() }` "
                + "or they are silently skipped. Fix the test wiring, rerun, and confirm tests actually ran.]";
    }

    /** Longest common DIRECTORY prefix of the modified files, "" when they live at the project root. */
    static String commonDirPrefix(Collection<String> paths) {
        String common = null;
        for (String p : paths) {
            String dir = p.replace('\\', '/');
            int cut = dir.lastIndexOf('/');
            dir = cut < 0 ? "" : dir.substring(0, cut);
            if (common == null) { common = dir; continue; }
            while (!common.isEmpty() && !(dir.equals(common) || dir.startsWith(common + "/"))) {
                int up = common.lastIndexOf('/');
                common = up < 0 ? "" : common.substring(0, up);
            }
        }
        return common == null ? "" : common;
    }

    // PATH-SPLICE grounding state: note at most once per distinct stray top-level segment, max 3/run
    // (sparse-injection principle — one clear callout, not a nag on every write into the stray tree).
    private final Set<String> splicedPrefixes = new HashSet<>();

    /** A grounding note when a just-written path has the shape of a second parallel tree, else "". */
    private String spliceNote(String wp) {
        String norm = wp.replace('\\', '/').replaceFirst("^\\./", "");
        int slash = norm.indexOf('/');
        if (slash <= 0) return "";
        String first = norm.substring(0, slash);
        String rootName = projectRoot.getFileName().toString();
        String reason = null;
        if (first.equals(rootName)) {
            reason = "its first segment `" + first + "/` repeats this project's own directory name — the "
                    + "shell already runs INSIDE `" + rootName + "`, so this started a NESTED duplicate tree";
        } else if (first.equals("home") || first.equals("Users")) {
            reason = "it looks like an ABSOLUTE path written as a relative one (`" + first + "/...`), which "
                    + "materialized a fake deep tree inside the project";
        } else {
            // Mirror rule: stripping 1-3 leading segments leaves a path that already exists at the
            // project root → this write created a second parallel copy of an existing tree.
            String rest = norm;
            for (int k = 0; k < 3 && reason == null; k++) {
                int s = rest.indexOf('/');
                if (s < 0) break;
                rest = rest.substring(s + 1);
                if (!rest.isEmpty() && Files.exists(projectRoot.resolve(rest))) {
                    reason = "`" + rest + "` ALREADY EXISTS at the project root — this write created a second "
                            + "parallel copy under `" + norm.substring(0, norm.length() - rest.length()) + "`";
                }
            }
        }
        if (reason == null || !splicedPrefixes.add(first) || splicedPrefixes.size() > 3) return "";
        return "\n\nPATH CHECK: the file landed at `" + norm + "` exactly as you addressed it, but " + reason
                + ". All paths here are relative to the project root, where every shell command runs. If "
                + "this was unintended, put the file at its root-level path and remove the stray tree.";
    }

    /** A pinned-memory note when the modified-file set contains two parallel copies of one path, else "". */
    static String treeSplitNote(Collection<String> files) {
        for (String q : files) {
            for (String p : files) {
                if (q.length() > p.length() + 1 && q.endsWith("/" + p)) {
                    String prefix = q.substring(0, q.length() - p.length() - 1);
                    long under = files.stream().filter(f -> f.startsWith(prefix + "/")).count();
                    String dominant = under * 2 > files.size() ? "`" + prefix + "/`" : "the project root";
                    return "TWO PARALLEL TREES: `" + q + "` duplicates `" + p + "`. The dominant tree (most "
                            + "of your files) is " + dominant + " — consolidate ALL files there and do not "
                            + "write to the other copy again.";
                }
            }
        }
        return "";
    }

    // WRITE-MARKER idiom pushes: the worked examples collapse after EXAMPLE_FULL_TURNS, but some concerns
    // are first touched far later (battery22 java-n3 wrote templates/dashboard.html at turn ~150 with the
    // collapsed example and reproduced the controller-less 404 dashboard the full example prevents). The
    // library's push-on-marker design, extended from manifest markers to write-path markers: the moment a
    // matching file SHAPE is written, push the relevant idiom once. Path shapes cover all stacks uniformly.
    private boolean pushedPageIdiom = false, pushedAssertIdiom = false;
    private static final Pattern TEST_FILE = Pattern.compile(
            "(^|/)(test_[^/]+\\.py|[^/]+_test\\.(py|rs|go)|[^/]+\\.(test|spec)\\.[mc]?[jt]s|[^/]*Tests?\\.java|[^/]*test[^/]*\\.gd)$",
            Pattern.CASE_INSENSITIVE);

    private String writeMarkerPush(String wp) {
        if (library == null) return "";
        String norm = wp.replace('\\', '/');
        if (!pushedPageIdiom && norm.matches(".*templates?/[^/]+\\.html?$")) {
            pushedPageIdiom = true;
            String idiom = library.idiom("/library/idiom-page-needs-route.md");
            if (!idiom.isBlank()) {
                log.info("  ↳ write-marker idiom push: page-needs-route");
                return idiom;
            }
        }
        if (!pushedAssertIdiom && turnNow > EXAMPLE_FULL_TURNS && TEST_FILE.matcher(norm).find()) {
            pushedAssertIdiom = true;
            String idiom = library.idiom("/library/idiom-real-assertions.md");
            if (!idiom.isBlank()) {
                log.info("  ↳ write-marker idiom push: real-assertions");
                return idiom;
            }
        }
        return "";
    }

    /**
     * Faithful structured compaction (L1, the #1 cross-harness pattern — pi/opencode): when the one growing
     * conversation nears the window, replace the OLD span with an LLM-written STRUCTURED checkpoint that
     * preserves exact paths/commands/errors and is iteratively updated. File lists tracked MECHANICALLY.
     * Cuts only at a turn boundary. If the summary call fails, degrade to eliding stale tool observations.
     */
    private int ctxHighWater = 0;

    private void compact(ArrayNode history) {
        int sysTokens = systemPrompt().length() / CHARS_PER_TOKEN;
        int used = sysTokens + estimateTokens(history);
        // How close a run actually gets to the compaction threshold. Kept because it answered a
        // question cheaply and will keep answering it: MEASURED on read-heavy coding tasks, the high
        // water mark was 26% of a 32k window, and compaction never fired. Observations are already
        // capped at capture time (ReadFileTool 12k, ShellTool 20k), so this harness BOUNDS context
        // growth rather than letting it grow and compressing later. Compaction is a rare backstop
        // here, not a hot path — which is why the mask-first experiment below stays off: it optimises
        // something that, on these task shapes, does not run.
        int pct = nctx > 0 ? used * 100 / nctx : 0;
        if (pct >= ctxHighWater + 10) {
            ctxHighWater = pct - (pct % 10);
            log.info("ctx high-water: {}% of {} ({} tokens; compaction fires at 70%)", pct, nctx, used);
        }
        if (used < (int) (nctx * 0.70)) return;

        // EXPERIMENT (CODEZAIKU_COMPACT_MASK_FIRST, default off): reclaim context by masking old
        // observations BEFORE paying for a summary. Summarizing costs a blocking model call mid-run
        // and risks a summary that loses the detail it was meant to preserve; masking costs nothing
        // and, on frontier models, published results put it level with summarization on solve rate at
        // roughly half the cost. Whether that holds for a 9B is UNMEASURED — a smaller model may lean
        // harder on verbatim recent detail — so this is off until a controlled flip says otherwise.
        if (Config.isOn("CODEZAIKU_COMPACT_MASK_FIRST", false)) {
            int saved = maskObservations(history, 8);
            if (saved > 0) {
                log.info("mask-first: reclaimed {} chars from old observations", saved);
                if (sysTokens + estimateTokens(history) < (int) (nctx * 0.70)) {
                    log.info("mask-first: under threshold — no summary needed this turn");
                    return;
                }
            }
        }

        int tailBudget = (int) (nctx * 0.30);
        int acc = 0, cut = -1;
        for (int i = history.size() - 1; i >= 1; i--) {
            acc += history.get(i).toString().length() / CHARS_PER_TOKEN;
            if (acc >= tailBudget && "assistant".equals(history.get(i).path("role").asText())) {
                cut = i;
                break;
            }
        }
        if (cut < 2) return;

        ArrayNode oldSpan = j.createArrayNode();
        ArrayNode tail = j.createArrayNode();
        for (int i = 0; i < history.size(); i++) {
            if (i < cut) oldSpan.add(history.get(i));
            else tail.add(history.get(i));
        }

        String summary = summarize(oldSpan);
        if (summary == null) {
            elideFallback(history);
            return;
        }
        checkpoint = summary;
        String wr = commonDirPrefix(filesModified);
        String filesBlock = "\n\n## Files\n- working location: "
                + (filesModified.isEmpty() ? "(none yet)" : (wr.isEmpty() ? "the project root (./)" : wr + "/"))
                + " — continue there; do not start a second copy elsewhere"
                + "\n- modified: " + (filesModified.isEmpty() ? "(none)" : String.join(", ", filesModified))
                + "\n- read: " + (filesRead.isEmpty() ? "(none)" : String.join(", ", filesRead));

        ObjectNode ckpt = j.createObjectNode();
        ckpt.put("role", "user");
        ckpt.put("content", "[EARLIER WORK — compacted checkpoint of the session so far]\n"
                + summary + filesBlock);

        history.removeAll();
        history.add(ckpt);
        history.addAll(tail);
        log.info("compacted: {} old msgs → structured checkpoint ({} chars), kept {} recent msgs",
                oldSpan.size(), summary.length(), tail.size());
        log.info("=== CHECKPOINT ===\n{}{}", summary.length() > 1400 ? summary.substring(0, 1400) + "…" : summary,
                filesBlock);
    }

    /** LLM-summarize the old span into the structured checkpoint, carrying the prior one forward. */
    private String summarize(ArrayNode oldSpan) {
        StringBuilder convo = new StringBuilder();
        for (var m : oldSpan) {
            String role = m.path("role").asText();
            if (m.path("tool_calls").isArray()) {
                for (var c : m.path("tool_calls")) {
                    convo.append(role).append(" → ").append(c.path("function").path("name").asText())
                            .append("(").append(preview(c.path("function").path("arguments").asText("")))
                            .append(")\n");
                }
            }
            String content = m.path("content").asText("");
            if (!content.isBlank()) convo.append(role).append(": ").append(content).append('\n');
        }
        String old = convo.toString();
        if (old.length() > 24000) old = old.substring(old.length() - 24000);

        ArrayNode sm = j.createArrayNode();
        sm.addObject().put("role", "system").put("content", SUMMARIZE_PROMPT);
        sm.addObject().put("role", "user").put("content",
                (checkpoint.isBlank() ? "" : "PRIOR CHECKPOINT (carry forward + update):\n" + checkpoint + "\n\n")
                        + "CONVERSATION TO COMPACT:\n" + old);
        try {
            String s = drive.chat(sm, null, Math.min(nctx / 3, 2048)).path("content").asText("");
            return s.isBlank() ? null : s;
        } catch (Exception e) {
            log.warn("compaction summarize failed: {}", e.getMessage());
            return null;
        }
    }

    private static final int ELIDE_KEEP_TAIL = 6;
    private static final int ELIDE_ARG_MAX = 600;
    private static final int ELIDE_OBS_MAX = 800;

    /**
     * Continuous, lightweight elision: once a file is written it lives ON DISK and its current shape is in
     * the project-shape signatures, so the big write/edit code blob in OLD history is redundant; a bulky
     * build dump is redundant once its errors are captured. Strip those from messages older than the recent
     * tail so the window fills far slower. The model can always re-read a file or re-run a build.
     */
    private void elideRedundant(ArrayNode history) {
        int cutoff = history.size() - ELIDE_KEEP_TAIL;
        for (int i = 0; i < cutoff; i++) {
            if (!history.get(i).isObject()) continue;
            ObjectNode m = (ObjectNode) history.get(i);
            String role = m.path("role").asText();
            if ("assistant".equals(role) && m.path("tool_calls").isArray()) {
                for (var call : m.path("tool_calls")) {
                    String name = call.path("function").path("name").asText();
                    if (!name.equals("write_file") && !name.equals("edit_file")) continue;
                    ObjectNode fn = (ObjectNode) call.path("function");
                    String raw = fn.path("arguments").asText("");
                    if (raw.length() <= ELIDE_ARG_MAX || raw.contains("\"_elided\"")) continue;
                    String path = "";
                    try { path = j.readTree(raw).path("path").asText(""); } catch (Exception ignored) { }
                    fn.put("arguments", "{\"path\":\"" + path + "\",\"_elided\":\"already written; current "
                            + "shape is in PROJECT SHAPE — re-read the file only if you need exact lines\"}");
                }
            } else if ("tool".equals(role)) {
                String c = m.path("content").asText("");
                if (c.length() > ELIDE_OBS_MAX && !c.startsWith("[elided")) {
                    m.put("content", "[elided — large tool output; re-read the file or re-run the command if "
                            + "you need it. Current file shapes are in PROJECT SHAPE.]");
                }
            }
        }
    }

    /** Degrade path: elide stale tool observations in place (keeps decisions + last 8 msgs). */
    /**
     * Shrink old tool results in place, keeping a bounded HEAD and a bounded TAIL.
     *
     * <p>This used to replace the whole result with "[elided]", which threw away the one part the
     * model usually needs: a build or test failure prints its marker at the END. That is the same
     * lesson ShellTool already encodes when it caps output at capture time — head+tail, "never a
     * blind tail" — and this path was contradicting it. A masked result that still shows the command
     * context and the error line is often more useful than a summary of it, and costs no model call.
     *
     * <p>Observations dominate a coding trajectory and are mostly read once, so this is where the
     * context actually goes. The replacement is strictly smaller than the input, so running it twice
     * changes nothing.
     *
     * @return characters reclaimed
     */
    static int maskObservations(ArrayNode history, int keepRecent) {
        int cutoff = history.size() - keepRecent;
        int saved = 0;
        for (int i = 0; i < cutoff; i++) {
            if (!(history.get(i) instanceof ObjectNode m)) continue;
            if (!"tool".equals(m.path("role").asText())) continue;
            String c = m.path("content").asText();
            if (c.length() <= MASK_THRESHOLD || c.startsWith("[older output masked")) continue;
            String masked = c.substring(0, MASK_HEAD)
                    + "\n[older output masked — " + (c.length() - MASK_HEAD - MASK_TAIL)
                    + " chars omitted; re-read or re-run if you need them]\n"
                    + c.substring(c.length() - MASK_TAIL);
            if (masked.length() >= c.length()) continue;   // never grow
            m.put("content", masked);
            saved += c.length() - masked.length();
        }
        return saved;
    }

    // Head is larger than tail because the head carries the command and its first errors, while the
    // tail carries the summary line a runner prints last. Both matter; the middle rarely does.
    private static final int MASK_THRESHOLD = 1_200;
    private static final int MASK_HEAD = 700;
    private static final int MASK_TAIL = 400;

    private void elideFallback(ArrayNode history) {
        maskObservations(history, 8);
    }

    private int estimateTokens(ArrayNode messages) {
        int chars = 0;
        for (var m : messages) chars += m.toString().length();
        return chars / CHARS_PER_TOKEN;
    }

    private static String preview(String s) {
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= 160 ? s : s.substring(0, 160) + "…";
    }

    /**
     * Normalized signature for the spin guard. For shell, collapse to the command's stable PREFIX so
     * near-identical spam is caught — not just byte-identical calls (283× the same curl; `gradle test
     * … | head -N` with a varying tail). Non-shell tools keep their exact-arg key.
     */
    private static String spinKey(String name, JsonNode args, String argsRaw) {
        if (name.equals("shell")) {
            String c = args.path("command").asText("").toLowerCase().replaceAll("\\s+", " ").trim();
            int cut = c.length();
            // Strip only trailing PIPE/REDIRECT noise (`| head -N`, `2>&1`) so near-identical spam shares a
            // key. Do NOT cut at `&&`/`;` — those CHAIN different commands, and `cd X && cmd1` vs
            // `cd X && cmd2` are DIFFERENT actions that must not collapse to one key (that false-blocked the
            // model from running any command in its working dir after 3 tries).
            for (String sep : new String[]{" 2>", " | ", " > ", " >>"}) {
                int i = c.indexOf(sep);
                if (i > 8 && i < cut) cut = i;
            }
            c = c.substring(0, cut);
            return "shell:" + c.substring(0, Math.min(60, c.length()));
        }
        return name + "|" + argsRaw;
    }

    /**
     * A STABLE signature of a build/test FAILURE — the normalized set of its error lines, so the same
     * underlying failure hashes identically across runs while run-to-run noise (timestamps, durations,
     * varying line numbers, absolute paths) is stripped. Used by the no-progress-failure detector to tell
     * "the same failure keeps recurring despite edits" from "a different error each time (= progress)".
     * Returns "" when no error-ish lines are found (don't track a failure we can't characterize).
     */
    private static String failureSignature(String obs) {
        if (obs == null || obs.isBlank()) return "";
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String raw : obs.split("\\R")) {
            String l = raw.toLowerCase().strip();
            if (l.isEmpty()) continue;
            if (!(l.contains("error") || l.contains("fail") || l.contains("exception") || l.contains("expected")
                    || l.contains("assert") || l.contains("cannot") || l.contains("not found") || l.contains("no such")
                    || l.contains("undefined") || l.contains("unresolved") || l.contains("cannot find"))) continue;
            // strip volatile bits: numbers (line/col), hex addrs, and path prefixes — keep the error shape.
            String norm = l.replaceAll("0x[0-9a-f]+", "#").replaceAll("\\d+", "#")
                    .replaceAll("(/[\\w.\\-]+)+", "/P").replaceAll("\\s+", " ").strip();
            if (norm.length() > 8) keys.add(norm);
            if (keys.size() >= 5) break;     // first few error lines pin the failure's identity
        }
        if (keys.isEmpty()) return "";
        return Integer.toHexString(String.join("\n", keys).hashCode());
    }

    /** Normalize a shell command for hang-tracking: drop a leading `timeout [-k DUR] [-s SIG] DURATION`
     *  wrapper and any `env`/`VAR=val` prefix, collapse whitespace, lowercase — so `timeout 30 pytest X`
     *  and `timeout 60 pytest X` map to the SAME key (the exact-arg spin guard misses them). */
    static String normalizeShellCmd(String cmd) {
        if (cmd == null) return "";
        String c = cmd.strip();
        c = c.replaceFirst("^(?:env\\s+)?(?:[A-Za-z_][A-Za-z0-9_]*=\\S*\\s+)*", "");          // env / VAR=val prefix
        c = c.replaceFirst("^timeout\\s+(?:-k\\s+\\S+\\s+)?(?:-s\\s+\\S+\\s+)?\\S+\\s+", "");  // timeout wrapper
        return c.replaceAll("\\s+", " ").toLowerCase().strip();
    }

    private static final String[] SERVER_VERIFY_MARKERS = {
            "curl", "bootrun", "spring-boot:run", "npm start", "npm run dev", "npm run start", "uvicorn",
            "gunicorn", "flask run", "runserver", "rails s", "node server", "node src/server", "node app",
            "node src/app", "next dev", "vite", "http.server", "wget"
    };

    /**
     * Contextual salient REFRAME for a wedged shell spin (smallcode: change the approach, not just refuse),
     * pushed once per stuck key as a USER message — the 9B skims tool-result blocks. Tailored to the wedge:
     * trying to start/curl a live server (→ in-process integration test), re-running a dep install (→ read
     * the error / fix the manifest), or a failing path probe (→ relative paths from the root). Generic otherwise.
     */
    private String spinEscalation(String name, JsonNode args, int n) {
        String lc = name.equals("shell") ? args.path("command").asText("").toLowerCase() : "";
        for (String m : SERVER_VERIFY_MARKERS) if (lc.contains(m)) {
            return "STOP — you've tried to start/reach a live server " + n + "× but you CANNOT run a blocking "
                    + "server in this environment. Verify the endpoints with an IN-PROCESS INTEGRATION TEST "
                    + "instead (Spring: @SpringBootTest + MockMvc; FastAPI: starlette TestClient; Express: "
                    + "supertest), run the test command, and fix the code until it passes.";
        }
        if (lc.contains("pip install") || lc.contains("npm install") || lc.contains("npm ci")
                || lc.contains("cargo add") || lc.contains("cargo fetch") || lc.contains("go get")) {
            return "STOP re-running the dependency install (" + n + "× now). It already ran, or the real error "
                    + "is in the output above — read it and fix the CAUSE (add the package to your manifest, or "
                    + "correct its name), then write code and tests. Do not run the install again.";
        }
        if (lc.contains("/output") || lc.contains("/home/") || lc.startsWith("cat ") || lc.startsWith("ls ")
                || lc.startsWith("find ") || lc.startsWith("cd ")) {
            return "STOP — that command keeps failing (" + n + "× now). Address files with RELATIVE paths from "
                    + "the project root (the shell already runs there); there is no /output directory. The real "
                    + "structure is:\n" + ProjectShape.render(projectRoot) + "Use these relative paths.";
        }
        return "BLOCKED — you've run essentially the same command " + n + "× with no new result. Do something "
                + "DIFFERENT: read the failing file, change the code, write or run a test, or call task_done.";
    }
}
