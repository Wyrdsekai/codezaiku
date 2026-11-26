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
import org.codezaiku.chat.ChatConsent;
import org.codezaiku.chat.ChatIo;
import org.codezaiku.chat.ChatRepl;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A streamed answer is taken in as a plain one is: its tokens counted into the same counts (the log line that is the meter, the
 * session's for /cost, the job's for its line, the ledger and through it a saved model's monthly cap), its way of ending kept; it asks
 * for its counts the way OpenAI's API documents it (stream_options.include_usage), and a server that refuses the field is asked once
 * more without it. A server that sends no counts gets none made up: nothing is counted, and that is said. The recovery of a malformed
 * call counts its tokens the same way. A stream that breaks with an error is a failed call, asked for again as a plain request, and the
 * chat marks the part it had shown and prints the answer once. The server is a stand-in on the loopback; nothing reaches the network.
 */
class StreamUsageTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String MODEL = "deepseek/deepseek-v4.1-flash";
    static final String SUMMARY = "Hello from the stand-in, all of it.";

    @TempDir Path home;
    @TempDir Path project;
    String realHome;
    HttpServer server; String drive;
    final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    /** The tokens each answer that carried counts said it used: {prompt, completion}. */
    final List<long[]> answered = new CopyOnWriteArrayList<>();

    /** How a streamed request is answered: counts on the last chunk; none; broken off by an error chunk after some text; refused for stream_options. */
    enum Stream { USAGE, NO_USAGE, BREAKS, REFUSES_OPTIONS }
    volatile Stream streams = Stream.USAGE;
    /** The router's own shape: the counts and the routing metadata on the final chunk, which repeats the finish_reason (OpenRouter's streaming page). */
    volatile boolean routerShape = false;
    volatile boolean plainUsage = true;
    /** A plain request answered 200 with an error in the body and no choices: a provider's failure as OpenRouter sends it. */
    volatile boolean plainFails = false;
    final AtomicInteger parseErrors = new AtomicInteger();
    volatile ObjectNode message = M.createObjectNode().put("role", "assistant").put("content", "ready");

    Function<String, String> fetch; LongSupplier now;
    ListAppender<ILoggingEvent> lines;

    @BeforeEach void up() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.resolve("home").toString()); Config.invalidate();
        Files.createDirectories(home.resolve("home").resolve(".codezaiku"));
        fetch = OpenRouterProviders.FETCH; now = OpenRouterProviders.NOW;
        OpenRouterProviders.FETCH = url -> null;
        OpenRouterProviders.forget();
        DriveClient.OpenRouterModels.OVERRIDE = Map.of();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            byte[] b; int code = 200; String type = "application/json";
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (x.getRequestURI().getPath().endsWith("/chat/completions")) {
                JsonNode req = M.readTree(body);
                bodies.add(req);
                if (req.path("stream").asBoolean(false)) {
                    if (streams == Stream.REFUSES_OPTIONS && req.has("stream_options")) {
                        code = 400; b = "{\"error\":{\"message\":\"Unrecognized request argument supplied: stream_options\",\"type\":\"invalid_request_error\"}}".getBytes(StandardCharsets.UTF_8);
                    } else { type = "text/event-stream"; b = stream(req).getBytes(StandardCharsets.UTF_8); }
                } else if (parseErrors.getAndDecrement() > 0) {
                    code = 500; b = "{\"error\":{\"message\":\"Failed to parse tool call arguments as JSON\"}}".getBytes(StandardCharsets.UTF_8);
                } else if (plainFails) {
                    b = "{\"error\":{\"message\":\"Provider returned error\",\"code\":502}}".getBytes(StandardCharsets.UTF_8);
                } else b = M.writeValueAsBytes(plain());
            } else { code = 404; b = "{}".getBytes(StandardCharsets.UTF_8); }
            x.getResponseHeaders().add("Content-Type", type);
            x.sendResponseHeaders(code, b.length); x.getResponseBody().write(b); x.close();
        });
        server.start();
        drive = "http://127.0.0.1:" + server.getAddress().getPort();
        lines = new ListAppender<>(); lines.start();
        ((Logger) LoggerFactory.getLogger(DriveClient.class)).addAppender(lines);
    }

    @AfterEach void down() throws Exception {
        ((Logger) LoggerFactory.getLogger(DriveClient.class)).detachAppender(lines);
        server.stop(0);
        DriveClient.ROUTER_FOR_TESTS = null; DriveClient.streamTo(null); DriveClient.stopCounting(); DriveClient.USAGE_SINK.remove();
        DriveClient.OpenRouterModels.OVERRIDE = null;
        OpenRouterProviders.FETCH = fetch; OpenRouterProviders.NOW = now; OpenRouterProviders.forget();
        System.setProperty("user.home", realHome); Config.invalidate();
    }

    ObjectNode usage(long prompt, long completion) { answered.add(new long[]{prompt, completion}); return M.createObjectNode().put("prompt_tokens", prompt).put("completion_tokens", completion).put("total_tokens", prompt + completion); }

    ObjectNode plain() {
        ObjectNode out = M.createObjectNode().put("id", "chatcmpl-1").put("object", "chat.completion").put("model", MODEL);
        out.putArray("choices").addObject().put("index", 0).put("finish_reason", message.has("tool_calls") ? "tool_calls" : "stop").set("message", message.deepCopy());
        if (plainUsage) out.set("usage", usage(100, 5));
        return out;
    }

    static ObjectNode chunk(ObjectNode delta, String finish) {
        ObjectNode c = M.createObjectNode().put("id", "chatcmpl-1").put("object", "chat.completion.chunk").put("model", MODEL);
        ObjectNode choice = c.putArray("choices").addObject().put("index", 0);
        choice.set("delta", delta);
        if (finish == null) choice.putNull("finish_reason"); else choice.put("finish_reason", finish);
        return c;
    }

    /**
     * A stream in the shapes the documentation shows: the reply in pieces (words, or a tool call whose arguments come in fragments);
     * then, as OpenAI's API documents include_usage, a finishing chunk and one more chunk with no choices and the counts; or, as
     * OpenRouter sends it, one finishing chunk that carries the counts and the routing metadata; or, broken off, OpenRouter's example of
     * an error in the middle of a stream.
     */
    String stream(JsonNode req) throws IOException {
        List<ObjectNode> pieces = new ArrayList<>();
        boolean call = message.has("tool_calls") && req.path("tools").size() > 0;
        if (call) {
            JsonNode tc = message.path("tool_calls").path(0);
            String args = tc.path("function").path("arguments").asText();
            ObjectNode first = M.createObjectNode();
            ObjectNode one = first.putArray("tool_calls").addObject().put("index", 0).put("id", tc.path("id").asText()).put("type", "function");
            one.putObject("function").put("name", tc.path("function").path("name").asText()).put("arguments", "");
            pieces.add(first);
            int cut = Math.min(20, args.length());
            for (String frag : List.of(args.substring(0, cut), args.substring(cut))) {
                ObjectNode d = M.createObjectNode();
                d.putArray("tool_calls").addObject().put("index", 0).putObject("function").put("arguments", frag);
                pieces.add(d);
            }
        } else {
            String text = message.path("content").asText();
            pieces.add(M.createObjectNode().put("content", text.substring(0, 3)));
            pieces.add(M.createObjectNode().put("content", text.substring(3)));
        }
        StringBuilder s = new StringBuilder(": OPENROUTER PROCESSING\n\n");
        if (streams == Stream.BREAKS) {
            for (ObjectNode p : pieces.subList(0, call ? 2 : 1)) s.append("data: ").append(M.writeValueAsString(chunk(p, null))).append("\n\n");
            s.append("data: {\"id\":\"cmpl-abc123\",\"object\":\"chat.completion.chunk\",\"created\":1234567890,\"model\":\"" + MODEL + "\",\"provider\":\"openai\",")
             .append("\"error\":{\"code\":502,\"message\":\"Provider disconnected unexpectedly\"},\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"},\"finish_reason\":\"error\"}]}\n\n");
            return s.toString();
        }
        for (ObjectNode p : pieces) { ObjectNode c = chunk(p, null); if (req.has("stream_options")) c.putNull("usage"); s.append("data: ").append(M.writeValueAsString(c)).append("\n\n"); }
        String finish = call ? "tool_calls" : "stop";
        boolean counts = streams != Stream.NO_USAGE;
        if (routerShape) {
            ObjectNode last = chunk(M.createObjectNode().put("content", ""), finish);
            if (counts) last.set("usage", usage(300, 7));
            ObjectNode md = last.putObject("openrouter_metadata").put("requested", MODEL);
            md.putObject("endpoints").put("total", 1).putArray("available").addObject().put("provider", "DeepInfra").put("model", MODEL).put("selected", true);
            s.append("data: ").append(M.writeValueAsString(last)).append("\n\n");
        } else {
            s.append("data: ").append(M.writeValueAsString(chunk(M.createObjectNode(), finish))).append("\n\n");
            if (counts) {
                ObjectNode last = M.createObjectNode().put("id", "chatcmpl-1").put("object", "chat.completion.chunk").put("model", MODEL);
                last.putArray("choices");
                last.set("usage", usage(300, 7));
                s.append("data: ").append(M.writeValueAsString(last)).append("\n\n");
            }
        }
        return s.append("data: [DONE]\n\n").toString();
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

    void streaming(StringBuilder shown) throws IOException { Config.set("CODEZAIKU_STREAM", "on"); DriveClient.streamTo(shown::append); }

    /**
     * A streamed answer's tokens go where a plain answer's go: the job's count, the session's, the last call's figures, how full the
     * context is, the log line; its way of ending is kept. The stream asked for the counts as OpenAI's API documents it. The control is
     * a plain answer, counted the same way after it; and the router's own shape (the counts on the final chunk beside the metadata).
     */
    @Test
    void aStreamedAnswerIsCountedAsAPlainOneIs() throws Exception {
        long[] job = new long[4];
        DriveClient.countInto(job);
        List<Integer> context = new ArrayList<>();
        DriveClient.USAGE_SINK.set(context::add);
        long sessionBefore = DriveClient.SESSION_PROMPT_TOKENS.get(), completionBefore = DriveClient.SESSION_COMPLETION_TOKENS.get();
        StringBuilder shown = new StringBuilder();
        streaming(shown);
        DriveClient c = new DriveClient(drive, MODEL);
        assertEquals("ready", c.chat(hello(), null, 32, "none").path("content").asText());
        assertEquals("ready", shown.toString(), "it came as a stream (the control)");
        assertEquals(1, bodies.size(), "one request");
        assertTrue(bodies.get(0).path("stream_options").path("include_usage").asBoolean(false), "the stream asked for its counts: " + bodies.get(0));
        assertArrayEquals(new long[]{300, 0, 0, 7}, job, "the job's count");
        assertEquals(300, DriveClient.SESSION_PROMPT_TOKENS.get() - sessionBefore, "the session's (/cost)");
        assertEquals(7, DriveClient.SESSION_COMPLETION_TOKENS.get() - completionBefore);
        assertEquals(300, c.lastPromptTokens()); assertEquals(7, c.lastCompletionTokens()); assertEquals("stop", c.lastFinishReason());
        assertEquals(List.of(307), context, "how full the context is");
        assertEquals(List.of("usage ← prompt 300 completion 7 total 307"), logged("usage ←"), "the line that is the meter");
        // the control: a plain answer after it, counted the same way
        DriveClient.streamTo(null); Config.unset("CODEZAIKU_STREAM");
        c.chat(hello(), null, 32, "none");
        assertFalse(bodies.get(1).has("stream_options"), "a plain request asks for no stream's counts");
        assertArrayEquals(new long[]{400, 0, 0, 12}, job);
        assertEquals(List.of(307, 105), context);
        assertEquals(2, logged("usage ←").size());
        // through the router: the counts on the final chunk, beside who served it
        DriveClient.ROUTER_FOR_TESTS = drive; routerShape = true;
        streaming(shown);
        new DriveClient(drive, MODEL).chat(hello(), null, 32, "none");
        assertArrayEquals(new long[]{700, 0, 0, 19}, job);
        assertEquals("usage ← prompt 300 completion 7 total 307, served by DeepInfra", logged("usage ←").get(2));
        assertEquals(Map.of("DeepInfra", 1), DriveClient.servedBy(job));
        assertEquals(0, DriveClient.unreported(job));
    }

    /**
     * A server that sends no counts with a stream (or with a plain answer) gets none made up: nothing is counted for that answer, the
     * log says so, and the job keeps how many such answers it had, which what is said of its tokens then names.
     */
    @Test
    void anAnswerWithoutCountsIsCountedAsNothingAndSaidSo() throws Exception {
        long[] job = new long[4];
        DriveClient.countInto(job);
        streams = Stream.NO_USAGE;
        StringBuilder shown = new StringBuilder();
        streaming(shown);
        DriveClient c = new DriveClient(drive, MODEL);
        assertEquals("ready", c.chat(hello(), null, 32, "none").path("content").asText());
        assertEquals("ready", shown.toString(), "it streamed whole (the control)");
        assertArrayEquals(new long[4], job, "no figure made up");
        assertEquals(1, DriveClient.unreported(job));
        assertEquals(List.of("stream ← the model server sent no token counts; this answer's tokens are not counted"), logged("stream ←"));
        DriveClient.streamTo(null); Config.unset("CODEZAIKU_STREAM");
        plainUsage = false;
        c.chat(hello(), null, 32, "none");
        assertEquals(2, DriveClient.unreported(job));
        assertEquals(List.of("answer ← the model server sent no token counts; this answer's tokens are not counted"), logged("answer ←"));
        assertEquals("2 answers came without token counts from the model server", Spend.unreportedLine(DriveClient.unreported(job)));
        assertEquals("1 answer came without token counts from the model server", Spend.unreportedLine(1));
        assertEquals("", Spend.unreportedLine(0));
        DriveClient.Tally t = DriveClient.take(job);
        assertEquals(2, t.unreported());
        assertEquals(0, DriveClient.unreported(job), "taken, it begins again");
    }

    /** A server that refuses stream_options is asked once more without it, still as a stream; the same drive does not send it again. */
    @Test
    void aServerThatRefusesStreamOptionsIsAskedAgainWithoutThem() throws Exception {
        long[] job = new long[4];
        DriveClient.countInto(job);
        streams = Stream.REFUSES_OPTIONS;
        StringBuilder shown = new StringBuilder();
        streaming(shown);
        DriveClient c = new DriveClient(drive, MODEL);
        assertEquals("ready", c.chat(hello(), null, 32, "none").path("content").asText());
        assertEquals("ready", shown.toString(), "it streamed, after the refusal");
        assertEquals(2, bodies.size());
        assertTrue(bodies.get(0).has("stream_options") && bodies.get(0).path("stream").asBoolean());
        assertTrue(!bodies.get(1).has("stream_options") && bodies.get(1).path("stream").asBoolean(), "asked again as a stream, without the field");
        assertArrayEquals(new long[]{300, 0, 0, 7}, job, "the counts it sent of its own accord");
        c.chat(hello(), null, 32, "none");
        assertEquals(3, bodies.size());
        assertFalse(bodies.get(2).has("stream_options"), "not sent again to this drive");
    }

    /**
     * A stream that breaks with an error (OpenRouter's example: an error object at the top and finish_reason "error", after part of the
     * reply) is a failed call: whoever shows the stream is told which part was shown, the reply is asked for again as a plain request,
     * and only that request's tokens are counted, as a failed plain call's are not. When the plain request fails too, the call fails as
     * a plain answer that carries an error does.
     */
    @Test
    void aStreamThatBreaksWithAnErrorIsAFailedCallAskedForAgain() throws Exception {
        long[] job = new long[4];
        DriveClient.countInto(job);
        streams = Stream.BREAKS;
        StringBuilder shown = new StringBuilder();
        List<String> broken = new ArrayList<>();
        Config.set("CODEZAIKU_STREAM", "on"); DriveClient.streamTo(shown::append, shown::append, broken::add);
        DriveClient c = new DriveClient(drive, MODEL);
        assertEquals("ready", c.chat(hello(), null, 32, "none").path("content").asText(), "the answer is the plain request's, whole");
        assertEquals("rea", shown.toString(), "part of the reply had streamed (the control)");
        assertEquals(List.of("rea"), broken, "and the screen was told that part is not the answer");
        assertEquals(2, bodies.size());
        assertTrue(bodies.get(0).path("stream").asBoolean() && !bodies.get(1).path("stream").asBoolean(false), "the stream, then the plain request");
        assertArrayEquals(new long[]{100, 0, 0, 5}, job, "the plain answer's tokens only");
        assertEquals(1, logged("the model server reported an error in the middle of the reply: Provider disconnected unexpectedly").size());
        // the plain request fails too: a failed call, as it always was for a plain answer with an error in it
        plainFails = true;
        IllegalStateException failed = assertThrows(IllegalStateException.class, () -> c.chat(hello(), null, 32, "none"));
        assertTrue(failed.getMessage().contains("drive answered an error: Provider returned error"), failed.getMessage());
    }

    /** The recovery of a malformed tool call is an answer like any other: its tokens are counted with the job's and the session's. */
    @Test
    void theRecoveryOfAMalformedCallCountsItsTokens() {
        long[] job = new long[4];
        DriveClient.countInto(job);
        long sessionBefore = DriveClient.SESSION_PROMPT_TOKENS.get();
        parseErrors.set(4);   // four answers of a malformed tool call (no counts), then the text-mode request that recovers it
        message = M.createObjectNode().put("role", "assistant").put("content", "{\"name\":\"done\",\"arguments\":{}}");
        DriveClient c = new DriveClient(drive, MODEL);
        assertEquals("done", c.chat(hello(), aTool(), 32, "auto").path("tool_calls").path(0).path("function").path("name").asText(), "the call was recovered (the control)");
        assertEquals(5, bodies.size());
        assertArrayEquals(new long[]{100, 0, 0, 5}, job, "the recovery's tokens");
        assertEquals(100, DriveClient.SESSION_PROMPT_TOKENS.get() - sessionBefore);
        assertEquals(List.of("usage ← prompt 100 completion 5 total 105"), logged("usage ←"));
    }

    /** A chat that talks through lines given to it and keeps what it prints. */
    static final class Script implements ChatIo {
        final Deque<String> in; final StringBuilder out = new StringBuilder();
        Script(String... lines) { in = new ArrayDeque<>(List.of(lines)); }
        @Override public String readLine(String prompt) { out.append(prompt); String l = in.poll(); if (l != null) out.append(l).append('\n'); return l; }
        @Override public void println(String s) { out.append(s).append('\n'); }
        @Override public void print(String s) { out.append(s); }
        @Override public void close() { }
    }

    void finishes(String summary) {
        ObjectNode done = M.createObjectNode().put("role", "assistant");
        ObjectNode call = done.putArray("tool_calls").addObject().put("id", "c1").put("type", "function");
        call.putObject("function").put("name", "task_done").put("arguments", "{\"summary\":\"" + summary + "\"}");
        message = done;
    }

    /**
     * The chat with streaming on, on a saved model with prices and a monthly cap: /cost shows the streamed turn's tokens, the session's
     * line on the ledger carries them, and the cap, which reads the ledger when a job or a chat starts, refuses the next job.
     */
    @Test
    void aStreamedChatTurnIsOnCostOnTheLedgerAndUnderTheCap() throws Exception {
        SavedModels.put("sr", drive, MODEL, "100/200", null, "0.01", null);   // $100 and $200 a million: a turn is visible money; a cap of a cent
        Config.set("CODEZAIKU_STREAM", "on");
        finishes(SUMMARY);
        Script io = new Script("say hello", "/cost", "/exit");
        new ChatRepl(project, drive, MODEL, ChatConsent.Mode.ASK, 6).entry(SavedModels.get("sr")).io(io).run();
        String out = io.out.toString();
        assertTrue(bodies.stream().anyMatch(b -> b.path("stream").asBoolean(false)), "the turn streamed (the control): " + bodies.size());
        long prompt = 0, completion = 0;
        for (long[] a : answered) { prompt += a[0]; completion += a[1]; }
        assertTrue(prompt >= 300, "the stand-in's answers carried counts: " + prompt);
        double cost = (prompt * 100 + completion * 200) / 1e6;
        String line = "    since this model was chosen: " + Spend.usageLine(new long[]{prompt, 0, 0, completion}, cost, true);
        assertTrue(out.contains(line), "/cost shows the turn's tokens, the streamed ones among them:\n" + out);
        assertFalse(out.contains("no token usage reported yet"), out);
        List<JsonNode> ledger = Spend.lines();
        assertEquals(1, ledger.size(), "the session's line");
        assertEquals("chat", ledger.get(0).path("kind").asText());
        assertEquals(prompt, ledger.get(0).path("prompt").asLong()); assertEquals(completion, ledger.get(0).path("completion").asLong());
        assertEquals(cost, ledger.get(0).path("cost").asDouble(), 1e-4);
        assertNotNull(Spend.capReached(SavedModels.get("sr")), "the cap reads the streamed tokens");
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> FamiliarMain.runLoopTracked(project, "Say hello.", drive, 3, "none", null, "sr"));
        assertTrue(refused.getMessage().contains("sr has reached its cap"), refused.getMessage());
    }

    /**
     * A stream that breaks in the chat: the part it had shown is marked as broken off, and the answer, asked for again, is printed once
     * and whole. Before, the answer began with the part shown, was taken for shown already, and was never printed.
     */
    @Test
    void aStreamThatBreaksInTheChatIsMarkedAndTheAnswerShownOnce() throws Exception {
        Config.set("CODEZAIKU_STREAM", "on");
        streams = Stream.BREAKS;
        finishes(SUMMARY);
        Script io = new Script("say hello", "/exit");
        new ChatRepl(project, drive, MODEL, ChatConsent.Mode.ASK, 6).io(io).run();
        String out = io.out.toString();
        int partial = out.indexOf("Hello fr"), mark = out.indexOf("(the reply broke off here; asking for it again)");
        assertTrue(partial >= 0 && mark > partial, "the part shown, then the mark:\n" + out);
        int first = out.indexOf(SUMMARY);
        assertTrue(first > mark, "the answer, whole, after the mark:\n" + out);
        assertEquals(-1, out.indexOf(SUMMARY, first + 1), "and once:\n" + out);
    }

    /** /cost says when the model server sent no counts with its answers, and the session's ledger line keeps how many. */
    @Test
    void costSaysWhenTheServerSentNoCounts() throws Exception {
        Config.set("CODEZAIKU_STREAM", "on");
        streams = Stream.NO_USAGE; plainUsage = false;
        finishes(SUMMARY);
        Script io = new Script("say hello", "/cost", "/exit");
        new ChatRepl(project, drive, MODEL, ChatConsent.Mode.ASK, 6).io(io).run();
        String out = io.out.toString();
        assertTrue(out.contains("answer came without token counts from the model server") || out.contains("answers came without token counts from the model server"), out);
        List<JsonNode> ledger = Spend.lines();
        assertEquals(1, ledger.size(), "a line though no token was counted, so the record says requests went unmetered");
        assertEquals(0, ledger.get(0).path("prompt").asLong());
        assertEquals(bodies.size(), ledger.get(0).path("unreported").asInt(), "every answer of the session: " + ledger.get(0));
    }
}
