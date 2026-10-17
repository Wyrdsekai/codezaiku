package org.codezaiku.drive.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.Config;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The client's own behaviour: the headers and the address, the waits the limits ask for, a spent account that is not waited for, a failed reply, the output cap learnt. */
class OpenAITest {

    static final ObjectMapper M = new ObjectMapper();
    record Sent(String method, URI uri, Map<String, String> headers, String body) { }

    /** A transport that answers from a script and keeps what was sent. */
    static final class Script implements OpenAI.Transport {
        final List<OpenAI.Answer> answers = new ArrayList<>(); final List<Sent> sent = new ArrayList<>();
        Script then(int status, String body, String... headers) { Map<String, String> h = new java.util.HashMap<>(); for (int i = 0; i + 1 < headers.length; i += 2) h.put(headers[i], headers[i + 1]); answers.add(new OpenAI.Answer(status, body, h)); return this; }
        @Override public OpenAI.Answer send(String method, URI uri, Map<String, String> headers, String body, Duration timeout) {
            sent.add(new Sent(method, uri, headers, body));
            if (answers.isEmpty()) throw new IllegalStateException("no answer scripted for " + uri);
            return answers.remove(0);
        }
    }

    static ObjectNode ask() { ObjectNode b = M.createObjectNode(); b.put("max_tokens", 50); b.putArray("messages").addObject().put("role", "user").put("content", "hi"); return b; }
    static final String OK = "{\"id\":\"resp_1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}],\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}";

    @Test
    void theHeadersTheAddressAndTheReply() throws Exception {
        Script t = new Script().then(200, OK);
        List<Long> slept = new ArrayList<>();
        OpenAI o = new OpenAI("https://api.openai.com/v1/", () -> "sk-test", t, slept::add);
        ObjectNode r = o.chat("gpt-6.1-sol", ask(), Duration.ofSeconds(5));
        assertEquals("hello", r.path("choices").get(0).path("message").path("content").asText());
        Sent s = t.sent.get(0);
        assertEquals("https://api.openai.com/v1/responses", s.uri().toString(), "the /v1 typed on the end is not doubled");
        assertEquals("Bearer sk-test", s.headers().get("authorization")); assertEquals("application/json", s.headers().get("content-type"));
        assertEquals("gpt-6.1-sol", M.readTree(s.body()).path("model").asText()); assertEquals(50, M.readTree(s.body()).path("max_output_tokens").asInt());
        assertTrue(slept.isEmpty());
        assertTrue(OpenAI.is("https://api.openai.com") && OpenAI.is("openai") && OpenAI.is("https://api.openai.com/v1/") && !OpenAI.is("https://openrouter.ai/api/v1") && !OpenAI.is("http://localhost:8080"));
        assertEquals("https://api.openai.com", OpenAI.base("openai"));
        OpenAI noKey = new OpenAI("", () -> "", t, slept::add);
        assertTrue(assertThrows(OpenAI.Refused.class, () -> noKey.chat("m", ask(), Duration.ofSeconds(5))).getMessage().contains("needs a key"));
    }

    @Test
    void aRateLimitIsWaitedOutAsTheHeadersSayAndASpentAccountIsNot() {
        String limited = "{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"too many tokens\",\"code\":\"rate_limit_exceeded\"}}";
        Script t = new Script().then(429, limited, "retry-after", "7").then(429, limited, "x-ratelimit-reset-tokens", "1m2.5s").then(429, limited).then(200, OK);
        List<Long> slept = new ArrayList<>();
        OpenAI o = new OpenAI(null, () -> "k", t, slept::add);
        assertEquals("hello", o.chat("m", ask(), Duration.ofSeconds(5)).path("choices").get(0).path("message").path("content").asText());
        assertEquals(List.of(7_000L, 62_500L, 15_000L), slept, "retry-after in seconds, then the tokens' reset, then a growing default");
        String spent = "{\"error\":{\"type\":\"insufficient_quota\",\"message\":\"You exceeded your current quota\",\"code\":\"insufficient_quota\"}}";
        OpenAI o2 = new OpenAI(null, () -> "k", new Script().then(429, spent), slept::add);
        OpenAI.Refused r = assertThrows(OpenAI.Refused.class, () -> o2.chat("m", ask(), Duration.ofSeconds(5)));
        assertEquals(429, r.status); assertTrue(r.getMessage().contains("no credit left"), r.getMessage());
        assertEquals(3, slept.size(), "a spent account is not waited for");
        Script always = new Script(); for (int i = 0; i < 8; i++) always.then(429, limited);
        OpenAI o3 = new OpenAI(null, () -> "k", always, slept::add);
        assertTrue(assertThrows(OpenAI.Refused.class, () -> o3.chat("m", ask(), Duration.ofSeconds(5))).getMessage().contains("six waits"));
        assertEquals(1_500L, OpenAI.durationMillis("1.5s")); assertEquals(6L, OpenAI.durationMillis("6ms")); assertEquals(3_600_000L + 120_000L, OpenAI.durationMillis("1h2m0s")); assertEquals(0L, OpenAI.durationMillis("soon"));
    }

