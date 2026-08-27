package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Read a file, with a read-guard for the small window (little-coder / smallcode pattern): a too-large
 * file is NOT dumped — it returns its head plus a directive to grep then read a specific range via
 * {@code offset}/{@code limit}. Keeps a 9B's context full of relevant lines, not whole files.
 */
public final class ReadFileTool implements Tool {
    // MEASURED 2026-08-16 and kept. A/B against a 30k cap, n=10 per arm on find-and-fix-the-bug in an
    // 806-line file (the defect placed past HEAD_LINES so it is invisible without grep/range):
    //   success   10/10 BOTH arms — the cap does not affect outcome, and the 9B closes the
    //             grep->range loop reliably
    //   tokens    12k cap sent 202k req-chars vs 265k for the 30k cap — ~24% cheaper, P(A>B)=0.22
    //   turns     a wash: means 10.3 vs 8.7 but MEDIANS 9.0 vs 9.5; the mean gap is one 17-turn tail
    // The reason a bigger read costs more, which is easy to miss: a large file enters the history ONCE
    // and is then re-sent on EVERY later turn, so it is paid for repeatedly, while pagination pays a
    // few extra round trips of a smaller history. Re-transmission dominates, not window pressure —
    // measured context high-water on these runs never exceeded ~26% of the window.
    // Still env-settable so the experiment is repeatable; the default is the measured choice.
    private static final int CAP = org.codezaiku.Config.getInt("CODEZAIKU_READ_CAP", 12_000);
    private static final int HEAD_LINES = 60;  // lines of a too-large file shown for structure
    private final PathScope scope;

    public ReadFileTool(PathScope scope) {
        this.scope = scope;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Read a text file (relative to the project root). For a large file, pass offset "
                + "(1-based start line) and limit (number of lines) to read just a range; otherwise a "
                + "large file returns only its head plus guidance to grep and read a range.";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("path").put("type", "string");
        props.putObject("offset").put("type", "integer");
        props.putObject("limit").put("type", "integer");
        p.putArray("required").add("path");
        return p;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String rel = args.path("path").asText();
        List<String> lines;
        if (ContainerExec.active()) {                       // container-exec mode (env-gated); off by default
            if (ContainerExec.isDir(rel)) return "ERROR: is a directory: " + rel;
            String content = ContainerExec.read(rel);
            if (content == null) return "ERROR: no such file: " + rel;
            lines = Arrays.asList(content.split("\n", -1));
        } else {
            Path f = scope.resolve(rel);
            if (!Files.exists(f)) return "ERROR: no such file: " + rel;
            if (Files.isDirectory(f)) return "ERROR: is a directory: " + rel;
            lines = Files.readAllLines(f);
        }
        int total = lines.size();
        int offset = args.path("offset").asInt(0); // 1-based; 0 = from start
        int limit = args.path("limit").asInt(0);   // 0 = unbounded

        if (offset > 0 || limit > 0) {
            int start = Math.max(0, offset > 0 ? offset - 1 : 0);
            if (start >= total) return "ERROR: offset " + offset + " is past end of file (" + total + " lines)";
            int end = limit > 0 ? Math.min(total, start + limit) : total;
            return "(lines " + (start + 1) + "-" + end + " of " + total + ")\n" + cap(String.join("\n", lines.subList(start, end)));
        }

        String whole = String.join("\n", lines);
        if (whole.isEmpty()) return "(empty file)";
        if (whole.length() <= CAP) return whole;

        int head = Math.min(HEAD_LINES, total);
        return "FILE IS LARGE (" + total + " lines, " + whole.length() + " chars) — showing the first "
                + head + " lines for structure. grep -n for what you need, then read the range with "
                + "read_file(path, offset, limit). Do NOT read the whole file.\n\n"
                + String.join("\n", lines.subList(0, head));
    }

    private static String cap(String s) {
        return s.length() <= CAP ? s : s.substring(0, CAP) + "\n...[truncated; narrow the offset/limit range]";
    }
}
