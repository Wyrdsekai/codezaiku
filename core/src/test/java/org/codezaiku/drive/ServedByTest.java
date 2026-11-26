package org.codezaiku.drive;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.codezaiku.Config;
import org.codezaiku.FamiliarMain;
import org.codezaiku.run.ResultDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A job's record names the provider that served each of its requests through OpenRouter: the router says which one in its answer when
 * the request asks it to (the header X-OpenRouter-Metadata, its documentation's "Router metadata"), on a plain answer and on a stream's
 * last chunk; the job keeps a tally, provider → requests, and says it on each call's log line, on its model line at the end, in the
 * ledger and in the document {@code codezaiku run} prints. A host that is no router, and an answer that names nobody, add nothing. The
 * router is a stand-in on the loopback that the client takes for OpenRouter's address ({@link DriveClient#ROUTER_FOR_TESTS}); nothing
 * here reaches the network.
 */
class ServedByTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String MODEL = "deepseek/deepseek-v4.1-flash";

    @TempDir Path home;
    @TempDir Path project;
    String realHome;
    HttpServer router; String drive;
    /** Each completion request as it came: its body, and its metadata header ("" when it had none). */
    final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    final List<String> asked = new CopyOnWriteArrayList<>();
    /** Who the stand-in names as the provider on its answer to the request of each index; "" names nobody. And whom it named, in order. */
    volatile IntFunction<String> serves = i -> "DeepInfra";
    final List<String> named = new CopyOnWriteArrayList<>();
    final AtomicInteger parseErrors = new AtomicInteger();
    /** The message every answer carries. */
    volatile ObjectNode message = M.createObjectNode().put("role", "assistant").put("content", "ready");
    Function<String, String> fetch; LongSupplier now;
    ListAppender<ILoggingEvent> lines;

    @BeforeEach void up() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.resolve("home").toString()); Config.invalidate();
        Files.createDirectories(home.resolve("home").resolve(".codezaiku"));
        fetch = OpenRouterProviders.FETCH; now = OpenRouterProviders.NOW;
        OpenRouterProviders.FETCH = url -> null;   // no reading of the router's lists leaves this machine
        OpenRouterProviders.forget();
        DriveClient.OpenRouterModels.OVERRIDE = Map.of();
        router = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        router.createContext("/", x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] b; int code = 200; String type = "application/json";
            if (x.getRequestURI().getPath().endsWith("/chat/completions")) {
                JsonNode req = M.readTree(body);
                int i = bodies.size();
                bodies.add(req);
                String header = x.getRequestHeaders().getFirst(DriveClient.ROUTER_METADATA);
                asked.add(header == null ? "" : header);
                if (parseErrors.getAndDecrement() > 0) { code = 500; b = "{\"error\":{\"message\":\"Failed to parse tool call arguments as JSON\"}}".getBytes(StandardCharsets.UTF_8); }
                else {
                    String who = serves.apply(i);
                    named.add(who);
                    if (req.path("stream").asBoolean(false)) { type = "text/event-stream"; b = stream(who).getBytes(StandardCharsets.UTF_8); }
                    else b = M.writeValueAsBytes(answer(who));
                }
            } else { code = 404; b = "{}".getBytes(StandardCharsets.UTF_8); }
            x.getResponseHeaders().add("Content-Type", type);
            x.sendResponseHeaders(code, b.length); x.getResponseBody().write(b); x.close();
        });
        router.start();
        drive = "http://127.0.0.1:" + router.getAddress().getPort();
        DriveClient.ROUTER_FOR_TESTS = drive;
        lines = new ListAppender<>(); lines.start();
        ((Logger) LoggerFactory.getLogger(DriveClient.class)).addAppender(lines);
    }

    @AfterEach void down() {
        ((Logger) LoggerFactory.getLogger(DriveClient.class)).detachAppender(lines);
        router.stop(0);
        DriveClient.ROUTER_FOR_TESTS = null; DriveClient.streamTo(null); DriveClient.stopCounting();
        DriveClient.OpenRouterModels.OVERRIDE = null;
        OpenRouterProviders.FETCH = fetch; OpenRouterProviders.NOW = now; OpenRouterProviders.forget();
        System.setProperty("user.home", realHome); Config.invalidate();
    }

    /** The routing metadata as OpenRouter's documentation shows it: a candidate the router did not pick, then the one marked selected. */
    static ObjectNode metadata(String who) {
        ObjectNode md = M.createObjectNode().put("requested", MODEL).put("strategy", "direct").put("region", "iad")
                .put("summary", "available=2, selected=" + who).put("attempt", 1).put("is_byok", false);
        ObjectNode endpoints = md.putObject("endpoints").put("total", 2);
        ArrayNode available = endpoints.putArray("available");
        available.addObject().put("provider", "Parasail").put("model", MODEL).put("selected", false);   // not the one: the first is no answer
        available.addObject().put("provider", who).put("model", MODEL).put("selected", true);
        return md;
    }

    /** A plain answer, naming {@code who} as the provider that served it ("" names nobody). */
    ObjectNode answer(String who) {
        ObjectNode out = M.createObjectNode().put("id", "gen-1").put("object", "chat.completion").put("model", MODEL);
        out.putArray("choices").addObject().put("index", 0).put("finish_reason", "stop").set("message", message.deepCopy());
        out.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 5).put("total_tokens", 105);
        if (!who.isEmpty()) out.set("openrouter_metadata", metadata(who));
        return out;
    }

    /** A stream as OpenRouter's documentation shows it: a comment line, the content in pieces, then usage and the routing metadata on the last chunk before [DONE]. */
    static String stream(String who) throws IOException {
        StringBuilder s = new StringBuilder(": OPENROUTER PROCESSING\n\n");
        for (String piece : List.of("rea", "dy")) {
            ObjectNode c = M.createObjectNode().put("id", "gen-1").put("object", "chat.completion.chunk").put("model", MODEL);
            c.putArray("choices").addObject().put("index", 0).putNull("finish_reason").putObject("delta").put("content", piece);
            s.append("data: ").append(M.writeValueAsString(c)).append("\n\n");
        }
        ObjectNode last = M.createObjectNode().put("id", "gen-1").put("object", "chat.completion.chunk").put("model", MODEL);
        last.putArray("choices").addObject().put("index", 0).put("finish_reason", "stop").putObject("delta").put("content", "");
        last.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 5).put("total_tokens", 105);
        if (!who.isEmpty()) last.set("openrouter_metadata", metadata(who));
        return s.append("data: ").append(M.writeValueAsString(last)).append("\n\ndata: [DONE]\n\n").toString();
    }

    static ArrayNode hello() { ArrayNode m = M.createArrayNode(); m.addObject().put("role", "user").put("content", "hi"); return m; }

    static ArrayNode aTool() {
        ArrayNode t = M.createArrayNode();
        ObjectNode f = t.addObject().put("type", "function").putObject("function").put("name", "done");
        f.putObject("parameters").put("type", "object").putObject("properties");
        return t;
    }

    List<String> logged(String containing) {
        List<String> out = new ArrayList<>();
        for (ILoggingEvent e : lines.list) if (e.getFormattedMessage().contains(containing)) out.add(e.getFormattedMessage());
        return out;
    }

    /**
     * A plain answer names the provider in openrouter_metadata, the endpoint marked selected: a turn, a decision and the recovery of a
     * malformed call are each counted for the job and said on the call's line, and every request to the router asked for the metadata.
     */
    @Test
    void aPlainAnswerNamesTheProviderThatServedIt() {
        long[] usage = new long[4];
        DriveClient.countInto(usage);
        DriveClient c = new DriveClient(drive, MODEL);
        assertEquals("ready", c.chat(hello(), null, 32, "none").path("content").asText(), "the turn was answered (the control)");
        assertEquals(Map.of("DeepInfra", 1), DriveClient.servedBy(usage));
        assertEquals(List.of("usage ← prompt 100 completion 5 total 105, served by DeepInfra"), logged("usage ←"), "the call's line says who served it");
        serves = i -> "Novita";
        assertEquals("ready", c.classify(hello(), 8), "a decision");
        assertEquals(List.of("decision ← served by Novita"), logged("decision ←"));
        // four answers of a malformed tool call, then the text-mode request that recovers it: the four served nobody, the fifth was served
        serves = i -> "Chutes";
        parseErrors.set(4);
        message = M.createObjectNode().put("role", "assistant").put("content", "{\"name\":\"done\",\"arguments\":{}}");
        assertEquals("done", c.chat(hello(), aTool(), 32, "auto").path("tool_calls").path(0).path("function").path("name").asText(), "the call was recovered (the control)");
        assertEquals("usage ← prompt 100 completion 5 total 105, served by Chutes", logged("usage ←").get(logged("usage ←").size() - 1), "the recovery's line, its tokens counted");
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("DeepInfra", 1); expected.put("Novita", 1); expected.put("Chutes", 1);
        assertEquals(expected, DriveClient.servedBy(usage), "every completion the router served, once each");
        assertEquals(7, asked.size(), "a turn, a decision, four refused and the recovery reached the stand-in");
        for (String h : asked) assertEquals("enabled", h, "each asked the router to say who served it");
        assertEquals("served by DeepInfra (1 request), Novita (1 request), Chutes (1 request)", Spend.servedLine(DriveClient.servedBy(usage)));
        // the tally is the job's: a count of its own begins empty, and the chat's way of starting again empties it
        assertEquals(Map.of(), DriveClient.servedBy(new long[4]));
        assertEquals(expected, DriveClient.take(usage).servedBy());
        assertEquals(Map.of(), DriveClient.servedBy(usage));
    }

    /** A stream names the provider on its last chunk, before [DONE]: counted once, when the stream ended whole, and said on its line. */
    @Test
    void aStreamNamesItOnItsLastChunk() throws Exception {
        long[] usage = new long[4];
        DriveClient.countInto(usage);
        StringBuilder shown = new StringBuilder();
        Config.set("CODEZAIKU_STREAM", "on"); DriveClient.streamTo(shown::append);
        try {
            serves = i -> "Parasail";
            JsonNode said = new DriveClient(drive, MODEL).chat(hello(), null, 32, "none");
            assertEquals("ready", said.path("content").asText());
            assertEquals("ready", shown.toString(), "it came as a stream (the control)");
            assertEquals(1, bodies.size(), "one request, and no plain one after it");
            assertTrue(bodies.get(0).path("stream").asBoolean(false));
            assertEquals("enabled", asked.get(0));
            assertEquals(Map.of("Parasail", 1), DriveClient.servedBy(usage), "the provider of the last chunk, not the candidate beside it");
            assertEquals(List.of("usage ← prompt 100 completion 5 total 105, served by Parasail"), logged("usage ←"), "the stream's line, as a plain answer's");
        } finally { DriveClient.streamTo(null); Config.unset("CODEZAIKU_STREAM"); }
    }

    /**
     * Two providers over one job: the router hands its requests to one and then the other. The job's model line, its ledger line and the
     * document {@code codezaiku run} prints each name both, with as many requests as the router said each served.
     */
    @Test
    void twoProvidersOverOneJobAreBothOnItsRecord() throws Exception {
        serves = i -> i % 2 == 0 ? "DeepInfra" : "Novita";
        ObjectNode done = M.createObjectNode().put("role", "assistant");
        ObjectNode call = done.putArray("tool_calls").addObject().put("id", "c1").put("type", "function");
        call.putObject("function").put("name", "task_done").put("arguments", "{\"summary\":\"nothing to do\"}");
        message = done;
        FamiliarMain.Tracked t = FamiliarMain.runLoopTracked(project, "Say hello.", drive, 3, "none", null, MODEL);
        assertTrue(t.result().done(), t.result().summary());
        Map<String, Integer> said = new LinkedHashMap<>();
        for (String who : named) said.merge(who, 1, Integer::sum);
        assertTrue(said.size() == 2 && bodies.size() >= 2, "the job made requests that both providers served (the control): " + named);
        for (String h : asked) assertEquals("enabled", h);
        Map<String, Integer> served = t.run().servedBy();
        assertEquals(said, served, "the job's tally is what the router said, request by request");
        String line = t.run().line();
        assertTrue(line.startsWith("model: " + MODEL + " via 127.0.0.1 — ") && line.contains(" — " + Spend.servedLine(served)), line);
        assertTrue(Spend.servedLine(served).matches("served by (DeepInfra|Novita) \\(\\d+ requests?\\), (DeepInfra|Novita) \\(\\d+ requests?\\)"), line);
        List<JsonNode> ledger = Spend.lines();
        assertEquals(1, ledger.size());
        assertEquals(M.valueToTree(said), ledger.get(0).path("served"), "the ledger's line: " + ledger.get(0));
        ObjectNode doc = ResultDocument.of(project, "untested").model(t.run().model()).host(t.run().host()).usage(t.run().usage()).servedBy(t.run().servedBy()).build();
        assertEquals(M.valueToTree(said), doc.path("servedBy"), "the document a host reads: " + doc);
    }

    /**
     * An answer that names nobody, and a host that is no router, add nothing to the job's record and no words to any line; a host that
     * is no router is not asked to say who served. The control: the same stand-in taken for the router, naming a provider, is counted.
     */
    @Test
    void anAnswerThatNamesNobodyAndAHostThatIsNoRouterRecordNothing() {
        long[] control = new long[4];
        DriveClient.countInto(control);
        new DriveClient(drive, MODEL).chat(hello(), null, 32, "none");
        assertEquals(Map.of("DeepInfra", 1), DriveClient.servedBy(control), "the control");
        // the router answers, naming nobody
        long[] nobody = new long[4];
        DriveClient.countInto(nobody);
        serves = i -> "";
        DriveClient c = new DriveClient(drive, MODEL);
        assertEquals("ready", c.chat(hello(), null, 32, "none").path("content").asText());
        assertEquals("ready", c.classify(hello(), 8));
        assertTrue(nobody[0] > 0, "the tokens were counted");
        assertEquals(Map.of(), DriveClient.servedBy(nobody));
        assertEquals("", Spend.servedLine(DriveClient.servedBy(nobody)));
        // a host that is no router, whose answers carry the same metadata: not asked, not read
        DriveClient.ROUTER_FOR_TESTS = null;
        long[] other = new long[4];
        DriveClient.countInto(other);
        serves = i -> "DeepInfra";
        int before = asked.size();
        assertEquals("ready", new DriveClient(drive, MODEL).chat(hello(), null, 32, "none").path("content").asText());
        assertEquals("ready", new DriveClient(drive, MODEL).classify(hello(), 8));
        assertEquals(Map.of(), DriveClient.servedBy(other));
        assertEquals(List.of("", ""), asked.subList(before, asked.size()), "no header of the router's to another host");
        assertEquals(1, logged("served by").size(), "the control's line only: " + logged("served by"));
        FamiliarMain.ModelRun run = new FamiliarMain.ModelRun("", MODEL, "127.0.0.1", other, 0, false, null, DriveClient.servedBy(other));
        assertFalse(run.line().contains("served by"), run.line());
        ObjectNode doc = ResultDocument.of(project, "untested").model(MODEL).servedBy(DriveClient.servedBy(other)).build();
        assertFalse(doc.has("servedBy"), doc.toString());
        // how a name is read: only the endpoint marked selected in the routing metadata, the one field OpenRouter's reference defines
        // for it; a provider at the top of an answer is in no schema of the reference (only in an example of an error) and is not read
        assertEquals("Novita", DriveClient.whoServed(answer("Novita")));
        assertNull(DriveClient.whoServed(answer("")));
        assertNull(DriveClient.whoServed(answer("").put("provider", "Together")));
        ObjectNode noneSelected = M.createObjectNode();
        noneSelected.putObject("openrouter_metadata").putObject("endpoints").putArray("available").addObject().put("provider", "Parasail").put("selected", false);
        assertNull(DriveClient.whoServed(noneSelected), "a candidate the router did not pick served nothing");
        // a name is the router's text: one short printable line
        assertEquals("Deep Infra", DriveClient.whoServed(answer("Deep\nInfra‮")));
        assertNull(DriveClient.whoServed(answer(" \t")));
        assertEquals(60, DriveClient.whoServed(answer("x".repeat(500))).length());
    }

    /**
     * A request that asks who served it gets the routing metadata on the router's errors too, and that metadata names endpoints and
     * providers whatever the error was (the documentation's 404 for no provider left, and its 403). The router's 404 is read by its
     * message: a model it does not know stays its own error, and its own words that no provider is left are still said as CodeZaiku
     * says them, with the setting that allows them. Nothing of an error is counted as served.
     */
    @Test
    void theRoutersErrorsCarryTheMetadataAndAreReadByTheirMessage() {
        long[] usage = new long[4];
        DriveClient.countInto(usage);
        ObjectNode unknown = M.createObjectNode();
        unknown.putObject("error").put("code", 404).put("message", "The model does not exist.");
        ObjectNode md = metadata("OpenAI"); md.put("attempt", 0);
        for (JsonNode e : md.path("endpoints").path("available")) ((ObjectNode) e).put("selected", false);
        unknown.set("openrouter_metadata", md);
        assertTrue(unknown.toString().contains("endpoints") && unknown.toString().contains("provider"), "the body names both words (the instrument)");
        assertNull(DriveClient.nobodyServes(drive, MODEL, 404, unknown.toString()), "a model the router does not know is not taken for no provider left");
        ObjectNode noneLeft = unknown.deepCopy();
        ((ObjectNode) noneLeft.path("error")).put("message", "No allowed providers are available for the selected model.");
        String said = DriveClient.nobodyServes(drive, MODEL, 404, noneLeft.toString());
        assertTrue(said != null && said.contains("codezaiku config set avoid_places"), "no provider left is still said as CodeZaiku says it: " + said);
        assertTrue(DriveClient.nobodyServes(drive, MODEL, 404, "All providers have been ignored.") != null, "words that are not JSON are read as they came");
        assertNull(DriveClient.whoServed(noneLeft), "and nobody served it");
        assertEquals(Map.of(), DriveClient.servedBy(usage));
    }
}
