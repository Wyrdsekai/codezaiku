package org.codezaiku.shape;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The derived structure map: the real file tree on disk, recomputed every turn and pinned into
 * the system message. Because it is DERIVED (never narrated), it is self-healing and can never
 * drift away from ground truth — strictly better than an LLM-summarized memory (RESET §3.3).
 *
 * <p>Slice 1 = file tree + sizes. Symbols (tree-sitter) and the duplicate/module-conflict warning
 * are a later slice; this is the honest floor.
 */
public final class ProjectShape {
    private static final Set<String> IGNORE_DIRS = Set.of(
            ".git", "build", ".gradle", "node_modules", "target", "__pycache__",
            ".venv", "venv", "dist", ".idea", ".mvn", "bin", "obj");
    private static final int MAX_ENTRIES = 400;

    private ProjectShape() {
    }

    /**
     * Render fitted to a character budget — what the caller can afford to spend on this block.
     *
     * <p>The entry cap alone is not a bound the context window can rely on: it counts entries, and a
     * deep tree pays for path length too. Measured on a 926-file repository, the block came to
     * ~31,900 characters, which is more than half of a 16k-token window in a section that is
     * reassembled every turn and never compacted — so on a large repository the loop could not land a
     * single call, whatever the task was. Sized against the window instead, it degrades: fewer
     * entries, then fewer signatures, and it always says what it left out.
     */
    public static String render(Path root, int maxChars) {
        String full = render(root);
        if (maxChars <= 0 || full.length() <= maxChars) return full;
        return fitTo(full, maxChars);
    }

    /**
     * Trim from the END, which is where the cheapest detail is: the tail of the file list and then
     * the per-file signatures. The header and the build/language facts come first and are what the
     * model needs to place a file at all, so they survive.
     */
    static String fitTo(String full, int maxChars) {
        String note = "\n...[shortened to fit the model's context window]";
        int keep = Math.max(0, maxChars - note.length());
        if (keep == 0) return note.strip();
        String head = full.substring(0, Math.min(full.length(), keep));
        int lastLine = head.lastIndexOf('\n');          // never end mid-path
        if (lastLine > 0) head = head.substring(0, lastLine);
        return head + note;
    }

    /** Render the current tree of {@code root} as a pinned text block. */
    public static String render(Path root) {
        Path base = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            return "PROJECT FILES (relative to project root):\n(no such directory)";
        }
        List<Path> entries = walkPruned(base);
        entries.sort(Comparator.comparing(p -> base.relativize(p).toString()));
        List<String> lines = new ArrayList<>();
        for (Path p : entries) {
            if (lines.size() >= MAX_ENTRIES) break;
            String rel = base.relativize(p).toString();
            if (Files.isDirectory(p)) lines.add(rel + "/");
            else lines.add(rel + "  (" + sizeOf(p) + ")");
        }
        String header = "PROJECT FILES (relative to project root):";
        if (lines.isEmpty()) return ProjectFacts.render(base) + header + "\n(empty — nothing built yet)";
        if (lines.size() >= MAX_ENTRIES) lines.add("...[truncated; >" + MAX_ENTRIES + " entries]");
        return ProjectFacts.render(base) + header + "\n" + String.join("\n", lines)
                + signaturesBlock(base) + conflictsBlock(base);
    }

    /**
     * Per-file declaration signatures (SPEC_CODEZAIKU_PROJECT_MEMORY §5 pinned tier). Derived every
     * turn, so the model sees the current shape of its OWN code without re-reading files after a
     * compaction. Source files only; capped to stay lean in the small window.
     */
    private static String signaturesBlock(Path base) {
        int maxFiles = 40;
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        List<Path> files = walkPruned(base).stream().filter(Files::isRegularFile)
                .sorted(Comparator.comparing(p -> base.relativize(p).toString())).toList();
        for (Path p : files) {
            List<String> sigs = Signatures.of(p);
            if (sigs.isEmpty()) continue;
            if (shown == 0) sb.append("\n\nFILE SIGNATURES (derived — the current shape of your code; "
                    + "do NOT re-read these files just to recall what you wrote):");
            sb.append("\n").append(base.relativize(p)).append(":");
            for (String s : sigs) sb.append("\n  ").append(s);
            if (++shown >= maxFiles) {
                sb.append("\n…[more files]");
                break;
            }
        }
        return sb.toString();
    }

    /** The one bounded, pruned, churn-tolerant walk every shape reader uses (see {@link TreeWalk}). */
    private static List<Path> walkPruned(Path base) {
        return TreeWalk.entries(base, IGNORE_DIRS);
    }

    private static String conflictsBlock(Path base) {
        List<String> warnings = Conflicts.detect(base);
        if (warnings.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "\n\n⚠ STRUCTURE CONFLICTS (the project is self-inconsistent — fix these):");
        for (String w : warnings) sb.append("\n - ").append(w);
        return sb.toString();
    }

    private static boolean notIgnored(Path p) {
        for (Path part : p) {
            if (IGNORE_DIRS.contains(part.toString())) return false;
        }
        return true;
    }

    private static String sizeOf(Path p) {
        try {
            long b = Files.size(p);
            if (b < 1024) return b + "B";
            if (b < 1024 * 1024) return (b / 1024) + "K";
            return (b / (1024 * 1024)) + "M";
        } catch (IOException e) {
            return "?";
        }
    }
}
