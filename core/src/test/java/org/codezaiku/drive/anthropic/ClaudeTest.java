package org.codezaiku.drive.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** The Claude API with somebody's own key: where the request goes, what it carries, what happens when the API is busy or refuses, and what the person is told. */
class ClaudeTest {

    private static final ObjectMapper J = new ObjectMapper();
    private static final String LISTING = """
        {"id": "claude-opus-5-5", "max_input_tokens": 1000000, "max_tokens": 128000,
         "capabilities": {"thinking": {"supported": true, "types": {"enabled": {"supported": false}, "adaptive": {"supported": true}}},
                          "effort": {"supported": true, "low": {"supported": true}, "medium": {"supported": true}, "high": {"supported": true}, "xhigh": {"supported": true}, "max": {"supported": true}}}}""";
    private static final String[] HELLO = {
        "event: message_start", "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}}", "",
        "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
        "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}",
        "data: {\"type\":\"content_block_stop\",\"index\":0}",
        "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}",
        "data: {\"type\":\"message_stop\"}"};

    private static ObjectNode ask(String model) throws Exception {
        return (ObjectNode) J.readTree("{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":500000,\"reasoning_effort\":\"xhigh\"}");
    }

    private static Claude.Answer stream(Consumer<String> onLine, String... lines) { for (String l : lines) onLine.accept(l); return new Claude.Answer(200, ""); }

    @Test
    void whereItGoesWhatItCarriesAndWhatTheListingDecides() throws Exception {
        List<String> seen = new ArrayList<>(), bodies = new ArrayList<>();
        Claude c = new Claude("https://api.anthropic.com/", () -> " Bearer sk-ant-test ", (method, uri, headers, body, idle, onLine) -> {
            seen.add(method + " " + uri + " " + headers);
            if (method.equals("GET")) return new Claude.Answer(200, LISTING);
            bodies.add(body);
            return stream(onLine, HELLO);
        }, ms -> fail("nothing to wait for"));
        ObjectNode r = c.chat("claude-opus-5-5-a", ask("claude-opus-5-5-a"), Duration.ofSeconds(5), null, null);
        assertEquals("hello", r.path("choices").get(0).path("message").path("content").asText());
        assertEquals("stop", r.path("choices").get(0).path("finish_reason").asText());
        assertTrue(seen.get(0).startsWith("GET https://api.anthropic.com/v1/models/claude-opus-5-5-a "), seen.get(0));
        assertTrue(seen.get(1).startsWith("POST https://api.anthropic.com/v1/messages "), seen.get(1));
        assertTrue(seen.get(1).contains("x-api-key=sk-ant-test") && seen.get(1).contains("anthropic-version=2023-06-01") && !seen.get(1).contains("Authorization"), seen.get(1));
        JsonNode sent = J.readTree(bodies.get(0));
        assertEquals(128_000, sent.path("max_tokens").asInt(), "no more output is asked for than the model writes");
        assertEquals("xhigh", sent.path("output_config").path("effort").asText());
        assertEquals("adaptive", sent.path("thinking").path("type").asText());
        assertTrue(sent.path("stream").asBoolean());
        c.chat("claude-opus-5-5-a", ask("claude-opus-5-5-a"), Duration.ofSeconds(5), null, null);
        assertEquals(3, seen.size(), "the listing is read once: " + seen);
        assertEquals(200_000, c.contextWindow("claude-opus-5-5-a", 0), "a million-token window is not filled unless a setting says so");
        assertEquals(400_000, c.contextWindow("claude-opus-5-5-a", 400_000));
    }

    @Test
    void aModelWithoutAnEffortSettingAndAListingThatCannotBeRead() throws Exception {
        List<String> bodies = new ArrayList<>();
        Claude small = new Claude("https://api.anthropic.com", () -> "k", (method, uri, headers, body, idle, onLine) -> {
            if (method.equals("GET")) return new Claude.Answer(200, "{\"max_input_tokens\": 150000, \"max_tokens\": 64000, \"capabilities\": {\"thinking\": {\"types\": {\"adaptive\": {\"supported\": false}}}, \"effort\": {\"supported\": false}}}");
            bodies.add(body); return stream(onLine, HELLO);
        }, ms -> { });
        small.chat("claude-small-b", ask("claude-small-b"), Duration.ofSeconds(5), null, null);
        JsonNode sent = J.readTree(bodies.get(0));
        assertFalse(sent.has("output_config") || sent.has("thinking"), sent.toString());
        assertEquals(64_000, sent.path("max_tokens").asInt());
        assertEquals(150_000, small.contextWindow("claude-small-b", 0));

        AtomicInteger listings = new AtomicInteger();
        Claude unlisted = new Claude("https://api.anthropic.com", () -> "k", (method, uri, headers, body, idle, onLine) -> {
            if (method.equals("GET")) { listings.incrementAndGet(); return new Claude.Answer(503, "busy"); }
            bodies.add(body); return stream(onLine, HELLO);
        }, ms -> { });
        unlisted.chat("claude-unlisted-c", ask("claude-unlisted-c"), Duration.ofSeconds(5), null, null);
        unlisted.chat("claude-unlisted-c", ask("claude-unlisted-c"), Duration.ofSeconds(5), null, null);
        assertEquals(2, listings.get(), "a listing that could not be read is asked for again");
        assertEquals("xhigh", J.readTree(bodies.get(1)).path("output_config").path("effort").asText(), "sent as configured when nothing is known");
        assertEquals(Claude.WINDOW, unlisted.contextWindow("claude-unlisted-c", 0));
    }

