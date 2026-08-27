package org.codezaiku.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import org.codezaiku.Config;

/**
 * Track B edit-precision telemetry (env-gated, off by default).
 *
 * The display log truncates every tool call to ~110 chars, so the drive's FULL {@code old_string} /
 * {@code new_string} — the exact signal Track B fine-tunes on — is never persisted. This records it,
 * losslessly, one JSON line per edit_file execution, gated on {@code CODEZAIKU_EDIT_TELEMETRY}=&lt;path&gt;.
 * When the var is unset the whole class is a single null-check and a return: zero cost, zero risk to the
 * hot edit path (same discipline as the dev-gate).
 *
 * Each row is the raw material for a self-supervised DPO/KTO pair (PLAN_TRACKB_DESIGN §Data):
 *   outcome=exact|whitespace|anchor|first_anchor|symbol  → the old_string LANDED (a positive / "chosen")
 *   outcome=not_found|refused|not_unique                 → it MISSED (a negative / "rejected"); the
 *                                                          windowed `context` holds the TRUE nearby text
 *                                                          (the chosen target) so no re-read is needed.
 * We store a bounded ±{@link #WINDOW}-line window around the best token-overlap region rather than the
 * whole file (django sources run thousands of lines); the region is computed the same way the corrective
 * near-miss picks what to show the model, so success and failure rows are windowed consistently.
 */
public final class EditTelemetry {
    private EditTelemetry() { }

    private static final ObjectMapper J = new ObjectMapper();
    private static final int WINDOW = 60; // file lines of context on each side of the best-match region

    /** Append one telemetry row for an edit_file call. No-op unless CODEZAIKU_EDIT_TELEMETRY is set. */
    static void record(String path, String oldS, String newS, String content, String outcome) {
        String sink = Config.get("CODEZAIKU_EDIT_TELEMETRY");
        if (sink == null || sink.isBlank()) return;
        try {
            String[] fileLines = content.split("\n", -1);
            int[] region = bestRegion(fileLines, oldS);        // {start, end} inclusive, or {-1,-1}
            int winStart = region[0] < 0 ? 0 : Math.max(0, region[0] - WINDOW);
            int winEnd = region[1] < 0
                    ? Math.min(fileLines.length - 1, WINDOW * 2)
                    : Math.min(fileLines.length - 1, region[1] + WINDOW);

            StringBuilder ctx = new StringBuilder();
            for (int i = winStart; i <= winEnd && i < fileLines.length; i++) {
                ctx.append(fileLines[i]);
                if (i < winEnd) ctx.append('\n');
            }

            ObjectNode row = J.createObjectNode();
            row.put("path", path);
            row.put("outcome", outcome);
            row.put("old_string", oldS);
            row.put("new_string", newS);
            row.put("file_lines", fileLines.length);
            row.put("region_start", region[0]);   // 0-based inclusive line of the matched/near-miss region
            row.put("region_end", region[1]);
            row.put("window_start", winStart);     // 0-based line where `context` begins
            row.put("context", ctx.toString());    // the true surrounding text (the DPO "chosen" evidence)

            Path out = Path.of(sink);
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            synchronized (EditTelemetry.class) {
                Files.writeString(out, J.writeValueAsString(row) + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception ignore) {
            // telemetry must never perturb a real run
        }
    }

    /**
     * The file line range [start,end] whose token overlap with old_string is highest — the block the drive
     * meant to edit. For a clean match this IS the matched block; for a miss it's the nearest region (the
     * text the drive should have copied). Mirrors {@code EditFileTool.nearMiss}'s scan so rows are consistent.
     * Returns {-1,-1} when old_string is blank or nothing overlaps.
     */
    private static int[] bestRegion(String[] fileLines, String oldS) {
        Set<String> oldToks = new HashSet<>();
        for (String l : oldS.split("\n")) {
            for (String w : l.strip().split("\\s+")) if (!w.isEmpty()) oldToks.add(w);
        }
        if (oldToks.isEmpty()) return new int[]{-1, -1};
        int oldN = Math.max(1, (int) oldS.lines().filter(l -> !l.isBlank()).count());
        int bestStart = -1, bestScore = -1;
        for (int i = 0; i < fileLines.length; i++) {
            int sc = 0;
            for (int k = i; k < Math.min(fileLines.length, i + oldN); k++) {
                for (String w : fileLines[k].strip().split("\\s+")) if (oldToks.contains(w)) sc++;
            }
            if (sc > bestScore) { bestScore = sc; bestStart = i; }
        }
        if (bestStart < 0 || bestScore <= 0) return new int[]{-1, -1};
        return new int[]{bestStart, Math.min(fileLines.length - 1, bestStart + oldN - 1)};
    }
}