    @Test
    void anOverloadIsTriedAgainWithAGrowingWaitAndTheMinutesTokensArePaced() {
        Script t = new Script().then(503, "{\"error\":{\"message\":\"overloaded\"}}").then(500, "oops").then(200, OK, "x-ratelimit-remaining-tokens", "1500", "x-ratelimit-reset-tokens", "2s").then(200, OK);
        List<Long> slept = new ArrayList<>();
        OpenAI o = new OpenAI(null, () -> "k", t, slept::add);
        o.chat("m", ask(), Duration.ofSeconds(5));
        assertEquals(List.of(2_000L, 4_000L), slept, "2 s, then 4 s");
        o.chat("m", ask(), Duration.ofSeconds(5));
        assertEquals(3, slept.size(), "the next call waited for the minute's reset"); assertTrue(slept.get(2) > 0 && slept.get(2) <= 2_000, "about the reset the header gave: " + slept.get(2));
        Script dead = new Script(); for (int i = 0; i < 6; i++) dead.then(502, "bad gateway");
        OpenAI o2 = new OpenAI(null, () -> "k", dead, slept::add);
        assertTrue(assertThrows(OpenAI.Refused.class, () -> o2.chat("m", ask(), Duration.ofSeconds(5))).getMessage().contains("five tries"));
    }

    @Test
    void aRefusalAFailedReplyAndTheOutputCapLearnt() throws Exception {
        Script t = new Script().then(400, "{\"error\":{\"message\":\"Unsupported value: 'reasoning.effort' does not support 'none' with this model.\",\"type\":\"invalid_request_error\"}}");
        List<Long> slept = new ArrayList<>();
        OpenAI o = new OpenAI(null, () -> "k", t, slept::add);
        OpenAI.Refused r = assertThrows(OpenAI.Refused.class, () -> o.chat("m", ask(), Duration.ofSeconds(5)));
        assertEquals(400, r.status); assertTrue(r.getMessage().contains("does not support 'none'"), r.getMessage());
        Script failed = new Script().then(200, "{\"id\":\"resp_2\",\"status\":\"failed\",\"error\":{\"code\":\"server_error\",\"message\":\"The model produced invalid output\"},\"output\":[]}");
        OpenAI o2 = new OpenAI(null, () -> "k", failed, slept::add);
        assertTrue(assertThrows(OpenAI.Refused.class, () -> o2.chat("m", ask(), Duration.ofSeconds(5))).getMessage().contains("invalid output"), "a reply that failed is a refusal, never an empty answer");
        // the cap: ours is capped at what the model can write, and what a refusal names is learnt and asked once more
        Script cap = new Script().then(400, "{\"error\":{\"message\":\"Invalid 'max_output_tokens': integer above maximum value. Expected a value <= 65536, but got 128000 instead.\"}}").then(200, OK);
        OpenAI o3 = new OpenAI(null, () -> "k", cap, slept::add);
        ObjectNode big = ask(); big.put("max_tokens", 500_000);
        assertEquals("hello", o3.chat("small-model", big, Duration.ofSeconds(5)).path("choices").get(0).path("message").path("content").asText());
        assertEquals(128_000, M.readTree(cap.sent.get(0).body()).path("max_output_tokens").asInt(), "first the default cap");
        assertEquals(65_536, M.readTree(cap.sent.get(1).body()).path("max_output_tokens").asInt(), "then the cap the API named");
        assertEquals(65_536, o3.maxOutput("small-model")); assertEquals(128_000, o3.maxOutput("other"));
        assertEquals(272_000, OpenAI.DEFAULT_CONTEXT, "the standard-price tier, the models list saying nothing");
        assertEquals(Config.getInt("CODEZAIKU_CTX", OpenAI.DEFAULT_CONTEXT), o3.contextWindow("gpt-6.1-sol"), "CODEZAIKU_CTX wins (the test suite sets it)");
        Script models = new Script().then(200, "{\"data\":[{\"id\":\"gpt-6.1-sol\"},{\"id\":\"gpt-6-luna\"}]}").then(401, "{\"error\":{\"message\":\"bad key\"}}");
        OpenAI o4 = new OpenAI(null, () -> "k", models, slept::add);
        assertEquals(List.of("gpt-6.1-sol", "gpt-6-luna"), o4.models()); assertFalse(o4.reachable(), "a key the models list refuses does not open the API");
    }
}
