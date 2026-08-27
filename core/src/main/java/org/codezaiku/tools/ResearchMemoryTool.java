package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.research.ResearchMemory;

/**
 * Recall from the research memory pool AS A TOOL, not just as a pre-seeded prompt block.
 *
 * <p>Follows A-RAG (arXiv 2602.03442): retrieval works better as a tool call inside the agent loop than as a
 * preprocessing step — the agent pulls what it needs, when a gap appears, instead of being handed one fixed
 * blob up front. We do both: the run is seeded with the most relevant prior findings, and the agent can query
 * the pool again mid-run as its questions sharpen.
 */
public final class ResearchMemoryTool implements Tool {

    @Override public String name() { return "recall_findings"; }

    @Override public String description() {
        return "Recall findings established by EARLIER research runs (the research memory pool). Use before "
                + "searching the web for something that may already be known, and whenever a new sub-question "
                + "appears — it is cheaper and already verified.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("query").put("type", "string");
        props.putObject("limit").put("type", "integer");
        p.putArray("required").add("query");
        return p;
    }

    @Override public String execute(JsonNode args) {
        String q = args.path("query").asText("");
        if (q.isBlank()) return "ERROR: empty query";
        int limit = Math.min(Math.max(args.path("limit").asInt(6), 1), 20);
        var hits = ResearchMemory.recall(q, limit);
        if (hits.isEmpty())
            return "nothing in the research pool about that yet — search the web for it.";
        StringBuilder sb = new StringBuilder("known from earlier research:\n");
        for (var h : hits) {
            sb.append("- ").append(h.claim());
            if (!h.source().isBlank()) sb.append("  [").append(h.source().split(" ")[0]).append(']');
            sb.append('\n');
        }
        return sb.toString();
    }
}
