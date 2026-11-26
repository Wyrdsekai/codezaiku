package org.codezaiku.loop;

import com.fasterxml.jackson.databind.JsonNode;
import org.codezaiku.drive.DriveClient;
import org.codezaiku.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A model server that lets words through although a tool call was asked for (tool_choice "required"), and a model that answers in
 * words: a chat asked "You must act by calling a tool." again with no limit, request after request. A chat's turn now ends after
 * three replies in words in a row, with what the model said. A model that calls tools, and a coding job, go on as before.
 */
class WordsWithoutActionTest {

    /** The last user message of each request, in order: what CodeZaiku said to the model after its reply before. */
    private static List<String> lastAsks(StubDrive stub) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : stub.requests) {
            String last = "";
            for (JsonNode m : r.path("messages")) if ("user".equals(m.path("role").asText())) last = m.path("content").asText("");
            out.add(last);
        }
        return out;
    }

    @Test
    void aChatTurnEndsAfterThreeRepliesInWordsWithWhatTheModelSaid(@TempDir Path tmp) throws Exception {
        try (StubDrive stub = new StubDrive(req -> StubDrive.says("Hello from the stand-in."))) {
            DriveClient drive = new DriveClient(stub.url(), "t");
            assertTrue(drive.forcesToolCalls(), "every working turn asks for a tool call");
            // 40 turns stand for the chat's own limit, which by default is none (a million)
            FamiliarLoop.Result r = new FamiliarLoop(drive, ToolRegistry.readOnly(tmp, null), tmp, "Say hello.", 40, null, null).chat().run();
            assertEquals(3, stub.requests.size(), "the first reply and two asks again; before, every turn the limit allowed");
            assertFalse(r.done(), "the model did not finish the turn");
            assertTrue(r.summary().startsWith(FamiliarLoop.WORDS_WITHOUT_ACTION + ": its last 3 replies were words with no tool call"), r.summary());
            assertTrue(r.summary().endsWith("What it said:\nHello from the stand-in."), r.summary());
            for (JsonNode req : stub.requests) assertEquals("required", req.path("tool_choice").asText());
        }
    }

    @Test
    void aModelThatCallsToolsBetweenItsWordsGoesOnAsBefore(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("note.txt"), "hello");
        AtomicInteger n = new AtomicInteger();
        // two replies in words, a tool call, two more in words, the finish: never three in a row, so nothing changes
        try (StubDrive stub = new StubDrive(req -> switch (n.incrementAndGet()) {
            case 1, 2, 4, 5 -> StubDrive.says("Let me look at the note.");
            case 3 -> StubDrive.calls("read_file", "{\"path\":\"note.txt\"}");
            default -> StubDrive.calls("task_done", "{\"summary\":\"The note says hello.\"}");
        })) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.readOnly(tmp, null), tmp, "What does the note say?", 40, null, null).chat().run();
            assertTrue(r.done(), r.summary());
            assertTrue(r.summary().contains("The note says hello."), r.summary());
            assertEquals(6, stub.requests.size());
            List<String> asks = lastAsks(stub);
            assertEquals("You must act by calling a tool.", asks.get(1));
            assertEquals("You must act by calling a tool.", asks.get(2));
            assertEquals("You must act by calling a tool.", asks.get(4));
            assertEquals("You must act by calling a tool.", asks.get(5));
        }
    }

    @Test
    void twoRepliesInWordsAndThenTheFinishIsAnOrdinaryTurn(@TempDir Path tmp) throws Exception {
        AtomicInteger n = new AtomicInteger();
        try (StubDrive stub = new StubDrive(req -> n.incrementAndGet() <= 2 ? StubDrive.says("Hello.")
                : StubDrive.calls("task_done", "{\"summary\":\"Hello.\"}"))) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.readOnly(tmp, null), tmp, "Say hello.", 40, null, null).chat().run();
            assertTrue(r.done(), r.summary());
            assertEquals(3, stub.requests.size());
        }
    }

    @Test
    void aCodingJobKeepsAskingUntilItsTurnLimitAsBefore(@TempDir Path tmp) throws Exception {
        try (StubDrive stub = new StubDrive(req -> StubDrive.says("I would change the parser."))) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.readOnly(tmp, null), tmp, "Fix the parser.", 6, null, null).run();
            assertFalse(r.done());
            assertFalse(r.summary().startsWith(FamiliarLoop.WORDS_WITHOUT_ACTION), "a job is bounded by its turn limit: " + r.summary());
            assertEquals(6, stub.requests.size(), "one request for each of the six turns, as before");
        }
    }
}
