package org.codezaiku.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.tools.Tool;

/**
 * Fetch a matched runbook's procedure (HolmesGPT's {@code fetch_runbook}, PLAN_CODEZAIKU_OPS.md §4). The
 * model calls this when its investigation matches a runbook in the catalog; the returned procedure
 * overrides the default steps. Runbooks are procedures, not answers — fetching one never reveals the root
 * cause, only how to find it.
 */
public final class FetchRunbookTool implements Tool {
    private final RunbookCatalog catalog;

    public FetchRunbookTool(RunbookCatalog catalog) { this.catalog = catalog; }

    @Override public String name() { return "fetch_runbook"; }

    @Override public String description() {
        return "Fetch the step-by-step procedure for a class of incident, by runbook id. Call this when the "
             + "problem matches one of the runbooks listed in your instructions; follow its procedure to find "
             + "the ROOT cause. Available runbooks are listed in the system prompt.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("id").put("type", "string")
                .put("description", "The runbook id, e.g. web-502, service-down, disk-full, dependency-down.");
        p.putArray("required").add("id");
        return p;
    }

    @Override public String execute(JsonNode args) {
        return catalog.fetch(args.path("id").asText(""));
    }
}
