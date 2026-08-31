package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * First-class code search, so the model stops paying for exploration with its context window.
 *
 * <p>Measured need, one day of live logs: with no search tool the model shells out — a single
 * {@code ls -R} on a real repo cost 22% of a 32k window in one call, one exploration racked up 47
 * {@code read_file} calls, and every unfamiliar codebase is navigated by guesswork. The shell
 * results also arrive uncapped, which is what pushed a live turn into compaction. This tool is the
 * capped, structured answer: ripgrep when the box has it, {@code grep -rn} otherwise, output
 * bounded the way {@code ReadFileTool} bounds reads and for the same re-transmission reason — a
 * fat result enters the history once and is paid for on every later turn.
 *
 * <p>Read-only by construction, so it belongs to every rung including {@code readOnly()}, and the
 * chat consent layer never prompts for it — searching is looking, not acting.
 */
public final class SearchCodeTool implements Tool {

    /** Matches shown before the tail is summarized. Small: results ride in the history. */
    static final int MAX_MATCHES = 40;
    static final int MAX_LINE = 200;

    private final PathScope scope;

    public SearchCodeTool(PathScope scope) {
        this.scope = scope;
    }

    @Override
    public String name() {
        return "search_code";
    }

    @Override
    public String description() {
        return "Search file contents in the project (regex). Returns path:line: text matches. "
                + "Use this to FIND where something lives before reading files — one search "
                + "replaces many speculative reads.";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("pattern").put("type", "string")
                .put("description", "Regex (ripgrep/grep -E syntax).");
        props.putObject("path").put("type", "string")
                .put("description", "Directory or file to search, relative to the project root. Default: whole project.");
        p.putArray("required").add("pattern");
        return p;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String pattern = args.path("pattern").asText("");
        if (pattern.isBlank()) return "ERROR: empty pattern";
        String rel = args.path("path").asText(".");
        java.nio.file.Path target;
        try {
            target = rel.equals(".") ? scope.root() : scope.resolve(rel);
        } catch (IllegalArgumentException e) {
            // The scope's refusal, verbatim — it names the correction the model can act on.
            return "ERROR: " + e.getMessage();
        }

        List<String> cmd = new ArrayList<>();
        boolean rg = new ProcessBuilder("sh", "-c", "command -v rg").start().waitFor() == 0;
        if (rg) {
            // -n line numbers, -S smart case, --no-heading path:line:text, capped columns so a
            // minified line cannot flood the result.
            cmd.addAll(List.of("rg", "-n", "-S", "--no-heading", "--max-columns", String.valueOf(MAX_LINE),
                    "-g", "!.git", "-g", "!build", "-g", "!node_modules", "-g", "!.codezaiku",
                    "--", pattern, target.toString()));
        } else {
            cmd.addAll(List.of("grep", "-rnE", "--exclude-dir=.git", "--exclude-dir=build",
                    "--exclude-dir=node_modules", "--exclude-dir=.codezaiku",
                    "--", pattern, target.toString()));
        }
        Process proc = new ProcessBuilder(cmd).directory(scope.root().toFile())
                .redirectErrorStream(true).start();
        List<String> lines = new ArrayList<>();
        int total = 0;
        try (var r = new java.io.BufferedReader(
                new java.io.InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                total++;
                if (lines.size() < MAX_MATCHES) {
                    if (l.length() > MAX_LINE) l = l.substring(0, MAX_LINE) + "…";
                    // Paths relative to the root, so the result plugs straight into read_file.
                    lines.add(l.startsWith(scope.root().toString())
                            ? l.substring(scope.root().toString().length() + 1) : l);
                }
            }
        }
        int rc = proc.waitFor();
        // Judge the OUTPUT, not the exit code: grep/rg exit 1 on the entirely legitimate answer
        // "no matches" (the house rule about shell probes, applied to our own tool).
        if (total == 0) {
            return rc > 1 ? "ERROR: search failed (exit " + rc + ")" : "no matches for: " + pattern;
        }
        String out = String.join("\n", lines);
        if (total > MAX_MATCHES) {
            out += "\n(+" + (total - MAX_MATCHES) + " more matches — narrow the pattern or pass a path)";
        }
        return out;
    }
}
