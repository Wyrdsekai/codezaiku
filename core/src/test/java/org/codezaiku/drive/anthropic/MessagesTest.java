package org.codezaiku.drive.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** A chat-completions request becomes a Claude Messages request, and Claude's answer becomes a chat completion. */
class MessagesTest {

    private static final ObjectMapper J = new ObjectMapper();
    private static final Messages.Takes OPUS = new Messages.Takes(true, Set.of("low", "medium", "high", "xhigh", "max"), 128_000);

    /** The whole request of an ordinary working turn, field for field: this is what the API is sent. */
    @Test
    void aWorkingTurnAsTheApiIsSentIt() throws Exception {
        JsonNode body = J.readTree("""
            {"model": "claude-opus-5-5", "max_tokens": 900, "temperature": 0.7, "tool_choice": "auto", "reasoning_effort": "high", "thinking_budget_tokens": 500,
             "tools": [{"type": "function", "function": {"name": "read_file", "description": "read a file", "parameters": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}}},
                       {"type": "function", "function": {"name": "task_done"}}],
             "messages": [
               {"role": "system", "content": "You are a coding familiar."},
               {"role": "user", "content": "TASK: fix the parser"},
               {"role": "assistant", "content": "", "reasoning_content": "I should read it first.", "tool_calls": [{"id": "toolu_1", "type": "function", "function": {"name": "read_file", "arguments": "{\\"path\\": \\"parser.py\\"}"}}]},
               {"role": "tool", "tool_call_id": "toolu_1", "content": "def parse(): pass"},
               {"role": "user", "content": "The check is failing."}]}""");
        JsonNode expected = J.readTree("""
            {"model": "claude-opus-5-5", "max_tokens": 900,
             "system": [{"type": "text", "text": "You are a coding familiar.", "cache_control": {"type": "ephemeral"}}],
             "messages": [
               {"role": "user", "content": [{"type": "text", "text": "TASK: fix the parser"}]},
               {"role": "assistant", "content": [{"type": "tool_use", "id": "toolu_1", "name": "read_file", "input": {"path": "parser.py"}}]},
               {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "toolu_1", "content": "def parse(): pass"},
                                            {"type": "text", "text": "The check is failing.", "cache_control": {"type": "ephemeral"}}]}],
             "tools": [{"name": "read_file", "description": "read a file", "input_schema": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}},
                       {"name": "task_done", "description": "task_done", "input_schema": {"type": "object"}, "cache_control": {"type": "ephemeral"}}],
             "tool_choice": {"type": "auto"},
             "thinking": {"type": "adaptive", "display": "summarized"},
             "output_config": {"effort": "high"},
             "stream": true}""");
        assertEquals(expected, Messages.request(body, OPUS), "no temperature, no thinking sent back, the three cache marks, effort as Claude's own setting");
    }

    @Test
    void aCallThatNeedsNoThinkingAsksForTheLeastEffortAndNoMoreOutputThanTheModelWrites() throws Exception {
        JsonNode body = J.readTree("""
            {"model": "m", "max_tokens": 500000, "reasoning_effort": "xhigh", "chat_template_kwargs": {"enable_thinking": false}, "messages": [{"role": "user", "content": "one word"}]}""");
        ObjectNode r = Messages.request(body, OPUS);
        assertEquals("low", r.path("output_config").path("effort").asText());
        assertEquals(128_000, r.path("max_tokens").asInt());
        assertFalse(r.has("tools") || r.has("tool_choice") || r.has("system"), r.toString());
    }

    @Test
    void whatTheModelDoesNotTakeIsNotSent() throws Exception {
        JsonNode body = J.readTree("{\"model\": \"m\", \"max_tokens\": 100, \"reasoning_effort\": \"xhigh\", \"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}");
        ObjectNode small = Messages.request(body, new Messages.Takes(false, Set.of(), 64_000));
        assertFalse(small.has("thinking") || small.has("output_config"), "a model without adaptive thinking or an effort setting: " + small);
        ObjectNode some = Messages.request(body, new Messages.Takes(true, Set.of("low", "medium", "high"), 64_000));
        assertFalse(some.has("output_config"), "a level the model does not know is left out: " + some);
        assertEquals("xhigh", Messages.request(body, Messages.Takes.UNKNOWN).path("output_config").path("effort").asText(), "nothing known of the model: sent as configured");
    }

