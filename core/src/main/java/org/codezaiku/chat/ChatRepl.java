package org.codezaiku.chat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;

import org.codezaiku.drive.DriveClient;
import org.codezaiku.library.Library;
import org.codezaiku.library.LibraryIndex;
import org.codezaiku.loop.FamiliarLoop;
import org.codezaiku.lsp.LspClient;
import org.codezaiku.shape.ProjectFacts;
import org.codezaiku.tools.ToolRegistry;

/**
 * Talk to CodeZaiku directly — no host, no editor, no browser.
 *
 * <h2>What was actually missing</h2>
 *
 * Not a UI. <b>A session.</b> Every existing surface is one-shot: the ACP server builds a brand-new
 * {@link FamiliarLoop} for every prompt with that prompt as the entire goal, so turn two starts from
 * zero. Files persist because the workspace does; the conversation never existed. That is why this
 * class is mostly {@link ChatSession} and only a little readline.
 *
 * <h2>Each turn is a fresh loop</h2>
 *
 * A turn is {@code session.restate() + what you typed}, handed to a NEW loop. The conversation is
 * not appended to and re-sent. See {@link ChatSession} for the measurements behind that; the short
 * version is that growing transcripts make models measurably less reliable, and our own preamble is
 * expensive enough that a transcript on top of it pushes a small drive over its window.
 *
 * <h2>Deliberately not here yet</h2>
 *
 * <ul>
 *   <li><b>No token streaming.</b> {@code DriveClient} sets {@code stream:false} everywhere. Tool
 *       activity is narrated live, which is what a person actually watches; prose appears when the
 *       turn ends.</li>
 *   <li><b>No intent routing.</b> {@code chat} means <i>coding, in this directory</i>. It will not
 *       decide that "my database is down" belongs to the ops surface — that is a guess a small model
 *       makes badly, and getting it wrong means coding-surface behaviour on a live stack without the
 *       ops guard stack. For ops you type {@code fix}.</li>
 * </ul>
 */
public final class ChatRepl {

    private final Path root;
    private String driveUrl;
    private String model;
    private DriveClient drive;
    private final int maxTurns;

    private final ChatConsent consent;
    private final ChatLogs logs = new ChatLogs();
    private ChatJournal journal;
    private ChatTasks tasks;
    private ChatMemory memory;
    private java.util.List<org.codezaiku.mcp.McpClient> mcpClients = java.util.List.of();

    /** /diff and /commit are git features and honestly say so; /undo no longer is. */
    private boolean gitRepo() {
        return java.nio.file.Files.isDirectory(root.resolve(".git"));
    }
    /**
     * Show the model's reasoning as it streams. OFF by default — the operator's words on seeing it: "why
     * is it still talking to itself." The thinking is diagnostic (it found two conversation-design
     * bugs) but it is the inner voice, and the default view is the conversation.
     * {@code CODEZAIKU_STREAM=all} starts it on; {@code /thinking on|off} changes it mid-session.
     */
    private volatile boolean showThinking =
            java.util.List.of("all", "think", "thinking")
                    .contains(org.codezaiku.Config.get("CODEZAIKU_STREAM", "").toLowerCase(java.util.Locale.ROOT));
    private ChatSession session;
    private ChatIo io;
    /** Set when the person abandons a turn at an approval prompt; read by the loop's cancel hook. */
    private final AtomicBoolean abandoned = new AtomicBoolean();

    public ChatRepl(Path root, String driveUrl, String model, ChatConsent.Mode mode, int maxTurns) {
        this(root, driveUrl, model, mode, maxTurns, null);
    }

    public ChatRepl(Path root, String driveUrl, String model, ChatConsent.Mode mode, int maxTurns,
                    String fromId) {
        this.root = root.toAbsolutePath().normalize();
        this.driveUrl = driveUrl;
        this.model = model;
        this.maxTurns = maxTurns;
        // Consent files live in the harness-owned store, NEVER the repo — a checkout must not be
        // able to arrive pre-trusted (see ChatConsent). The prompter is bound late so it can use
        // the terminal this REPL opens.
        this.consent = new ChatConsent(mode, this::askUser,
                ChatSession.storeDir(this.root).resolve("consent"),
                Path.of(System.getProperty("user.home"), ".codezaiku", "chat", "consent"));
        if (fromId != null) {
            // --from <id>: onboard before the first message, so even turn one knows the history.
            ChatSession.load(this.root, fromId).ifPresent(old ->
                    this.session = ChatSession.onboardFrom(this.root, old));
        }
    }

