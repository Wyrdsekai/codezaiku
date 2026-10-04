package org.codezaiku.loop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The done check as the model declares it and as the harness judges a run of it. */
class DoneCheckTest {

    private static final String REPLY = """
            PLAN:
            1. Read the data.
            2. Build measure.py.
            3. Build evaluate.py and write RESULTS.md.

            DONE CHECK:
            run: python evaluate.py
            pass: median_abs_err < 5
            pass: measured >= 30
            """;

    @Test
    void theBlockGivesTheCommandAndItsBounds() {
        DoneCheck c = DoneCheck.parse(REPLY);
        assertEquals("python evaluate.py", c.command);
        assertEquals(2, c.conditions.size());
        assertEquals("median_abs_err < 5", c.conditions.get(0).toString());
        assertEquals("measured >= 30", c.conditions.get(1).toString());
        assertTrue(c.pinned(null).contains("run: `python evaluate.py`\n pass when: median_abs_err < 5 and measured >= 30\n not run yet"));
    }

    @Test
    void aTestCommandThatMustExitZeroHasNoBoundsAndNoneMeansNoCheck() {
        DoneCheck tests = DoneCheck.parse("PLAN:\n1. a\n2. b\n\nDONE CHECK:\nrun: `pytest -q`\npass: exit 0\n");
        assertEquals("pytest -q", tests.command);
        assertTrue(tests.conditions.isEmpty());
        assertTrue(tests.pinned(null).contains("pass when: it exits 0"));
        assertNull(DoneCheck.parse("PLAN:\n1. a\n2. b\n\nDONE CHECK:\nrun: none\n"));
        assertNull(DoneCheck.parse("PLAN:\n1. a\n2. b\n"));
        assertNull(DoneCheck.parse(null));
    }

    @Test
    void theOutputIsJudgedByItsLastPrintedNumbersAndTheExitCode() {
        DoneCheck c = DoneCheck.parse(REPLY);
        String out = "clip 1 ... median_abs_err: 12.0\n...\nsummary: measured=38/40 median abs err 3.8 within_5 22\n";
        DoneCheck.Outcome ok = c.judge(0, out, 412, 61);
        assertTrue(ok.passes(), ok.report());
        assertEquals("`python evaluate.py` → exit 0; median_abs_err 3.8 (needs < 5: yes); measured 38 (needs >= 30: yes) — PASSES", ok.report());
        assertEquals(3.8, ok.found().get("median_abs_err"));

        DoneCheck.Outcome worse = c.judge(0, "measured 19 median_abs_err 6.5", 400, 70);
        assertFalse(worse.passes());
        assertEquals("`python evaluate.py` → exit 0; median_abs_err 6.5 (needs < 5: NO); measured 19 (needs >= 30: NO) — does not pass yet", worse.report());

        DoneCheck.Outcome crashed = c.judge(1, "Traceback ...", 3, 71);
        assertFalse(crashed.passes());
        assertTrue(crashed.report().contains("median_abs_err: not printed (the output must show it as `median_abs_err: <number>`)"), crashed.report());

        DoneCheck.Outcome goodNumbersBadExit = c.judge(2, "median_abs_err 3.8 measured 38", 3, 72);
        assertFalse(goodNumbersBadExit.passes(), "a command that fails is not a pass, whatever it printed");
    }

    @Test
    void aBetterRunMeetsMoreBoundsOrIsCloserOnTheFirstAndNothingBeatsAPass() {
        DoneCheck c = DoneCheck.parse(REPLY);
        DoneCheck.Outcome far = c.judge(0, "median_abs_err 30.9 measured 3", 300, 10);
        DoneCheck.Outcome near = c.judge(0, "median_abs_err 5.0 measured 3", 13, 20);
        DoneCheck.Outcome covered = c.judge(0, "median_abs_err 24.3 measured 39", 1000, 30);
        DoneCheck.Outcome crashed = c.judge(1, "Traceback", 2, 40);
        DoneCheck.Outcome pass = c.judge(0, "median_abs_err 3.8 measured 38", 400, 50);
        assertTrue(c.better(near, far), "closer on the first bound, same bounds met");
        assertTrue(c.better(covered, near), "one bound met beats none, whatever the first number");
        assertTrue(c.better(far, crashed) && !c.better(crashed, far), "a failed command is worst");
        assertTrue(c.better(pass, covered) && !c.better(covered, pass));
        assertTrue(c.better(far, null) && !c.better(null, far));
        assertTrue(!c.better(far, far));
    }

