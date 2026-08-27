package org.codezaiku.library;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a Library query from a tool observation that carries a build/compile/parse error.
 * Returns {@code null} when the observation is not an error.
 *
 * <p>This is the engine of the error-driven push (cut B2): the model typically fails by writing an
 * OLDER version's API of a pinned dependency (rust {@code sysinfo::CpuExt} removed; ratatui import
 * dropped; GDScript 3 vs 4 syntax). The error names the exact symbol; we extract it and retrieve the
 * CURRENT authoritative API so the harness can push the real signature back. Sibling-in-spirit to the
 * pre-focus {@code LibraryHintRegistry} symbol parsers, but feeding the Lucene library, not a grep.
 */
public final class ErrorQuery {

    private ErrorQuery() {
    }

    private static final String[] ERROR_MARKERS = {
            "error[", "error:", "script error", "parse error", "cannot find symbol",
            "unresolved import", "no module named", "modulenotfounderror", "syntaxerror",
            "nameerror", "attributeerror", "importerror", "not declared", "does not exist",
            "has no attribute", "panicked", "failed to resolve", "no method named",
            "no function named", "invalid call", "compilation failed", "undeclared"
    };

    private static final Pattern BACKTICK = Pattern.compile("`([^`]{2,60})`");
    private static final Pattern DQUOTE = Pattern.compile("\"([A-Za-z_][\\w:.]{1,58})\"");
    private static final Pattern SQUOTE = Pattern.compile("'([A-Za-z_][\\w:.]{1,58})'");
    private static final Pattern PATHED = Pattern.compile("\\b([A-Za-z_]\\w*(?:::[A-Za-z_]\\w*)+)\\b");

    // Lines from OUR gate/harness verdict, not the toolchain — never mine these for symbols.
    private static final String[] META_LINE = {
            "cli gate", "=== gate", "output contained forbidden", "output missing",
            "verification failed", "expectexit", "behavior door", "↳ cause"
    };

    // Language keywords + error/gate meta words + the harness's own vocabulary — never a library symbol.
    private static final Set<String> STOP = Set.of(
            "for", "void", "null", "true", "false", "self", "func", "var", "const", "return", "pass",
            "class", "function", "method", "type", "name", "named", "value", "call", "object", "property",
            "base", "scope", "current", "import", "module", "attribute", "error", "script", "parse",
            "fail", "failed", "output", "contained", "forbidden", "loads", "runs", "build", "test",
            "exit", "expected", "found", "the", "and", "not", "declared", "undeclared", "activate",
            "string", "int", "float", "bool", "array", "dictionary");

    /** A BM25 query of salient error symbols, or {@code null} if {@code obs} is not an error. */
    // Harness/tool-op failures — NOT a toolchain compile error; never mine these for library symbols.
    private static final String[] TOOL_OP = {
            "old_string not found", "is a directory", "no such file", "write_file failed",
            "permission denied", "⚠ syntax error" // the per-edit syntax note is handled elsewhere
    };

    public static String fromObservation(String obs) {
        if (obs == null || obs.isBlank()) return null;
        String lower = obs.toLowerCase();
        for (String t : TOOL_OP) {
            if (lower.contains(t)) return null;
        }
        boolean isError = false;
        for (String m : ERROR_MARKERS) {
            if (lower.contains(m)) { isError = true; break; }
        }
        if (!isError) return null;

        // Narrow to the lines that actually carry a TOOLCHAIN error — skip our own gate/harness verdict
        // lines (they contain "SCRIPT ERROR" etc. as forbidden-strings, not as a real compiler error).
        StringBuilder errLines = new StringBuilder();
        for (String line : obs.split("\\R")) {
            String ll = line.toLowerCase();
            boolean meta = false;
            for (String m : META_LINE) {
                if (ll.contains(m)) { meta = true; break; }
            }
            if (meta) continue;
            for (String m : ERROR_MARKERS) {
                if (ll.contains(m)) { errLines.append(line).append('\n'); break; }
            }
            if (errLines.length() > 800) break;
        }
        String scope = errLines.length() == 0 ? obs : errLines.toString();

        Set<String> tokens = new LinkedHashSet<>();
        collect(BACKTICK, scope, tokens);
        collect(PATHED, scope, tokens);
        collect(DQUOTE, scope, tokens);
        collect(SQUOTE, scope, tokens);

        StringBuilder q = new StringBuilder();
        for (String t : tokens) {
            String cleaned = t.replace("::", " ").replace(".", " ").trim();
            if (!cleaned.isBlank()) q.append(cleaned).append(' ');
            if (q.length() > 140) break;
        }
        if (q.length() == 0) {
            String first = scope.split("\\R")[0].replaceAll("[^A-Za-z0-9_ ]", " ").trim();
            if (first.isBlank()) return null;
            return first.substring(0, Math.min(140, first.length())).trim();
        }
        return q.toString().trim();
    }

    private static void collect(Pattern p, String s, Set<String> out) {
        Matcher m = p.matcher(s);
        while (m.find() && out.size() < 12) {
            String g = m.group(1).trim();
            if (g.contains("/") || g.contains("\\") || g.isBlank()) continue;       // file paths
            if (g.matches(".*\\.(rs|gd|py|java|js|ts|toml|json)$")) continue;        // filenames
            if (g.matches("[0-9.]+")) continue;                                      // bare version/number
            // Keep only things that look like a real library SYMBOL: a qualified path (::/.), or a
            // CamelCase / snake_case identifier. Drops bare keywords ("for", "void", "activate").
            boolean qualified = g.contains("::") || g.contains(".");
            boolean symbolish = g.matches(".*[A-Z].*") || g.contains("_");
            if (!qualified && !symbolish) continue;
            // Drop stopwords (per ::/. segment too, so "module asyncio" → asyncio survives but "self" dies).
            String head = g.split("[:.]")[0].toLowerCase();
            if (STOP.contains(g.toLowerCase()) || STOP.contains(head)) continue;
            out.add(g);
        }
    }
}