    /** A history that was compacted does not always keep a tool call and its result together; Claude refuses a request where they are apart. */
    @Test
    void callsAndResultsThatLostEachOtherAreMadeWhole() throws Exception {
        JsonNode body = J.readTree("""
            {"model": "m", "max_tokens": 100, "tools": [{"type": "function", "function": {"name": "shell"}}],
             "messages": [
               {"role": "tool", "tool_call_id": "gone_1", "content": "output of a call that was summarised away"},
               {"role": "user", "content": "[summary of the work so far]"},
               {"role": "assistant", "content": "Two things.", "tool_calls": [{"id": "a:1", "type": "function", "function": {"name": "shell", "arguments": "{\\"command\\": \\"ls\\"}"}},
                                                                            {"id": "", "type": "function", "function": {"name": "shell", "arguments": ""}}]},
               {"role": "user", "content": "A note that arrived first."},
               {"role": "tool", "tool_call_id": "a:1", "content": ""},
               {"role": "assistant", "content": "", "tool_calls": [{"id": "b2", "type": "function", "function": {"name": "shell", "arguments": "{}"}}]}]}""");
        JsonNode m = Messages.request(body, OPUS).path("messages");
        assertEquals(5, m.size(), m.toString());
        assertEquals("text", m.get(0).path("content").get(0).path("type").asText(), "a result whose call is gone is told as text: " + m.get(0));
        assertTrue(m.get(0).path("content").get(0).path("text").asText().contains("summarised away"));
        JsonNode calls = m.get(1).path("content"), results = m.get(2).path("content");
        assertEquals("a_1", calls.get(1).path("id").asText(), "an id is written in the letters Claude accepts");
        assertEquals("call_1", calls.get(2).path("id").asText(), "a call without an id gets one");
        assertEquals("tool_result", results.get(0).path("type").asText(), "results come first: " + results);
        assertEquals("a_1", results.get(0).path("tool_use_id").asText());
        assertEquals("(nothing)", results.get(0).path("content").asText());
        assertEquals("call_1", results.get(1).path("tool_use_id").asText(), "a call without a result gets one saying so: " + results);
        assertEquals("A note that arrived first.", results.get(2).path("text").asText());
        JsonNode end = m.get(4);
        assertEquals("user", end.path("role").asText(), "the conversation ends on the user's side, with the last call answered: " + end);
        assertEquals("b2", end.path("content").get(0).path("tool_use_id").asText());
    }

    @Test
    void aConversationWithToolCallsButNoToolsInTheRequestTellsThemAsText() throws Exception {
        JsonNode body = J.readTree("""
            {"model": "m", "max_tokens": 100,
             "messages": [
               {"role": "system", "content": "Summarise."},
               {"role": "assistant", "content": "", "tool_calls": [{"id": "t1", "type": "function", "function": {"name": "shell", "arguments": "{\\"command\\": \\"ls\\"}"}}]},
               {"role": "tool", "tool_call_id": "t1", "content": "a.py"},
               {"role": "user", "content": [{"type": "text", "text": "Look:"}, {"type": "image_url", "image_url": {"url": "data:image/jpg;base64,QUJD"}}, {"type": "image_url", "image_url": {"url": "https://example.org/a.png"}}]}]}""");
        ObjectNode r = Messages.request(body, OPUS);
        String all = r.path("messages").toString();
        assertFalse(all.contains("tool_use") || all.contains("tool_result"), all);
        assertEquals("(continue)", r.path("messages").get(0).path("content").get(0).path("text").asText(), "Claude's side cannot speak first");
        assertTrue(all.contains("[called shell with {\\\"command\\\":\\\"ls\\\"}]") && all.contains("[the tool's result]\\na.py"), all);
        JsonNode last = r.path("messages").get(2).path("content");
        assertEquals("image/jpeg", last.get(2).path("source").path("media_type").asText());
        assertEquals("QUJD", last.get(2).path("source").path("data").asText());
        assertEquals("https://example.org/a.png", last.get(3).path("source").path("url").asText());
        assertTrue(last.get(3).has("cache_control"), "the mark is on the last block: " + last);
    }

