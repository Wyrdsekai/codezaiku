package org.codezaiku.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import org.codezaiku.tools.ToolRegistry;

/**
 * Ask the person, in the moment, instead of making them choose a mode up front.
 *
 * <h2>Why this replaced a mode switch</h2>
 *
 * The first cut of chat copied aider: you type {@code /ask} or {@code /code} and the tool set changes
 * underneath you. It is safe and it is not how anyone wants to work — you have to know, before you
 * speak, whether the thing you are about to say is a question or an instruction.
 *
 * Claude Code and Codex do the opposite and it is plainly better: <b>you just talk.</b> The agent has
 * its tools, and when it wants to do something consequential the harness stops and asks. Codex layers
 * a sandbox underneath as a hard ceiling; Claude Code layers permission modes. Both keep the
 * conversation free of bookkeeping.
 *
 * <h2>Why this is allowed here — the rule I first over-read</h2>
 *
 * CLAUDE.md says <i>"no action-interceptor gates — never reject a model's action by harness rule"</i>,
 * and I took that to forbid asking. It does not. {@link ToolRegistry.Listener#permit} says so
 * directly: <i>"This is NOT the harness overruling the model… It is how an EXTERNAL authority (the
 * user, or the host acting for them) exercises consent it already holds: the decision is made outside
 * the harness and merely carried through here."</i>
 *
 * The forbidden thing is the harness deciding an action is unwise. A person deciding about their own
 * files is the seam working as designed — and {@code AcpServer} has been doing exactly this for git
 * writes all along.
 *
 * <h2>Standing answers are keyed by WHAT WAS ASKED</h2>
 *
 * Not by tool. "Always allow {@code git commit}" must not silently also permit {@code git push}.
 * Taken from ACP's implementation, which got this right first.
 */
public final class ChatConsent implements ToolRegistry.Listener {

    /**
     * How often to ask. The names mirror what Claude Code and Codex settled on, because people
     * already know them.
     */
    public enum Mode {
        /** Never act. Read and reason only — nothing to ask about, so nothing interrupts. */
        PLAN("read and reason; never changes anything"),
        /** Ask before every write and every shell command. The default. */
        ASK("ask before writing or running anything"),
        /** Edits go through; shell commands still ask. */
        AUTO_EDIT("edit freely, ask before running commands"),
        /** Never ask. For a scratch directory you do not mind losing. */
        YOLO("never ask — do not use on anything you care about");

        private final String description;
        Mode(String d) { this.description = d; }
        public String description() { return description; }

        @Override public String toString() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }

        public static java.util.Optional<Mode> parse(String s) {
            if (s == null) return java.util.Optional.empty();
            String k = s.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            for (Mode m : values()) if (m.name().equals(k)) return java.util.Optional.of(m);
            return java.util.Optional.empty();
        }

