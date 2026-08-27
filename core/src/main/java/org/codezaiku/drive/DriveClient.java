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
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
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
    private Integer fromLlamaCppProps() {
        try {
            HttpRequest req = auth(HttpRequest.newBuilder(URI.create(baseUrl + "/props")))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            JsonNode n = json.readTree(resp.body()).path("default_generation_settings").path("n_ctx");
            return n.isInt() ? n.asInt() : null;
        } catch (Exception e) {
            // A connection-level failure here is worth naming, because on macOS it is usually the
            // Local Network permission rather than the endpoint — but it is not fatal on its own.
            String hint = localNetworkHint(e);
            if (!hint.isEmpty()) log.warn("reaching {}{}", baseUrl, hint);
            return null;
        }
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
        body.put("temperature", temperature); // 0.7 for the coding loop (greedy degrades it, RESET §10.4);
                                              // ops remediation passes 0.0 to follow a fix procedure exactly
        if (noThink) body.putObject("chat_template_kwargs").put("enable_thinking", false);
        body.put("stream", false);
        try {
            String payload = json.writeValueAsString(body);
            // Log the REAL outgoing request shape so we never have to infer which knob applied (L11).
            log.info("drive → {} msgs, {} req-chars, max_tokens={}, tools={}, tool_choice={}",
                    messages.size(), payload.length(), maxTokens,
                    tools == null ? 0 : tools.size(), toolChoice);
            HttpRequest req = auth(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions")))
                    .timeout(Duration.ofMinutes(5))
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
            JsonNode msg = json.readTree(resp.body()).path("choices").path(0).path("message");
            if (!msg.isObject()) {
                throw new IllegalStateException("no choices[0].message in response: " + resp.body());
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
                    .timeout(Duration.ofMinutes(5)).header("Content-Type", "application/json")
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
                    .timeout(Duration.ofMinutes(5)).header("Content-Type", "application/json")
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
}