    /**
     * The interruption. Blocking on the same thread the loop runs on is correct here: the tool has
     * not started, nothing is racing, and the person is looking at the screen. A background prompt
     * would need a queue and a timeout to solve a problem that does not exist in a terminal.
     */
    private ChatConsent.Answer askUser(String what, List<String> preview) {
        io.println("");
        // The change first, the question second. Someone skimming should see WHAT before they see
        // that a decision is wanted — the other order invites a reflex `y`.
        for (String l : preview) io.println("     " + l);
        if (!preview.isEmpty()) io.println("");
        io.println("  ⏸  " + what + "?");
        while (true) {
            String a = io.readLine("     [y]es / [a]lways / all=stop asking / [n]o / n[e]ver / [s]top > ");
            if (a == null) { abandoned.set(true); return ChatConsent.Answer.NO; }   // input ended
            String t = a.strip().toLowerCase(java.util.Locale.ROOT);
            // A way OUT. Measured live: with no escape, every subsequent line — including the next
            // thing the person wanted to say, and `/quit` — was eaten as an invalid answer and the
            // prompt asked again forever. A gate you cannot walk away from is a trap, and typing a
            // slash command at it is someone telling you plainly that they have left.
            if (t.equals("s") || t.equals("stop") || t.startsWith("/")) {
                abandoned.set(true);
                io.println("     stopped — the turn is abandoned");
                return ChatConsent.Answer.NO;
            }
            switch (t) {
                // Empty is NOT yes. Measured in the first live conversation: a scripted session ran
                // out of input at a prompt, the blank line was read as consent, and a shell command
                // executed that nobody had approved. A default that says yes turns "I pressed enter"
                // and "the input ended" into approval — which is the one direction this must never
                // fail in. Approval has to be typed.
                case "y", "yes" -> { return ChatConsent.Answer.YES; }
                case "a", "always"  -> { return ChatConsent.Answer.YES_ALWAYS; }
                case "all", "yolo"  -> {
                    io.println("     yolo for the rest of the session — /mode ask brings the questions back");
                    return ChatConsent.Answer.YES_ALL;
                }
                case "n", "no"      -> { return ChatConsent.Answer.NO; }
                case "e", "never"   -> { return ChatConsent.Answer.NO_ALWAYS; }
                case "" -> io.println("     (nothing is assumed — type y, a, n or e)");
                default -> io.println("     y, a, n or e");
            }
        }
    }

    /** Runs until the person leaves. Returns a process exit code. */
    public int run() throws IOException {
        // Quiet screen, complete file: the console threshold goes to WARN and everything streams to
        // a per-run file under the session store — /logging changes the screen, never the file.
        logs.init(ChatSession.storeDir(root).resolve("logs"));
        if ("info".equalsIgnoreCase(org.codezaiku.Config.get("CODEZAIKU_CHAT_LOG", ""))) {
            logs.console("info");
        }
        io = ChatIo.open();
        // The rewind covers the budget: a REAL cap (not the unlimited sentinel) becomes the undo
        // depth, so granting 100 turns means being able to take all 100 back. The undo unit is the
        // USER turn — one journal record per message, first-touch pre-image per path — so /undo 1
        // already rewinds a whole run; the depth matters across a session's many messages.
        // Always at least the default; a HIGHER cap raises it to match — the budget you grant is
        // the rewind you keep, step by step.
        int suggested = maxTurns >= 1_000_000 ? ChatJournal.DEFAULT_DEPTH
                : Math.max(ChatJournal.DEFAULT_DEPTH, maxTurns);
        journal = new ChatJournal(root, ChatSession.storeDir(root), suggested);
        memory = new ChatMemory(ChatSession.storeDir(root));
        tasks = new ChatTasks(ChatSession.storeDir(root).resolve("tasks"), line -> {
            // May arrive while the person is typing; the io picks the safe way to show it.
            if (io != null) io.notifyLine(line);
        });
        // MCP servers this chat consumes (CODEZAIKU_MCP_SERVERS: name=command;name2=command2).
        // Started once per session; a broken entry is reported, not fatal.
        mcpClients = org.codezaiku.mcp.McpClient.fromConfig(
                org.codezaiku.Config.get("CODEZAIKU_MCP_SERVERS"),
                line -> { if (io != null) io.println("  ! " + line); });
        banner(io);
        try (LibraryIndex index = new LibraryIndex(org.codezaiku.FamiliarMain.libraryIndexDir())) {
            drive = new DriveClient(driveUrl, model);
            var lsp = LspClient.forProject(root, ProjectFacts.language(root));
            var library = new Library();
            while (true) {
                String line = io.readLine(consent.mode() + " > ");
                if (line == null) break;                 // ctrl-D
                line = line.strip();
                if (line.isEmpty()) continue;
                if (line.startsWith("/")) {
                    if (!command(io, line)) break;
                    continue;
                }
                turn(io, drive, lsp, library, index, line);   // drive is a field: /model swaps it
            }
        } finally {
            // A session that never completed a turn leaves NOTHING behind — not a state file and
            // not a transcript. The drive being down should not litter the store with files named
            // after a question that was never answered; the operator's very first run did exactly that,
            // and a transcript with no state beside it is a puzzle rather than a record.
            if (tasks != null) tasks.shutdown();
            for (var mc : mcpClients) mc.close();
            if (session != null && session.turns() == 0) {
                session.discard();
            } else if (session != null) {
                session.save();
                io.println("");
                io.println("saved  " + session.stateFile());
                io.println("       " + session.transcriptFile() + "  (what was said)");
                if (logs.file() != null) {
                    io.println("       " + logs.file() + "  (the run log — /sessionid next time)");
                }
            }
            io.close();
        }
        return 0;
    }

