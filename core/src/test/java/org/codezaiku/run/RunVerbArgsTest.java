package org.codezaiku.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
}
