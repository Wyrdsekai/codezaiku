package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;

public final class WriteFileTool implements Tool {
    // llama.cpp's tool-call parser CRASHES on a large/complex content arg — and the threshold is much
    // lower than the old 13KB assumption: battery42 saw a ~5KB sample.mbox write (heavily escaped, lots of
    // \n + quotes) fail to parse at column ~4760 (drive HTTP 500), repeatedly, burning the run. The crash
    // is at the SERVER, before this tool even runs, so this cap is a PROACTIVE guard (the drive-500 nudge
    // in FamiliarLoop handles the calls that crash outright): keep writes small enough that the model never
    // approaches the parser's limit, forcing skeleton-then-patch / chunked-append for big files.
    private static final int CAP = 4_000;
    private final PathScope scope;

    public WriteFileTool(PathScope scope) {
        this.scope = scope;
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Create or overwrite a file with the given text content (relative to the project root).";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("path").put("type", "string");
        props.putObject("content").put("type", "string");
        p.putArray("required").add("path").add("content");
        return p;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String rel = args.path("path").asText();
        String content = args.path("content").asText();
        if (content.length() > CAP) {
            return "ERROR: content too large (" + content.length() + " chars > " + CAP + ") — one big write "
                    + "overflows the tool-call parser and fails. Write this file in SMALL PIECES: a short "
                    + "write_file to create it, then ADD the rest with several small edit_file calls; for a "
                    + "data file (e.g. a .mbox fixture) append in chunks via shell (`cat >> " + rel
                    + " <<'EOF' … EOF`). Keep each piece under " + CAP + " chars.";
        }
        if (ContainerExec.active()) {                       // container-exec mode (env-gated); off by default
            ContainerExec.write(rel, content);
            scope.recordWrite(rel);
            return "wrote " + content.length() + " chars to " + rel;
        }
        Path f = scope.resolve(rel);
        if (f.getParent() != null) Files.createDirectories(f.getParent());
        Files.writeString(f, content);
        String landed = scope.rel(f);
        scope.recordWrite(landed);
        String redirect = pathNote(rel, landed);
        return "wrote " + content.length() + " chars to " + landed + redirect + SyntaxCheck.check(scope, landed);
    }

    /** Tell the model when its path was redirected to the real build root (so it stops re-nesting). */
    static String pathNote(String requested, String landed) {
        String req = requested.replace('\\', '/').strip();
        while (req.startsWith("./")) req = req.substring(2);
        if (req.equals(landed)) return "";
        return " (NOTE: redirected from '" + requested + "' — write at the project root, do NOT nest under "
                + "output/ or a project-name folder; the build target is the root)";
    }
}
