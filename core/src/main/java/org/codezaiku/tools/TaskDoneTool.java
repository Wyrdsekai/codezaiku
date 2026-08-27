package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The model's only way to end the loop. Because {@code tool_choice="required"} forbids a plain
 * prose message, the familiar signals completion by calling this. The loop detects the name and
 * stops; the summary is the familiar's closing statement.
 */
public final class TaskDoneTool implements Tool {
    public static final String NAME = "task_done";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Call this when the goal is met and verified, with a short summary of what was done.";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        p.putObject("properties").putObject("summary").put("type", "string");
        p.putArray("required").add("summary");
        return p;
    }

    @Override
    public String execute(JsonNode args) {
        return args.path("summary").asText("(done)");
    }
}
