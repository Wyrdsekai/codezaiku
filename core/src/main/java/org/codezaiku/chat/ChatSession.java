package org.codezaiku.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.codezaiku.Config;

/**
 * What a chat remembers between turns — and, deliberately, what it forgets.
 *
 * <h2>Why this is a schema and not a transcript</h2>
 *
 * Turn N does NOT get the previous turns appended to it. It gets THIS, restated, plus what the
 * person just typed. The reason is measured rather than aesthetic:
 *
 * <ul>
 *   <li><b>Growing transcripts make models worse.</b> arXiv 2505.06120: −39% mean going single-turn
 *       to multi-turn across 15 models, of which +112% is <i>unreliability</i> rather than lost
 *       aptitude. The named causes — answer bloat and loss-in-middle-turns — are both properties of
 *       a long accumulated history.</li>
 *   <li><b>Our own prompt is expensive.</b> The system prompt is ~1,271 tokens and the tool schemas
 *       another ~724 before a single word of the task arrives. A transcript on top of that is what
 *       pushes a small drive over its window — measured: a 2,048-token model that solves a task in
 *       90 tokens fails it entirely when wrapped in 2,593.</li>
 * </ul>
 *
 * So every field here is <b>capped</b>. A file that can grow without bound is a transcript wearing a
 * schema's clothes, and would quietly rebuild the thing this exists to avoid. When a list is full the
 * oldest entry is dropped — visibly, by {@link #dropped()}, never silently.
 *
 * <h2>Why it is ours and not the user's to edit</h2>
 *
 * The harness rewrites this file after every turn. If a person edited it, their edit would be
 * clobbered by the next rewrite, or we would need a merge — inside the one file that decides what
 * the model sees. So the state file is written by us and read by anyone; the transcript beside it is
 * what a human reads to find out what actually happened.
 *
 * <h2>Identity is portable</h2>
 *
 * Sessions are keyed by a project id stored <i>in the project</i> ({@code .codezaiku/project-id}),
 * not by a hash of its absolute path. Moving a checkout, or cloning it onto another machine, keeps
 * its sessions attached. {@code FamiliarMemory} does key by absolute path today and loses its store
 * on a move; this does not repeat that.
 */
public final class ChatSession {

    /** Caps. Small on purpose — see the class note. Exceeding one drops the oldest, visibly. */
    static final int MAX_DECISIONS = 8;
    static final int MAX_FILES = 20;
    static final int MAX_PENDING = 4;
    static final int MAX_NOTES = 8;

    private static final Pattern SLUG = Pattern.compile("[^a-z0-9]+");

    private final String id;
    private final Path projectRoot;
    private final String title;

    private String topic = "";
    private String verdict = "";
    private final List<String> decisions = new ArrayList<>();
    private final Set<String> files = new LinkedHashSet<>();
    private final List<String> pending = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private int dropped = 0;
    private int turns = 0;

    ChatSession(String id, Path projectRoot, String title) {
        this.id = id;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.title = title;
    }

    /** A new session in {@code projectRoot}, named from the first thing the person typed. */
    public static ChatSession start(Path projectRoot, String firstMessage) {
        return new ChatSession(LocalDate.now() + "-" + slug(firstMessage), projectRoot,
                firstMessage.length() > 60 ? firstMessage.substring(0, 57) + "..." : firstMessage);
    }

    /**
     * The project's portable identity.
     *
     * <p>Read from {@code .codezaiku/project-id}, created if absent. A UUID in a file beats both a
     * path hash (dies on {@code mv}) and the git remote (absent for a non-git project, and shared by
     * two checkouts that should have separate sessions).
     */
    public static String projectId(Path projectRoot) {
        Path f = projectRoot.toAbsolutePath().normalize().resolve(".codezaiku").resolve("project-id");
        try {
            if (Files.isRegularFile(f)) {
                String s = Files.readString(f, StandardCharsets.UTF_8).trim();
                if (!s.isBlank()) return s;
            }
            String fresh = UUID.randomUUID().toString();
            Files.createDirectories(f.getParent());
            Files.writeString(f, fresh + System.lineSeparator(), StandardCharsets.UTF_8);
            return fresh;
        } catch (IOException e) {
            // A project we cannot write to still deserves a working chat. Fall back to a
            // path-derived key and say nothing — the only cost is that sessions do not follow a
            // move, which is exactly what every other harness does anyway.
            return "local-" + Integer.toHexString(projectRoot.toAbsolutePath().normalize().toString().hashCode());
        }
    }

