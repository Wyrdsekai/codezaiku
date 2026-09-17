package org.codezaiku.drive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** After each model call, the thread's usage sink hears how many tokens the call held. Another thread's sink does not. */
class UsageSinkTest {

    @Test
    void theCallingThreadsSinkHearsTheTotalAndNoOtherThreadsDoes() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] b = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":4100,\"completion_tokens\":23,\"total_tokens\":4123}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (var os = ex.getResponseBody()) { os.write(b); }
        });
        s.start();
        try {
            var J = new ObjectMapper();
            var msgs = J.createArrayNode(); msgs.addObject().put("role", "user").put("content", "hi");
            DriveClient d = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort() + "/v1", "m");   // pasted with /v1: the same drive
            List<Integer> mine = new ArrayList<>(), theirs = new ArrayList<>();
            Thread other = new Thread(() -> { DriveClient.USAGE_SINK.set(theirs::add); try { Thread.sleep(1500); } catch (InterruptedException ignored) { } });
            other.start();
            DriveClient.USAGE_SINK.set(mine::add);
            try {
                d.chat(msgs, J.createArrayNode(), 64, "none");
            } finally { DriveClient.USAGE_SINK.remove(); }
            other.join();
            assertEquals(List.of(4123), mine);
            assertTrue(theirs.isEmpty(), "a session hears its own calls only");
        } finally { s.stop(0); }
    }
}