    /** {@code @path} tokens in a message, expanded to fenced file content. */
    private static final java.util.regex.Pattern AT_FILE =
            java.util.regex.Pattern.compile("(?<![\\w@])@([\\w./-]+)");
    /** Enough to be useful, small enough not to blow a small drive's window. */
    private static final int AT_FILE_MAX_CHARS = 8000;

    private String expandAtFiles(String text) {
        var m = AT_FILE.matcher(text);
        var b = new StringBuilder();
        while (m.find()) {
            String rel = m.group(1);
            Path f = root.resolve(rel).normalize();
            // Confined to the project — @/etc/passwd is a request the tools would refuse, and the
            // expansion must not be a way around them.
            if (!f.startsWith(root) || !java.nio.file.Files.isRegularFile(f)) {
                io.println("  (@" + rel + " not found — sent as plain text)");
                continue;
            }
            try {
                String content = java.nio.file.Files.readString(f);
                boolean cut = content.length() > AT_FILE_MAX_CHARS;
                if (cut) content = content.substring(0, AT_FILE_MAX_CHARS);
                b.append("[attached ").append(rel).append(cut ? " — first " + AT_FILE_MAX_CHARS + " chars]" : "]")
                 .append("\n```\n").append(content).append("\n```\n\n");
                session.sawFile(rel);
                io.println("  · attached " + rel + (cut ? " (truncated)" : ""));
            } catch (IOException e) {
                io.println("  (@" + rel + " unreadable: " + e.getMessage() + ")");
            }
        }
        return b.toString();
    }

    /** What {@code /v1/models} at {@code base} serves (comma-joined, may be empty), or null if dead. */
    private static String probeModels(String base) {
        try {
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/v1/models"))
                    .timeout(java.time.Duration.ofSeconds(8)).GET().build();
            var resp = java.net.http.HttpClient.newHttpClient()
                    .send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            var data = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(resp.body()).path("data");
            var names = new java.util.ArrayList<String>();
            for (var n : data) names.add(n.path("id").asText(""));
            return String.join(", ", names);
        } catch (Exception e) {
            return null;
        }
    }

