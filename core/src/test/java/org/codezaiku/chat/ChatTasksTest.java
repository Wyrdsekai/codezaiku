package org.codezaiku.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Background jobs: complete, notify once, digest once, artifacts kept. */
class ChatTasksTest {

    @Test
    void aJobRunsNotifiesAndDigestsExactlyOnce(@TempDir Path tmp) throws Exception {
        List<String> notes = new ArrayList<>();
        ChatTasks t = new ChatTasks(tmp, notes::add);
        ChatTasks.Job j = t.startShell("echo done-marker; exit 0", "quick echo");
        j.runner.join(10_000);
        assertEquals(ChatTasks.State.DONE, j.state);
        assertEquals(1, notes.size(), "one console notification");
        assertTrue(notes.get(0).contains("task " + j.id));
        assertTrue(Files.readString(j.outFile).contains("done-marker"), "output is the artifact");

        String d1 = t.digestInto();
        assertTrue(d1.contains("task " + j.id) && d1.contains("DONE"), d1);
        assertEquals("", t.digestInto(), "a completion is digested once, not every turn");
    }

    @Test
    void failureIsAFailureNotASilence(@TempDir Path tmp) throws Exception {
        List<String> notes = new ArrayList<>();
        ChatTasks t = new ChatTasks(tmp, notes::add);
        ChatTasks.Job j = t.startShell("echo boom >&2; exit 3", "failing job");
        j.runner.join(10_000);
        assertEquals(ChatTasks.State.FAILED, j.state);
        assertEquals(3, j.exit);
        assertTrue(t.digestInto().contains("FAILED"));
    }

    @Test
    void externalJobsCompleteThroughTheCallback(@TempDir Path tmp) {
        List<String> notes = new ArrayList<>();
        ChatTasks t = new ChatTasks(tmp, notes::add);
        ChatTasks.Job j = t.startExternal("delegated sub-task", tmp.resolve("sub.log"),
                Thread.currentThread());
        assertTrue(t.anyRunning());
        t.complete(j, true, "12 turns, tests green");
        assertFalse(t.anyRunning());
        assertTrue(t.digestInto().contains("12 turns, tests green"));
        assertEquals(1, notes.size());
    }
}
