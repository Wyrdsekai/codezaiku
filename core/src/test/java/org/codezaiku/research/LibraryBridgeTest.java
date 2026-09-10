package org.codezaiku.research;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** The door to ResearchZosho: everything degrades to "nothing" without a daemon, and rides the client with one. */
class LibraryBridgeTest {

    private HttpServer server;
    private final List<String> hits = new CopyOnWriteArrayList<>();

    @AfterEach
    void down() {
        if (server != null) server.stop(0);
        LibraryBridge.urlOverride = null;
        LibraryBridge.reprobe();
    }

    private void fakeDaemon(String askBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/status", x -> reply(x, "{\"library_id\":\"lib-1\",\"library_name\":\"t\",\"contract\":\"1.0\"}"));
        server.createContext("/v1/ask", x -> {
            hits.add(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(x, askBody);
        });
        server.start();
        LibraryBridge.urlOverride = "http://127.0.0.1:" + server.getAddress().getPort();
        LibraryBridge.reprobe();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange x, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(200, b.length);
        try (var o = x.getResponseBody()) { o.write(b); }
    }

    @Test
    void withoutADaemonThePushIsEmptyAndTheAnswerSaysSo() {
        LibraryBridge.urlOverride = "http://127.0.0.1:1";   // nothing listens on port 1
        LibraryBridge.reprobe();
        assertFalse(LibraryBridge.answers());
        assertEquals("", LibraryBridge.push("lead paint", 4), "a turn without the library is a normal turn");
        assertTrue(LibraryBridge.answer("lead paint", 5).startsWith("no library answers on"), LibraryBridge.answer("lead paint", 5));
    }

    @Test
    void withADaemonThePushIsTheRenderedPackage() throws Exception {
        fakeDaemon("{\"library_id\":\"lib-1\",\"holds_nothing\":false,\"entries\":[{}],\"rendered\":\"THE LIBRARIAN — holdings relevant to: lead paint\\n== F-0001 lead paint was banned in 1978\\n\"}");
        assertTrue(LibraryBridge.answers());
        String push = LibraryBridge.push("lead paint", 4);
        assertTrue(push.startsWith("THE LIBRARIAN") && push.endsWith("\n\n"), push);
        assertTrue(LibraryBridge.answer("lead paint", 5).contains("F-0001"));
        assertEquals(2, hits.size());
        assertTrue(hits.get(0).contains("\"question\":\"lead paint\"") && hits.get(0).contains("\"k\":4"), hits.get(0));
    }

    @Test
    void holdsNothingIsAnEmptyPush() throws Exception {
        fakeDaemon("{\"library_id\":\"lib-1\",\"holds_nothing\":true,\"entries\":[],\"rendered\":\"THE LIBRARIAN — holds nothing on: purple teapots\\n\"}");
        assertEquals("", LibraryBridge.push("purple teapots", 4));
        assertEquals("the library holds nothing on that", LibraryBridge.answer("purple teapots", 5));
    }

    @Test
    void compressKeepsShortTextAndCutsLong() {
        assertEquals("a b", LibraryBridge.compress("  a \n b ", 10));
        assertEquals("abcd…", LibraryBridge.compress("abcdefgh", 5));
    }
}