    /**
     * The API reads a prompt from its cache only up to the first thing that changed. What the harness rebuilds every turn arrives
     * as system text at the end, and is told after the conversation and after the mark.
     */
    @Test
    void whatIsRebuiltEveryTurnIsToldAfterTheConversationAndAfterTheMark() throws Exception {
        String tools = "\"tools\": [{\"type\": \"function\", \"function\": {\"name\": \"shell\"}}]";
        String head = "{\"role\": \"system\", \"content\": \"The rules.\"}, {\"role\": \"user\", \"content\": \"TASK\"},"
                + "{\"role\": \"assistant\", \"content\": \"\", \"tool_calls\": [{\"id\": \"t1\", \"type\": \"function\", \"function\": {\"name\": \"shell\", \"arguments\": \"{}\"}}]},"
                + "{\"role\": \"tool\", \"tool_call_id\": \"t1\", \"content\": \"one file\"}";
        ObjectNode first = Messages.request(J.readTree("{\"model\": \"m\", \"max_tokens\": 100, " + tools + ", \"messages\": [" + head
                + ", {\"role\": \"system\", \"content\": \"FILES: a.py\"}, {\"role\": \"system\", \"content\": \"GOAL: fix it\"}]}"), OPUS);
        assertEquals(1, first.path("system").size(), "only the rules are the system prompt: " + first.path("system"));
        JsonNode last = first.path("messages").get(2).path("content");
        assertEquals(2, last.size(), last.toString());
        assertTrue(last.get(0).has("cache_control") && last.get(0).path("type").asText().equals("tool_result"), "the mark is on the last thing of the conversation: " + last);
        assertEquals("FILES: a.py\n\nGOAL: fix it", last.get(1).path("text").asText());
        assertFalse(last.get(1).has("cache_control"));

        // the next turn: one more call and result, and the files have changed
        ObjectNode second = Messages.request(J.readTree("{\"model\": \"m\", \"max_tokens\": 100, " + tools + ", \"messages\": [" + head
                + ", {\"role\": \"assistant\", \"content\": \"Now b.\", \"tool_calls\": [{\"id\": \"t2\", \"type\": \"function\", \"function\": {\"name\": \"shell\", \"arguments\": \"{}\"}}]},"
                + "{\"role\": \"tool\", \"tool_call_id\": \"t2\", \"content\": \"two files\"}, {\"role\": \"system\", \"content\": \"FILES: a.py b.py\"}]}"), OPUS);
        assertTrue(kept(second).startsWith(kept(first)), "what the first request marked is the start of the second, unchanged:\n" + kept(first) + "\n---\n" + kept(second));

        // an instruction given part-way through stays where it was given; a conversation of system text alone keeps it as the system prompt
        ObjectNode midway = Messages.request(J.readTree("{\"model\": \"m\", \"max_tokens\": 100, \"messages\": [{\"role\": \"user\", \"content\": \"a\"}, {\"role\": \"system\", \"content\": \"Be brief.\"}, {\"role\": \"assistant\", \"content\": \"b\"}, {\"role\": \"user\", \"content\": \"c\"}]}"), OPUS);
        assertFalse(midway.has("system"));
        assertEquals("Be brief.", midway.path("messages").get(0).path("content").get(1).path("text").asText());
        ObjectNode alone = Messages.request(J.readTree("{\"model\": \"m\", \"max_tokens\": 100, \"messages\": [{\"role\": \"system\", \"content\": \"Say hello.\"}]}"), OPUS);
        assertEquals("Say hello.", alone.path("system").get(0).path("text").asText());
        assertEquals("(continue)", alone.path("messages").get(0).path("content").get(0).path("text").asText());

        // Claude's side spoke last and the harness has something to tell: the mark stays on Claude's words
        ObjectNode spoke = Messages.request(J.readTree("{\"model\": \"m\", \"max_tokens\": 100, \"messages\": [{\"role\": \"user\", \"content\": \"a\"}, {\"role\": \"assistant\", \"content\": \"b\"}, {\"role\": \"system\", \"content\": \"NOW\"}]}"), OPUS);
        assertTrue(spoke.path("messages").get(1).path("content").get(0).has("cache_control"), spoke.toString());
        assertEquals("NOW", spoke.path("messages").get(2).path("content").get(0).path("text").asText());
        assertEquals(1, spoke.path("messages").get(2).path("content").size());
    }

    /** What a request asks the API to keep, as the API compares it: the tools, the system prompt, and every block up to the last mark, without the marks. */
    static String kept(JsonNode request) {
        StringBuilder b = new StringBuilder();
        b.append(strip(request.path("tools"))).append('\n').append(strip(request.path("system"))).append('\n');
        StringBuilder upToMark = new StringBuilder(), pending = new StringBuilder();
        for (JsonNode m : request.path("messages")) for (JsonNode block : m.path("content")) {
            pending.append(m.path("role").asText()).append(' ').append(strip(block)).append('\n');
            if (block.has("cache_control")) { upToMark.append(pending); pending.setLength(0); }
        }
        return b.append(upToMark).toString();
    }

    private static String strip(JsonNode n) {
        JsonNode copy = n.deepCopy();
        if (copy.isObject()) ((ObjectNode) copy).remove("cache_control");
        for (JsonNode child : copy) if (child.isObject()) ((ObjectNode) child).remove("cache_control");
        return copy.toString();
    }