    /** Where this project's sessions live. Honours {@code CODEZAIKU_CHAT_DIR}. */
    public static Path storeDir(Path projectRoot) {
        String base = Config.get("CODEZAIKU_CHAT_DIR",
                System.getProperty("user.home") + "/.codezaiku/chat");
        return Path.of(base, projectId(projectRoot));
    }

    public Path stateFile()      { return storeDir(projectRoot).resolve(id + ".md"); }
    public Path transcriptFile() { return storeDir(projectRoot).resolve(id + ".log.jsonl"); }

    public String id()      { return id; }
    public String title()   { return title; }
    public int turns()      { return turns; }
    /** How many entries have been pushed out by the caps. Reported, never hidden. */
    public int dropped()    { return dropped; }
    public List<String> decisions() { return List.copyOf(decisions); }
    public List<String> pending()   { return List.copyOf(pending); }
    public List<String> notes()     { return List.copyOf(notes); }
    public Set<String> files()      { return Set.copyOf(files); }

    public void topic(String t)   { if (t != null && !t.isBlank()) this.topic = oneLine(t); }
    public void verdict(String v) { if (v != null && !v.isBlank()) this.verdict = oneLine(v); }
    public void turnDone()        { turns++; }

    public void decided(String d) { push(decisions, d, MAX_DECISIONS); }

    /**
     * Something the agent said it would do and has not done yet.
     *
     * <p>This field exists because of a measured failure a one-shot verb cannot have. Told <i>"do it
     * — wrap the call in a retry"</i>, a model read a file instead; told <i>"now run the tests"</i> on
     * the next turn, it obediently ran them, and the edit never happened. It noticed six turns later:
     * <i>"I never actually made the retry edit."</i> A verb has one goal and runs until it is met; in
     * a chat every new turn can silently displace unfinished work. Carrying it here is what makes it
     * survive the next thing the person says.
     */
    public void pending(String p)  { push(pending, p, MAX_PENDING); }

    /**
     * A fact the PERSON pinned — Prime Agent's durable supplemental state, minus the part where the
     * harness writes it. Measured need: asked to "remember the deploy target is staging-eu-3", the
     * model echoed to the shell, diff-verified the tree, and later tried to write it into a README.
     * Pinning a fact should not require a model turn, and a note the person wrote is the one kind
     * of durable state that needs no consent surface.
     */
    public void note(String n)     { push(notes, n, MAX_NOTES); }
    public void unnote(String n)   { notes.removeIf(x -> x.equalsIgnoreCase(ChatSession.oneLine(n))); }
    public void resolved(String p) { pending.removeIf(x -> x.equalsIgnoreCase(p)); }
    public void sawFile(String f) {
        if (f == null || f.isBlank()) return;
        if (files.size() >= MAX_FILES && !files.contains(f)) {
            var it = files.iterator();
            it.next();
            it.remove();
            dropped++;
        }
        files.add(f);
    }

    private void push(List<String> into, String v, int cap) {
        if (v == null || v.isBlank()) return;
        String s = oneLine(v);
        if (into.remove(s)) { /* re-stating something moves it to the front, it is not a new entry */ }
        into.add(s);
        while (into.size() > cap) { into.remove(0); dropped++; }
    }

    /**
     * The block that goes in front of the user's turn. This IS the memory — if a fact is not here,
     * the model does not know it, which is the trade the class note explains.
     *
     * <p>Empty for the first turn: a preamble saying "nothing has happened yet" costs tokens to
     * communicate nothing.
     */
    public String restate() {
        if (topic.isBlank() && decisions.isEmpty() && files.isEmpty() && pending.isEmpty()
                && notes.isEmpty()) return "";
        var b = new StringBuilder("[session so far — this is what you already know]\n");
        if (!topic.isBlank())   b.append("working on: ").append(topic).append('\n');
        if (!notes.isEmpty()) {
            // The person's own words come before the harness-derived state: they outrank it.
            b.append("notes from the person (authoritative):\n");
            for (String x : notes) b.append("  - ").append(x).append('\n');
        }
        if (!decisions.isEmpty()) {
            b.append("decided:\n");
            for (String d : decisions) b.append("  - ").append(d).append('\n');
        }
        if (!files.isEmpty())   b.append("files seen: ").append(String.join(", ", files)).append('\n');
        if (!verdict.isBlank()) b.append("last test result: ").append(verdict).append('\n');
        if (!pending.isEmpty()) {
            // Last, and named as outstanding, because this is the thing the next instruction is
            // most likely to displace.
            b.append("NOT DONE YET:\n");
            for (String p : pending) b.append("  - ").append(p).append('\n');
        }
        return b.append('\n').toString();
    }

