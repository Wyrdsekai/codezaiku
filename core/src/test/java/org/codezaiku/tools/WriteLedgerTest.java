package org.codezaiku.tools;

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
 * The ledger answers "which files did this run change?" for a host that syncs on the answer, so the
 * failure that matters is a write that happens without being recorded. These drive the real tools
 * rather than calling {@code recordWrite} directly — a test that records by hand would still pass if
 * a tool stopped recording.
 */
class WriteLedgerTest {

    private static final ObjectMapper J = new ObjectMapper();

    private static ObjectNode args(String... kv) {
        ObjectNode n = J.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) n.put(kv[i], kv[i + 1]);
        return n;
    }

    @Test void startsEmpty(@TempDir Path root) {
        var scope = new PathScope(root);
        assertTrue(scope.written().isEmpty());
        assertFalse(scope.mayBeIncomplete(), "nothing has run, so nothing is unaccounted for");
    }

    @Test void writeFileRecordsThePath(@TempDir Path root) throws Exception {
        var scope = new PathScope(root);
        new WriteFileTool(scope).execute(args("path", "app/main.py", "content", "x = 1\n"));
        assertEquals(List.of("app/main.py"), scope.written());
    }

    @Test void editFileRecordsThePath(@TempDir Path root) throws Exception {
        Files.writeString(Files.createDirectories(root.resolve("app")).resolve("m.py"), "a = 1\n");
        var scope = new PathScope(root);
        new EditFileTool(scope, null).execute(
                args("path", "app/m.py", "old_string", "a = 1", "new_string", "a = 2"));
        assertEquals(List.of("app/m.py"), scope.written(), "an edit is a modification and must appear");
    }

    /** Reading is not writing. A read that recorded would inflate the list the host syncs. */
    @Test void readingDoesNotRecordAnything(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("r.txt"), "content\n");
        var scope = new PathScope(root);
        new ReadFileTool(scope).execute(args("path", "r.txt"));
        assertTrue(scope.written().isEmpty(), "read_file must not appear as a change: " + scope.written());
    }

    /** The ledger must record where the write LANDED, not what the model asked for. */
    @Test void recordsTheLandedPathWhenTheWriteWasRedirected(@TempDir Path root) throws Exception {
        var scope = new PathScope(root);
        new WriteFileTool(scope).execute(args("path", "/app/main.py", "content", "x = 1\n"));
        assertEquals(List.of("app/main.py"), scope.written(),
                "the leading slash is stripped on the way in; the ledger must agree with disk");
    }

    @Test void recordsEachDistinctFileOnceInAStableOrder(@TempDir Path root) throws Exception {
        var scope = new PathScope(root);
        var w = new WriteFileTool(scope);
        w.execute(args("path", "z.py", "content", "1"));
        w.execute(args("path", "a.py", "content", "1"));
        w.execute(args("path", "z.py", "content", "2"));      // same file again
        assertEquals(List.of("a.py", "z.py"), scope.written());
    }

    /** A refused write must not be recorded — the file was never created. */
    @Test void doesNotRecordAWriteThatEscapedTheRoot(@TempDir Path root) {
        var scope = new PathScope(root);
        try {
            new WriteFileTool(scope).execute(args("path", "/etc/passwd", "content", "x"));
        } catch (Exception expected) {
            // confinement refuses it
        }
        assertTrue(scope.written().isEmpty(), "a refused write is not a change: " + scope.written());
    }

    // ---- the shell gap -------------------------------------------------------------------------

    /**
     * The shell is unscoped, so a `sed -i` writes a file the ledger cannot see. It must mark the
     * ledger incomplete rather than let the caller present a short list as the whole truth.
     */
    @Test void aFileMutatingShellCommandMarksTheLedgerIncomplete(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("f.txt"), "before\n");
        var scope = new PathScope(root);
        new ShellTool(scope).execute(args("command", "sed -i 's/before/after/' f.txt"));

        assertTrue(scope.mayBeIncomplete(),
                "sed -i wrote a file the ledger never saw — the list is a lower bound");
    }

    /** ...but a command that only reads must NOT, or the signal is noise on every run. */
    @Test void aReadOnlyShellCommandLeavesTheLedgerComplete(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("f.txt"), "hello\n");
        var scope = new PathScope(root);
        var shell = new ShellTool(scope);
        shell.execute(args("command", "cat f.txt"));
        shell.execute(args("command", "grep hello f.txt"));
        shell.execute(args("command", "ls -la"));

        assertFalse(scope.mayBeIncomplete(),
                "reading commands write nothing; flagging them would make the signal useless");
    }

    /** The read-only shell refuses mutating commands outright, so it can never make us incomplete. */
    @Test void theReadOnlyShellNeverMarksTheLedgerIncomplete(@TempDir Path root) throws Exception {
        var scope = new PathScope(root);
        String out = new ShellTool(scope, true).execute(args("command", "rm -rf app/"));
        assertTrue(out.startsWith("REFUSED"), "expected refusal, got: " + out);
        assertFalse(scope.mayBeIncomplete(), "a refused command wrote nothing");
    }

    @Test void recognisesTheCommonMutatingShapes(@TempDir Path root) throws Exception {
        for (String cmd : List.of("rm f.txt", "mv a b", "cp a b", "touch new.py",
                                  "sed -i 's/a/b/' f", "echo x > out.txt", "git checkout .",
                                  "mkdir sub", "chmod +x s.sh")) {
            var scope = new PathScope(root);
            new ShellTool(scope).execute(args("command", cmd));
            assertTrue(scope.mayBeIncomplete(), "should mark incomplete: " + cmd);
        }
    }
}
