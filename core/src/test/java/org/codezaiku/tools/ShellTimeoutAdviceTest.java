package org.codezaiku.tools;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.codezaiku.Config;
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
        return (boolean) m.invoke(null, cmd.toLowerCase(Locale.ROOT));
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

    /**
     * A run wrote `timeout 900 python3 evaluate.py` for a check over forty video clips; the harness killed it at 300 s and told it to
     * run a smaller slice, and it measured one clip at a time for the rest of the run (2026-09-30). The model's own timeout is
     * what the command needs, and the harness now waits that long, up to the heavy cap.
     */
    @Test
    void theModelsOwnTimeoutIsHonouredUpToTheHeavyCap() {
        long dflt = Config.getInt("CODEZAIKU_SHELL_TIMEOUT_SEC", 300), heavy = Config.getInt("CODEZAIKU_SHELL_HEAVY_TIMEOUT_SEC", 1200);
        assertEquals(900, ShellTool.requestedTimeoutSec("timeout 900 python3 evaluate.py 2>/dev/null"));
        assertEquals(600, ShellTool.requestedTimeoutSec("cd sub && timeout -k 5 -s TERM 10m python3 evaluate.py"));
        assertEquals(0, ShellTool.requestedTimeoutSec("python3 evaluate.py --timeout 900"));
        assertEquals(0, ShellTool.requestedTimeoutSec("./timeout_test.sh"));
        assertEquals(910, ShellTool.limitFor("timeout 900 python3 evaluate.py 2>/dev/null"), "the asked time and a little on top, so the model's own timeout fires first");
        assertEquals(heavy, ShellTool.limitFor("timeout 99999 python3 evaluate.py"), "never past the heavy cap");
        assertEquals(dflt, ShellTool.limitFor("python3 evaluate.py"), "no timeout written: the default");
        assertEquals(heavy, ShellTool.limitFor("python3 train.py --epochs 3"), "a training step: the heavy cap, as before");
        assertEquals(dflt, ShellTool.limitFor("timeout 120 python3 probe.py"), "a short timeout fires by itself; it does not shorten the command's own limit");
        assertEquals(dflt, ShellTool.limitFor("cd cache; for f in *.npz; do echo \"$(timeout 20 python ../measure.py $f | tail -1)\"; done"),
                "a timeout around one step of a loop is that step's time, not the loop's");
    }

    @Test
    void theCapIsDeclarable() {
        // A host that knows its step takes twenty minutes can say so rather than lose the run.
        assertTrue(Config.getInt("CODEZAIKU_SHELL_TIMEOUT_SEC", 300) > 0);
        assertTrue(Config.getInt("CODEZAIKU_SHELL_HEAVY_TIMEOUT_SEC", 1200) > 0);
    }
}
