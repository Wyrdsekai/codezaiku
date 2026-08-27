package org.codezaiku.review;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the model's findings list into one anchored to real line numbers.
 *
 * <p>The model reports the code it is complaining about, quoted; {@link DiffAnchor} computes where that
 * code is. Nothing here trusts a line number from the model, because there is not one to trust.
 *
 * <p>Findings that cannot be located are kept, but SEPARATED and labelled rather than printed beside
 * located ones. Dropping them silently would hide two different things that deserve different
 * reactions: a hallucinated quote (the finding is probably fiction) and a real problem in a file the
 * diff does not cover (the finding may be fine, it just cannot be placed). Printing them at a guessed
 * line — what asking the model for {@code file:line} amounts to — is the option worth eliminating.
 */
public final class ReviewReport {

    /** One finding as reported, plus where the harness managed to place it. */
    public record Finding(String severity, String file, String snippet, String body,
                          Optional<DiffAnchor.Anchor> anchor) {

        public boolean located() { return anchor.isPresent(); }

        /** {@code [sev] file:line — body}, or the unplaced form when no anchor was found. */
        public String render() {
            if (anchor.isEmpty()) return "[" + severity + "] " + file + " — " + body;
            DiffAnchor.Anchor a = anchor.get();
            String where = a.isSingleLine()
                    ? String.valueOf(a.startLine())
                    : a.startLine() + "-" + a.endLine();
            String deleted = a.side() == DiffAnchor.Side.OLD ? "  (in code this change DELETES)" : "";
            return "[" + severity + "] " + file + ":" + where + " — " + body + deleted;
        }
    }

    /**
     * The contract given to the model. Deliberately a ONE-LINE shape and a minimal edit away from the
     * plain findings list a model produces unprompted — a small model copies a literal format
     * faithfully and garbles a multi-field template, so the only change from the obvious format is
     * that the position field holds quoted CODE instead of a number.
     */
    public static final String FORMAT_INSTRUCTION =
            "as a list where EVERY line is one finding, copying this shape exactly:\n"
            + "  [high] src/api/handler.py `query = \"SELECT * FROM t WHERE u = \" + user` — SQL injection: "
            + "user input is concatenated into the query — use a parameterised query\n"
            + "  [med] src/cache.py `cache = Cache(ttl=0)` — ttl=0 disables caching entirely — set a real ttl\n"
            + "Severity is one of high, med, low. Inside the backticks put ONE line of code copied "
            + "CHARACTER-FOR-CHARACTER from the file, with no + or - diff marker. The quoted code is what "
            + "locates the finding, so it must appear verbatim in the code; write no line numbers.";

