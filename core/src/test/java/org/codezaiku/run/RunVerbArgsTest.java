package org.codezaiku.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The argv shape is a contract with a calling host: it builds this command line, and a parse that
 * quietly drops a flag changes which model runs or where the output goes. These pin the exact form
 * the host emits.
 */
class RunVerbArgsTest {

    /** The literal command line the host builds. */
    @Test void parsesTheContractCommandLine() {
        var a = RunVerb.Args.parse(new String[]{
                "--text", "fix the flaky test", "--output-format", "json", "--no-session", "-q",
                "--provider", "local", "--model", "coder-9b"});
        assertEquals("fix the flaky test", a.text);
        assertTrue(a.json, "--output-format json must select the JSON document");
        assertTrue(a.quiet);
        assertEquals("local", a.provider);
        assertEquals("coder-9b", a.model);
    }

    @Test void requiresText() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> RunVerb.Args.parse(new String[]{"--output-format", "json"}));
        assertTrue(e.getMessage().contains("--text"), e.getMessage());
    }

    @Test void rejectsAFlagMissingItsValue() {
        assertThrows(IllegalArgumentException.class,
                () -> RunVerb.Args.parse(new String[]{"--text"}));
    }

    /** A newer host may pass flags we do not know; failing the whole run over one would be worse. */
    @Test void toleratesUnknownFlagsRatherThanFailingTheRun() {
        var a = RunVerb.Args.parse(new String[]{"--text", "t", "--some-future-flag", "--verbose"});
        assertEquals("t", a.text);
    }

    /** Anything other than json stays human-readable rather than silently emitting a document. */
    @Test void nonJsonOutputFormatDoesNotSelectJson() {
        assertFalse(RunVerb.Args.parse(new String[]{"--text", "t", "--output-format", "text"}).json);
        assertFalse(RunVerb.Args.parse(new String[]{"--text", "t"}).json);
    }

    /** We hold no session state, so the flag is accepted and does nothing — never an error. */
    @Test void acceptsNoSession() {
        assertEquals("t", RunVerb.Args.parse(new String[]{"--no-session", "--text", "t"}).text);
    }

    @Test void takesTaskIdAndMaxTurns() {
        var a = RunVerb.Args.parse(new String[]{"--text", "t", "--task-id", "abc-1", "--max-turns", "7"});
        assertEquals("abc-1", a.taskId);
        assertEquals(7, a.maxTurns);
    }

    /** A bare trailing task is accepted so the verb is usable by hand, not only by the host. */
    @Test void acceptsAPositionalTask() {
        assertEquals("do the thing", RunVerb.Args.parse(new String[]{"do the thing"}).text);
    }

    /** The workspace defaults to the process CWD — the host sets it via the subprocess directory. */
    @Test void workspaceDefaultsToTheCurrentDirectory() {
        assertEquals(Path.of("."), RunVerb.Args.parse(new String[]{"--text", "t"}).workspace);
    }

    // --- @file and stdin -----------------------------------------------------------------------
    //
    // These are not a convenience. On Windows every argument crosses cmd.exe via the .bat launcher,
    // which refuses a command line over 8,191 characters — a host's task preamble is larger than
    // that alone, so before this every real Windows dispatch died before the JVM started.

    @Test void readsTheTaskFromAnAtFile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("task.md");
        Files.writeString(f, "line one\nline two\n", StandardCharsets.UTF_8);
        var a = RunVerb.Args.parse(new String[]{"--text", "@" + f, "--output-format", "json"});
        assertEquals("line one\nline two\n", a.text);
        assertTrue(a.json, "the flags after an @file must still parse");
    }

    /** The whole point: a task far past what cmd.exe would carry arrives intact. */
    @Test void anAtFileCarriesATaskPastTheWindowsCommandLineCeiling(@TempDir Path dir) throws Exception {
        String big = "x".repeat(20_000);
        Path f = dir.resolve("big.md");
        Files.writeString(f, big, StandardCharsets.UTF_8);
        var a = RunVerb.Args.parse(new String[]{"--text", "@" + f});
        assertEquals(20_000, a.text.length());
    }

    /** A positional task gets the same treatment — the form must not depend on which spelling was used. */
    @Test void resolvesAnAtFileGivenPositionally(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("task.md");
        Files.writeString(f, "from the file", StandardCharsets.UTF_8);
        assertEquals("from the file", RunVerb.Args.parse(new String[]{"@" + f}).text);
    }

    /**
     * The load-bearing one. An unreadable @file must FAIL, not fall back to the literal the way the
     * interactive verbs do: a host cannot read a warning on stderr, and the fallback would hand the
     * model "@C:\...\task.md" as its entire task — a full budget spent, and a confident document
     * returned, for a task nobody asked for.
     */
    @Test void aMissingAtFileIsFatalRatherThanTreatedAsTheTask(@TempDir Path dir) {
        Path missing = dir.resolve("nope.md");
        var e = assertThrows(IllegalArgumentException.class,
                () -> RunVerb.Args.parse(new String[]{"--text", "@" + missing}));
        assertTrue(e.getMessage().contains("nope.md"), e.getMessage());
    }

    /** An empty task file is the same class of silent-garbage as a missing one. */
    @Test void anEmptyAtFileIsFatal(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("empty.md");
        Files.writeString(f, "   \n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> RunVerb.Args.parse(new String[]{"--text", "@" + f}));
    }

    /** A task that genuinely begins with an at-sign is still expressible. */
    @Test void doubleAtEscapesALiteralAtSign() {
        assertEquals("@channel review this",
                RunVerb.Args.parse(new String[]{"--text", "@@channel review this"}).text);
    }

    @Test void readsTheTaskFromStdin() {
        InputStream real = System.in;
        try {
            System.setIn(new ByteArrayInputStream("piped task\n".getBytes(StandardCharsets.UTF_8)));
            assertEquals("piped task\n", RunVerb.Args.parse(new String[]{"--text", "-"}).text);
        } finally {
            System.setIn(real);
        }
    }

    /** Empty stdin is a caller mistake, not an empty task to run. */
    @Test void emptyStdinIsFatal() {
        InputStream real = System.in;
        try {
            System.setIn(new ByteArrayInputStream(new byte[0]));
            assertThrows(IllegalArgumentException.class,
                    () -> RunVerb.Args.parse(new String[]{"--text", "-"}));
        } finally {
            System.setIn(real);
        }
    }
}
