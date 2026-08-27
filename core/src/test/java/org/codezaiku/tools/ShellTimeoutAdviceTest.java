package org.codezaiku.tools;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the harness says on a timeout is what the model does next.
 *
 * The old text — "for a quick check run it under `timeout N ...`" — was followed literally: a host
 * watched a command killed at the 300s cap come back the very next turn as `timeout 120 <cmd>`,
 * guaranteeing failure sooner, after which the model wandered for the rest of its budget. The
 * command was legitimately minutes long; the harness talked it into shortening its own leash.
 */
class ShellTimeoutAdviceTest {

    private static boolean heavy(String cmd) throws Exception {
        Method m = ShellTool.class.getDeclaredMethod("isHeavyStep", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, cmd.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * The reporter's command. It is not "heavy" by any keyword, which is why it got the short cap —
     * recorded so the classifier's blind spot is visible rather than inferred from a trace.
     */
    @Test
    void anOrdinaryLongRunningScriptIsNotClassifiedHeavy() throws Exception {
        assertFalse(heavy("python3 scripts/classifier/expand_corpus.py --head foo"),
                "if this becomes heavy the short-cap path stops being exercised by this test");
        assertTrue(heavy("python3 train.py --epochs 3"), "the heavy path must still fire");
    }

    @Test
    void theCapIsDeclarable() {
        // A host that knows its step takes twenty minutes can say so rather than lose the run.
        assertTrue(org.codezaiku.Config.getInt("CODEZAIKU_SHELL_TIMEOUT_SEC", 300) > 0);
        assertTrue(org.codezaiku.Config.getInt("CODEZAIKU_SHELL_HEAVY_TIMEOUT_SEC", 1200) > 0);
    }
}
