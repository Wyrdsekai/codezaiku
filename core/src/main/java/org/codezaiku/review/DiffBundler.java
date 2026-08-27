package org.codezaiku.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Splits a unified diff into review-sized units.
 *
 * <p>Measured, and the reason this exists: on a stratified sample of 25 real PRs, <b>12 of them had
 * diffs larger than the prompt's inline budget</b> and the model was shown only the first part of the
 * change. Half the sample was reviewed blind past the truncation point, which is the largest known
 * drag on recall and has nothing to do with the model's ability.
 *
 * <p>The fix is the one OpenCodeReview uses: stop trying to fit a whole change into one prompt. A diff
 * splits cleanly at file boundaries, and each file's hunk headers already carry absolute line numbers,
 * so a per-file diff anchors exactly as well as the whole one. Small files are packed together so a
 * 40-file change does not become 40 model calls.
 *
 * <p>Bundling is only worth its cost when the diff actually overflows — for a change that already
 * fits, one pass over the whole thing gives the model the most context and costs the least.
 */
public final class DiffBundler {

    /** A file's slice of the diff, with its path for reporting. */
    public record Bundle(List<String> files, String diff) {
        public int size() { return diff.length(); }
    }

    /**
     * Split {@code unifiedDiff} into bundles no larger than {@code budgetChars} where possible.
     *
     * <p>A single file bigger than the budget is returned ALONE and oversized rather than cut: the
     * caller truncates it with a marker, which is honest, whereas splitting a file mid-hunk would
     * produce a diff that no longer parses and would silently break anchoring.
     */
    public static List<Bundle> bundle(String unifiedDiff, int budgetChars) {
        return bundle(unifiedDiff, budgetChars, new ArrayList<>());
    }

    /**
     * As {@link #bundle(String, int)}, recording into {@code filteredOut} any file skipped as
     * generated. The caller reports it — a file dropped silently is indistinguishable from a file
     * reviewed and found clean.
     */
    public static List<Bundle> bundle(String unifiedDiff, int budgetChars, List<String> filteredOut) {
        Map<String, String> byFile = splitByFile(unifiedDiff);
        byFile.keySet().removeIf(f -> {
            if (!isGenerated(f)) return false;
            filteredOut.add(f);
            return true;
        });
        List<Bundle> out = new ArrayList<>();
        if (byFile.isEmpty()) return out;

        List<String> curFiles = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (Map.Entry<String, String> e : byFile.entrySet()) {
            String piece = e.getValue();
            if (!curFiles.isEmpty() && cur.length() + piece.length() > budgetChars) {
                out.add(new Bundle(List.copyOf(curFiles), cur.toString()));
                curFiles = new ArrayList<>();
                cur = new StringBuilder();
            }
            curFiles.add(e.getKey());
            cur.append(piece);
        }
        if (!curFiles.isEmpty()) out.add(new Bundle(List.copyOf(curFiles), cur.toString()));
        return out;
    }

    /**
     * Break a diff into one text per file, preserving each file's own header block so every piece is
     * a VALID unified diff on its own — {@link DiffAnchor} parses the pieces exactly as it parses the
     * whole, and the hunk headers still carry absolute line numbers.
     */
    static Map<String, String> splitByFile(String unifiedDiff) {
        Map<String, String> out = new LinkedHashMap<>();
        if (unifiedDiff == null || unifiedDiff.isBlank()) return out;

        String path = null;
        StringBuilder buf = new StringBuilder();
        for (String line : unifiedDiff.split("\n", -1)) {
            // `diff --git` opens a file block. Some diffs omit it and lead with `---`, so a `---`
            // that arrives when a block is already underway also starts a new file.
            boolean starts = line.startsWith("diff --git ")
                    || (line.startsWith("--- ") && buf.length() > 0 && !inHeader(buf));
            if (starts) {
                flush(out, path, buf);
                buf.setLength(0);
                path = null;
            }
            buf.append(line).append('\n');
            if (path == null && line.startsWith("+++ ")) path = cleanPath(line.substring(4));
            if (path == null && line.startsWith("--- ")) {
                String p = cleanPath(line.substring(4));
                if (!p.equals("/dev/null")) path = p;      // a new file has --- /dev/null
            }
        }
        flush(out, path, buf);
        return out;
    }

    /** True while the buffer still holds only this file's header lines (no hunk seen yet). */
    private static boolean inHeader(StringBuilder buf) {
        return !buf.toString().contains("\n@@");
    }

    private static void flush(Map<String, String> out, String path, StringBuilder buf) {
        if (buf.length() == 0) return;
        String key = path != null ? path : "file" + out.size();
        // A path can legitimately appear twice (a rename shows both sides); keep both.
        out.merge(key, buf.toString(), String::concat);
    }

    private static String cleanPath(String raw) {
        String p = raw.trim();
        int tab = p.indexOf('\t');
        if (tab >= 0) p = p.substring(0, tab);
        if (p.length() > 2 && (p.startsWith("a/") || p.startsWith("b/"))) p = p.substring(2);
        return p;
    }


    /**
     * Files that are machine-generated and not worth a reviewer's attention.
     *
     * <p>This is not a nicety. A real pandas PR came to 676k of diff, of which <b>599k was a single
     * {@code pixi.lock}</b> — 88% of the change, generated, and it consumed the entire prompt budget
     * while pushing the actual code out. Filtering it is the difference between reviewing the change
     * and reviewing a lockfile.
     *
     * <p>Deliberately conservative: it matches lock/checksum files, vendored trees and minified
     * bundles, and nothing that a human plausibly hand-edits. A false positive here silently removes
     * code from review, which is far worse than spending a few tokens on a generated file.
     */
    static boolean isGenerated(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        String base = p.substring(p.lastIndexOf('/') + 1);
        if (base.endsWith(".lock") || base.endsWith(".sum")) return true;
        switch (base) {
            case "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "poetry.lock", "pixi.lock",
                 "cargo.lock", "composer.lock", "gemfile.lock", "go.sum", "flake.lock":
                return true;
            default:
                break;
        }
        if (p.contains("/vendor/") || p.startsWith("vendor/")) return true;
        if (p.contains("/node_modules/")) return true;
        if (p.endsWith(".min.js") || p.endsWith(".min.css") || p.endsWith(".map")) return true;
        if (p.contains("/generated/") || p.endsWith(".pb.go") || p.endsWith("_pb2.py")) return true;
        return false;
    }

    private DiffBundler() { }
}
