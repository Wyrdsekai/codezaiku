package org.codezaiku.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.codezaiku.Config;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A hosted OpenAI-style API's limits and shape (OpenRouter, Gemini, Nebius, Hugging Face): a 429 is waited out for as long as retry-after
 * says and the call goes on; an error OpenRouter puts inside a 200 is said as an error, never read as an empty reply; the request is
 * shaped as the host takes it; a job's tokens are counted on its own thread; the endpoint root keeps Gemini's path.
 */
class DriveHostedLimitsTest {

    static final ObjectMapper M = new ObjectMapper();

    @Test
    void aRateLimitIsWaitedOutAndTheCallGoesOnAndTheTokensAreCounted() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath(); byte[] out; int code = 200;
            if (path.equals("/v1/chat/completions")) {
                ex.getRequestBody().readAllBytes();
                if (calls.incrementAndGet() == 1) { code = 429; ex.getResponseHeaders().add("Retry-After", "1"); out = "{\"error\":{\"code\":429,\"message\":\"Rate limited\"}}".getBytes(StandardCharsets.UTF_8); }
                else out = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"after the wait\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3,\"total_tokens\":13,\"prompt_tokens_details\":{\"cached_tokens\":4}}}".getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/v1/models")) out = "{\"data\":[{\"id\":\"m\",\"context_length\":32768}]}".getBytes(StandardCharsets.UTF_8);
            else { code = 404; out = "{}".getBytes(StandardCharsets.UTF_8); }
            ex.sendResponseHeaders(code, out.length); ex.getResponseBody().write(out); ex.close();
        });
        s.start();
        try {
            DriveClient c = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m");
            ArrayNode msgs = M.createArrayNode(); msgs.addObject().put("role", "user").put("content", "hi");
            long[] mine = new long[4]; AtomicReference<long[]> theirs = new AtomicReference<>(new long[4]);
            DriveClient.countInto(mine);
            long t0 = System.nanoTime();
            JsonNode reply;
            try { reply = c.chat(msgs, null, 32, "none"); } finally { DriveClient.stopCounting(); }
            assertEquals("after the wait", reply.path("content").asText());
            assertEquals(2, calls.get());
            assertTrue((System.nanoTime() - t0) / 1_000_000 >= 900, "waited the second retry-after named");
            assertArrayEquals(new long[]{10, 4, 0, 3}, mine, "prompt, read from the cache (an OpenAI-style server's word), written, completion");
            Thread other = new Thread(() -> { DriveClient.countInto(theirs.get()); try { c.chat(msgs, null, 32, "none"); } finally { DriveClient.stopCounting(); } });
            other.start(); other.join();
            assertArrayEquals(new long[]{10, 4, 0, 3}, mine, "another thread's job is not counted here");
            assertArrayEquals(new long[]{10, 4, 0, 3}, theirs.get());
        } finally { s.stop(0); }
    }

    @Test
    void anErrorInsideA200IsSaidAsAnError() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath(); byte[] out; int code = 200;
            if (path.equals("/v1/chat/completions")) { ex.getRequestBody().readAllBytes(); out = "{\"error\":{\"code\":502,\"message\":\"Provider returned error\",\"metadata\":{\"provider_name\":\"x\"}},\"user_id\":\"u\"}".getBytes(StandardCharsets.UTF_8); }
            else if (path.equals("/v1/models")) out = "{\"data\":[{\"id\":\"m\",\"context_length\":32768}]}".getBytes(StandardCharsets.UTF_8);
            else { code = 404; out = "{}".getBytes(StandardCharsets.UTF_8); }
            ex.sendResponseHeaders(code, out.length); ex.getResponseBody().write(out); ex.close();
        });
        s.start();
        try {
            DriveClient c = new DriveClient("http://127.0.0.1:" + s.getAddress().getPort(), "m");
            ArrayNode msgs = M.createArrayNode(); msgs.addObject().put("role", "user").put("content", "hi");
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> c.chat(msgs, null, 32, "none"));
            assertTrue(e.getMessage().contains("Provider returned error"), e.getMessage());
        } finally { s.stop(0); }
    }

    @Test
    void aHostedApiGetsNoTemplateFieldOnlyTheFieldsItTakesAndADecisionAsksForLittleReasoning() {
        ObjectNode body = M.createObjectNode(); body.put("model", "m"); body.put("max_tokens", 12);
        body.putObject("chat_template_kwargs").put("enable_thinking", false);
        body.put("thinking_budget_tokens", 500);
        body.put("temperature", 0.7); body.put("top_p", 0.9); body.put("reasoning_effort", "medium");
        DriveClient.OpenRouterModels.OVERRIDE = Map.of("m", new DriveClient.OpenRouterModels.Entry(false, Set.of("tools", "tool_choice", "reasoning", "max_tokens")),
                "must", new DriveClient.OpenRouterModels.Entry(true, Set.of("tools", "temperature", "max_tokens")));
        try {
            ObjectNode or = DriveClient.hostedShape(body.deepCopy(), "https://openrouter.ai/api/v1");
            assertFalse(or.has("chat_template_kwargs")); assertFalse(or.has("thinking_budget_tokens"), "llama.cpp's fields stay home");
            assertFalse(or.path("reasoning").path("enabled").asBoolean(true), "a decision: off where the model allows it"); assertEquals(DriveClient.QUICK_FLOOR, or.path("max_tokens").asInt(), "room for the least thinking");
            assertFalse(or.has("temperature") || or.has("top_p"), "a field the model's endpoints do not take is not sent: " + or);
            assertFalse(or.has("reasoning_effort"), "OpenRouter's word is reasoning.effort");
            ObjectNode working = body.deepCopy(); working.remove("chat_template_kwargs");
            ObjectNode shapedWork = DriveClient.hostedShape(working, "https://openrouter.ai/api/v1");
            assertEquals("medium", shapedWork.path("reasoning").path("effort").asText(), "the effort setting, in OpenRouter's field"); assertEquals(12, shapedWork.path("max_tokens").asInt());
            ObjectNode must = body.deepCopy(); must.put("model", "must");
            ObjectNode shaped = DriveClient.hostedShape(must, "https://openrouter.ai/api/v1");
            assertEquals("low", shaped.path("reasoning").path("effort").asText(), "little where it cannot be turned off");
            assertTrue(shaped.has("temperature") && !shaped.has("top_p"), "only the fields the model takes: " + shaped);
            ObjectNode unknown = body.deepCopy(); unknown.put("model", "nobody/knows");
            ObjectNode u = DriveClient.hostedShape(unknown, "https://openrouter.ai/api/v1");
            assertEquals("low", u.path("reasoning").path("effort").asText(), "little when the list does not say"); assertTrue(u.has("temperature"), "every field when the list does not say");
        } finally { DriveClient.OpenRouterModels.OVERRIDE = null; }
        // a host that runs vLLM gets the template field back, with DeepSeek's own word beside the usual one; a working turn sends none
        ObjectNode neb = DriveClient.hostedShape(body.deepCopy(), "https://api.tokenfactory.nebius.com/v1");
        assertFalse(neb.path("chat_template_kwargs").path("enable_thinking").asBoolean(true)); assertFalse(neb.path("chat_template_kwargs").path("thinking").asBoolean(true), neb.toString());
        assertEquals(DriveClient.QUICK_FLOOR, neb.path("max_tokens").asInt()); assertFalse(neb.has("reasoning") || neb.has("thinking_budget_tokens"));
        ObjectNode nebWork = M.createObjectNode(); nebWork.put("model", "m"); nebWork.putObject("chat_template_kwargs").put("enable_thinking", true);
        assertFalse(DriveClient.hostedShape(nebWork, "https://api.tokenfactory.nebius.com/v1").has("chat_template_kwargs"), "a working turn keeps the model's own thinking, and the field goes home");
        assertEquals("disabled", DriveClient.hostedShape(body.deepCopy(), "https://api.deepseek.com/v1").path("thinking").path("type").asText(), "DeepSeek's own API has its own field");
        assertTrue(DriveClient.templateHost("router.huggingface.co") && !DriveClient.templateHost("openrouter.ai"));
        ObjectNode gem = DriveClient.hostedShape(body.deepCopy(), "https://generativelanguage.googleapis.com/v1beta/openai");
        assertFalse(gem.has("chat_template_kwargs")); assertEquals("low", gem.path("reasoning_effort").asText()); assertFalse(gem.has("reasoning"));
        ObjectNode thinking = M.createObjectNode(); thinking.putObject("chat_template_kwargs").put("enable_thinking", true);
        assertFalse(DriveClient.hostedShape(thinking, "https://openrouter.ai/api/v1").has("reasoning"), "a working turn keeps the model's own reasoning");
        for (String h : List.of("https://openrouter.ai/api/v1", "https://generativelanguage.googleapis.com/v1beta/openai", "https://api.tokenfactory.nebius.com/v1", "https://router.huggingface.co/v1", "https://api.deepseek.com/v1"))
            assertTrue(DriveClient.hostedApi(h), h);
        assertFalse(DriveClient.hostedApi("https://api.openai.com/v1"), "OpenAI's own API has a client of its own");
        assertFalse(DriveClient.hostedApi("https://api.anthropic.com") || DriveClient.hostedApi("http://localhost:8200") || DriveClient.hostedApi("bedrock"));
        assertTrue(new DriveClient("https://openrouter.ai/api/v1", "x/y").openRouter()); assertFalse(new DriveClient("http://localhost:8080", "m").openRouter());
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", Config.v1("https://generativelanguage.googleapis.com/v1beta/openai"), "Gemini's root has no /v1");
        assertEquals("https://openrouter.ai/api/v1", Config.v1("https://openrouter.ai/api")); assertEquals("http://localhost:8200/v1", Config.v1("http://localhost:8200/"));
    }
}
