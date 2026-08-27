package org.codezaiku.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.codezaiku.tools.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * The channel a review reports defects through: a TOOL CALL, not a text format.
 *
 * <p>Measured, and the reason this class exists. Asked for a one-line format —
 * {@code [high] file `code` — problem — fix} — the 9B wrote accurate numbered PROSE instead, across
 * three prompt variants, keeping the severity tag and dropping the code quote, so nothing could be
 * anchored. Asking a tool-calling model to hand-format text was the category error: {@code codezaiku
 * smoke} exists precisely because emitting a well-formed tool call is the one thing the harness
 * cannot work without, and the model does it reliably. On the identical fixture, a harness that took
 * findings through a tool got a located, correctly-typed finding out of the same 9B.
 *
 * <p>Each finding carries the CODE it concerns, never a line number — {@link DiffAnchor} computes the
 * position. The anchor is resolved here, at call time, so the tool result can tell the model whether
 * its quote was actually found. That is information, not a gate: an unlocatable finding is still
 * recorded and still reported, because refusing the model's action to enforce harness preference is
 * the thing this project does not do. The model is simply told, and may re-report with an exact quote.
 */
public final class ReportFindingTool implements Tool {

    private static final int MAX_FINDINGS = 40;

    private final String diff;
    private final Function<String, String> fileContent;
    private final List<ReviewReport.Finding> findings = new ArrayList<>();

    public ReportFindingTool(String diff, Function<String, String> fileContent) {
        this.diff = diff == null ? "" : diff;
        this.fileContent = fileContent == null ? f -> null : fileContent;
    }

    /** Everything reported this run, in call order. */
    public List<ReviewReport.Finding> findings() { return List.copyOf(findings); }

    @Override public String name() { return "report_finding"; }

    @Override public String description() {
        return "Report ONE defect you found. Call this once per defect, as soon as you find it. "
                + "`existing_code` must be a line copied CHARACTER-FOR-CHARACTER from the code, with no "
                + "+/- diff marker — the harness uses it to locate your finding, so it must appear "
                + "verbatim. Do not report line numbers; do not use this to describe what the change "
                + "does, only what is wrong with it.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode schema = j.createObjectNode();
        schema.put("type", "object");
        ObjectNode p = schema.putObject("properties");

        ObjectNode sev = p.putObject("severity");
        sev.put("type", "string");
        sev.putArray("enum").add("high").add("med").add("low");
        sev.put("description", "high = a bug, security hole or data loss; med = likely to bite; low = "
                + "maintainability.");

        p.putObject("file").put("type", "string")
                .put("description", "Path of the file the defect is in, as it appears in the change.");
        p.putObject("existing_code").put("type", "string")
                .put("description", "One line of the offending code, copied EXACTLY from the file.");
        p.putObject("problem").put("type", "string")
                .put("description", "What is wrong, and what goes wrong because of it.");
        p.putObject("fix").put("type", "string")
                .put("description", "The change that would fix it.");

        schema.putArray("required").add("severity").add("file").add("existing_code").add("problem");
        return schema;
    }

    @Override public String execute(JsonNode args) {
        String severity = normalizeSeverity(args.path("severity").asText(""));
        String file = args.path("file").asText("").strip();
        String snippet = args.path("existing_code").asText("");
        String problem = args.path("problem").asText("").strip();
        String fix = args.path("fix").asText("").strip();

        if (file.isBlank() || snippet.isBlank() || problem.isBlank()) {
            return "ERROR: report_finding needs `file`, `existing_code` (a line copied from that file) "
                    + "and `problem`. Nothing was recorded — call it again with all three.";
        }
        if (findings.size() >= MAX_FINDINGS) {
            return "That is " + MAX_FINDINGS + " findings — enough. Call task_done now.";
        }

        Optional<DiffAnchor.Anchor> anchor = DiffAnchor.resolve(diff, snippet, file);
        if (anchor.isEmpty()) {
            String content = fileContent.apply(file);
            if (content != null) anchor = DiffAnchor.resolveInFile(content, snippet);
        }

        String body = fix.isBlank() ? problem : problem + " — " + fix;
        findings.add(new ReviewReport.Finding(severity, file, snippet, body, anchor));

        if (anchor.isPresent()) {
            DiffAnchor.Anchor a = anchor.get();
            return "recorded [" + severity + "] " + file + ":" + a.startLine()
                    + (a.side() == DiffAnchor.Side.OLD ? " (in code this change deletes)" : "")
                    + ". Keep going, or call task_done when you have reported every defect.";
        }
        return "recorded [" + severity + "] " + file + ", but that exact code was NOT found in the change "
                + "or the file, so it has no line. If you were quoting from memory, call report_finding "
                + "again with the line copied exactly as it appears; otherwise carry on.";
    }

    private static String normalizeSeverity(String raw) {
        String s = raw.strip().toLowerCase(Locale.ROOT);
        if (s.startsWith("h") || s.startsWith("crit")) return "high";
        if (s.startsWith("l") || s.startsWith("min") || s.startsWith("nit")) return "low";
        return "med";
    }
}