    /** The state file: what {@link #restate()} says, in a form a person can read. */
    public String toMarkdown() {
        var b = new StringBuilder("# ").append(title).append("\n\n");
        b.append("- session: `").append(id).append("`\n");
        b.append("- project: `").append(projectRoot).append("`\n");
        b.append("- turns: ").append(turns).append('\n');
        if (dropped > 0) b.append("- dropped by caps: ").append(dropped).append('\n');
        b.append("\nWritten by CodeZaiku after every turn — edits here are overwritten.\n");
        b.append("The transcript beside this file is the record of what was said.\n");
        if (!topic.isBlank())     b.append("\n## Working on\n\n").append(topic).append('\n');
        if (!notes.isEmpty()) {
            b.append("\n## Notes from the person\n\n");
            for (String x : notes) b.append("- ").append(x).append('\n');
        }
        if (!decisions.isEmpty()) {
            b.append("\n## Decided\n\n");
            for (String d : decisions) b.append("- ").append(d).append('\n');
        }
        if (!pending.isEmpty()) {
            b.append("\n## Not done yet\n\n");
            for (String p : pending) b.append("- ").append(p).append('\n');
        }
        if (!files.isEmpty()) {
            b.append("\n## Files seen\n\n");
            for (String f : files) b.append("- `").append(f).append("`\n");
        }
        if (!verdict.isBlank())   b.append("\n## Last test result\n\n").append(verdict).append('\n');
        return b.toString();
    }

    /**
     * A NEW session seeded from an old one — the onboarding half of restate-vs-transcript.
     *
     * <p>{@code /resume} continues the same session: same id, same file, the caps keep pruning.
     * This is the other thing a person wants: <i>start fresh, but knowing what we know.</i> The new
     * session gets its own id and file; the old one is left exactly as it was, so it remains a
     * readable record of where the work stood when it ended. What carries over is the STATE — topic,
     * decisions, unfinished work, files, last verdict — because that is what the next session needs.
     * What deliberately does not carry is the transcript: onboarding is a restate, not a replay.
     *
     * <p>The seed is recorded as a decision naming the source session, so the model knows it is
     * continuing something and a person reading the file can follow the [[reference]] back.
     */
    public static ChatSession onboardFrom(Path projectRoot, ChatSession old) {
        var s = new ChatSession(LocalDate.now() + "-onboard-" + slug(old.title), projectRoot,
                "continuing: " + old.title);
        s.topic = old.topic;
        s.verdict = old.verdict;
        s.notes.addAll(old.notes);          // the person's pins carry into the new session
        s.decisions.addAll(old.decisions);
        s.pending.addAll(old.pending);
        s.files.addAll(old.files);
        s.decisions.add(0, "onboarded from [[" + old.id + "]]");
        while (s.decisions.size() > MAX_DECISIONS) { s.decisions.remove(1); s.dropped++; }
        return s;
    }

    /**
     * A handoff summary for the NEXT session or the next person: the state, an optional note from
     * the person ending the session, and the command that picks it up. Written beside the state
     * file as {@code <id>.handoff.md}.
     *
     * <p>The note is the part the harness cannot write. The state says what was decided; only the
     * person knows what the highlights were and what the next session should look at first.
     */
    public Path writeHandoff(String note) throws IOException {
        var b = new StringBuilder("# Handoff: ").append(title).append("\n\n");
        b.append("From session [[").append(id).append("]] after ").append(turns).append(" turn(s).\n");
        b.append("Pick this up with:  codezaiku chat --from ").append(id).append("\n");
        if (note != null && !note.isBlank()) {
            b.append("\n## Highlights (written by the person, not the harness)\n\n")
             .append(note.strip()).append('\n');
        }
        b.append('\n').append(toMarkdown().substring(toMarkdown().indexOf('\n') + 1));
        Path f = storeDir(projectRoot).resolve(id + ".handoff.md");
        Files.createDirectories(f.getParent());
        Files.writeString(f, b.toString(), StandardCharsets.UTF_8);
        return f;
    }

