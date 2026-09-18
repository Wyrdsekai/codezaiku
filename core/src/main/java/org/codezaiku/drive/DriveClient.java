package org.codezaiku.drive;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import org.codezaiku.Config;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Thin client over the llama.cpp drive at :8200 (OpenAI-compatible).
 *
 * <p>When {@code tools} are present we set
 * {@code tool_choice="required"}, which (with {@code --jinja}) structurally forbids prose
 * runaways — the model can only act. The per-turn {@code max_tokens} cap is derived from the
 * live {@code /props} {@code n_ctx} so the output can never overflow the window.
 *
 * <p>The client transforms nothing on the way in or out beyond JSON framing — the loop owns
 * the raw conversation. (RESET §2/§3: do not pre-digest what the model sees.) The ONE exception is
 * malformed-tool-call RECOVERY: when {@code --jinja} 500s on the model's own bad tool-call JSON (a known
 * llama.cpp bug, ggml-org #20359), we re-request the same turn in text mode and salvage the call so a
 * sampling artifact can't spin the run to its turn cap. The recovered message has the normal shape the
 * loop already executes — no downstream change.
 */
public final class DriveClient {
    private static final Logger log = LoggerFactory.getLogger(DriveClient.class);

    private final String baseUrl;
    private final String model;
    /** One HTTP client for every DriveClient: each owns a selector thread and an executor, and a client per instance
     *  leaked threads until a small macOS runner failed with "pthread_create failed (EAGAIN)" (ResearchZosho, 2026-09-14). */
    private static final HttpClient SHARED_HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    // Tolerant reader for SALVAGED tool calls (text-mode recovery, below): the re-emitted JSON can still
    // carry single quotes / unescaped control chars / a trailing comma. Mirrors the loop's parseArgs reader.
    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .build();

    public DriveClient(String baseUrl, String model) {
        this.baseUrl = org.codezaiku.Config.driveBase(baseUrl);   // a --drive flag gets the same treatment as the setting
        this.model = model;
        this.http = SHARED_HTTP;
    }

    /**
     * Bearer credential for the endpoint, or null when none is configured.
     *
     * <p>Hosted OpenAI-compatible endpoints need one; a local llama.cpp or Ollama does not, and
     * sending an empty header to those is worse than sending nothing. Read per request rather than
     * cached so a key rotated in the environment takes effect without a restart.
     */
    private static String apiKey() {
        String k = Config.get("CODEZAIKU_API_KEY");
        return (k == null || k.isBlank()) ? null : k.trim();
    }

    /** Apply the credential, if there is one. Every request to the drive goes through here —
     *  including doctor's health probe and endpoint detection, which live outside this package. */
    public static HttpRequest.Builder auth(HttpRequest.Builder b) {
        return auth(b, apiKey());
    }

    /** The decision itself, separated so it can be exercised without the process environment. */
    static HttpRequest.Builder auth(HttpRequest.Builder b, String key) {
        if (key == null || key.isBlank()) return b;          // a local server wants no header at all
        String k = key.trim();
        // Accept a key given either bare or already prefixed, because both are in circulation and a
        // doubled "Bearer Bearer sk-..." fails in a way that looks like a bad key rather than a typo.
        return b.header("Authorization", k.regionMatches(true, 0, "Bearer ", 0, 7) ? k : "Bearer " + k);
    }

    public ObjectMapper json() {
        return json;
    }

