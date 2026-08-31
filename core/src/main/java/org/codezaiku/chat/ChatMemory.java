package org.codezaiku.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-session project memory — the colleague half a session store cannot provide.
 *
 * <p>Sessions remember a conversation; a COLLEAGUE remembers working with you. The measured gap
 * (2026-08-30): the difference between the frontier assistant and this chat was less the model
 * than that one of them walks in carrying "what we learned, what burned us, what you prefer".
 * This is that file: one markdown file per project in the HARNESS-OWNED store (never the repo —
 * a cloned repo must not arrive carrying implanted "memories"), entries appended by the person
 * ({@code /remember}) or by the model through a CONSENTED tool ({@link
 * org.codezaiku.tools.RememberTool}) — writing to the model's own future context is an act, and
 * acts ask.
 *
 * <p>Markdown IS the format (the session-store rule): the person edits or prunes the file with
 * any editor, and what {@link #recall} returns is exactly what the file says, newest entries
 * kept when the budget trims — recent memory outranks old, and the trim is DECLARED.
 */
public final class ChatMemory {

    /** Recall budget, chars. Memory rides in every turn's context; it must stay a passenger. */
    static final int RECALL_BUDGET = 4_000;

    private final Path file;

    public ChatMemory(Path storeDir) {
        this.file = storeDir.resolve("memory.md");
    }

    public Path file() {
        return file;
    }

    /** Append one remembered fact, dated. Source says who is writing: "person" or "model". */
    public void remember(String fact, String source) throws IOException {
        String f = fact.strip().replaceAll("\\s+", " ");
        if (f.isEmpty()) return;
        Files.createDirectories(file.getParent());
        String entry = "- " + LocalDate.now() + " (" + source + ") " + f + "\n";
        if (!Files.exists(file)) {
            Files.writeString(file, "# Project memory — carried into every session\n\n" + entry,
                    StandardCharsets.UTF_8);
        } else {
            Files.writeString(file, entry, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
    }

    /** Remove entries containing the fragment (the person's eraser). Returns how many went. */
    public int forget(String fragment) throws IOException {
        if (!Files.exists(file) || fragment.isBlank()) return 0;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> kept = new ArrayList<>();
        int removed = 0;
        for (String l : lines) {
            if (l.startsWith("- ") && l.toLowerCase().contains(fragment.strip().toLowerCase())) {
                removed++;
            } else {
                kept.add(l);
            }
        }
        if (removed > 0) Files.write(file, kept, StandardCharsets.UTF_8);
        return removed;
    }

    /**
     * The memory block for a turn's context, or "" when nothing is remembered. Newest entries
     * survive the budget; a trim says so rather than silently forgetting the old.
     */
    public String recall() {
        try {
            if (!Files.exists(file)) return "";
            List<String> entries = new ArrayList<>();
            for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (l.startsWith("- ")) entries.add(l);
            }
            if (entries.isEmpty()) return "";
            var kept = new ArrayList<String>();
            int used = 0;
            int dropped = 0;
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (used + entries.get(i).length() > RECALL_BUDGET) {
                    dropped = i + 1;
                    break;
                }
                kept.add(0, entries.get(i));
                used += entries.get(i).length();
            }
            var b = new StringBuilder("[project memory — from earlier sessions]\n");
            if (dropped > 0) {
                b.append("(").append(dropped).append(" older entries not shown — ")
                 .append(file).append(" holds them)\n");
            }
            for (String e : kept) b.append(e).append('\n');
            return b.append('\n').toString();
        } catch (IOException e) {
            return "";   // unreadable memory is absent memory, never a crash
        }
    }
}
