package org.codezaiku.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;
import org.codezaiku.verify.ProjectTests;

/**
 * The single result shape both surfaces emit — the CLI's stdout document and the ACP prompt
 * response's {@code _meta} — so the calling host parses one thing rather than two that drift.
 *
 * <p>Everything a host depends on is decided here, including the two rules agreed with them:
 *
 * <ul>
 *   <li>{@code status} follows {@link RunVerb#statusFor}: {@code success} only behind a real oracle.
 *   <li>{@code gitRef} is never written. The harness authors no commit, so there is no ref of ours to
 *       report. (This is NOT a claim that no commit can happen — a task may ask the model to commit
 *       through the shell, which is correct for standalone use. See docs/public/DEPLOYING_AS_A_BACKEND.md.)
 * </ul>
 */
public final class ResultDocument {

    private static final ObjectMapper J = new ObjectMapper();

    /**
     * The three-way status rule, agreed with the calling host and deliberately not flattering to us.
     *
     * <p>{@code success} requires that a real test oracle RAN and passed, with genuine counts.
     * {@code untested} means the agent finished and nothing verified its claim — the model asserting
     * "all 5 tests pass" in its summary is a claim, not a verification, and promoting it would launder
     * an unverified assertion onto the caller's board. {@code failed} means the oracle failed, or the
     * run did.
     *
     * <p>No oracle runs on the current path, so {@code success} is unreachable today; the rule is
     * written out in full because that is the half a later change would get wrong.
     *
     * @param passed / {@code failed} null when no oracle ran — NOT zero, which would be
     *                indistinguishable from an oracle that ran and found nothing.
     */
    public static String statusFor(boolean done, Integer passed, Integer failed) {
        boolean oracleRan = passed != null && failed != null;
        if (!done) return oracleRan && failed > 0 ? "failed" : "incomplete";
        if (!oracleRan) return "untested";
        if (failed > 0) return "failed";
        return passed > 0 ? "success" : "untested";     // an oracle that ran zero tests verified nothing
    }

    /**
     * The status for a real oracle verdict, which knows things a pair of counts cannot express.
     *
     * <p>The distinction that matters: a suite that RAN AND PASSED but whose output we could not parse
     * is still verified — reporting it as {@code untested} because the numbers are missing would
     * understate a green suite, which is the failure this whole path exists to fix. Counts are a
     * detail of the report; whether an oracle ran and passed is the finding.
     */
    /** Marker a loop puts at the front of its summary when the run could not proceed at all. */
    public static final String UNRECOVERABLE = "context overflow:";

    /**
     * As {@link #statusFor(boolean, ProjectTests.Verdict)}, but a summary naming an unrecoverable
     * setup problem reports {@code failed}. Named rather than overloaded: a third parameter would
     * make {@code statusFor(done, null, null)} ambiguous against the counts form, and the compiler
     * would only say so at the call sites, not here.
     *
     * <p>{@code incomplete} means "ran out of room with real work in {@code files[]}" — a caller may
     * reasonably keep what it got. A request that never fit the model's context window produced
     * nothing and will not on a retry either; calling that {@code incomplete} would invite exactly
     * the wrong response.
     */
    public static String statusForRun(boolean done, ProjectTests.Verdict v, String summary) {
        if (!done && summary != null && summary.startsWith(UNRECOVERABLE)) return "failed";
        return statusFor(done, v);
    }

    public static String statusFor(boolean done, ProjectTests.Verdict v) {
        // A run that stopped because it ran out of TURNS is not a run that failed. It may have
        // produced exactly what was asked and then kept looking for more to do; `files[]` still
        // describes real work. Reporting that as `failed` made a caller ignore the document and read
        // the disk instead — which is the contract failing at its one job. `incomplete` says what
        // actually happened, and only a genuinely red oracle still says `failed`.
        if (!done) return v != null && v.ran() && !v.passed() ? "failed" : "incomplete";
        if (v == null || !v.ran()) return "untested";
        if (!v.passed()) return "failed";
        if (!v.counted()) return "success";             // green, just not countable
        return v.passedCount() != null && v.passedCount() > 0 ? "success" : "untested";
    }

