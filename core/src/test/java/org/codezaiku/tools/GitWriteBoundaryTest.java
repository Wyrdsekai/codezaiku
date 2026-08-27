package org.codezaiku.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where committing is actually possible, as opposed to where we merely decline to report one.
 *
 * <p>The distinction matters and is easy to get wrong: the HARNESS never runs a git write command,
 * and the {@code run} verb never reports a {@code gitRef}. Neither of those stops the MODEL from
 * running {@code git commit} through the shell when a task asks it to — and on the standalone coding
 * path that is legitimate, so it is allowed. A host that needs committing PREVENTED needs a guard;
 * these tests pin what today's boundary really is so nobody reads "we never commit" too broadly.
 */
class GitWriteBoundaryTest {

    private static final ObjectMapper J = new ObjectMapper();

    private static ObjectNode cmd(String c) {
        ObjectNode n = J.createObjectNode();
        n.put("command", c);
        return n;
    }

    private static void git(Path dir, String... args) throws Exception {
        var c = new ArrayList<String>(List.of("git"));
        c.addAll(List.of(args));
        var p = new ProcessBuilder(c).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
    }

    private static Path repo(Path root) throws Exception {
        git(root, "init", "-q");
        git(root, "config", "user.email", "t@example.com");
        git(root, "config", "user.name", "t");
        Files.writeString(root.resolve("a.txt"), "one\n");
        git(root, "add", "-A");
        git(root, "commit", "-qm", "base");
        return root;
    }

    private static String headCount(Path dir) throws Exception {
        var p = new ProcessBuilder("git", "rev-list", "--count", "HEAD")
                .directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return out;
    }

    /**
     * The standalone coding path CAN commit — a user asking CodeZaiku to commit its work should get a
     * commit. A host that requires the human to own commits needs a rule that is an exception to this,
     * not the norm.
     */
    @Test void theStandardShellPermitsACommit(@TempDir Path root) throws Exception {
        repo(root);
        assertEquals("1", headCount(root));
        Files.writeString(root.resolve("b.txt"), "two\n");

        var scope = new PathScope(root);
        new ShellTool(scope).execute(cmd("git add -A && git commit -qm 'agent commit'"));

        assertEquals("2", headCount(root), "the standard shell must be able to commit when asked");
        assertTrue(scope.mayBeIncomplete(), "a git write is a mutation the ledger cannot see");
    }

    /** The read-only surface (review / investigate) refuses it, as it refuses every mutation. */
    @Test void theReadOnlyShellRefusesACommit(@TempDir Path root) throws Exception {
        repo(root);
        var scope = new PathScope(root);
        String out = new ShellTool(scope, true).execute(cmd("git commit -am 'nope'"));

        assertTrue(out.startsWith("REFUSED"), "expected refusal, got: " + out);
        assertEquals("1", headCount(root), "and no commit was actually made");
        assertFalse(scope.mayBeIncomplete());
    }

    /** Whether the agent committed or not, the harness itself never authors a commit. */
    @Test void theHarnessNeverCommitsOnItsOwn(@TempDir Path root) throws Exception {
        repo(root);
        var scope = new PathScope(root);
        new WriteFileTool(scope).execute(
                J.createObjectNode().put("path", "c.txt").put("content", "three\n"));

        assertEquals("1", headCount(root), "writing a file must never imply committing it");
    }
}
