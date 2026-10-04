package org.codezaiku.loop;

import com.fasterxml.jackson.databind.JsonNode;
import org.codezaiku.drive.DriveClient;
import org.codezaiku.run.ResultDocument;
import org.codezaiku.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A coding run that uses all its turns ends with the model's own report. Its last turn offers task_done alone and says what to put
 * in it; what comes back is the summary of a run that is still "incomplete". Before, the run ended "max turns reached without
 * task_done" and said nothing of where the work stood.
 */
class CodingDeadlineTest {

    @Test
    void theLastTurnOffersOnlyTaskDoneAndItsSummaryIsTheRunsReport(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("note.txt"), "hello");
        try (StubDrive stub = new StubDrive(req -> req.path("tools").size() == 1
                ? StubDrive.calls("task_done", "{\"summary\":\"measure.py works on 29 of 40 clips; median error 5.4 degrees; the results file is not written\"}")
                : StubDrive.calls("read_file", "{\"path\":\"note.txt\"}"))) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.readOnly(tmp, null), tmp,
                    "Fix the parser and write the numbers to `RESULTS.md`.", 6, null, null).run();

            assertEquals(6, stub.requests.size(), "one request a turn, and the run ends on the sixth");
            for (int i = 0; i < 5; i++) {
                assertTrue(stub.requests.get(i).path("tools").size() > 1, "turn " + (i + 1) + " still offers every tool");
            }
            JsonNode last = stub.requests.get(5);
            assertEquals(1, last.path("tools").size());
            assertEquals("task_done", last.path("tools").get(0).path("function").path("name").asText());
            assertTrue(StubDrive.userText(last).contains("This is the last turn, and task_done is the one tool left"), StubDrive.userText(last));
            assertTrue(StubDrive.userText(last).contains("The goal names `RESULTS.md`, not written"), StubDrive.userText(last));

            assertFalse(r.done(), "running out of turns is not done");
            assertTrue(r.summary().startsWith(FamiliarLoop.TURN_LIMIT_REPORT + " (6 turns)"), r.summary());
            assertTrue(r.summary().contains("median error 5.4 degrees"), r.summary());
            assertTrue(r.summary().endsWith("Named in the goal and not written: `RESULTS.md`."), r.summary());
            assertEquals("incomplete", ResultDocument.statusForRun(r.done(), null, r.summary()), "the status a host reads is unchanged");
        }
    }

    @Test
    void aBudgetOfThreeTurnsKeepsEveryToolToTheEnd(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("note.txt"), "hello");
        try (StubDrive stub = new StubDrive(req -> StubDrive.calls("read_file", "{\"path\":\"note.txt\"}"))) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.readOnly(tmp, null), tmp,
                    "Read the note.", 3, null, null).run();
            assertEquals(3, stub.requests.size());
            for (JsonNode req : stub.requests) assertTrue(req.path("tools").size() > 1);
            assertFalse(r.done());
            assertTrue(r.summary().startsWith("max turns (3) reached"), r.summary());
        }
    }
}
