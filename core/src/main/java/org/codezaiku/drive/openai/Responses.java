package org.codezaiku.drive.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Between the chat format the rest of the program speaks (chat completions) and OpenAI's Responses API, the way to its current models.
 * Why not the chat completions OpenAI also serves: the current models keep their reasoning from one tool call to the next, which that
 * format has no field for, so it refuses function tools from them (400 "use /v1/responses", 2026-10-15). Here the conversation goes as
 * input items — messages, function calls, their outputs — and the reasoning items that came back with a tool call go in again before it,
 * carried on the assistant message as {@code reasoning_items}, so nothing above the model client knows which format it talked to.
 * Sampling fields are left out: the current models refuse any temperature or top_p but their own.
 */
public final class Responses {

    private Responses() { }

    private static final ObjectMapper J = new ObjectMapper();
    /** The field on an assistant message that keeps the reasoning items the API returned with it, for the next turn. */
    public static final String REASONING = "reasoning_items";
    /** A decision asked for without thinking still has to hold the model's least thinking and the answer. */
    static final int QUICK_FLOOR = 4096;

    /** {@code body}: a chat-completions request (messages, tools, tool_choice, max_tokens). {@code effort}: none/minimal/low/medium/high/xhigh/max, or blank for the model's own. */
    public static ObjectNode request(JsonNode body, String model, String effort) {
        ObjectNode out = J.createObjectNode();
        out.put("model", model);
        out.put("store", false);                                   // stateless: the conversation is ours, the reasoning comes back encrypted
        out.putArray("include").add("reasoning.encrypted_content");
        // the system text: every system or developer message, in order, as the instructions
        StringBuilder system = new StringBuilder();
        for (JsonNode m : body.path("messages")) {
            String role = m.path("role").asText("user");
            if (role.equals("system") || role.equals("developer")) { String t = text(m.path("content")); if (!t.isBlank()) { if (system.length() > 0) system.append("\n\n"); system.append(t); } }
        }
        if (system.length() > 0) out.put("instructions", system.toString());
        // the conversation as input items
        ArrayNode input = out.putArray("input");
        for (JsonNode m : body.path("messages")) {
            String role = m.path("role").asText("user");
            if (role.equals("system") || role.equals("developer")) continue;
            if (role.equals("tool")) {
                ObjectNode r = input.addObject();
                r.put("type", "function_call_output");
                r.put("call_id", m.path("tool_call_id").asText(""));
                String t = text(m.path("content"));
                r.put("output", t.isBlank() ? "(nothing)" : t);
                continue;
            }
            if (role.equals("assistant")) {
                for (JsonNode item : m.path(REASONING)) if (item.isObject()) input.add(item.deepCopy());   // what it thought before these calls, back as it came
                String t = text(m.path("content"));
                if (!t.isBlank()) {
                    ObjectNode msg = input.addObject(); msg.put("type", "message"); msg.put("role", "assistant");
                    ObjectNode part = msg.putArray("content").addObject(); part.put("type", "output_text"); part.put("text", t);
                }
                for (JsonNode call : m.path("tool_calls")) {
                    ObjectNode fc = input.addObject();
                    fc.put("type", "function_call");
                    fc.put("call_id", call.path("id").asText(""));
                    fc.put("name", call.path("function").path("name").asText(""));
                    JsonNode args = call.path("function").path("arguments");
                    fc.put("arguments", args.isTextual() ? args.asText() : args.isMissingNode() ? "{}" : args.toString());
                }
                continue;
            }
            ObjectNode msg = input.addObject(); msg.put("type", "message"); msg.put("role", "user");
            ArrayNode parts = msg.putArray("content");
            content(m.path("content"), parts);
            if (parts.isEmpty()) { ObjectNode t = parts.addObject(); t.put("type", "input_text"); t.put("text", "(nothing)"); }
        }
        if (input.isEmpty()) { ObjectNode msg = input.addObject(); msg.put("type", "message"); msg.put("role", "user"); ObjectNode t = msg.putArray("content").addObject(); t.put("type", "input_text"); t.put("text", "(continue)"); }
        // the tools
        ArrayNode tools = J.createArrayNode();
        for (JsonNode t : body.path("tools")) {
            JsonNode f = t.has("function") ? t.path("function") : t;
            ObjectNode tool = tools.addObject();
            tool.put("type", "function");
            tool.put("name", f.path("name").asText(""));
            if (!f.path("description").asText("").isBlank()) tool.put("description", f.path("description").asText());
            JsonNode params = f.path("parameters");
            if (params.isObject()) tool.set("parameters", params.deepCopy()); else { ObjectNode s = tool.putObject("parameters"); s.put("type", "object"); s.putObject("properties"); }
            tool.put("strict", false);
        }
        if (!tools.isEmpty()) {
            out.set("tools", tools);
            JsonNode tc = body.path("tool_choice");
            if (tc.isTextual()) out.put("tool_choice", tc.asText());
            else if (tc.isObject()) { ObjectNode named = out.putObject("tool_choice"); named.put("type", "function"); named.put("name", tc.path("function").path("name").asText(tc.path("name").asText(""))); }
        }
        // the output budget: reasoning, the visible answer and formatting all count against it
        int budget = body.path("max_tokens").isNumber() ? body.path("max_tokens").asInt() : body.path("max_completion_tokens").asInt(4096);
        boolean quick = body.path("chat_template_kwargs").path("enable_thinking").isBoolean() && !body.path("chat_template_kwargs").path("enable_thinking").asBoolean();
        if (quick && budget < QUICK_FLOOR) budget = QUICK_FLOOR;
        out.put("max_output_tokens", budget);
        if (effort != null && !effort.isBlank()) out.putObject("reasoning").put("effort", effort.strip().toLowerCase(Locale.ROOT));
        else if (quick) out.putObject("reasoning").put("effort", "low");   // a decision asked for without thinking: the least these models do
        return out;
    }

