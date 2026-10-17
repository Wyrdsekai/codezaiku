package org.codezaiku.drive.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAI through its own Responses API (0.3.13): the drive {@code https://api.openai.com} with the person's key. Why not the chat
 * completions it also serves: the current models carry their reasoning from one tool call to the next, which that format cannot hold,
 * so it refuses function tools from them (400 "use /v1/responses", 2026-10-15); the Responses API takes the reasoning back and caches
 * the prompt by itself (the usage lines say how much). Rate limits are built in: a 429 is waited out for as long as the headers say, a
 * 5xx is tried again with a growing wait, and when the headers say the minute's tokens are nearly spent the next call waits for the
 * reset rather than running into the limit. The context window is CODEZAIKU_CTX or the standard-price tier, since the models list
 * does not say. The effort is the request's {@code reasoning_effort} (CODEZAIKU_REASONING_EFFORT, or a saved model's own).
 */
public final class OpenAI {

    private static final Logger log = LoggerFactory.getLogger(OpenAI.class);
    private static final ObjectMapper J = new ObjectMapper();
    public static final String BASE = "https://api.openai.com";
    /** The input a call may carry at the standard price (the current models take a million, dearer above this). */
    static final int DEFAULT_CONTEXT = 272_000;
    /** What the current models can write at most in one reply, when no refusal has said otherwise. */
    static final int DEFAULT_MAX_OUTPUT = 128_000;

    public interface Transport { Answer send(String method, URI uri, Map<String, String> headers, String body, Duration timeout) throws Exception; }
    public record Answer(int status, String body, Map<String, String> headers) {
        public String header(String name) { for (var e : headers.entrySet()) if (e.getKey().equalsIgnoreCase(name)) return e.getValue(); return null; }
    }

