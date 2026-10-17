package org.codezaiku.drive.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The translation to and from OpenAI's Responses API: the input items, the reasoning carried back, the tools, no sampling fields, the usage with the cache's share. */
class ResponsesTest {

    static final ObjectMapper M = new ObjectMapper();

    static ObjectNode chatBody() {
        ObjectNode b = M.createObjectNode();
        b.put("model", "x"); b.put("max_tokens", 500); b.put("temperature", 0.7); b.put("top_p", 0.9); b.put("tool_choice", "required");
        ArrayNode msgs = b.putArray("messages");
        msgs.addObject().put("role", "system").put("content", "You are the familiar.");
        msgs.addObject().put("role", "developer").put("content", "Cite everything.");
        msgs.addObject().put("role", "user").put("content", "Who wrote it?");
        ObjectNode a = msgs.addObject(); a.put("role", "assistant"); a.put("content", "Let me look.");
        ObjectNode rs = a.putArray(Responses.REASONING).addObject(); rs.put("type", "reasoning"); rs.put("id", "rs_1"); rs.putArray("summary"); rs.put("encrypted_content", "gAAAA…");
        ObjectNode call = a.putArray("tool_calls").addObject(); call.put("id", "call_1"); call.put("type", "function"); call.putObject("function").put("name", "search").put("arguments", "{\"q\":\"author\"}");
        msgs.addObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "a result");
        msgs.addObject().put("role", "user").put("content", "and then?");
        ArrayNode tools = b.putArray("tools");
        for (String n : new String[]{"search", "done"}) { ObjectNode t = tools.addObject(); t.put("type", "function"); ObjectNode f = t.putObject("function"); f.put("name", n); f.put("description", n + " it"); ObjectNode p = f.putObject("parameters"); p.put("type", "object"); p.putObject("properties").putObject("q").put("type", "string"); }
        return b;
    }

    @Test
    void aRequestCarriesTheConversationAsItemsWithTheReasoningBeforeItsCalls() {
        ObjectNode r = Responses.request(chatBody(), "gpt-6.1-sol", "medium");
        assertEquals("gpt-6.1-sol", r.path("model").asText()); assertEquals(500, r.path("max_output_tokens").asInt());
        assertFalse(r.has("temperature")); assertFalse(r.has("top_p")); assertFalse(r.has("max_tokens"));
        assertFalse(r.path("store").asBoolean(true), "stateless: the conversation is ours"); assertEquals("reasoning.encrypted_content", r.path("include").get(0).asText());
        assertEquals("You are the familiar.\n\nCite everything.", r.path("instructions").asText());
        JsonNode in = r.path("input");
        assertEquals(6, in.size(), in.toString());
        assertEquals("user", in.get(0).path("role").asText()); assertEquals("input_text", in.get(0).path("content").get(0).path("type").asText()); assertEquals("Who wrote it?", in.get(0).path("content").get(0).path("text").asText());
        assertEquals("reasoning", in.get(1).path("type").asText(), "what it thought goes in again, before the message and the calls"); assertEquals("gAAAA…", in.get(1).path("encrypted_content").asText());
        assertEquals("assistant", in.get(2).path("role").asText()); assertEquals("output_text", in.get(2).path("content").get(0).path("type").asText()); assertEquals("Let me look.", in.get(2).path("content").get(0).path("text").asText());
        assertEquals("function_call", in.get(3).path("type").asText()); assertEquals("call_1", in.get(3).path("call_id").asText()); assertEquals("search", in.get(3).path("name").asText()); assertEquals("{\"q\":\"author\"}", in.get(3).path("arguments").asText());
        assertEquals("function_call_output", in.get(4).path("type").asText()); assertEquals("call_1", in.get(4).path("call_id").asText()); assertEquals("a result", in.get(4).path("output").asText());
        assertEquals("and then?", in.get(5).path("content").get(0).path("text").asText());
        assertEquals(2, r.path("tools").size());
        assertEquals("function", r.path("tools").get(0).path("type").asText()); assertEquals("search", r.path("tools").get(0).path("name").asText()); assertEquals("object", r.path("tools").get(0).path("parameters").path("type").asText()); assertFalse(r.path("tools").get(0).path("strict").asBoolean(true));
        assertEquals("required", r.path("tool_choice").asText(), "required is allowed here");
        assertEquals("medium", r.path("reasoning").path("effort").asText());
        assertFalse(Responses.request(chatBody(), "m", "").has("reasoning"), "unset: the model's own default");
        ObjectNode named = chatBody(); named.putObject("tool_choice").put("type", "function").putObject("function").put("name", "done");
        assertEquals("done", Responses.request(named, "m", null).path("tool_choice").path("name").asText());
        // a decision asked for without thinking: the least these models do, and room for it under the budget
        ObjectNode quick = chatBody(); quick.put("max_tokens", 12); quick.putObject("chat_template_kwargs").put("enable_thinking", false);
        ObjectNode q = Responses.request(quick, "m", null);
        assertEquals("low", q.path("reasoning").path("effort").asText()); assertEquals(Responses.QUICK_FLOOR, q.path("max_output_tokens").asInt()); assertFalse(q.has("chat_template_kwargs"));
        assertEquals("high", Responses.request(quick, "m", "high").path("reasoning").path("effort").asText(), "a set effort wins");
        ObjectNode empty = M.createObjectNode(); empty.putArray("messages");
        assertEquals("(continue)", Responses.request(empty, "m", null).path("input").get(0).path("content").get(0).path("text").asText(), "never an empty input");
    }

    @Test
    void aReplyBecomesAChatCompletionWithItsCallsReasoningStopReasonAndCacheShare() throws Exception {
        JsonNode reply = M.readTree("{\"id\":\"resp_1\",\"model\":\"gpt-6.1-sol\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"reasoning\",\"id\":\"rs_9\",\"summary\":[],\"encrypted_content\":\"zzz\"},"
                + "{\"type\":\"message\",\"id\":\"msg_1\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Looking.\"}]},"
                + "{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_7\",\"name\":\"search\",\"arguments\":\"{\\\"q\\\":\\\"author\\\"}\"}],"
                + "\"usage\":{\"input_tokens\":10120,\"input_tokens_details\":{\"cached_tokens\":9000},\"output_tokens\":30,\"output_tokens_details\":{\"reasoning_tokens\":12},\"total_tokens\":10150}}");
        ObjectNode c = Responses.response(reply, "gpt-6.1-sol");
        JsonNode msg = c.path("choices").get(0).path("message");
        assertEquals("Looking.", msg.path("content").asText());
        assertEquals("search", msg.path("tool_calls").get(0).path("function").path("name").asText());
        assertEquals("{\"q\":\"author\"}", msg.path("tool_calls").get(0).path("function").path("arguments").asText());
        assertEquals("call_7", msg.path("tool_calls").get(0).path("id").asText(), "the call id is what the result answers to");
        assertEquals("zzz", msg.path(Responses.REASONING).get(0).path("encrypted_content").asText(), "the reasoning stays on the message for the next turn");
        assertEquals("tool_calls", c.path("choices").get(0).path("finish_reason").asText());
        assertEquals(10120, c.path("usage").path("prompt_tokens").asLong()); assertEquals(9000, c.path("usage").path("prompt_tokens_details").path("cached_tokens").asLong(), "the cache's share, where the usage line reads it");
        assertEquals(30, c.path("usage").path("completion_tokens").asLong()); assertEquals(12, c.path("usage").path("completion_tokens_details").path("reasoning_tokens").asLong());
        JsonNode cut = M.readTree("{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Half\"}]}],\"usage\":{\"input_tokens\":5,\"output_tokens\":5}}");
        ObjectNode l = Responses.response(cut, "m");
        assertEquals("length", l.path("choices").get(0).path("finish_reason").asText()); assertEquals("Half", l.path("choices").get(0).path("message").path("content").asText());
        assertFalse(l.path("choices").get(0).path("message").has(Responses.REASONING), "no reasoning items, no field");
        JsonNode refused = M.readTree("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"not this\"}]}],\"usage\":{\"input_tokens\":5,\"output_tokens\":5}}");
        ObjectNode r = Responses.response(refused, "m");
        assertEquals("refusal", r.path("choices").get(0).path("finish_reason").asText());
        assertEquals("not this", r.path("choices").get(0).path("message").path("refusal").asText(), "the decline travels as the chat format's refusal, which the drive client reads");
        JsonNode plain = M.readTree("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Yes.\"}]}],\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}");
        assertEquals("stop", Responses.response(plain, "m").path("choices").get(0).path("finish_reason").asText());
    }

    @Test
    void whatCameBackGoesInAgainUnchanged() throws Exception {
        // the round trip: a reply's message, with its reasoning and calls, becomes the next request's items in the order the API gave them
        JsonNode reply = M.readTree("{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"id\":\"rs_2\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"thinking\"}],\"encrypted_content\":\"abc\"},"
                + "{\"type\":\"function_call\",\"call_id\":\"call_2\",\"name\":\"read\",\"arguments\":\"{}\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");
        ObjectNode message = (ObjectNode) Responses.response(reply, "m").path("choices").get(0).path("message");
        ObjectNode body = M.createObjectNode();
        ArrayNode msgs = body.putArray("messages");
        msgs.addObject().put("role", "user").put("content", "go");
        msgs.add(message);
        msgs.addObject().put("role", "tool").put("tool_call_id", "call_2").put("content", "page text");
        JsonNode in = Responses.request(body, "m", null).path("input");
        assertEquals(4, in.size(), in.toString());
        assertEquals("reasoning", in.get(1).path("type").asText()); assertEquals("rs_2", in.get(1).path("id").asText()); assertEquals("thinking", in.get(1).path("summary").get(0).path("text").asText()); assertEquals("abc", in.get(1).path("encrypted_content").asText());
        assertEquals("function_call", in.get(2).path("type").asText()); assertEquals("call_2", in.get(2).path("call_id").asText());
        assertEquals("function_call_output", in.get(3).path("type").asText()); assertEquals("page text", in.get(3).path("output").asText());
        assertTrue(message.path("content").isNull(), "a reply that only called tools has no text");
    }
}
