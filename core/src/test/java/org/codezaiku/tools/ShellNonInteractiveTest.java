package org.codezaiku.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** A command the model runs never waits for a person, and its git looks at the workspace. */
class ShellNonInteractiveTest {

    static int run(Path dir, String cmd, StringBuilder out) throws Exception {
        var pb = new ProcessBuilder("bash", "-c", cmd).directory(dir.toFile()).redirectErrorStream(true);
        ShellTool.nonInteractive(pb.environment());
        pb.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        Process p = pb.start();
        p.getOutputStream().close();
        var drain = new Thread(() -> { try { out.append(new String(p.getInputStream().readAllBytes())); } catch (Exception ignored) { } });
        drain.start();
        if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); return 124; }
        drain.join(2000);
        return p.exitValue();
    }

    @Test
    void aCommitWithNoMessageFailsAtOnceInsteadOfOpeningAnEditor(@TempDir Path repo) throws Exception {
        var out = new StringBuilder();
        assertEquals(0, run(repo, "git init -q && git config user.email t@example.com && git config user.name t && echo a > a.txt && git add a.txt", out), out.toString());
        long t0 = System.nanoTime();
        int rc = run(repo, "git commit", out);
        assertNotEquals(124, rc, "it did not wait for an editor");
        assertNotEquals(0, rc, out.toString());
        assertTrue((System.nanoTime() - t0) / 1_000_000_000L < 10);
        assertEquals(0, run(repo, "git commit -qm 'with a message'", out), out.toString());
    }

    @Test
    void anInheritedGitDirIsRemovedAndTheEditorsAreSet() {
        var env = new HashMap<String, String>();
        env.put("GIT_DIR", "/somewhere/else/.git"); env.put("GIT_WORK_TREE", "/somewhere/else"); env.put("GIT_INDEX_FILE", "/x"); env.put("EDITOR", "vim");
        ShellTool.nonInteractive(env);
        assertFalse(env.containsKey("GIT_DIR") || env.containsKey("GIT_WORK_TREE") || env.containsKey("GIT_INDEX_FILE"));
        assertEquals("true", env.get("EDITOR")); assertEquals("true", env.get("GIT_EDITOR")); assertEquals("true", env.get("GIT_SEQUENCE_EDITOR"));
        assertEquals("never", env.get("SSH_ASKPASS_REQUIRE")); assertEquals("0", env.get("GIT_TERMINAL_PROMPT"));
    }
}
