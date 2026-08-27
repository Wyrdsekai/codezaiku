package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which shell commands get routed to the host's consent flow.
 *
 * <p>Both directions are dangerous in different ways. A false NEGATIVE lets a commit through ungated,
 * which is the whole thing consent existed to prevent. A false POSITIVE stops the file ledger, which
 * depends on read-only git and would then interrupt every run to ask about `git status`.
 */
class GitWriteDetectionTest {

    private static final ObjectMapper J = new ObjectMapper();

    @Test void recognisesCommandsThatWriteGitState() {
        for (String c : List.of(
                "git commit -m 'x'", "git add -A", "git push origin main", "git checkout -b feat",
                "git reset --hard HEAD~1", "git rebase main", "git merge feature", "git stash",
                "git revert abc123", "git cherry-pick abc", "git tag v1", "git branch -D old",
                "git clean -fd", "git restore .", "git switch main", "git apply patch.diff",
                "git rm old.py", "git mv a.py b.py", "git remote add o url", "git init",
                "git config user.name x", "git worktree add ../wt")) {
            assertTrue(ShellTool.isGitWrite(c), "must be gated: " + c);
        }
    }

    /** The file ledger runs `git status`; gating reads would interrupt every single run. */
    @Test void leavesReadOnlyGitAlone() {
        for (String c : List.of(
                "git status --porcelain", "git log --oneline -5", "git diff HEAD",
                "git show abc123", "git rev-parse --show-toplevel", "git rev-list --count HEAD",
                "git blame f.py", "git ls-files", "git describe --tags")) {
            assertFalse(ShellTool.isGitWrite(c), "must NOT be gated: " + c);
        }
    }

    /** A write hidden behind a chain or a global flag is still a write. */
    @Test void seesThroughChainsAndGlobalFlags() {
        assertTrue(ShellTool.isGitWrite("cd sub && git commit -m x"));
        assertTrue(ShellTool.isGitWrite("git -C /some/path commit -m x"));
        assertTrue(ShellTool.isGitWrite("ls && git add -A && echo done"));
        assertTrue(ShellTool.isGitWrite("git --no-pager add ."));
        // Flags that take a VALUE were the gap: `git -C <path> commit` read as ungated.
        assertTrue(ShellTool.isGitWrite("git -c user.name=x commit -m y"));
        assertTrue(ShellTool.isGitWrite("git --git-dir=/r/.git --work-tree=/r commit -m z"));
    }

    @Test void ignoresNonGitCommandsAndNulls() {
        assertFalse(ShellTool.isGitWrite("pytest -q"));
        assertFalse(ShellTool.isGitWrite("echo 'git commit is a thing'".replace("git commit", "gitc")));
        assertFalse(ShellTool.isGitWrite(null));
        assertFalse(ShellTool.isGitWrite(""));
    }

    // ---- the veto path -------------------------------------------------------------------------

    /** A listener denial must PREVENT execution, and reach the model as an observation. */
    @Test void aDenialPreventsTheToolFromRunning(@TempDir Path root) {
        var scope = new PathScope(root);
        var reg = ToolRegistry.standard(root).listener(new ToolRegistry.Listener() {
            @Override public String permit(String tool, JsonNode a) {
                return "REFUSED: not permitted";
            }
            @Override public void started(String id, String tool, JsonNode a) { }
            @Override public void finished(String id, String tool, String r, boolean f) { }
        });

        ObjectNode args = J.createObjectNode().put("path", "x.py").put("content", "should not exist");
        String out = reg.execute("write_file", args);

        assertEquals("REFUSED: not permitted", out, "the denial is the tool result");
        assertFalse(Files.exists(root.resolve("x.py")),
                "a denied tool must not have run");
    }

    /** With no listener — the standalone CLI — nothing is gated. */
    @Test void nothingIsGatedWithoutAListener(@TempDir Path root) {
        var reg = ToolRegistry.standard(root);
        reg.execute("write_file", J.createObjectNode().put("path", "y.py").put("content", "ok"));
        assertTrue(Files.exists(root.resolve("y.py")),
                "standalone must be unaffected by the consent machinery");
    }

    /** A gate that throws must not take the run down with it. */
    @Test void aBrokenGateDoesNotBlockTheRun(@TempDir Path root) {
        var reg = ToolRegistry.standard(root).listener(new ToolRegistry.Listener() {
            @Override public String permit(String tool, JsonNode a) {
                throw new IllegalStateException("gate exploded");
            }
            @Override public void started(String id, String tool, JsonNode a) { }
            @Override public void finished(String id, String tool, String r, boolean f) { }
        });
        reg.execute("write_file", J.createObjectNode().put("path", "z.py").put("content", "ok"));
        assertTrue(Files.exists(root.resolve("z.py")));
    }
}
