package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * {@code scholar_search}: papers and books by DOI from Crossref and OpenAlex. Offered in every research run
 * beside {@code web_search}, in every configuration, because a web engine ranks the primary literature low or not
 * at all and these two find it in under a second with no key.
 */
public final class ScholarSearchTool implements Tool {

    @Override public String name() { return "scholar_search"; }

    @Override public String description() {
        return "Search the scholarly literature (Crossref and OpenAlex): papers, books and chapters, each with its DOI, "
                + "venue, year and authors. Use it whenever the question touches a literature: medicine, science, history, "
                + "law, the humanities: because its hits are primary sources a web engine ranks low. Then web_fetch the "
                + "DOI or landing page to read.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("query").put("type", "string").put("description", "Words a paper's title or abstract would use; a topic, not a sentence.");
        props.putObject("limit").put("type", "integer").put("description", "How many works (default 8, max 20).");
        p.putArray("required").add("query");
        return p;
    }

    @Override public String execute(JsonNode args) {
        String query = args.path("query").asText("");
        if (query.isBlank()) return "ERROR: empty query";
        int limit = Math.min(Math.max(args.path("limit").asInt(8), 1), 20);
        List<ScholarSearch.Row> rows = ScholarSearch.merged(query, limit);
        if (rows.isEmpty()) return "no works found for: " + query + " (Crossref and OpenAlex answered with nothing, or did not answer)";
        ScholarSearch.SCHOLAR_USED.incrementAndGet();
        return ScholarSearch.render(query, " (scholarly literature: Crossref and OpenAlex)", rows, limit);
    }
}