    @Test
    void claudesAnswerAsAChatCompletion() throws Exception {
        JsonNode claude = J.readTree("""
            {"id": "msg_1", "role": "assistant", "stop_reason": "tool_use",
             "content": [{"type": "thinking", "thinking": "The parser first.", "signature": "abc"},
                         {"type": "text", "text": "Reading the parser."},
                         {"type": "tool_use", "id": "toolu_9", "name": "read_file", "input": {"path": "parser.py"}}],
             "usage": {"input_tokens": 40, "cache_read_input_tokens": 9000, "cache_creation_input_tokens": 300, "output_tokens": 55}}""");
        ObjectNode c = Messages.response(claude, "claude-opus-5-5");
        JsonNode msg = c.path("choices").get(0).path("message");
        assertEquals("Reading the parser.", msg.path("content").asText());
        assertEquals("The parser first.", msg.path("reasoning_content").asText());
        assertEquals("toolu_9", msg.path("tool_calls").get(0).path("id").asText());
        assertEquals("{\"path\":\"parser.py\"}", msg.path("tool_calls").get(0).path("function").path("arguments").asText());
        assertEquals("tool_calls", c.path("choices").get(0).path("finish_reason").asText());
        assertEquals(9340, c.path("usage").path("prompt_tokens").asInt(), "the prompt is what was read from the cache, written to it, and neither");
        assertEquals(9395, c.path("usage").path("total_tokens").asInt());
        assertEquals(9000, c.path("usage").path("cache_read_input_tokens").asInt());
        assertEquals(300, c.path("usage").path("cache_creation_input_tokens").asInt());

        assertEquals("length", Messages.response(J.readTree("{\"stop_reason\": \"max_tokens\", \"content\": []}"), "m").path("choices").get(0).path("finish_reason").asText());
        ObjectNode declined = Messages.response(J.readTree("{\"stop_reason\": \"refusal\", \"content\": []}"), "m");
        assertEquals("content_filter", declined.path("choices").get(0).path("finish_reason").asText());
        assertTrue(declined.path("choices").get(0).path("message").path("content").asText().contains("declined"), "a refusal is said, not left as an empty answer");
    }

    @Test
    void aReplyPutTogetherFromTheEventsOfAStream() throws Exception {
        Messages.Reply reply = new Messages.Reply();
        StringBuilder shown = new StringBuilder(), thought = new StringBuilder();
        for (String data : new String[]{
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":12,\"cache_read_input_tokens\":5000,\"cache_creation_input_tokens\":0,\"output_tokens\":1}}}",
                "{\"type\":\"ping\"}",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"List first.\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"zzz\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Looking \"}}",
                "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"around.\"}}",
                "{\"type\":\"content_block_stop\",\"index\":1}",
                "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_3\",\"name\":\"shell\",\"input\":{}}}",
                "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"comm\"}}",
                "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"and\\\": \\\"ls\\\"}\"}}",
                "{\"type\":\"content_block_stop\",\"index\":2}",
                "{\"type\":\"content_block_start\",\"index\":3,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_4\",\"name\":\"task_done\",\"input\":{}}}",
                "{\"type\":\"content_block_stop\",\"index\":3}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":77}}",
                "{\"type\":\"message_stop\"}"}) reply.take(data, shown::append, thought::append);
        assertTrue(reply.started() && reply.complete() && reply.error() == null);
        assertEquals("Looking around.", shown.toString());
        assertEquals("List first.", thought.toString());
        ObjectNode c = Messages.response(reply.message(), "m");
        JsonNode msg = c.path("choices").get(0).path("message");
        assertEquals("Looking around.", msg.path("content").asText());
        assertEquals("List first.", msg.path("reasoning_content").asText());
        assertEquals("{\"command\": \"ls\"}", msg.path("tool_calls").get(0).path("function").path("arguments").asText(), "the input as the model wrote it");
        assertEquals("{}", msg.path("tool_calls").get(1).path("function").path("arguments").asText(), "a call without input");
        assertEquals(5012, c.path("usage").path("prompt_tokens").asInt());
        assertEquals(77, c.path("usage").path("completion_tokens").asInt(), "the count at the end of the reply, not the one at its start");
    }

    @Test
    void aReplyCutOffInsideAToolCallKeepsWhatArrivedAndAnErrorInTheStreamIsKept() {
        Messages.Reply cut = new Messages.Reply();
        cut.take("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"write_file\",\"input\":{}}}", null, null);
        cut.take("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\": \\\"a.py\\\", \\\"content\\\": \\\"impo\"}}", null, null);
        cut.take("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"},\"usage\":{\"output_tokens\":900}}", null, null);
        cut.take("{\"type\":\"message_stop\"}", null, null);
        ObjectNode c = Messages.response(cut.message(), "m");
        assertEquals("length", c.path("choices").get(0).path("finish_reason").asText());
        assertEquals("{\"path\": \"a.py\", \"content\": \"impo", c.path("choices").get(0).path("message").path("tool_calls").get(0).path("function").path("arguments").asText());

        Messages.Reply failed = new Messages.Reply();
        failed.take("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}", null, null);
        failed.take("not json", null, null);
        assertEquals("overloaded_error", failed.errorType());
        assertEquals("Overloaded", failed.error());
        assertFalse(failed.started() || failed.complete());
    }
}
