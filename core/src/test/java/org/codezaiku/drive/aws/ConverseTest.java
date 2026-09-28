package org.codezaiku.drive.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A chat-completions request becomes a Converse request, and Converse's answer becomes a chat completion. */
class ConverseTest {

    private static final ObjectMapper J = new ObjectMapper();

    @Test
    void aConversationWithToolsAndAPicture() throws Exception {
        JsonNode body = J.readTree("""
            {"model": "ignored here", "max_tokens": 900, "temperature": 0.2, "stop": ["END"], "tool_choice": "required",
             "tools": [{"type": "function", "function": {"name": "web_search", "description": "search the web", "parameters": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}}},
                       {"type": "function", "function": {"name": "finish"}}],
             "messages": [
               {"role": "system", "content": "You are a librarian."},
               {"role": "system", "content": "Cite what you use."},
               {"role": "user", "content": [{"type": "text", "text": "What is on this page?"}, {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,QUJD"}}]},
               {"role": "assistant", "content": "", "tool_calls": [{"id": "call_1", "type": "function", "function": {"name": "web_search", "arguments": "{\\"query\\": \\"town register 1872\\"}"}},
                                                                 {"id": "call_2", "type": "function", "function": {"name": "web_search", "arguments": "not json"}}]},
               {"role": "tool", "tool_call_id": "call_1", "content": "three results"},
               {"role": "tool", "tool_call_id": "call_2", "content": ""},
               {"role": "user", "content": "Go on."}]}""");
        ObjectNode c = Converse.request(body);
        assertEquals(2, c.path("system").size());
        assertEquals("You are a librarian.", c.path("system").get(0).path("text").asText());
        assertEquals(3, c.path("messages").size(), "the two tool results and the next user message are one turn of the user side: " + c.path("messages"));
        JsonNode user = c.path("messages").get(0), assistant = c.path("messages").get(1), results = c.path("messages").get(2);
        assertEquals("jpeg", user.path("content").get(1).path("image").path("format").asText());
        assertEquals("QUJD", user.path("content").get(1).path("image").path("source").path("bytes").asText());
        assertEquals(2, assistant.path("content").size(), "an empty text is not sent: " + assistant);
        assertEquals("town register 1872", assistant.path("content").get(0).path("toolUse").path("input").path("query").asText());
        assertEquals("not json", assistant.path("content").get(1).path("toolUse").path("input").path("_unparsed").asText());
        assertEquals("user", results.path("role").asText());
        assertEquals("call_1", results.path("content").get(0).path("toolResult").path("toolUseId").asText());
        assertEquals("(nothing)", results.path("content").get(1).path("toolResult").path("content").get(0).path("text").asText());
        assertEquals("Go on.", results.path("content").get(2).path("text").asText());
        assertEquals(900, c.path("inferenceConfig").path("maxTokens").asInt());
        assertEquals("END", c.path("inferenceConfig").path("stopSequences").get(0).asText());
        assertTrue(c.path("toolConfig").path("toolChoice").has("any"), "required is Converse's any");
        assertEquals("finish", c.path("toolConfig").path("tools").get(1).path("toolSpec").path("description").asText(), "Converse wants a description");
        assertEquals("object", c.path("toolConfig").path("tools").get(1).path("toolSpec").path("inputSchema").path("json").path("type").asText());
        assertFalse(Converse.withoutToolChoice(c).path("toolConfig").has("toolChoice"));
        assertTrue(c.path("toolConfig").has("toolChoice"), "the original is left as it was");
    }

    @Test
    void aNamedToolAndAConversationThatBeginsWithTheAssistant() throws Exception {
        ObjectNode c = Converse.request(J.readTree("""
            {"tool_choice": {"type": "function", "function": {"name": "finish"}}, "tools": [{"type": "function", "function": {"name": "finish", "description": "done", "parameters": {"type": "object"}}}],
             "messages": [{"role": "assistant", "content": "I was saying"}, {"role": "user", "content": "yes?"}]}"""));
        assertEquals("finish", c.path("toolConfig").path("toolChoice").path("tool").path("name").asText());
        assertEquals("user", c.path("messages").get(0).path("role").asText(), "Converse wants the user side first");
        assertFalse(c.has("system"));
        assertThrows(IllegalArgumentException.class, () -> Converse.request(J.readTree("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.org/a.png\"}}]}]}")));
    }

    @Test
    void conversesAnswerAsAChatCompletion() throws Exception {
        ObjectNode r = Converse.response(J.readTree("""
            {"output": {"message": {"role": "assistant", "content": [{"reasoningContent": {"reasoningText": {"text": "look first"}}}, {"text": "Let me look."},
               {"toolUse": {"toolUseId": "tooluse_9", "name": "web_search", "input": {"query": "parish register"}}}]}},
             "stopReason": "tool_use", "usage": {"inputTokens": 120, "outputTokens": 30, "totalTokens": 150}}"""), "anthropic.claude");
        JsonNode m = r.path("choices").get(0).path("message");
        assertEquals("Let me look.", m.path("content").asText());
        assertEquals("look first", m.path("reasoning_content").asText());
        assertEquals("tooluse_9", m.path("tool_calls").get(0).path("id").asText());
        assertEquals("{\"query\":\"parish register\"}", m.path("tool_calls").get(0).path("function").path("arguments").asText());
        assertEquals("tool_calls", r.path("choices").get(0).path("finish_reason").asText());
        assertEquals(120, r.path("usage").path("prompt_tokens").asLong());
        ObjectNode cut = Converse.response(J.readTree("{\"output\":{\"message\":{\"content\":[{\"text\":\"and then\"}]}},\"stopReason\":\"max_tokens\",\"usage\":{\"inputTokens\":5,\"outputTokens\":9}}"), "m");
        assertEquals("length", cut.path("choices").get(0).path("finish_reason").asText());
        assertEquals(14, cut.path("usage").path("total_tokens").asLong());
    }
}
