package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.codezaiku.lsp.LspClient;

/**
 * Unique-match string replacement (the SWE-agent edit primitive) with a FORGIVING fallback: a 9B
 * routinely reproduces a target block with slightly-off indentation/trailing whitespace, which an
 * exact matcher rejects ("old_string not found") and burns turns. So if the exact match misses, we
 * retry on a whitespace-normalized, line-aligned basis (smallcode/opencode finding: forgiving edits
 * are the single highest-ROI small-model ACI fix). On a miss we return a CORRECTIVE error with the
 * nearest near-miss so the next turn can self-correct, rather than a bare "not found".
 */
public final class EditFileTool implements Tool {
    private final PathScope scope;
    private final LspClient lsp; // nullable — enables the symbol-span fallback (push)

    public EditFileTool(PathScope scope) {
        this(scope, null);
    }

    public EditFileTool(PathScope scope, LspClient lsp) {
        this.scope = scope;
        this.lsp = lsp;
    }

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "Replace an exact unique occurrence of old_string with new_string in a file. "
                + "old_string must match exactly once; include surrounding context to make it unique.";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("path").put("type", "string");
        props.putObject("old_string").put("type", "string");
        props.putObject("new_string").put("type", "string");
        p.putArray("required").add("path").add("old_string").add("new_string");
        return p;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String rel = args.path("path").asText();
        String oldS = args.path("old_string").asText();
        String newS = args.path("new_string").asText();
        Path f = scope.resolve(rel);
        String content;
        if (ContainerExec.active()) {                       // container-exec mode (env-gated); off by default
            content = ContainerExec.read(rel);
            if (content == null) return "ERROR: no such file: " + rel;
        } else {
            if (!Files.exists(f)) return "ERROR: no such file: " + rel;
            content = Files.readString(f);
        }
        if (oldS.isEmpty()) return "ERROR: old_string is empty";

        // 1) exact unique match — the strict, lossless path
        int first = content.indexOf(oldS);
        if (first >= 0) {
            if (content.indexOf(oldS, first + 1) >= 0) {
                EditTelemetry.record(rel, oldS, newS, content, "not_unique");
                return "ERROR: old_string is not unique in " + rel + " — add more surrounding context.";
            }
            EditTelemetry.record(rel, oldS, newS, content, "exact");
            writeBack(f, rel, content.substring(0, first) + newS + content.substring(first + oldS.length()));
            return "edited " + rel + SyntaxCheck.check(scope, rel);
        }

        // A fuzzy match is a GUESS at where the model meant to edit. If applying it would newly unbalance
        // the file's braces/brackets, it almost certainly landed on the wrong span and would CORRUPT the
        // structure (the battery2 java-n3 failure: an anchored match ate a brace → "class/interface
        // expected" → a 1010-line app that wouldn't compile, killed by ONE bad edit). So we VALIDATE each
        // fuzzy candidate before committing and ROLL BACK (skip it) on a structural regression, falling
        // through to the corrective near-miss instead of writing corruption. The exact path (1) is lossless
        // and intentional, so it's never gated. (opencode/continue both validate post-fuzzy-apply.)
        boolean refusedCorrupting = false;

        // 2) forgiving fallback — match line-aligned, ignoring per-line leading/trailing whitespace
        String result = forgivingReplace(content, oldS, newS);
        if (result != null) {
            if (newlyUnbalanced(content, result)) refusedCorrupting = true;
            else {
                EditTelemetry.record(rel, oldS, newS, content, "whitespace");
                writeBack(f, rel, result);
                return "edited " + rel + " (matched ignoring whitespace)" + SyntaxCheck.check(scope, rel);
            }
        }

        // 2b) anchored fallback — match on old_string's FIRST and LAST lines (each unique) and replace
        // the span between them. Tolerant of the model getting MIDDLE lines wrong (dropping/reordering/
        // paraphrasing) — the dominant edit-miss in practice. Language-agnostic (operates on lines).
        result = anchoredReplace(content, oldS, newS);
        if (result != null) {
            if (newlyUnbalanced(content, result)) refusedCorrupting = true;
            else {
                EditTelemetry.record(rel, oldS, newS, content, "anchor");
                writeBack(f, rel, result);
                return "edited " + rel + " (matched by first/last-line anchors)" + SyntaxCheck.check(scope, rel);
            }
        }

