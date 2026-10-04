package org.codezaiku.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the plan out of the planning call's reply.
 *
 * <p>The plan is a numbered list that counts up from 1. A reply can hold several such lists (requirements, options weighed, then the
 * plan), so the one under a "PLAN" heading wins, and without a heading the last one does: a model lists what it is choosing between
 * first and what it will do last. A model that thinks before it answers can leave the plan in its thinking and put something else in
 * the reply — a 27B did on every run of 2026-09-30, and the harness logged "0 steps parsed" over an eight-step plan — so the thinking
 * is read the same way when the reply holds no plan.
 */
final class PlanReply {

    static final int MIN_STEPS = 2;
    static final int MAX_STEPS = 8;
    private static final int STEP_MAX_CHARS = 200;
    /** Lines of other text allowed between two steps (a step's own sub-points) before the list counts as ended. */
    private static final int MAX_GAP_LINES = 6;

    private static final Pattern NUMBERED = Pattern.compile("^(?:step\\s+)?(\\d{1,2})[.):\\-]\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BULLET = Pattern.compile("^[-*•]\\s+(.+)$");
    private static final Pattern HEADING = Pattern.compile(
            "^[#\\s]*(?:[\\w', ]{0,40}\\s)?(?:plan|steps)\\s*:?\\s*$|^[#\\s]*approach\\s*:?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADED_BLOCK = Pattern.compile(
            "(?:^|\\n)(?:plan|steps?|approach):?\\s*\\n([\\s\\S]+?)(?=\\n\\n[A-Z]|$)", Pattern.CASE_INSENSITIVE);
    private static final String[] TOOL_MARKUP = {"<tool_call", "<function=", "<|tool_call", "<tool_use", "<invoke "};

    private PlanReply() { }

    /** The plan in the reply's own text: a numbered list, else a bulleted one. Null when there is none. */
    static List<String> inReply(String text) {
        String clean = clean(text);
        if (clean.isBlank()) return null;
        List<String> numbered = numberedPlan(clean);
        if (numbered != null) return numbered;
        String body = clean;
        Matcher h = HEADED_BLOCK.matcher(clean);
        if (h.find()) body = h.group(1);
        List<String> bullets = new ArrayList<>();
        for (String l : body.split("\\n")) {
            Matcher m = BULLET.matcher(l.strip());
            if (m.find()) bullets.add(m.group(1).strip());
        }
        return bullets.size() >= MIN_STEPS ? trim(bullets) : null;
    }

    /**
     * The research questions in a reply to the questions call: the numbered lines after QUESTIONS, at most three, each ending
     * in a question mark or long enough to be one. Empty for {@code QUESTIONS: none} and for a reply without a list.
     */
    static List<String> researchQuestions(String text) {
        String clean = clean(text);
        if (clean.isBlank()) return List.of();
        int at = clean.toUpperCase(Locale.ROOT).indexOf("QUESTIONS");
        String block = at < 0 ? clean : clean.substring(at);
        if (block.toLowerCase(Locale.ROOT).matches("(?s)questions:?\\s*none\\b.*")) return List.of();
        List<String> out = new ArrayList<>();
        for (String l : block.split("\\n")) {
            Matcher m = NUMBERED.matcher(l.strip());
            if (!m.matches()) continue;
            String q = m.group(2).strip().replaceAll("^[`*]+|[`*]+$", "");
            if (q.length() >= 12 && !q.startsWith("<")) out.add(q);
            if (out.size() == 3) break;
        }
        return out;
    }

    /** The plan in the model's thinking: numbered lists only, since thinking is full of bullets that are not a plan. */
    static List<String> inThinking(String text) {
        String clean = clean(text);
        return clean.isBlank() ? null : numberedPlan(clean);
    }

    /** Where tool-call markup written as text starts, or -1: what follows it is an action, not part of a plan or a summary. */
    static int toolMarkupStart(String s) {
        int at = -1;
        for (String marker : TOOL_MARKUP) {
            int i = s.indexOf(marker);
            if (i >= 0 && (at < 0 || i < at)) at = i;
        }
        return at;
    }

    private static String clean(String text) {
        if (text == null) return "";
        String s = text.replaceAll("```[\\w]*\\n?|\\n?```", "").replace("**", "");
        int markup = toolMarkupStart(s);
        return markup >= 0 ? s.substring(0, markup) : s;
    }

    private record Run(boolean headed, List<String> steps) { }

    private static List<String> numberedPlan(String clean) {
        List<Run> runs = new ArrayList<>();
        List<String> cur = null;
        boolean curHeaded = false;
        int last = 0, gap = 0;
        String before = "";
        for (String line : clean.split("\\n")) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            Matcher m = NUMBERED.matcher(t);
            if (m.matches()) {
                int n = Integer.parseInt(m.group(1));
                if (n == 1) {
                    if (cur != null) runs.add(new Run(curHeaded, cur));
                    cur = new ArrayList<>();
                    curHeaded = HEADING.matcher(before).matches();
                    cur.add(m.group(2).strip());
                    last = 1;
                    gap = 0;
                } else if (cur != null && n == last + 1) {
                    cur.add(m.group(2).strip());
                    last = n;
                    gap = 0;
                } else if (cur != null) {
                    runs.add(new Run(curHeaded, cur));
                    cur = null;
                }
                continue;
            }
            before = t;
            if (cur != null && ++gap > MAX_GAP_LINES) {
                runs.add(new Run(curHeaded, cur));
                cur = null;
            }
        }
        if (cur != null) runs.add(new Run(curHeaded, cur));
        List<String> best = null;
        boolean bestHeaded = false;
        for (Run r : runs) {
            if (r.steps().size() < MIN_STEPS) continue;
            if (best == null || r.headed() || !bestHeaded) {
                best = r.steps();
                bestHeaded = r.headed();
            }
        }
        return best == null ? null : trim(best);
    }

    private static List<String> trim(List<String> steps) {
        List<String> out = new ArrayList<>();
        for (String s : steps) {
            out.add(s.length() > STEP_MAX_CHARS ? s.substring(0, STEP_MAX_CHARS) + "…" : s);
            if (out.size() >= MAX_STEPS) break;
        }
        return out;
    }
}