    /** One git/shell helper for the small commands; empty string on failure. */
    private String sh(String... cmd) {
        try {
            var p = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor();
            return out.strip();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Sessions matching {@code want}: by prefix, or failing that by substring.
     *
     * <p>Ids are date-prefixed ({@code 2026-08-28-trust-me}), so the part a person actually
     * remembers — the slug — can never match a prefix rule. The battery found this: {@code /onboard
     * trust-me} reported "no session" about a session sitting right there. Prefix is tried first so
     * a full id stays exact; substring is the human path. Ambiguity still refuses to guess.
     */
    private static List<String[]> matchSessions(List<String[]> all, String want) {
        var byPrefix = all.stream().filter(r -> r[0].startsWith(want)).toList();
        if (!byPrefix.isEmpty()) return byPrefix;
        return all.stream().filter(r -> r[0].contains(want)).toList();
    }

    /** The repo root above {@code dir}, if {@code dir} is not itself a repo root. */
    private static Path gitRootAbove(Path dir) {
        if (java.nio.file.Files.isDirectory(dir.resolve(".git"))) return null;
        for (Path p = dir.getParent(); p != null; p = p.getParent()) {
            if (java.nio.file.Files.isDirectory(p.resolve(".git"))) return p;
        }
        return null;
    }

    private void banner(ChatIo io) {
        io.println("codezaiku chat — " + root);
        // Running `./codezaiku chat` from inside bin/ makes bin/ the project. Correct, and
        // surprising — the banner already showed the path and it was still missed, because nobody
        // reads a path they expected. Naming the repo ABOVE it is what turns a confusing session
        // into an obvious one. Reported from a real first run.
        Path above = gitRootAbove(root);
        if (above != null) {
            io.println("  note: only files under this directory are in scope.");
            io.println("        for the whole project, run from " + above);
        }
        io.println("drive " + driveUrl + " · " + consent.mode() + " — " + consent.mode().description());
        io.println("/help for commands, /quit to leave.  ctrl-C stops a turn, ctrl-D leaves");
        io.println("");
    }

    /** @return false to leave the REPL. */
    /**
     * The project's standing instructions, exactly as I honor CLAUDE.md: first existing of
     * FAMILIAR.md, CLAUDE.md, AGENTS.md, capped — working agreements ride every turn without the
     * person restating them. A colleague knows the house rules.
     */
    private String workingAgreements() {
        for (String name : new String[]{"FAMILIAR.md", "CLAUDE.md", "AGENTS.md"}) {
            java.nio.file.Path f = root.resolve(name);
            if (java.nio.file.Files.isRegularFile(f)) {
                try {
                    String t = java.nio.file.Files.readString(f);
                    if (t.length() > 3_000) t = t.substring(0, 3_000) + "\n…(truncated — read "
                            + name + " for the rest)";
                    return "[working agreements — this project's " + name + "]\n" + t + "\n\n";
                } catch (java.io.IOException e) {
                    return "";
                }
            }
        }
        return "";
    }

    private boolean command(ChatIo io, String line) {
        String[] p = line.split("\\s+", 2);
        String arg = p.length > 1 ? p[1].strip() : "";
        switch (p[0]) {
            case "/quit", "/exit" -> { return false; }
            case "/help" -> {
                io.println("  TALKING");
                io.println("    just type — it asks before writing or running anything, showing the change first");
                io.println("    @path in a message attaches that file (project files only)");
                io.println("    at a question:  y  ·  a always for THIS command  ·  all stop asking (session)");
                io.println("                    n  ·  e never for this command   ·  s abandon the turn");
                io.println("    ctrl-C stops a running turn (twice quits) · ctrl-D leaves");
                io.println("  PERMISSIONS");
                io.println("    /mode [plan|ask|auto-edit|yolo]  how often it asks; bare shows it");
                io.println("    /trust project|global            keep this session's answers beyond it");
                io.println("    /trusted                         every standing answer and where it lives");
                io.println("    /forget                          clear this session's standing answers");
                io.println("  WORKING");
                io.println("    /undo [n]                        step back n agent actions (default 1)");
                io.println("    /diff · /test                    what changed · run the test suite");
                io.println("    /tasks                           background tasks (it can start them; you get told)");
                io.println("    /cost                            tokens this session (what a metered drive bills)");
                io.println("    /commit [msg]                    stage and commit, after showing the command");
                io.println("    /model [url|name] [id]           show or switch the drive (probes first)");
                io.println("  SESSIONS");
                io.println("    /note [fact] · /unnote <fact>    pin a fact into the context — no model turn needed");
                io.println("    /remember <fact> · /forget-memory  PROJECT memory: carried into every future session");
                io.println("    /memory                          what this project remembers (it can save too — asks first)");
                io.println("    /state · /sessionid              what it remembers · this session's id + run log");
                io.println("    /sessions · /resume [id]         list earlier ones · reopen (bare = latest)");
                io.println("    /onboard <id>                    NEW session seeded from an old one");
                io.println("    /handoff [note] · /new           summary for the next session · start over");
                io.println("  WATCHING");
                io.println("    /thinking on|off                 show the model's reasoning as it streams");
                io.println("    /logging [level]                 what reaches the screen; the file gets all");
                io.println("    (CODEZAIKU_STREAM: on=reply as it streams · all=+thinking · unset=quiet)");
                io.println("  /quit leaves; everything is saved");
            }
            case "/remember" -> {
                if (arg.isEmpty()) { io.println("    usage: /remember <fact worth knowing next month>"); break; }
                try {
                    memory.remember(arg, "person");
                    io.println("    remembered → " + memory.file());
                } catch (java.io.IOException e) { io.println("    ! could not write memory: " + e.getMessage()); }
            }
            case "/forget-memory" -> {
                if (arg.isEmpty()) { io.println("    usage: /forget-memory <fragment of the entry>"); break; }
                try {
                    int n = memory.forget(arg);
                    io.println(n > 0 ? "    removed " + n + " entr" + (n == 1 ? "y" : "ies")
                            : "    nothing matched — /memory shows what is remembered");
                } catch (java.io.IOException e) { io.println("    ! " + e.getMessage()); }
            }
            case "/memory" -> {
                String m = memory.recall();
                io.println(m.isEmpty() ? "    no project memory yet — /remember <fact> starts it"
                        : m.stripTrailing());
            }
            case "/cost" -> {
                long pt = org.codezaiku.drive.DriveClient.SESSION_PROMPT_TOKENS.get();
                long ct = org.codezaiku.drive.DriveClient.SESSION_COMPLETION_TOKENS.get();
                if (pt + ct == 0) {
                    io.println("    no token usage reported yet (local drives report it too — after the first turn)");
                } else {
                    io.println(String.format("    this session: %,d prompt + %,d completion = %,d tokens", pt, ct, pt + ct));
                    io.println("    (a metered drive bills these; the run log's 'usage ←' lines are the per-call record)");
                }
            }
            case "/tasks" -> {
                var all = tasks.all();
                if (all.isEmpty()) { io.println("    no background tasks this session"); break; }
                for (ChatTasks.Job j : all) {
                    io.println("    " + j.id + "  " + j.state + "  " + j.label
                            + (j.state == ChatTasks.State.RUNNING ? "" : "  (exit " + j.exit + ")")
                            + "  → " + j.outFile);
                }
            }
            case "/mode" -> {
                if (arg.isEmpty()) {
                    for (ChatConsent.Mode m : ChatConsent.Mode.values()) {
                        io.println("    " + (m == consent.mode() ? "*" : " ") + " "
                                + String.format("%-10s", m) + m.description());
                    }
                    if (consent.standingCount() > 0) {
                        io.println("  " + consent.standingCount() + " standing answer(s) this session"
                                + " — /forget to clear them");
                    }
                } else {
                    ChatConsent.Mode.parse(arg).ifPresentOrElse(m -> {
                        consent.mode(m);
                        io.println("  " + m + " — " + m.description());
                    }, () -> io.println("  not a mode: " + arg));
                }
            }
            case "/trust" -> {
                var scope = switch (arg.toLowerCase(java.util.Locale.ROOT)) {
                    case "project" -> ChatConsent.Scope.PROJECT;
                    case "global" -> ChatConsent.Scope.GLOBAL;
                    default -> null;
                };
                if (scope == null) {
                    io.println("  /trust project   keep this session's answers for this project");
                    io.println("  /trust global    keep them for every project");
                    break;
                }
                int n = consent.trust(scope);
                io.println(n < 0 ? "  could not write the " + arg + " consent file"
                        : "  " + n + " answer(s) now standing for " + arg);
            }
            case "/trusted" -> {
                var all = consent.trusted();
                if (all.isEmpty()) { io.println("  (no standing answers)"); break; }
                for (String l : all) io.println("    " + l);
            }
            case "/onboard" -> {
                if (arg.isEmpty()) { io.println("  /onboard <id> — new session seeded from an old one"); break; }
                var matches = matchSessions(ChatSession.list(root), arg);
                if (matches.size() != 1) {
                    io.println(matches.isEmpty() ? "  no session matching '" + arg + "'"
                            : "  '" + arg + "' is ambiguous — /sessions to list");
                    break;
                }
                ChatSession.load(root, matches.get(0)[0]).ifPresentOrElse(old -> {
                    if (session != null && session.turns() > 0) session.save();
                    session = ChatSession.onboardFrom(root, old);
                    io.println("  new session, seeded from " + old.id());
                    io.println("  " + session.restate().replace("\n", "\n  "));
                }, () -> io.println("  could not read " + matches.get(0)[0]));
            }
            case "/handoff" -> {
                if (session == null || session.turns() == 0) {
                    io.println("  (nothing to hand off yet)");
                    break;
                }
                try {
                    session.save();
                    Path f = session.writeHandoff(arg);
                    io.println("  wrote " + f);
                    io.println("  pick it up later with:  codezaiku chat --from " + session.id());
                } catch (IOException e) {
                    io.println("  could not write the handoff: " + e.getMessage());
                }
            }
            case "/forget" -> {
                consent.forget();
                io.println("  standing answers cleared — you will be asked again");
            }
            case "/sessions" -> {
                var all = ChatSession.list(root);
                if (all.isEmpty()) { io.println("  (no earlier sessions in this project)"); break; }
                for (String[] r : all) {
                    io.println(String.format("    %-46s %2s turn(s)  %s", r[0], r[2], r[1]));
                }
                io.println("  /resume <id>   (a unique prefix is enough)");
            }
            case "/resume" -> {
                var all = ChatSession.list(root);
                if (arg.isEmpty() && all.isEmpty()) {
                    io.println("  (no earlier sessions in this project)");
                    break;
                }
                // No argument means the most recent, which is what someone reopening a laptop wants
                // nine times out of ten.
                final String want = arg.isEmpty() ? all.get(0)[0] : arg;
                var matches = matchSessions(all, want);
                if (matches.isEmpty()) {
                    io.println("  no session starting with '" + want + "' — /sessions to list them");
                } else if (matches.size() > 1) {
                    // Never guess between two. Resuming the wrong conversation is worse than asking.
                    io.println("  '" + want + "' matches " + matches.size() + " sessions:");
                    for (String[] r : matches) io.println("    " + r[0]);
                } else {
                    ChatSession.load(root, matches.get(0)[0]).ifPresentOrElse(loaded -> {
                        if (session != null && session.turns() > 0) session.save();
                        session = loaded;
                        io.println("  resumed " + loaded.id() + "  (" + loaded.turns() + " turn(s))");
                        String st = loaded.restate();
                        if (!st.isBlank()) io.println("  " + st.replace("\n", "\n  "));
                    }, () -> io.println("  could not read " + matches.get(0)[0]));
                }
            }
            case "/thinking" -> {
                if (arg.equalsIgnoreCase("on")) showThinking = true;
                else if (arg.equalsIgnoreCase("off")) showThinking = false;
                else if (!arg.isEmpty()) { io.println("  /thinking on|off"); break; }
                io.println("  thinking: " + (showThinking ? "shown (labelled and dimmed)" : "hidden"));
            }
            case "/logging" -> {
                if (arg.isEmpty()) {
                    io.println("  /logging off|error|warn|info|debug   what reaches the screen");
                    if (logs.file() != null) io.println("  the file always gets everything: " + logs.file());
                    break;
                }
                String r = logs.console(arg);
                io.println("  " + (r == null ? "not a level: " + arg + "  (off|error|warn|info|debug)" : r));
            }
            case "/sessionid" -> {
                // The join point for a diagnosis: the id names the session files, the log file names
                // the run, and the id is logged INTO the file when the session starts.
                io.println("  session: " + (session == null ? "(no session yet — say something first)"
                        : session.id()));
                if (logs.file() != null) io.println("  run log: " + logs.file());
            }
            case "/model" -> {
                if (arg.isEmpty()) {
                    io.println("  drive " + driveUrl + "  model " + model);
                    var names = org.codezaiku.Config.keysWithPrefix("CODEZAIKU_MODEL_");
                    for (String k : names) {
                        if (!k.endsWith("_URL")) continue;
                        String nm = k.substring("CODEZAIKU_MODEL_".length(), k.length() - 4)
                                .toLowerCase(java.util.Locale.ROOT);
                        io.println("    " + nm + "  ->  " + org.codezaiku.Config.get(k, ""));
                    }
                    io.println("  /model <url|saved-name> [modelId]   — switches after a live probe");
                    break;
                }
                String[] parts = arg.split("\\s+");
                String target = parts[0];
                // A saved name (codezaiku model add <name> <url>) resolves to its URL.
                String savedUrl = org.codezaiku.Config.get(
                        "CODEZAIKU_MODEL_" + target.toUpperCase(java.util.Locale.ROOT) + "_URL");
                if (savedUrl != null && !savedUrl.isBlank()) target = savedUrl;
                // Probe BEFORE switching — the same rule as `model use`: a dead endpoint is reported
                // now, not discovered as a confusing failure in the next turn. And it must answer
                // /v1/models, not merely accept a TCP connection.
                String serves = probeModels(target);
                if (serves == null) {
                    io.println("  nothing answering /v1/models at " + target + " — not switching");
                    break;
                }
                driveUrl = target;
                if (parts.length > 1) model = parts[1];
                drive = new DriveClient(driveUrl, model);
                io.println("  drive -> " + driveUrl + (serves.isBlank() ? "" : "  (serving " + serves + ")"));
            }
            case "/undo" -> {
                int n = 1;
                if (!arg.isEmpty()) {
                    try { n = Integer.parseInt(arg); }
                    catch (NumberFormatException e) { io.println("  /undo [n] — n is a number of turns"); break; }
                }
                String what = journal.undo(n);
                io.println("  " + what);
                if (session != null && what.startsWith("rewound")) {
                    // Files roll back; the conversation does not pretend. The model is TOLD, the
                    // same way a denial is an observation — its next turn must not reason from
                    // edits that no longer exist.
                    session.decided("the user undid the last " + n + " step(s) — " + what);
                    session.save();
                }
            }
            case "/diff" -> {
                if (!gitRepo()) { io.println("  (not a git repository)"); break; }
                String d = sh("git", "status", "--short");
                String st = sh("git", "diff", "--stat");
                if (d.isBlank() && st.isBlank()) { io.println("  (working tree clean)"); break; }
                for (String l : (st + d).split("\n")) if (!l.isBlank()) io.println("  " + l);
            }
            case "/test" -> {
                var v = org.codezaiku.verify.ProjectTests.verdict(root);
                String verdictLine = !v.ran() ? "no test suite ran"
                        : (v.passed() ? "PASS" : "FAIL")
                          + (v.passedCount() != null ? " — " + v.passedCount() + " passed" : "")
                          + (v.failedCount() != null && v.failedCount() > 0 ? ", " + v.failedCount() + " failed" : "");
                io.println("  " + verdictLine);
                if (session != null) { session.verdict(verdictLine); session.save(); }
            }
            case "/commit" -> {
                if (!gitRepo()) { io.println("  (not a git repository)"); break; }
                String msg = !arg.isEmpty() ? arg
                        : "chat: " + (session != null ? ChatSession.oneLine(session.title()) : "session changes");
                io.println("  git add -A && git commit -m \"" + msg + "\"");
                String a = io.readLine("  commit? [y/N] > ");
                if (a == null || !a.strip().equalsIgnoreCase("y")) { io.println("  (not committed)"); break; }
                sh("git", "add", "-A");
                String out = sh("git", "commit", "-m", msg);
                io.println("  " + (out.isBlank() ? "committed" : out.split("\n")[0]));
            }
            case "/note" -> {
                if (arg.isEmpty()) {
                    if (session == null || session.notes().isEmpty()) { io.println("  (no notes)"); break; }
                    for (String x : session.notes()) io.println("    - " + x);
                    break;
                }
                if (session == null) session = ChatSession.start(root, "notes");
                session.note(arg);
                session.save();
                io.println("  noted — it rides in every turn's context, ahead of the derived state");
            }
            case "/unnote" -> {
                if (session == null || arg.isEmpty()) { io.println("  /unnote <the note text>"); break; }
                session.unnote(arg);
                session.save();
                io.println("  removed (if it matched)");
            }
            case "/state" -> {
                if (session == null) { io.println("  (nothing yet)"); break; }
                String s = session.restate();
                io.println(s.isBlank() ? "  (nothing yet)" : "  " + s.replace("\n", "\n  "));
                if (session.dropped() > 0) {
                    io.println("  " + session.dropped() + " entries dropped by the caps — see "
                            + session.stateFile());
                }
            }
            case "/new" -> {
                if (session != null) session.save();
                session = null;
                io.println("  new session");
            }
            default -> io.println("  unknown: " + p[0] + "  (/help)");
        }
        return true;
    }

    private void turn(ChatIo io, DriveClient drive, LspClient lsp, Library library,
                      LibraryIndex index, String text) {
        if (session == null) {
            session = ChatSession.start(root, text);
            // Into the RUN LOG, so a log file can always be joined back to its session files.
            org.slf4j.LoggerFactory.getLogger(ChatRepl.class)
                    .info("chat session {} in {}", session.id(), root);
        }
        session.log("user", text);
        session.topic(session.turns() == 0 ? text : null);

        // @file: the person names the evidence, the harness injects it. Deterministic context beats
        // hoping the model reads the right file — the house rule about machine-computed evidence,
        // applied to chat — and it saves the read turns, which on a small local drive is the
        // difference between seconds and half a minute to the first useful token.
        String attachments = expandAtFiles(text);


        // Full tool set, always. What the agent may DO is decided in the moment by the person,
        // not up front by a mode — except PLAN, which removes the write path entirely so the
        // question never arises and the guarantee is structural rather than a promise to ask.
        var seen = new java.util.LinkedHashSet<String>();
        ToolRegistry tools = consent.mode() == ChatConsent.Mode.PLAN
                ? ToolRegistry.readOnly(root, lsp)
                : ToolRegistry.standard(root, lsp);
        // Web joins CHAT's registries only — the coding/run verbs keep their measured tool set
        // (adding tools there would silently change every benchmark). Both web tools are
        // read-shaped (searching is looking), so they ride at every rung including plan; what a
        // page RETURNS is untrusted text, which the loop already fences. Off unless the operator
        // set a search endpoint: an advertised tool that always fails teaches the model to stop
        // calling tools (the FindSymbolTool rule).
        if (org.codezaiku.Config.get("CODEZAIKU_SEARXNG") != null
                || org.codezaiku.Config.get("CODEZAIKU_CHAT_WEB") != null) {
            tools.add(new org.codezaiku.tools.WebSearchTool())
                 .add(new org.codezaiku.tools.WebFetchTool());
        }
        if (consent.mode() != ChatConsent.Mode.PLAN) {
            tools.add(new org.codezaiku.tools.RememberTool(memory));
            // Long work without holding the conversation. Consent-wise this IS shell (canonical
            // in ChatConsent); journal-wise the step is declared not-coverable (see Narrator).
            tools.add(new org.codezaiku.tools.RunBackgroundTool(tasks));
            tools.add(new org.codezaiku.tools.DelegateTool(tasks, root, driveUrl, model));
        }
        for (var mc : mcpClients) {
            // Remote tools ride at every rung ABOVE plan: their consent is per-call (fail closed
            // in ChatConsent — nothing local knows whether a remote tool mutates).
            if (consent.mode() == ChatConsent.Mode.PLAN) break;
            try {
                for (var rt : mc.listTools()) {
                    tools.add(new org.codezaiku.tools.McpBridgeTool(mc, rt));
                }
            } catch (Exception e) {
                io.println("  ! mcp: " + mc.serverName() + " tools/list failed: " + e.getMessage());
            }
        }
        tools.listener(new Narrator(io, seen, consent, journal));

        String digest = tasks == null ? "" : tasks.digestInto();
        String goal = session.restate() + memory.recall() + workingAgreements()
                + digest + attachments + text;
        abandoned.set(false);
        // Streamed prose appears as it is generated, when CODEZAIKU_STREAM is on. What streams is
        // the finishing tool's summary, decoded out of the argument fragments — the loop runs
        // tool_choice=required, so plain content never exists (measured: the first live streaming
        // run streamed a whole turn to a silent screen). Anything that DID stream must not print
        // again at turn end, or every answer appears twice.
        var streamed = new StringBuilder();
        var thinking = new AtomicBoolean();
        org.codezaiku.drive.DriveClient.streamTo(
                piece -> {
                    // A line break when the voice changes: thinking runs straight into the reply
                    // otherwise, and the seam between them is exactly what a reader needs to see.
                    if (thinking.getAndSet(false)) io.print("\n\n");
                    synchronized (streamed) { streamed.append(piece); }
                    io.print(piece);
                },
                piece -> {
                    if (!showThinking) return;
                    if (!thinking.getAndSet(true)) io.printThinkingStart();
                    io.printThinking(piece);
                });

        FamiliarLoop.Result result;
        // ctrl-C stops the turn and keeps the conversation. The loop checks `abandoned` between
        // steps, so this lands at the next boundary rather than tearing anything down mid-write.
        try (AutoCloseable ignored = io.onInterrupt(() -> {
            if (abandoned.compareAndSet(false, true)) {
                io.println("");
                io.println("  (stopping — finishing the step in flight; ctrl-C again to quit)");
            } else {
                // Twice means leave, the way every other tool behaves. Someone pressing it a second
                // time has decided the first press did not work, and arguing with them by staying
                // open is the wrong answer.
                io.println("  (quitting)");
                if (session != null && session.turns() > 0) session.save();
                Runtime.getRuntime().halt(130);      // 128 + SIGINT
            }
        })) {
            result = new FamiliarLoop(drive, tools, root, goal, maxTurns, library, index)
                    .chat()          // one task_done ends the turn — the person is the verifier
                    .lsp(lsp)
                    .cancelIf(abandoned::get)
                    .run();
        } catch (RuntimeException e) {
            // A drive that is down, or a request that will not fit, must not end the conversation —
            // the person can change the rung, or start the server, and carry on.
            io.println("  ! " + e.getMessage());
            session.log("error", String.valueOf(e.getMessage()));
            return;
        } catch (Exception e) {
            // onInterrupt's close() is declared to throw; nothing here actually does.
            io.println("  ! " + e);
            return;
        }

        org.codezaiku.drive.DriveClient.streamTo(null);
        io.println("");
        if (abandoned.get()) {
            // Say the turn was cut short. A partial result presented as a finished one is how
            // someone ends up believing work happened that did not.
            io.println("(stopped — whatever had already been applied is still applied)");
        }
        // Print the summary UNLESS the stream already showed it — compared by content, not by
        // count. "Anything streamed" was the first rule, and it ate a real answer: a turn that hit
        // the max-turns cap gets a HARNESS-made summary that never streamed, but a few earlier
        // characters had, so the screen went blank exactly when the person most needed to be told
        // what happened.
        String summary = result.summary() == null ? "(no answer)" : result.summary();
        // The 8/8 experience, diagnosed from the operator's real run: a work-shaped ask hit the turn cap
        // mid-fix and the session just... stopped, with nothing saying why or what to do. The cap
        // is fine — a conversational default must not let one ask run forever — but silence at the
        // cap is not. Say it, and say the two ways forward.
        if (summary.startsWith("max turns")) {
            // Only reachable when someone SET a cap — the default is unlimited, because the person
            // watching the turn is the real cap (ctrl-C). Still worth saying the way forward.
            summary = summary + "\n(a cap of " + maxTurns + " was set — say `continue` for another "
                    + maxTurns + " from where it stood; the session carries)";
        }
        String shown;
        synchronized (streamed) { shown = streamed.toString().strip(); }
        if (!shown.isEmpty()) { io.println(""); io.println(""); }   // close the streamed line
        // Symmetric: skip only when one already contains the other's tail. A task_blocked summary
        // carries a "task_blocked: " prefix its streamed reason never had, and the first endsWith
        // check printed both — the reason once streamed, then again inside the summary.
        String sum = summary.strip();
        boolean alreadyShown = !shown.isEmpty()
                && (shown.endsWith(sum) || sum.endsWith(shown) || sum.contains(shown));
        if (!alreadyShown) io.println(summary);
        io.println("");

        seen.forEach(session::sawFile);
        session.turnDone();
        session.log("agent", result.summary());
        if (result.done() && result.summary() != null) session.decided(firstLine(result.summary()));
        session.save();
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return ChatSession.oneLine(i < 0 ? s : s.substring(0, i));
    }

    /**
     * Live tool narration. This is the seam ACP already drives, reused rather than reinvented — and
     * it is what makes a 30-second turn watchable without token streaming.
     */
    private static final class Narrator implements ToolRegistry.Listener {
        private final ChatIo io;
        private final java.util.Set<String> files;
        private final ChatConsent consent;
        private final ChatJournal journal;

        Narrator(ChatIo io, java.util.Set<String> files, ChatConsent consent, ChatJournal journal) {
            this.io = io;
            this.files = files;
            this.consent = consent;
            this.journal = journal;
        }

        /** Consent runs first; narration is what happens once it is allowed. */
        @Override public String permit(String tool, JsonNode args) {
            String denial = consent.permit(tool, args);
            if (denial == null && journal != null) {
                // Pre-images are captured HERE — after consent, before execution — because this is
                // the one moment the old content still exists and the mutation is already allowed.
                // EACH mutating action is its own step, so /undo walks back through what the agent
                // did action by action — the correction the operator asked for — and the label tells the
                // person exactly what each rewound step was.
                if ("write_file".equals(tool) || "edit_file".equals(tool)) {
                    String path = args == null ? null : args.path("path").asText(null);
                    journal.beginStep(ChatConsent.describe(tool, args));
                    journal.preWrite(path);
                } else if ("shell".equals(tool) && !ChatConsent.inspectsForJournal(args)) {
                    journal.beginStep(ChatConsent.describe(tool, args));
                    journal.preShell();
                } else if ("delegate".equals(tool)) {
                    journal.beginStep(ChatConsent.describe(tool, args));
                    journal.notCovered();   // the sub-loop mutates after this turn ends
                } else if ("run_background".equals(tool)) {
                    // A background process mutates AFTER the turn ends; no pre-image can cover
                    // that. The step exists so the range says PARTIAL instead of lying.
                    journal.beginStep(ChatConsent.describe(tool, args));
                    journal.notCovered();
                }
            }
            return denial;
        }

        @Override public void started(String callId, String tool, JsonNode args) {
            String path = args != null && args.hasNonNull("path") ? args.get("path").asText() : null;
            if (path != null) files.add(path);
            String detail = path != null ? path
                    : args != null && args.hasNonNull("command") ? args.get("command").asText() : "";
            if (detail.length() > 70) detail = detail.substring(0, 67) + "...";
            // The leading newline is for streaming: reasoning arrives without a trailing break, and
            // without this the tool line glues onto the middle of a streamed sentence.
            io.println("\n  · " + tool + (detail.isEmpty() ? "" : "  " + detail));
        }

        @Override public void finished(String callId, String tool, String result, boolean failed) {
            if (failed) io.println("    failed");
        }
    }

}
