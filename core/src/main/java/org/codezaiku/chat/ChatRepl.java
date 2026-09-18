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
    /** The plan step: auto = for build-sized asks (the default), on = every turn, off = never. */
    enum PlanMode { auto, on, off }
    private volatile PlanMode planMode = PlanMode.auto;
    /** A line typed at the stop question that was a command; the main loop takes it before reading the keyboard again. */
    private String queuedLine = null;
    private ChatSession session;
    // /research mode: the REFINE conversation that produces a Research Requirements Document.
    // Non-null = active. `/research go` hands the brief to the daemon; the deep run happens there.
    private ResearchBrief brief;
    // jobs this chat launched, so the prompt can say when one lands
    private final java.util.List<String> launched = new java.util.ArrayList<>();
    private final java.util.Set<String> reported = new java.util.HashSet<>();
    // the run() locals a command needs to start a turn itself (orientation on /research <topic>)
    private LspClient lspRef; private Library libraryRef; private LibraryIndex indexRef; private ChatIo ioRef;

    /**
     * Loop steps ONE message may spend while research mode is on: the refine conversation
     * orients with a general search or two and asks — it never researches in depth (that is
     * the daemon's run). The chat's turn budget is otherwise unlimited (the person is the cap),
     * and an uncapped research-mode turn was a full research run per message with no visible
     * end (the operator, 2026-09-03: "I never get out of this").
     */
    static final int RESEARCH_TURN_STEPS = Integer.parseInt(org.codezaiku.Config.get("CODEZAIKU_RESEARCH_TURN_STEPS", "5"));
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
            lspRef = lsp; libraryRef = library; indexRef = index; ioRef = io;
            while (true) {
                reportLanded(io);
                String line;
                if (queuedLine != null) { line = queuedLine; queuedLine = null; io.println(consent.mode() + " > " + line); }
                else line = io.readLine(consent.mode() + " > ");
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
        String self = org.codezaiku.SelfUpdate.maybeAuto();
        if (self.isEmpty()) self = org.codezaiku.SelfUpdate.updateNotice();
        if (!self.isEmpty()) io.println(self);
        String rz = org.codezaiku.ResearchZoshoInstall.updateNotice();
        if (!rz.isEmpty()) io.println(rz);
        io.println("/help for commands, /quit to leave.  ctrl-C stops a turn, ctrl-D leaves");
        io.println("");
    }

    /** @return false to leave the REPL. */
    /**
     * The project's standing instructions, exactly as I honor CLAUDE.md: first existing of
     * FAMILIAR.md, CLAUDE.md, AGENTS.md, capped — working agreements ride every turn without the
     * person restating them. A colleague knows the house rules.
     */
    /** A message that will create or change things, as opposed to a question or a small edit. Cheap, and the plan call can still say NO PLAN. */
    static boolean looksLikeBuild(String text) {
        if (text == null) return false;
        String t = text.toLowerCase(java.util.Locale.ROOT);
        if (t.length() < 12) return false;
        boolean verb = java.util.regex.Pattern.compile("\\b(build|create|make|write|implement|add|refactor|set ?up|generate|convert|migrate|scaffold|design|develop|port|rewrite|do (it|that|all|everything|the rest|[0-9])|let'?s do|can (u|you) do)\\b").matcher(t).find();
        boolean small = java.util.regex.Pattern.compile("\\b(one[- ]liner|typo|rename|comment|just (tell|explain|show|answer|say))\\b").matcher(t).find();
        return verb && !small;
    }

    /**
     * Ask the model for a short plan, show it, and let the person say go, change it, or skip. Returns the approved
     * plan, "" for no plan, or null when the person cancelled the turn. The model may answer NO PLAN for an ask that
     * turns out to be a question or a one-line edit.
     */
    private String planStep(ChatIo io, DriveClient drive, String context, String ask, boolean mayDecline) {
        String changes = "";
        for (int round = 1; round <= 3; round++) {
            io.print("  planning… "); 
            String plan;
            try {
                var json = new com.fasterxml.jackson.databind.ObjectMapper();
                var msgs = json.createArrayNode();
                msgs.addObject().put("role", "system").put("content",
                        "You plan work for a coding assistant that runs in the project directory named below. Write a SHORT plan the person can approve: "
                        + "under 14 lines. Sections, each one line or a few bullets: FILES (each file you will create or change, with its path); "
                        + "APPROACH (how, in plain words); INSTALLS (packages or tools you would install, or 'none'); NOT DOING (what you will leave out). "
                        + "Prefer the simplest thing that meets the ask: no server, framework or extra tool unless the ask needs one. "
                        // With /plan on the step runs for every message, so the model may wave a question through. For a message
                        // that already looks like a build there is no such door: the model declined a plain "do all 6" once.
                        + (mayDecline ? "If the message is a question, an opinion, or a change small enough to just do, reply with exactly: NO PLAN" : "Always write the plan."));
                msgs.addObject().put("role", "user").put("content", context + "\n\nTHE MESSAGE TO PLAN FOR:\n" + ask
                        + (changes.isEmpty() ? "" : "\n\nTHE PERSON'S CHANGES TO YOUR LAST PLAN (apply them):\n" + changes));
                plan = drive.classify(msgs, 700).strip();
            } catch (RuntimeException e) {
                io.println("no plan (" + firstLine(String.valueOf(e.getMessage())) + ") — going ahead");
                return "";
            }
            org.slf4j.LoggerFactory.getLogger(ChatRepl.class).info("plan step (round {}): {}", round, plan.replace("\n", " / "));
            if (plan.isEmpty() || (mayDecline && plan.toUpperCase(java.util.Locale.ROOT).startsWith("NO PLAN"))) { io.println("no plan needed"); return ""; }
            io.println("");
            io.println("");
            io.println("  " + plan.replace("\n", "\n  "));
            io.println("");
            String a = io.readLine("  go / type what to change / skip (no plan) / stop > ");
            org.slf4j.LoggerFactory.getLogger(ChatRepl.class).info("plan step: the person answered [{}]", a);
            if (session != null) session.log("plan", plan + "\n-- answer: " + a);
            if (a == null || a.strip().equalsIgnoreCase("stop") || a.strip().equalsIgnoreCase("/quit")) return null;
            String ans = a.strip();
            if (ans.isEmpty() || ans.equalsIgnoreCase("go") || ans.equalsIgnoreCase("y") || ans.equalsIgnoreCase("yes") || ans.equalsIgnoreCase("ok")) return plan;
            if (ans.equalsIgnoreCase("skip")) return "";
            changes = changes.isEmpty() ? ans : changes + "\n" + ans;
        }
        return "";
    }

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
                io.println("    /plan [auto|on|off]              a plan you approve before a build starts (auto: for build asks)");
                io.println("    /trust project|global            keep this session's answers beyond it");
                io.println("    /trusted                         every standing answer and where it lives");
                io.println("    /forget                          clear this session's standing answers");
                io.println("  WORKING");
                io.println("    /undo [n]                        step back n agent actions (default 1)");
                io.println("    /diff · /test                    what changed · run the test suite");
                io.println("    /tasks                           background tasks (it can start them; you get told)");
                io.println("    /cost                            tokens this session (what a metered drive bills)");
                io.println("  THE LIBRARIAN");
                io.println("    /setup librarian                 install ResearchZosho (the library) and set it up, here");
                io.println("    /librarian <question>            what the shelves hold — no model, instant");
                io.println("    /research <topic>                refine a research brief in conversation; then /research go");
                io.println("    /research · go · status · off    the brief · file the deep run with the daemon · jobs · discard");
                io.println("    /research read <J-id|I-id|all>   print a finished research result here; all marks every finished run read");
                io.println("    /commit [msg]                    stage and commit, after showing the command");
                io.println("    /model [url|name] [id]           show or switch the drive (probes first)");
                io.println("  SESSIONS");
                io.println("    /note [fact] · /unnote <fact>    pin a fact into the context — no model turn needed");
                io.println("    /remember <fact> · /forget-memory  PROJECT memory: carried into every future session");
                io.println("    /memory                          what this project remembers (it can save too — asks first)");
                io.println("    /librarian <question>            ask The Librarian's shelves (the long-term library)");
                io.println("    /research <topic>                work the stacks: live mind map; done submits · off discards");
                io.println("    /state · /sessionid              what it remembers · this session's id + run log");
                io.println("    /sessions · /resume [id]         list earlier ones · reopen (bare = latest)");
                io.println("    /onboard <id>                    NEW session seeded from an old one");
                io.println("    /handoff [note] · /new           summary for the next session · start over");
                io.println("  WATCHING");
                io.println("    /thinking on|off                 show the model's reasoning as it streams");
                io.println("    /logging [level]                 what reaches the screen; the file gets all");
                io.println("    (CODEZAIKU_STREAM: on=the reply as it streams · all=also the thinking · unset=the reply whole, the default)");
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
            case "/research" -> {
                // Two phases (the operator, 2026-09-03: ). REFINE: a conversation that orients (the shelves,
                // then a general search or two) and asks, producing a Research Requirements Document.
                // GO: the brief is filed with the daemon, whose one worker runs the deep research and
                // lands a draft investigation; the chat says so when it does.
                if (arg.isEmpty()) {
                    io.println(brief == null
                            ? "    usage: /research <what you want to understand>   starts a refine conversation\n"
                              + "           then: /research shows the brief · /research go runs it (daemon) · /research status · /research read <J-id> · /research off"
                            : brief.render().stripTrailing());
                } else if (arg.equals("off")) {
                    if (brief != null) { io.println("    research mode off — the brief is discarded (nothing was filed)"); brief = null; }
                    else io.println("    research mode was not on");
                } else if (arg.equals("done")) {
                    io.println("    there is no 'done' — /research go files the brief for the deep run; /research off discards it");
                } else if (arg.equals("status")) {
                    researchStatus(io);
                } else if (arg.startsWith("read ")) {
                    researchRead(io, arg.substring(5).strip());
                } else if (arg.startsWith("go")) {
                    if (brief == null) { io.println("    research mode is not on — /research <topic> first"); break; }
                    if (!org.codezaiku.research.LibraryBridge.answers()) {
                        io.println("    no library answers on " + org.codezaiku.research.LibraryBridge.url() + " — /setup librarian installs ResearchZosho, or start its service (the brief is kept)");
                        break;
                    }
                    if (org.codezaiku.research.LibraryBridge.token() == null) {
                        io.println("    filing a run needs a write token: /setup librarian makes one, or set CODEZAIKU_LIBRARIAN_TOKEN (the brief is kept)");
                        break;
                    }
                    String mode = arg.contains("depth") ? "depth" : arg.contains("broad") ? "broad" : brief.depth();
                    try {
                        // no ceiling unless the person set one: the run goes until the work is done (2026-09-07)
                        String goTurns = org.codezaiku.Config.get("CODEZAIKU_RESEARCH_GO_TURNS", "");
                        int turns = goTurns != null && goTurns.matches("\\d+") ? Integer.parseInt(goTurns) : 0;
                        // The brief's sub-questions ARE the runner's plan — the refine phase's work, not redone.
                        var r = org.codezaiku.research.LibraryBridge.client().research(brief.toQuestion(), mode, turns, brief.subQuestions());
                        String id = r.get("job_id").asText();
                        launched.add(id);
                        io.println("    filed " + id + " (" + mode + ") — the library's worker runs it; you can keep talking or leave");
                        io.println("    " + brief.render().lines().skip(1).map(String::strip).filter(l -> l.startsWith("question:")).findFirst().orElse(""));
                        io.println("    /research status shows it; the chat says when it lands — in this session or the next — and /research read " + id + " prints it.");
                    io.println("    Research mode is off.");
                        brief = null;
                    } catch (org.researchzosho.client.LibraryException e) {
                        io.println("    ! " + e.getMessage() + " [" + e.code + "]");
                    } catch (Exception e) {
                        io.println("    ! could not file: " + e.getMessage() + " (brief kept)");
                    }
                } else {
                    brief = new ResearchBrief(arg);
                    io.println("    research mode ON — refining a brief for: " + arg);
                    io.println("    Talk it through: what you want to understand, what to leave out, which sources matter.");
                    io.println("    It checks the shelves and does a general search or two to orient — no deep research here.");
                    io.println("      /research        shows the brief so far");
                    io.println("      /research go     files the brief with the daemon for the deep run (go depth | go broad)");
                    io.println("      /research off    discards it");
                    // orientation: the first turn is the topic itself — the shelves, one general search, a question back
                    if (ioRef != null && drive != null) turn(ioRef, drive, lspRef, libraryRef, indexRef, arg);
                }
            }
            case "/setup" -> {
                // `/setup librarian`: install ResearchZosho if it is not here yet, then its setup conversation, in this terminal.
                if (!arg.equals("librarian") && !arg.equals("researchzosho")) { io.println("    usage: /setup librarian   (install ResearchZosho and set it up)"); break; }
                int rc = org.codezaiku.ResearchZoshoInstall.door(new String[0], System.out);
                io.println(rc == 0 ? "    done — /librarian <question> asks the library" : "    setup did not finish (exit " + rc + ")");
            }
            case "/librarian" -> {
                // The desk, in chat: the deterministic answer package — exactly what the shelves
                // hold, states and sources included, plus open threads. Model-free on purpose:
                // what you read is what the library says, not a composition over it.
                if (arg.isEmpty()) {
                    io.println("    usage: /librarian <question>   (asks the library, not the web)");
                    break;
                }
                io.println(org.codezaiku.research.LibraryBridge.answer(arg, 5).stripTrailing());
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
            case "/plan" -> {
                if (arg.equalsIgnoreCase("on")) planMode = PlanMode.on;
                else if (arg.equalsIgnoreCase("off")) planMode = PlanMode.off;
                else if (arg.equalsIgnoreCase("auto")) planMode = PlanMode.auto;
                else if (!arg.isEmpty()) { io.println("  /plan auto|on|off"); break; }
                io.println("  plan: " + switch (planMode) {
                    case auto -> "auto — before an ask that builds or changes things, you see the plan and say go";
                    case on -> "on — every turn starts with a plan you approve";
                    case off -> "off — no plan step";
                });
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
            tools.add(new org.codezaiku.tools.WebSearchTool().focus(text))
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
        Narrator narrator = new Narrator(io, seen, consent, journal);
        tools.listener(narrator);
        Ticker ticker = new Ticker(io, narrator);

        String digest = tasks == null ? "" : tasks.digestInto();
        // The library's push: relevant holdings ride into the turn's context (push before pull —
        // the model never has to call a recall tool to benefit). "" when absent or irrelevant.
        String stacks = org.codezaiku.research.LibraryBridge.push(text, 4);
        // Research mode rides as context, not as a different loop: the map shows what is already
        // organized so the model extends it instead of re-gathering (same push-not-pull logic).
        String mapBlock = brief == null ? ""
                : "RESEARCH REFINE MODE — you are The Librarian helping the person write a research brief on: "
                  + brief.topic() + "\nTHE BRIEF SO FAR:\n" + brief.render()
                  + "\nYOUR JOB THIS TURN: orient and refine, never research in depth. First use what the LIBRARY block above holds "
                  + "(say so if it holds nothing). If the topic is unfamiliar or ambiguous, run ONE or TWO general web searches "
                  + "(an encyclopedia-level overview is enough) to make sure you and the person mean the same thing. Then answer "
                  + "briefly and ask ONE clarifying question that sharpens the brief — scope, sub-questions, sources, exclusions, "
                  + "what a good result would contain. Reflect back what you now understand the ask to be. The deep research "
                  + "runs later, from the finished brief.\n";
        // the previous reply gets a sixteenth of the window, in characters: 6,000 was right for a 64k window and a
        // fifth of the fixed cost on a 32k one
        int window = drive.contextWindow();
        int exchangeChars = Math.max(2_000, (int) (window / 16 * 2.5));
        String goal = session.restate() + session.lastExchange(exchangeChars) + memory.recall() + workingAgreements()
                + stacks + mapBlock + digest + attachments + text;
        abandoned.set(false);
        // THE PLAN STEP. Before a build starts, the model says what it will do and the person says go or changes
        // it. Decided 2026-09-18 after a build ask installed FastAPI and set out to write a server nobody wanted.
        String approvedPlan = "";
        if (planMode == PlanMode.on || (planMode == PlanMode.auto && looksLikeBuild(text))) {
            approvedPlan = planStep(io, drive, goal, text, planMode == PlanMode.on);
            if (approvedPlan == null) return;           // the person cancelled the turn at the plan
            if (!approvedPlan.isEmpty()) goal = goal + "\n\n[THE PLAN THE PERSON APPROVED — do exactly this; anything not in it, such as installing a package or starting a server, asks first]\n" + approvedPlan + "\n";
        }
        consent.plan(approvedPlan);
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
                    ticker.quiet();               // the answer is arriving; no status line under it
                    io.print(piece);
                },
                piece -> {
                    ticker.quiet();
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
            // research mode: a bounded turn (a few searches, then answer); otherwise the person is the cap
            int turnBudget = brief != null ? Math.min(maxTurns, RESEARCH_TURN_STEPS) : maxTurns;
            ticker.start();
            try {
                result = new FamiliarLoop(drive, tools, root, goal, turnBudget, library, index)
                        .chat()          // one task_done ends the turn — the person is the verifier
                        .lsp(lsp)
                        .cancelIf(abandoned::get)
                        .run();
            } finally {
                ticker.stop();
            }
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
        String continueWith = null;
        if (abandoned.get()) {
            // Say the turn was cut short. A partial result presented as a finished one is how
            // someone ends up believing work happened that did not.
            io.println("(stopped — whatever had already been applied is still applied)");
            // ctrl-C is the pause. What the person types here goes straight into a continuation turn, so a run
            // that is drifting can be steered without starting over ("no server — plain HTML and JS").
            String note = io.readLine("  What should change? (Enter to leave it stopped) > ");
            if (note != null && note.strip().startsWith("/")) queuedLine = note.strip();     // a command, not a note: the main loop runs it
            else if (note != null && !note.isBlank()) continueWith = note.strip();
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
        io.println("\u001b[2m  " + (abandoned.get() ? "stopped" : "done") + " · " + narrator.steps() + " step(s) · " + Ticker.elapsed(ticker.startedAt()) + "\u001b[0m");
        io.println("");

        seen.forEach(session::sawFile);
        session.turnDone();
        session.log("agent", result.summary());
        if (continueWith != null) {
            session.turnDone();
            session.save();
            turn(io, drive, lsp, library, index, "Continue the work you were doing, with this change: " + continueWith
                    + "\nKeep what is already done and fits; redo only what the change touches.");
            return;
        }
        if (result.done() && result.summary() != null) session.decided(firstLine(result.summary()));
        session.save();

        // Refine-mode harvest: ONE classify call proposes how the turn changes the brief; the
        // machine applies it (append/dedupe/remove) — the model never edits the document directly.
        // A failed or unparseable proposal means the brief does not move this turn — never an error.
        if (brief != null && result.summary() != null && !abandoned.get()) {
            try {
                var json = new com.fasterxml.jackson.databind.ObjectMapper();
                var msgs = json.createArrayNode();
                msgs.addObject().put("role", "user").put("content",
                        "You maintain a RESEARCH BRIEF (requirements document) from a conversation.\n\nBRIEF SO FAR:\n"
                        + brief.render()
                        + "\nTHE TURN — the person said:\n" + org.codezaiku.research.LibraryBridge.compress(text, 600)
                        + "\nThe librarian answered:\n" + org.codezaiku.research.LibraryBridge.compress(result.summary(), 1800)
                        + "\n\nAnswer with a JSON object ONLY describing what the turn CHANGES (omit what it does not): "
                        + "{\"question\": \"the refined research question, one sentence\", \"depth\": \"broad|depth\", "
                        + "\"scope_in\": [..], \"scope_out\": [..], \"sub_questions\": [..], \"sources\": [\"languages or kinds of source wanted\"], "
                        + "\"asks\": [\"specific deliverables the person asked for\"], \"held\": [\"library entry ids already covering part of it\"]}\n"
                        + "Prefix an item with - to remove it. Only what the PERSON established or agreed to — not the librarian's guesses.");
                String raw = drive.classify(msgs, 600);
                int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
                if (a >= 0 && b > a) {
                    int n = brief.apply(json.readTree(raw.substring(a, b + 1)));
                    if (n > 0) io.println("(brief: " + n + " change(s) — /research shows it; /research go when it says what you want)");
                }
            } catch (Exception ignored) {
                // the conversation is the product; the brief is its record, never a blocker
            }
        }
    }

    /** `/research status`: the active jobs, the last ten finished, and how many more there are. */
    /** `/research status`: this patron's jobs on the daemon — the active ones, the last ten finished, unread marked. */
    private void researchStatus(ChatIo io) {
        if (!org.codezaiku.research.LibraryBridge.answers()) { io.println("    no library answers on " + org.codezaiku.research.LibraryBridge.url() + " — /setup librarian installs ResearchZosho, or start its service"); return; }
        try {
            var page = org.codezaiku.research.LibraryBridge.client().jobs(10, null);
            var read = readJobs();
            int shown = 0;
            for (var j : page.path("active")) { if (statusLine(io, j, read)) shown++; }
            for (var j : page.path("finished")) { if (statusLine(io, j, read)) shown++; }
            long total = page.path("finished_total").asLong(0);
            if (total > page.path("finished").size()) io.println("    … and " + (total - page.path("finished").size()) + " older finished job(s) on the daemon");
            if (shown == 0) io.println("    no research jobs filed from here yet — /research <topic>, then /research go");
            else io.println("    ◆ = landed, not yet read — /research read <J-id> prints it");
        } catch (Exception e) {
            io.println("    ! " + e.getMessage());
        }
    }

    private boolean statusLine(ChatIo io, JsonNode j, java.util.Set<String> read) {
        if (!"research".equals(j.path("kind").asText("research"))) return false;
        String id = j.path("job_id").asText("");
        String st = j.path("state").asText("");
        boolean unread = ("done".equals(st) || "failed".equals(st)) && !read.contains(id);
        String q = j.path("question").asText("");
        int cut = q.indexOf('\n');
        io.println("    " + (unread ? "◆ " : "  ") + id + "  " + st + "  " + j.path("elapsed_s").asLong(0) + "s  "
                + org.codezaiku.research.LibraryBridge.compress(cut > 0 ? q.substring(0, cut) : q, 90)
                + (j.hasNonNull("investigation") ? "  → " + j.get("investigation").asText() : ""));
        return true;
    }

    /**
     * Before each prompt: mention, once per chat session, every research job of ours that has landed
     * and has NOT been read yet — including ones filed in an earlier chat that finished while nobody
     * was here. The mark is cleared when the result is READ (/research read), never by the mention.
     * The read marks live in CodeZaiku's own home, since the ledger is the daemon's.
     */
    private void reportLanded(ChatIo io) {
        try {
            if (!org.codezaiku.research.LibraryBridge.answers()) return;
            var read = readJobs();
            var page = org.codezaiku.research.LibraryBridge.client().jobs(20, null);
            for (var j : page.path("finished")) {
                String id = j.path("job_id").asText("");
                if (id.isEmpty() || read.contains(id) || reported.contains(id)) continue;
                reported.add(id);
                String st = j.path("state").asText();
                String q = j.path("question").asText("");
                int cut = q.indexOf('\n');
                io.println("  ◆ research " + id + ("done".equals(st) ? " landed" : " FAILED") + " — "
                        + org.codezaiku.research.LibraryBridge.compress(cut > 0 ? q.substring(0, cut) : q, 100));
                if ("done".equals(st) && !j.hasNonNull("investigation")) {
                    // nothing came of it and there is nothing to read, so this is said once; before, it was said at
                    // every start until the person ran /research read on each one (eight of them, 2026-09-18)
                    io.println("    the library refused it at intake, so there is no report to read. Its question is on the library's open questions page.");
                    markRead(id);
                } else if ("done".equals(st)) {
                    io.println("    " + j.get("investigation").asText() + " is in the library.  /research read " + id + " prints it (and marks it read); the library's housekeeping extracts its findings");
                } else {
                    io.println("    " + firstLine(j.path("result").asText("")) + "   (/research read " + id + " marks it read)");
                }
            }
        } catch (Exception ignored) {
            // a notice, never a blocker
        }
    }

    /** `/research read <J-id | I-id>`: the whole result, here, from the daemon. */
    private void researchRead(ChatIo io, String ref) {
        if (!org.codezaiku.research.LibraryBridge.answers()) { io.println("    no library answers on " + org.codezaiku.research.LibraryBridge.url()); return; }
        try {
            var client = org.codezaiku.research.LibraryBridge.client();
            if (ref.equals("all")) {
                // clear the backlog of notices without printing eight reports
                int n = 0;
                for (var j : client.jobs(50, null).path("finished")) { String id = j.path("job_id").asText(""); if (!id.isEmpty() && !readJobs().contains(id)) { markRead(id); n++; } }
                io.println("    " + n + " finished run(s) marked read; their notices will not show again. /research read <J-id> still prints any of them.");
                return;
            }
            String invId = ref;
            if (ref.startsWith("J-")) {
                JsonNode j = client.job(ref);
                String st = j.path("state").asText();
                if ("done".equals(st) || "failed".equals(st)) markRead(ref);   // read = the notice is spent
                if (!"done".equals(st)) { io.println("    " + ref + " is " + st + (st.equals("failed") ? ": " + firstLine(j.path("result").asText("")) : " — not yet")); return; }
                if (!j.hasNonNull("investigation")) { io.println("    " + ref + " finished but was refused at intake; its summary:"); io.println(j.path("result").asText("")); return; }
                invId = j.get("investigation").asText();
            }
            JsonNode inv = client.get(invId);
            if (inv == null || inv.isNull()) { io.println("    no investigation " + invId); return; }
            io.println("  " + inv.path("id").asText() + "  [" + inv.path("state").asText() + ", by " + inv.path("writer").asText() + "]  " + inv.path("title").asText());
            io.println("");
            io.println(inv.path("body").asText("").strip());
            var fs = inv.path("findings");
            if (fs.isArray() && fs.size() > 0) { var ids = new java.util.ArrayList<String>(); for (var f : fs) ids.add(f.asText()); io.println("\n  findings extracted: " + String.join(", ", ids)); }
            else io.println("\n  (no findings extracted yet — the library's housekeeping does that, or `researchzosho settle`)");
        } catch (org.researchzosho.client.LibraryException e) {
            io.println("    ! " + e.getMessage() + " [" + e.code + "]");
        } catch (Exception e) {
            io.println("    ! " + e.getMessage());
        }
    }

    // the read marks: one id per line, in CodeZaiku's own home
    private static Path readJobsFile() { return org.codezaiku.Config.home().resolve("research").resolve("read-jobs.txt"); }
    private static java.util.Set<String> readJobs() {
        try { return new java.util.HashSet<>(java.nio.file.Files.readAllLines(readJobsFile())); } catch (Exception e) { return new java.util.HashSet<>(); }
    }
    private static void markRead(String id) {
        try {
            java.nio.file.Files.createDirectories(readJobsFile().getParent());
            if (!readJobs().contains(id)) java.nio.file.Files.writeString(readJobsFile(), id + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return ChatSession.oneLine(i < 0 ? s : s.substring(0, i));
    }

    /**
     * Live tool narration. This is the seam ACP already drives, reused rather than reinvented — and
     * it is what makes a 30-second turn watchable without token streaming.
     */
    /**
     * The status line under a working turn: "… thinking · 23 s" between tool calls, "… running shell · 8 s" during
     * one. A daemon thread rewrites it once a second and the terminal clears it before any real output. Off while
     * the answer streams, and gone when the turn ends.
     */
    static final class Ticker {
        private final ChatIo io;
        private final Narrator narrator;
        private volatile boolean on = false, quiet = false;
        private volatile long startedAt = System.nanoTime();
        private Thread thread;

        Ticker(ChatIo io, Narrator narrator) { this.io = io; this.narrator = narrator; }
        long startedAt() { return startedAt; }
        void quiet() { quiet = true; }

        void start() {
            startedAt = System.nanoTime();
            on = true; quiet = false;
            thread = new Thread(() -> {
                while (on) {
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                    if (!on || quiet) continue;
                    String tool = narrator.running();
                    long since = tool == null ? startedAt : narrator.runningSince();
                    io.status("  … " + (tool == null ? "thinking" : "running " + tool) + " · " + elapsed(since));
                }
            }, "chat-status");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            on = false;
            if (thread != null) thread.interrupt();
            io.clearStatus();
        }

        static String elapsed(long sinceNanos) {
            long s = (System.nanoTime() - sinceNanos) / 1_000_000_000L;
            return s < 60 ? s + " s" : s < 3600 ? (s / 60) + " min " + (s % 60) + " s" : (s / 3600) + " h " + ((s % 3600) / 60) + " min";
        }
    }

    static final class Narrator implements ToolRegistry.Listener {
        private final ChatIo io;
        private final java.util.Set<String> files;
        private final ChatConsent consent;
        private final ChatJournal journal;

        /** What is running now, for the status line: null between tool calls (the model is thinking). */
        private volatile String running = null;
        private volatile long runningSince = 0;
        private final java.util.concurrent.atomic.AtomicInteger steps = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.Set<String> changed = new java.util.LinkedHashSet<>();
        private final long turnStart = System.nanoTime();
        String running() { return running; }
        long runningSince() { return runningSince; }
        int steps() { return steps.get(); }

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
            // Web work narrates its SUBSTANCE (the operator, 2026-09-01: "right now we only know
            // searches and pages are being done") — the query in quotes, the url being read.
            String detail = path != null ? path
                    : args != null && args.hasNonNull("command") ? args.get("command").asText()
                    : args != null && args.hasNonNull("query") ? "\"" + args.get("query").asText() + "\""
                    : args != null && args.hasNonNull("url") ? args.get("url").asText() : "";
            if (detail.length() > 100) detail = detail.substring(0, 97) + "...";
            // The leading newline is for streaming: reasoning arrives without a trailing break, and
            // without this the tool line glues onto the middle of a streamed sentence.
            io.println("\n  · " + tool + (detail.isEmpty() ? "" : "  " + detail));
            int n = steps.incrementAndGet();
            runningSince = System.nanoTime();
            running = tool;
            if (n % 10 == 0) {
                // a line to steer by on a long turn: how far, how long, what has changed on disk
                io.println("\u001b[2m    progress · " + n + " steps · " + Ticker.elapsed(turnStart)
                        + (changed.isEmpty() ? "" : " · changed: " + String.join(", ", changed)) + "\u001b[0m");
            }
            if ("write_file".equals(tool) || "edit_file".equals(tool)) { if (path != null) changed.add(path); }
        }

        @Override public void finished(String callId, String tool, String result, boolean failed) {
            running = null;
            if (failed) { io.println("    failed" + (result == null || result.isBlank() ? "" : ": " + firstLineOf(result))); return; }
            // one line on what came back, so a step reads as progress rather than a command that vanished
            if ("shell".equals(tool) || "read_file".equals(tool)) {
                if (result != null && !result.isBlank()) {
                    long lines = result.lines().count();
                    io.println("    → " + (lines == 1 ? firstLineOf(result).length() > 100 ? firstLineOf(result).substring(0, 97) + "..." : firstLineOf(result) : lines + " lines"));
                }
                return;
            }
            if ("write_file".equals(tool) || "edit_file".equals(tool)) { io.println("    ✓"); return; }
            if ("web_search".equals(tool)) {
                io.println("    " + searchDigest(result));
            } else if ("web_fetch".equals(tool) && result != null && result.startsWith("source: ")) {
                // the source line already carries url — Title (WebFetchTool adds the page title)
                io.println("    read " + firstLineOf(result.substring("source: ".length())));
            } else if ("web_fetch".equals(tool) && result != null
                    && (result.startsWith("ERROR") || result.startsWith("ALREADY"))) {
                io.println("    " + firstLineOf(result));
            }
        }

        /** "N results — url1 · url2 · url3" (CODEZAIKU_CHAT_SEARCH_URLS urls, default 3), or the
         *  honest failure state: no results / backend degraded. */
        static String searchDigest(String result) {
            if (result == null) return "(no result)";
            if (result.startsWith("SEARCH BACKEND DEGRADED")) return "search backend DEGRADED — retrying differently";
            if (result.startsWith("no results")) return "0 results";
            if (result.startsWith("ALREADY SEARCHED")) return "already searched — needs a different query";
            // both backends emit: "N. Title" then an indented "   url" line per result
            int shown = org.codezaiku.Config.getInt("CODEZAIKU_CHAT_SEARCH_URLS", 3);
            var urls = new java.util.ArrayList<String>();
            int count = 0;
            for (String line : result.split("\n")) {
                if (line.matches("\\d+\\. .*")) count++;
                else if (line.startsWith("   http") && urls.size() < shown) urls.add(line.strip());
            }
            if (count == 0) return firstLineOf(result);
            return count + " result" + (count == 1 ? "" : "s")
                    + (urls.isEmpty() ? "" : " — " + String.join(" · ", urls));
        }

        private static String firstLineOf(String s) {
            int i = s.indexOf('\n');
            String line = (i < 0 ? s : s.substring(0, i)).strip();
            return line.length() > 160 ? line.substring(0, 157) + "..." : line;
        }
    }

}