        // 2b2) first-line-anchored fallback — old_string's FIRST line matches exactly one file line; replace
        // that line plus the next (oldN-1) lines (old_string's own line count). Tolerant of the model getting
        // the END of a multi-line block wrong (the complement of the first/last-anchor case: it nailed the
        // opening line but mis-remembered the tail). Balance-guarded like the others; bounded span (≤10 lines)
        // and oldN≥2 so it never fires on a single-line case the forgiving path already covers.
        result = firstAnchoredReplace(content, oldS, newS);
        if (result != null) {
            if (newlyUnbalanced(content, result)) refusedCorrupting = true;
            else {
                EditTelemetry.record(rel, oldS, newS, content, "first_anchor");
                writeBack(f, rel, result);
                return "edited " + rel + " (matched by first-line anchor)" + SyntaxCheck.check(scope, rel);
            }
        }

        // 2c) LSP symbol-span fallback (PUSH, not a tool the 9B must opt into): if old_string targets a
        // whole function/type the language server knows, replace that symbol's EXACT span — no text
        // reproduction needed. This auto-recovers the dominant miss (the model editing a whole function
        // with edit_file and mis-remembering its body) without requiring it to call replace_symbol.
        String bySymbol = symbolReplace(f, content, oldS, newS);
        if (bySymbol != null) {
            if (newlyUnbalanced(content, bySymbol)) refusedCorrupting = true;
            else {
                EditTelemetry.record(rel, oldS, newS, content, "symbol");
                writeBack(f, rel, bySymbol);
                return "edited " + rel + " (matched whole symbol via the language server)" + SyntaxCheck.check(scope, rel);
            }
        }

