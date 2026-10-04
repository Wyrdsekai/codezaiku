package org.codezaiku.drive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model server that works but slowly gets the time its answers need. 2026-09-29: a 27B on a GPU capped at 130 W made 7.8 tokens a
 * second; every long answer ran past the five-minute limit, and the same request went out again with the same limit eight times.
 */
class SlowDriveTest {

    static final Duration FIVE_MIN = Duration.ofSeconds(300), HALF_HOUR = Duration.ofSeconds(1800);

    @Test
    void theLimitFollowsTheSpeedTheServerHasShownAndGrowsAfterATimeout() {
        assertEquals(300, DriveClient.callLimit(FIVE_MIN, HALF_HOUR, 0, 0, 12000, 20000, 0).toSeconds(), "no speed known yet: the setting");
        assertEquals(300, DriveClient.callLimit(FIVE_MIN, HALF_HOUR, 60, 2000, 1000, 5000, 0).toSeconds(), "a fast server: the setting is enough");
        long slow = DriveClient.callLimit(FIVE_MIN, HALF_HOUR, 7.8, 400, 3000, 20000, 0).toSeconds();
        assertTrue(slow > 3000 / 7.8 + 20000 / 400, "3,000 tokens at 7.8 a second after reading 20,000 at 400: " + slow);
        assertEquals(1800, DriveClient.callLimit(FIVE_MIN, HALF_HOUR, 7.8, 400, 12000, 20000, 0).toSeconds(), "never past the ceiling");
        assertEquals(600, DriveClient.callLimit(FIVE_MIN, HALF_HOUR, 0, 0, 12000, 0, 1).toSeconds(), "after one timeout: twice as long");
        assertEquals(1200, DriveClient.callLimit(FIVE_MIN, HALF_HOUR, 0, 0, 12000, 0, 2).toSeconds());
        assertEquals(1800, DriveClient.callLimit(FIVE_MIN, HALF_HOUR, 0, 0, 12000, 0, 5).toSeconds());
    }

    @Test
    void theSpeedIsTakenFromTheServersOwnTimings() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] b = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"total_tokens\":120},"
                    + "\"timings\":{\"prompt_per_second\":450.0,\"predicted_per_second\":7.8}}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (var os = ex.getResponseBody()) { os.write(b); }
        });
        s.start();
        try {
            var J = new ObjectMapper();
            var msgs = J.createArrayNode(); msgs.addObject().put("role", "user").put("content", "hi");
            DriveClient d = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m");
            assertEquals(0, d.generatedPerSecond(), 0.0);
            d.chat(msgs, J.createArrayNode(), 64, "none");
            assertEquals(7.8, d.generatedPerSecond(), 1e-9);
            assertEquals(300, d.lastCallLimit().toSeconds(), "the first call had no speed to go on");
            d.chat(msgs, J.createArrayNode(), 8000, "none");
            assertTrue(d.lastCallLimit().toSeconds() > 8000 / 7.8, "the second call waits as long as 8,000 tokens need at that speed: " + d.lastCallLimit());
        } finally { s.stop(0); }
    }
}
