package org.codezaiku.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.codezaiku.drive.DriveClient;
import org.codezaiku.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A coding run through the Claude drive, end to end, against a stand-in that speaks the Messages API over a stream. The Claude
 * API reads a prompt from its cache only up to the first thing that changed, and CodeZaiku's system prompt names the project's
 * files, so every file a run wrote made the whole conversation new to the API. The rules now go first and stay the same; what
 * changes every turn is told after the conversation.
 */
class ClaudeDriveLoopTest {

    private static final ObjectMapper J = new ObjectMapper();

    /** What a request asks the API to keep, as the API compares it: the tools, the system prompt, and every block up to the last mark, without the marks. */
    private static String kept(JsonNode request) {
        StringBuilder upToMark = new StringBuilder(strip(request.path("tools")) + "\n" + strip(request.path("system")) + "\n"), pending = new StringBuilder();
        for (JsonNode m : request.path("messages")) for (JsonNode block : m.path("content")) {
            pending.append(m.path("role").asText()).append(' ').append(strip(block)).append('\n');
            if (block.has("cache_control")) { upToMark.append(pending); pending.setLength(0); }
        }
        return upToMark.toString();
    }

    private static String strip(JsonNode n) {
        JsonNode copy = n.deepCopy();
        if (copy.isObject()) ((ObjectNode) copy).remove("cache_control");
        for (JsonNode child : copy) if (child.isObject()) ((ObjectNode) child).remove("cache_control");
        return copy.toString();
    }

    private static String event(String json) { return "event: e\ndata: " + json + "\n\n"; }

