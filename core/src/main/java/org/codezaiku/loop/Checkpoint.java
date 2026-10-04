package org.codezaiku.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a compaction summary can stand in for the transcript it replaces, and what to put there when it cannot.
 *
 * <p>The summary replaces every old message, so a bad one erases the run's memory. On 2026-09-30 a 27B wrote its next tool call as
 * the "summary" in ten of twenty-nine compactions across three runs (it went on working instead of summarising), and in most of the
 * others the text was cut off at the token limit before it reached "Next steps". Both were accepted: anything non-blank was. Now a
 * summary has to be text, hold the sections a continuing worker cannot do without, and have been finished; otherwise it is asked for
 * once more, and if that fails too the harness writes the checkpoint from the record itself.
 */
final class Checkpoint {

    private static final int MIN_CHARS = 150;
    private static final String[] REQUIRED = {"Progress", "Next steps"};
    private static final int RECORD_ACTIONS = 30;
    private static final int RECORD_RESULT_CHARS = 160;
    private static final int RECORD_ERROR_CHARS = 700;
    private static final int PRIOR_MAX_CHARS = 3000;
    private static final Pattern ERROR_MARK = Pattern.compile(
            "Traceback \\(most recent call last\\)|(?m)^ERROR\\b|\\b\\w*(?:Error|Exception): |\\bFAILED\\b|command not found|No such file or directory");

    private Checkpoint() { }

    /** The summary's text: thinking tags and anything from the first tool-call markup on are not part of it. */
    static String text(String reply) {
        if (reply == null) return "";
        String s = reply.replaceAll("(?s)<think>.*?</think>", "");
        int markup = PlanReply.toolMarkupStart(s);
        if (markup >= 0) s = s.substring(0, markup);
        return s.strip();
    }

    /**
     * What is wrong with the summary, in a few words; null when it can replace the transcript. A summary cut off at the token limit
     * is unusable only when the cut took a needed section with it.
     */
    static String defect(String reply, boolean cutOff) {
        String s = text(reply);
        if (s.isEmpty()) return reply != null && PlanReply.toolMarkupStart(reply) >= 0 ? "a tool call instead of a summary" : "empty";
        List<String> absent = new ArrayList<>();
        for (String h : REQUIRED) if (!hasSection(s, h)) absent.add(h);
        if (!absent.isEmpty()) return (cutOff ? "cut off at the token limit, " : "") + "no section: " + String.join(", ", absent);
        if (s.length() < MIN_CHARS) return "only " + s.length() + " characters";
        return null;
    }

    private static boolean hasSection(String s, String heading) {
        return Pattern.compile("(?im)^\\s{0,3}(?:#{1,6}\\s*|\\*\\*\\s*)?" + Pattern.quote(heading) + "\\b").matcher(s).find();
    }

    /** The text without one "## heading" section (up to the next heading). The goal is the harness's to state, not the summary's. */
    static String withoutSection(String s, String heading) {
        Matcher m = Pattern.compile("(?ims)^\\s{0,3}#{1,6}\\s*" + Pattern.quote(heading) + "\\b.*?(?=^\\s{0,3}#{1,6}\\s|\\z)").matcher(s);
        return m.replaceFirst("").strip();
    }

    /**
     * A checkpoint made from the record itself, for when the model's summary is unusable: the earlier checkpoint carried forward,
     * then what was done in the span being dropped — each tool call with the start of its result — and the last error seen.
     */
    static String fromRecord(ArrayNode oldSpan, String prior) {
        List<String> actions = new ArrayList<>();
        String lastError = "";
        List<String> pending = new ArrayList<>();
        for (JsonNode m : oldSpan) {
            String role = m.path("role").asText();
            if ("assistant".equals(role) && m.path("tool_calls").isArray()) {
                for (JsonNode c : m.path("tool_calls")) {
                    pending.add(c.path("function").path("name").asText() + "(" + oneLine(c.path("function").path("arguments").asText(""), 140) + ")");
                }
            } else if ("tool".equals(role)) {
                String result = m.path("content").asText("");
                String call = pending.isEmpty() ? "tool" : pending.remove(0);
                actions.add("- " + call + " → " + oneLine(result, RECORD_RESULT_CHARS));
                if (ERROR_MARK.matcher(result).find()) {
                    lastError = call + " →\n" + (result.length() > RECORD_ERROR_CHARS ? "…" + result.substring(result.length() - RECORD_ERROR_CHARS) : result);
                }
            }
        }
        for (String call : pending) actions.add("- " + call + " → (no result recorded)");
        StringBuilder sb = new StringBuilder();
        if (prior != null && !prior.isBlank()) {
            String p = prior.strip();
            sb.append("## Earlier checkpoint (carried forward)\n").append(p.length() > PRIOR_MAX_CHARS ? "…" + p.substring(p.length() - PRIOR_MAX_CHARS) : p).append("\n\n");
        }
        sb.append("## Progress\n(The harness wrote this part from its own record of the session: each step taken, oldest first, with the start of its result.)\n");
        int from = Math.max(0, actions.size() - RECORD_ACTIONS);
        if (from > 0) sb.append("- (").append(from).append(" earlier steps left out)\n");
        for (int i = from; i < actions.size(); i++) sb.append(actions.get(i)).append('\n');
        if (actions.isEmpty()) sb.append("- (no tool call in this part of the session)\n");
        sb.append("\n## Next steps\n- Continue from the last step above toward the goal; the files the goal names and whether each is written are listed in the system prompt.\n");
        if (!lastError.isEmpty()) sb.append("\n## Critical context\n- The last failing output: ").append(lastError.strip()).append('\n');
        return sb.toString().strip();
    }

    private static String oneLine(String s, int max) {
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