        /** Unknown values fall back to ASK — a typo must never widen what the agent may do. */
        public static Mode fromConfig(String s) { return parse(s).orElse(ASK); }
    }

    /** What the person is asked, and how they answer. Kept out of {@link ChatConsent} so it can be tested headlessly. */
    public interface Prompter {
        /**
         * @param what    a short description of the action — also the standing-answer key
         * @param preview the actual change, already rendered. <b>This is what makes the question
         *                answerable</b>: "edit Client.java?" gives you nothing to decide on, and
         *                needing to go and look is the reason someone reaches for a plan mode.
         * @return the answer
         */
        Answer ask(String what, java.util.List<String> preview);
    }

    public enum Answer {
        YES, YES_ALWAYS, NO, NO_ALWAYS,
        /**
         * "Stop asking me for anything" — switches the SESSION to {@link Mode#YOLO} and allows
         * this action. On the prompt itself because that is the only place it can usefully live: a
         * slash command typed at a prompt abandons the turn, so {@code /mode yolo} was unreachable
         * exactly when someone wanted it. Session-scoped, never persisted: blanket trust that
         * outlives the session is a decision for {@code /trust}, which takes a deliberate step.
         */
        YES_ALL
    }

    /** Where a standing answer lives. Most specific wins: SESSION over PROJECT over GLOBAL. */
    public enum Scope { SESSION, PROJECT, GLOBAL }

    private final Prompter prompter;
    private final Map<String, Boolean> standing = new ConcurrentHashMap<>();
    private final Map<String, Boolean> project = new ConcurrentHashMap<>();
    private final Map<String, Boolean> global = new ConcurrentHashMap<>();
    private final java.nio.file.Path projectFile;
    private final java.nio.file.Path globalFile;
    private Mode mode;

    public ChatConsent(Mode mode, Prompter prompter) {
        this(mode, prompter, null, null);
    }

    /**
     * With {@code projectFile}/{@code globalFile}, standing answers can be PROMOTED to survive the
     * session — {@code /trust project} or {@code /trust global} — and are loaded back on start.
     *
     * <p>Both files live in the harness-owned store under the user's home, <b>never inside the
     * repository</b>. That is a security decision, not tidiness: a consent file that travelled with
     * a checkout would mean a cloned repo could arrive pre-trusted to run whatever its author chose
     * to allow. Nothing an untrusted tree ships may widen what the agent is permitted to do.
     */
    public ChatConsent(Mode mode, Prompter prompter,
                       java.nio.file.Path projectFile, java.nio.file.Path globalFile) {
        this.mode = mode;
        this.prompter = prompter;
        this.projectFile = projectFile;
        this.globalFile = globalFile;
        loadInto(projectFile, project);
        loadInto(globalFile, global);
    }

    public Mode mode()            { return mode; }
    public void mode(Mode m)      { this.mode = m; }
    /** Clears SESSION answers only. Persisted scopes are edited via their files or {@code /trust}. */
    public void forget()          { standing.clear(); }
    public int standingCount()    { return standing.size(); }

    /** Every effective standing answer, one line each, with where it came from. */
    public List<String> trusted() {
        var out = new ArrayList<String>();
        global.forEach((k, v) -> out.add((v ? "allow  " : "deny   ") + k + "   (global)"));
        project.forEach((k, v) -> out.add((v ? "allow  " : "deny   ") + k + "   (project)"));
        standing.forEach((k, v) -> out.add((v ? "allow  " : "deny   ") + k + "   (this session)"));
        java.util.Collections.sort(out);
        return out;
    }

    /**
     * Promote this session's standing answers into {@code scope}, so they hold next time.
     *
     * <p>Promotion is a deliberate command rather than a fifth key on the approval prompt. Adding
     * "always, everywhere" next to "always" invites answering it by reflex, and a reflex answer that
     * persists beyond the session is precisely the thing that should take one extra step.
     *
     * @return how many answers were promoted, or -1 if the scope has no file to write to
     */
    public int trust(Scope scope) {
        var target = scope == Scope.PROJECT ? project : global;
        var file = scope == Scope.PROJECT ? projectFile : globalFile;
        if (scope == Scope.SESSION || file == null) return -1;
        target.putAll(standing);
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            var sb = new StringBuilder("# Standing consent answers (" + scope.name().toLowerCase(Locale.ROOT)
                    + ") — written by /trust, one per line. Delete a line to be asked again.\n");
            target.forEach((k, v) -> sb.append(v ? "allow " : "deny ").append(k).append('\n'));
            java.nio.file.Files.writeString(file, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
            return standing.size();
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    private static void loadInto(java.nio.file.Path file, Map<String, Boolean> into) {
        if (file == null || !java.nio.file.Files.isRegularFile(file)) return;
        try {
            for (String l : java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
                String t = l.strip();
                // Anything unrecognised is IGNORED, not guessed at: a malformed line in a consent
                // file must never become an allowance.
                if (t.startsWith("allow ")) into.put(t.substring(6).strip(), true);
                else if (t.startsWith("deny ")) into.put(t.substring(5).strip(), false);
            }
        } catch (java.io.IOException ignored) {
            // Unreadable consent means "ask", which is the safe direction.
        }
    }

    /** run_background IS a shell command for every consent decision — same previews, same
     *  standing answers, same trust. A different name must not be a consent bypass. */
    static String canonical(String tool) {
        return "run_background".equals(tool) ? "shell" : tool;
    }

    @Override
    public String permit(String tool, JsonNode args) {
        tool = canonical(tool);
        if (mode == Mode.YOLO) return null;

        boolean isShell = "shell".equals(tool);
        boolean isWrite = "write_file".equals(tool) || "edit_file".equals(tool);
        // Delegation acts: a sub-loop with standard tools and NO per-action prompts. The one
        // consent here covers everything the sub-agent will do, so it is never waved through —
        // not even in auto-edit, which the person granted for edits, not for autonomous agents.
        boolean isDelegate = "delegate".equals(tool);
        // An MCP tool acts in ANOTHER process; nothing here can know whether it mutates. Fail
        // closed: ask, in every mode short of yolo. "always" keys on the tool name, so a granted
        // tool stays granted and an unknown one still asks.
        boolean isMcp = tool.startsWith("mcp_");
        // Writing to the model's own future context is an act (the /refine lesson): remember asks
        // in every mode short of yolo — auto-edit granted edits to the PROJECT, not to memory.
        boolean isMemory = "remember".equals(tool);
        // Reads never ask. Codex sandboxes reads freely and Claude Code does not prompt for them;
        // a chat that interrupts to read a file is unusable, and reading cannot lose your work.
        if (!isShell && !isWrite && !isDelegate && !isMcp && !isMemory) return null;

        // Inspecting is not acting. But "does this write a project file" is the WRONG question here,
        // and a failing test is what showed it: ShellTool classifies `npm test` as read-only, which
        // is right for ITS purpose (a review must be able to run the suite) and wrong for consent —
        // running an arbitrary command is exactly the thing a person wants a say in, and plenty of
        // commands have large effects without touching a tracked file (`curl … | sh`, `docker run`,
        // `kubectl delete`). So consent uses its own, much narrower allowlist and asks about
        // everything else. It fails CLOSED: an unrecognised command is asked about.
        if (isShell && inspects(args)) return null;

        if (mode == Mode.PLAN) {
            // Nothing to ask: PLAN never acts. Say why, so the model treats it as a fact about the
            // session rather than a failure to retry.
            return "plan mode — no changes are being made. Describe what you would do instead.";
        }
        if (mode == Mode.AUTO_EDIT && isWrite) return null;

        String what = describe(tool, args);
        Boolean remembered = standing.get(what);
        if (remembered == null) remembered = project.get(what);
        if (remembered == null) remembered = global.get(what);
        if (remembered != null) return remembered ? null : denial(what);

        return switch (prompter.ask(what, ChatPreview.of(tool, args))) {
            case YES -> null;
            case YES_ALWAYS -> { standing.put(what, true); yield null; }
            case YES_ALL -> { mode = Mode.YOLO; yield null; }
            case NO -> denial(what);
            case NO_ALWAYS -> { standing.put(what, false); yield denial(what); }
        };
    }

    private static String denial(String what) {
        // Comes back to the model as an observation it can work around — not an exception, and not
        // a scolding. It should try something else, not apologise or stop.
        return "the user declined: " + what + ". Do something else, or explain what you would need.";
    }

    /**
     * The key for a standing answer, and the text the person sees.
     *
     * <p>For shell it is the command's <b>first two words</b> — enough that "always allow
     * {@code git commit}" does not also permit {@code git push}, and coarse enough that the same
     * command with different arguments is not asked twice. For writes it is the tool plus the path,
     * so approving one file does not approve the tree.
     */
    static String describe(String tool, JsonNode args) {
        tool = canonical(tool);
        if ("shell".equals(tool)) {
            String cmd = args == null ? "" : args.path("command").asText("");
            // EVERY part of a chain, not just the first. Keying `mvn -q test && git commit` on its
            // first two words would let one "always" silently cover `mvn -q test && rm -rf /` —
            // the exact hole the per-command keying exists to close, reintroduced by forgetting
            // that a command can be more than one command. Found by reading a rendered prompt.
            var heads = new java.util.ArrayList<String>();
            for (String part : cmd.split(CHAIN)) {
                String[] w = part.strip().split("\\s+");
                if (w.length == 0 || w[0].isBlank()) continue;
                heads.add(w.length >= 2 ? w[0] + " " + w[1] : w[0]);
            }
            if (heads.isEmpty()) return "run ``";
            return "run " + String.join(" && ", heads.stream().map(h -> "`" + h + "`").toList());
        }
        if ("remember".equals(tool)) {
            String fact = args == null ? "" : args.path("fact").asText("");
            if (fact.length() > 70) fact = fact.substring(0, 67) + "...";
            return "remember \"" + fact + "\"";
        }
        if (tool.startsWith("mcp_")) {
            return "call " + tool + " (a tool in another process)";
        }
        if ("delegate".equals(tool)) {
            String task = args == null ? "" : args.path("task").asText("");
            if (task.length() > 60) task = task.substring(0, 57) + "...";
            // The WHOLE task is the standing key: "always" for one delegated task must not cover
            // a different one — there is no safe two-word head for free text.
            return "delegate \"" + task + "\"";
        }
        String path = args == null ? "" : args.path("path").asText("");
        return ("write_file".equals(tool) ? "write " : "edit ") + path;
    }

    /** The full command, for showing before the question. Truncated so a heredoc cannot fill a screen. */
    static String detail(String tool, JsonNode args) {
        String s = "shell".equals(tool)
                ? (args == null ? "" : args.path("command").asText(""))
                : (args == null ? "" : args.path("path").asText(""));
        s = s.replace('\n', '⏎');
        return s.length() > 160 ? s.substring(0, 157) + "..." : s;
    }

    /**
     * Commands that only look at things. Short, boring, and closed: anything not on this list is
     * asked about, including commands that happen to be harmless.
     *
     * <p>Every entry ends the prefix at a word boundary, so {@code git status} is here and
     * {@code git push} is not, and {@code lsof} does not match {@code ls}.
     */
    private static final String[] INSPECTS = {
        "ls", "cat", "head", "tail", "wc", "file", "stat", "pwd", "which", "tree",
        "grep", "rg", "find", "diff", "du", "df", "env", "printenv", "date", "echo",
        "git status", "git log", "git diff", "git show", "git branch", "git remote -v",
        "git ls-files", "git rev-parse", "git blame",
    };

    /** Shell chain separators: {@code &&}, {@code ||}, {@code ;}, {@code |}. */
    private static final String CHAIN = "&&|\\|\\||;|\\|";

    /** A real redirect, not an fd-dup like {@code 2>&1}. */
    private static final java.util.regex.Pattern REDIRECT =
            java.util.regex.Pattern.compile("(^|[^0-9<>&])>");

    /** The journal shares consent's judgement of what mutates — one classifier, two callers. */
    static boolean inspectsForJournal(JsonNode args) { return inspects(args); }

    private static boolean inspects(JsonNode args) {
        String cmd = args == null ? null : args.path("command").asText(null);
        if (cmd == null || cmd.isBlank()) return false;                 // unknown shape → ask
        String c = cmd.strip();
        // A chain is only inspective if every part of it is. `ls && rm -rf /` must be asked about,
        // and judging it by its first word is how that gets missed — little-coder learned this one
        // the same way.
        for (String part : c.split(CHAIN)) {
            String t = part.strip();
            if (t.isEmpty()) continue;
            boolean ok = false;
            for (String p : INSPECTS) {
                if (t.equals(p) || t.startsWith(p + " ")) { ok = true; break; }
            }
            if (!ok) return false;
        }
        // A redirect writes, whatever it starts with — the same rule little-coder applies, because
        // `cat > main.py` is the same write as the write tool and deserves the same question.
        // A redirect writes, whatever it starts with — `cat > main.py` is the same write as
        // the write tool. An fd-dup (2>&1) is not.
        if (c.contains(" tee ") || c.contains("dd of=")) return false;
        return !REDIRECT.matcher(c).find();
    }

    @Override public void started(String callId, String tool, JsonNode args) { }
    @Override public void finished(String callId, String tool, String result, boolean failed) { }
}
