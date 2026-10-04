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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A model server that will not force a tool call. Claude Opus 5.5, Claude Fable 5.1 and Claude Sonnet 5.5 answer
 * {@code tool_choice: required} with a 400, and CodeZaiku sent it on every working turn, so it could not drive them. The drive now
 * takes the refusal as the server's word and asks with {@code auto}; the loop takes a reply of text alone as the model ending its
 * turn.
 */
class UnforcedToolChoiceTest {

    private static final String REFUSAL = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":"
            + "\"tool_choice: type \\\"tool\\\" and \\\"any\\\" are not supported for this model.\"}}";

    private static List<String> toolChoices(StubDrive stub) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : stub.requests) out.add(r.path("tool_choice").asText("-"));
        return out;
    }

    @Test
    void aRefusedForcedToolCallIsAskedAgainWithAutoAndNeverForcedAfterThat(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("note.txt"), "hello");
        int[] n = {0};
        try (StubDrive stub = new StubDrive(req -> {
            if ("required".equals(req.path("tool_choice").asText())) return StubDrive.refuses(400, REFUSAL);
            return ++n[0] < 3 ? StubDrive.calls("read_file", "{\"path\":\"note.txt\"}") : StubDrive.calls("task_done", "{\"summary\":\"read it\"}");
        })) {
            DriveClient drive = new DriveClient(stub.url(), "t");
            assertTrue(drive.forcesToolCalls());
            FamiliarLoop.Result r = new FamiliarLoop(drive, ToolRegistry.readOnly(tmp, null), tmp, "Read the note.", 8, null, null).chat().run();

            assertTrue(r.done());
            assertEquals(List.of("required", "auto", "auto", "auto"), toolChoices(stub), "one refused request, then auto throughout");
            assertFalse(drive.forcesToolCalls());
        }
    }

    @Test
    void anOrdinaryBadRequestIsNotTakenForARefusal() {
        assertTrue(DriveClient.refusesForcedToolChoice(REFUSAL));
        assertFalse(DriveClient.refusesForcedToolChoice("{\"error\":{\"message\":\"max_tokens: must be at least 1\"}}"));
        assertFalse(DriveClient.refusesForcedToolChoice("{\"error\":{\"message\":\"prompt is too long: 250000 tokens\"}}"));
        assertFalse(DriveClient.refusesForcedToolChoice(null));
    }

    @Test
    void inACodingRunATextOnlyReplyIsAskedForItsNextActionAndTheReportStillComesThroughTaskDone(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("note.txt"), "hello");
        int[] n = {0};
        try (StubDrive stub = new StubDrive(req -> {
            if ("required".equals(req.path("tool_choice").asText())) return StubDrive.refuses(400, REFUSAL);
            n[0]++;
            if (n[0] == 1) return StubDrive.calls("read_file", "{\"path\":\"note.txt\"}");
            if (n[0] == 2) return StubDrive.says("The note says hello. That is everything.");
            return StubDrive.calls("task_done", "{\"summary\":\"the note says hello\"}");
        })) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.readOnly(tmp, null), tmp, "Read the note.", 8, null, null).run();
            assertTrue(r.done());
            assertTrue(r.summary().startsWith("the note says hello"), r.summary());
            assertTrue(StubDrive.userText(stub.requests.get(stub.requests.size() - 1)).contains("this harness acts only through tool calls"),
                    "the text-only reply was answered with how to go on or finish");
        }
    }

    @Test
    void inAChatATextOnlyReplyIsTheReply(@TempDir Path tmp) throws Exception {
        try (StubDrive stub = new StubDrive(req -> "required".equals(req.path("tool_choice").asText())
                ? StubDrive.refuses(400, REFUSAL) : StubDrive.says("It is a small note that says hello."))) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.readOnly(tmp, null), tmp, "What is in the note?", 8, null, null).chat().run();
            assertTrue(r.done());
            assertEquals("It is a small note that says hello.", r.summary());
            assertEquals(2, stub.requests.size(), "the refused request and the answer");
        }
    }

    @Test
    void onTheLastTurnATextOnlyReplyIsTheRunsReport(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("note.txt"), "hello");
        try (StubDrive stub = new StubDrive(req -> {
            if ("required".equals(req.path("tool_choice").asText())) return StubDrive.refuses(400, REFUSAL);
            return req.path("tools").size() == 1 ? StubDrive.says("Half done: the parser works, the report is not written.")
                    : StubDrive.calls("read_file", "{\"path\":\"note.txt\"}");
        })) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.readOnly(tmp, null), tmp, "Fix the parser.", 6, null, null).run();
            assertFalse(r.done());
            assertTrue(r.summary().startsWith(FamiliarLoop.TURN_LIMIT_REPORT + " (6 turns)"), r.summary());
            assertTrue(r.summary().contains("Half done: the parser works"), r.summary());
        }
    }
}