    // Two shapes, tried in order. They are separate patterns rather than one alternation because a
    // character class of [`"'] terminates the snippet at the first quote INSIDE the code — and most
    // real lines contain a quote, so that silently discarded a large share of genuine findings.
    //
    //   [sev] path `code` — rest        backticks: code may contain any quote freely
    private static final Pattern BACKTICKED = Pattern.compile(
            "^\\s*[-*]?\\s*\\[(high|med|medium|low)]\\s+(\\S+?)\\s*`([^`]+)`\\s*[—:-]+\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    //   [sev] path "code" — rest        fallback; GREEDY so it closes on the LAST quote that is
    //                                   followed by a separator, not the first quote in the code
    private static final Pattern QUOTED = Pattern.compile(
            "^\\s*[-*]?\\s*\\[(high|med|medium|low)]\\s+(\\S+?)\\s*[\"'](.+)[\"']\\s*[—:-]+\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Parse the model's summary and anchor each finding against {@code diff}.
     *
     * <p>Tolerant by design: a line that does not match the contract is skipped rather than failing the
     * report, because a model that emits one malformed line among nine good ones should cost one
     * finding, not the review.
     */
    public static List<Finding> parse(String summary, String diff) {
        return parse(summary, diff, f -> null);
    }

    /**
     * As {@link #parse(String, String)}, but falling back to the file's own text when the quoted code
     * is not in the diff. Two cases need this: a whole-project review, which has no diff at all, and a
     * finding about code the change only touches at its edge. {@code fileContent} returns null for a
     * path it cannot or should not read — it is the caller's confinement boundary, not ours.
     */
    public static List<Finding> parse(String summary, String diff,
                                      Function<String, String> fileContent) {
        List<Finding> out = new ArrayList<>();
        if (summary == null) return out;
        for (String raw : summary.split("\n")) {
            Matcher m = BACKTICKED.matcher(raw);
            if (!m.matches()) m = QUOTED.matcher(raw);
            if (!m.matches()) continue;
            String severity = m.group(1).toLowerCase(Locale.ROOT);
            if (severity.equals("medium")) severity = "med";
            String file = m.group(2).replaceAll("[:,]+$", "");
            // A model quoting code inside a quoted field escapes the inner quotes. That is an artifact
            // of the report format, not part of the source line, so it must go before matching.
            String snippet = m.group(3).replace("\\\"", "\"").replace("\\'", "'");
            String body = m.group(4).strip();
            Optional<DiffAnchor.Anchor> anchor = DiffAnchor.resolve(diff, snippet, file);
            if (anchor.isEmpty()) {
                String content = fileContent == null ? null : fileContent.apply(file);
                if (content != null) anchor = DiffAnchor.resolveInFile(content, snippet);
            }
            out.add(new Finding(severity, file, snippet, body, anchor));
        }
        return out;
    }

    /**
     * Merge findings from several independent review passes, dropping duplicates.
     *
     * <p>Measured: across 25 PRs at K=3, recall by the UNION of runs was 48% against 32% for the
     * mean — the same findings, just not surfacing on every pass. Five of the thirteen PRs missed in
     * one run had been found in another. Repeating the pass and unioning converts that 16-point gap
     * into output.
     *
     * <p>Two findings are the same when they sit on the same file and their anchors OVERLAP. Line
     * identity is too strict — independent passes quote different lines of the same block and anchor
     * a line or two apart — and file-only is far too loose. Unanchored findings cannot be compared by
     * position, so they dedupe on the quoted snippet instead.
     *
     * <p>This is a WORKAROUND for instability, not a cure, and it has a cost the recall number does
     * not show: unioning N passes also unions their false positives, so the reader sees more. Keep
     * the passes low.
     */
    public static List<Finding> union(List<List<Finding>> passes) {
        List<Finding> merged = new ArrayList<>();
        for (List<Finding> pass : passes) {
            if (pass == null) continue;
            for (Finding f : pass) {
                if (merged.stream().noneMatch(seen -> duplicates(seen, f))) merged.add(f);
            }
        }
        return merged;
    }

    private static boolean duplicates(Finding a, Finding b) {
        if (!sameFile(a.file(), b.file())) return false;
        if (a.located() && b.located()) {
            DiffAnchor.Anchor x = a.anchor().get(), y = b.anchor().get();
            return x.startLine() <= y.endLine() && y.startLine() <= x.endLine();   // overlap
        }
        if (a.located() != b.located()) return false;
        return normalize(a.snippet()).equals(normalize(b.snippet()));
    }

    /** The model may name a bare filename in one pass and a full path in another. */
    private static boolean sameFile(String a, String b) {
        if (a == null || b == null) return false;
        return a.equals(b) || a.endsWith("/" + b) || b.endsWith("/" + a);
    }

    /**
     * Whitespace is dropped ENTIRELY, not merely collapsed. This compares two model QUOTES of the
     * same source line, and a model re-typing code varies its spacing freely — {@code os.system(cmd)}
     * and {@code os.system( cmd )} are one finding reported twice. (DiffAnchor keeps single spaces
     * because it matches against real file lines; this does not.)
     */
    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    /** Located findings first (severity order), then anything that could not be placed. */
    public static String render(List<Finding> findings) {
        if (findings.isEmpty()) return "No findings in the expected format.";
        List<String> order = List.of("high", "med", "low");
        StringBuilder sb = new StringBuilder();

        List<Finding> located = findings.stream().filter(Finding::located)
                .sorted(Comparator.comparingInt(f -> order.indexOf(f.severity()))).toList();
        List<Finding> unplaced = findings.stream().filter(f -> !f.located()).toList();

        for (Finding f : located) sb.append(f.render()).append('\n');

        if (!unplaced.isEmpty()) {
            sb.append("\nCOULD NOT LOCATE (the quoted code was not found in the reviewed change — treat "
                    + "with suspicion: most often the quote was invented):\n");
            for (Finding f : unplaced) sb.append("  ").append(f.render()).append('\n');
        }
        sb.append("\n").append(located.size()).append(" located, ")
          .append(unplaced.size()).append(" unlocated");
        return sb.toString();
    }

    private ReviewReport() { }
}
