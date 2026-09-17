package org.codezaiku.ops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Stdin is fed while the timeout runs and the output drains, not before either has started. */
class ProcsStdinTest {

    @Test
    void aChildThatNeverReadsItsStdinTimesOutInsteadOfHangingTheCaller() {
        String big = "x".repeat(4 * 1024 * 1024);           // far more than a pipe holds
        long t0 = System.nanoTime();
        Exec.Result r = Procs.run(big, 2, "bash", "-c", "sleep 30");
        long s = (System.nanoTime() - t0) / 1_000_000_000L;
        assertEquals(124, r.exit(), r.out());
        assertTrue(s < 15, "returned after " + s + " s");
    }

    @Test
    void aChildThatEchoesALotWhileReadingALotFinishes() {
        String big = "line of text\n".repeat(400_000);      // ~5 MB in, ~5 MB out: both pipes fill unless both sides move
        Exec.Result r = Procs.run(big, 30, "cat");
        assertEquals(0, r.exit());
        assertEquals(big.length(), r.out().length());
    }

    @Test
    void theContainerRunnerHasTheSameTwoProperties() throws Exception {
        var run = org.codezaiku.tools.ContainerExec.class.getDeclaredMethod("run", String.class, int.class, String[].class);
        run.setAccessible(true);
        Object hung = run.invoke(null, "x".repeat(4 * 1024 * 1024), 2, new String[]{"bash", "-c", "sleep 30"});
        assertTrue(hung.toString().contains("exit=124"), "the hung child");
        String big = "line of text\n".repeat(400_000);
        Object ok = run.invoke(null, big, 30, new String[]{"cat"});
        assertTrue(ok.toString().startsWith("Result[exit=0"), "the echoing child");
    }
}
