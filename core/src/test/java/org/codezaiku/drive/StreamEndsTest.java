package org.codezaiku.drive;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** A stream is a whole message only when the server said it was finished; a stream that stalls is given up. */
class StreamEndsTest {

    static final String HEAD = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"write_file\",\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\",\\\"con\"}}]}}]}\n\n";
    static final String TAIL = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"tent\\\":\\\"hi\\\"}\"}}]}}]}\n\n";
    static final String FINISH = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n";

    @AfterEach void off() { DriveClient.streamTo(null, null); }

    /** mode: whole = both halves, a finish_reason and [DONE]; cut = the first half then the connection closes; nodone = finish_reason and no [DONE]; stall = the first half then silence. */
    static HttpServer stub(String mode) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (var os = ex.getResponseBody()) {
                os.write(HEAD.getBytes(StandardCharsets.UTF_8)); os.flush();
                if (mode.equals("stall")) { try { Thread.sleep(8000); } catch (InterruptedException ignored) { } return; }
                if (mode.equals("cut")) return;
                os.write(TAIL.getBytes(StandardCharsets.UTF_8));
                os.write(FINISH.getBytes(StandardCharsets.UTF_8));
                if (mode.equals("whole")) os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (java.io.IOException ignored) { }
        });
        s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        s.start();
        return s;
    }

    static ObjectNode stream(String mode, Duration idle) throws Exception {
        HttpServer s = stub(mode);
        try {
            DriveClient.streamTo(x -> { }, null);
            return new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m").chatStreaming("{}", idle);
        } finally { s.stop(0); }
    }

    @Test
    void aFinishedStreamIsAMessageAndACutOneIsNot() throws Exception {
        for (String mode : new String[]{"whole", "nodone"}) {
            ObjectNode msg = stream(mode, Duration.ofSeconds(30));
            assertNotNull(msg, mode);
            assertEquals("{\"path\":\"a.txt\",\"content\":\"hi\"}", msg.path("tool_calls").path(0).path("function").path("arguments").asText(), mode);
        }
        // the connection dropped after half the arguments: that is not a message, and the caller falls back to the plain request
        assertNull(stream("cut", Duration.ofSeconds(30)), "half a tool call must not come back as a whole one");
    }

    @Test
    void aStreamThatGoesSilentIsGivenUp() throws Exception {
        long t0 = System.nanoTime();
        assertNull(stream("stall", Duration.ofMillis(600)));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 5000, "gave up after " + ms + " ms, the server would have held the line for 8 s");
    }
}