    /** The API refused the request (a 4xx that is not a rate limit, a rate limit that cannot be waited out, or a reply that failed). */
    public static final class Refused extends RuntimeException {
        public final int status;
        public Refused(int status, String message) { super(message); this.status = status; }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(15)).build();
    private static final Transport REAL = (method, uri, headers, body, timeout) -> {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout);
        headers.forEach(b::header);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Map<String, String> h = new ConcurrentHashMap<>();
        r.headers().map().forEach((k, v) -> { if (!v.isEmpty()) h.put(k, v.get(0)); });
        return new Answer(r.statusCode(), r.body(), h);
    };

    private final String base;
    private final Supplier<String> key;
    private final Transport transport;
    private final LongConsumer sleeper;
    /** The moment the minute's tokens are back, when the last answer said they were nearly spent; 0 when they were not. */
    private volatile long waitUntilMillis;

    /** {@code key}: read per call, so a key rotated in the settings takes effect without a restart. */
    public OpenAI(String base, Supplier<String> key) { this(base, key, REAL, ms -> { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }); }

    OpenAI(String base, Supplier<String> key, Transport transport, LongConsumer sleeper) {
        this.base = (base == null || base.isBlank() ? BASE : base.strip()).replaceAll("/+$", "").replaceAll("/v1$", "");
        this.key = key == null ? () -> "" : key;
        this.transport = transport; this.sleeper = sleeper;
    }

    private String key() { String k = key.get(); if (k == null) return ""; k = k.strip(); return k.regionMatches(true, 0, "Bearer ", 0, 7) ? k.substring(7).strip() : k; }

    /** Whether a drive setting names OpenAI's API: its address, or the word. */
    public static boolean is(String drive) {
        if (drive == null) return false;
        String d = drive.strip().toLowerCase(Locale.ROOT);
        return d.equals("openai") || d.contains("://api.openai.com");
    }

    public static String base(String drive) { return is(drive) && drive.contains("://") ? drive.strip() : BASE; }

    private Map<String, String> headers(boolean json) {
        Map<String, String> h = new ConcurrentHashMap<>();
        String k = key();
        if (!k.isEmpty()) h.put("authorization", "Bearer " + k);
        if (json) h.put("content-type", "application/json");
        return h;
    }

    /** A chat-completions request in, a chat completion out. The output asked for is capped at what the model can write. */
    public ObjectNode chat(String model, JsonNode chatCompletionsBody, Duration timeout) {
        if (key().isEmpty()) throw new Refused(401, "OpenAI needs a key. Keep one for its host (`codezaiku model key openai --file <file>`) or put it in the settings (`codezaiku config set api.key <your key>`); keys are made at platform.openai.com.");
        ObjectNode request = Responses.request(chatCompletionsBody, model, chatCompletionsBody.path("reasoning_effort").asText(""));
        int cap = maxOutput(model);
        if (request.path("max_output_tokens").asInt(0) > cap) request.put("max_output_tokens", cap);
        Answer a;
        try { a = call("POST", base + "/v1/responses", request.toString(), timeout); }
        catch (Refused r) {
            // the cap the API names, when ours was wrong: learnt, and asked once more
            Matcher m = Pattern.compile("max_output_tokens.*?<= (\\d+)").matcher(r.getMessage());
            if (r.status != 400 || !m.find()) throw r;
            int learnt = Integer.parseInt(m.group(1));
            OUTPUTS.put(model, learnt);
            request.put("max_output_tokens", Math.min(request.path("max_output_tokens").asInt(), learnt));
            a = call("POST", base + "/v1/responses", request.toString(), timeout);
        }
        JsonNode reply;
        try { reply = J.readTree(a.body()); }
        catch (Exception e) { throw new Refused(a.status(), "OpenAI answered something that is not a reply: " + head(a.body())); }
        if (reply.path("status").asText("").equals("failed") || reply.path("error").isObject()) throw new Refused(a.status(), "OpenAI could not answer: " + message(a.body()));
        return Responses.response(reply, model);
    }

    /** The call, with the waiting the limits ask for: the headers' wait on a 429, a growing wait on a 5xx, and the minute's reset when the headers say it is nearly spent. */
    Answer call(String method, String url, String body, Duration timeout) {
        long wait = waitUntilMillis - System.currentTimeMillis();
        if (wait > 0) { log.info("openai: the minute's tokens are nearly spent; waiting {} s for the reset", wait / 1000); sleeper.accept(Math.min(wait, 60_000)); }
        Answer a;
        long overloadWait = 2_000;
        for (int attempt = 1; ; attempt++) {
            try { a = transport.send(method, URI.create(url), headers(body != null), body, timeout); }
            catch (Exception e) { throw new RuntimeException("OpenAI could not be reached: " + e.getMessage(), e); }
            int s = a.status();
            if (s == 200) { pace(a); return a; }
            String message = message(a.body());
            if (s == 429) {
                if (message.contains("insufficient_quota") || body(a.body()).path("error").path("code").asText("").equals("insufficient_quota"))
                    throw new Refused(429, "OpenAI: the account has no credit left for this; " + message);
                long ms = retryAfterMillis(a);
                if (attempt > 6) throw new Refused(429, "OpenAI rate limit, still after six waits: " + message);
                if (ms <= 0) ms = Math.min(5_000L * attempt, 60_000);
                log.warn("openai 429 (try {}/6), waiting {} s: {}", attempt, Math.max(1, ms / 1000), message);
                sleeper.accept(Math.min(ms, 120_000));
                continue;
            }
            if (s >= 500) {
                if (attempt > 5) throw new Refused(s, "OpenAI HTTP " + s + " after five tries: " + message);
                log.warn("openai HTTP {} (try {}/5), waiting {} s: {}", s, attempt, overloadWait / 1000, message);
                sleeper.accept(overloadWait);
                overloadWait = Math.min(overloadWait * 2, 30_000);
                continue;
            }
            throw new Refused(s, "OpenAI HTTP " + s + ": " + message);
        }
    }

    /** The next call waits for the reset when fewer than 20,000 tokens are left in the minute. */
    private void pace(Answer a) {
        String remaining = a.header("x-ratelimit-remaining-tokens"), reset = a.header("x-ratelimit-reset-tokens");
        if (remaining == null || reset == null) return;
        try {
            if (Long.parseLong(remaining.strip()) < 20_000) waitUntilMillis = System.currentTimeMillis() + durationMillis(reset);
            else waitUntilMillis = 0;
        } catch (NumberFormatException ignored) { }
    }

    /** How long a 429 asks us to wait: {@code retry-after} in seconds, else the tokens' reset the headers give. */
    static long retryAfterMillis(Answer a) {
        String v = a.header("retry-after");
        if (v != null && !v.isBlank()) { try { return Long.parseLong(v.strip()) * 1000; } catch (NumberFormatException ignored) { } }
        String reset = a.header("x-ratelimit-reset-tokens");
        return reset == null ? 0 : durationMillis(reset);
    }

    /** OpenAI writes a wait as "6ms", "1.2s", "1m30s" or "2h0m0s". */
    static long durationMillis(String s) {
        if (s == null) return 0;
        long ms = 0;
        Matcher m = Pattern.compile("([\\d.]+)(ms|h|m|s)").matcher(s.strip().toLowerCase(Locale.ROOT));
        while (m.find()) {
            double n;
            try { n = Double.parseDouble(m.group(1)); } catch (NumberFormatException e) { continue; }
            ms += switch (m.group(2)) { case "ms" -> (long) n; case "s" -> (long) (n * 1000); case "m" -> (long) (n * 60_000); default -> (long) (n * 3_600_000); };
        }
        return ms;
    }

    private static JsonNode body(String s) { try { return J.readTree(s); } catch (Exception e) { return J.createObjectNode(); } }

    static String message(String body) {
        JsonNode n = body(body);
        String m = n.path("error").path("message").asText("");
        return m.isBlank() ? head(body) : m;
    }

    private static String head(String s) { return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s; }

    // ---- models ----

    /** The model ids this key may use, as the API lists them. */
    public List<String> models() {
        Answer a = call("GET", base + "/v1/models", null, Duration.ofSeconds(20));
        List<String> out = new ArrayList<>();
        for (JsonNode m : body(a.body()).path("data")) out.add(m.path("id").asText(""));
        return out;
    }

    private static final Map<String, Integer> OUTPUTS = new ConcurrentHashMap<>();

    /** The most output tokens one reply may have: what a refusal named for the model, else 128,000. */
    public int maxOutput(String model) { return OUTPUTS.getOrDefault(model, DEFAULT_MAX_OUTPUT); }

    /** The model's context window: CODEZAIKU_CTX when it is set, else the standard-price tier (the models list does not say). */
    public int contextWindow(String model) {
        int set = Config.getInt("CODEZAIKU_CTX", 0);
        return set > 0 ? set : DEFAULT_CONTEXT;
    }

    /** Whether the key opens the API: the models list answers. */
    public boolean reachable() {
        try { call("GET", base + "/v1/models", null, Duration.ofSeconds(15)); return true; } catch (RuntimeException e) { return false; }
    }
}
