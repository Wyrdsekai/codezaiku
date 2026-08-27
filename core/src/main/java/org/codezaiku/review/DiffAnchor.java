package org.codezaiku.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a review finding to a line number from the CODE IT QUOTES, not from a number the model
 * reported.
 *
 * <p>Asking a model for {@code file:line} makes it do arithmetic over a unified diff — count hunk
 * headers, track added versus removed lines, offset into the new file. Small models are bad at it and
 * large ones still drift, which is the single most common way a review comment lands on the wrong line.
 * So the model's job is reduced to what it is actually good at: quoting the code it is complaining
 * about. The harness then computes where that code lives. Machine-computed evidence outranks model
 * judgment — the same move as constraining the ops localizer to sensor-flagged services.
 *
 * <p>A finding whose snippet cannot be located is NOT reported at a guessed line. Failing to anchor is
 * itself a signal: the quoted code is usually not in the diff at all, meaning the model invented it.
 * {@link #resolve} returns empty and the caller drops or flags the finding.
 */
public final class DiffAnchor {

    /** Where a quoted snippet actually lives. Lines are 1-based and inclusive. */
    public record Anchor(int startLine, int endLine, Side side) {
        public boolean isSingleLine() { return startLine == endLine; }
    }

    /** Which version of the file the snippet was found in. */
    public enum Side {
        /** Present in the post-change file — the normal case for a review comment. */
        NEW,
        /** Only in the pre-change file: the model is commenting on code the diff DELETED. */
        OLD
    }

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");

    private record NumberedLine(int lineNumber, String normalized) { }

    /**
     * Locate {@code snippet} within {@code unifiedDiff}, preferring the post-change file.
     *
     * <p>The new side is tried first because a review comment almost always concerns code that now
     * exists; the old side is a deliberate fallback so a comment about deleted code still anchors
     * somewhere truthful rather than being silently dropped.
     */
    public static Optional<Anchor> resolve(String unifiedDiff, String snippet) {
        return resolve(unifiedDiff, snippet, null);
    }

    /**
     * As {@link #resolve(String, String)}, restricted to hunks belonging to {@code file}.
     *
     * <p>Always pass the file when it is known. Without it a snippet is searched across EVERY file in
     * the diff and the first match wins, so a finding the model attributed to one file could be
     * reported at another file's line number. Seen in a real scoring run as {@code array.py:3644} and
     * {@code string_arrow.py:3644} — the same line in two files, because both matched the same
     * physical hunk. A location that is confidently wrong is worse than no location.
     */
    public static Optional<Anchor> resolve(String unifiedDiff, String snippet, String file) {
        List<String> target = normalizeSnippet(snippet);
        if (target.isEmpty() || unifiedDiff == null || unifiedDiff.isBlank()) return Optional.empty();

        for (Side side : new Side[]{Side.NEW, Side.OLD}) {
            List<NumberedLine> lines = sideLines(unifiedDiff, side, file);
            Optional<Anchor> hit = findRun(lines, target, side);
            if (hit.isPresent()) return hit;
        }
        // RELAXED PASS. Measured on real runs: roughly half of reported findings failed to anchor,
        // and the dominant cause was a multi-line quote that is REAL but not CONTIGUOUS — the model
        // quotes two statements and silently drops the comment lines between them. Requiring a
        // consecutive run threw those away even though every quoted line is present.
        //
        // The relaxation is narrow on purpose: fall back to the single most DISTINCTIVE quoted line.
        // Anchoring to any line the model quoted risks landing on `}` or `return;`, which appear
        // everywhere and would produce exactly the confidently-wrong location this class exists to
        // prevent. A line must be long enough to identify a place before it may anchor one.
        for (Side side : new Side[]{Side.NEW, Side.OLD}) {
            List<NumberedLine> lines = sideLines(unifiedDiff, side, file);
            Optional<Anchor> hit = findDistinctive(lines, target, side);
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }

    /** Shortest line that can still identify a location. Below this, a "match" is a coincidence. */
    private static final int DISTINCTIVE_MIN_CHARS = 12;

    /**
     * Anchor on the longest quoted line that occurs in {@code lines} — longest first, because a
     * distinctive line is far less likely to appear twice than a short one. Lines below
     * {@link #DISTINCTIVE_MIN_CHARS} are never used, so a quote made only of braces stays unanchored.
     */
    private static Optional<Anchor> findDistinctive(List<NumberedLine> lines, List<String> target, Side side) {
        List<String> byLength = new ArrayList<>(target);
        byLength.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String candidate : byLength) {
            if (candidate.length() < DISTINCTIVE_MIN_CHARS) break;   // sorted: the rest are shorter
            for (NumberedLine l : lines) {
                if (!l.normalized().equals(candidate)) continue;
                // Found the anchor. Widen it to the other quoted lines sitting NEAR it, so a finding
                // about a block is reported as the block rather than as one line inside it. Bounded
                // by CLUSTER_SPAN: a quoted line that also appears somewhere far away is a different
                // occurrence, and stretching the range to swallow it would point at code the finding
                // is not about.
                int lo = l.lineNumber(), hi = l.lineNumber();
                for (NumberedLine other : lines) {
                    if (!target.contains(other.normalized())) continue;
                    int d = other.lineNumber() - l.lineNumber();
                    if (Math.abs(d) <= CLUSTER_SPAN) {
                        lo = Math.min(lo, other.lineNumber());
                        hi = Math.max(hi, other.lineNumber());
                    }
                }
                return Optional.of(new Anchor(lo, hi, side));
            }
        }
        return Optional.empty();
    }

    /** How far from the anchor another quoted line may sit and still be the same block. */
    private static final int CLUSTER_SPAN = 15;

    /**
     * Fallback for when the diff does not contain the quoted code — typically a whole-file review
     * (no meaningful diff), or a comment about a region the diff only touches at its edge.
     */
    public static Optional<Anchor> resolveInFile(String fileContent, String snippet) {
        List<String> target = normalizeSnippet(snippet);
        if (target.isEmpty() || fileContent == null || fileContent.isEmpty()) return Optional.empty();

        List<NumberedLine> lines = new ArrayList<>();
        String[] raw = fileContent.split("\n", -1);
        for (int i = 0; i < raw.length; i++) {
            lines.add(new NumberedLine(i + 1, normalizeLine(raw[i])));
        }
        return findRun(lines, target, Side.NEW);
    }

    /**
     * Expand a unified diff into (absolute line number, normalized text) for one side.
     *
     * <p>Context lines belong to BOTH sides, so they are emitted for each — that is what lets a snippet
     * made only of unchanged lines still anchor.
     */
    private static List<NumberedLine> sideLines(String unifiedDiff, Side side, String wantFile) {
        List<NumberedLine> out = new ArrayList<>();
        int lineNo = 0;
        boolean inHunk = false;
        String currentFile = null;
        boolean fileMatches = (wantFile == null);

        for (String raw : unifiedDiff.split("\n", -1)) {
            Matcher m = HUNK_HEADER.matcher(raw);
            if (m.find()) {
                lineNo = Integer.parseInt(side == Side.NEW ? m.group(3) : m.group(1));
                inHunk = true;
                continue;
            }
            // File headers are read BEFORE the in-hunk guard. They arrive before the first `@@`, so
            // testing `inHunk` first skips the opening file's header entirely — which left the first
            // file in every diff unattributed while later files worked.
            if (raw.startsWith("diff ") || raw.startsWith("index ")
                    || raw.startsWith("--- ") || raw.startsWith("+++ ")) {
                inHunk = false;                      // next file's header block
                // Track WHICH file the coming hunks belong to. `+++ b/path` names the new side,
                // `--- a/path` the old; either identifies the file for our purposes.
                if (raw.startsWith("+++ ") || raw.startsWith("--- ")) {
                    String p = raw.substring(4).trim();
                    if (!p.equals("/dev/null")) {
                        if (p.length() > 2 && (p.startsWith("a/") || p.startsWith("b/"))) p = p.substring(2);
                        int tab = p.indexOf('\t');
                        if (tab >= 0) p = p.substring(0, tab);
                        currentFile = p;
                        fileMatches = (wantFile == null) || samePath(currentFile, wantFile);
                    }
                }
                continue;
            }
            if (!inHunk) continue;
            if (!fileMatches) continue;
            if (raw.startsWith("\\")) continue;      // "\ No newline at end of file"
            if (raw.isEmpty()) continue;

            char marker = raw.charAt(0);
            String content = raw.substring(1);
            boolean belongsToSide = marker == ' '
                    || (side == Side.NEW && marker == '+')
                    || (side == Side.OLD && marker == '-');
            boolean consumesLine = belongsToSide;

            if (belongsToSide) out.add(new NumberedLine(lineNo, normalizeLine(content)));
            if (consumesLine) lineNo++;
        }
        return out;
    }

    /** Lenient path comparison — the model may name a bare filename or a partial path. */
    private static boolean samePath(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        return a.endsWith("/" + b) || b.endsWith("/" + a);
    }

    /** First contiguous run of {@code target} inside {@code lines}. */
    private static Optional<Anchor> findRun(List<NumberedLine> lines, List<String> target, Side side) {
        if (target.size() > lines.size()) return Optional.empty();
        for (int i = 0; i <= lines.size() - target.size(); i++) {
            boolean all = true;
            for (int j = 0; j < target.size(); j++) {
                if (!lines.get(i + j).normalized().equals(target.get(j))) { all = false; break; }
            }
            if (all) {
                // Contiguity in the LIST is not contiguity in the FILE: a hunk boundary can sit between
                // two entries. Anchor to the real first/last line numbers either way.
                return Optional.of(new Anchor(
                        lines.get(i).lineNumber(), lines.get(i + target.size() - 1).lineNumber(), side));
            }
        }
        return Optional.empty();
    }

    /**
     * Reduce a quoted snippet to comparable lines.
     *
     * <p>Models routinely paste the diff's own {@code +}/{@code -} markers into the snippet, and indent
     * inconsistently when re-typing rather than copying. Both are formatting noise, not disagreement
     * about which code is meant, so both are normalized away. Blank lines are dropped so a stray
     * trailing newline does not defeat the match.
     */
    private static List<String> normalizeSnippet(String snippet) {
        List<String> out = new ArrayList<>();
        if (snippet == null) return out;
        for (String raw : snippet.split("\n", -1)) {
            String line = raw;
            if (!line.isEmpty() && (line.charAt(0) == '+' || line.charAt(0) == '-')) {
                // Only when it looks like a diff marker rather than real code (unary minus, `--x`).
                String rest = line.substring(1);
                if (rest.isBlank() || Character.isWhitespace(rest.charAt(0)) || !isOperatorContext(rest)) {
                    line = rest;
                }
            }
            String n = normalizeLine(line);
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    /** True when what follows a leading +/- reads as an expression, i.e. the sign is real code. */
    private static boolean isOperatorContext(String rest) {
        char c = rest.charAt(0);
        return Character.isDigit(c) || c == '+' || c == '-' || c == '.';
    }

    /** Collapse internal whitespace runs and trim, so indentation drift does not defeat a match. */
    private static String normalizeLine(String s) {
        return s.strip().replaceAll("\\s+", " ");
    }

    private DiffAnchor() { }
}