        // 3) corrective error: show the ACTUAL current text around the closest region so the next turn
        // copies it exactly instead of re-guessing (aider pattern).
        String note = refusedCorrupting
                ? "\n(A fuzzy match WAS found but was REFUSED: applying it would unbalance the braces/brackets "
                + "in this file and corrupt its structure. The file is unchanged. Copy old_string EXACTLY "
                + "from the current lines below — do not paraphrase the middle.)"
                : "";
        EditTelemetry.record(rel, oldS, newS, content, refusedCorrupting ? "refused" : "not_found");
        return "ERROR: old_string not found in " + rel + note + nearMiss(content, oldS);
    }

    /**
     * True if {@code before} is bracket-balanced but {@code after} is not — i.e. the edit introduced an
     * imbalance, the signature of a fuzzy match that landed wrong and ate/added a delimiter. Conservative
     * by design: when {@code before} is already unbalanced (a file mid-construction, or one with braces in
     * string/comment literals), the guard disables itself (returns false) rather than risk a false refusal.
     * So it only blocks edits we're confident corrupt a previously-valid structure. Language-general
     * (every C-family/Rust/JS/Java/Python source balances {}/()/[]).
     */
    /**
     * Write the edited content back — to the container (env-gated mode) or the host file.
     *
     * <p>An instance method rather than a static one so it can record the write in the run's ledger:
     * all five edit paths funnel through here, so the ledger cannot miss one by someone adding a
     * sixth caller that forgets to record.
     */
    private void writeBack(Path f, String rel, String content) throws IOException {
        if (ContainerExec.active()) ContainerExec.write(rel, content);
        else Files.writeString(f, content);
        scope.recordWrite(rel);
    }

    static boolean newlyUnbalanced(String before, String after) {
        return isBalanced(before) && !isBalanced(after);
    }

    private static boolean isBalanced(String s) {
        int curly = 0, paren = 0, square = 0;
        for (int i = 0; i < s.length(); i++) {
            switch (s.charAt(i)) {
                case '{' -> curly++;
                case '}' -> curly--;
                case '(' -> paren++;
                case ')' -> paren--;
                case '[' -> square++;
                case ']' -> square--;
                default -> { }
            }
            if (curly < 0 || paren < 0 || square < 0) return false; // closed before opened
        }
        return curly == 0 && paren == 0 && square == 0;
    }

    /**
     * Replace the unique block of file lines whose whitespace-normalized form equals old_string's,
     * preserving the file's surrounding text. Returns null if zero or multiple normalized matches.
     */
    private static String forgivingReplace(String content, String oldS, String newS) {
        String[] fileLines = content.split("\n", -1);
        String[] oldLines = oldS.split("\n", -1);
        // a trailing empty element from a terminating newline isn't part of the block to match
        int n = oldLines.length;
        while (n > 0 && oldLines[n - 1].isEmpty()) n--;
        if (n == 0) return null;

        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + n <= fileLines.length; i++) {
            boolean ok = true;
            for (int k = 0; k < n; k++) {
                if (!fileLines[i + k].strip().equals(oldLines[k].strip())) {
                    ok = false;
                    break;
                }
            }
            if (ok) starts.add(i);
        }
        if (starts.size() != 1) return null; // must be unambiguous

        int s = starts.get(0);
        return spliceLines(fileLines, s, s + n - 1, reindent(newS, leadingWs(fileLines[s]), leadingWs(oldLines[0])));
    }

    /**
     * Anchored fallback: old_string's first and last lines must EACH match exactly one file line; we
     * then replace the whole span between them. This lands edits where the model got the middle of a
     * multi-line block wrong (the dominant miss — e.g. it dropped two of five sysinfo refresh calls).
     * Guardrailed: both anchors unique, end after start, and the matched span size near old_string's so
     * we never silently eat an unintended region. Returns null if not confidently resolvable.
     */
    private static String anchoredReplace(String content, String oldS, String newS) {
        String[] fileLines = content.split("\n", -1);
        String[] oldLines = oldS.split("\n", -1);
        int n = oldLines.length;
        while (n > 0 && oldLines[n - 1].isEmpty()) n--;
        if (n < 2) return null; // need a distinct first and last line to anchor
        String firstA = oldLines[0].strip();
        String lastA = oldLines[n - 1].strip();
        if (firstA.isEmpty() || lastA.isEmpty()) return null;
        int s = uniqueLine(fileLines, firstA);
        int e = uniqueLine(fileLines, lastA);
        if (s < 0 || e < 0 || e < s) return null;
        int span = e - s + 1;
        if (Math.abs(span - n) > 6) return null; // span must be close to old's line count (anti-overreach)
        return spliceLines(fileLines, s, e, reindent(newS, leadingWs(fileLines[s]), leadingWs(oldLines[0])));
    }

    /**
     * First-line-anchored fallback: old_string's first line matches exactly one file line; replace the span
     * of old_string's own line count starting there. Lands the "first line right, tail wrong" miss. Bounded
     * (2..10 old lines) and the anchor must be unique, so it never silently eats a large unintended region;
     * the caller's balance check rejects any candidate that corrupts the bracket structure. Returns null when
     * not confidently resolvable.
     */
    private static String firstAnchoredReplace(String content, String oldS, String newS) {
        String[] fileLines = content.split("\n", -1);
        String[] oldLines = oldS.split("\n", -1);
        int n = oldLines.length;
        while (n > 0 && oldLines[n - 1].isEmpty()) n--;
        if (n < 2 || n > 10) return null;               // 1-line handled by forgiving; cap blast radius
        String firstA = oldLines[0].strip();
        if (firstA.isEmpty()) return null;
        int s = uniqueLine(fileLines, firstA);
        if (s < 0 || s + n > fileLines.length) return null;
        return spliceLines(fileLines, s, s + n - 1, reindent(newS, leadingWs(fileLines[s]), leadingWs(oldLines[0])));
    }

    /**
     * Symbol-span fallback: if old_string is a DEFINITION (starts with a decl keyword) naming exactly
     * one symbol the language server knows, replace that symbol's exact line span with new_string. The
     * decl-keyword gate prevents triggering on a mere call line that happens to mention the name.
     * Returns null when not confidently a whole-symbol rewrite. Language-general (the span is the LSP's).
     */
    private String symbolReplace(Path file, String content, String oldS, String newS) {
        if (lsp == null) return null;
        String firstLine = firstNonBlank(oldS);
        if (firstLine.isEmpty() || !looksLikeDef(firstLine)) return null;
        var syms = lsp.symbols(file, content);
        if (syms.isEmpty()) return null;
        Set<String> names = new HashSet<>();
        for (var s : syms) names.add(s.name());
        List<String> hits = new ArrayList<>();
        for (String tok : firstLine.split("[^A-Za-z0-9_]+")) {
            if (!tok.isEmpty() && names.contains(tok) && !hits.contains(tok)) hits.add(tok);
        }
        if (hits.size() != 1) return null; // 0 or ambiguous
        String name = hits.get(0);
        var matching = syms.stream().filter(s -> s.name().equals(name)).toList();
        if (matching.size() != 1) return null; // not unique in the file
        var sym = matching.get(0);
        String[] fileLines = content.split("\n", -1);
        if (sym.startLine() < 0 || sym.endLine() >= fileLines.length || sym.endLine() < sym.startLine()) {
            return null;
        }
        String reindented = reindent(newS, leadingWs(fileLines[sym.startLine()]), leadingWs(firstNonBlank(newS)));
        return spliceLines(fileLines, sym.startLine(), sym.endLine(), reindented);
    }

    private static String firstNonBlank(String s) {
        for (String l : s.split("\n", -1)) if (!l.isBlank()) return l.strip();
        return "";
    }

    /** First line looks like a symbol DEFINITION (not a call) — the safety gate for symbol-replace. */
    private static boolean looksLikeDef(String line) {
        String t = line.strip();
        for (String kw : new String[]{"fn ", "pub fn", "pub(crate) fn", "async fn", "const fn", "def ",
                "async def", "func ", "fun ", "class ", "struct ", "enum ", "trait ", "impl ", "interface ",
                "type ", "public ", "private ", "protected ", "static ", "internal "}) {
            if (t.startsWith(kw)) return true;
        }
        return false;
    }

    /** Index of the single file line whose stripped text equals {@code target}, or -1 if 0 or >1. */
    private static int uniqueLine(String[] lines, String target) {
        int found = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].strip().equals(target)) {
                if (found >= 0) return -1; // ambiguous
                found = i;
            }
        }
        return found;
    }

    /** Replace file lines [from..to] (inclusive) with {@code replacement}, preserving the rest exactly. */
    private static String spliceLines(String[] fileLines, int from, int to, String replacement) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < from; i++) sb.append(fileLines[i]).append('\n');
        sb.append(replacement);
        if (!replacement.endsWith("\n") && to + 1 < fileLines.length) sb.append('\n');
        for (int i = to + 1; i < fileLines.length; i++) {
            sb.append(fileLines[i]);
            if (i < fileLines.length - 1) sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Re-indent new_string to the matched block's indentation. The model usually supplied a de-indented
     * old_string (why exact missed), so its new_string is de-indented too; writing that verbatim breaks
     * indentation-sensitive languages (Python). Shift each line by the file's leading whitespace beyond
     * old_string's — preserving relative nesting. Language-agnostic.
     */
    private static String reindent(String newS, String leadFile, String leadOld) {
        if (!leadFile.startsWith(leadOld) || leadFile.length() <= leadOld.length()) return newS;
        String pad = leadFile.substring(leadOld.length());
        String[] nl = newS.split("\n", -1);
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < nl.length; i++) {
            if (!nl[i].isEmpty()) r.append(pad);
            r.append(nl[i]);
            if (i < nl.length - 1) r.append('\n');
        }
        return r.toString();
    }

    private static String leadingWs(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return line.substring(0, i);
    }

    /**
     * Corrective error (aider pattern): find the file region most similar to old_string and show the
     * model its ACTUAL current text (with line numbers), so its retry is a COPY, not a re-guess —
     * the failure mode is the model misremembering the block, so handing it the real text is the fix.
     */
    private static String nearMiss(String content, String oldS) {
        String[] fileLines = content.split("\n", -1);
        Set<String> oldToks = new HashSet<>();
        for (String l : oldS.split("\n")) for (String w : l.strip().split("\\s+")) if (!w.isEmpty()) oldToks.add(w);
        if (oldToks.isEmpty()) return " — old_string is blank; read_file and copy an exact span.";

        int oldN = Math.max(1, (int) oldS.lines().filter(l -> !l.isBlank()).count());
        int bestStart = -1, bestScore = -1;
        for (int i = 0; i < fileLines.length; i++) {
            int sc = 0;
            for (int k = i; k < Math.min(fileLines.length, i + oldN); k++) {
                for (String w : fileLines[k].strip().split("\\s+")) if (oldToks.contains(w)) sc++;
            }
            if (sc > bestScore) { bestScore = sc; bestStart = i; }
        }
        if (bestStart < 0 || bestScore <= 0) {
            return " — that text isn't in the file. read_file it and copy an exact span before editing.";
        }
        int from = Math.max(0, bestStart - 2);
        int to = Math.min(fileLines.length - 1, bestStart + oldN + 2);
        StringBuilder w = new StringBuilder("\nThe closest region in the file is below — copy old_string "
                + "EXACTLY from these current lines (verbatim, including indentation):\n");
        for (int i = from; i <= to; i++) w.append(i + 1).append(": ").append(fileLines[i]).append('\n');
        return w.toString();
    }
}
