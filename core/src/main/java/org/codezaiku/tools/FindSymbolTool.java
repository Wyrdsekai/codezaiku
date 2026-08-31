package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.codezaiku.lsp.LspClient;

/**
 * IDE-grade code navigation as a tool: where is this defined, and who uses it.
 *
 * <p>The corridor for a door that already existed. {@code LspClient} carried the whole LSP
 * transport — didOpen, request/await, documentSymbol — and nothing exposed navigation to the model,
 * which is precisely the shape the wyrdsekai access audit warns about. Sourcebot made the gap
 * visible from outside: its headline feature is code nav, and ours was sitting unwired.
 *
 * <p>One tool, not three, on purpose. Every schema rides in every request (the measured 724-token
 * tool budget), and definition/references/symbols share their inputs — a file plus a symbol name —
 * so they are one schema with a {@code mode}. The symbol is found by NAME via documentSymbol and
 * the position is computed server-side; the model never supplies line:character coordinates, which
 * small models garble.
 *
 * <p>Registered only when a language server actually started: an advertised tool that always
 * answers "unavailable" teaches the model to stop calling tools.
 */
public final class FindSymbolTool implements Tool {

    static final int MAX_RESULTS = 30;

    private final PathScope scope;
    private final LspClient lsp;

    public FindSymbolTool(PathScope scope, LspClient lsp) {
        this.scope = scope;
        this.lsp = lsp;
    }

    @Override
    public String name() {
        return "find_symbol";
    }

    @Override
    public String description() {
        return "Language-server code navigation. mode=symbols lists a file's functions/types with "
                + "line spans; mode=definition finds where a named symbol is defined; "
                + "mode=references finds every use. Faster and exact vs guessing with search.";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("mode").put("type", "string")
                .put("description", "symbols | definition | references");
        props.putObject("path").put("type", "string")
                .put("description", "File, relative to the project root.");
        props.putObject("symbol").put("type", "string")
                .put("description", "Symbol name (required for definition/references).");
        p.putArray("required").add("mode").add("path");
        return p;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String mode = args.path("mode").asText("symbols");
        Path file;
        try {
            file = scope.resolve(args.path("path").asText(""));
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        }
        if (!Files.isRegularFile(file)) return "ERROR: no such file: " + args.path("path").asText("");
        String text = Files.readString(file);

        List<LspClient.Sym> syms = lsp.symbols(file, text);
        if (syms.isEmpty()) {
            return "no symbols reported — the language server may not cover this file type";
        }
        if (mode.equals("symbols")) {
            var b = new StringBuilder();
            syms.stream().limit(MAX_RESULTS).forEach(s ->
                    b.append(s.name()).append("  lines ").append(s.startLine() + 1)
                     .append("-").append(s.endLine() + 1).append('\n'));
            if (syms.size() > MAX_RESULTS) b.append("(+").append(syms.size() - MAX_RESULTS).append(" more)\n");
            return b.toString().stripTrailing();
        }

        String name = args.path("symbol").asText("");
        if (name.isBlank()) return "ERROR: mode=" + mode + " needs a symbol name";
        // Name -> position, computed here: the model supplies what it knows (a name), never
        // line:character coordinates, which small models garble.
        LspClient.Sym target = syms.stream().filter(s -> s.name().equals(name)).findFirst()
                .orElse(syms.stream().filter(s -> s.name().contains(name)).findFirst().orElse(null));
        if (target == null) {
            return "no symbol named '" + name + "' in this file — mode=symbols lists what it has";
        }
        String[] fileLines = text.split("\n", -1);
        String defLine = fileLines[Math.min(target.startLine(), fileLines.length - 1)];
        int character = Math.max(0, defLine.indexOf(name));

        List<LspClient.Loc> locs = mode.equals("definition")
                ? lsp.definition(file, text, target.startLine(), character)
                : lsp.references(file, text, target.startLine(), character);
        if (locs.isEmpty()) return "the language server returned nothing for " + mode + " of '" + name + "'";
        var b = new StringBuilder();
        Path root = scope.root();
        locs.stream().limit(MAX_RESULTS).forEach(l -> {
            String rel = l.path().startsWith(root.toString())
                    ? l.path().substring(root.toString().length() + 1) : l.path();
            b.append(rel).append(":").append(l.line() + 1).append('\n');
        });
        if (locs.size() > MAX_RESULTS) b.append("(+").append(locs.size() - MAX_RESULTS).append(" more)\n");
        return b.toString().stripTrailing();
    }
}
