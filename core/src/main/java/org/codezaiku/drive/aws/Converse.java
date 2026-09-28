package org.codezaiku.drive.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * Between the chat format the rest of the program speaks (OpenAI's chat completions) and Amazon Bedrock's Converse API, which
 * is the one interface Bedrock gives to nearly every chat model it hosts: Claude, Llama, Nova, Qwen, Mistral and the others.
 * A request is turned into a Converse request, and Converse's answer back into a chat completion, so that nothing above the
 * model client knows which of the two it talked to.
 */
public final class Converse {

    private Converse() { }

    private static final ObjectMapper J = new ObjectMapper();

    /** {@code body}: a chat-completions request (messages, tools, tool_choice, max_tokens, temperature, top_p, stop). The model id goes in the address, not here. */
    public static ObjectNode request(JsonNode body) {
        ObjectNode out = J.createObjectNode();
        ArrayNode system = J.createArrayNode(), messages = J.createArrayNode();
        boolean usesTools = false;
        for (JsonNode m : body.path("messages")) {
            String role = m.path("role").asText("user");
            if (role.equals("system") || role.equals("developer")) { String t = text(m.path("content")); if (!t.isBlank()) system.addObject().put("text", t); continue; }
            ArrayNode blocks = J.createArrayNode();
            if (role.equals("tool")) {
                usesTools = true;
                ObjectNode result = blocks.addObject().putObject("toolResult");
                result.put("toolUseId", m.path("tool_call_id").asText(""));
                String t = text(m.path("content"));
                result.putArray("content").addObject().put("text", t.isBlank() ? "(nothing)" : t);
                role = "user";   // Converse has no tool role: a tool's result is something the user side says
            } else {
                content(m.path("content"), blocks);
                for (JsonNode call : m.path("tool_calls")) {
                    usesTools = true;
                    ObjectNode use = blocks.addObject().putObject("toolUse");
                    use.put("toolUseId", call.path("id").asText(""));
                    use.put("name", call.path("function").path("name").asText(""));
                    use.set("input", object(call.path("function").path("arguments")));
                }
                if (!role.equals("assistant")) role = "user";
            }
            if (blocks.isEmpty()) blocks.addObject().put("text", "(nothing)");   // Converse refuses a message with no content
            // Converse wants the two sides to take turns: what one side says in several messages becomes one message
            JsonNode last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
            if (last != null && last.path("role").asText().equals(role)) ((ArrayNode) last.path("content")).addAll(blocks);
            else { ObjectNode msg = messages.addObject(); msg.put("role", role); msg.set("content", blocks); }
        }
        if (!messages.isEmpty() && messages.get(0).path("role").asText().equals("assistant")) { ObjectNode first = J.createObjectNode(); first.put("role", "user"); first.putArray("content").addObject().put("text", "(continue)"); messages.insert(0, first); }
        if (!system.isEmpty()) out.set("system", system);
        out.set("messages", messages);
        ObjectNode inference = J.createObjectNode();
        if (body.path("max_tokens").isNumber()) inference.put("maxTokens", body.path("max_tokens").asInt());
        else if (body.path("max_completion_tokens").isNumber()) inference.put("maxTokens", body.path("max_completion_tokens").asInt());
        if (body.path("temperature").isNumber()) inference.put("temperature", body.path("temperature").asDouble());
        if (body.path("top_p").isNumber()) inference.put("topP", body.path("top_p").asDouble());
        if (body.path("stop").isArray()) { ArrayNode s = inference.putArray("stopSequences"); body.path("stop").forEach(x -> s.add(x.asText())); }
        else if (body.path("stop").isTextual()) inference.putArray("stopSequences").add(body.path("stop").asText());
        if (!inference.isEmpty()) out.set("inferenceConfig", inference);
        ArrayNode tools = J.createArrayNode();
        for (JsonNode t : body.path("tools")) {
            JsonNode f = t.path("function");
            if (f.path("name").asText("").isBlank()) continue;
            ObjectNode spec = tools.addObject().putObject("toolSpec");
            spec.put("name", f.path("name").asText());
            String d = f.path("description").asText(""); spec.put("description", d.isBlank() ? f.path("name").asText() : d);
            JsonNode schema = f.path("parameters").isObject() ? f.path("parameters") : J.createObjectNode().put("type", "object");
            spec.putObject("inputSchema").set("json", schema);
        }
        if (!tools.isEmpty()) {
            ObjectNode config = out.putObject("toolConfig");
            config.set("tools", tools);
            JsonNode choice = body.path("tool_choice");
            if (choice.isTextual() && choice.asText().equals("required")) config.putObject("toolChoice").putObject("any");
            else if (choice.isObject() && !choice.path("function").path("name").asText("").isBlank()) config.putObject("toolChoice").putObject("tool").put("name", choice.path("function").path("name").asText());
            // "auto" is Converse's own default, and it has no "none": the tools stay, because the history may hold their calls
        } else if (usesTools) throw new IllegalArgumentException("the conversation holds tool calls but the request lists no tools: Converse needs the tools to read them");
        return out;
    }

    /** The same request without a forced tool choice: some models on Bedrock take tools but not a choice among them. */
    public static ObjectNode withoutToolChoice(ObjectNode converseRequest) {
        ObjectNode copy = converseRequest.deepCopy();
        if (copy.path("toolConfig").isObject()) ((ObjectNode) copy.path("toolConfig")).remove("toolChoice");
        return copy;
    }

    /** Converse's answer as a chat completion. */
    public static ObjectNode response(JsonNode converse, String model) {
        StringBuilder text = new StringBuilder(), reasoning = new StringBuilder();
        ArrayNode calls = J.createArrayNode();
        for (JsonNode block : converse.path("output").path("message").path("content")) {
            if (block.has("text")) text.append(block.path("text").asText(""));
            else if (block.has("toolUse")) {
                JsonNode use = block.path("toolUse");
                ObjectNode call = calls.addObject();
                call.put("id", use.path("toolUseId").asText("")); call.put("type", "function");
                ObjectNode f = call.putObject("function");
                f.put("name", use.path("name").asText(""));
                f.put("arguments", use.path("input").isMissingNode() || use.path("input").isNull() ? "{}" : use.path("input").toString());
            } else if (block.has("reasoningContent")) reasoning.append(block.path("reasoningContent").path("reasoningText").path("text").asText(""));
        }
        ObjectNode out = J.createObjectNode();
        out.put("object", "chat.completion"); out.put("model", model);
        ObjectNode choice = out.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        if (text.length() > 0 || calls.isEmpty()) message.put("content", text.toString()); else message.putNull("content");
        if (reasoning.length() > 0) message.put("reasoning_content", reasoning.toString());
        if (!calls.isEmpty()) message.set("tool_calls", calls);
        choice.put("finish_reason", switch (converse.path("stopReason").asText("")) {
            case "tool_use" -> "tool_calls";
            case "max_tokens" -> "length";
            case "content_filtered", "guardrail_intervened" -> "content_filter";
            default -> "stop";
        });
        JsonNode u = converse.path("usage");
        ObjectNode usage = out.putObject("usage");
        usage.put("prompt_tokens", u.path("inputTokens").asLong(0)); usage.put("completion_tokens", u.path("outputTokens").asLong(0));
        usage.put("total_tokens", u.path("totalTokens").asLong(u.path("inputTokens").asLong(0) + u.path("outputTokens").asLong(0)));
        return out;
    }

    /** A message's content as Converse blocks: text, and a picture given as a data: address. */
    private static void content(JsonNode content, ArrayNode blocks) {
        if (content.isTextual()) { if (!content.asText().isBlank()) blocks.addObject().put("text", content.asText()); return; }
        for (JsonNode part : content) {
            String type = part.path("type").asText("");
            if (type.equals("text")) { if (!part.path("text").asText("").isBlank()) blocks.addObject().put("text", part.path("text").asText()); }
            else if (type.equals("image_url")) {
                String url = part.path("image_url").path("url").asText("");
                Matcher m = Pattern.compile("^data:image/(png|jpe?g|gif|webp);base64,(.+)$", Pattern.DOTALL).matcher(url);
                if (!m.matches()) throw new IllegalArgumentException("Bedrock takes a picture as its data, not as a web address: fetch the picture and send it as a data: address");
                ObjectNode image = blocks.addObject().putObject("image");
                image.put("format", m.group(1).equals("jpg") ? "jpeg" : m.group(1));
                image.putObject("source").put("bytes", m.group(2).replaceAll("\\s+", ""));
            }
        }
    }

    private static String text(JsonNode content) {
        if (content.isTextual()) return content.asText();
        StringBuilder b = new StringBuilder();
        for (JsonNode part : content) if (part.path("type").asText("").equals("text")) b.append(part.path("text").asText(""));
        return b.toString();
    }

    /** A tool call's arguments arrive as JSON written in a string; Converse wants the object itself. */
    private static JsonNode object(JsonNode arguments) {
        if (arguments.isObject()) return arguments;
        String s = arguments.asText("").strip();
        if (s.isEmpty()) return J.createObjectNode();
        try { JsonNode n = J.readTree(s); return n.isObject() ? n : J.createObjectNode().set("value", n); }
        catch (Exception e) { return J.createObjectNode().put("_unparsed", s); }
    }
}
