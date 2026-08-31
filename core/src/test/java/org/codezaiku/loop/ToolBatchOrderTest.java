package org.codezaiku.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.codezaiku.drive.DriveClient;
import org.codezaiku.library.Library;
import org.codezaiku.tools.ToolRegistry;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In a PARALLEL tool batch, every tool response must directly follow the assistant message —
 * interjected nudges land AFTER the batch. Anthropic's compat layer rejects the whole request
 * otherwise (measured: a 4-write fable-5 turn wedged a run unrecoverably at turn 6); llama.cpp
 * merely tolerates the malformed order, which is why no local run ever caught it.
 *
 * <p>Provoked exactly as the live failure was: a batch of two writes where one exceeds the
 * write-size cap, whose ERROR observation triggers interjections mid-batch. The stub drive
 * captures the NEXT request and the test asserts on the wire order — the thing the server
 * actually validates.
 */
class ToolBatchOrderTest {

    private static final ObjectMapper J = new ObjectMapper();

    @Test
    void toolResponsesStayContiguousInParallelBatches(@TempDir Path tmp) throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // First reply: TWO tool calls, one write oversized (provokes the error-path interjections).
        // Second reply: task_done, ending the run; its REQUEST body carries the history we check.
        String big = "x".repeat(5_000);
        String first = J.writeValueAsString(msg(J.createObjectNode()
                .put("role", "assistant")
                .set("tool_calls", J.createArrayNode()
                        .add(call("c1", "write_file",
                                "{\"path\":\"ok.txt\",\"content\":\"fine\"}"))
                        .add(call("c2", "write_file",
                                "{\"path\":\"big.txt\",\"content\":\"" + big + "\"}")))));
        String done = J.writeValueAsString(msg(J.createObjectNode()
                .put("role", "assistant")
                .set("tool_calls", J.createArrayNode()
                        .add(call("c3", "task_done", "{\"summary\":\"done\"}")))));
        final int[] n = {0};
        stub.createContext("/", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = (n[0]++ == 0 ? first : done).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        stub.start();
        try {
            new FamiliarLoop(new DriveClient("http://127.0.0.1:" + stub.getAddress().getPort(), "t"),
                    ToolRegistry.standard(tmp), tmp, "write the files", 4, new Library())
                    .chat().run();
            assertTrue(bodies.size() >= 2, "the loop made a second request");
            var messages = J.readTree(bodies.get(bodies.size() - 1)).path("messages");
            // Find the parallel-batch assistant message and verify the two turns after it are its
            // tool responses, whatever else the harness interjected.
            for (int i = 0; i < messages.size(); i++) {
                var m = messages.get(i);
                if ("assistant".equals(m.path("role").asText()) && m.path("tool_calls").size() == 2) {
                    assertEquals("tool", messages.get(i + 1).path("role").asText(),
                            "first message after the batch must be a tool response");
                    assertEquals("tool", messages.get(i + 2).path("role").asText(),
                            "second tool response must be CONTIGUOUS — an interjection between "
                            + "tool responses is what Anthropic rejects");
                    return;
                }
            }
            fail("no 2-call assistant message found in the follow-up request");
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
