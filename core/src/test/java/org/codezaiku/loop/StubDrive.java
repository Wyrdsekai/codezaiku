package org.codezaiku.loop;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** A model server for loop tests: every chat request is kept, and the reply is whatever the test's function makes of it. */
final class StubDrive implements AutoCloseable {

    static final ObjectMapper J = new ObjectMapper();

    final List<JsonNode> requests = new ArrayList<>();
    private final HttpServer server;

    StubDrive(Function<JsonNode, ObjectNode> reply) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            JsonNode body = J.readTree(ex.getRequestBody().readAllBytes());
            ObjectNode message;
            synchronized (requests) {
                requests.add(body);
                message = reply.apply(body);
            }
            int status = 200;
            byte[] b;
            if (message.has(REFUSE)) {
                status = message.path(REFUSE).asInt();
                b = message.path("body").asText("").getBytes(StandardCharsets.UTF_8);
            } else {
                ObjectNode out = J.createObjectNode();
                out.set("choices", J.createArrayNode().add(J.createObjectNode().set("message", message)));
                b = J.writeValueAsBytes(out);
            }
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private static final String REFUSE = "__http_status";

    /** Not a message: the server answers with this HTTP status and body. */
    static ObjectNode refuses(int status, String body) {
        return J.createObjectNode().put(REFUSE, status).put("body", body);
    }

    /** An assistant message that calls one tool. */
    static ObjectNode calls(String tool, String args) {
        ObjectNode m = J.createObjectNode().put("role", "assistant");
        ObjectNode c = m.putArray("tool_calls").addObject();
        c.put("id", "c" + System.nanoTime());
        c.put("type", "function");
        c.putObject("function").put("name", tool).put("arguments", args);
        return m;
    }

    /** An assistant message that is text. */
    private static final AtomicInteger WAITS = new AtomicInteger();

    /**
     * What a stub answers while the harness has a check running in the background: a harmless shell command that differs every time,
     * after a short pause. Re-reading the same file was what the stubs did, and on a slow runner (CI) the harness's spin block —
     * the same command repeated with no new result — ended the run before a one-second check came back (2026-10-04); a real model
     * never answers in zero time, and never issues the identical command forty times.
     */
    static ObjectNode waiting() {
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return calls("shell", "{\"command\":\"echo waiting " + WAITS.incrementAndGet() + "\"}");
    }

    static ObjectNode says(String text) {
        return J.createObjectNode().put("role", "assistant").put("content", text);
    }

    /** Every user message of a request, joined. */
    static String userText(JsonNode request) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode m : request.path("messages")) {
            if ("user".equals(m.path("role").asText())) sb.append(m.path("content").asText("")).append('\n');
        }
        return sb.toString();
    }

    @Override public void close() { server.stop(0); }
}
