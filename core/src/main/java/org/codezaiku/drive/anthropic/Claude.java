package org.codezaiku.drive.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
/**
 * The Claude API as the model behind the program, in its own format (the Messages API) and with the person's own key. The
 * setting is {@code drive = https://api.anthropic.com}, the key is {@code api.key}, and the model is a Claude model id such as
 * {@code claude-opus-5-5}.
 *
 * <p>What this gives that the API's OpenAI-style endpoint does not: the effort setting (CODEZAIKU_REASONING_EFFORT), prompt
 * caching, and a summary of the model's thinking in the log. What the model takes — its window, the most it writes in one
 * reply, whether it thinks adaptively, which effort levels it knows — is read from the API's own model listing, once.
 */
public final class Claude {

    private static final Logger log = LoggerFactory.getLogger(Claude.class);

    /** The version of the API these requests are written for. It is a fixed name, not a date to keep current. */
    public static final String VERSION = "2023-06-01";

    /** One exchange with the API, so that the tests can stand in for it. A reply to a request that streams is handed over line by line, as it arrives; any other answer comes back whole. */
    public interface Transport { Answer send(String method, URI uri, Map<String, String> headers, String body, Duration idle, Consumer<String> onLine) throws Exception; }
    public record Answer(int status, String body) { }

    /** What went wrong, in words for the person: what the API said, and what usually puts it right. */
    public static final class Refused extends RuntimeException {
        public final int status;
        public Refused(int status, String message) { super(message); this.status = status; }
    }

    /** What the listing says of one model. {@code window}: the tokens it reads; {@code known}: false when the listing could not be read. */
    public record About(int window, Messages.Takes takes, boolean known) {
        static final About UNKNOWN = new About(0, Messages.Takes.UNKNOWN, false);
    }

    private static final ObjectMapper J = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private static final Transport REAL = (method, uri, headers, body, idle, onLine) -> {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(idle);
        headers.forEach(b::header);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<InputStream> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        InputStream in = r.body();
        if (r.statusCode() / 100 != 2 || onLine == null) { try (in) { return new Answer(r.statusCode(), new String(in.readAllBytes(), StandardCharsets.UTF_8)); } }
        // The request's own time limit ends when the headers arrive; after that a read waits for ever. A stream that sends
        // nothing for the whole limit is dead (the API sends a ping every few seconds while it works): it is closed, and the read fails.
        AtomicLong lastLine = new AtomicLong(System.nanoTime());
        AtomicBoolean over = new AtomicBoolean(false);
        Thread watchdog = new Thread(() -> {
            while (!over.get()) {
                try { Thread.sleep(Math.min(1000L, Math.max(50L, idle.toMillis() / 4))); } catch (InterruptedException e) { return; }
                if (!over.get() && System.nanoTime() - lastLine.get() > idle.toNanos()) { try { in.close(); } catch (IOException ignored) { } return; }
            }
        }, "claude-stream-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) { lastLine.set(System.nanoTime()); onLine.accept(line); }
        } finally { over.set(true); watchdog.interrupt(); }
        return new Answer(r.statusCode(), "");
    };

    private final String base;
    private final Transport transport;
    private final Supplier<String> key;
    private final LongConsumer pause;

    public Claude(String base, Supplier<String> key) { this(base, key, REAL, ms -> { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }); }

    Claude(String base, Supplier<String> key, Transport transport, LongConsumer pause) {
        this.base = base.strip().replaceAll("/+$", "").replaceAll("(?i)/v1/messages$", "").replaceAll("(?i)/v1$", ""); this.key = key; this.transport = transport; this.pause = pause;
    }

    /**
     * Whether the drive setting is the Claude API: its own address, or the address of a Messages endpoint somewhere else
     * ({@code https://gateway.example/v1/messages}), which is how a gateway in front of the API is named.
     */
    public static boolean is(String drive) {
        String d = drive == null ? "" : drive.strip().toLowerCase(Locale.ROOT);
        return isAnthropic(d) || d.matches("https?://[^\\s]+/v1/messages/?");
    }

