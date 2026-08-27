package org.codezaiku.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Counting is the half a host's forge recipes decide on, so the failure that matters is UNDER-counting
 * a green suite — reporting zero passed for a suite that passed reads as "nothing was verified" and
 * starves whatever consumes it. Null must mean "could not tell" and never collapse into zero.
 */
class TestCountsTest {

    @Test void readsPytest() {
        assertArrayEquals(new int[]{2, 0}, TestCounts.parse(
                "===== test session starts =====\ncollected 2 items\n\n===== 2 passed in 0.01s ====="));
    }

    @Test void readsPytestWithFailures() {
        assertArrayEquals(new int[]{2, 1}, TestCounts.parse("===== 1 failed, 2 passed in 0.04s ====="));
    }

    @Test void readsPytestErrorsAsFailures() {
        assertArrayEquals(new int[]{0, 3}, TestCounts.parse("===== 3 errors in 0.1s ====="));
    }

    /** Totals come last; an earlier line may be a per-file tally or a fixture quoting the phrase. */
    @Test void takesTheFinalSummaryNotTheFirstMatch() {
        assertArrayEquals(new int[]{9, 0}, TestCounts.parse(
                "test_a.py 1 passed\ntest_b.py 3 passed\n===== 9 passed in 1.2s ====="));
    }

    @Test void readsUnittest() {
        assertArrayEquals(new int[]{5, 0}, TestCounts.parse("Ran 5 tests in 0.001s\n\nOK"));
        assertArrayEquals(new int[]{3, 2}, TestCounts.parse(
                "Ran 5 tests in 0.01s\n\nFAILED (failures=2)"));
        assertArrayEquals(new int[]{2, 3}, TestCounts.parse(
                "Ran 5 tests in 0.01s\n\nFAILED (failures=2, errors=1)"));
    }

    @Test void readsCargo() {
        assertArrayEquals(new int[]{7, 0}, TestCounts.parse(
                "test result: ok. 7 passed; 0 failed; 0 ignored; 0 measured"));
        assertArrayEquals(new int[]{5, 2}, TestCounts.parse(
                "test result: FAILED. 5 passed; 2 failed; 0 ignored"));
    }

    @Test void readsJest() {
        assertArrayEquals(new int[]{2, 1}, TestCounts.parse(
                "Test Suites: 1 failed, 1 total\nTests:       1 failed, 2 passed, 3 total\n"));
    }

    @Test void readsMocha() {
        assertArrayEquals(new int[]{4, 0}, TestCounts.parse("  4 passing (12ms)"));
        assertArrayEquals(new int[]{3, 1}, TestCounts.parse("  3 passing (9ms)\n  1 failing\n"));
    }

    @Test void readsGoTest() {
        assertArrayEquals(new int[]{2, 1}, TestCounts.parse(
                "--- PASS: TestA (0.00s)\n--- FAIL: TestB (0.00s)\n--- PASS: TestC (0.00s)\nFAIL"));
    }

    /** Null, never {0,0}: a caller must be able to tell "could not parse" from "zero tests". */
    @Test void returnsNullWhenNothingIsRecognisable() {
        assertNull(TestCounts.parse("Build succeeded.\nDone in 3s."));
        assertNull(TestCounts.parse(""));
        assertNull(TestCounts.parse(null));
    }

    // ---- JUnit XML (gradle / maven print failures but not a pass total) -------------------------

    @Test void readsJUnitXmlFromGradle(@TempDir Path work) throws Exception {
        Path d = Files.createDirectories(work.resolve("build/test-results/test"));
        Files.writeString(d.resolve("TEST-a.xml"),
                "<?xml version=\"1.0\"?>\n<testsuite name=\"a\" tests=\"4\" failures=\"1\" errors=\"0\">");
        Files.writeString(d.resolve("TEST-b.xml"),
                "<?xml version=\"1.0\"?>\n<testsuite name=\"b\" tests=\"3\" failures=\"0\" errors=\"0\">");
        assertArrayEquals(new int[]{6, 1}, TestCounts.fromJUnitXml(work));
    }