    /**
     * On macOS, name the real cause of an unreachable LAN drive instead of leaving a dead end.
     *
     * <p>macOS 14+ gates local-network access per application. A JVM launched from a terminal — and
     * especially over ssh — cannot show the permission prompt, so the connection is refused with
     * {@code NoRouteToHostException} even though the host is plainly reachable: measured on macOS 26.5,
     * where {@code curl} returned 200 for the same URL, in the same shell, a second apart. Without this
     * note the message points at the endpoint, which is the one thing that is NOT wrong.
     */
    private static String localNetworkHint(Exception e) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) return "";
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof NoRouteToHostException || c instanceof ConnectException) {
                return " — on macOS this is usually the Local Network privacy permission rather than the "
                        + "network: grant it to your terminal app under System Settings > Privacy & "
                        + "Security > Local Network, then retry. (If `curl` reaches the same URL, that is "
                        + "the cause.) A drive on localhost or a remote host outside the LAN is unaffected.";
            }
        }
        return "";
    }

    /** Process-lifetime token counters, for /cost on metered drives. Static on purpose: a chat
     *  session swaps DriveClient instances on /model, and the person's question is "what has this
     *  SESSION spent", not "this client object". */
    public static final java.util.concurrent.atomic.AtomicLong SESSION_PROMPT_TOKENS =
            new java.util.concurrent.atomic.AtomicLong();
    /**
     * Who wants to know how full the context is after each model call: prompt plus completion tokens of the call
     * just made. Per thread, because an ACP server runs several sessions at once and each one's loop makes its
     * calls on its own thread; a static sink would report one session's usage to another.
     */
    public static final ThreadLocal<java.util.function.IntConsumer> USAGE_SINK = new ThreadLocal<>();

    /** The server's own count of the last request's prompt tokens, 0 until a reply carried one. The loop calibrates its estimate on it. */
    private volatile int lastPromptTokens = 0;
    public int lastPromptTokens() { return lastPromptTokens; }
    /** The last reply's completion tokens and finish reason ("stop", "tool_calls", "length"…): "length" means the reply was cut off at max_tokens. */
    private volatile int lastCompletionTokens = 0;
    private volatile String lastFinishReason = "";
    public int lastCompletionTokens() { return lastCompletionTokens; }
    public String lastFinishReason() { return lastFinishReason; }

    public static final java.util.concurrent.atomic.AtomicLong SESSION_COMPLETION_TOKENS =
            new java.util.concurrent.atomic.AtomicLong();

    /** Per-request HTTP timeout. Five minutes suits every drive we had — until a 744B with
     *  CPU-resident experts needed 10-20 min per long generation and every call "failed" at
     *  exactly 300s (measured 2026-08-31: 8 identical 5-minute-spaced failures ended the run).
     *  CODEZAIKU_DRIVE_TIMEOUT (seconds) raises it for slow drives. */
    private static Duration driveTimeout() {
        int s = org.codezaiku.Config.getInt("CODEZAIKU_DRIVE_TIMEOUT", 300);
        return Duration.ofSeconds(Math.max(30, s));
    }

    /** Context window from the live server: /props → default_generation_settings.n_ctx. */
    /** Used when no server will tell us. Deliberately small: guessing LOW costs an early compaction,
     *  guessing HIGH overflows the window mid-run, and only one of those is recoverable. */
    private static final int FALLBACK_CTX = 8192;

    public int contextWindow() {
        // An explicit setting wins: the operator knows what they launched the server with, and a
        // server's self-report can be the model's maximum rather than the context it was started with.
        int forced = org.codezaiku.Config.getInt("CODEZAIKU_CTX", 0);
        if (forced > 0) return forced;

        Integer n = fromLlamaCppProps();
        if (n != null) return n;

        n = fromOllamaShow();
        if (n != null) {
            log.info("context window {} (from ollama /api/show; llama.cpp /props not served here)", n);
            return n;
        }

        n = fromModelsContextLength();
        if (n != null) {
            log.info("context window {} (from /v1/models context_length — FreeToken/vLLM style)", n);
            return n;
        }

        // Only degrade when a server ANSWERED but does not serve these endpoints. An unreachable
        // drive must still fail here, immediately and by name.
        //
        // My first version of this fallback did not make that distinction, and it turned a clear
        // instant failure into a slow confusing one: with no drive at all the run proceeded on the
        // assumed window and burned its whole turn budget on chat calls that could never succeed,
        // ending in "max turns reached without task_done" with the real cause nowhere in sight.
        // Measured while verifying WSL2. Degrading is right for a DIFFERENT api; it is wrong for a
        // dead endpoint.
        if (!reachable()) {
            throw new RuntimeException("no model server is answering at " + baseUrl
                    + " — check CODEZAIKU_DRIVE, or start one (`codezaiku doctor` names the fix)");
        }
        log.warn("no server reported a context window at {} — assuming {}. Set CODEZAIKU_CTX if your "
                + "server's window differs; too small only costs an early compaction.", baseUrl, FALLBACK_CTX);
        return FALLBACK_CTX;
    }

    /**
     * FreeToken/vLLM-family servers put the window in {@code /v1/models} as {@code context_length}
     * (or {@code max_model_len}). NOTE the caveat from {@link #contextWindow}: this is the MODEL's
     * maximum, which for a long-context model can be far beyond what the KV cache actually holds —
     * so it is capped at 131072 here, and CODEZAIKU_CTX remains the operator override. Added
     * 2026-08-29 after FreeToken fell through to the 8192 fallback and strangled a whole battery
     * run (out_budget 553, compaction every turn) while serving a 262k model.
     */
    private Integer fromModelsContextLength() {
        try {
            HttpRequest req = auth(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/models")))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            JsonNode m = json.readTree(resp.body()).path("data").path(0);
            int n = m.path("context_length").asInt(m.path("max_model_len").asInt(0));
            return n > 0 ? Math.min(n, 131072) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Did anything answer at all? Any HTTP status counts — a 404 is a live server with another API. */
    private boolean reachable() {
        for (String path : new String[]{"/v1/models", "/"}) {
            try {
                HttpRequest req = auth(HttpRequest.newBuilder(URI.create(baseUrl + path)))
                        .timeout(Duration.ofSeconds(8)).GET().build();
                http.send(req, HttpResponse.BodyHandlers.ofString());
                return true;
            } catch (Exception ignored) {
                // try the next probe
            }
        }
        return false;
    }

    /** llama.cpp: {@code /props → default_generation_settings.n_ctx}. The most accurate source. */
    Integer fromLlamaCppProps() {
        try {
            Integer n = propsAt(baseUrl + "/props");
            if (n != null) return n;
            // llama-swap routes by model name and answers /props with "no model id could be identified"; the upstream
            // server's own properties are at /upstream/<model>/props (every `model serve install` sits behind llama-swap,
            // and the fallback of 8192 had the loop compacting at a quarter of the real window, 2026-09-14)
            if (model != null && !model.isBlank()) {
                n = propsAt(baseUrl + "/upstream/" + java.net.URLEncoder.encode(model, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20") + "/props");
                if (n != null) { log.info("context window {} (from llama-swap's upstream /props for {})", n, model); return n; }
            }
            return null;
        } catch (Exception e) {
            // A connection-level failure here is worth naming, because on macOS it is usually the
            // Local Network permission rather than the endpoint — but it is not fatal on its own.
            String hint = localNetworkHint(e);
            if (!hint.isEmpty()) log.warn("reaching {}{}", baseUrl, hint);
            return null;
        }
    }

    /** {@code default_generation_settings.n_ctx} from a llama.cpp /props page at {@code url}, or null. */
    private Integer propsAt(String url) throws Exception {
        HttpRequest req = auth(HttpRequest.newBuilder(URI.create(url))).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;
        JsonNode n = json.readTree(resp.body()).path("default_generation_settings").path("n_ctx");
        return n.isInt() ? n.asInt() : null;
    }

    /** Ollama: {@code /api/show → model_info["<family>.context_length"]}, keyed by model family. */
    private Integer fromOllamaShow() {
        try {
            String body = "{\"model\":\"" + model + "\"}";
            HttpRequest req = auth(HttpRequest.newBuilder(URI.create(baseUrl + "/api/show")))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            JsonNode info = json.readTree(resp.body()).path("model_info");
            var fields = info.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                if (e.getKey().endsWith(".context_length") && e.getValue().isInt()) {
                    return e.getValue().asInt();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * One chat turn. Returns {@code choices[0].message} (the assistant message, which carries
     * {@code tool_calls} when tools were offered). The caller appends it to the conversation
     * verbatim and executes the tool calls.
     */
    /**
     * Where streamed prose goes, when streaming is on. Null (the default) means nobody is watching
     * and requests stay non-streaming.
     *
     * <p>Streaming is opt-in twice: {@code CODEZAIKU_STREAM} must be truthy AND a sink must be set.
     * Config alone is not enough because most callers — `run`, MCP, the batteries — have no screen,
     * and parsing SSE for nobody is pure risk. A sink alone is not enough because the phone and the
     * chat want the same binary to behave identically until the config says otherwise. The loop and
     * every existing call site are untouched: the streamed request is reassembled into exactly the
     * message shape the non-streaming path returns, tool-call deltas included, so downstream code
     * cannot tell which path ran.
     */
    private static volatile java.util.function.Consumer<String> onDelta;
    private static volatile java.util.function.Consumer<String> onThink;

    public static void streamTo(java.util.function.Consumer<String> sink) { streamTo(sink, sink); }

    /**
     * Separate channels for the reply and the thinking. They are different voices: the reply is the
     * model talking TO the person, the reasoning is it talking to itself — and the operator, watching
     * both arrive in identical text, read the second as the injected-prompt bug all over again. The
     * renderer decides how the inner voice looks (dimmed, in a terminal); this layer only keeps the
     * two from arriving indistinguishable.
     */
    public static void streamTo(java.util.function.Consumer<String> sink,
                                java.util.function.Consumer<String> thinking) {
        onDelta = sink;
        onThink = sink == null ? null : thinking;
    }

    private static boolean streamingOn() {
        // Off unless asked for, as in ResearchZosho (decided 2026-09-18). The status line says what is happening
        // while the person waits; the answer arrives whole.
        String v = org.codezaiku.Config.get("CODEZAIKU_STREAM", "");
        return onDelta != null && !v.isBlank() && !v.equalsIgnoreCase("off")
                && !v.equalsIgnoreCase("false") && !v.equals("0");
    }

    // Whether thinking is SHOWN is the renderer's decision, not this layer's: it owns the screen
    // and can change its mind mid-session (/thinking). This layer only keeps the channels apart.

    public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens) {
        return chat(messages, tools, maxTokens, "required");
    }

    /**
     * One chat turn with an explicit {@code wantToolChoice} so the LOOP can control prose-vs-act per turn:
     * "none" forces prose-only (used for the smallcode planning turn — the model emits the numbered PLAN as
     * text, which `required` would forbid), "required" forces a tool call (the work-turn default — no prose
     * runaways, the original reason for `required`). This is the hybrid: force prose only when we want it
     * (planning), require tools the rest of the time.
     */
    public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String wantToolChoice) {
        return chat(messages, tools, maxTokens, wantToolChoice, 0.7, false);
    }

    /**
     * DETERMINISTIC tool-calling turn for OPS remediation — temperature 0 + {@code enable_thinking:false}.
     * The coding loop wants temp 0.7 (greedy degrades creative code gen, RESET §10.4), but ops remediation
     * is the opposite task: FOLLOW a matched fix procedure exactly. At 0.7 the 9B improvised its own SQL and
     * ignored the card (postgres read-only fixed 0/3); the measured refstack driver ran remediation at temp 0
     * and followed the card (5/5). Same shape as {@link #chat}, only the sampling differs.
     */
    public ObjectNode chatOps(ArrayNode messages, ArrayNode tools, int maxTokens, String wantToolChoice) {
        return chat(messages, tools, maxTokens, wantToolChoice, 0.0, true);
    }

    private ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String wantToolChoice,
                            double temperature, boolean noThink) {
        // SANITIZE history before every send: llama.cpp --jinja re-parses EVERY historical tool_call's
        // `arguments` while rendering the chat template, so a single malformed one (the model emitted bad
        // JSON that the output parser let through, or compaction rewrote args into invalid JSON) makes EVERY
        // subsequent request 500 with "Failed to parse tool call arguments" — poisoning the whole run into a
        // spin to the turn cap (battery56 py-n1-pass2: 792 such 500s). Repairing the args to valid JSON
        // prevents the 500 at the source (proven against the live drive: malformed history → 500, repaired
        // history → 200). Mutates in place, which also heals the loop's own history for later turns.
        sanitizeToolCallArgs(messages);
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.set("messages", messages);
        String toolChoice = "none";
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", tools);
            toolChoice = wantToolChoice;
            body.put("tool_choice", toolChoice);
        }
        body.put("max_tokens", maxTokens);
        // CODEZAIKU_TEMP=none omits the field entirely — claude-fable-5 400s on ANY temperature
        // ("deprecated for this model", measured 2026-08-29); a number overrides the caller's
        // choice for engines whose default sampling is better left alone.
        String tempCfg = org.codezaiku.Config.get("CODEZAIKU_TEMP");
        if ("none".equalsIgnoreCase(tempCfg)) {
            // omitted
        } else if (tempCfg != null && !tempCfg.isBlank()) {
            body.put("temperature", Double.parseDouble(tempCfg));
        } else {
            body.put("temperature", temperature); // 0.7 for the coding loop (greedy degrades it, RESET §10.4);
                                                  // ops remediation passes 0.0 to follow a fix procedure exactly
        }
        // Operator-set template kwargs, merged into every request. This is the per-drive twin of
        // llama.cpp's --chat-template-kwargs: cz-chat runs the 27B with reasoning_effort=low at the
        // CONTAINER, but an engine with no such flag (FreeToken 0.1.2) leaves a reasoning model
        // thinking at full length through every loop turn — measured 2026-08-29: the same weights
        // spent a 25-turn budget mid-write. An explicit noThink turn still wins the merge.
        String tk = org.codezaiku.Config.get("CODEZAIKU_DRIVE_TEMPLATE_KWARGS");
        if (tk != null && !tk.isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode kw = json.readTree(tk);
                if (kw.isObject()) body.set("chat_template_kwargs", kw.deepCopy());
            } catch (Exception e) {
                throw new IllegalStateException(
                        "CODEZAIKU_DRIVE_TEMPLATE_KWARGS is not a JSON object: " + tk);
            }
        }
        if (noThink) {
            ObjectNode kw = body.has("chat_template_kwargs")
                    ? (ObjectNode) body.get("chat_template_kwargs")
                    : body.putObject("chat_template_kwargs");
            kw.put("enable_thinking", false);
        }
        body.put("stream", streamingOn());
        try {
            String payload = json.writeValueAsString(body);
            if (streamingOn()) {
                ObjectNode streamed = chatStreaming(payload);
                if (streamed != null) return streamed;
                // A failed stream falls through to the plain request rather than failing the turn:
                // streaming is presentation, and presentation must never cost an answer.
                body.put("stream", false);
                payload = json.writeValueAsString(body);
            }
            // Log the REAL outgoing request shape so we never have to infer which knob applied (L11).
            log.info("drive → {} msgs, {} req-chars, max_tokens={}, tools={}, tool_choice={}",
                    messages.size(), payload.length(), maxTokens,
                    tools == null ? 0 : tools.size(), toolChoice);
            HttpRequest req = auth(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions")))
                    .timeout(driveTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            // Retry a 5xx: llama.cpp (--jinja) returns HTTP 500 when the MODEL emits a malformed tool call
            // (bad JSON args — an unescaped quote/newline), and that's the model's sampling, not a real
            // server fault. At temp 0.7 a re-send re-samples and usually produces valid JSON. (A single bad
            // tool call must NOT abort the run — the loop also catches, as a backstop.)
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            for (int attempt = 2; attempt <= 4 && resp.statusCode() >= 500; attempt++) {
                String b = resp.body();
                log.warn("drive HTTP {} (retry {}/4): {}", resp.statusCode(), attempt,
                        b.length() > 200 ? b.substring(0, 200) : b);
                try { Thread.sleep(300L); } catch (InterruptedException ignored) { }
                resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            }
            if (resp.statusCode() != 200) {
                // RECOVERY (A): a 5xx whose body says the MODEL's tool call wouldn't parse is a known
                // llama.cpp --jinja bug (ggml-org #20359/#22948): the grammar tool-call parser emits/rejects
                // invalid JSON args for code-ish payloads (mixed quote escaping, unterminated strings),
                // amplified at sub-Q5 quants. Re-sampling deterministically re-fails (it re-emits the same
                // payload), so the run spins to its turn cap. The fix is to re-request the SAME turn in
                // TEXT mode (tool_choice="none" → no grammar parse → no 500) and SALVAGE the intended call
                // from the prose. parseArgs downstream is already lenient; we only need name + args here.
                if (looksLikeToolCallParseError(resp.body())) {
                    ObjectNode recovered = recoverMalformedToolCall(messages, tools, maxTokens);
                    if (recovered != null) return recovered;
                }
                throw new IllegalStateException("drive HTTP " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode parsed = json.readTree(resp.body());
            JsonNode msg = parsed.path("choices").path(0).path("message");
            lastFinishReason = parsed.path("choices").path(0).path("finish_reason").asText("");
            if (!msg.isObject()) {
                throw new IllegalStateException("no choices[0].message in response: " + resp.body());
            }
            // Usage, when the server reports it. On a metered drive this line IS the meter:
            // grep 'usage ←' over a run log and sum. Local llama.cpp reports it too — harmless.
            JsonNode u = parsed.path("usage");
            if (u.isObject()) {
                log.info("usage ← prompt {} completion {} total {}",
                        u.path("prompt_tokens").asInt(), u.path("completion_tokens").asInt(),
                        u.path("total_tokens").asInt());
                SESSION_PROMPT_TOKENS.addAndGet(u.path("prompt_tokens").asLong(0));
                lastPromptTokens = u.path("prompt_tokens").asInt(0);
                lastCompletionTokens = u.path("completion_tokens").asInt(0);
                SESSION_COMPLETION_TOKENS.addAndGet(u.path("completion_tokens").asLong(0));
                var usageSink = USAGE_SINK.get();
                if (usageSink != null) {
                    int total = u.path("total_tokens").asInt(u.path("prompt_tokens").asInt(0) + u.path("completion_tokens").asInt(0));
                    try { usageSink.accept(total); } catch (RuntimeException e) { log.debug("usage sink failed: {}", e.toString()); }
                }
            }
            return (ObjectNode) msg;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("chat() failed against " + baseUrl, e);
        }
    }

    /**
     * A DETERMINISTIC, no-thinking prose completion for classification/localization steps — temperature 0
     * and {@code enable_thinking:false}. This 9B is a reasoning model: left thinking-on it fills
     * {@code reasoning_content} and varies its surface answer run-to-run, so a "name the single root-cause
     * service" step misses intermittently (measured ~1 in 3 on the refstack localizer). A classifier wants
     * the SAME answer for the same evidence — unlike the creative loop path, which stays temp 0.7. Returns
     * the message content (falling back to reasoning_content), or "" on any failure.
     */
    public String classify(ArrayNode messages, int maxTokens) {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.set("messages", messages);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.0);
        body.putObject("chat_template_kwargs").put("enable_thinking", false);
        body.put("stream", false);
        try {
            HttpRequest req = auth(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions")))
                    .timeout(driveTimeout()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("classify HTTP {}: {}", resp.statusCode(),
                        resp.body().length() > 200 ? resp.body().substring(0, 200) : resp.body());
                return "";
            }
            JsonNode msg = json.readTree(resp.body()).path("choices").path(0).path("message");
            String content = msg.path("content").asText("");
            if (content.isBlank()) content = msg.path("reasoning_content").asText("");
            return content;
        } catch (Exception e) {
            log.warn("classify() failed against {}: {}", baseUrl, e.toString());
            return "";
        }
    }

    /**
     * Ensure every tool_call's {@code function.arguments} in the history is VALID JSON, so the --jinja
     * template can re-render it without 500ing. Empty → "{}"; malformed → lenient-parse-and-reserialize,
     * else a valid placeholder that preserves a slice of the raw for debuggability. Mutates in place.
     */
    private void sanitizeToolCallArgs(ArrayNode messages) {
        if (messages == null) return;
        for (JsonNode m : messages) {
            JsonNode tcs = m.path("tool_calls");
            if (!tcs.isArray()) continue;
            for (JsonNode tc : tcs) {
                JsonNode fn = tc.path("function");
                if (!fn.isObject() || !fn.path("arguments").isTextual()) continue;
                String args = fn.path("arguments").asText();
                if (args.isBlank()) { ((ObjectNode) fn).put("arguments", "{}"); continue; }
                try { json.readTree(args); continue; } catch (Exception malformed) { /* repair below */ }
                ((ObjectNode) fn).put("arguments", repairJson(args));
                log.warn("sanitized malformed tool_call args in history ({} chars) — prevented --jinja 500",
                        args.length());
            }
        }
    }

    /** Best-effort: lenient-parse then re-serialize to strict JSON; else a valid placeholder. Never throws. */
    private String repairJson(String raw) {
        try {
            JsonNode n = LENIENT.readTree(raw);
            if (n.isObject() || n.isArray()) return json.writeValueAsString(n);
        } catch (Exception ignored) { }
        try {
            return json.writeValueAsString(json.createObjectNode()
                    .put("_unparsed", raw.length() > 200 ? raw.substring(0, 200) : raw));
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Does this 5xx body signal that the MODEL's emitted tool call failed to parse (vs a real fault)? */
    private static boolean looksLikeToolCallParseError(String body) {
        if (body == null) return false;
        String b = body.toLowerCase();
        return b.contains("tool call") || b.contains("tool_call")
                || (b.contains("parse") && b.contains("arguments"));
    }

    /**
     * RECOVERY for the --jinja malformed-tool-call 500: re-request the same turn in TEXT mode
     * (tool_choice="none", so the server does NO grammar tool parse and cannot 500) with a nudge to
     * re-emit the intended action as one fenced json block, then salvage it into a normal tool_calls
     * message the loop executes unchanged. Returns null on any failure (caller then throws as before).
     */
    private ObjectNode recoverMalformedToolCall(ArrayNode messages, ArrayNode tools, int maxTokens) {
        try {
            log.info("drive 500 on tool-call parse — attempting text-mode recovery (tools omitted)");
            ArrayNode recov = messages.deepCopy();
            recov.addObject().put("role", "user").put("content",
                    "Your previous tool call could not be parsed (malformed JSON arguments). Re-emit your "
                    + "intended action as ONE fenced json block and nothing else:\n```json\n"
                    + "{\"name\": \"<tool_name>\", \"arguments\": { ... }}\n```\n"
                    + "Every string value MUST be valid JSON: escape each embedded double-quote as \\\" and "
                    + "each newline as \\n. If the action writes a large file, write a SMALLER piece now "
                    + "(you can append the rest with follow-up edits).");
            ObjectNode body = json.createObjectNode();
            body.put("model", model);
            body.set("messages", recov);
            // Deliberately send NO `tools` and NO `tool_choice`: with `tools` present, llama.cpp --jinja keeps
            // the tool grammar active and 500s AGAIN on the model's output (even at tool_choice="none") — which
            // silently re-failed the whole recovery (battery55 py-n2: 20 parse-500s, 0 recoveries). Without
            // tools there is no grammar to trip, so the server returns plain prose we salvage the call from.
            body.put("max_tokens", maxTokens);
            body.put("temperature", 0.7);
            body.put("stream", false);
            HttpRequest req = auth(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions")))
                    .timeout(driveTimeout()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("recovery re-request returned HTTP {} — cannot salvage", resp.statusCode());
                return null;
            }
            JsonNode msg = json.readTree(resp.body()).path("choices").path(0).path("message");
            String content = msg.path("content").asText("");
            if (content.isBlank()) content = msg.path("reasoning_content").asText("");
            ObjectNode synth = salvageToolCall(content);
            if (synth != null) log.info("recovered malformed tool call via text-mode re-request");
            else log.warn("recovery re-request succeeded (HTTP 200) but no tool call salvaged from prose");
            return synth;
        } catch (Exception e) {
            log.warn("tool-call recovery attempt failed: {}", e.toString());
            return null;
        }
    }

    /**
     * Turn re-emitted prose ({@code {"name":..,"arguments":{..}}}, possibly fenced) into an assistant
     * message carrying a single {@code tool_calls} entry — the exact shape the loop consumes. Args are
     * stringified (the loop's parseArgs re-parses them, leniently). Null if no usable call is present.
     */
    private ObjectNode salvageToolCall(String content) {
        if (content == null || content.isBlank()) return null;
        String s = content.strip();
        int fence = s.indexOf("```");
        if (fence >= 0) {                              // strip a leading ```json fence + its closer
            int nl = s.indexOf('\n', fence);
            if (nl > 0) s = s.substring(nl + 1);
            int close = s.lastIndexOf("```");
            if (close >= 0) s = s.substring(0, close);
        }
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        s = s.substring(a, b + 1);
        JsonNode node;
        try { node = json.readTree(s); }
        catch (Exception e) { try { node = LENIENT.readTree(s); } catch (Exception e2) { return null; } }
        String name = node.path("name").asText("");
        if (name.isBlank()) return null;
        JsonNode argsNode = node.path("arguments");
        String argsStr = (argsNode.isObject() || argsNode.isArray()) ? argsNode.toString()
                : argsNode.isTextual() ? argsNode.asText() : "{}";
        ObjectNode out = json.createObjectNode();
        out.put("role", "assistant");
        out.putNull("content");
        ObjectNode call = out.putArray("tool_calls").addObject();
        call.put("id", "recovered_1");
        call.put("type", "function");
        ObjectNode fn = call.putObject("function");
        fn.put("name", name);
        fn.put("arguments", argsStr);
        return out;
    }

    /**
     * One streamed chat request, reassembled into the same {@code choices[0].message} object the
     * non-streaming path returns. Null on any failure — the caller falls back to a plain request.
     *
     * <p>The fiddly part is tool calls: the OpenAI stream format delivers them as DELTAS — a first
     * chunk carrying {@code index}, {@code id} and the function {@code name}, then chunks each
     * carrying a fragment of {@code arguments} — and the fragments are raw string pieces of a JSON
     * document, split anywhere. They are concatenated per index and only parsed by whoever consumes
     * the final message, exactly as with the non-streamed shape. Prose deltas go to the sink as they
     * arrive; tool-call deltas do not, because half a JSON argument is noise on a screen.
     */
    private ObjectNode chatStreaming(String payload) { return chatStreaming(payload, driveTimeout()); }

    /** {@code idle}: how long the stream may send nothing before it is given up. */
    ObjectNode chatStreaming(String payload, Duration idle) {
        var sink = onDelta;
        if (sink == null) return null;
        try {
            HttpRequest req = auth(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions")))
                    .timeout(driveTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            HttpResponse<java.io.InputStream> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) return null;
            // The request timeout ends when the headers arrive; after that a body read waits forever. A stream that
            // sends nothing for a whole drive timeout is dead: the watchdog closes it, the read throws, and the turn
            // falls back to the plain request.
            final java.io.InputStream body = resp.body();
            final long idleNanos = idle.toNanos();
            final java.util.concurrent.atomic.AtomicLong lastEvent = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
            final java.util.concurrent.atomic.AtomicBoolean over = new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread watchdog = new Thread(() -> {
                while (!over.get()) {
                    try { Thread.sleep(Math.min(1000L, Math.max(50L, idleNanos / 4_000_000L))); } catch (InterruptedException e) { return; }
                    if (!over.get() && System.nanoTime() - lastEvent.get() > idleNanos) {
                        log.warn("stream idle for {} ms, closing it", idle.toMillis());
                        try { body.close(); } catch (java.io.IOException ignored) { }
                        return;
                    }
                }
            }, "drive-stream-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            boolean finished = false;
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(body, java.nio.charset.StandardCharsets.UTF_8))) {
                var content = new StringBuilder();
                var toolNames = new java.util.TreeMap<Integer, String>();
                var toolIds = new java.util.TreeMap<Integer, String>();
                var toolArgs = new java.util.TreeMap<Integer, StringBuilder>();
                var summaries = new java.util.TreeMap<Integer, SummaryStream>();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).strip();
                    lastEvent.set(System.nanoTime());
                    if (data.equals("[DONE]")) { finished = true; break; }
                    JsonNode choice = json.readTree(data).path("choices").path(0);
                    if (choice.hasNonNull("finish_reason")) finished = true;
                    JsonNode delta = choice.path("delta");
                    String piece = delta.path("content").asText(null);
                    if (piece != null && !piece.isEmpty()) {
                        content.append(piece);
                        sink.accept(piece);
                    }
                    // Reasoning models (gemma under --jinja, the 27B) think out loud in
                    // reasoning_content before the tool call — verified against the live server,
                    // where an entire turn's visible streaming WAS the reasoning. It goes to the
                    // sink so the person watches the model think, but never into the message:
                    // downstream must see exactly the non-streamed shape, which drops reasoning.
                    String think = delta.path("reasoning_content").asText(null);
                    if (think != null && !think.isEmpty()) {
                        var t = onThink;
                        (t != null ? t : sink).accept(think);
                    }
                    for (JsonNode tc : delta.path("tool_calls")) {
                        int idx = tc.path("index").asInt(0);
                        if (tc.hasNonNull("id")) toolIds.put(idx, tc.get("id").asText());
                        JsonNode fn = tc.path("function");
                        if (fn.hasNonNull("name")) toolNames.put(idx, fn.get("name").asText());
                        if (fn.hasNonNull("arguments")) {
                            String frag = fn.get("arguments").asText();
                            toolArgs.computeIfAbsent(idx, k -> new StringBuilder()).append(frag);
                            // The loop runs with tool_choice=required, so the model NEVER produces
                            // plain content — measured on the first live streaming run, where a
                            // whole turn streamed and the screen showed nothing. The prose a person
                            // actually reads is the summary argument of the finishing tool, so THAT
                            // is what streams: decoded incrementally out of the JSON fragments,
                            // which split anywhere, including mid-escape.
                            String name = toolNames.get(idx);
                            if ("task_done".equals(name) || "task_blocked".equals(name)) {
                                summaries.computeIfAbsent(idx,
                                                k -> new SummaryStream("task_done".equals(name)
                                                        ? "summary" : "reason", sink))
                                        .feed(frag);
                            }
                        }
                    }
                }
                // A connection that drops mid-answer ends the read the same way a finished stream does. Without this
                // check half a tool call came back as a whole message, and its cut-off arguments went to the tool.
                if (!finished) throw new java.io.IOException("the stream ended with no finish_reason and no [DONE] after " + content.length() + " chars");
                ObjectNode msg = json.createObjectNode();
                msg.put("role", "assistant");
                msg.put("content", content.toString());
                if (!toolNames.isEmpty()) {
                    var arr = msg.putArray("tool_calls");
                    toolNames.forEach((idx, name) -> {
                        var one = arr.addObject();
                        one.put("id", toolIds.getOrDefault(idx, "call_" + idx));
                        one.put("type", "function");
                        var fn = one.putObject("function");
                        fn.put("name", name);
                        fn.put("arguments", toolArgs.getOrDefault(idx, new StringBuilder()).toString());
                    });
                }
                return msg;
            } finally {
                over.set(true);
                watchdog.interrupt();
            }
        } catch (Exception e) {
            log.warn("streaming failed ({}), falling back to a plain request", e.toString());
            return null;
        }
    }

    /**
     * Streams ONE string field's value out of a JSON object that arrives in arbitrary fragments.
     *
     * <p>Looks for {@code "<field>"}, then the colon, then the opening quote, then emits decoded
     * characters until the unescaped closing quote. Fragments split anywhere — inside the key,
     * inside an escape, inside a {@code \\u} sequence — because that is how tool-call deltas
     * actually arrive. Unknown escapes pass through as their literal character; a malformed tail is
     * simply not emitted, since the fully reassembled arguments are parsed downstream anyway and
     * this exists only so a person watches the answer appear instead of a silent turn.
     */
    static final class SummaryStream {
        private final String needle;
        private final java.util.function.Consumer<String> sink;
        private final StringBuilder window = new StringBuilder();
        private int state = 0;         // 0=looking for key, 1=looking for opening quote, 2=in value, 3=done
        private boolean escaping = false;
        private int uLeft = 0;
        private final StringBuilder uHex = new StringBuilder();

        SummaryStream(String field, java.util.function.Consumer<String> sink) {
            this.needle = "\"" + field + "\"";
            this.sink = sink;
        }

        void feed(String fragment) {
            for (int i = 0; i < fragment.length() && state != 3; i++) {
                char c = fragment.charAt(i);
                switch (state) {
                    case 0 -> {
                        window.append(c);
                        if (window.length() > needle.length()) window.deleteCharAt(0);
                        if (window.toString().equals(needle)) state = 1;
                    }
                    case 1 -> { if (c == '"') state = 2; }
                    case 2 -> {
                        if (uLeft > 0) {
                            uHex.append(c);
                            if (--uLeft == 0) {
                                try { sink.accept(String.valueOf((char) Integer.parseInt(uHex.toString(), 16))); }
                                catch (NumberFormatException ignored) { }
                                uHex.setLength(0);
                            }
                        } else if (escaping) {
                            escaping = false;
                            switch (c) {
                                case 'n' -> sink.accept("\n");
                                case 't' -> sink.accept("\t");
                                case 'r' -> { }
                                case 'u' -> uLeft = 4;
                                default -> sink.accept(String.valueOf(c));
                            }
                        } else if (c == '\\') {
                            escaping = true;
                        } else if (c == '"') {
                            state = 3;
                        } else {
                            sink.accept(String.valueOf(c));
                        }
                    }
                    default -> { }
                }
            }
        }
    }
}