    private static boolean isAnthropic(String address) { return address.strip().toLowerCase(Locale.ROOT).matches("https?://api\\.anthropic\\.com(/.*)?"); }

    /** The Claude API takes its key in a header of its own, and wants to be told which version of the API is spoken. */
    public static HttpRequest.Builder auth(HttpRequest.Builder b, String key) {
        String k = key == null ? "" : key.strip();
        if (k.regionMatches(true, 0, "Bearer ", 0, 7)) k = k.substring(7).strip();
        return b.header("x-api-key", k).header("anthropic-version", VERSION);
    }

    private Map<String, String> headers() {
        String k = key.get() == null ? "" : key.get().strip();
        // a gateway in front of the API may hold the key itself; the API does not answer without one
        if (k.isEmpty() && isAnthropic(base)) throw new Refused(401, "The Claude API needs a key. Put yours in the settings: `codezaiku config set api.key <your key>` (it is kept in ~/.codezaiku/config and sent only to the Claude API).");
        if (k.regionMatches(true, 0, "Bearer ", 0, 7)) k = k.substring(7).strip();
        Map<String, String> h = new LinkedHashMap<>();
        if (!k.isEmpty()) h.put("x-api-key", k);
        h.put("anthropic-version", VERSION); h.put("content-type", "application/json");
        return h;
    }

