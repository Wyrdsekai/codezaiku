package org.codezaiku.drive;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;


/** Behind llama-swap, /props names no model; the upstream server's /props does, and carries the real window. */
class DriveContextWindowTest {
    @Test
    void theWindowComesFromTheUpstreamPropsBehindLlamaSwap() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            byte[] out; int code;
            if (path.equals("/props")) { code = 404; out = "{\"src\":\"llama-swap\",\"error\":{\"message\":\"no model id could be identified\"}}".getBytes(StandardCharsets.UTF_8); }
            else if (path.equals("/upstream/qwen3.8-27b/props")) { code = 200; out = "{\"default_generation_settings\":{\"n_ctx\":32768,\"params\":{}},\"total_slots\":4}".getBytes(StandardCharsets.UTF_8); }
            else if (path.equals("/v1/models")) { code = 200; out = "{\"data\":[{\"id\":\"qwen3.8-27b\"}]}".getBytes(StandardCharsets.UTF_8); }
            else { code = 404; out = "{}".getBytes(StandardCharsets.UTF_8); }
            ex.sendResponseHeaders(code, out.length); ex.getResponseBody().write(out); ex.close();
        });
        s.start();
        try {
            String base = "http://127.0.0.1:" + s.getAddress().getPort();
            // the test task may pin CODEZAIKU_CTX, so the probe is asked directly
            assertEquals(32768, new DriveClient(base, "qwen3.8-27b").fromLlamaCppProps());
            // and with no model name there is nothing to ask the proxy for: this is why doctor must pass the configured
            // model, which it did not (it reported 8192 behind llama-swap while every run read 32768)
            assertNull(new DriveClient(base, "").fromLlamaCppProps());
            assertFalse(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/org/codezaiku/Doctor.java")).contains("new DriveClient(driveUrl, \"\")"), "doctor asks with the configured model");
        } finally { s.stop(0); }
    }
}
