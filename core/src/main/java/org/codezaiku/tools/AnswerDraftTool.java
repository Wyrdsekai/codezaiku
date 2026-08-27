package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Incremental answer assembly for research runs whose deliverable is BIGGER than one completion.
 *
 * <p>Measured failure (WideSearch ws_en_013): the model fetched every source and had the data, but a
 * 109-row table exceeds what a small model can emit in one pass — the prose-answer turn produced nothing
 * once and a description of the table the second time. The fix is the same one the coding loop uses for
 * big work: accumulate. Rows are SAVED as they are extracted, turn by turn, and the harness includes the
 * accumulated draft in the final answer automatically — the model never has to reproduce the whole table
 * in one breath.
 */
public final class AnswerDraftTool implements Tool {
    private static final int MAX_CHARS = 24_000;
    private final StringBuilder draft = new StringBuilder();

    /** The accumulated draft ("" when unused). */
    public String draft() { return draft.toString(); }

    @Override public String name() { return "add_to_answer"; }

    @Override public String description() {
        return "Save a piece of your final answer as you find it (e.g. the table rows you just extracted "
                + "from a source). Everything saved is AUTOMATICALLY included in your final answer when you "
                + "call task_done — never re-type saved content. Save the table header first, then each "
                + "batch of rows right after reading its source.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        p.putObject("properties").putObject("text").put("type", "string");
        p.putArray("required").add("text");
        return p;
    }

    @Override public String execute(JsonNode args) {
        String text = args.path("text").asText("").strip();
        if (text.isBlank()) return "ERROR: nothing to save — pass the rows/text in `text`.";
        if (draft.length() >= MAX_CHARS)
            return "draft is FULL (" + draft.length() + " chars) — stop saving; write any brief closing "
                    + "notes and call task_done now.";
        if (draft.length() > 0) draft.append('\n');
        draft.append(text);
        long rows = draft.toString().lines().filter(l -> l.chars().filter(c -> c == '|').count() >= 2).count();
        return "saved — draft now " + draft.length() + " chars, " + rows + " table rows. It will be part of "
                + "your final answer; do NOT re-type it. Keep going, or call task_done when every item is in.";
    }
}