    @Test void readsJUnitXmlFromMavenSurefire(@TempDir Path work) throws Exception {
        Path d = Files.createDirectories(work.resolve("target/surefire-reports"));
        Files.writeString(d.resolve("TEST-x.xml"),
                "<testsuite name=\"x\" tests=\"5\" failures=\"0\" errors=\"2\">");
        assertArrayEquals(new int[]{3, 2}, TestCounts.fromJUnitXml(work));
    }

    @Test void junitXmlIsNullWhenThereAreNoReports(@TempDir Path work) {
        assertNull(TestCounts.fromJUnitXml(work));
    }

    // ---- the verdict contract ------------------------------------------------------------------

    /** No test command in the stack is NOT a green suite — that conflation was the reported bug. */
    @Test void noRunnerReportsNotRunRatherThanPassed(@TempDir Path empty) {
        ProjectTests.Verdict v = ProjectTests.verdict(empty);
        assertTrue(!v.ran(), "an empty dir has no runner, so nothing ran");
        assertNull(v.passedCount());
        assertTrue(ProjectTests.testsGreen(empty),
                "the in-loop check keeps its permissive meaning: unmeasurable does not block");
    }

    @Test void countedIsFalseWhenTheRunnerGaveNoTotals() {
        assertTrue(!ProjectTests.Verdict.notRun().counted());
        assertTrue(!new ProjectTests.Verdict(true, true, null, null).counted());
        assertTrue(new ProjectTests.Verdict(true, true, 3, 0).counted());
    }

    /**
     * A suite that collected NOTHING did not fail — it did not run. pytest exits 5 for "no tests
     * collected", and reading any non-zero as failure made a project with a manifest and no tests
     * report `failed`, which a host deciding outcomes from test results would act on. Measured.
     */
    @Test void noTestsCollectedIsNotRunRatherThanFailed() {
        assertTrue(ProjectTests.noTestsCollected(5, ""), "pytest exit 5 = nothing collected");
        assertTrue(ProjectTests.noTestsCollected(1, "===== no tests ran in 0.01s ====="));
        assertTrue(ProjectTests.noTestsCollected(1, "collected 0 items"));
        assertTrue(ProjectTests.noTestsCollected(4, "No tests found"));
    }

    /** ...but a suite that ran and genuinely failed must still read as a failure. */
    @Test void arealFailureIsNotMistakenForAnEmptySuite() {
        assertTrue(!ProjectTests.noTestsCollected(1, "===== 1 failed, 2 passed in 0.04s ====="));
        assertTrue(!ProjectTests.noTestsCollected(0, "===== 3 passed in 0.02s ====="));
        assertTrue(!ProjectTests.noTestsCollected(1, "collected 4 items\n1 failed"));
    }

    /**
     * An absent runner is not a failing suite. A stock macOS box has no pytest — measured — so without
     * this every python project there reported red, and the same holds for any host that never
     * installed the runner.
     */
    @Test void anAbsentRunnerIsNotRunRatherThanFailed() {
        assertTrue(ProjectTests.runnerMissing(1, "python3: No module named pytest"));
        assertTrue(ProjectTests.runnerMissing(127, ""), "127 = shell could not find the command");
        assertTrue(ProjectTests.runnerMissing(1, "bash: pytest: command not found"));
    }

    /** ...and a genuinely red suite must not be excused as a missing runner. */
    @Test void arealFailureIsNotMistakenForAnAbsentRunner() {
        assertTrue(!ProjectTests.runnerMissing(1, "===== 1 failed, 2 passed in 0.04s ====="));
        assertTrue(!ProjectTests.runnerMissing(1,
                "E   FileNotFoundError: [Errno 2] No such file or directory: 'fixture.json'"),
                "a test failing on a missing FIXTURE is still a failure");
    }

    @Test void notRunIsNeverReportedAsPassed() {
        assertEquals(false, ProjectTests.Verdict.notRun().passed());
    }
}
