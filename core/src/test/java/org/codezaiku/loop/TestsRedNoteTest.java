package org.codezaiku.loop;

import org.codezaiku.verify.ProjectTests;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run sent back because the harness's own run of the tests failed is told which command ran and what it printed. It used to be
 * told only that the tests "do NOT pass": a control run on 2026-10-02 was sent back four times that way, could not see the
 * failure, and said so ("I never saw the failure output").
 */
class TestsRedNoteTest {

    @Test
    void theNoteNamesTheCommandAndShowsTheEndOfTheOutput() {
        ProjectTests.Run red = new ProjectTests.Run(new ProjectTests.Verdict(true, false, 3, 1), "python3 -m pytest -q",
                "tests/test_angle.py ...F\nFAILED tests/test_angle.py::test_release - AssertionError: 41.0 != 39.5\n1 failed, 3 passed in 2.1s\n");
        String note = FamiliarLoop.testsRedNote(red);
        assertTrue(note.contains("`python3 -m pytest -q`") && note.contains("test_release - AssertionError: 41.0 != 39.5") && note.contains("task_done"), note);

        String silent = FamiliarLoop.testsRedNote(new ProjectTests.Run(new ProjectTests.Verdict(true, false, null, null), "cargo test", ""));
        assertTrue(silent.contains("`cargo test`"), silent);
        assertFalse(silent.contains("```"), "no empty block when the tests printed nothing: " + silent);
    }
}
