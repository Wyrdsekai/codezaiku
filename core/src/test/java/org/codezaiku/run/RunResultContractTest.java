package org.codezaiku.run;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two of these encode decisions the calling host made explicitly, not implementation details:
 * a run is never reported as verified unless something verified it, and we never write to git in a
 * workspace the user manages. Both are the kind of thing a later change makes "helpfully" — so they
 * are asserted rather than left as comments.
 */
class RunResultContractTest {

    private static RunVerb.State state(Path ws, String... argv) {
        return new RunVerb.State(RunVerb.Args.parse(argv), ws,
                new PrintStream(new ByteArrayOutputStream()));
    }

    // ---- the honesty rule ----------------------------------------------------------------------

    /** No oracle ran: finishing is not passing, however confident the model's summary was. */
    @Test void anAgentThatFinishedWithNoOracleIsUntestedNotSuccess() {
        assertEquals("untested", ResultDocument.statusFor(true, null, null));
    }

    /**
     * Running out of TURNS is not failing. Such a run may have produced exactly what was asked and
     * then kept looking for more to do, and `files[]` still describes real work — reporting it as
     * `failed` made a caller ignore the document and read the disk instead.
     */
    @Test void aRunThatExhaustedItsTurnBudgetIsIncompleteNotFailed() {
        assertEquals("incomplete", ResultDocument.statusFor(false, null, null));
    }

    /** success requires an oracle that RAN and passed — that is the whole point of the value. */
    @Test void successRequiresAnOracleThatRanAndPassed() {
        assertEquals("success", ResultDocument.statusFor(true, 5, 0));
    }

    @Test void anyFailingTestIsFailedEvenIfTheAgentSaysItFinished() {
        assertEquals("failed", ResultDocument.statusFor(true, 4, 1));
    }

    /** An oracle that executed zero tests verified nothing; it must not read as success. */
    @Test void anOracleThatRanNoTestsIsUntested() {
        assertEquals("untested", ResultDocument.statusFor(true, 0, 0));
    }

    /** Still not `success` — success requires the agent to have finished — but not `failed` either. */
    @Test void aPassingOracleOnAnUnfinishedRunIsIncomplete() {
        assertEquals("incomplete", ResultDocument.statusFor(false, 3, 0));
    }

    /** A RED oracle is a real failure whether or not the budget also ran out. */
    @Test void aFailingOracleOnAnUnfinishedRunIsStillFailed() {
        assertEquals("failed", ResultDocument.statusFor(false, 2, 1));
    }

    /**
     * The exit code is derived from the status so the two cannot disagree. It used to come from the
     * loop's own `done` flag, so a run could name real work in `files[]` and exit 1 at the same
     * time — and a caller reads the exit code as the verdict on the run, not on the loop.
     */
    @Test void theExitCodeAgreesWithTheStatus() {
        assertEquals(0, ResultDocument.exitFor("success"));
        assertEquals(0, ResultDocument.exitFor("untested"));
        assertEquals(2, ResultDocument.exitFor("incomplete"));
        assertEquals(1, ResultDocument.exitFor("failed"));
        assertEquals(1, ResultDocument.exitFor(null));
    }

    /**
     * A request that never fit the window produced nothing and will not on a retry. `incomplete`
     * invites a caller to keep what it got; there is nothing to keep.
     */
    @Test void aRunThatCouldNotProceedAtAllIsFailedNotIncomplete() {
        assertEquals("failed", ResultDocument.statusForRun(
                false, null, ResultDocument.UNRECOVERABLE + " 23461 tokens into a 16384-token window"));
        assertEquals("incomplete", ResultDocument.statusForRun(
                false, null, "max turns (40) reached without task_done"),
                "an ordinary budget exhaustion must still be incomplete");
    }

    /** `incomplete` must be distinguishable from `failed` WITHOUT parsing the document. */
    @Test void exhaustionAndFailureDoNotShareAnExitCode() {
        assertTrue(ResultDocument.exitFor("incomplete") != ResultDocument.exitFor("failed"),
                "a caller has to tell 'ran out of room' from 'went wrong' to decide what to do next");
    }

    /** The live path has no oracle, so it must never emit success today. */
    @Test void theCurrentPathCannotEmitSuccess(@TempDir Path ws) {
        var s = state(ws, "--text", "t");
        JsonNode d = s.document(ResultDocument.statusFor(true, null, null), null, false);
        assertEquals("untested", d.get("status").asText());
        assertEquals(0, d.get("testsPassed").asInt());
        assertEquals(0, d.get("testsFailed").asInt());
    }

    // ---- the consent rule ----------------------------------------------------------------------

    /**
     * We never commit in a workspace the user manages, so gitRef must never appear. Populating it
     * would mean we had touched git state underneath them.
     */
    @Test void neverEmitsGitRef(@TempDir Path ws) {
        for (String status : new String[]{"untested", "failed", "success"}) {
            JsonNode d = state(ws, "--text", "t").document(status, null, false);
            assertFalse(d.has("gitRef"), "gitRef must never be populated (status=" + status + ")");
        }
    }

    @Test void neverEmitsGitRefOnTheInterruptedPathEither(@TempDir Path ws) {
        JsonNode d = state(ws, "--text", "t").document("failed", null, true);
        assertFalse(d.has("gitRef"));
        assertTrue(d.get("interrupted").asBoolean(), "a killed run still says so");
    }

    // ---- the rest of the shape -----------------------------------------------------------------

    @Test void echoesTheTaskIdWhenGiven(@TempDir Path ws) {
        JsonNode d = state(ws, "--text", "t", "--task-id", "wyrd-42").document("untested", null, false);
        assertEquals("wyrd-42", d.get("taskId").asText());
    }

    /** Omitted rather than null — a null taskId is not "their task id echoed back". */
    @Test void omitsTaskIdWhenNotGiven(@TempDir Path ws) {
        assertFalse(state(ws, "--text", "t").document("untested", null, false).has("taskId"));
    }

    @Test void alwaysCarriesTheFieldsTheHostParses(@TempDir Path ws) {
        JsonNode d = state(ws, "--text", "t").document("untested", null, false);
        for (String f : new String[]{"workspacePath", "files", "status", "testsPassed", "testsFailed"}) {
            assertTrue(d.has(f), "missing required field: " + f);
        }
        assertTrue(d.get("files").isArray());
        assertTrue(Path.of(d.get("workspacePath").asText()).isAbsolute(), "workspacePath must be absolute");
    }

    @Test void reportsTheProviderBackWhenOneWasNamed(@TempDir Path ws) {
        JsonNode d = state(ws, "--text", "t", "--provider", "local", "--model", "m9")
                .document("untested", null, false);
        assertEquals("local", d.get("provider").asText());
        assertEquals("m9", d.get("model").asText());
    }

    /** Without a baseline (killed during startup) there is no git delta to claim — say so, don't guess. */
    @Test void degradesHonestlyWhenKilledBeforeTheGitBaseline(@TempDir Path ws) {
        JsonNode d = state(ws, "--text", "t").document("failed", null, true);
        assertFalse(d.get("filesComplete").asBoolean(),
                "no ledger and no baseline means the file list is not authoritative");
        assertTrue(d.get("filesSource").asText().contains("no git"), d.get("filesSource").asText());
    }
}
