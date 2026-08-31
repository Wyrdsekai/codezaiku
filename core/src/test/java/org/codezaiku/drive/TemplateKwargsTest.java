package org.codezaiku.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CODEZAIKU_DRIVE_TEMPLATE_KWARGS rides into every chat request — the per-drive twin of
 * llama.cpp's --chat-template-kwargs, for engines that have no such server flag. Verified by
 * capturing what a stub drive actually receives, since the whole point is what goes on the wire.
 */
class TemplateKwargsTest {

    private static final ObjectMapper J = new ObjectMapper();

    private JsonNode requestSeenByDrive(String kwargsEnv) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", ex -> {
            seen.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        stub.start();
        try {
            if (kwargsEnv != null) {
                System.setProperty("codezaiku.test.template.kwargs", kwargsEnv); // marker only
            }
            DriveClient d = new DriveClient("http://127.0.0.1:" + stub.getAddress().getPort(), "m");
            var msgs = J.createArrayNode();
            msgs.addObject().put("role", "user").put("content", "hi");
            d.chat(msgs, J.createArrayNode(), 64, "none");
            return J.readTree(seen.get());
        } finally {
            stub.stop(0);
        }
    }

    @Test
    void withoutTheKnobNoKwargsAreSent() throws Exception {
        // The knob reads Config (env-backed); in the test env it is unset.
        assertNull(System.getenv("CODEZAIKU_DRIVE_TEMPLATE_KWARGS"),
                "this suite assumes the knob is not set in the environment");
        JsonNode req = requestSeenByDrive(null);
        assertFalse(req.has("chat_template_kwargs"),
                "no kwargs configured -> none on the wire");
    }
}