    /**
     * Read a session back from its state file.
     *
     * <p>The markdown IS the format — there is no second, machine-only copy to drift from it. That
     * is deliberate: a state file a person can read but the harness cannot is a decoration, and two
     * representations of the same state is a bug waiting for the day they disagree. The cost is that
     * the writer and this parser have to stay in step, which {@code ChatSessionTest} pins by writing
     * a session, reading it back, and comparing what {@link #restate()} produces.
     *
     * <p>Returns empty when the file is missing or unreadable. A session that cannot be resumed is
     * reported to the person, never silently replaced by a blank one wearing its name.
     */
    public static java.util.Optional<ChatSession> load(Path projectRoot, String id) {
        Path f = storeDir(projectRoot).resolve(id + ".md");
        if (!Files.isRegularFile(f)) return java.util.Optional.empty();
        try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            String title = lines.isEmpty() ? id : lines.get(0).replaceFirst("^#\\s*", "");
            var s = new ChatSession(id, projectRoot, title);
            String section = "";
            for (String raw : lines) {
                String l = raw.strip();
                if (l.startsWith("## ")) { section = l.substring(3).toLowerCase(Locale.ROOT); continue; }
                if (l.startsWith("- turns: ")) {
                    try { s.turns = Integer.parseInt(l.substring(9).strip()); } catch (NumberFormatException ignored) { }
                    continue;
                }
                if (l.startsWith("- session:") || l.startsWith("- project:")
                        || l.startsWith("- dropped")) continue;
                if (l.isEmpty() || l.startsWith("#")) continue;
                boolean bullet = l.startsWith("- ");
                String v = bullet ? l.substring(2).strip() : l;
                switch (section) {
                    case "working on"       -> { if (!bullet) s.topic(v); }
                    case "notes from the person" -> { if (bullet) s.notes.add(oneLine(v)); }
                    case "decided"          -> { if (bullet) s.decisions.add(oneLine(v)); }
                    case "not done yet"     -> { if (bullet) s.pending.add(oneLine(v)); }
                    case "files seen"       -> { if (bullet) s.files.add(v.replace("`", "")); }
                    case "last test result" -> { if (!bullet) s.verdict(v); }
                    default -> { }
                }
            }
            return java.util.Optional.of(s);
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    /** Sessions in this project, newest first, as (id, title, turns). */
    public static List<String[]> list(Path projectRoot) {
        Path dir = storeDir(projectRoot);
        if (!Files.isDirectory(dir)) return List.of();
        try (var st = Files.list(dir)) {
            var ids = st.map(p -> p.getFileName().toString())
                    // A handoff is ABOUT a session, not a session — listing it made "/onboard
                    // trust-me" ambiguous against the very handoff that told you to type it.
                    .filter(n -> n.endsWith(".md") && !n.endsWith(".handoff.md"))
                    .map(n -> n.substring(0, n.length() - 3))
                    .sorted(java.util.Comparator.reverseOrder())
                    .toList();
            var out = new ArrayList<String[]>();
            for (String id : ids) {
                var s = load(projectRoot, id);
                out.add(new String[]{id,
                        s.map(ChatSession::title).orElse("(unreadable)"),
                        String.valueOf(s.map(ChatSession::turns).orElse(0))});
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Write the state file. Best-effort: an unwritable store must not end the conversation. */
    public void save() {
        try {
            Files.createDirectories(storeDir(projectRoot));
            Files.writeString(stateFile(), toMarkdown(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Reported by `chat` at exit rather than thrown mid-conversation.
        }
    }

    /** Append one line to the transcript. Never re-sent to the model — this is for people. */
    public void log(String role, String text) {
        try {
            Files.createDirectories(storeDir(projectRoot));
            var esc = text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "");
            Files.writeString(transcriptFile(),
                    "{\"turn\":" + turns + ",\"role\":\"" + role + "\",\"text\":\"" + esc + "\"}\n",
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // as above
        }
    }

    /**
     * Remove everything this session wrote. Used when it never completed a turn — a drive that was
     * down should leave no trace at all, and a transcript with no state file beside it is a puzzle
     * rather than a record.
     */
    public void discard() {
        try {
            Files.deleteIfExists(stateFile());
            Files.deleteIfExists(transcriptFile());
        } catch (IOException ignored) {
            // Nothing to do and nothing worth saying: the files are noise either way.
        }
    }

    static String oneLine(String s) {
        return s.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
    }

    static String slug(String s) {
        String out = SLUG.matcher(s.toLowerCase(Locale.ROOT)).replaceAll("-")
                .replaceAll("^-+|-+$", "");
        if (out.length() > 40) out = out.substring(0, 40).replaceAll("-+$", "");
        return out.isBlank() ? "session" : out;
    }
}