    /** A Responses reply as a chat completion: the text, the tool calls, the reasoning items kept for the next turn, how it stopped, and the usage with the cache's share. */
    public static ObjectNode response(JsonNode reply, String model) {
        ObjectNode out = J.createObjectNode();
        out.put("id", reply.path("id").asText(""));
        out.put("object", "chat.completion");
        out.put("model", reply.path("model").asText(model));
        ObjectNode choice = out.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        StringBuilder text = new StringBuilder();
        String refusal = null;
        ArrayNode calls = J.createArrayNode(), reasoning = J.createArrayNode();
        for (JsonNode item : reply.path("output")) {
            String type = item.path("type").asText("");
            if (type.equals("message")) {
                for (JsonNode part : item.path("content")) {
                    String pt = part.path("type").asText("");
                    if (pt.equals("output_text")) text.append(part.path("text").asText(""));
                    else if (pt.equals("refusal")) refusal = part.path("refusal").asText("The model declined this request.");
                }
            } else if (type.equals("function_call")) {
                ObjectNode call = calls.addObject();
                call.put("id", item.path("call_id").asText(item.path("id").asText("")));
                call.put("type", "function");
                ObjectNode f = call.putObject("function");
                f.put("name", item.path("name").asText(""));
                JsonNode args = item.path("arguments");
                f.put("arguments", args.isTextual() ? (args.asText().isBlank() ? "{}" : args.asText()) : args.isMissingNode() ? "{}" : args.toString());
            } else if (type.equals("reasoning")) reasoning.add(item.deepCopy());
        }
        if (text.length() > 0 || calls.isEmpty()) message.put("content", text.toString()); else message.putNull("content");
        if (!calls.isEmpty()) message.set("tool_calls", calls);
        if (!reasoning.isEmpty()) message.set(REASONING, reasoning);
        String status = reply.path("status").asText("completed"), why = reply.path("incomplete_details").path("reason").asText("");
        String finish;
        if (refusal != null) finish = "refusal";
        else if (!calls.isEmpty()) finish = "tool_calls";
        else if (status.equals("incomplete") && why.equals("max_output_tokens")) finish = "length";
        else if (status.equals("incomplete") && why.equals("content_filter")) { finish = "refusal"; refusal = "The content filter stopped this answer."; }
        else finish = "stop";
        choice.put("finish_reason", finish);
        if (refusal != null) message.put("refusal", refusal);
        JsonNode u = reply.path("usage");
        ObjectNode usage = out.putObject("usage");
        long in = u.path("input_tokens").asLong(0), outTokens = u.path("output_tokens").asLong(0), cached = u.path("input_tokens_details").path("cached_tokens").asLong(0);
        usage.put("prompt_tokens", in);
        usage.put("completion_tokens", outTokens);
        usage.put("total_tokens", u.path("total_tokens").asLong(in + outTokens));
        usage.putObject("prompt_tokens_details").put("cached_tokens", cached);
        usage.putObject("completion_tokens_details").put("reasoning_tokens", u.path("output_tokens_details").path("reasoning_tokens").asLong(0));
        return out;
    }

    private static void content(JsonNode content, ArrayNode parts) {
        if (content.isTextual()) { if (!content.asText().isBlank()) { ObjectNode t = parts.addObject(); t.put("type", "input_text"); t.put("text", content.asText()); } return; }
        for (JsonNode part : content) {
            String type = part.path("type").asText("text");
            if (type.equals("text")) { if (!part.path("text").asText("").isBlank()) { ObjectNode t = parts.addObject(); t.put("type", "input_text"); t.put("text", part.path("text").asText()); } }
            else if (type.equals("image_url")) { ObjectNode img = parts.addObject(); img.put("type", "input_image"); img.put("image_url", part.path("image_url").path("url").asText("")); }
        }
    }

    private static String text(JsonNode content) {
        if (content.isTextual()) return content.asText();
        List<String> parts = new ArrayList<>();
        for (JsonNode p : content) if (p.path("type").asText("text").equals("text")) parts.add(p.path("text").asText(""));
        return String.join("\n", parts);
    }
}
