package org.codezaiku.verify;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test command tries several interpreters in a `||` chain, so the missing ones announce
 * themselves on the way to the one that works. On Windows `python3` does not exist and prints
 * "command not found" immediately before `python` runs the suite successfully.
 *
 * Classifying on that text alone turned a GREEN suite into "no runner installed" — reported as no
 * measurement at all rather than as a pass. A zero exit settles it: something ran this suite.
 */
class RunnerClassifierTest {

    private static boolean call(String name, int exit, String out) throws Exception {
        Method m = ProjectTests.class.getDeclaredMethod(name, int.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, exit, out);
    }

    private static final String WINDOWS_CHAIN_OUTPUT = """
            /usr/bin/bash: line 1: .venv/bin/python: No such file or directory
            /usr/bin/bash: line 1: .venv/Scripts/python.exe: No such file or directory
            /usr/bin/bash: line 1: python3: command not found
            ..                                                                       [100%]
            2 passed in 0.01s
            """;

    @Test
    void aGreenSuiteIsNotAMissingRunnerJustBecauseAFallbackWasAbsent() throws Exception {
        assertFalse(call("runnerMissing", 0, WINDOWS_CHAIN_OUTPUT),
                "a suite that exited 0 ran — earlier fallbacks failing is how the chain works");
        assertFalse(call("noTestsCollected", 0, WINDOWS_CHAIN_OUTPUT),
                "a suite that exited 0 collected tests");
    }

    @Test
    void agenuinelyMissingRunnerIsStillDetected() throws Exception {
        assertTrue(call("runnerMissing", 127, "bash: line 1: python3: command not found"));
        assertTrue(call("runnerMissing", 1, "ModuleNotFoundError: No module named pytest"));
        assertTrue(call("runnerMissing", 1, "'pytest' is not recognized as an internal or external command"));
    }

    @Test
    void anEmptySuiteIsStillDetected() throws Exception {
        assertTrue(call("noTestsCollected", 5, ""));
        assertTrue(call("noTestsCollected", 1, "collected 0 items\nno tests ran in 0.01s"));
    }
}
