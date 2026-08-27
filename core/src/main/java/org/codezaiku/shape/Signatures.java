package org.codezaiku.shape;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Per-file DECLARATION signatures, derived from disk (SPEC_CODEZAIKU_PROJECT_MEMORY §5 pinned tier:
 * "source roots + file tree + per-file signatures"). This is the load-bearing half of the pinned
 * structure map that was missing: with the model's own functions/types/impls pinned every turn, it
 * does not have to RE-READ its own files after each compaction to recall what it already wrote — the
 * single biggest source of turn-burn observed in the rust-monitor runs. Derived ⇒ self-healing, never
 * drifts. Language-general (declaration shapes per extension); empty for unknown types.
 */
public final class Signatures {
    private static final int MAX_PER_FILE = 30;

    private Signatures() {
    }

    /** Trimmed top-level declaration lines of {@code file}, capped; empty if none/unsupported. */
    public static List<String> of(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String lang = lang(name);
        if (lang == null) return List.of();
        List<String> out = new ArrayList<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (Exception e) {
            return List.of();
        }
        for (String line : lines) {
            String t = line.strip();
            if (isDecl(t, lang)) {
                String s = t.endsWith("{") ? t.substring(0, t.length() - 1).strip() : t;
                if (s.length() > 160) s = s.substring(0, 160) + " …";
                out.add(s);
                if (out.size() >= MAX_PER_FILE) {
                    out.add("…[more]");
                    break;
                }
            }
        }
        return out;
    }

    private static String lang(String name) {
        if (name.endsWith(".rs")) return "rust";
        if (name.endsWith(".py")) return "python";
        if (name.endsWith(".java")) return "java";
        if (name.endsWith(".go")) return "go";
        if (name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".js")
                || name.endsWith(".jsx") || name.endsWith(".mjs")) return "js";
        if (name.endsWith(".gd")) return "gdscript";
        return null;
    }

    private static boolean isDecl(String t, String lang) {
        if (t.isEmpty()) return false;
        return switch (lang) {
            case "rust" -> t.startsWith("pub fn ") || t.startsWith("fn ") || t.startsWith("pub struct ")
                    || t.startsWith("struct ") || t.startsWith("pub enum ") || t.startsWith("enum ")
                    || t.startsWith("pub trait ") || t.startsWith("trait ") || t.startsWith("impl ")
                    || t.startsWith("pub type ") || t.startsWith("pub mod ") || t.startsWith("mod ")
                    || t.startsWith("pub(crate) fn ");
            case "python" -> t.startsWith("def ") || t.startsWith("class ") || t.startsWith("async def ");
            case "java" -> (t.contains("class ") || t.contains("interface ") || t.contains("enum ")
                    || t.contains("record ")) && !t.startsWith("//") && !t.startsWith("*")
                    || ((t.startsWith("public ") || t.startsWith("private ") || t.startsWith("protected ")
                    || t.startsWith("static ")) && t.contains("(") && !t.contains(";"));
            case "go" -> t.startsWith("func ") || t.startsWith("type ");
            case "js" -> t.startsWith("export ") || t.startsWith("function ") || t.startsWith("class ")
                    || t.startsWith("async function ") || t.startsWith("export default ");
            case "gdscript" -> t.startsWith("func ") || t.startsWith("class ") || t.startsWith("signal ")
                    || t.startsWith("class_name ") || t.startsWith("extends ");
            default -> false;
        };
    }
}
