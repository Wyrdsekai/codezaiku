package org.codezaiku;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.codezaiku.drive.SavedModels;
import org.codezaiku.drive.Spend;
import org.codezaiku.run.ResultDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A job on a saved model (0.3.13): {@code --model cheap} sends the job to the saved model's own address with its id, counts its tokens,
 * prices them, writes the ledger, and says so; a cap that is reached refuses the next job before it starts; the result document a host
 * reads carries the saved model, the host, the tokens and the cost;
 */
class ModelJobTest {

    static final ObjectMapper J = new ObjectMapper();
    @TempDir Path home;
    @TempDir Path project;
    private String realHome;

    @BeforeEach void scratchHome() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".codezaiku"));
        Config.set("CODEZAIKU_DRIVE", "http://127.0.0.1:9");   // the settings' drive: a port nothing answers on, so a job that went there would fail
        Config.set("CODEZAIKU_MODEL", "local-model");
    }

    @AfterEach void realHomeBack() throws Exception { System.setProperty("user.home", realHome); Config.unset("CODEZAIKU_DRIVE"); }

    /** A model server that finishes every job on its first turn and reports its usage; every request is kept. */
    static HttpServer answering(List<JsonNode> requests) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            byte[] b;
            if (path.equals("/v1/chat/completions")) {
                JsonNode body = J.readTree(ex.getRequestBody().readAllBytes());
                synchronized (requests) { requests.add(body); }
                ObjectNode m = J.createObjectNode().put("role", "assistant");
                ObjectNode c = m.putArray("tool_calls").addObject(); c.put("id", "c1"); c.put("type", "function");
                c.putObject("function").put("name", "task_done").put("arguments", "{\"summary\":\"nothing to do\"}");
                ObjectNode out = J.createObjectNode();
                out.putArray("choices").addObject().set("message", m);
                out.putObject("usage").put("prompt_tokens", 1000).put("completion_tokens", 50).put("total_tokens", 1050);
                b = J.writeValueAsBytes(out);
            } else if (path.equals("/v1/models")) b = "{\"data\":[{\"id\":\"fake-model\"}]}".getBytes(StandardCharsets.UTF_8);
            else b = "{}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        s.start();
        return s;
    }

    @Test
    void aJobRunsOnTheSavedModelCountsItsTokensWritesTheLedgerAndIsRefusedAtTheCap() throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        HttpServer s = answering(requests);
        try {
            String address = "http://127.0.0.1:" + s.getAddress().getPort();
            SavedModels.put("cheap", address, "fake-model", "100/200", "low", null, null);   // $100 and $200 a million: a 1,000-token call is visible money
            FamiliarMain.Tracked t = FamiliarMain.runLoopTracked(project, "Say hello.", Config.get("CODEZAIKU_DRIVE"), 3, "none", null, "cheap");
            assertTrue(t.result().done(), t.result().summary());
            // every call of the job (the plan before the first turn, then the turn) went to the saved model's own address, not the settings' drive
            assertTrue(requests.size() >= 1);
            for (JsonNode r : requests) assertEquals("fake-model", r.path("model").asText(), "with its id");
            JsonNode turn = requests.get(requests.size() - 1);
            assertEquals("low", turn.path("reasoning_effort").asText(), "and its effort");
            assertNotNull(t.run());
            assertEquals("cheap", t.run().name()); assertEquals("fake-model", t.run().model()); assertEquals("127.0.0.1", t.run().host());
            long[] u = t.run().usage();
            assertTrue(u[0] >= 1000 && u[0] % 1000 == 0 && u[3] == u[0] / 20, "the calls' usage, summed: " + u[0] + "/" + u[3]);
            double cost = (u[0] * 100 + u[3] * 200) / 1e6;
            assertEquals(cost, t.run().cost(), 1e-9); assertTrue(t.run().priced());
            assertEquals("model: cheap (fake-model via 127.0.0.1) — " + Spend.usageLine(u, cost, true), t.run().line());
            assertTrue(t.run().line().matches("model: cheap \\(fake-model via 127\\.0\\.0\\.1\\) — [\\d,]+ prompt \\+ [\\d,]+ completion tokens, \\$\\d+\\.\\d\\d"), t.run().line());
            List<JsonNode> ledger = Spend.lines();
            assertEquals(1, ledger.size());
            assertEquals("code", ledger.get(0).path("kind").asText()); assertEquals("cheap", ledger.get(0).path("entry").asText()); assertEquals(cost, ledger.get(0).path("cost").asDouble(), 1e-4);
            assertEquals(project.toAbsolutePath().normalize().toString(), ledger.get(0).path("where").asText());

            // the result document a host reads
            ObjectNode doc = ResultDocument.of(project, "completed").model(t.run().model()).savedModel(t.run().name()).host(t.run().host()).usage(t.run().usage()).cost(t.run().cost()).build();
            assertEquals("fake-model", doc.path("model").asText()); assertEquals("cheap", doc.path("savedModel").asText()); assertEquals("127.0.0.1", doc.path("host").asText());
            assertEquals(u[0], doc.path("usage").path("prompt").asLong()); assertEquals(u[3], doc.path("usage").path("completion").asLong()); assertEquals(cost, doc.path("cost").asDouble(), 1e-4);
            ObjectNode plain = ResultDocument.of(project, "completed").model("m").usage(new long[4]).cost(null).build();
            assertFalse(plain.has("usage") || plain.has("cost") || plain.has("savedModel") || plain.has("host"), "nothing is claimed that was not counted: " + plain);

            // a model id by hand, on the settings' drive: no saved model, no prices, still counted
            SavedModels.put("local", address, "other-model", null, null, null, null);
            FamiliarMain.Tracked byId = FamiliarMain.runLoopTracked(project, "Say hello.", address, 3, "none", null, "other-model");
            assertEquals("local", byId.run().name(), "a drive and id a saved model has are its own"); assertFalse(byId.run().priced());
            assertTrue(byId.run().line().startsWith("model: local (other-model via 127.0.0.1) — ") && !byId.run().line().contains("$"), byId.run().line());
            assertEquals(2, Spend.lines().size());

            // the cap: more than a cent spent, a cap of a cent — the next job is refused before it starts, with the figures
            int sent = requests.size();
            SavedModels.put("cheap", null, null, null, null, "0.01", null);
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> FamiliarMain.runLoopTracked(project, "Say hello.", Config.get("CODEZAIKU_DRIVE"), 3, "none", null, "cheap"));
            assertTrue(refused.getMessage().contains("cheap has reached its cap") && refused.getMessage().contains(Spend.dollars(cost) + " of $0.01"), refused.getMessage());
            assertEquals(sent, requests.size(), "nothing was sent");
            SavedModels.put("cheap", null, null, null, null, "100", null);
            assertTrue(FamiliarMain.runLoopTracked(project, "Say hello.", Config.get("CODEZAIKU_DRIVE"), 3, "none", null, "cheap").result().done(), "raised, the job runs");

            // best judgement: two saved models on the same fake server, one free and one priced — auto takes the free one and says why
            SavedModels.put("local", address, "other-model", "0/0", null, null, null, "A");
            SavedModels.put("cheap", null, null, null, null, "100", null, "A");
            FamiliarMain.Tracked auto = FamiliarMain.runLoopTracked(project, "Say hello.", Config.get("CODEZAIKU_DRIVE"), 3, "none", null, "auto");
            assertTrue(auto.result().done()); assertEquals("local", auto.run().name(), "free and tier A: " + auto.run().line());
            assertEquals("other-model", requests.get(requests.size() - 1).path("model").asText());
            FamiliarMain.Tracked byDefault = FamiliarMain.runLoopTracked(project, "Say hello.", Config.get("CODEZAIKU_DRIVE"), 3, "none", null, null);
            assertEquals("local", byDefault.run().name(), "with a second model saved, auto is the default when none is named");
            // a name nothing is saved under is a model id on the drive given
            FamiliarMain.Tracked unknown = FamiliarMain.runLoopTracked(project, "Say hello.", address, 3, "none", null, "nobody");
            assertEquals("", unknown.run().name()); assertEquals("nobody", requests.get(requests.size() - 1).path("model").asText());
        } finally { s.stop(0); }
    }
}