    /** A chat-completions request in, a chat completion out. {@code idle}: how long the API may send nothing before the call is given up. */
    public ObjectNode chat(String model, JsonNode chatCompletionsBody, Duration idle, Consumer<String> onText, Consumer<String> onThinking) {
        About about = about(model);
        ObjectNode request = Messages.request(chatCompletionsBody, about.takes());
        request.put("model", model);
        String asked = chatCompletionsBody.path("reasoning_effort").asText("");
        if (!asked.isBlank() && about.takes().efforts() != null && !about.takes().efforts().contains(asked) && SAID.add(model + " " + asked))
            log.warn("{} does not take the effort level \"{}\" ({}); it is left at the model's own", model, asked, about.takes().efforts().isEmpty() ? "it has no effort setting" : "it knows " + String.join(", ", about.takes().efforts()));
        dump(request);
        String payload = request.toString();
        Map<String, String> headers = headers();
        for (int attempt = 1; ; attempt++) {
            Messages.Reply reply = new Messages.Reply();
            Answer a;
            try { a = transport.send("POST", URI.create(base + "/v1/messages"), headers, payload, idle, line -> { if (line.startsWith("data:")) reply.take(line.substring(5).strip(), onText, onThinking); }); }
            catch (Exception e) {
                if (reply.started()) throw new Refused(0, "The Claude API stopped in the middle of a reply (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ").");
                throw new Refused(0, "The Claude API at " + base + " could not be reached, or did not answer within " + idle.toSeconds() + " seconds: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
            boolean busy = a.status() == 429 || a.status() >= 500 || "overloaded_error".equals(reply.errorType()) || "rate_limit_error".equals(reply.errorType());
            // nothing of the reply has been shown yet, so asking again repeats nothing
            if (busy && !reply.started() && attempt < 5) { log.info("the Claude API is busy ({}); waiting and asking again ({}/5)", a.status() / 100 == 2 ? reply.errorType() : "HTTP " + a.status(), attempt + 1); pause.accept(1500L * attempt * attempt); continue; }
            if (a.status() / 100 != 2) throw new Refused(a.status(), explain(a.status(), a.body(), model));
            if (reply.error() != null) throw new Refused(a.status(), "The Claude API stopped the reply. It said: " + reply.error());
            if (!reply.complete()) throw new Refused(a.status(), "The Claude API's reply ended before it was complete.");
            return Messages.response(reply.message(), model);
        }
    }

    private static final Map<String, About> ABOUT = new ConcurrentHashMap<>();
    private static final Set<String> SAID = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger DUMPED = new AtomicInteger();

    /** The API's own listing of one model, read once. A listing that cannot be read is asked for again the next time. */
    public About about(String model) {
        About known = ABOUT.get(base + " " + model);
        if (known != null) return known;
        try {
            Answer a = transport.send("GET", URI.create(base + "/v1/models/" + URLEncoder.encode(model, StandardCharsets.UTF_8)), headers(), null, Duration.ofSeconds(20), null);
            if (a.status() != 200) return About.UNKNOWN;
            About read = about(J.readTree(a.body()));
            ABOUT.put(base + " " + model, read);
            return read;
        } catch (Exception e) { return About.UNKNOWN; }
    }

    static About about(JsonNode listing) {
        JsonNode caps = listing.path("capabilities");
        if (!caps.isObject()) return new About(listing.path("max_input_tokens").asInt(0), new Messages.Takes(true, null, listing.path("max_tokens").asInt(0)), true);
        Set<String> efforts = new LinkedHashSet<>();
        if (caps.path("effort").path("supported").asBoolean(false))
            caps.path("effort").fieldNames().forEachRemaining(level -> { if (caps.path("effort").path(level).path("supported").asBoolean(false)) efforts.add(level); });
        boolean adaptive = caps.path("thinking").path("types").path("adaptive").path("supported").asBoolean(false);
        return new About(listing.path("max_input_tokens").asInt(0), new Messages.Takes(adaptive, efforts, listing.path("max_tokens").asInt(0)), true);
    }

    /** The most the harness fills of a Claude model's window unless told otherwise. */
    public static final int WINDOW = 200_000;

    /**
     * The window to work in, in tokens. A setting decides it; without one it is the model's own window and at most {@link #WINDOW}:
     * the current models read a million tokens, and a conversation allowed to grow to that size costs several dollars a turn
     * whenever the cache misses.
     */
    public int contextWindow(String model, int configured) {
        if (configured > 0) return configured;
        int listed = about(model).window();
        return listed > 0 ? Math.min(listed, WINDOW) : WINDOW;
    }

    /** Whether the API answers this key at all. */
    public boolean answers() {
        try { return transport.send("GET", URI.create(base + "/v1/models?limit=1"), headers(), null, Duration.ofSeconds(8), null).status() == 200; }
        catch (Exception e) { return false; }
    }

    /** CODEZAIKU_CLAUDE_DUMP=<folder>: every request as it goes to the API, one file each. For finding out what a model was really sent, and what changed between two turns. */
    private static void dump(ObjectNode request) {
        String dir = System.getenv("CODEZAIKU_CLAUDE_DUMP");
        if (dir == null || dir.isBlank()) return;
        try { Files.createDirectories(Path.of(dir)); Files.writeString(Path.of(dir, String.format("request-%03d.json", DUMPED.incrementAndGet())), request.toPrettyString()); }
        catch (IOException ignored) { }
    }

    private static String message(String body) {
        try { String m = J.readTree(body).path("error").path("message").asText(""); return m.isBlank() ? body : m; }
        catch (Exception e) { return body == null ? "" : body; }
    }

    private String explain(int status, String body, String model) {
        String said = message(body).strip(), apiSaid = said.isEmpty() ? "" : " The API said: " + said;
        if (status == 401) return "The Claude API did not accept the key. Check `api.key` in the settings (`codezaiku config set api.key <your key>`); keys are made at console.anthropic.com." + apiSaid;
        if (status == 403) return "This key is not allowed to do this. What a key may use is set in the Claude Console, in the workspace the key belongs to." + apiSaid;
        if (status == 404) return "The Claude API does not know the model \"" + model + "\". Check the model's name in the settings (`codezaiku config set model <name>`); it is written like claude-opus-5-5." + apiSaid;
        if (status == 413) return "The request is larger than the Claude API accepts." + apiSaid;
        if (status == 429) return "The Claude API is asking for fewer requests than this key's limit allows right now. It was tried several times. The limits are shown in the Claude Console." + apiSaid;
        if (status >= 500) return "The Claude API is having trouble (HTTP " + status + "). It was tried several times." + apiSaid;
        return "The Claude API refused the request (HTTP " + status + ")." + apiSaid;
    }
}
