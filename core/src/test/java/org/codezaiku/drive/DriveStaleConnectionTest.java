package org.codezaiku.drive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model server that closes a kept-alive connection under the client. llama.cpp's server drops a connection idle for 5 seconds; the
 * JVM's pool reused such connections for up to 20 minutes, so a request sent at that moment failed ("HTTP/1.1 header parser received
 * no bytes") or, through a port proxy, was swallowed and waited out the call's limit (2026-10-06). The client sends such a request once
 * more on a fresh connection; a request the server is slow on is not sent twice.
 */
class DriveStaleConnectionTest {

    static final ObjectMapper M = new ObjectMapper();

    /** Speaks just enough HTTP/1.1: the first request on a connection is answered and the connection kept; the second is read and then dropped. */
    static final class DroppingServer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        final AtomicInteger requests = new AtomicInteger(), connections = new AtomicInteger(), dropped = new AtomicInteger();
        final Thread thread;

        DroppingServer() throws IOException {
            thread = new Thread(() -> {
                try {
                    while (!server.isClosed()) {
                        Socket s = server.accept();
                        connections.incrementAndGet();
                        new Thread(() -> serve(s)).start();
                    }
                } catch (IOException closed) { /* done */ }
            });
            thread.setDaemon(true);
            thread.start();
        }

        String base() { return "http://127.0.0.1:" + server.getLocalPort(); }

        private void serve(Socket s) {
            try (s; InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
                for (int onThisConnection = 1; ; onThisConnection++) {
                    if (!readRequest(in)) return;                // the client closed
                    requests.incrementAndGet();
                    if (onThisConnection == 2) { dropped.incrementAndGet(); return; }   // close without a word, as the server at its idle limit does
                    byte[] body = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"answer " + requests.get() + "\"},\"finish_reason\":\"stop\"}],"
                            + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}").getBytes(StandardCharsets.UTF_8);
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: keep-alive\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(body);
                    out.flush();
                }
            } catch (IOException ignored) { }
        }

        /** Reads one request (headers, then Content-Length bytes of body). False when the connection ended before a request. */
        private static boolean readRequest(InputStream in) throws IOException {
            StringBuilder head = new StringBuilder();
            int c, len = -1;
            while ((c = in.read()) != -1) {
                head.append((char) c);
                if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) break;
            }
            if (c == -1 && head.length() == 0) return false;
            for (String line : head.toString().split("\r\n")) if (line.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(line.substring(15).trim());
            for (int i = 0; i < len; i++) if (in.read() == -1) return false;
            return true;
        }

        @Override public void close() throws IOException { server.close(); }
    }

    static ArrayNode hello() {
        ArrayNode m = M.createArrayNode();
        m.addObject().put("role", "user").put("content", "hello");
        return m;
    }

    @Test
    void aRequestOnAConnectionTheServerClosedIsSentAgainOnce() throws Exception {
        try (DroppingServer srv = new DroppingServer()) {
            DriveClient d = new DriveClient(srv.base(), "m");
            long before = DriveClient.STALE_RETRIES.get();
            ObjectNode first = d.chat(hello(), M.createArrayNode(), 16, "none");
            assertEquals("answer 1", first.path("content").asText(), first.toString());
            // the pool reuses the connection; the server reads the request and drops the line
            ObjectNode second = d.chat(hello(), M.createArrayNode(), 16, "none");
            assertEquals("answer 3", second.path("content").asText(), "answered on a new connection: " + second);
            assertEquals(1, srv.dropped.get(), "the server dropped one request");
            assertEquals(2, srv.connections.get(), "the retry opened a second connection");
            assertEquals(before + 1, DriveClient.STALE_RETRIES.get(), "one request was sent again");
        }
    }

    @Test
    void whatCountsAsAStaleConnection() {
        assertTrue(DriveClient.staleConnection(new IOException("HTTP/1.1 header parser received no bytes")));
        assertTrue(DriveClient.staleConnection(new IOException("boom", new IOException("Connection reset"))));
        assertFalse(DriveClient.staleConnection(new HttpTimeoutException("request timed out")), "a slow server is never sent the request twice");
        assertFalse(DriveClient.staleConnection(new IOException("Received fatal alert: handshake_failure")));
    }

    @Test
    void theIdleLimitIsBelowTheServers() {
        String was = System.getProperty(HttpSettings.KEEPALIVE);
        try {
            System.clearProperty(HttpSettings.KEEPALIVE);
            assertEquals("3", HttpSettings.apply());
            System.setProperty(HttpSettings.KEEPALIVE, "7");
            assertEquals("7", HttpSettings.apply(), "an explicit setting is kept");
        } finally {
            if (was == null) System.clearProperty(HttpSettings.KEEPALIVE); else System.setProperty(HttpSettings.KEEPALIVE, was);
        }
    }
}
