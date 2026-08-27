package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.lsp.LspClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Replace a whole named symbol (function / method / class / struct / …) by NAME, using the language
 * server's EXACT span (textDocument/documentSymbol). The model never reproduces the old code, so the
 * edit cannot miss — fixes the dominant edit-landing loss (the 9B mis-remembering a whole-function
 * block it had already edited). Language-general by construction: the span comes from the per-language
 * LSP, the splice is line-based, no language syntax is baked in here.
 */
public final class ReplaceSymbolTool implements Tool {
    private final PathScope scope;
    private final LspClient lsp;

    public ReplaceSymbolTool(PathScope scope, LspClient lsp) {
        this.scope = scope;
        this.lsp = lsp;
    }

    @Override
    public String name() {
        return "replace_symbol";
    }

    @Override
    public String description() {
        return "Replace an ENTIRE named function/method/class/struct/type with new code, located by the "
                + "language server's exact span — you do NOT supply the old text, so it can't mismatch. "
                + "Prefer this over edit_file for whole-definition rewrites. Args: path; symbol (the "
                + "name, e.g. \"render_cpu_tab\"); new_text (the complete new definition).";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("path").put("type", "string");
        props.putObject("symbol").put("type", "string");
        props.putObject("new_text").put("type", "string");
        p.putArray("required").add("path").add("symbol").add("new_text");
        return p;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String rel = args.path("path").asText();
        String symbol = args.path("symbol").asText().trim();
        String newText = args.path("new_text").asText();
        if (symbol.isEmpty()) return "ERROR: symbol is required";
        Path f = scope.resolve(rel);
        if (!Files.exists(f)) return "ERROR: no such file: " + rel;
        String content = Files.readString(f);

        List<LspClient.Sym> syms = lsp.symbols(f, content);
        if (syms.isEmpty()) {
            return "ERROR: the language server returned no symbols for " + rel + " (not indexed yet, or "
                    + "unsupported language) — use edit_file instead.";
        }
        List<LspClient.Sym> matches = syms.stream().filter(s -> s.name().equals(symbol)).toList();
        if (matches.isEmpty()) {
            String names = syms.stream().map(LspClient.Sym::name).distinct().limit(40)
                    .reduce((a, b) -> a + ", " + b).orElse("");
            return "ERROR: no symbol named \"" + symbol + "\" in " + rel + ". Available symbols: " + names;
        }
        if (matches.size() > 1) {
            return "ERROR: \"" + symbol + "\" is defined " + matches.size() + " times in " + rel
                    + " — use edit_file with surrounding context to disambiguate.";
        }

        LspClient.Sym sym = matches.get(0);
        String[] lines = content.split("\n", -1);
        if (sym.startLine() >= lines.length || sym.endLine() >= lines.length) {
            return "ERROR: stale symbol span for \"" + symbol + "\" — re-read the file and use edit_file.";
        }
        // Re-indent new_text to the symbol's column (model may supply it de-indented), then splice the
        // symbol's whole line span. Language-agnostic — preserves the file's surrounding text exactly.
        String indent = leadingWs(lines[sym.startLine()]);
        String body = reindent(newText, indent);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sym.startLine(); i++) out.append(lines[i]).append('\n');
        out.append(body);
        if (!body.endsWith("\n") && sym.endLine() + 1 < lines.length) out.append('\n');
        for (int i = sym.endLine() + 1; i < lines.length; i++) {
            out.append(lines[i]);
            if (i < lines.length - 1) out.append('\n');
        }
        Files.writeString(f, out.toString());
        scope.recordWrite(rel);
        return "replaced symbol \"" + symbol + "\" (lines " + (sym.startLine() + 1) + "-"
                + (sym.endLine() + 1) + ") in " + rel + SyntaxCheck.check(scope, rel);
    }

    /** Prefix every non-empty line of {@code text} with {@code indent} (unless it already starts there). */
    private static String reindent(String text, String indent) {
        if (indent.isEmpty()) return text;
        String[] nl = text.split("\n", -1);
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < nl.length; i++) {
            String line = nl[i];
            if (!line.isEmpty() && !line.startsWith(indent) && !line.startsWith(" ") && !line.startsWith("\t")) {
                r.append(indent);
            }
            r.append(line);
            if (i < nl.length - 1) r.append('\n');
        }
        return r.toString();
    }

    private static String leadingWs(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return line.substring(0, i);
    }
}