    /**
     * The process exit code for a status, so the two can never disagree.
     *
     * <p>It used to be computed from the loop's {@code done} flag independently of the document, so a
     * run could report work in {@code files[]} and exit 1 at the same time. A caller reasonably reads
     * the exit code as the verdict on the run, and it was reporting the verdict on the loop.
     *
     * <ul>
     *   <li>{@code 0} — {@code success} or {@code untested}: the agent finished; the difference is
     *       only whether an oracle verified it.
     *   <li>{@code 2} — {@code incomplete}: stopped on its turn budget. Distinct on purpose, so a
     *       caller can tell "ran out of room" from "went wrong" without parsing anything.
     *   <li>{@code 1} — {@code failed}, and any error that produced no document at all.
     * </ul>
     */
    public static int exitFor(String status) {
        return switch (status == null ? "failed" : status) {
            case "success", "untested" -> 0;
            case "incomplete" -> 2;
            default -> 1;
        };
    }


    private String taskId;
    private Path workspace;
    private List<String> files = List.of();
    private boolean filesComplete;
    private String filesSource = "ledger";
    private int filesExcluded;
    private String status = "failed";
    private Integer turns;
    private String summary;
    private boolean interrupted;
    private String error;
    private String model;
    private String provider;
    private ProjectTests.Verdict verdict;

    public static ResultDocument of(Path workspace, String status) {
        var r = new ResultDocument();
        r.workspace = workspace;
        r.status = status;
        return r;
    }

    public ResultDocument taskId(String v) { this.taskId = v; return this; }
    public ResultDocument files(List<String> v) { this.files = v == null ? List.of() : v; return this; }
    public ResultDocument filesComplete(boolean v) { this.filesComplete = v; return this; }
    public ResultDocument filesSource(String v) { this.filesSource = v; return this; }

    /** How many installed-dependency paths were held back, so a pruned list never reads as whole. */
    public ResultDocument filesExcluded(int n) { this.filesExcluded = n; return this; }
    public ResultDocument turns(Integer v) { this.turns = v; return this; }
    public ResultDocument summary(String v) { this.summary = v; return this; }
    public ResultDocument interrupted(boolean v) { this.interrupted = v; return this; }
    public ResultDocument error(String v) { this.error = v; return this; }
    public ResultDocument model(String v) { this.model = v; return this; }
    public ResultDocument provider(String v) { this.provider = v; return this; }

    /** The test-oracle verdict, which decides {@code status} and fills the counts. */
    public ResultDocument verdict(ProjectTests.Verdict v) { this.verdict = v; return this; }

    /** Describe which mechanisms produced the file list, so a host knows how far to trust it. */
    public ResultDocument fileProvenance(boolean ledgerComplete, boolean gitAvailable) {
        this.filesComplete = ledgerComplete || gitAvailable;
        this.filesSource = gitAvailable
                ? (ledgerComplete ? "ledger+git" : "ledger+git (shell ran)")
                : (ledgerComplete ? "ledger" : "ledger (shell ran; no git to reconcile)");
        return this;
    }

    public ObjectNode build() {
        ObjectNode d = J.createObjectNode();
        if (taskId != null) d.put("taskId", taskId);
        d.put("workspacePath", workspace == null ? "" : workspace.toString());
        ArrayNode arr = d.putArray("files");
        for (String f : files) arr.add(f);
        d.put("status", status);
        // Counts come from the oracle, never from the model's claim that it finished. `testsCounted`
        // keeps a zero honest: without it a caller cannot tell "no tests ran" from "the runner's output
        // did not report a total", and a host deciding outcomes from test results needs those apart.
        boolean ran = verdict != null && verdict.ran();
        d.put("testsPassed", ran && verdict.passedCount() != null ? verdict.passedCount() : 0);
        d.put("testsFailed", ran && verdict.failedCount() != null ? verdict.failedCount() : 0);
        d.put("testsRan", ran);
        d.put("testsCounted", ran && verdict.counted());

        // gitRef is deliberately absent — see the class note. The host treats its absence as normal.

        d.put("filesComplete", filesComplete);
        d.put("filesSource", filesSource);
        // Present only when something was actually held back. A caller that never installs anything
        // sees the document it saw before; one that did is told, rather than handed a short list that
        // looks complete. Silence here would be the same defect as an unreported cap anywhere else.
        if (filesExcluded > 0) d.put("filesExcluded", filesExcluded);
        if (turns != null) d.put("turns", turns);
        if (summary != null) d.put("summary", summary);
        if (interrupted) d.put("interrupted", true);
        if (error != null) d.put("error", error);
        if (model != null) d.put("model", model);
        if (provider != null) d.put("provider", provider);
        return d;
    }
}
