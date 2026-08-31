package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.codezaiku.chat.ChatMemory;

/**
 * The model's write path into cross-session memory — CONSENTED, because writing to your own
 * future context is an act (the deferred prime-agent {@code /refine} lesson: self-modifying
 * operating state goes behind the same consent surface as a file edit, and not before). The
 * person's own path is {@code /remember}, which asks nobody.
 */
public final class RememberTool implements Tool {

    private final ChatMemory memory;

    public RememberTool(ChatMemory memory) {
        this.memory = memory;
    }

    @Override
    public String name() {
        return "remember";
    }

    @Override
    public String description() {
        return "Save one durable fact to PROJECT MEMORY, carried into every future session here. "
                + "For things worth knowing next month: a preference the person stated, a trap "
                + "that cost real time, a decision with lasting scope. Not for session-local "
                + "state (the session already keeps that).";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("fact").put("type", "string")
                .put("description", "One self-contained sentence, understandable months from now "
                        + "with no other context.");
        p.putArray("required").add("fact");
        return p;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String fact = args.path("fact").asText("");
        if (fact.isBlank()) return "ERROR: empty fact";
        memory.remember(fact, "model");
        return "remembered (in " + memory.file() + " — the person can edit or /forget-memory it)";
    }
}
