package org.codezaiku.tools;

import com.sun.net.httpserver.HttpServer;
import org.codezaiku.Setup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The search check of doctor and setup: whether a SearXNG answers in the form CodeZaiku reads, found without making it search. A
 * search for "probe" or "test" was passed on by the SearXNG to every engine it has. The stand-in answers as SearXNG 2026.7.26 does
 * (tried on a container of that version): a search with no words is refused as "No query" in JSON when its JSON output is on, with
 * 403 when it is off, and no engine is asked either way.
 */
class SearxngCheckTest {

    HttpServer searx;
    final List<String> asked = new CopyOnWriteArrayList<>();
    boolean jsonOn = true;
    int generalEngines = 10;

    @BeforeEach void standIn() throws IOException {
        searx = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        searx.createContext("/", x -> {
            String q = x.getRequestURI().getRawQuery();
            asked.add(x.getRequestURI().getPath() + (q == null ? "" : "?" + q) + (x.getRequestHeaders().containsKey("Authorization") ? " [with a key]" : ""));
            String path = x.getRequestURI().getPath();
            int code; String body;
            if (path.equals("/config")) {
                StringBuilder engines = new StringBuilder();
                for (int i = 0; i < generalEngines; i++) engines.append("{\"name\":\"e").append(i).append("\",\"enabled\":true,\"categories\":[\"general\"]},");
                engines.append("{\"name\":\"off\",\"enabled\":false,\"categories\":[\"general\"]},{\"name\":\"pictures\",\"enabled\":true,\"categories\":[\"images\"]}");
                code = 200; body = "{\"version\":\"2026.7.26+b060c780d\",\"engines\":[" + engines + "]}";
            } else if (path.equals("/search") && q != null && q.contains("format=json") && jsonOn) {
                boolean words = q.matches(".*(^|&)q=[^&]+.*");
                code = words ? 200 : 400; body = words ? "{\"query\":\"x\",\"results\":[]}" : "{\"error\": \"No query\"}";
            } else if (path.equals("/search")) { code = 403; body = "<!doctype html><title>403 Forbidden</title>"; }
            else { code = 404; body = "not found"; }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", body.startsWith("{") ? "application/json" : "text/html");
            x.sendResponseHeaders(code, b.length);
            try (var o = x.getResponseBody()) { o.write(b); }
        });
        searx.start();
    }

    @AfterEach void stop() { searx.stop(0); }

    String base() { return "http://127.0.0.1:" + searx.getAddress().getPort(); }

    static boolean searched(List<String> asked) {
        return asked.stream().anyMatch(a -> a.matches(".*[?&]q=[^&\\s]+.*"));
    }

    @Test
    void setupChecksTheSearxngWithoutMakingItSearch() {
        assertTrue(Setup.liveProbe().searxng(base()), "a SearXNG with its JSON output on is taken");
        assertFalse(searched(asked), "no words were sent to be searched: " + asked);
        jsonOn = false;
        asked.clear();
        assertFalse(Setup.liveProbe().searxng(base()), "its JSON output off: not taken");
        assertFalse(searched(asked), asked.toString());
    }

    @Test
    void theCheckSaysWhatItFoundAndAsksNoEngine() {
        WebSearchTool.SearxCheck c = WebSearchTool.searxngCheck(base());
        assertTrue(c.ready(), c.said());
        assertEquals("SearXNG 2026.7.26 answers in JSON, with 10 engines for a plain search; checked without searching", c.said());
        assertEquals(List.of("/search?format=json", "/config"), asked, "a search with no words, then its settings; no key goes with either");

        jsonOn = false;
        c = WebSearchTool.searxngCheck(base());
        assertFalse(c.ready());
        assertTrue(c.said().contains("its JSON output is off") && c.said().contains("search.formats"), c.said());

        jsonOn = true; generalEngines = 0;
        c = WebSearchTool.searxngCheck(base());
        assertFalse(c.ready(), "no engine is on for a plain search: it would answer every search with nothing");
        assertTrue(c.said().contains("no engine"), c.said());

        assertFalse(WebSearchTool.searxngCheck("http://127.0.0.1:9").ready(), "nothing there");
        assertFalse(searched(asked), asked.toString());
    }
}
