package org.codezaiku.chat;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the change actually is, shown before you are asked to allow it.
 *
 * <h2>Why this exists</h2>
 *
 * A prompt that says <i>"edit Client.java?"</i> gives you nothing to answer with. You either trust it
 * blindly or you stop and go looking — and needing to go looking is precisely the reason someone
 * would reach for a plan mode. <b>The approval prompt is only a real checkpoint if it shows the
 * change.</b> With the diff in front of you, plan mode goes back to being what it should be: an
 * override for when you want to force a discussion, not the thing you need because the normal flow
 * is unreadable.
 *
 * <p>Deliberately small: enough to decide, never enough to scroll past. A change nobody reads is the
 * same as no prompt at all, and a heredoc that fills the screen trains people to type {@code a}.
 */
public final class ChatPreview {

    /** Lines of context either side of a change. Three is the diff default for a reason. */
    private static final int CONTEXT = 2;
    /** Beyond this, show a head and a tail with a marker between. */
    private static final int MAX_LINES = 14;
    private static final int MAX_COL = 100;

    private ChatPreview() { }

    /** Renders the change {@code tool} is about to make, already indented for display. */
    public static List<String> of(String tool, JsonNode args) {
        return switch (tool) {
            case "edit_file"  -> edit(args);
            case "write_file" -> write(args);
            case "shell", "run_background" -> shell(args);
            case "delegate" -> java.util.List.of(
                    "a background sub-agent will work in this project with FULL tools and no",
                    "per-action questions. Its complete instructions:",
                    "  " + (args == null ? "" : args.path("task").asText("")
                            .replace("\n", "\n  ")));
            default -> tool.startsWith("mcp_")
                    ? List.of("a tool in ANOTHER process will act; arguments:",
                              "  " + (args == null ? "{}" : args.toString()))
                    : List.of();
        };
    }

    private static List<String> edit(JsonNode args) {
        String oldS = text(args, "old_string");
        String newS = text(args, "new_string");
        var out = new ArrayList<String>();
        // A real diff would need the file; this is the model's own before/after, which is what it is
        // asking permission for and is available without touching disk. Shown as -/+ because that is
        // the notation everyone already reads.
        for (String l : trim(oldS.split("\n", -1))) out.add("- " + l);
        for (String l : trim(newS.split("\n", -1))) out.add("+ " + l);
        return out;
    }

    private static List<String> write(JsonNode args) {
        String content = text(args, "content");
        String[] lines = content.split("\n", -1);
        var out = new ArrayList<String>();
        for (String l : trim(lines)) out.add("+ " + l);
        // The size matters as much as the content — "347 lines" is the number that makes someone
        // look properly at a file they thought was a small change.
        out.add("  (" + lines.length + " line" + (lines.length == 1 ? "" : "s") + ")");
        return out;
    }

    private static List<String> shell(JsonNode args) {
        String cmd = text(args, "command");
        var out = new ArrayList<String>();
        // The WHOLE command, wrapped rather than truncated: the dangerous part of a long command
        // line is usually at the end, so cutting the tail off hides exactly what matters.
        for (String l : cmd.split("\n", -1)) {
            while (l.length() > MAX_COL) {
                out.add("$ " + l.substring(0, MAX_COL));
                l = "    " + l.substring(MAX_COL);
            }
            out.add("$ " + l);
        }
        return out.size() > MAX_LINES ? elide(out) : out;
    }

    /** Keep the interesting lines: everything if it is short, otherwise a head and a tail. */
    private static List<String> trim(String[] lines) {
        var kept = new ArrayList<String>();
        for (String l : lines) kept.add(l.length() > MAX_COL ? l.substring(0, MAX_COL - 1) + "…" : l);
        // Drop a single trailing empty line — an artefact of split(), not part of the change.
        if (!kept.isEmpty() && kept.get(kept.size() - 1).isEmpty()) kept.remove(kept.size() - 1);
        return kept.size() > MAX_LINES ? elide(kept) : kept;
    }

    private static List<String> elide(List<String> lines) {
        int head = MAX_LINES / 2;
        int tail = MAX_LINES - head - 1;
        var out = new ArrayList<>(lines.subList(0, head));
        out.add("  … " + (lines.size() - head - tail) + " more lines …");
        out.addAll(lines.subList(lines.size() - tail, lines.size()));
        return out;
    }

    private static String text(JsonNode args, String field) {
        return args == null ? "" : args.path(field).asText("");
    }

    /** Unused today; kept because CONTEXT documents the intent of {@link #trim}. */
    static int context() { return CONTEXT; }
}
