package org.codezaiku.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** The fetch tool's address check (Wyrdsekai, 2026-09-07): loopback, link-local and our own services are never fetched. */
class FetchGuardTest {

    @Test
    void refusesTheAddressesAPageMustNotReach() {
        assertNotNull(Fetch.refusal(URI.create("http://127.0.0.1:7071/v1/status")));
        assertNotNull(Fetch.refusal(URI.create("http://localhost:8200/v1/models")));
        assertNotNull(Fetch.refusal(URI.create("http://169.254.169.254/latest/meta-data/")));
        assertNotNull(Fetch.refusal(URI.create("ftp://example.org/x")));
        assertNull(Fetch.refusal(URI.create("https://93.184.215.14/")));
    }

    @Test
    void walksRedirectsWithTheCheckAtEveryHop() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/start", ex -> { ex.getResponseHeaders().add("Location", "/page"); ex.sendResponseHeaders(302, -1); ex.close(); });
        s.createContext("/page", ex -> { byte[] b = "Hand cut, says the page.".getBytes(StandardCharsets.UTF_8); ex.getResponseHeaders().add("Content-Type", "text/plain"); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
        s.createContext("/loop", ex -> { ex.getResponseHeaders().add("Location", "/loop"); ex.sendResponseHeaders(302, -1); ex.close(); });
        s.createContext("/away", ex -> { ex.getResponseHeaders().add("Location", "http://169.254.169.254/"); ex.sendResponseHeaders(302, -1); ex.close(); });
        s.start();
        String base = "http://127.0.0.1:" + s.getAddress().getPort();
        try {
            assertThrows(IllegalArgumentException.class, () -> Fetch.get(base + "/page", Duration.ofSeconds(5)));
            String obs = new WebFetchTool().execute(new ObjectMapper().readTree("{\"url\":\"" + base + "/page\"}"));
            assertTrue(obs.startsWith("ERROR: refused:"), obs);
            setLoopback(true);
            Fetch.Result r = Fetch.get(base + "/start", Duration.ofSeconds(5));
            assertEquals(200, r.status());
            assertEquals(base + "/page", r.url());
            assertThrows(IllegalStateException.class, () -> Fetch.get(base + "/loop", Duration.ofSeconds(5)));
            assertThrows(IllegalArgumentException.class, () -> Fetch.get(base + "/away", Duration.ofSeconds(5)));
            String ok = new WebFetchTool().execute(new ObjectMapper().readTree("{\"url\":\"" + base + "/start\"}"));
            assertTrue(ok.startsWith("source: " + base + "/page"), ok);
            assertTrue(ok.contains("Hand cut, says the page."), ok);
        } finally {
            setLoopback(false);
            s.stop(0);
        }
    }

    private static void setLoopback(boolean v) throws Exception {
        var f = Fetch.class.getDeclaredField("allowLoopback"); f.setAccessible(true); f.set(null, v);
    }

    @AfterEach void noLoopback() throws Exception { setLoopback(false); }
}
