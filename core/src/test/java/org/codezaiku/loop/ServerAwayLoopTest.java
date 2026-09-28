package org.codezaiku.loop;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.codezaiku.drive.DriveClient;
import org.codezaiku.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model server that goes away in the middle of a run — refusing connections while it restarts, then answering 503 while it loads its
 * model, as Wyrdsekai's brain does when it moves between the card and RAM — is waited for, and the same turn is asked again. It used to
 * count each refusal as a failed call, answer it with a nudge about malformed JSON, and end the run after eight of them.
 */
class ServerAwayLoopTest {

    private static final ObjectMapper J = new ObjectMapper();

    @Test
    void aServerThatIsAwayIsToldApartFromAnAnswerAndFromACallThatRanOutOfTime() {
        assertTrue(FamiliarLoop.serverAway(new RuntimeException("chat() failed against http://x", new ConnectException("Connection refused"))));
        assertTrue(FamiliarLoop.serverAway(new RuntimeException("chat() failed against http://x", new IOException("Connection reset"))));
        assertTrue(FamiliarLoop.serverAway(new RuntimeException("chat() failed against http://x", new HttpConnectTimeoutException("HTTP connect timed out"))));
        assertTrue(FamiliarLoop.serverAway(new IllegalStateException("drive HTTP 503: {\"error\":{\"message\":\"Loading model\"}}")));
        assertFalse(FamiliarLoop.serverAway(new IllegalStateException("drive HTTP 500: failed to parse tool call")), "the model's own bad call");
        assertFalse(FamiliarLoop.serverAway(new IllegalStateException("drive HTTP 400: the request exceeds the context")), "an answer about the request");
        assertFalse(FamiliarLoop.serverAway(new RuntimeException("chat() failed against http://x", new HttpTimeoutException("request timed out"))), "held until its limit");
        assertFalse(FamiliarLoop.serverAway(new RuntimeException("chat() failed against http://x", new JsonParseException(null, "Unexpected character"))), "an answer that is not JSON");
    }

    @Test
    void aRunWaitsForAServerThatIsRestartingAndGoesOnWithoutANudge(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("note.txt"), "hello");
        long[] real = FamiliarLoop.awayWaitsMs;
        FamiliarLoop.awayWaitsMs = new long[]{100, 100, 100};
        try {
            List<String> steady = run(tmp, 0, false);   // a server that never left
            List<String> away = run(tmp, 6, true);      // refused at first, then six 503s while it loads
            assertEquals(steady.size(), away.size(), "the same turns as a run the server never left");
            for (String b : away) assertFalse(b.contains("could not be processed") || b.contains("TOO LARGE"), "no nudge for a server that was away: " + b);
        } finally {
            FamiliarLoop.awayWaitsMs = real;
        }
    }

    /** One run against a stub that reads note.txt and then finishes: {@code loading} 503s first, and when {@code late}, nothing listening for the first 400 ms. The bodies it answered. */
    private static List<String> run(Path tmp, int loading, boolean late) throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) { port = free.getLocalPort(); }
        List<String> bodies = new CopyOnWriteArrayList<>();
        AtomicInteger requests = new AtomicInteger();
        String read = J.writeValueAsString(msg(J.createObjectNode().put("role", "assistant")
                .set("tool_calls", J.createArrayNode().add(call("c", "read_file", "{\"path\":\"note.txt\"}")))));
        String done = J.writeValueAsString(msg(J.createObjectNode().put("role", "assistant")
                .set("tool_calls", J.createArrayNode().add(call("d", "task_done", "{\"summary\":\"read note.txt: hello\"}")))));
        HttpServer stub = HttpServer.create();
        stub.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] b; int code;
            if (!ex.getRequestURI().getPath().endsWith("/chat/completions")) { code = 404; b = "{}".getBytes(StandardCharsets.UTF_8); }
            else if (requests.incrementAndGet() <= loading) { code = 503; b = "{\"error\":{\"code\":503,\"message\":\"Loading model\"}}".getBytes(StandardCharsets.UTF_8); }
            else { bodies.add(body); code = 200; b = (bodies.size() == 1 ? read : done).getBytes(StandardCharsets.UTF_8); }
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        Thread up = new Thread(() -> {
            try { if (late) Thread.sleep(400); stub.bind(new InetSocketAddress("127.0.0.1", port), 0); stub.start(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        up.start();
        if (!late) up.join();
        try {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient("http://127.0.0.1:" + port, "t"),
                    ToolRegistry.readOnly(tmp, null), tmp, "read note.txt and say what it holds", 12, null, null).run();
            assertTrue(r.done(), "the run finished: " + r);
            if (loading > 0) assertTrue(requests.get() > loading, "the server was asked while it loaded");
            return bodies;
        } finally {
            up.join();
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
        ObjectNode r = J.createObjectNode();
        r.putArray("choices").addObject().set("message", message);
        return r;
    }
}