    /** A reply as the API streams it: one tool call, or text. */
    private static String stream(String tool, String input, String text) throws Exception {
        StringBuilder s = new StringBuilder(event("{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"role\":\"assistant\",\"usage\":{\"input_tokens\":10,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0,\"output_tokens\":1}}}"));
        if (tool == null) {
            s.append(event("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
            s.append(event(J.createObjectNode().put("type", "content_block_delta").put("index", 0).set("delta", J.createObjectNode().put("type", "text_delta").put("text", text)).toString()));
        } else {
            s.append(event("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_" + System.nanoTime() + "\",\"name\":\"" + tool + "\",\"input\":{}}}"));
            s.append(event(J.createObjectNode().put("type", "content_block_delta").put("index", 0).set("delta", J.createObjectNode().put("type", "input_json_delta").put("partial_json", input)).toString()));
        }
        s.append(event("{\"type\":\"content_block_stop\",\"index\":0}"));
        s.append(event("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + (tool == null ? "end_turn" : "tool_use") + "\"},\"usage\":{\"output_tokens\":20}}"));
        return s.append(event("{\"type\":\"message_stop\"}")).toString();
    }

    @Test
    void aFileWrittenDoesNotMakeTheConversationNewToTheApi(@TempDir Path tmp) throws Exception {
        List<JsonNode> working = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] out;
            String type = "application/json";
            synchronized (working) {
                seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " version=" + ex.getRequestHeaders().getFirst("anthropic-version"));
                try {
                    if (ex.getRequestMethod().equals("GET")) {
                        out = "{\"id\":\"claude-test\",\"max_input_tokens\":1000000,\"max_tokens\":128000,\"capabilities\":{\"thinking\":{\"types\":{\"adaptive\":{\"supported\":true}}},\"effort\":{\"supported\":true,\"high\":{\"supported\":true}}}}".getBytes(StandardCharsets.UTF_8);
                    } else {
                        JsonNode body = J.readTree(ex.getRequestBody().readAllBytes());
                        type = "text/event-stream";
                        if (!body.has("tools")) out = stream(null, null, "1. Write a.py\n2. Write b.py\nDONE CHECK:\nrun: none").getBytes(StandardCharsets.UTF_8);
                        else {
                            working.add(body);
                            out = (switch (working.size()) {
                                case 1 -> stream("write_file", "{\"path\": \"a.py\", \"content\": \"A = 1\\n\"}", null);
                                case 2 -> stream("write_file", "{\"path\": \"b.py\", \"content\": \"B = 2\\n\"}", null);
                                default -> stream("task_done", "{\"summary\": \"wrote a.py and b.py\"}", null);
                            }).getBytes(StandardCharsets.UTF_8);
                        }
                    }
                } catch (Exception e) { out = ("{\"type\":\"error\",\"error\":{\"message\":\"" + e + "\"}}").getBytes(StandardCharsets.UTF_8); }
            }
            ex.getResponseHeaders().set("Content-Type", type);
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
        try {
            DriveClient drive = new DriveClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages", "claude-test");
            assertTrue(drive.cachesPrompts() && !drive.forcesToolCalls());
            new FamiliarLoop(drive, ToolRegistry.standard(tmp), tmp, "Write `a.py` and `b.py`.", 12, null, null).run();

            assertEquals("A = 1\n", Files.readString(tmp.resolve("a.py")));
            assertEquals("B = 2\n", Files.readString(tmp.resolve("b.py")));
            assertTrue(seen.stream().allMatch(s -> s.endsWith("version=2023-06-01")) && seen.stream().anyMatch(s -> s.startsWith("POST /v1/messages ")), seen.toString());
            assertTrue(working.size() >= 3, "three working turns at least: " + working.size());
            JsonNode first = working.get(0), second = working.get(1), third = working.get(2);
            for (JsonNode r : List.of(first, second, third)) {
                assertFalse(r.has("temperature"), "no sampling settings go to the Claude API");
                assertEquals("auto", r.path("tool_choice").path("type").asText());
                assertEquals(1, r.path("system").size());
            }
            assertEquals(first.path("system"), second.path("system"), "the system prompt is the rules, the same on every turn");
            assertFalse(first.path("system").toString().contains("GOAL:"), "what changes is not in it");
            JsonNode end = second.path("messages").get(second.path("messages").size() - 1).path("content");
            String now = end.get(end.size() - 1).path("text").asText();
            assertTrue(now.contains("a.py") && now.contains("GOAL:") && !end.get(end.size() - 1).has("cache_control"), "the files and the goal are told last, after the mark: " + now);
            assertTrue(end.get(end.size() - 2).has("cache_control"), "the mark is on the last thing of the conversation: " + end);
            assertTrue(kept(second).startsWith(kept(first)), "the second turn starts with everything the first asked the API to keep:\n" + kept(first) + "\n---\n" + kept(second));
            assertTrue(kept(third).startsWith(kept(second)), "and the third with the second, though a.py and b.py were written in between:\n" + kept(second) + "\n---\n" + kept(third));
        } finally { server.stop(0); }
    }

    /** Every other model server gets the request it always got: one system prompt, first, with everything in it. */
    @Test
    void anOrdinaryServerStillGetsOneSystemPromptFirst(@TempDir Path tmp) throws Exception {
        int[] n = {0};
        try (StubDrive stub = new StubDrive(req -> req.has("tools") ? (++n[0] == 1 ? StubDrive.calls("write_file", "{\"path\": \"a.py\", \"content\": \"A = 1\\n\"}") : StubDrive.calls("task_done", "{\"summary\": \"wrote a.py\"}"))
                : StubDrive.says("1. Write a.py\nDONE CHECK:\nrun: none"))) {
            DriveClient drive = new DriveClient(stub.url(), "t");
            assertFalse(drive.cachesPrompts());
            new FamiliarLoop(drive, ToolRegistry.standard(tmp), tmp, "Write `a.py`.", 8, null, null).run();
            for (JsonNode r : stub.requests) {
                if (!r.has("tools")) continue;
                JsonNode m = r.path("messages");
                assertEquals("system", m.get(0).path("role").asText());
                assertTrue(m.get(0).path("content").asText().contains("GOAL:"), "the whole system prompt is the first message");
                for (int i = 1; i < m.size(); i++) assertFalse("system".equals(m.get(i).path("role").asText()), "and there is no other: " + m.get(i));
            }
        }
    }
}
