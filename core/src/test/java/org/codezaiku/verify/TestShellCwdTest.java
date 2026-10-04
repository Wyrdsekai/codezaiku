package org.codezaiku.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test oracle's shell must actually START in the workspace, and the way it gets there matters.
 *
 * It used to `cd` to the workspace inside the command string. On Windows `bash` resolves to
 * C:\Windows\System32\bash.exe — the WSL launcher — whenever WSL is installed, and a Windows-style
 * path does not exist inside WSL, so that `cd` failed with exit 1 and "No such file or directory".
 * That is neither exit 127 nor "command not found", so runnerMissing() did not fire and the oracle
 * returned ran=true with NO counts — reporting a passing suite as testsPassed=0 / status=failed.
 *
 * Setting the directory on the process instead lets the launcher translate it. This pins that: the
 * shell reports the workspace as its working directory, and sees a file that exists only there.
 */
class TestShellCwdTest {

    @TempDir Path work;

    private static Object sh(String cwd, String command) throws Exception {
        Method m = ProjectTests.class.getDeclaredMethod("sh", String.class, String.class);
        m.setAccessible(true);
        return m.invoke(null, cwd, command);
    }

    private static String out(Object sh) throws Exception {
        var f = sh.getClass().getDeclaredField("out");
        f.setAccessible(true);
        return ((String) f.get(sh)).trim();
    }

    private static int exit(Object sh) throws Exception {
        var f = sh.getClass().getDeclaredField("exit");
        f.setAccessible(true);
        return (int) f.get(sh);
    }

    @Test
    void theShellStartsInTheWorkspace() throws Exception {
        Files.writeString(work.resolve("marker.txt"), "here\n");

        Object r = sh(work.toString(), "cat marker.txt");
        assertEquals(0, exit(r), "the shell could not read a file in the workspace it was given");
        assertEquals("here", out(r), "the shell was not running in the workspace");
    }

    /**
     * The tests run inside the project's own environment, where the run installed its packages. They used to run in the person's
     * base environment, and a suite that passes in the project's environment was reported as failing.
     */
    @Test
    void theShellRunsInsideTheProjectsEnvironment() throws Exception {
        Method m = ProjectTests.class.getDeclaredMethod("sh", String.class, String.class, String.class);
        m.setAccessible(true);
        Object r = m.invoke(null, work.toString(), "export CZ_ENV_PROBE=switched-on; ", "echo \"env: $CZ_ENV_PROBE\"");
        assertEquals(0, exit(r));
        assertEquals("env: switched-on", out(r), "the command did not see what the environment's prelude set");
    }

    @Test
    void aFailingRunSaysWhichCommandRanAndKeepsTheEndOfItsOutput() {
        StringBuilder printed = new StringBuilder();
        for (int i = 1; i <= 60; i++) printed.append("line ").append(i).append('\n');
        printed.append("FAILED tests/test_angle.py::test_release - ImportError: numpy.core.multiarray failed to import\n1 failed, 3 passed in 2.1s\n");
        ProjectTests.Run run = new ProjectTests.Run(new ProjectTests.Verdict(true, false, 3, 1), "python3 -m pytest -q", printed.toString());
        String tail = run.outputTail();
        assertTrue(tail.contains("ImportError: numpy.core.multiarray") && tail.endsWith("1 failed, 3 passed in 2.1s"), tail);
        assertTrue(tail.startsWith("line 23") && !tail.contains("line 22\n"), "the last forty lines: " + tail);
        assertEquals("", new ProjectTests.Run(ProjectTests.Verdict.notRun(), "", null).outputTail());
    }

    @Test
    void aWorkspaceThatCannotBeEnteredIsNotAPassingRun() throws Exception {
        Object r = sh(work.resolve("does-not-exist").toString(), "echo reached");
        assertTrue(exit(r) != 0 || !out(r).contains("reached"),
                "a command ran despite the workspace being unreachable — any verdict from that is a phantom");
    }
}
