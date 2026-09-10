package org.codezaiku.chat;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Research Requirements Document — the brief a refine conversation produces and the deep run
 * consumes. It dictates the boundaries of the research and any specific asks (the operator,
 * 2026-09-03). The model PROPOSES changes as a JSON patch after each turn; the machine applies
 * them (append, deduplicate); the person reads and steers. {@link #toQuestion()} renders it as the
 * single prompt the research loop receives, so what was asked is what runs — and it opens the
 * investigation, so a reader later sees the ask, not only the findings.
 */
public final class ResearchBrief {

    private final String topic;
    private String question;
    private String depth = "broad";
    private final Set<String> scopeIn = new LinkedHashSet<>();
    private final Set<String> scopeOut = new LinkedHashSet<>();
    private final Set<String> subQuestions = new LinkedHashSet<>();
    private final Set<String> sources = new LinkedHashSet<>();
    private final Set<String> asks = new LinkedHashSet<>();
    private final Set<String> held = new LinkedHashSet<>();
    private int turns = 0;

    public ResearchBrief(String topic) {
        this.topic = topic.strip();
        this.question = this.topic;
    }

    public String topic() { return topic; }
    public String question() { return question; }
    public String depth() { return depth; }
    public int turns() { return turns; }
    public boolean empty() { return subQuestions.isEmpty() && scopeIn.isEmpty() && asks.isEmpty() && question.equals(topic); }

    /**
     * Apply the model's proposal: {@code {question?, depth?, scope_in[], scope_out[], sub_questions[],
     * sources[], asks[], held[]}}. Lists append (deduplicated, case-insensitively); scalars replace.
     * A list item starting with {@code -} REMOVES the matching entry. Returns how many items changed.
     */
    public int apply(JsonNode patch) {
        if (patch == null || !patch.isObject()) return 0;
        int n = 0;
        String q = text(patch, "question");
        if (q != null && !q.equals(question)) { question = q; n++; }
        String d = text(patch, "depth");
        if (d != null && (d.equals("broad") || d.equals("depth")) && !d.equals(depth)) { depth = d; n++; }
        n += merge(scopeIn, patch.get("scope_in"));
        n += merge(scopeOut, patch.get("scope_out"));
        n += merge(subQuestions, patch.get("sub_questions"));
        n += merge(sources, patch.get("sources"));
        n += merge(asks, patch.get("asks"));
        n += merge(held, patch.get("held"));
        turns++;
        return n;
    }

    private static String text(JsonNode p, String k) {
        JsonNode v = p.get(k);
        if (v == null || !v.isTextual() || v.asText().isBlank()) return null;
        return v.asText().strip().replaceAll("\\s+", " ");
    }

    private static int merge(Set<String> into, JsonNode arr) {
        if (arr == null || !arr.isArray()) return 0;
        int n = 0;
        for (JsonNode e : arr) {
            if (!e.isTextual()) continue;
            String s = e.asText().strip().replaceAll("\\s+", " ");
            if (s.isEmpty()) continue;
            if (s.startsWith("-") && s.length() > 1) {
                String target = s.substring(1).strip().toLowerCase(Locale.ROOT);
                if (into.removeIf(x -> x.toLowerCase(Locale.ROOT).equals(target))) n++;
                continue;
            }
            boolean dup = into.stream().anyMatch(x -> x.equalsIgnoreCase(s));
            if (!dup && into.add(s)) n++;
        }
        return n;
    }

    /** What the person reads. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("RESEARCH BRIEF — ").append(topic).append("\n");
        sb.append("  question:  ").append(question).append("\n");
        sb.append("  depth:     ").append(depth).append(depth.equals("broad") ? "  (survey the landscape)" : "  (deep-read a narrow question)").append("\n");
        list(sb, "in scope", scopeIn);
        list(sb, "out of scope", scopeOut);
        list(sb, "sub-questions", subQuestions);
        list(sb, "sources wanted", sources);
        list(sb, "specific asks", asks);
        list(sb, "already held", held);
        if (empty()) sb.append("  (nothing refined yet — say what you want to understand, what to leave out, which sources matter)\n");
        return sb.toString();
    }

    private static void list(StringBuilder sb, String label, Set<String> items) {
        if (items.isEmpty()) return;
        sb.append("  ").append(label).append(":\n");
        for (String s : items) sb.append("    - ").append(s).append("\n");
    }

    /** The single prompt the deep run receives — the brief, verbatim, with its boundaries stated as instructions. */
    public String toQuestion() {
        StringBuilder sb = new StringBuilder();
        sb.append(question).append("\n\nRESEARCH BRIEF (the person's requirements — respect its boundaries):\n");
        if (!scopeIn.isEmpty()) sb.append("IN SCOPE: ").append(String.join("; ", scopeIn)).append("\n");
        if (!scopeOut.isEmpty()) sb.append("OUT OF SCOPE (do not spend searches here): ").append(String.join("; ", scopeOut)).append("\n");
        if (!subQuestions.isEmpty()) {
            sb.append("SUB-QUESTIONS to answer:\n");
            for (String s : subQuestions) sb.append("- ").append(s).append("\n");
        }
        if (!sources.isEmpty()) sb.append("SOURCES WANTED: ").append(String.join("; ", sources)).append("\n");
        if (!asks.isEmpty()) {
            sb.append("SPECIFIC ASKS (deliverables):\n");
            for (String s : asks) sb.append("- ").append(s).append("\n");
        }
        if (!held.isEmpty()) sb.append("ALREADY HELD by the library (build on it, do not re-research): ").append(String.join("; ", held)).append("\n");
        return sb.toString();
    }

    /** A short title for the investigation. */
    public String title() { return org.codezaiku.research.LibraryBridge.compress(question, 120); }

    public List<String> subQuestions() { return new ArrayList<>(subQuestions); }
}
