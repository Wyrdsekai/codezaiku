package org.codezaiku.loop;

import com.fasterxml.jackson.databind.JsonNode;
import org.codezaiku.drive.DriveClient;
import org.codezaiku.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The goal names a file; the model says it is done; the file is not on disk. The harness says so once, shows the list every turn,
 * and after that takes the model at its word while telling the host what is missing.
 */
class NamedFilesBounceTest {

    private static final String GOAL = "Add up the rows of `input.csv` and write the totals to `totals.csv`.";
    private static final String BOUNCE = "Before finishing: the goal names `totals.csv`, and the harness does not find it on disk";

    private static int bounces(StubDrive stub) {
        String all = StubDrive.userText(stub.requests.get(stub.requests.size() - 1));
        int n = 0;
        for (int at = all.indexOf(BOUNCE); at >= 0; at = all.indexOf(BOUNCE, at + 1)) n++;
        return n;
    }

    @Test
    void doneWithoutTheNamedFileIsSentBackOnceAndThenReportedAsMissing(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("input.csv"), "a,b\n1,2\n");
        try (StubDrive stub = new StubDrive(req -> StubDrive.calls("task_done", "{\"summary\":\"all done\"}"))) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.readOnly(tmp, null), tmp, GOAL, 12, null, null).run();

            JsonNode first = stub.requests.get(0);
            String system = first.path("messages").get(0).path("content").asText();
            assertTrue(system.contains("FILES THE GOAL NAMES"), "the list is in the pinned block from the first turn");
            assertTrue(system.contains(" - totals.csv — not written yet"), system.substring(Math.max(0, system.length() - 600)));
            assertFalse(system.contains(" - input.csv"), "a file that was there at the start is an input");

            assertTrue(StubDrive.userText(stub.requests.get(1)).contains(BOUNCE), "the second request carries the harness's look at the disk");
            assertEquals(1, bounces(stub), "said once");
            assertTrue(r.done());
            assertTrue(r.summary().endsWith("Named in the goal and not written: `totals.csv`."), r.summary());
        }
    }

    @Test
    void onceTheFileIsWrittenTheListSaysSoAndNothingIsAddedToTheSummary(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("input.csv"), "a,b\n1,2\n");
        try (StubDrive stub = new StubDrive(req -> {
            if (StubDrive.userText(req).contains(BOUNCE)) {
                try { Files.writeString(tmp.resolve("totals.csv"), "a,b\n1,2\n"); } catch (IOException e) { throw new UncheckedIOException(e); }
            }
            return StubDrive.calls("task_done", "{\"summary\":\"all done\"}");
        })) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.readOnly(tmp, null), tmp, GOAL, 12, null, null).run();

            assertEquals(1, bounces(stub));
            JsonNode last = stub.requests.get(stub.requests.size() - 1);
            assertTrue(last.path("messages").get(0).path("content").asText().contains(" - totals.csv — written"), "the next turn's list shows the file");
            assertTrue(r.done());
            assertFalse(r.summary().contains("not written"), r.summary());
        }
    }

    @Test
    void aChatIsNotACodingRunAndTracksNoFiles(@TempDir Path tmp) throws Exception {
        try (StubDrive stub = new StubDrive(req -> StubDrive.calls("task_done", "{\"summary\":\"You could write it to totals.csv.\"}"))) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.readOnly(tmp, null), tmp, GOAL, 12, null, null).chat().run();
            assertEquals(1, stub.requests.size());
            assertFalse(stub.requests.get(0).path("messages").get(0).path("content").asText().contains("FILES THE GOAL NAMES"));
            assertTrue(r.done());
            assertFalse(r.summary().contains("not written"), r.summary());
        }
    }
}