    @Test
    void busyIsWaitedOutBeforeAnythingOfTheReplyWasShown() throws Exception {
        AtomicInteger calls = new AtomicInteger(), waited = new AtomicInteger();
        Claude c = new Claude("https://api.anthropic.com", () -> "k", (method, uri, headers, body, idle, onLine) -> {
            if (method.equals("GET")) return new Claude.Answer(200, LISTING);
            return switch (calls.incrementAndGet()) {
                case 1 -> new Claude.Answer(529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}");
                case 2 -> new Claude.Answer(429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}");
                case 3 -> stream(onLine, "data: {\"type\":\"message_start\",\"message\":{}}", "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}");
                default -> stream(onLine, HELLO);
            };
        }, ms -> waited.incrementAndGet());
        assertEquals("hello", c.chat("claude-busy-d", ask("claude-busy-d"), Duration.ofSeconds(5), null, null).path("choices").get(0).path("message").path("content").asText());
        assertEquals(4, calls.get());
        assertEquals(3, waited.get());

        Claude midway = new Claude("https://api.anthropic.com", () -> "k", (method, uri, headers, body, idle, onLine) -> method.equals("GET") ? new Claude.Answer(200, LISTING)
                : stream(onLine, HELLO[1], HELLO[3], HELLO[4], "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"), ms -> fail("a reply that had begun is not asked for again"));
        Claude.Refused r = assertThrows(Claude.Refused.class, () -> midway.chat("claude-busy-d", ask("claude-busy-d"), Duration.ofSeconds(5), null, null));
        assertTrue(r.getMessage().contains("stopped the reply") && r.getMessage().contains("Overloaded"), r.getMessage());

        Claude cut = new Claude("https://api.anthropic.com", () -> "k", (method, uri, headers, body, idle, onLine) -> method.equals("GET") ? new Claude.Answer(200, LISTING)
                : stream(onLine, HELLO[1], HELLO[3], HELLO[4]), ms -> { });
        assertTrue(assertThrows(Claude.Refused.class, () -> cut.chat("claude-busy-d", ask("claude-busy-d"), Duration.ofSeconds(5), null, null)).getMessage().contains("before it was complete"));
    }

    @Test
    void aRefusalIsSaidInWordsThePersonCanActOn() throws Exception {
        Claude noKey = new Claude("https://api.anthropic.com", () -> " ", (m, u, h, b, t, l) -> fail("nothing is sent without a key"), ms -> { });
        Claude.Refused none = assertThrows(Claude.Refused.class, () -> noKey.chat("claude-x-e", ask("claude-x-e"), Duration.ofSeconds(5), null, null));
        assertTrue(none.getMessage().contains("codezaiku config set api.key"), none.getMessage());

        Claude badKey = new Claude("https://api.anthropic.com", () -> "k", (m, u, h, b, t, l) -> new Claude.Answer(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"), ms -> { });
        Claude.Refused r = assertThrows(Claude.Refused.class, () -> badKey.chat("claude-x-e", ask("claude-x-e"), Duration.ofSeconds(5), null, null));
        assertEquals(401, r.status);
        assertTrue(r.getMessage().contains("did not accept the key") && r.getMessage().contains("The API said: invalid x-api-key"), r.getMessage());
        assertFalse(badKey.answers());

        Claude noModel = new Claude("https://api.anthropic.com", () -> "k", (m, u, h, b, t, l) -> new Claude.Answer(404, "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":\"model: claude-x-e\"}}"), ms -> { });
        assertTrue(assertThrows(Claude.Refused.class, () -> noModel.chat("claude-x-e", ask("claude-x-e"), Duration.ofSeconds(5), null, null)).getMessage().contains("does not know the model \"claude-x-e\""));

        Claude other = new Claude("https://api.anthropic.com", () -> "k", (m, u, h, b, t, l) -> m.equals("GET") ? new Claude.Answer(200, LISTING) : new Claude.Answer(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Your credit balance is too low\"}}"), ms -> { });
        assertTrue(assertThrows(Claude.Refused.class, () -> other.chat("claude-x-e", ask("claude-x-e"), Duration.ofSeconds(5), null, null)).getMessage().contains("HTTP 400). The API said: Your credit balance is too low"));

        Claude dead = new Claude("https://api.anthropic.com", () -> "k", (m, u, h, b, t, l) -> { throw new IOException("connection reset"); }, ms -> { });
        assertTrue(assertThrows(Claude.Refused.class, () -> dead.chat("claude-x-e", ask("claude-x-e"), Duration.ofSeconds(5), null, null)).getMessage().contains("could not be reached"));
    }

    @Test
    void whichAddressIsTheClaudeApi() {
        assertTrue(Claude.is("https://api.anthropic.com") && Claude.is(" https://API.anthropic.com/v1/messages "));
        assertTrue(Claude.is("https://gateway.example.org/claude/v1/messages"), "a Messages endpoint somewhere else is the Claude API through a gateway");
        assertFalse(Claude.is("https://api.openai.com") || Claude.is("bedrock") || Claude.is(null) || Claude.is("http://localhost:8210") || Claude.is("https://api.anthropic.com.example.org"));
        assertFalse(Claude.is("https://api.openai.com/v1/chat/completions") || Claude.is("https://gateway.example.org/v1"));
    }
}
