package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * An honest exit for a task the model cannot bring to a working state — a contradictory/under-specified
 * spec, an unavailable capability, or a build it has genuinely failed to make compile. The field finding
 * (ImpossibleBench): giving the model an explicit abort drops fake-green declarations sharply (54%→9%),
 * because a model with no way out is pressured to call task_done on broken code. This is NOT a gate — it
 * is a second voluntary exit alongside {@code task_done}; the loop ends with a distinct blocked status so
 * the bench layer records an honest give-up instead of a false success.
 */
public final class TaskBlockedTool implements Tool {
    public static final String NAME = "task_blocked";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Call this ONLY if the task cannot be completed — the spec is contradictory or impossible, "
                + "a required capability is unavailable, or you have genuinely failed to make the build/tests "
                + "pass after real effort. Give the concrete reason. Use this instead of declaring done on "
                + "code that does not work — an honest blocked report is better than a false completion.";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        p.putObject("properties").putObject("reason").put("type", "string");
        p.putArray("required").add("reason");
        return p;
    }

    @Override
    public String execute(JsonNode args) {
        return args.path("reason").asText("(blocked)");
    }
}
