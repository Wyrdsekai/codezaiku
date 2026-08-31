package org.codezaiku.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executors;

import org.codezaiku.drive.DriveClient;
import org.codezaiku.library.Library;
import org.codezaiku.library.LibraryIndex;
import org.codezaiku.loop.FamiliarLoop;
import org.codezaiku.lsp.LspClient;
import org.codezaiku.shape.ProjectFacts;
import org.codezaiku.tools.ToolRegistry;

/**
 * OpenAI-compatible {@code /v1/chat/completions} over one project — front end #2 from the chat
 * research: point Open WebUI (or any OpenAI client) at this port and the browser is the GUI.
 *
 * <p><b>Pinned to the read rung, by protocol shape.</b> {@code /v1/chat/completions} has no
 * mid-turn client→server callback, so nothing behind it can render an approval prompt — any rung
 * that can ask a question is unreachable through this endpoint. The session therefore gets
 * {@link ToolRegistry#readOnly}: the model is never handed a tool it would need consent to use,
 * which keeps this a constraint on the choice space, not an action-interceptor gate.
 *
 * <p><b>Stateless on purpose.</b> The protocol resends the whole conversation each request, so the
 * CLIENT is the session store; nothing is written to ours. Prior messages arrive as context for
 * the turn (capped — the tail is kept, the head dropped and said so), the last user message is the
 * ask. That is restate with the client doing the restating.
 *
 * <p>One turn at a time (single-thread executor): one drive, and interleaving two loops over the
 * same project would race their tool calls.
 */
public final class V1Server {

    /** Prior-message context kept per request, chars. The tail wins; the drop is declared. */
    static final int MAX_CONTEXT_CHARS = 24_000;
    static final int MAX_ONE_MESSAGE = 4_000;

    private final Path root;
    private final String driveUrl;
    private final String model;
    private final int maxTurns;
    private final ObjectMapper json = new ObjectMapper();

    public V1Server(Path root, String driveUrl, String model, int maxTurns) {
        this.root = root.toAbsolutePath().normalize();
        this.driveUrl = driveUrl;
        this.model = model;
        this.maxTurns = maxTurns;
    }

    /** Serve until the process dies. Binds loopback unless told otherwise: no auth exists here. */
    public HttpServer start(String host, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/health", ex -> respond(ex, 200, "application/json",
                "{\"status\":\"ok\",\"project\":\"" + root.getFileName() + "\",\"rung\":\"read\"}"));
        server.createContext("/v1/models", ex -> {
            ObjectNode o = json.createObjectNode();
            o.put("object", "list");
            ObjectNode m = o.putArray("data").addObject();
            m.put("id", "codezaiku");
            m.put("object", "model");
            m.put("owned_by", "codezaiku");
            respond(ex, 200, "application/json", o.toString());
        });
        server.createContext("/v1/chat/completions", this::completions);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        return server;
    }

    private void completions(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "application/json", error("POST only"));
            return;
        }
        JsonNode req;
        try {
            req = json.readTree(ex.getRequestBody());
        } catch (Exception e) {
            respond(ex, 400, "application/json", error("request is not JSON"));
            return;
        }
        String goal = goalFrom(req.path("messages"));
        if (goal == null) {
            respond(ex, 400, "application/json", error("'messages' must end with a user message"));
            return;
        }
        boolean stream = req.path("stream").asBoolean(false);

        String reply;
        try (LibraryIndex index = new LibraryIndex(org.codezaiku.FamiliarMain.libraryIndexDir())) {
            DriveClient drive = new DriveClient(driveUrl, model);
            LspClient lsp = LspClient.forProject(root, ProjectFacts.language(root));
            FamiliarLoop.Result r = new FamiliarLoop(drive, ToolRegistry.readOnly(root, lsp),
                    root, goal, maxTurns, new Library(), index)
                    .chat()
                    .lsp(lsp)
                    .run();
            reply = r.summary() == null || r.summary().isBlank()
                    ? "(the turn produced no reply)" : r.summary();
        } catch (Exception e) {
            respond(ex, 500, "application/json", error(String.valueOf(e.getMessage())));
            return;
        }

        if (stream) {
            // Valid SSE, one content chunk. Honest limitation: the loop's answer exists only when
            // task_done lands, so this streams availability, not tokens.
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(sse(chunk("assistant", null, null)));
                os.write(sse(chunk(null, reply, null)));
                os.write(sse(chunk(null, null, "stop")));
                os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        } else {
            respond(ex, 200, "application/json", completionJson(reply));
        }
    }

    /**
     * OpenAI messages → one loop goal. Prior messages become a fenced context block (tail kept,
     * drop declared), the final user message is the ask. Null when there is no final user message.
     */
    static String goalFrom(JsonNode messages) {
        if (!messages.isArray() || messages.isEmpty()) return null;
        JsonNode last = messages.get(messages.size() - 1);
        if (!"user".equals(last.path("role").asText())) return null;
        String ask = last.path("content").asText("");
        if (ask.isBlank()) return null;
        if (messages.size() == 1) return ask;

        StringBuilder ctx = new StringBuilder();
        int dropped = 0;
        for (int i = messages.size() - 2; i >= 0; i--) {
            JsonNode m = messages.get(i);
            String role = m.path("role").asText("user");
            String text = m.path("content").asText("");
            if (text.length() > MAX_ONE_MESSAGE) text = text.substring(0, MAX_ONE_MESSAGE) + " …";
            String line = role + ": " + text + "\n";
            if (ctx.length() + line.length() > MAX_CONTEXT_CHARS) { dropped = i + 1; break; }
            ctx.insert(0, line);
        }
        String head = dropped > 0
                ? "Conversation so far (" + dropped + " earlier message(s) dropped for length):\n"
                : "Conversation so far:\n";
        return head + ctx + "\nThe person now says:\n" + ask;
    }

    String completionJson(String reply) {
        ObjectNode o = json.createObjectNode();
        o.put("id", "chatcmpl-cz-" + Long.toHexString(System.nanoTime()));
        o.put("object", "chat.completion");
        o.put("created", System.currentTimeMillis() / 1000);
        o.put("model", "codezaiku");
        ObjectNode c = o.putArray("choices").addObject();
        c.put("index", 0);
        c.put("finish_reason", "stop");
        ObjectNode m = c.putObject("message");
        m.put("role", "assistant");
        m.put("content", reply);
        return o.toString();
    }

    private String chunk(String role, String content, String finish) {
        ObjectNode o = json.createObjectNode();
        o.put("id", "chatcmpl-cz-" + Long.toHexString(System.nanoTime()));
        o.put("object", "chat.completion.chunk");
        o.put("created", System.currentTimeMillis() / 1000);
        o.put("model", "codezaiku");
        ObjectNode c = o.putArray("choices").addObject();
        c.put("index", 0);
        ObjectNode d = c.putObject("delta");
        if (role != null) d.put("role", role);
        if (content != null) d.put("content", content);
        if (finish != null) c.put("finish_reason", finish); else c.putNull("finish_reason");
        return o.toString();
    }

    private static byte[] sse(String data) {
        return ("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8);
    }

    private String error(String msg) {
        ObjectNode o = json.createObjectNode();
        o.putObject("error").put("message", msg).put("type", "invalid_request_error");
        return o.toString();
    }

    private static void respond(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
