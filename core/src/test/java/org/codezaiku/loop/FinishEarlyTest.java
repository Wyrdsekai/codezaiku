package org.codezaiku.loop;

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stop rule's hook: when the early-finish check says the search is exhausted, the loop
 * brings the deadline turn forward — the request offers ONLY task_done — never before turn 4,
 * and only in a mode that has the deadline turn (research). Pinned against a stub drive that
 * keeps asking to read a file until the tool list collapses, then finishes.
 */
class FinishEarlyTest {

    private static final ObjectMapper J = new ObjectMapper();

    @Test
    void exhaustionCollapsesTheToolListToTaskDoneButNotBeforeTurnFour(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("note.txt"), "hello");
        List<String> bodies = new ArrayList<>();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String read = J.writeValueAsString(msg(J.createObjectNode().put("role", "assistant")
                .set("tool_calls", J.createArrayNode().add(call("c", "read_file", "{\"path\":\"note.txt\"}")))));
        String done = J.writeValueAsString(msg(J.createObjectNode().put("role", "assistant")
                .set("tool_calls", J.createArrayNode().add(call("d", "task_done",
                        "{\"summary\":\"answer https://example.org\"}")))));
        stub.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            // finish only once the loop offers task_done alone — otherwise keep reading
            boolean onlyDone = J.readTree(body).path("tools").size() == 1;
            byte[] b = (onlyDone ? done : read).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        stub.start();
        try {
            FamiliarLoop.Result r = new FamiliarLoop(
                    new DriveClient("http://127.0.0.1:" + stub.getAddress().getPort(), "t"),
                    ToolRegistry.readOnly(tmp, null), tmp, "research something", 12, null, null)
                    .research()
                    .finishEarlyIf(() -> true)      // "exhausted" from the very first turn
                    .run();
            assertTrue(r.done(), "the run finished through the early deadline turn");
            // turns 1-3: the full tool list; turn 4: task_done alone
            for (int i = 0; i < 3; i++) {
                assertTrue(J.readTree(bodies.get(i)).path("tools").size() > 1,
                        "turn " + (i + 1) + " must still offer the full tool list");
            }
            var t4 = J.readTree(bodies.get(3)).path("tools");
            assertEquals(1, t4.size(), "turn 4 offers exactly one tool");
            assertEquals("task_done", t4.get(0).path("function").path("name").asText());
            assertEquals(4, bodies.size(), "the run ended on that turn, well before the cap of 12");
        } finally {
            stub.stop(0);
        }
    }

    private static ObjectNode call(String id, String name, String args) {
        ObjectNode c = J.createObjectNode();
        c.put("id", id);
        c.put("type", "function");
        c.putObject("function").put("name", name).put("arguments", args);
        return c;
    }

    private static ObjectNode msg(ObjectNode message) {
        ObjectNode o = J.createObjectNode();
        o.set("choices", J.createArrayNode().add(J.createObjectNode().set("message", message)));
        return o;
    }
}
