package org.codezaiku.drive.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * Between the chat format the rest of the program speaks (OpenAI's chat completions) and the Claude API's own Messages format.
 * A request is turned into a Messages request, and Claude's answer back into a chat completion, so that nothing above the model
 * client knows which of the two it talked to.
 *
 * <p>The Claude API also has an OpenAI-style endpoint, and CodeZaiku used it until 0.3.12. That endpoint ignores the effort
 * setting, has no prompt caching and returns no thinking (checked 2026-10-02), so a long run paid full price for the same
 * prompt on every turn. This is the API's own format, which has all three.
 */
public final class Messages {

    private Messages() { }

    private static final ObjectMapper J = new ObjectMapper();

    /**
     * What the model takes, as the API's own model listing says it: whether it thinks adaptively, which effort levels it knows
     * ({@code null}: not known, so the configured level is sent as it is), and the most tokens it writes in one reply (0: not known).
     */
    public record Takes(boolean adaptiveThinking, Set<String> efforts, int mostOutput) {
        public static final Takes UNKNOWN = new Takes(true, null, 0);
    }

    private static final class Turn {
        final String role; ArrayNode blocks = J.createArrayNode();
        Turn(String role) { this.role = role; }
    }

    /**
     * {@code body}: a chat-completions request (model, messages, tools, tool_choice, max_tokens, reasoning_effort).
     *
     * <p>What is left out on purpose. Sampling settings: the current Claude models refuse a temperature. Earlier thinking: Claude
     * accepts its own thinking back only in a conversation whose earlier part is unchanged, byte for byte, and CodeZaiku rebuilds
     * its system prompt every turn and compacts its history, so a thinking block sent back would be refused with a 400 on accounts
     * opened since 2026-08-31. Each reply is thought out from the conversation as it stands.
     */
    public static ObjectNode request(JsonNode body, Takes takes) {
        ObjectNode out = J.createObjectNode();
        out.put("model", body.path("model").asText(""));
        int most = body.path("max_tokens").asInt(body.path("max_completion_tokens").asInt(4096));
        out.put("max_tokens", takes.mostOutput() > 0 ? Math.min(most, takes.mostOutput()) : most);

        ArrayNode tools = J.createArrayNode();
        for (JsonNode t : body.path("tools")) {
            JsonNode f = t.path("function");
            if (f.path("name").asText("").isBlank()) continue;
            ObjectNode tool = tools.addObject();
            tool.put("name", f.path("name").asText());
            String d = f.path("description").asText(""); tool.put("description", d.isBlank() ? f.path("name").asText() : d);
            tool.set("input_schema", f.path("parameters").isObject() ? f.path("parameters") : J.createObjectNode().put("type", "object"));
        }
        boolean tooled = !tools.isEmpty();

        ArrayNode system = J.createArrayNode();
        List<Turn> turns = new ArrayList<>();
        int unnamed = 0;
        // System text at the very end is what the harness rebuilds every turn. It is told after the conversation and after the
        // cache mark, so that the conversation before it reads the same to the API from one request to the next.
        JsonNode all = body.path("messages");
        int end = all.size(), lead = 0;
        while (lead < end && isSystem(all.get(lead))) lead++;
        StringBuilder now = new StringBuilder();
        while (end > lead && isSystem(all.get(end - 1))) { String t = text(all.get(--end).path("content")).strip(); if (!t.isEmpty()) now.insert(0, now.length() > 0 ? t + "\n\n" : t); }
        for (int at = 0; at < end; at++) {
            JsonNode m = all.get(at);
            String role = m.path("role").asText("user");
            if (isSystem(m)) {
                String t = text(m.path("content"));
                if (t.isBlank()) continue;
                if (turns.isEmpty()) { system.addObject().put("type", "text").put("text", t); continue; }
                role = "user";   // an instruction given part-way through stays where it was given
            }
            ArrayNode blocks = J.createArrayNode();
            if (role.equals("tool")) {
                String t = text(m.path("content"));
                // Claude has no tool role: a tool's result is something the user side says
                if (tooled) blocks.addObject().put("type", "tool_result").put("tool_use_id", id(m.path("tool_call_id").asText(""))).put("content", t.isBlank() ? "(nothing)" : t);
                else blocks.addObject().put("type", "text").put("text", "[the tool's result]\n" + (t.isBlank() ? "(nothing)" : t));
                role = "user";
            } else {
                content(m.path("content"), blocks);
                for (JsonNode call : role.equals("assistant") ? m.path("tool_calls") : J.createArrayNode()) {
                    String name = call.path("function").path("name").asText("");
                    JsonNode input = object(call.path("function").path("arguments"));
                    if (!tooled) { blocks.addObject().put("type", "text").put("text", "[called " + name + " with " + input + "]"); continue; }
                    String id = id(call.path("id").asText(""));
                    ObjectNode use = blocks.addObject();
                    use.put("type", "tool_use").put("id", id.isEmpty() ? "call_" + (++unnamed) : id).put("name", name);
                    use.set("input", input);
                }
                if (!role.equals("assistant")) role = "user";
            }
            // the two sides take turns: what one side says in several messages becomes one message
            Turn last = turns.isEmpty() ? null : turns.get(turns.size() - 1);
            if (last == null || !last.role.equals(role)) turns.add(last = new Turn(role));
            last.blocks.addAll(blocks);
        }
        if (turns.isEmpty() || turns.get(0).role.equals("assistant")) { Turn first = new Turn("user"); first.blocks.addObject().put("type", "text").put("text", "(continue)"); turns.add(0, first); }
        // Claude writes the next turn; a conversation that ends on Claude's own turn has nothing to answer
        if (turns.get(turns.size() - 1).role.equals("assistant")) turns.add(new Turn("user"));
        for (int i = 0; i < turns.size(); i++) if (turns.get(i).role.equals("user")) pair(turns.get(i), i == 0 ? null : turns.get(i - 1));

        // What repeats from one request to the next is marked, and the API keeps it: the tools, the system prompt, and the
        // conversation up to here. The next request reads those at a fraction of the price where they are unchanged.
        if (tooled) mark(tools.get(tools.size() - 1));
        if (!system.isEmpty()) mark(system.get(system.size() - 1));
        Turn closing = turns.get(turns.size() - 1);
        if (closing.blocks.isEmpty() && now.length() == 0) closing.blocks.addObject().put("type", "text").put("text", "(continue)");
        for (int i = turns.size() - 1; i >= 0; i--) if (!turns.get(i).blocks.isEmpty()) { mark(turns.get(i).blocks.get(turns.get(i).blocks.size() - 1)); break; }
        if (now.length() > 0) closing.blocks.addObject().put("type", "text").put("text", now.toString());

        ArrayNode messages = J.createArrayNode();
        for (Turn t : turns) {
            if (t.blocks.isEmpty()) t.blocks.addObject().put("type", "text").put("text", "(nothing)");
            ObjectNode msg = messages.addObject(); msg.put("role", t.role); msg.set("content", t.blocks);
        }

        if (!system.isEmpty()) out.set("system", system);
        out.set("messages", messages);
        if (tooled) {
            out.set("tools", tools);
            JsonNode choice = body.path("tool_choice");
            ObjectNode tc = out.putObject("tool_choice");
            if (choice.isObject() && !choice.path("function").path("name").asText("").isBlank()) tc.put("type", "tool").put("name", choice.path("function").path("name").asText());
            else tc.put("type", switch (choice.asText("auto")) { case "none" -> "none"; case "required" -> "any"; default -> "auto"; });
        }
        if (takes.adaptiveThinking()) out.putObject("thinking").put("type", "adaptive").put("display", "summarized");
        // A call marked as needing no thinking (a classification, a summary) is asked for with the least effort: the current
        // models cannot be told not to think at all.
        boolean quick = body.path("chat_template_kwargs").path("enable_thinking").isBoolean() && !body.path("chat_template_kwargs").path("enable_thinking").asBoolean();
        String effort = quick ? "low" : body.path("reasoning_effort").asText("");
        if (!effort.isBlank() && (takes.efforts() == null || takes.efforts().contains(effort))) out.putObject("output_config").put("effort", effort);
        out.put("stream", true);
        return out;
    }

    /**
     * Claude wants every tool call answered in the next message, results first, and every result to answer a call in the message
     * before. A history that was compacted or trimmed does not always keep the two together, so here a result whose call is gone
     * is told as text, and a call whose result is gone gets one saying so.
     */
    private static void pair(Turn user, Turn before) {
        Set<String> waiting = new LinkedHashSet<>();
        if (before != null) for (JsonNode b : before.blocks) if (b.path("type").asText().equals("tool_use")) waiting.add(b.path("id").asText());
        ArrayNode results = J.createArrayNode(), rest = J.createArrayNode();
        for (JsonNode b : user.blocks) {
            if (!b.path("type").asText().equals("tool_result")) { rest.add(b); continue; }
            String id = b.path("tool_use_id").asText("");
            if (id.isEmpty() && !waiting.isEmpty()) { id = waiting.iterator().next(); ((ObjectNode) b).put("tool_use_id", id); }
            if (waiting.remove(id)) results.add(b);
            else rest.addObject().put("type", "text").put("text", "[the result of an earlier tool call]\n" + b.path("content").asText(""));
        }
        for (String id : waiting) results.addObject().put("type", "tool_result").put("tool_use_id", id).put("content", "(no result was recorded for this call)");
        user.blocks = results.addAll(rest);
    }

    private static boolean isSystem(JsonNode m) { String r = m.path("role").asText(""); return r.equals("system") || r.equals("developer"); }

    private static void mark(JsonNode block) { ((ObjectNode) block).putObject("cache_control").put("type", "ephemeral"); }

    /**
     * A reply as it arrives, one event at a time, put together into the message a call without streaming would have answered.
     * The reply is always streamed: a long one takes minutes, and a connection that carries nothing for that long gets dropped
     * on the way.
     */
    public static final class Reply {
        private final ObjectNode message = J.createObjectNode();
        private final TreeMap<Integer, ObjectNode> blocks = new TreeMap<>();
        private final TreeMap<Integer, StringBuilder> inputs = new TreeMap<>();
        private boolean started, complete;
        private String error, errorType;

        /** One event's data. Text and thinking are handed on as they arrive, when somebody is watching. */
        public void take(String data, Consumer<String> onText, Consumer<String> onThinking) {
            JsonNode e;
            try { e = J.readTree(data); } catch (Exception x) { return; }
            int at = e.path("index").asInt(0);
            switch (e.path("type").asText("")) {
                case "message_start" -> { if (e.path("message").isObject()) { message.setAll((ObjectNode) e.path("message").deepCopy()); message.remove("content"); } }
                case "content_block_start" -> { started = true; blocks.put(at, (ObjectNode) e.path("content_block").deepCopy()); }
                case "content_block_delta" -> {
                    ObjectNode block = blocks.get(at);
                    if (block == null) return;
                    JsonNode d = e.path("delta");
                    switch (d.path("type").asText("")) {
                        case "text_delta" -> { String s = d.path("text").asText(""); block.put("text", block.path("text").asText("") + s); if (onText != null && !s.isEmpty()) onText.accept(s); }
                        case "thinking_delta" -> { String s = d.path("thinking").asText(""); block.put("thinking", block.path("thinking").asText("") + s); if (onThinking != null && !s.isEmpty()) onThinking.accept(s); }
                        case "input_json_delta" -> inputs.computeIfAbsent(at, k -> new StringBuilder()).append(d.path("partial_json").asText(""));
                        default -> { }   // a thinking block's signature: only needed to send the thinking back, which is not done
                    }
                }
                case "content_block_stop" -> {
                    ObjectNode block = blocks.get(at);
                    StringBuilder written = inputs.get(at);
                    // a tool call's input arrives as JSON in pieces; it is kept as written, so that a reply cut off in the middle of one is seen as that
                    if (block != null && written != null && written.length() > 0) block.put("input_json", written.toString());
                }
                case "message_delta" -> {
                    if (e.path("delta").hasNonNull("stop_reason")) message.put("stop_reason", e.path("delta").path("stop_reason").asText());
                    if (e.path("usage").isObject()) { if (!message.path("usage").isObject()) message.putObject("usage"); ((ObjectNode) message.path("usage")).setAll((ObjectNode) e.path("usage")); }
                }
                case "message_stop" -> complete = true;
                case "error" -> { errorType = e.path("error").path("type").asText(""); error = e.path("error").path("message").asText(errorType); }
                default -> { }   // ping
            }
        }

        public boolean started() { return started; }
        public boolean complete() { return complete; }
        public String error() { return error; }
        public String errorType() { return errorType; }

        public ObjectNode message() {
            ObjectNode m = message.deepCopy();
            ArrayNode content = m.putArray("content");
            // a block still open when the reply ended (cut off at the token limit) keeps what had arrived of its input
            inputs.forEach((at, written) -> { ObjectNode b = blocks.get(at); if (b != null && !b.has("input_json") && written.length() > 0) b.put("input_json", written.toString()); });
            blocks.values().forEach(content::add);
            return m;
        }
    }

    /** Claude's answer as a chat completion. */
    public static ObjectNode response(JsonNode claude, String model) {
        StringBuilder text = new StringBuilder(), reasoning = new StringBuilder();
        ArrayNode calls = J.createArrayNode();
        for (JsonNode block : claude.path("content")) {
            switch (block.path("type").asText("")) {
                case "text" -> text.append(block.path("text").asText(""));
                case "thinking" -> reasoning.append(block.path("thinking").asText(""));
                case "tool_use" -> {
                    ObjectNode call = calls.addObject();
                    call.put("id", block.path("id").asText("")); call.put("type", "function");
                    ObjectNode f = call.putObject("function");
                    f.put("name", block.path("name").asText(""));
                    String written = block.path("input_json").asText("");
                    f.put("arguments", !written.isBlank() ? written : block.path("input").isObject() ? block.path("input").toString() : "{}");
                }
                default -> { }
            }
        }
        String stop = claude.path("stop_reason").asText("");
        // a reply Claude declined to give has no text of its own; said in words, so that it is not taken for an empty answer
        if (stop.equals("refusal") && text.length() == 0 && calls.isEmpty()) text.append("The model declined to answer this request.");
        ObjectNode out = J.createObjectNode();
        out.put("object", "chat.completion"); out.put("model", model);
        ObjectNode choice = out.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        if (text.length() > 0 || calls.isEmpty()) message.put("content", text.toString()); else message.putNull("content");
        if (reasoning.length() > 0) message.put("reasoning_content", reasoning.toString());
        if (!calls.isEmpty()) message.set("tool_calls", calls);
        choice.put("finish_reason", switch (stop) {
            case "tool_use" -> "tool_calls";
            case "max_tokens", "model_context_window_exceeded" -> "length";
            case "refusal" -> "content_filter";
            default -> "stop";
        });
        // Claude counts the prompt in three parts: read from the cache, written to it, and neither. The prompt's size is their sum.
        JsonNode u = claude.path("usage");
        long read = u.path("cache_read_input_tokens").asLong(0), written = u.path("cache_creation_input_tokens").asLong(0);
        long prompt = u.path("input_tokens").asLong(0) + read + written, completion = u.path("output_tokens").asLong(0);
        ObjectNode usage = out.putObject("usage");
        usage.put("prompt_tokens", prompt); usage.put("completion_tokens", completion); usage.put("total_tokens", prompt + completion);
        usage.put("cache_read_input_tokens", read); usage.put("cache_creation_input_tokens", written);
        return out;
    }

    /** A message's content as Claude blocks: text, and a picture given as a data: address or a web address. */
    private static void content(JsonNode content, ArrayNode blocks) {
        if (content.isTextual()) { if (!content.asText().isBlank()) blocks.addObject().put("type", "text").put("text", content.asText()); return; }
        for (JsonNode part : content) {
            String type = part.path("type").asText("");
            if (type.equals("text")) { if (!part.path("text").asText("").isBlank()) blocks.addObject().put("type", "text").put("text", part.path("text").asText()); }
            else if (type.equals("image_url")) {
                String url = part.path("image_url").path("url").asText("");
                Matcher m = Pattern.compile("^data:(image/(?:png|jpeg|gif|webp));base64,(.+)$", Pattern.DOTALL).matcher(url.replaceFirst("^data:image/jpg;", "data:image/jpeg;"));
                ObjectNode source = blocks.addObject().put("type", "image").putObject("source");
                if (m.matches()) source.put("type", "base64").put("media_type", m.group(1)).put("data", m.group(2).replaceAll("\\s+", ""));
                else source.put("type", "url").put("url", url);
            }
        }
    }

    private static String text(JsonNode content) {
        if (content.isTextual()) return content.asText();
        StringBuilder b = new StringBuilder();
        for (JsonNode part : content) if (part.path("type").asText("").equals("text")) b.append(part.path("text").asText(""));
        return b.toString();
    }

    /** A tool call's id as Claude accepts one: letters, digits, _ and -. */
    private static String id(String id) { return id.strip().replaceAll("[^a-zA-Z0-9_-]", "_"); }

    /** A tool call's arguments arrive as JSON written in a string; Claude wants the object itself. */
    private static JsonNode object(JsonNode arguments) {
        if (arguments.isObject()) return arguments;
        String s = arguments.asText("").strip();
        if (s.isEmpty()) return J.createObjectNode();
        try { JsonNode n = J.readTree(s); return n.isObject() ? n : J.createObjectNode().set("value", n); }
        catch (Exception e) { return J.createObjectNode().put("_unparsed", s); }
    }
}
