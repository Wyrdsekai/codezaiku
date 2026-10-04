package org.codezaiku.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.drive.DriveClient;
import org.codezaiku.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model's thinking stays in the record while there is room, and the oldest goes first when there is not. Kept without limit
 * it was half of what filled a 32k window (2026-09-30); dropped wholesale it threw away what a large window can hold and what the
 * model's makers recommend keeping for agent work.
 */
class ShedThinkingTest {

    private static ObjectNode reply(ArrayNode h, int n) {
        ObjectNode a = h.addObject().put("role", "assistant").put("content", "reply " + n).put("reasoning_content", "thinking " + n);
        a.putArray("tool_calls").addObject().put("id", "c" + n).putObject("function").put("name", "shell").put("arguments", "{}");
        h.addObject().put("role", "tool").put("tool_call_id", "c" + n).put("content", "result " + n);
        return a;
    }

    @Test
    void theOldestThinkingGoesFirstAndTheLatestReplyKeepsItsOwn() {
        ArrayNode h = StubDrive.J.createArrayNode();
        h.addObject().put("role", "user").put("content", "Begin.").put("reasoning_content", "not an assistant message");
        reply(h, 1);
        reply(h, 2);
        ObjectNode last = reply(h, 3);

        assertEquals("thinking 1".length(), FamiliarLoop.shedOldestThinking(h, 5), "enough is one reply's");
        assertFalse(h.get(1).has("reasoning_content"));
        assertEquals("thinking 2", h.get(3).path("reasoning_content").asText(), "the next oldest is still there");
        assertEquals("reply 1", h.get(1).path("content").asText());
        assertEquals("c1", h.get(1).path("tool_calls").get(0).path("id").asText());
        assertEquals("result 1", h.get(2).path("content").asText());

        assertEquals("thinking 2".length(), FamiliarLoop.shedOldestThinking(h, 1_000_000), "asked for more than there is: all but the latest");
        assertEquals("thinking 3", last.path("reasoning_content").asText());
        assertTrue(h.get(0).has("reasoning_content"), "only assistant messages carry thinking to drop");
        assertEquals(0, FamiliarLoop.shedOldestThinking(h, 1_000_000), "a second pass finds nothing");
    }

    @Test
    void aRunWithRoomSendsBackEveryRepliesThinking(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("note.txt"), "hello");
        int[] n = {0};
        try (StubDrive stub = new StubDrive(req -> (++n[0] < 4 ? StubDrive.calls("read_file", "{\"path\":\"note.txt\"}")
                : StubDrive.calls("task_done", "{\"summary\":\"done\"}")).put("reasoning_content", "thinking of reply " + n[0]))) {
            new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.readOnly(tmp, null), tmp, "Read the note.", 8, null, null).chat().run();

            JsonNode fourth = stub.requests.get(3);
            StringBuilder thinking = new StringBuilder();
            for (JsonNode m : fourth.path("messages")) {
                if ("assistant".equals(m.path("role").asText())) thinking.append(m.path("reasoning_content").asText("")).append('|');
            }
            assertEquals("thinking of reply 1|thinking of reply 2|thinking of reply 3|", thinking.toString(), "a short run is far below half the window");
        }
    }
}