    @Test
    void aSecondDeclarationAddsBoundsByNameAndKeepsTheCommand() {
        DoneCheck c = DoneCheck.parse("DONE CHECK:\nrun: python evaluate.py\npass: median_abs_error < 5\n");
        DoneCheck again = DoneCheck.parse("DONE CHECK:\nrun: python other.py\npass: median_abs_error < 4\npass: measured >= 30\n");
        DoneCheck merged = c.withConditionsOf(again);
        assertEquals("python evaluate.py", merged.command);
        assertEquals("median_abs_error < 5 and measured >= 30", String.join(" and ", merged.conditions.stream().map(Object::toString).toList()));
        assertEquals(c.conditions, c.withConditionsOf(null).conditions);
    }

    @Test
    void aResultThatIsNullIsNotReadFromTheNextKeysName() {
        String json = "{\"measured_count\": 0, \"median_abs_error_deg\": null, \"mae_deg\": null, \"within_5deg_count\": 0}";
        assertNull(DoneCheck.numberAfter("median_abs_error_deg", json), "null is no number, and the 5 in within_5deg_count is a name");
        assertEquals(0.0, DoneCheck.numberAfter("measured_count", json));
        assertEquals(0.0, DoneCheck.numberAfter("within_5deg_count", json));
        assertNull(DoneCheck.numberAfter("median_abs_err", "median_abs_err: n/a (target < 5)"), "the target's own number is not a result");
        assertNull(DoneCheck.numberAfter("median_abs_err", "the goal is median_abs_err < 5"));
        assertEquals(3.93, DoneCheck.numberAfter("median_abs_error_deg", "{\"median_abs_error_deg\": 3.93}"));
        assertEquals(3.9, DoneCheck.numberAfter("median absolute error", "| **Median absolute error** | 3.9 |".replace("|", " ")), "a table row without its bars");
        DoneCheck c = DoneCheck.parse("DONE CHECK:\nrun: python evaluate.py\npass: median_abs_error_deg < 5\npass: measured_count >= 32\n");
        DoneCheck.Outcome nothing = c.judge(0, json, 2, 10);
        assertTrue(nothing.report().contains("median_abs_error_deg: not printed"), nothing.report());
        DoneCheck.Outcome real = c.judge(0, "{\"measured_count\": 39, \"median_abs_error_deg\": 24.3}", 900, 20);
        assertTrue(c.better(real, nothing), "a run that measured 39 clips at 24.3 beats one that measured none");
    }

    @Test
    void aNameMatchesWithUnderscoresOrSpacesAndNotInsideAnotherWord() {
        assertEquals(3.8, DoneCheck.numberAfter("median_abs_err", "median abs err = 3.8"));
        assertEquals(3.8, DoneCheck.numberAfter("median abs err", "median_abs_err: 3.8"));
        assertNull(DoneCheck.numberAfter("measured", "unmeasured 7"));
        assertEquals(38.0, DoneCheck.numberAfter("measured", "measured 38 of 40"), "the first number after the name, of the last mention");
    }

    @Test
    void aCheckCanDeclareAPrepareCommand() {
        DoneCheck c = DoneCheck.parse("PLAN:\n1. a\n\nDONE CHECK:\nrun: python evaluate.py\npass: median_abs_err < 5\nprepare: `python prepare.py`\n");
        assertEquals("python prepare.py", c.prepare);
        assertTrue(c.pinned(null, "`prepare.py` is not written yet").contains(" prepare: `python prepare.py` — does the check's slow step once"), c.pinned(null, "x"));
        assertTrue(c.pinned(null, "`prepare.py` is not written yet").endsWith("; `prepare.py` is not written yet\n"));
        assertEquals("python prepare.py", c.withConditionsOf(DoneCheck.parse("DONE CHECK:\nrun: python evaluate.py\npass: measured >= 30\n")).prepare, "a second look at the bounds keeps it");

        assertNull(DoneCheck.parse("DONE CHECK:\nrun: python evaluate.py\npass: x < 5\nprepare: none\n").prepare);
        assertNull(DoneCheck.parse("DONE CHECK:\nrun: python evaluate.py\npass: x < 5\nprepare: <command, or none>\n").prepare, "the format's own placeholder is not a command");
        assertNull(DoneCheck.parse("DONE CHECK:\nrun: python evaluate.py\npass: x < 5\nprepare: python evaluate.py\n").prepare, "the check itself is not its own prepare step");
        assertNull(DoneCheck.parse("DONE CHECK:\nrun: python evaluate.py\npass: x < 5\n").prepare);
        assertFalse(DoneCheck.parse("DONE CHECK:\nrun: python evaluate.py\npass: x < 5\n").pinned(null, null).contains("prepare"));
        assertEquals("python prepare.py", DoneCheck.parse("DONE CHECK:\nrun: python evaluate.py\n").withPrepare("python prepare.py").prepare);
    }
}
