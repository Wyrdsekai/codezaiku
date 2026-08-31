package org.codezaiku.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The /v1 façade's contract: OpenAI request in, one read-rung loop turn, OpenAI response out.
 *
 * <p>The goal builder is tested pure (it decides what the model sees), and one round trip runs
 * against a stub drive that answers every request with a task_done tool call — the same shape
 * scripts/stub-drive.py plays, inlined so the unit suite needs no python.
 */
class V1ServerTest {

    private static final ObjectMapper J = new ObjectMapper();

    private static ArrayNode msgs(String... roleContentPairs) {
        ArrayNode a = J.createArrayNode();
        for (int i = 0; i < roleContentPairs.length; i += 2) {
            a.addObject().put("role", roleContentPairs[i]).put("content", roleContentPairs[i + 1]);
        }
        return a;
    }

    @Test
    void aLoneUserMessageIsTheGoalVerbatim() {
        assertEquals("what does this repo do", V1Server.goalFrom(msgs("user", "what does this repo do")));
    }

    @Test
    void priorMessagesBecomeContextAndTheLastUserMessageTheAsk() {
        String g = V1Server.goalFrom(msgs("user", "first question", "assistant", "first answer",
                "user", "and a follow-up"));
        assertNotNull(g);
        assertTrue(g.contains("user: first question"));
        assertTrue(g.contains("assistant: first answer"));
        assertTrue(g.endsWith("and a follow-up"));
        // The ask is outside the context block, not inside it.
        assertTrue(g.indexOf("The person now says:") > g.indexOf("first answer"));
    }

    @Test
    void aConversationNotEndingInAUserMessageIsRejected() {
        assertNull(V1Server.goalFrom(msgs("user", "hi", "assistant", "hello")));
        assertNull(V1Server.goalFrom(J.createArrayNode()));
        assertNull(V1Server.goalFrom(msgs("user", "")));
    }

    @Test
    void oversizeHistoryKeepsTheTailAndSaysWhatItDropped() {
        ArrayNode a = J.createArrayNode();
        for (int i = 0; i < 40; i++) {
            a.addObject().put("role", "user").put("content", "m" + i + " " + "x".repeat(2_000));
        }
        a.addObject().put("role", "user").put("content", "the ask");
        String g = V1Server.goalFrom(a);
        assertTrue(g.contains("dropped for length"), "the drop must be declared, not silent");
        assertTrue(g.contains("m39"), "the newest prior message survives");
        assertFalse(g.contains("m0 "), "the oldest is what goes");
    }

    @Test
    void roundTripAgainstAStubDrive(@TempDir Path tmp) throws Exception {
        // Stub drive: every chat request finishes the turn with a fixed summary.
        HttpServer drive = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String toolCall = J.writeValueAsString(J.createObjectNode()
                .set("choices", J.createArrayNode().add(J.createObjectNode()
                        .set("message", J.createObjectNode()
                                .put("role", "assistant")
                                .set("tool_calls", J.createArrayNode().add(J.createObjectNode()
                                        .put("id", "c1").put("type", "function")
                                        .set("function", J.createObjectNode()
                                                .put("name", "task_done")
                                                .put("arguments",
                                                     "{\"summary\":\"the stub's answer\"}"))))))));
        drive.createContext("/", ex -> {
            byte[] b = toolCall.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        drive.start();
        HttpServer v1 = null;
        try {
            String driveUrl = "http://127.0.0.1:" + drive.getAddress().getPort();
            v1 = new V1Server(tmp, driveUrl, "test", 4).start("127.0.0.1", 0);
            String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"say hi\"}]}";
            HttpResponse<String> resp = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + v1.getAddress().getPort()
                                    + "/v1/chat/completions"))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode(), resp.body());
            JsonNode r = J.readTree(resp.body());
            assertEquals("chat.completion", r.path("object").asText());
            assertEquals("assistant", r.path("choices").path(0).path("message").path("role").asText());
            assertEquals("the stub's answer",
                    r.path("choices").path(0).path("message").path("content").asText());
        } finally {
            if (v1 != null) v1.stop(0);
            drive.stop(0);
        }
    }

    @Test
    void badRequestsGetOpenAiShapedErrors(@TempDir Path tmp) throws Exception {
        HttpServer v1 = new V1Server(tmp, "http://127.0.0.1:1", "test", 4).start("127.0.0.1", 0);
        try {
            HttpClient c = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + v1.getAddress().getPort();
            HttpResponse<String> notJson = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/chat/completions"))
                    .POST(HttpRequest.BodyPublishers.ofString("not json")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, notJson.statusCode());
            assertTrue(J.readTree(notJson.body()).has("error"));

            HttpResponse<String> models = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/models"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, models.statusCode());
            assertEquals("codezaiku", J.readTree(models.body()).path("data").path(0).path("id").asText());
        } finally {
            v1.stop(0);
        }
    }
}
