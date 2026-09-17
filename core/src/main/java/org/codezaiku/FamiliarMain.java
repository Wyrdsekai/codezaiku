package org.codezaiku;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.acp.AcpServer;
import org.codezaiku.drive.DriveClient;
import org.codezaiku.gate.Gate;
import org.codezaiku.gate.GateResult;
import org.codezaiku.gate.GateLoader;
import org.codezaiku.library.Library;
import org.codezaiku.loop.FamiliarLoop;
import org.codezaiku.report.Sarif;
import org.codezaiku.run.RunVerb;
import org.codezaiku.tools.ToolRegistry;
import org.codezaiku.tools.VerifyTool;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.codezaiku.library.CrateIndexer;
import org.codezaiku.library.ErrorQuery;
import org.codezaiku.library.KnowledgePackIndexer;
import org.codezaiku.library.KnowledgeProvisioner;
import org.codezaiku.library.LibraryIndex;
import org.codezaiku.library.ProjectConventions;
import org.codezaiku.loop.CodeMode;
import org.codezaiku.loop.FamiliarMemory;
import org.codezaiku.lsp.LspClient;
import org.codezaiku.mcp.McpServer;
import org.codezaiku.ops.ConcludeTool;
import org.codezaiku.ops.Exec;
import org.codezaiku.ops.FetchRunbookTool;
import org.codezaiku.ops.InvestigationResult;
import org.codezaiku.ops.MachineTriage;
import org.codezaiku.ops.OpsAuthority;
import org.codezaiku.ops.OpsDiscovery;
import org.codezaiku.ops.OpsKnowledge;
import org.codezaiku.ops.OpsLearn;
import org.codezaiku.ops.OpsLoop;
import org.codezaiku.ops.OpsPromote;
import org.codezaiku.ops.OpsShellTool;
import org.codezaiku.ops.Precheck;
import org.codezaiku.ops.ProactiveScan;
import org.codezaiku.ops.PythonSessionTool;
import org.codezaiku.ops.ReconJail;
import org.codezaiku.ops.ReconPhase;
import org.codezaiku.ops.RemediationDoneTool;
import org.codezaiku.ops.RemediationLoop;
import org.codezaiku.ops.RemediationResult;
import org.codezaiku.ops.TargetOs;
import org.codezaiku.ops.RemediationSnapshot;
import org.codezaiku.ops.RunbookCatalog;
import org.codezaiku.ops.SecurityAlerts;
import org.codezaiku.ops.SecurityResponse;
import org.codezaiku.ops.SecurityScan;
import org.codezaiku.ops.StackLocalizer;
import org.codezaiku.ops.TelemetryPrecheck;
import org.codezaiku.research.ResearchMemory;
import org.codezaiku.shape.ProjectFacts;
import org.codezaiku.shape.ProjectShape;
import org.codezaiku.redact.Redactor;
import org.codezaiku.review.DiffBundler;
import org.codezaiku.review.ReportFindingTool;
import org.codezaiku.review.ReviewReport;
import org.codezaiku.tools.AnswerDraftTool;
import org.codezaiku.tools.EditFileTool;
import org.codezaiku.tools.PathScope;
import org.codezaiku.tools.ReadDepSourceTool;
import org.codezaiku.tools.ReplaceSymbolTool;
import org.codezaiku.tools.WebSearchTool;
import org.codezaiku.exec.Shell;

/**
 * Entry point.
 * <pre>
 *   smoke [baseUrl]                                         — prove the drive call (tool_choice=required)
 *   gate  &lt;projectRoot&gt; &lt;gateName&gt;                            — run a gate once, print the verdict
 *   loop  &lt;projectRoot&gt; &lt;goal&gt; [baseUrl] [maxTurns] [gateName] — run the execute→observe→act loop
 * </pre>
 * gateName currently: {@code library-api} (or {@code none}).
 */
public final class FamiliarMain {

    // Config, not a literal: the drive is the one setting everyone changes, and it should come from
    // ~/.codezaiku/config without needing an export in every shell.
    private static final String DEFAULT_DRIVE = Config.get("CODEZAIKU_DRIVE", "http://localhost:8200");

    // The `model` field of the chat-completions request. llama.cpp ignores it and serves whatever is
    // loaded, but Ollama, LM Studio, vLLM and hosted APIs use it to SELECT a model — so it must be
    // settable, and its default must not name a model only one machine has.
    private static final String MODEL = Config.get("CODEZAIKU_MODEL", "local-model");

    /** Reported by `codezaiku --version` and by the MCP server handshake. */
    public static final String VERSION = "0.3.7";

    /**
     * Lucene announces on every start that the vector incubator module is not enabled. It is
     * advice about throughput we are not bound by, it arrives through java.util.logging rather than
     * our own logger, and it is among the FIRST things on stderr — so a caller quoting stderr to
     * explain a failure quotes this instead of the reason. Adding the incubator module silences it
     * only by trading it for the JVM's own "Using incubator modules" warning, measured, so the
     * logger is quietened directly. Errors from Lucene still come through.
     */
    private static void quietenThirdPartyStartupNoise() {
        try {
            java.util.logging.Logger.getLogger("org.apache.lucene")
                    .setLevel(java.util.logging.Level.SEVERE);
        } catch (Exception ignored) {
            // a logging tweak must never be the reason a run does not start
        }
    }

    public static void main(String[] args) {
        quietenThirdPartyStartupNoise();
        // `--version` is how a host harness health-checks an installed CLI before dispatching work
        // to it — backend adapters run `<exe> --version` and mark the backend unavailable
        // if it fails. We answered "unknown command", so CodeZaiku would have been skipped before it
        // ran anything. Kept trivially parseable: name and version on one line, nothing else.
        if (args.length >= 1 && (args[0].equals("--version") || args[0].equals("-v")
                || args[0].equals("version"))) {
            System.out.println("codezaiku " + VERSION);
            System.exit(0);
        }
        if (args.length >= 1 && args[0].equals("setup")) {
            // the first ten minutes: the model, web search, the programs that reach CodeZaiku over MCP, the library
            boolean yes = false, programs = true, library = true;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--yes", "-y" -> yes = true;
                    case "--no-programs" -> programs = false;
                    case "--no-library" -> library = false;
                    default -> { System.err.println("usage: codezaiku setup [--yes] [--no-programs] [--no-library]"); System.exit(2); }
                }
            }
            try {
                System.exit(new Setup(new java.io.BufferedReader(new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8)), System.out,
                        Setup.liveProbe(), Setup.liveActs(), yes).run(programs, library));
            } catch (Exception e) { System.err.println("setup: " + e.getMessage()); System.exit(1); }
        }
        if (args.length >= 1 && args[0].equals("model")) {
            System.exit(Models.command(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length >= 1 && args[0].equals("config")) {
            System.exit(configCommand(Arrays.copyOfRange(args, 1, args.length)));
        }
        if (args.length >= 1 && args[0].equals("init")) {
            // Write a starter config so settings live in a file that can be reviewed and shared,
            // rather than in whatever the last shell happened to export.
            System.exit(writeStarterConfig(args.length >= 2 && args[1].equals("--force")));
        }
        if (args.length >= 1 && args[0].equals("update")) {
            String op = args.length >= 2 ? args[1] : "status";
            switch (op) {
                case "status" -> { System.out.print(SelfUpdate.status()); System.exit(0); }
                case "now" -> { var o = SelfUpdate.now(args.length >= 3 ? args[2] : null, System.out); System.out.println(o.note()); System.exit(o.updated() ? 0 : 1); }
                case "auto" -> {
                    if (args.length < 3 || !(args[2].equals("on") || args[2].equals("off"))) { System.err.println("usage: codezaiku update auto on|off"); System.exit(2); }
                    try { Config.set("CODEZAIKU_UPDATE", args[2].equals("on") ? "auto" : "check"); } catch (Exception e) { System.err.println("could not save: " + e.getMessage()); System.exit(1); }
                    System.out.println(args[2].equals("on") ? "auto-update is on: a chat swaps a newer release in at its start, for the next start" : "auto-update is off: doctor and the chat say when a newer release exists; codezaiku update now installs it");
                    System.exit(0);
                }
                default -> { System.err.println("usage: codezaiku update [status | now [version] | auto on|off]"); System.exit(2); }
            }
        }
        if (args.length >= 1 && args[0].equals("doctor")) {
            // "why doesn't this work yet" — checks the model server and every optional component,
            // and names the one command that fixes each gap.
            System.exit(Doctor.run(args.length >= 2 ? args[1] : DEFAULT_DRIVE));
        }
        if (args.length >= 1 && args[0].equals("smoke")) {
            smoke(args.length >= 2 ? args[1] : DEFAULT_DRIVE);
            return;
        }
        if (args.length >= 2 && args[0].equals("shape")) {
            System.out.println(ProjectShape.render(Path.of(args[1])));
            return;
        }
        if (args.length >= 2 && args[0].equals("library-search")) {
            librarySearch(args[1], args.length >= 3 ? Integer.parseInt(args[2]) : 5,
                    args.length >= 4 ? args[3] : null);
            return;
        }
        if (args.length >= 2 && args[0].equals("library-fields")) {
            try (var idx = new LibraryIndex(libraryIndexDir())) {
                System.out.println(idx.describeFields(args[1]));
            }
            return;
        }
        if (args.length >= 3 && args[0].equals("crate-index")) {
            crateIndex(args); // args[1]=framework, args[2..]=crate dir basenames (e.g. sysinfo-0.38.4)
            return;
        }
        if (args.length >= 1 && args[0].equals("kp-index")) {
            kpIndex();
            return;
        }
        if (args.length >= 2 && args[0].equals("error-query")) {
            String q = ErrorQuery.fromObservation(args[1]);
            System.out.println("extracted query: " + q);
            if (q != null) {
                String fw = args.length >= 3 ? args[2] : null;
                try (var idx = new LibraryIndex(libraryIndexDir())) {
                    for (var c : idx.search(q, fw, 3)) {
                        System.out.println("  • [" + c.framework() + " " + c.version() + "] " + c.title());
                    }
                }
            }
            return;
        }
        if (args.length >= 3 && args[0].equals("dep-source")) {
            // dep-source <projectRoot> <dependency> [query] — smoke the on-demand real-source tool
            var tool = new ReadDepSourceTool(Path.of(args[1]));
            var a = new ObjectMapper().createObjectNode();
            a.put("dependency", args[2]);
            if (args.length >= 4) a.put("query", args[3]);
            System.out.println(tool.execute(a));
            return;
        }
        if (args.length >= 5 && args[0].equals("edit")) {
            // edit <projectRoot> <path> <old> <new> — smoke the (forgiving) edit tool
            Path er = Path.of(args[1]);
            var scope = new PathScope(er);
            var elsp = LspClient.forProject(er, ProjectFacts.language(er));
            var tool = new EditFileTool(scope, elsp);
            var a = new ObjectMapper().createObjectNode();
            a.put("path", args[2]);
            a.put("old_string", args[3].replace('~', ' ').replace('^', '\n'));
            a.put("new_string", args[4].replace('~', ' ').replace('^', '\n'));
            try { System.out.println(tool.execute(a)); } catch (Exception e) { System.out.println("EXC: " + e); }
            return;
        }
        if (args.length >= 2 && args[0].equals("mem-test")) {
            // mem-test <projectRoot> — run cargo build, feed it through FamiliarMemory twice, show pinned
            Path r = Path.of(args[1]);
            try {
                var mem = new FamiliarMemory(r);
                Process p = Shell.pb("cargo build 2>&1").directory(r.toFile())
                        .redirectErrorStream(true).start();
                String out; try (var in = p.getInputStream()) { out = new String(in.readAllBytes()); }
                p.waitFor();
                mem.observeBuild(out);
                System.out.println("--- after build 1 ---" + mem.pinned());
                mem.observeBuild(out);
                System.out.println("\n--- after build 2 (same errors → should escalate) ---" + mem.pinned());
                // exercise the API-signature retention path
                var dep = new ReadDepSourceTool(r);
                var la = new ObjectMapper().createObjectNode();
                la.put("dependency", "sysinfo"); la.put("query", "host_name");
                mem.observeLookup("sysinfo", dep.execute(la));
                System.out.println("\n--- after a read_dep_source lookup (should pin KNOWN API SIGNATURES) ---"
                        + mem.pinned());
            } catch (Exception e) { System.out.println("mem-test EXC: " + e); }
            return;
        }
        if (args.length >= 4 && args[0].equals("replace-symbol")) {
            // replace-symbol <root> <relFile> <symbol> <new_text(~=space ^=newline)>
            Path r = Path.of(args[1]);
            try (var lsp = LspClient.forProject(r, ProjectFacts.language(r))) {
                if (lsp == null) { System.out.println("no language server"); return; }
                var tool = new ReplaceSymbolTool(new PathScope(r), lsp);
                var a = new ObjectMapper().createObjectNode();
                a.put("path", args[2]);
                a.put("symbol", args[3]);
                a.put("new_text", (args.length >= 5 ? args[4] : "").replace('~', ' ').replace('^', '\n'));
                System.out.println(tool.execute(a));
            } catch (Exception e) { System.out.println("replace-symbol EXC: " + e); }
            return;
        }
        if (args.length >= 3 && args[0].equals("lsp-check")) {
            // lsp-check <projectRoot> <relFile> — smoke the language-server diagnostics path
            Path r = Path.of(args[1]);
            Path f = r.resolve(args[2]);
            String lang = ProjectFacts.language(r);
            try (var lsp = LspClient.forProject(r, lang)) {
                if (lsp == null) { System.out.println("no language server for " + lang); return; }
                long t0 = System.currentTimeMillis();
                String diag = lsp.check(f, Files.readString(f));
                System.out.println("(" + (System.currentTimeMillis() - t0) + "ms)\n"
                        + (diag.isBlank() ? "(clean — no LSP errors)" : diag));
            } catch (Exception e) { System.out.println("lsp-check EXC: " + e); }
            return;
        }
        if (args.length >= 3 && args[0].equals("gate")) {
            gateOnly(Path.of(args[1]), args[2]);
            return;
        }
        // ACP (Agent Client Protocol) v1 over stdio — the other way a host drives us.
        if (args.length >= 1 && args[0].equals("acp")) {
            try {
                AcpServer.main(Arrays.copyOfRange(args, 1, args.length));
            } catch (Exception e) {
                System.err.println("acp: " + e);
                System.exit(1);
            }
            return;
        }
        // The subprocess-host contract: one task, one JSON document, CWD is the workspace.
        if (args.length >= 1 && args[0].equals("run")) {
            RunVerb.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        // CHAT — the standalone surface. Every other verb is one-shot and every integration
        // surface assumes a host in front of it; this is the one you can sit down at.
        //
        // You do not pick a mode before speaking. The agent has its tools and the harness stops to
        // ask before it writes or runs anything — the shape Claude Code and Codex settled on, and
        // the documented purpose of ToolRegistry.Listener#permit: an EXTERNAL authority exercising
        // consent it already holds, which is a different thing from the harness overruling a model.
        if (args.length >= 1 && args[0].equals("chat")) {
          try {
            Path root = args.length >= 2 && !args[1].startsWith("--") ? Path.of(args[1]) : Path.of(".");
            var mode = org.codezaiku.chat.ChatConsent.Mode.fromConfig(
                    flag(args, "--mode", Config.get("CODEZAIKU_CHAT_MODE")));
            // UNLIMITED by default. The old cap of 8 was defending against a problem that no
            // longer exists: it dated from before ctrl-C could stop a turn and before anything
            // streamed, when a stuck turn was ten silent minutes with no way out. In chat the
            // person IS the cap — they are watching the turn and can end it with one key — and
            // the operator hit the ceiling mid-fix on the first real work-shaped ask ("why can we not
            // do infinite?"). A number here still matters for UNATTENDED runs (pipes, scripts),
            // which is what --max-turns and CODEZAIKU_CHAT_MAX_TURNS remain for; 0 means no cap.
            int turns = Integer.parseInt(flag(args, "--max-turns",
                    Config.get("CODEZAIKU_CHAT_MAX_TURNS", "0")));
            if (turns <= 0) turns = 1_000_000;   // "unlimited": far past any real conversation,
                                                 // small enough that + epilogue cannot overflow
            try {
                System.exit(new org.codezaiku.chat.ChatRepl(
                        root, flag(args, "--drive", DEFAULT_DRIVE), MODEL, mode, turns,
                        flag(args, "--from", null)).run());
            } catch (IOException e) {
                System.err.println("chat: could not open a terminal: " + e.getMessage());
                System.exit(1);
            }
          } catch (IllegalArgumentException e) {
            System.err.println("codezaiku chat: " + e.getMessage());
            System.err.println("usage: codezaiku chat [project] [--mode ask] [--drive URL] [--max-turns 8]");
            System.exit(2);
          }
        }
        // SESSIONS — the chat store as an archive: list, export (= backup), import (= restore).
        // The store was designed to make this a file walk; the verbs just say what happened.
        if (args.length >= 2 && args[0].equals("sessions")) {
            try {
                String sub = args[1];
                Path root = args.length >= 3 && !args[2].startsWith("--") ? Path.of(args[2])
                        : (sub.equals("import") ? Path.of(".") : Path.of("."));
                if (sub.equals("import") && args.length >= 3 && !args[2].startsWith("--")) {
                    // import's positional arg is the ARCHIVE; an optional project follows it.
                    root = args.length >= 4 && !args[3].startsWith("--") ? Path.of(args[3]) : Path.of(".");
                }
                Path store = org.codezaiku.chat.ChatSession.storeDir(root.toAbsolutePath().normalize());
                switch (sub) {
                    case "list" -> {
                        var rows = org.codezaiku.chat.ChatSession.list(root);
                        if (rows.isEmpty()) { System.out.println("no sessions for " + root.toAbsolutePath().normalize()); return; }
                        for (String[] r : rows) System.out.println(r[0] + "  " + r[1]);
                    }
                    case "export" -> {
                        Path out = Path.of(flag(args, "--out",
                                org.codezaiku.chat.SessionArchive.defaultOut(root).toString()));
                        var names = org.codezaiku.chat.SessionArchive.export(store, out,
                                root.toAbsolutePath().normalize().toString());
                        System.out.println("exported " + names.size() + " file(s) -> " + out);
                    }
                    case "import" -> {
                        if (args.length < 3 || args[2].startsWith("--")) {
                            System.err.println("usage: codezaiku sessions import <archive.zip> [project] [--force]");
                            System.exit(2);
                        }
                        boolean force = java.util.Arrays.asList(args).contains("--force");
                        var r = org.codezaiku.chat.SessionArchive.importInto(Path.of(args[2]), store, force);
                        System.out.println("restored " + r.restored().size() + " file(s) into " + store);
                        if (!r.skipped().isEmpty()) {
                            System.out.println("skipped " + r.skipped().size()
                                    + " already-present file(s) — --force overwrites:");
                            r.skipped().forEach(n -> System.out.println("  " + n));
                        }
                    }
                    default -> {
                        System.err.println("usage: codezaiku sessions <list|export|import> ...");
                        System.exit(2);
                    }
                }
                return;
            } catch (Exception e) {
                System.err.println("sessions " + args[1] + " failed: " + e.getMessage());
                System.exit(1);
            }
        }
        // V1 — front end #2: OpenAI-compatible /v1/chat/completions over one project, so Open
        // WebUI (or any OpenAI client) is the GUI. Pinned to the READ rung by the protocol's own
        // shape: no mid-turn callback exists, so no approval prompt can render, so no tool that
        // would need one is in the registry (V1Server's javadoc carries the argument).
        if (args.length >= 1 && args[0].equals("v1")) {
            try {
                Path root = args.length >= 2 && !args[1].startsWith("--") ? Path.of(args[1]) : Path.of(".");
                int port = Integer.parseInt(flag(args, "--port", Config.get("CODEZAIKU_V1_PORT", "7071")));
                String host = flag(args, "--host", "127.0.0.1");   // loopback: no auth exists here
                int turns = Integer.parseInt(flag(args, "--max-turns", "25"));
                new org.codezaiku.chat.V1Server(root, flag(args, "--drive", DEFAULT_DRIVE), MODEL, turns)
                        .start(host, port);
                System.out.println("v1: OpenAI-compatible chat on http://" + host + ":" + port
                        + "/v1  —  project " + root.toAbsolutePath().normalize()
                        + "  rung=read (read-only tools; the protocol cannot ask permission)");
                Thread.currentThread().join();
            } catch (NumberFormatException e) {
                System.err.println("codezaiku v1: not a number: " + e.getMessage());
                System.exit(2);
            } catch (Exception e) {
                System.err.println("v1 server failed: " + e);
                System.exit(1);
            }
        }
        // REVERSE — a repo, backwards: the single conversational prompt someone would have typed
        // to vibe-code this project from scratch. GitReverse's idea (a Next.js app over five cloud
        // providers) rebuilt as one verb over what this harness already owns: ProjectFacts for the
        // shape, the tree, the README, and the local drive for the words. Beyond the party trick it
        // is a FIXTURE GENERATOR: repo -> prompt -> feed the prompt back to the coding loop -> diff
        // against the real repo is a self-grading greenfield eval.
        if (args.length >= 1 && args[0].equals("reverse")) {
            Path root = args.length >= 2 && !args[1].startsWith("--") ? Path.of(args[1]) : Path.of(".");
            System.exit(reverse(root.toAbsolutePath().normalize(),
                    flag(args, "--drive", DEFAULT_DRIVE)));
        }
        // `code` is the name the usage text, the README and the MCP tool all use; `loop` is the
        // original. Both dispatch here — the help promised an alias the dispatch did not accept.
        if (args.length >= 3 && (args[0].equals("loop") || args[0].equals("code"))) {
            Path root = Path.of(args[1]);
            String goal = resolveGoal(args[2]);
            String baseUrl = driveArg(args, 3);
            int maxTurns = args.length >= 5 ? Integer.parseInt(args[4]) : 40;
            String gateName = args.length >= 6 ? args[5] : "none";
            loop(root, goal, baseUrl, maxTurns, gateName);
            return;
        }
        if (args.length >= 3 && args[0].equals("decompose")) {
            Path root = Path.of(args[1]);
            String goal = resolveGoal(args[2]);
            String baseUrl = driveArg(args, 3);
            int maxTurnsPerSub = args.length >= 5 ? Integer.parseInt(args[4]) : 20;
            String gateName = args.length >= 6 ? args[5] : "none";
            decompose(root, goal, baseUrl, maxTurnsPerSub, gateName);
            return;
        }
        if (args.length >= 3 && args[0].equals("ops")) {
            // ops <target> <incident|@file> [baseUrl] [maxIterations] [outJsonPath]
            //   target: local | ssh://[user@]host[:port] | docker://<container-id>
            String target = args[1];
            String incident = resolveGoal(args[2]);
            String baseUrl = driveArg(args, 3);
            int maxIter = args.length >= 5 ? Integer.parseInt(args[4]) : 25;
            String outPath = args.length >= 6 ? args[5] : null;
            ops(target, incident, baseUrl, maxIter, outPath, null, null);
            return;
        }
        if (args.length >= 2 && args[0].equals("fix")) {
            // fix <scope> [ceiling] [incident|@file] [baseUrl]  — "here are the systems, go fix"
            //   scope   : <project> | compose:<project>            (local docker) |
            //             ssh://[user@]host[:port]/<project>       (compose project on a REMOTE host over ssh)
            //   ceiling : observe|localize|propose|guarded|unattended  (default: propose — surface, don't apply)
            // App-health endpoint + verify command are AUTO-DISCOVERED from the project; no per-stack env needed.
            var scope = OpsDiscovery.parseScope(args[1]);
            String ceiling = args.length >= 3 ? args[2] : "propose";
            String incident = args.length >= 4 ? resolveGoal(args[3])
                    : "Investigate the '" + scope.project() + "' stack for any degraded service and restore it.";
            String baseUrl = driveArg(args, 4);
            System.out.println("fix: target=" + scope.target() + "  project=" + scope.project() + "  ceiling=" + ceiling);
            // multi-round so one trigger clears ALL current faults (cascade + multiple independent outages).
            OpsOutcome fo = args.length >= 4
                    ? ops(scope.target(), incident, baseUrl, 25, null, scope.project(), ceiling)
                    : fixRounds(scope.target(), scope.project(), ceiling, baseUrl, 5);
            System.out.println("fix: final outcome → " + fo.line());
            return;
        }
        if (args.length >= 2 && args[0].equals("watch")) {
            // watch <scope> [ceiling] [intervalSec] [baseUrl] — proactive loop: sense; if degraded, fix; sleep.
            // scope: <project> (local docker) | ssh://[user@]host/<project> (remote host over ssh).
            var scope = OpsDiscovery.parseScope(args[1]);
            String ceiling = args.length >= 3 ? args[2] : "guarded";
            int interval = args.length >= 4 ? Integer.parseInt(args[3]) : 60;
            String baseUrl = driveArg(args, 4);
            watch(scope.target(), scope.project(), ceiling, interval, baseUrl);
            return;
        }
        if (args.length >= 1 && args[0].equals("serve")) {
            // serve [port] [baseUrl] — HTTP dispatch: POST /fix {scope,ceiling,incident}, GET /health.
            int port = args.length >= 2 ? Integer.parseInt(args[1]) : 7070;
            String baseUrl = driveArg(args, 2);
            try { serve(port, baseUrl); Thread.currentThread().join(); }
            catch (Exception e) { System.err.println("serve failed: " + e); System.exit(1); }
            return;
        }
        if (args.length >= 1 && args[0].equals("triage")) {
            // triage <target> [drive] [maxIter] [ceiling] — explore the whole box, fix unhealthy compose stacks.
            String target = args.length >= 2 ? args[1] : "local";
            String baseUrl = driveArg(args, 2);
            int maxIter = args.length >= 4 ? Integer.parseInt(args[3]) : 20;
            String ceiling = args.length >= 5 ? args[4] : null;
            triage(target, baseUrl, maxIter, ceiling);
            return;
        }
        if (args.length >= 2 && args[0].equals("review")) {
            // review <projectRoot> [scopeSpec/diffRef] [drive] [maxTurns] — read-only review → findings.
            Path root = Path.of(args[1]);
            String scopeSpec = (args.length >= 3 && !args[2].isBlank()) ? args[2] : null;
            String baseUrl = driveArg(args, 3);
            int maxTurns = args.length >= 5 ? Integer.parseInt(args[4]) : 30;
            String summary = review(root, scopeSpec, baseUrl, maxTurns).summary();
            System.out.println("\n=== REVIEW ===\n" + anchorReview(root, scopeSpec, summary));
            writeSarif(lastReviewFindings);
            System.exit(0);
        }
        if (args.length >= 2 && args[0].equals("install") && args[1].equalsIgnoreCase("researchzosho")) {
            // The door to the sibling: fetch the release, check it, put it on the path, hand over to its setup.
            System.exit(ResearchZoshoInstall.door(java.util.Arrays.copyOfRange(args, 2, args.length), System.out));
        }
        if (args.length >= 1 && args[0].equals("librarian")) {
            // `codezaiku librarian …` runs the installed researchzosho command (ResearchZosho is its own
            // program since 0.3.0); the verb stays so nothing anyone typed stops working.
            System.exit(ResearchZoshoInstall.alias(java.util.Arrays.copyOfRange(args, 1, args.length), System.out));
        }
        if (args.length >= 2 && args[0].equals("research")) {
            // research <question|@file> [broad|depth] [drive] [maxTurns]
            String question = resolveGoal(args[1]);
            String mode = args.length >= 3 ? args[2] : "broad";
            String baseUrl = driveArg(args, 3);
            int maxTurns = args.length >= 5 ? Integer.parseInt(args[4]) : 30;
            if ("fan".equalsIgnoreCase(mode)) {
                System.out.println("\n=== RESEARCH ===\n" + researchFan(question, baseUrl, maxTurns).summary());
            } else {
                FamiliarLoop.Result res = research(question, mode, baseUrl, maxTurns);
                System.out.println("\n=== RESEARCH ===\n" + res.summary());
            }
            System.exit(0);
        }
        if (args.length >= 2 && args[0].equals("investigate")) {
            // investigate <target> [incident|@file] [drive] — diagnosis-only RCA (no acting).
            String target = args[1];
            String incident = args.length >= 3 ? resolveGoal(args[2]) : "";
            String baseUrl = driveArg(args, 3);
            System.out.println(investigate(target, incident, baseUrl));
            System.exit(0);
        }
        if (args.length >= 2 && args[0].equals("secure")) {
            // secure <target> [subject] [drive] — posture + runtime detections, and for a detected
            // intrusion a bounded read-only response proposing ONE containment action. Never acts.
            String target = args[1];
            String subject = args.length >= 3 ? args[2] : "";
            String baseUrl = driveArg(args, 3);
            System.out.println(secure(target, subject, baseUrl));
            System.exit(0);
        }
        if (args.length >= 2 && args[0].equals("watch-machine")) {
            // watch-machine <target> [intervalSec] [drive] [ceiling] — proactive whole-machine triage loop.
            String target = args[1];
            int interval = args.length >= 3 ? Integer.parseInt(args[2]) : 120;
            String baseUrl = driveArg(args, 3);
            String ceiling = args.length >= 5 ? args[4] : "guarded";
            watchMachine(target, interval, baseUrl, ceiling);
            return;
        }
        if (args.length >= 1 && args[0].equals("mcp")) {
            // mcp — MCP server over stdio (JSON-RPC 2.0). Exposes tools: code (coding familiar), fix (SRE
            // operator). Any external agent driving CodeZaiku dispatches through this.
            try { McpServer.serveStdio(); }
            catch (Exception e) { System.err.println("mcp server failed: " + e); System.exit(1); }
            return;
        }
        boolean askedForHelp = args.length > 0
                && (args[0].equals("help") || args[0].equals("-h") || args[0].equals("--help"));
        if (!askedForHelp && args.length > 0)
            System.err.println("codezaiku: unknown command '" + args[0] + "'\n");
        usage();
        System.exit(askedForHelp ? 0 : 2);
    }


    /** The settings most people touch, in the order a starter config presents them. Used by
     *  `config list` so the common case is discoverable without reading the reference doc. */
    private static final String[] COMMON_KEYS = {
        "CODEZAIKU_DRIVE", "CODEZAIKU_MODEL", "CODEZAIKU_API_KEY",
        "CODEZAIKU_OPS_AUTHORITY", "CODEZAIKU_OPS_AUDIT",
        "CODEZAIKU_OPS_ROLLBACK", "CODEZAIKU_SEARXNG", "CODEZAIKU_DISTILLER_URL",
        "CODEZAIKU_OPS_FALCO_ALERTS", "CODEZAIKU_OPS_LEARN", "CODEZAIKU_HOME",
    };

    /** Secret-shaped settings report only that they are set. `config list` output gets pasted into
     *  issue reports and terminals get scrolled back; a credential printed once is a credential
     *  leaked. Whether it is configured is the useful part, and that survives masking. */
    private static String displayValue(String key, String value) {
        return key.matches(".*(KEY|TOKEN|SECRET|PASSWORD).*") ? "(set)" : value;
    }

    /** `codezaiku config …` — read and change settings without opening an editor. */
    private static int configCommand(String[] a) {
        try {
            String sub = a.length >= 1 ? a[0] : "list";
            switch (sub) {
                case "path" -> {
                    System.out.println(Config.userConfigPath());
                    return 0;
                }
                case "get" -> {
                    if (a.length < 2) { System.err.println("usage: codezaiku config get <key>"); return 2; }
                    String v = Config.get(normalizeCliKey(a[1]));
                    if (v == null) { System.err.println("(not set)"); return 1; }
                    System.out.println(v);
                    return 0;
                }
                case "set" -> {
                    if (a.length < 3) { System.err.println("usage: codezaiku config set <key> <value>"); return 2; }
                    String key = normalizeCliKey(a[1]);
                    Config.set(key, a[2]);
                    System.out.println(key + " = " + a[2] + "   (" + Config.userConfigPath() + ")");
                    // Say so rather than letting the change look like it did nothing.
                    if (System.getenv(key) != null && !System.getenv(key).isBlank())
                        System.out.println("NOTE: " + key + " is also set in your environment, which "
                                + "overrides the file — unset it for this to take effect.");
                    return 0;
                }
                case "unset" -> {
                    if (a.length < 2) { System.err.println("usage: codezaiku config unset <key>"); return 2; }
                    boolean gone = Config.unset(normalizeCliKey(a[1]));
                    System.out.println(gone ? "removed" : "(was not set in the config file)");
                    return 0;
                }
                case "edit" -> {
                    Path cfg = Config.userConfigPath();
                    if (!Files.exists(cfg)) writeStarterConfig(false);
                    String editor = Config.get("EDITOR", System.getenv("EDITOR"));
                    if (editor == null || editor.isBlank()) editor = "vi";
                    return new ProcessBuilder(editor, cfg.toString()).inheritIO().start().waitFor();
                }
                case "list" -> {
                    Path src = Config.source();
                    System.out.println(src == null
                            ? "no config file (run `codezaiku init`); showing environment only\n"
                            : "config: " + src + "\n");
                    var eff = Config.effective(Arrays.asList(COMMON_KEYS));
                    for (String k : COMMON_KEYS) {
                        String[] v = eff.get(k);
                        System.out.printf("  %-30s %-34s %s%n", shorthandKey(k),
                                v == null ? "(unset)" : displayValue(k, v[0]),
                                v == null ? "" : "[" + v[1] + "]");
                    }
                    System.out.println("\nall settings: docs/CONFIGURATION.md");
                    return 0;
                }
                default -> {
                    System.err.println("""
                        usage:
                          codezaiku config list                 what is set, and where it came from
                          codezaiku config get <key>
                          codezaiku config set <key> <value>
                          codezaiku config unset <key>
                          codezaiku config edit                 open it in $EDITOR
                          codezaiku config path

                        Keys accept any spelling: drive, ops.authority, CODEZAIKU_OPS_ROLLBACK.
                        """);
                    return 2;
                }
            }
        } catch (Exception e) {
            System.err.println("config: " + e.getMessage());
            return 1;
        }
    }

    private static String normalizeCliKey(String k) {
        String s = k.trim().toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        return s.startsWith("CODEZAIKU_") ? s : "CODEZAIKU_" + s;
    }

    private static String shorthandKey(String envKey) {
        String k = envKey.startsWith("CODEZAIKU_") ? envKey.substring("CODEZAIKU_".length()) : envKey;
        return k.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    /** Write a commented starter config to the user config path. Refuses to clobber without --force. */
    private static int writeStarterConfig(boolean force) {
        Path cfg = Config.userConfigPath();
        try {
            if (Files.exists(cfg) && !force) {
                System.out.println("config already exists: " + cfg);
                System.out.println("  (re-run with `codezaiku init --force` to overwrite)");
                return 0;
            }
            Files.createDirectories(cfg.getParent());
            Files.writeString(cfg, """
                    # CodeZaiku configuration.
                    #
                    # An environment variable of the same name always overrides the value here, so
                    # scripts and CI keep working. Keys may be written as `drive`, `ops.authority`
                    # or the full CODEZAIKU_OPS_AUTHORITY form — all three resolve.
                    #
                    # Full reference: docs/CONFIGURATION.md   Check your setup: codezaiku doctor

                    # The model server. The only setting most people need to change.
                    drive = http://localhost:8200

                    # How far the operator may go on its own:
                    #   observe < localize < propose < guarded < unattended
                    # `propose` tells you the command instead of running it. Raise deliberately.
                    ops.authority = propose

                    # Record what the operator did. Recommended if anything acts on your systems.
                    ops.audit = %s/audit.jsonl

                    # Snapshot before a destructive fix, restore if verification fails.
                    ops.rollback = on

                    # Search backend for `codezaiku research`. Needs JSON enabled in SearXNG.
                    # searxng = http://localhost:8888

                    # Larger model for one-shot hand-offs on hard sub-problems. Optional.
                    # distiller.url = http://localhost:8201

                    # Runtime intrusion detections, if you run Falco.
                    # ops.falco.alerts = /var/log/falco/alerts.json
                    """.formatted(cfg.getParent()));
            System.out.println("wrote " + cfg);
            System.out.println();
            System.out.println("next:  codezaiku doctor");
            return 0;
        } catch (Exception e) {
            System.err.println("could not write " + cfg + ": " + e.getMessage());
            return 1;
        }
    }

    /**
     * The command surface, grouped by what you are trying to do. Every verb the dispatcher accepts is
     * listed — an undocumented command is one nobody can use, and the previous usage text described
     * three of twenty-six.
     */
    /** The drive is a trailing positional on every verb, so reaching a later one (a ceiling, an
     *  iteration cap) means typing something in the drive's slot. Treat blank as absent: skipping a
     *  positional by passing "" is what a person means, and an empty base URL otherwise reaches the
     *  HTTP client as a malformed target and fails far from the cause. */
    private static String driveArg(String[] args, int idx) {
        return (args.length > idx && !args[idx].isBlank()) ? args[idx] : DEFAULT_DRIVE;
    }

    /**
     * The reverse prompt: what a person would have typed to get this repo. Context is deliberately
     * shallow — a depth-1 tree, the README's head, the detected shape — because the point is the
     * prompt someone types BEFORE the code exists, and deep source detail leaks the answer into
     * the question.
     */
    static int reverse(Path root, String driveUrl) {
        if (!Files.isDirectory(root)) {
            System.err.println("reverse: not a directory: " + root);
            return 2;
        }
        StringBuilder ctx = new StringBuilder();
        ctx.append("language: ").append(org.codezaiku.shape.ProjectFacts.language(root)).append('\n');
        ctx.append("top-level entries:\n");
        try (var st = Files.list(root)) {
            st.map(x -> x.getFileName().toString())
              .filter(n -> !n.startsWith(".") && !n.equals("build") && !n.equals("node_modules"))
              .sorted().limit(40)
              .forEach(n -> ctx.append("  ").append(n).append('\n'));
        } catch (IOException e) {
            System.err.println("reverse: cannot list " + root + ": " + e.getMessage());
            return 2;
        }
        for (String rd : new String[]{"README.md", "README", "readme.md"}) {
            Path f = root.resolve(rd);
            if (Files.isRegularFile(f)) {
                try {
                    String txt = Files.readString(f);
                    ctx.append("README (head):\n")
                       .append(txt, 0, Math.min(txt.length(), 4000)).append('\n');
                } catch (IOException ignored) { }
                break;
            }
        }
        var drive = new DriveClient(driveUrl, MODEL);
        var msgs = drive.json().createArrayNode();
        msgs.addObject().put("role", "system").put("content",
                "You turn an existing software project into the single prompt its author would have "
                + "typed to an AI coding agent to build it from scratch. Write ONE short, "
                + "conversational request in the first person — the features and constraints that "
                + "matter, nothing else. Never mention that a repository, README or existing code "
                + "exists; write as if the project does not yet.");
        msgs.addObject().put("role", "user").put("content", ctx.toString());
        String prompt = drive.classify(msgs, 700);
        if (prompt == null || prompt.isBlank()) {
            System.err.println("reverse: the drive returned nothing — is a model server at " + driveUrl + "?");
            return 1;
        }
        System.out.println(prompt.strip());
        return 0;
    }

    /**
     * {@code --name value} from argv, or {@code def} when the flag is absent.
     *
     * <p>A flag present with NO value is an error, not a silent fallback. `--drive` with nothing
     * after it used to quietly mean "use localhost:8200", so a typed-but-incomplete command looked
     * like it had worked and then failed later against a server the user had not asked for. Saying
     * so costs one line and saves the confusion.
     */
    static String flag(String[] args, String name, String def) {
        for (int i = 0; i < args.length; i++) {
            if (!args[i].equals(name)) continue;
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException(name + " needs a value");
            }
            return args[i + 1];
        }
        return def;
    }

    private static void usage() {
        System.err.println("""
            codezaiku — a coding and development harness that runs on local models or hosted APIs

            USAGE
              codezaiku <command> [args]        every command takes the model server as an
                                                optional trailing argument (default: %s)

            SETUP — the first ten minutes
              setup [--yes] [--no-programs] [--no-library]
                                                the model (found, served on demand, or a hosted
                                                key), web search, the programs that reach CodeZaiku
                                                over MCP, and ResearchZosho if wanted. Enter takes
                                                every default; --yes takes them all.

            CHAT — sit down and talk to it (no host, no editor, no browser)
              chat [project] [--mode ask] [--drive URL] [--from ID] [--max-turns N]
                                                a conversation in one project. Just talk — it asks
                                                before writing or running anything, and remembers
                                                what you allow. Modes: plan (never acts) ·
                                                ask (default) · auto-edit · yolo (never asks).
              v1 [project] [--port 7071] [--host 127.0.0.1] [--drive URL]
                                                the same conversation as an OpenAI-compatible
                                                /v1/chat/completions endpoint (Open WebUI is the
                                                GUI). Read-only tools: this wire cannot ask
                                                permission, so nothing that needs it is offered.
              sessions list|export|import [project] [--out F] [--force]
                                                the chat store as an archive: export is backup,
                                                import is restore (never overwrites unless --force).

            CODING — edit and maintain a project
              code|loop <project> <goal|@file> [drive] [maxTurns]
                                                work on a project until the goal is met
              decompose <project> <goal|@file> [drive] [maxTurns]
                                                same, with an explicit TODO the loop tracks
              review <project> [gitRef] [drive] read-only code review -> findings, no edits
              reverse [project] [--drive URL]   the repo, backwards: the one prompt that would
                                                have vibe-coded it from scratch

            OPERATIONS — keep a stack healthy
              fix <scope> [ceiling] [incident]  diagnose and repair. scope: <compose-project>,
                                                ssh://[user@]host/<project>, docker://<container>
              watch <scope> [ceiling] [secs]    continuous loop: fix on degraded, else report
                                                posture, intrusions and resource forecasts
              triage <target> [drive] [n] [ceiling]
                                                explore a whole machine and fix what is broken
              investigate <target> [incident]   diagnosis only — never acts
              serve [port]                      HTTP dispatch: POST /fix, GET /health, /audit
              ceiling = observe | localize | propose | guarded | unattended  (default: propose)

            SECURITY — report only, never auto-remediates
              secure <target> [subject]         posture + runtime intrusions, and for a live
                                                detection one proposed containment command

            RESEARCH — answer a question from the open web
              research <question|@file> [broad|depth] [drive] [maxTurns]

            LIBRARY — ResearchZosho, the research library, is a separate program
              install researchzosho           fetch it, check it, run its setup; the chat then files
                                              research runs with it and reads what it holds
              librarian <args…>               the installed researchzosho command, same arguments

            INTEGRATION — three ways another program drives CodeZaiku
              mcp                               MCP server on stdio (JSON-RPC 2.0). Exposes every
                                                surface above as tools to any MCP host.
              acp                               Agent Client Protocol v1 agent on stdio. Streams
                                                tool activity and supports mid-run cancellation.
              run --text <task|@file|-> [opts]  one coding task, one JSON result on stdout, for a
                                                host that drives a subprocess. The working
                                                directory IS the workspace.
                --output-format json            emit the result document (narration goes to stderr)
                --task-id <id>                  echoed back, so both sides log one id
                --mode artifact                 the deliverable is ONE file: write it and stop.
                                                Without it the harness drives toward passing tests,
                                                so a one-file task grows a test suite to satisfy them
                --model <m> / --provider <p>    per-invocation model; provider is recorded only
                -q                              silence narration entirely
                --text @FILE  (or -)            read the task from a file (or stdin). Windows needs
                                                this for a task over ~8K: cmd.exe refuses a longer
                                                command line, so the .bat launcher never starts
                exit codes: 0 finished · 2 ran out of turns (files[] is still real) · 1 failed
              --version                         print the version and exit — the health check

            SETUP
              init [--force]                    write a starter config to ~/.codezaiku/config
              config list|get|set|unset|edit    read and change settings
              model detect|list|add|use         find and switch model servers
              model serve install|status|stop|uninstall|check
                                                a model server on this machine, on demand (the row for your card)
              smoke [drive]                     check the model server answers
              doctor                            what is missing or wrong, and how to fix it
              update now|status                 take the latest release (settings kept); or say what you have
              shape <project>                   what the harness sees: language, tests, layout
              lsp-check <project>               language-server availability
              library-search <query> | kp-index | crate-index | error-query | dep-source
                                                the knowledge library

            Configuration is by environment variable; see docs/CONFIGURATION.md.
            """.formatted(DEFAULT_DRIVE));
    }

    /** A goal of the form {@code @/path/to/file} is read from that file (avoids shell-quoting a long,
     *  multi-line spec through gradle --args); otherwise the literal string is the goal. */
    private static String resolveGoal(String g) {
        if (g != null && g.startsWith("@")) {
            try {
                return Files.readString(Path.of(g.substring(1)));
            } catch (Exception e) {
                System.err.println("could not read goal file " + g + ": " + e + " — using literal");
            }
        }
        return g;
    }

    /**
     * Real decomposition — the harness-consensus shape (NO gate, NO ratchet, NO per-sub-task pipeline).
     * Plan the goal into ordered VERTICAL slices once, then run ONE growing-conversation loop with that
     * plan pinned as a re-injected TODO. The model implements slice-by-slice, building+testing its own
     * work via the shell, so a running program exists from slice 1 and only grows. We judge the result
     * by READING the code, not by a pass/fail. {@code gateName} is ignored (kept for CLI compatibility).
     * The Library is the product's data spine (SPEC_CODEZAIKU_LIBRARY.md §19) — ALWAYS on, no switch.
     * It is not an A/B knob; it is the framework-knowledge layer the harness is built around. Degrades
     * gracefully when the index is absent (LibraryIndex.available()==false → no-op reader; reads are
     * lock-free, so always-on is safe under concurrent runs).
     */
    private static void decompose(Path root, String goal, String baseUrl, int maxTurns, String gateName) {
        var drive = new DriveClient(baseUrl, MODEL);
        LibraryIndex index = new LibraryIndex(libraryIndexDir());
        Library library = new Library();
        System.out.println("decompose: library ON"
                + " — single loop, model-decides done, planning inline (no separate planner, no gate)");

        // KNOWLEDGE PROVISIONER (stage 1+2, MVP): profile this project + log the library's COVERAGE of its
        // concerns, so gaps are VISIBLE per run (stage-3 acquisition is the next build). Measurement only.
        KnowledgeProvisioner.report(root, goal, library);

        // Shared language-server client: per-edit diagnostics + edit_file symbol-span fallback + replace_symbol.
        var lsp = LspClient.forProject(root, ProjectFacts.language(root));
        var tools = ToolRegistry.standard(root, lsp);
        var result = new FamiliarLoop(drive, tools, root, goal, maxTurns, library, index)
                .lsp(lsp)
                .run();
        System.out.println("\n=== LOOP " + (result.done() ? "task_done" : "ended")
                + " after " + result.turns() + " turns ===\n" + result.summary());
        System.out.println("(read the code at " + root + " to judge quality)");
        try { index.close(); } catch (Exception ignored) { }
    }

    /**
     * OPS MODE (PLAN_CODEZAIKU_OPS.md) — the lean, verification-first diagnostician for a solo dev / small
     * team on ONE box. Connects to the box (local | ssh | docker), runs deterministic prechecks, then a
     * bounded ReAct loop over read-only tools with a verification-first conclusion gate, and emits the
     * structured {@link org.codezaiku.ops.InvestigationResult}. When {@code outPath} is given the result is
     * written there as JSON (the diagnosis eval reads it to score against ground truth). NOT the coding loop.
     */
    /** A machine-readable summary of one ops run — for the watch loop and the dispatch server. */
    public record OpsOutcome(boolean localized, String root, String rung, boolean acted,
                             Boolean verified, boolean rolledBack, boolean harmed) {
        public String line() {
            return "localized=" + localized + (root == null ? "" : " root=" + root) + " rung=" + rung
                    + " acted=" + acted + " verified=" + verified + " rolledBack=" + rolledBack + " harmed=" + harmed;
        }
    }

    /** WHOLE-MACHINE TRIAGE — "explore this box and fix what's broken", boundary = the machine. Enumerates
     *  everything (compose stacks, failed systemd units, host disk/mem), runs the SRE operator on each
     *  unhealthy compose stack (blast-radius + authority ladder bound each), and surfaces systemd/host issues
     *  for attention. Returns a human-readable report; used by the CLI {@code triage} verb + MCP {@code
     *  explore_and_fix} tool. */
    public static String triage(String target, String baseUrl, int maxIter, String ceiling) {
        var exec = Exec.forTarget(target);
        var issues = MachineTriage.explore(exec);
        int scanned = MachineTriage.composeProjectCount(exec);
        StringBuilder rep = new StringBuilder();
        rep.append("machine triage on ").append(exec.describe()).append(": scanned ").append(scanned)
                .append(" compose stack(s); ").append(issues.size()).append(" issue(s) found.\n");
        // P3: one-shot triage also reports the proactive NOW-thresholds (a single scan has no trend
        // baseline — projections need the long-lived watch loop). Forecast-only, never acted on here.
        for (var p : new ProactiveScan(exec).scan())
            rep.append("[PROACTIVE] ").append(p.line()).append('\n');
        // SECURITY POSTURE: deterministic misconfiguration/exposure checks. REPORT ONLY at every rung —
        // security remediation (containment, firewalling, credential rotation) is more destructive than
        // reliability remediation and stays a human decision until we have a measured false-positive rate.
        for (var f : new SecurityScan(exec).scan())
            rep.append("[SECURITY] ").append(f.line()).append('\n');
        // RUNTIME INTRUSION: Falco detections (machine-computed, rule-named) from the recent window.
        // Absence of a Falco stream is reported as "nothing is watching" — never as "no intrusion".
        var alerts = new SecurityAlerts(exec);
        if (alerts.available()) {
            var dets = alerts.recent(60);
            for (var d : dets) rep.append("[INTRUSION] ").append(d.line()).append('\n');
            if (dets.isEmpty()) rep.append("[INTRUSION] none in the last 60m (Falco stream present)\n");
        } else {
            rep.append("[INTRUSION] no runtime detection stream — nothing is watching syscalls; "
                    + "absence of alerts here is NOT evidence of absence of intrusion\n");
        }
        if (issues.isEmpty()) {
            rep.append("machine looks healthy — nothing to fix.");
            System.out.println(rep);
            return rep.toString();
        }
        boolean mayAct = "guarded".equals(ceiling) || "unattended".equals(ceiling);
        for (var iss : issues) {
            if ("compose".equals(iss.kind())) {
                String sym = iss.symptom().toLowerCase();
                boolean handled = false;
                // Lifecycle fix: a dead/exited container's obvious, bounded, reversible fix is to restart JUST it
                // (blast-radius = that stack). Only at an acting rung; else surface. If the restart doesn't hold,
                // fall through to the operator (a re-exit means a real in-service fault to localize).
                if (sym.contains("exited") || sym.contains("dead")) {
                    if (!mayAct) {
                        rep.append("\n[PROPOSE] compose '").append(iss.name()).append("' — ").append(iss.symptom())
                                .append(" (dead container; restart withheld at ceiling=").append(ceiling).append(")\n");
                        handled = true;
                    } else {
                        var ids = exec.run("docker ps -aq --filter label=com.docker.compose.project=" + iss.name()
                                + " --filter status=exited --filter status=dead", 15);
                        if (ids.ok() && !ids.out().isBlank()) {
                            rep.append("\n[FIX] compose '").append(iss.name()).append("' — restarting exited container(s)\n");
                            // start + a short settle (docker start is async, health-checks lag); then recount.
                            // NB: grep -c exits 1 when the count is 0, so judge by the OUTPUT, not the exit code.
                            exec.run("docker start " + ids.out().strip().replace("\n", " ") + " && sleep 6", 90);
                            var still = exec.run("docker ps -a --filter label=com.docker.compose.project=" + iss.name()
                                    + " --format '{{.Status}}' | grep -ciE 'exited|dead' || true", 15);
                            handled = "0".equals(still.out().strip());
                            rep.append("   -> ").append(handled ? "restarted; stack recovered\n"
                                    : "restart did not hold — escalating to the operator\n");
                        }
                    }
                }
                if (!handled) {
                    rep.append("\n[FIX] compose '").append(iss.name()).append("' — ").append(iss.symptom())
                            .append(" -> operator\n");
                    OpsOutcome out = ops(target, iss.symptom(), baseUrl, maxIter, null, iss.name(), ceiling);
                    rep.append("   -> ").append(out.line()).append('\n');
                }
            } else {
                rep.append("\n[FLAG] ").append(iss.kind()).append(" '").append(iss.name()).append("' — ")
                        .append(iss.symptom()).append(" (surfaced for attention; not auto-remediated in v1)\n");
            }
        }
        System.out.println(rep);
        return rep.toString();
    }

    public static OpsOutcome ops(String target, String incident, String baseUrl, int maxIter, String outPath,
                            String projectOverride, String ceilingOverride) {
        var drive = new DriveClient(baseUrl, MODEL);
        var exec = Exec.forTarget(target);
        System.out.println("ops: box=" + exec.describe() + "  drive=" + baseUrl + "  maxIter=" + maxIter);

        // AUTHORITY LADDER (R0–R4) — decides how much the loop may DO, per the earned rung + a global ceiling +
        // a kill-switch, with an audit trail. Ceiling from the `fix` arg (if any) else CODEZAIKU_OPS_AUTHORITY.
        var authority = OpsAuthority.fromEnv(ceilingOverride);
        System.out.println("ops: authority ceiling=" + authority.ceiling() + (authority.halted() ? "  [KILL-SWITCH: HALTED]" : ""));

        // SCOPE — which compose stack. From the `fix <scope>` arg (if any) else CODEZAIKU_OPS_STACK_LOCALIZE.
        // The app-health endpoint and verify command are AUTO-DISCOVERED from the project (OpsDiscovery), so a
        // trigger only needs scope + ceiling — no per-stack env. Explicit env still wins when set.
        String stackProject = (projectOverride != null && !projectOverride.isBlank())
                ? projectOverride : Config.get("CODEZAIKU_OPS_STACK_LOCALIZE");
        String appHealth = Config.get("CODEZAIKU_OPS_APP_HEALTH");
        if ((appHealth == null || appHealth.isBlank()) && stackProject != null && !stackProject.isBlank()) {
            appHealth = OpsDiscovery.appHealthUrl(exec, stackProject);
            if (appHealth != null) System.out.println("ops: auto-discovered app-health endpoint → " + appHealth);
        }
        String verifyCmd = Config.get("CODEZAIKU_OPS_VERIFY_CMD");
        boolean verifyFromEnv = verifyCmd != null && !verifyCmd.isBlank();
        if (!verifyFromEnv) {
            verifyCmd = OpsDiscovery.deriveVerify(appHealth, stackProject);   // whole-stack default
            if (verifyCmd != null) System.out.println("ops: derived verify command (override with CODEZAIKU_OPS_VERIFY_CMD)");
        }

        // Deterministic prechecks first (narrow before the model spends a turn). Toggle OFF to measure the
        // model's RAW diagnosis ability (the honest A/B): CODEZAIKU_OPS_PRECHECK=off.
        boolean precheckOn = !"off".equalsIgnoreCase(Config.get("CODEZAIKU_OPS_PRECHECK"));
        String precheckBlock = precheckOn ? new Precheck(exec).block() : "";
        System.out.println("ops: prechecks " + (precheckOn ? "ON" : "OFF"));
        if (!precheckBlock.isBlank()) System.out.println(precheckBlock);

        // WHOLE-STACK LOCALIZER (ported from bench/ops-eval/refstack) — for a multi-service compose stack,
        // sense the whole stack (every service's health + the app's per-dependency readiness report + each
        // service's recent logs) and let the model NAME the root-cause service, so diagnosis+remediation start
        // pointed at the culprit instead of wandering the app container. Gated:
        //   CODEZAIKU_OPS_STACK_LOCALIZE=<compose-project>  (+ CODEZAIKU_OPS_APP_HEALTH=<url> for the app's
        //   write-probe /health — the signal that NAMES the failing engine, incl. degraded-but-healthy ones).
        // The localized root also SCOPES remediation's blast radius (bystanders become untouchable).
        StackLocalizer.Result stackLoc = null;
        Set<String> stackBystanders = Set.of();
        if (stackProject != null && !stackProject.isBlank()) {
            stackLoc = new StackLocalizer(exec, drive, stackProject, appHealth).run();
            if (stackLoc.localized()) {
                String block = "STACK LOCALIZATION (whole-stack sense):\n" + stackLoc.evidence()
                        + "\nLOCALIZED ROOT-CAUSE SERVICE: " + stackLoc.root() + " (container "
                        + stackLoc.container() + "). Confirm this on that service, then conclude the root cause.\n";
                precheckBlock = precheckBlock.isBlank() ? block : precheckBlock + "\n" + block;
                stackBystanders = stackLoc.bystanders();
                // RE-SCOPE the auto-derived verify to THIS root: a whole-stack verify stays red while any OTHER
                // fault remains, so a successful per-service fix would be judged failed and rolled back (the
                // multi-fault bug). Only when the verify was auto-derived — an explicit env verify is respected.
                if (!verifyFromEnv) {
                    String rv = OpsDiscovery.deriveVerifyForRoot(appHealth, stackLoc.root(), stackLoc.container());
                    if (rv != null) { verifyCmd = rv; System.out.println("ops: verify scoped to root '" + stackLoc.root() + "'"); }
                }
            } else {
                System.out.println("ops: stack-localize did not name a root — continuing unlocalized");
            }
        }

        // RECON — a jailed step that learns the stack (the §5.4 project-shape snapshot, generalized to domain
        // knowledge, which is the measured 2.2× lever). It ADDS INFORMATION, unlike the loop gates that were
        // measured worthless and deleted. Off by default; CODEZAIKU_OPS_RECON=on to enable. The jail defaults
        // to eval-grade containment (deny the grader's answer files) so a recon card can never leak an answer;
        // CODEZAIKU_OPS_RECON_JAIL=box relaxes that to a real box (still read-only, still denies secrets).
        // The read MANIFEST is printed so a leak would be visible rather than silent — this is the whole point.
        if ("on".equalsIgnoreCase(Config.get("CODEZAIKU_OPS_RECON"))) {
            var jail = "box".equalsIgnoreCase(Config.get("CODEZAIKU_OPS_RECON_JAIL"))
                    ? ReconJail.forBox()
                    : ReconJail.forEvalBox();
            var card = new ReconPhase(exec, jail).run();
            System.out.println("ops: recon ON (jail=" + (jail.allowAll() ? "read-all−denied" : "allowlist")
                    + ")  probes read: " + card.readManifest().size());
            for (String r : card.readManifest()) System.out.println("    recon-read: " + r);
            if (!card.isEmpty()) {
                System.out.println(card.map());
                precheckBlock = precheckBlock.isBlank() ? card.map() : precheckBlock + "\n" + card.map();
            }
        }

        // TELEMETRY localization — the same "narrow before the model spends a turn" contract as Precheck,
        // for boxes whose evidence is telemetry rather than infra. Infra prechecks (systemctl/docker ps) say
        // nothing about a CSV tree, which is why they were switched OFF for the whole OpenRCA benchmark — so
        // the one component our evidence says matters was absent from the arm we measured.
        // Justified by measurement, not taste: oracle-localized evidence with NO agent scored mean 0.180 vs
        // our agent's 0.082 on the same model (2.2×). Enable with CODEZAIKU_OPS_TELEMETRY=<root>.
        String telemetryRoot = Config.get("CODEZAIKU_OPS_TELEMETRY");
        if (telemetryRoot != null && !telemetryRoot.isBlank()) {
            var tp = new TelemetryPrecheck(exec, telemetryRoot, 25);
            var win = TelemetryPrecheck.parseWindow(incident);
            String tBlock = (win == null) ? "" : tp.block(win);
            if (win == null) {
                System.out.println("ops: telemetry scan SKIPPED — no incident window parsed from the task");
            } else if (tBlock.isBlank()) {
                System.out.println("ops: telemetry scan found nothing for " + win.day());
            } else {
                System.out.println("ops: telemetry scan ON — window " + win.day()
                        + " [" + win.startEpoch() + ".." + win.endEpoch() + "]");
                System.out.println(tBlock);
                precheckBlock = precheckBlock.isBlank() ? tBlock : precheckBlock + "\n" + tBlock;
            }
        }

        // KNOWLEDGE layer — fault-class → fix-procedure cards, PUSHED by the harness (the direction the
        // OpenRCA oracle's 2.2× actually pointed at: a domain map, not topology, not statistics). Delivery
        // is the measured mechanism (AIOpsLab know3-9, see OpsKnowledge): signature cards arm a ONE-time
        // deferred log scan inside the loop and inject per the card's declared push policy; match-only
        // cards (no signature) go up-front by stack text. Off unless CODEZAIKU_OPS_KNOWLEDGE is set.
        // Resolved, not just read from the environment: an installed copy must find its shipped
        // cards without the user knowing they exist (see Install).
        String knowDir = Install.opsKnowledgeDir();
        List<OpsKnowledge.Card> knowCards = List.of();
        if (knowDir != null && !knowDir.isBlank()) {
            String kBlock = OpsKnowledge.block(knowDir, precheckBlock + "\n" + incident);
            if (!kBlock.isBlank()) {
                System.out.println("ops: knowledge match-only card PUSHED up-front (" + kBlock.length() + " chars)");
                precheckBlock = precheckBlock.isBlank() ? kBlock : precheckBlock + "\n" + kBlock;
            }
            // Assembly of precheckBlock is complete here. It is prompt context, NOT a tool result, so it
            // never passes the redaction in FamiliarLoop — and it is built from exactly the material that
            // carries credentials: sensed service evidence, readiness reports, matched card bodies. Scrub
            // once at the choke point rather than at each of the four places that append to it.
            precheckBlock = Redactor.scrub(precheckBlock);

            var merged = new ArrayList<>(OpsKnowledge.cards(knowDir));
            // LEARN-BACK: also load previously-learned candidate cards (recon successes memoized into the
            // library). Kept in their own dir for provenance; the loader reads both.
            String learnDir = Config.get("CODEZAIKU_OPS_LEARN");
            if (learnDir != null && !learnDir.isBlank()) {
                var learned = OpsKnowledge.cards(learnDir);
                merged.addAll(learned);
                if (!learned.isEmpty()) System.out.println("ops: knowledge +" + learned.size()
                        + " LEARNED candidate card(s) from " + learnDir);
            }
            knowCards = merged.stream().filter(c -> !c.signature().isEmpty()).toList();
            long nv = knowCards.stream().filter(OpsKnowledge.Card::validated).count();
            System.out.println("ops: knowledge " + knowCards.size() + " signature card(s) armed for deferred scan ("
                    + nv + " validated, " + (knowCards.size() - nv) + " candidate)");
        }

        var conclude = new ConcludeTool();
        var tools = new ToolRegistry()
                .add(new OpsShellTool(exec))
                .add(conclude);

        // PERSISTENT python session (gated). The shell tool spawns a fresh process per turn, so analysing a
        // large dataset re-loads it EVERY turn — on OpenRCA (1.1GB trace/day) that burned the whole budget
        // and 42/51 queries produced no answer. Their reference agent runs python in a persistent IPython
        // kernel, so this is parity with the baseline we are measured against. Off by default: it changes
        // the tool surface, and the single-box results were measured without it.
        if ("on".equalsIgnoreCase(Config.get("CODEZAIKU_OPS_PYTHON_SESSION"))) {
            tools.add(new PythonSessionTool(exec, 180));
            System.out.println("ops: persistent python session ON");
        }

        // RUNBOOKS (gated, PLAN_CODEZAIKU_OPS §4). Off by default (measurable lift). Two modes, from the
        // measured finding that the 9B needs PUSH not PULL:
        //   =on   → offer the catalog + fetch_runbook (PULL). Measured INERT: the 9B never fetches; kept
        //           only as the baseline for the record.
        //   =auto → PUSH the matched PROCEDURAL runbook into the first turn, and add NOTHING otherwise (no
        //           listing/tool — that is pure overload for a non-fetching model). So a non-matching
        //           incident (e.g. the single-hop scenarios) is a strict no-op → cannot regress.
        String runbookMode = Config.get("CODEZAIKU_OPS_RUNBOOK");
        String runbookListing = "";
        String runbookInject = "";
        if ("on".equalsIgnoreCase(runbookMode)) {
            var catalog = RunbookCatalog.load();
            if (catalog.available()) {
                tools.add(new FetchRunbookTool(catalog));
                runbookListing = catalog.listing();
                System.out.println("ops: runbooks ON/pull (" + catalog.entries().size() + " available)");
            }
        } else if ("auto".equalsIgnoreCase(runbookMode)) {
            var catalog = RunbookCatalog.load();
            if (catalog.available()) {
                runbookInject = catalog.autoInject(incident);
                System.out.println("ops: runbooks AUTO/push ("
                        + (runbookInject.isBlank() ? "no matching procedure — no-op" : "pushed a matched procedure") + ")");
            }
        }

        // The signature scan's probe: one bounded sweep of recent LOGS (journal warnings + every running
        // container's tail) PLUS a host STATE bundle — failed units, container statuses, disk fill — the
        // single-box analogs of the cluster-state bundle that three log-quiet fault classes required
        // (scaled-to-zero leaves no logs; queue-lag and config-poisoning leave only state). The inner
        // `timeout 25` ends the sweep early with partial output captured (an outer kill discards all).
        final var execRef = exec;
        // Scrubbed at the SOURCE: this supplier is handed to the loop and called on demand, so its output
        // reaches the model and the card matcher WITHOUT passing through tools.execute. Container logs are
        // the single most likely place a connection string with an inline password shows up.
        Supplier<String> logProbe = () -> Redactor.scrub(execRef.run(
                TargetOs.timeoutPrefix(execRef, 25) + "bash -c 'journalctl --no-pager -n 400 -p warning 2>/dev/null; "
                // The NESTED bound needs the same treatment as the outer one. Fixing only the wrapper
                // left `timeout 2 docker logs` inside, which still failed per container on macOS — so
                // the probe grew from 31 to 283 characters and STILL carried no logs, just four more
                // lines of "command not found". An incomplete fix that moves the number is worse than
                // none, because it looks like progress.
                + "for c in $(docker ps -q 2>/dev/null); do "
                + TargetOs.timeoutPrefix(execRef, 2) + "docker logs --tail 25 $c 2>&1; done; "
                + "systemctl --failed --no-pager --no-legend 2>/dev/null; "
                + "docker ps -a --format \"{{.Names}} {{.Status}}\" 2>/dev/null | head -30; "
                + "df -h 2>/dev/null | awk \"0+\\$5 >= 85\"'", 30).out());

        // Stack text for the two-layer trigger: environment identity + what actually runs here. A card's
        // match: keywords must hit this before its signature can fire — the measured fix for host-tier vs
        // platform-tier cards that share the same generic fault strings.
        // PROBED, not assumed. This was the constant "linux systemd host docker ", which is true of the
        // refstack and false of a Mac or any systemd-less host — and a card that passes the keyword gate
        // on a false claim hands the model a systemctl procedure that cannot run there.
        String knowStack = TargetOs.stackTokens(exec) + " "
                + exec.run(TargetOs.timeoutPrefix(exec, 8)
                + "bash -c 'docker ps --format \"{{.Names}} {{.Image}}\" 2>/dev/null; "
                + "systemctl list-units --type=service --state=running --no-pager --no-legend 2>/dev/null "
                + "| head -40'", 10).out()
                + " " + precheckBlock + " " + incident;

        InvestigationResult result;
        boolean concluded;
        int iterations;
        String remediationCard = "";   // matched fix-procedure card, pushed into remediation on the fast path
        boolean cardValidated = false; // the matched card's status == validated (earns auto-remediation)
        String matchedCardPath = null; // the matched card's file (for reuse-counting / auto-promotion)

        if (stackLoc != null && stackLoc.localized()) {
            // LOCALIZED FAST PATH — the measured refstack pipeline (localize → card → remediate). The
            // whole-stack localizer already named the root; the separate diagnosis conclude-loop only adds a
            // stop-decision this model class leaks runs on (it kept digging for the config location and never
            // called conclude, even after the card fired). Skipping it is SAFE because the objective
            // closed-loop verify + harm check make a wrong localization self-correcting: a bad root simply
            // fails to verify, it never reads as a false success. Match the fix card here (same ranking as the
            // in-loop scan) and hand root + card straight to remediation.
            // Match the fix card against the log/state probe PLUS the localizer's sensed evidence. Some
            // faults never reach the engine's own logs — postgres raises "cannot execute INSERT in a
            // read-only transaction" to the CLIENT, so it lives only in the app's /health readiness report,
            // which the localizer captured. Without this the card never matched and the model improvised.
            String probe = logProbe.get() + "\n" + stackLoc.evidence() + "\n" + stackLoc.rootSymptom();
            // Gate the card match to the LOCALIZED ROOT service, NOT the whole stack. On a single-box stack
            // every service is a running container, so whole-stack match-kw gating lets ANY card pass — then a
            // shared signature keyword ("read-only" appears in both the postgres read-only fault AND the
            // opensearch/qdrant write-block cards) let the WRONG card win (postgres got a vector card, never
            // its own ALTER fix). Scoping to the root — like the Python driver's match_card(root, …) — ties
            // the card to the service the localizer named.
            // The root's identity is its name PLUS its container and image, because the name alone is the
            // wrong half. Cards name the PRODUCT ("match: redis, valkey"); real stacks name services for
            // their ROLE — cache, db, queue, broker. Measured on a macOS fixture whose redis container is
            // called "cache": the probe carried 25 lines of "-NOAUTH Authentication required." and the
            // validated redis-auth-server card, whose signature contains that exact string, was rejected
            // before the signature was ever read. Every service on the certified Linux refstack happens to
            // be named for its product, which is the only reason this never showed there. The image is the
            // root's OWN image, so this stays root-scoped and the cross-service confusion above stays fixed.
            String rootImage = "", rootLaunch = "";
            if (stackLoc.container() != null) {
                // One inspect for both facts — image for identity, launch config for the probe.
                Exec.Result ins = exec.run("docker inspect " + stackLoc.container()
                        + " --format '{{.Config.Image}}|{{json .Config.Cmd}}|{{json .Config.Entrypoint}}'"
                        + " 2>/dev/null", 10);
                String[] parts = ins.out() == null ? new String[0] : ins.out().trim().split("\\|", 3);
                if (parts.length > 0) rootImage = parts[0];
                if (parts.length > 2) rootLaunch = launchFlags(parts[1]) + " " + launchFlags(parts[2]);
            }
            String rootIdentity = stackLoc.root() + " "
                    + (stackLoc.container() == null ? "" : stackLoc.container()) + " " + rootImage;
            // THE LAUNCH CONFIG GOES IN THE MATCHING PROBE, NOT THE PROMPT. Two fault classes can be
            // indistinguishable in the evidence and need opposite fixes: a redis whose requirepass was set
            // at RUNTIME and one that carries `--requirepass` on its command line both report
            // "Authentication required", but `CONFIG SET` fixes the first and is a band-aid on the second
            // (measured — the latent check catches it and rolls it back). Nothing in the logs or the
            // readiness report distinguishes them; the command line does. Adding it here lets a card carry
            // `--requirepass` as a signature and win only for the args case.
            //
            // Deliberately NOT added to the prompt block: this text is scored, never read to the model.
            // Prompt volume is a measured harm at this tier (the 900-char card ceiling exists because a
            // 1258-char card dropped the submit rate 43%->0%), and a card's own body already carries
            // whatever the model needs to see.
            String probeForCards = probe + "\n" + rootLaunch;
            var matched = OpsKnowledge.matchSignature(knowCards, rootIdentity, probeForCards,
                    TargetOs.stackTokens(exec));
            cardValidated = matched != null && matched.validated();
            matchedCardPath = matched == null ? null : matched.path();
            // GROUND the matched card against THIS stack (fill <user>/<db>/<container>/... from the container
            // env) and EXTRACT its exact fix command(s). The model reads a prose card but won't assemble the
            // command (both tiers restart/improvise instead of running the ALTER); handing it the concrete
            // command as the suggested remediation is what makes it run the fix verbatim (gap #1).
            String rootEnv = (matched == null || stackLoc.container() == null) ? "" : exec.run("docker inspect "
                    + stackLoc.container() + " --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null "
                    + "| grep -iE 'user|_db|database|passw|port|host|vhost' | grep -ivE 'path|readme' | head -12", 10).out();
            remediationCard = (matched == null) ? ""
                    : groundCard(matched.body(), stackLoc.container(), stackLoc.root(), rootEnv);
            List<String> fixCmds = extractFixCommands(remediationCard, stackLoc.container());
            String sym = stackLoc.rootSymptom();
            if (matched != null) {
                // FAST PATH (card matched) — hand root + grounded card + exact command straight to remediation.
                // Lead with the SPECIFIC symptom (the app's own error), not just the service name — otherwise
                // the remediator fixes a guess (postgres read-only → it hunted disk for 16 iters).
                String rc = "The root-cause service is '" + stackLoc.root() + "'."
                        + (sym.isBlank() ? "" : " The application reports this exact failure from it: \"" + sym
                            + "\" — fix THIS specific fault (not disk/other guesses).")
                        + " A fix-procedure card for this fault class is provided below.";
                List<String> evid = List.of("stack-localize → ROOT=" + stackLoc.root()
                        + (sym.isBlank() ? "" : "  |  app error: " + sym));
                result = new InvestigationResult("localized_root", rc, evid,
                        List.of(), List.of(), fixCmds);
                concluded = true;
                iterations = 0;
                System.out.println("\n=== OPS LOCALIZED (fast path) — root=" + stackLoc.root()
                        + " (fix card matched), diagnosis loop skipped ===");
            } else {
                // RECON-FOR-UNKNOWN — localized but NO card matched. Rather than stop at LOCALIZE, RECON: run the
                // read-only diagnosis grounded with the named root + its error, so the model investigates THIS
                // service and DERIVES a fix (no pre-authored card). The derived fix is unvalidated → it will be
                // PROPOSED (production) or trial-applied (measurement). This is how a NOVEL fault class is handled.
                String reconNote = "\n\nRECON — no pre-authored fix card matched for this fault. The whole-stack "
                        + "localizer named '" + stackLoc.root() + "' as the root-cause service"
                        + (sym.isBlank() ? "" : ", reporting: \"" + sym + "\"")
                        + ". Investigate THIS service and its error, determine the correct fix, and include the "
                        + "exact fix command(s) in remediation_steps.";
                System.out.println("\n=== OPS RECON (localized '" + stackLoc.root()
                        + "', no card) — investigating to derive a fix ===");
                var outcome = new OpsLoop(drive, exec, tools, conclude, incident,
                        precheckBlock + reconNote, maxIter).runbooks(runbookListing).runbookInject(runbookInject)
                        .knowledge(knowCards, knowStack, logProbe).run();
                result = outcome.result();
                concluded = outcome.concluded();
                iterations = outcome.iterations();
                if (result != null) {
                    System.out.println("recon root_cause: " + result.rootCause());
                    System.out.println("recon derived remediation: " + result.remediationSteps());
                } else {
                    System.out.println("(recon reached no conclusion)");
                }
            }
        } else {
            var outcome = new OpsLoop(drive, exec, tools, conclude, incident,
                    precheckBlock, maxIter).runbooks(runbookListing).runbookInject(runbookInject)
                    .knowledge(knowCards, knowStack, logProbe).run();
            System.out.println("\n=== OPS " + (outcome.concluded() ? "CONCLUDED" : "INCOMPLETE")
                    + " after " + outcome.iterations() + " iterations ===");
            result = outcome.result();
            concluded = outcome.concluded();
            iterations = outcome.iterations();
            if (result != null) {
                System.out.println("root_cause_category: " + result.rootCauseCategory());
                System.out.println("root_cause: " + result.rootCause());
                System.out.println("evidence: " + result.evidence());
                System.out.println("remediation: " + result.remediationSteps());
            } else {
                System.out.println("(no conclusion reached)");
            }
        }

        // AUTHORITY-GATED ACTION (R0–R4) — the earned rung decides whether we auto-remediate, only PROPOSE the
        // fix for approval, or just report. A validated card + confident localization can reach GUARDED/
        // UNATTENDED; a candidate card or a shaky localization stops at PROPOSE; the kill-switch forces OBSERVE.
        RemediationResult remediation = null;
        Boolean finalVerified = null;   // the settle-aware verdict (see the re-check below)
        boolean acted = false, rolledBack = false, harmed = false;   // outcome (returned for watch/serve)
        // A proposable fix exists if a card matched OR recon derived remediation steps. Both are unvalidated
        // (unless the card is validated) → PROPOSE in production, trial-apply for measurement.
        boolean haveReconFix = result != null && result.remediationSteps() != null
                && !result.remediationSteps().isEmpty();
        boolean haveFix = !remediationCard.isBlank() || haveReconFix;
        boolean confident = stackLoc != null && stackLoc.localized() && !stackLoc.rootSymptom().isBlank();
        var rung = authority.effective(haveFix, cardValidated, confident);
        String auditRoot = (stackLoc != null && stackLoc.root() != null) ? stackLoc.root()
                : (result != null ? result.rootCauseCategory() : "?");
        String cardTier = !remediationCard.isBlank() ? (cardValidated ? "validated" : "candidate")
                : (haveReconFix ? "recon-derived" : "none");
        if (haveFix && !cardValidated && authority.trial()) cardTier += "(trial)";
        authority.audit("localize", "root=" + auditRoot + " card=" + cardTier + " confident=" + confident + " rung=" + rung);
        System.out.println("ops: authority rung = " + rung + " for root=" + auditRoot
                + " (card=" + cardTier + ", confident=" + confident + ")");

        if (concluded && result != null && !OpsAuthority.autoRemediates(rung)) {
            // OBSERVE / LOCALIZE / PROPOSE — no mutation. PROPOSE surfaces the exact fix for a human to approve.
            List<String> proposed = result.remediationSteps();
            if (rung == OpsAuthority.Rung.PROPOSE && proposed != null && !proposed.isEmpty()) {
                System.out.println("\n=== PROPOSED FIX (rung=PROPOSE — surfaced for approval, NOT applied) ===");
                for (String c : proposed) System.out.println("    " + c);
                authority.audit("propose", "root=" + auditRoot + " fix=" + String.join(" ; ", proposed));
            } else {
                System.out.println("ops: rung=" + rung + " — report only, no action taken"
                        + (haveFix ? "" : " (no fix derived — human investigation needed)"));
            }
        } else if (OpsAuthority.autoRemediates(rung) && concluded && result != null) {
            acted = true;
            authority.audit("remediate-begin", "root=" + auditRoot + " rung=" + rung);
            System.out.println("\n=== REMEDIATION (rung=" + rung + ")"
                    + (verifyCmd != null ? " — closed-loop verify: " + verifyCmd : "") + " ===");
            // HARM BASELINE: which dependencies are healthy RIGHT BEFORE the fix. A remediation that breaks a
            // previously-healthy bystander is a regression, not a fix — this is the load-bearing safety metric
            // the refstack R4 gate measured. Captured from the app's per-dependency readiness report.
            Set<String> healthyBefore = healthyDeps(exec, appHealth);

            var rdone = new RemediationDoneTool();
            // Blast-radius scope: when the stack was localized, the remediation shell may only touch the root
            // service — a docker verb reaching any bystander is rejected.
            var rshell = new OpsShellTool(exec, 6000, 60, false);  // mutation allowed
            if (stackLoc != null && stackLoc.localized() && !stackBystanders.isEmpty()) {
                rshell.scopeTo(stackLoc.container(), stackBystanders);
                System.out.println("ops: remediation blast-radius scoped to " + stackLoc.container()
                        + " (" + stackBystanders.size() / 2 + " bystanders protected)");
            }
            var rtools = new ToolRegistry().add(rshell).add(rdone);
            // Remediation budget: 2/3 of the diagnosis budget. maxIter/2 was too stingy on a multi-service
            // stack (a correct fix needs restart + wait + verify across several services — one rep used 15
            // of its 20 and another ran out mid-fix without ever calling remediation_done).
            // On the fast path the diagnosis loop never ran, so build the remediator's grounding here. When a
            // fix card matched, lead with it (a card buried under precheck noise is not followed — the 9B went
            // spelunking postgresql.conf instead of running the ALTER). Card FIRST + the root container named +
            // the exact app error, and DROP the generic precheck findings (recent-changes/disk are red herrings
            // that pulled the model toward a config/disk cause). Unmatched → fall back to the box grounding.
            String remCtx;
            if (!remediationCard.isBlank()) {
                String sym2 = (stackLoc != null) ? stackLoc.rootSymptom() : "";
                String rc2 = (stackLoc != null ? stackLoc.container() : "<c>");
                // Hand the model the service's OWN connection config (its container env: user/db/port/etc.) so
                // it fills the card's <user>/<db> placeholders with REAL values instead of guessing 'postgres'
                // and burning turns grepping compose. This is the general port of the Python driver's per-engine
                // APIHINT — the target's declared config, which the model could read itself anyway.
                String rootEnv = exec.run("docker inspect " + rc2
                        + " --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null "
                        + "| grep -iE 'user|_db|database|passw|port|host|vhost' | grep -ivE 'path|readme' | head -12", 10).out();
                // GROUND the general card: fill its <container>/<user>/<db>/<pass>/<host>/<port> placeholders
                // with the target's OWN declared values (from the container env), so the 9B runs a concrete
                // command instead of failing the card-SQL + real-creds + docker-exec assembly. This is the
                // library's grounding job — a general card made executable against THIS stack.
                String groundedCard = groundCard(remediationCard, rc2, stackLoc != null ? stackLoc.root() : "", rootEnv);
                remCtx = "## APPLY THIS FIX PROCEDURE — it matches the confirmed fault; follow it exactly:\n"
                        + groundedCard
                        + "\n\nRoot-cause container: `" + rc2 + "` — act with `docker exec " + rc2 + " ...`."
                        + (rootEnv.isBlank() ? "" : "\nService connection config (from its container env — use "
                            + "these REAL values for user/db/port, not defaults):\n" + rootEnv.strip())
                        + (sym2.isBlank() ? "" : "\nApp error to clear: " + sym2);
            } else {
                remCtx = precheckBlock;
            }
            // R3 GUARDRAIL — snapshot the target BEFORE the (destructive-capable) fix, so a fix that bricks the
            // service (measured: a model appended SQL into postgresql.conf and crash-looped postgres) can be
            // ROLLED BACK. Only when localized (we need the container); gated by CODEZAIKU_OPS_ROLLBACK=on.
            boolean rollbackOn = "on".equalsIgnoreCase(Config.get("CODEZAIKU_OPS_ROLLBACK"));
            var snapshotter = new RemediationSnapshot(exec);
            RemediationSnapshot.Snap snap =
                    (rollbackOn && stackLoc != null && stackLoc.localized())
                            ? snapshotter.capture(stackLoc.container()) : null;

            remediation = new RemediationLoop(drive, exec, rtools, rdone, incident,
                    result, verifyCmd, Math.max(12, maxIter * 2 / 3))
                    .context(remCtx)          // the remediator needs the same box grounding the diagnosis had
                    .run();
            System.out.println("applied: " + remediation.appliedSteps());
            // The model's `applied` list is its own account and can be empty for a run that changed
            // plenty — print what the harness WATCHED it do whenever the two differ, so the operator
            // reading this transcript sees the destructive command either way.
            if (!remediation.observedMutations().isEmpty()
                    && !remediation.observedMutations().equals(remediation.appliedSteps())) {
                System.out.println("observed mutations (harness-recorded): " + remediation.observedMutations());
            }
            System.out.println("model_claims_resolved: " + remediation.modelClaimsResolved()
                    + "  harness_verified: " + remediation.harnessVerified());

            // R3 ROLLBACK — the objective check is the authority. TWO triggers:
            //  (a) verify FAILED: the fix did nothing or broke the target — do not leave a half-applied mutation.
            //  (b) verify PASSED but the fix EDITED A CONFIG FILE: a bad config line only bites on the NEXT
            //      restart (a LATENT corruption that verify can't see — it contaminated later runs). Restart the
            //      target to surface it, then re-verify. Runtime fixes (CONFIG SET / ALTER / index-setting) don't
            //      touch config files, so they skip this and are never needlessly restarted.
            // The OUTCOME verdict. The loop's own verify can fail while the app is still settling and then
            // pass on the ~30s re-check below — that re-check IS the verdict, and it must propagate: the
            // final 30B K=5 battery recorded 3 runs verified=false whose fixes had all LANDED ("verify
            // PASSED on re-check" in each log) because only the loop's first answer reached the audit,
            // the learn/promote gates, and OpsOutcome.
            finalVerified = remediation.harnessVerified();
            // The settle re-check needs only a verify command. The restart-validation and the rollback need the
            // snapshot too. Without this split a run with rollback off (or an unlocalized target) got no re-check,
            // and a service still coming back up was recorded verified=false in the audit and the outcome.
            boolean canVerify = verifyCmd != null && !verifyCmd.isBlank();
            boolean haveVerify = snap != null && canVerify;
            boolean failed = canVerify && Boolean.FALSE.equals(remediation.harnessVerified());
            boolean latentRisk = haveVerify && Boolean.TRUE.equals(remediation.harnessVerified())
                    && (touchedConfigFile(remediation.allMutations())
                        || runtimeWriteOverriddenByCommandLine(exec, stackLoc == null ? null
                                : stackLoc.container(), remediation.allMutations()));
            if (failed || latentRisk) {
                if (latentRisk) {
                    System.out.println("ops: verify passed but the fix may not survive a restart "
                            + "(config-file edit, or a runtime setting the command line also pins) — "
                            + "restart-validating the target to surface it");
                    exec.run("docker restart " + stackLoc.container(), 60);
                }
                boolean reVerify = false;
                for (int t = 0; t < 6 && !reVerify; t++) {   // ~30s: let a restart / transient DNS flake settle
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
                    reVerify = exec.run(verifyCmd, 30).ok();
                }
                if (reVerify) {
                    finalVerified = true;
                    System.out.println("ops: verify PASSED on re-check — no rollback needed"
                            + (latentRisk ? " (config edit survived a restart)" : " (transient)"));
                    // Re-state the verdict machine-readably — consumers grep the LAST harness_verified line.
                    System.out.println("model_claims_resolved: " + remediation.modelClaimsResolved()
                            + "  harness_verified: true (settled on re-check)");
                } else {
                    // THE VERDICT MUST FOLLOW THE EVIDENCE. finalVerified still holds the loop's first
                    // answer, and on the latent path that answer is TRUE — the fix passed verify and only
                    // failed the restart re-check. Leaving it there records a fix that demonstrably did not
                    // survive as `verified=true rolledBack=true`, which is a contradiction on its face and
                    // was reaching the outcome record, the audit line and every watch/serve consumer.
                    // (The learn/promote gates were spared only by their separate `!rolledBack` guard.)
                    // Measured on authstack: the model band-aided a command-line password with CONFIG SET,
                    // verify passed, the restart-validate re-check failed, and the run still reported
                    // verified=true.
                    finalVerified = false;
                    if (snap == null) {
                        System.out.println("ops: verify still FAILS after the settle re-check — no snapshot was taken "
                                + "(rollback is off or the target was not localized), so nothing is rolled back");
                    }
                    rolledBack = snap != null && snapshotter.rollback(snap);
                    if (rolledBack) {
                        boolean post = false;
                        for (int t = 0; t < 8 && !post; t++) {
                            try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
                            post = exec.run(verifyCmd, 30).ok();
                        }
                        System.out.println("ops: R3 post-rollback verify=" + post
                                + " (target restored to its pre-fix state — fix was UNDONE, service not bricked)");
                    } else if (snap != null) {
                        // A rollback that could not restore the service is the MOST urgent outcome there is —
                        // the fix failed AND the undo failed, so the target is left in whatever state the fix
                        // put it in. It used to be the only outcome that raised nothing, because the alert
                        // below is gated on rolledBack being true. Silence here reads as "nothing happened".
                        authority.alert("rollback-failed", "the fix for " + auditRoot + " failed verify AND "
                                + "R3 could not restore the service — it is NOT in its pre-fix state and "
                                + "needs a human");
                    }
                }
            }
            snapshotter.cleanup(snap);

            // HARM CHECK: any dependency that was healthy before the fix but is down after = a broken bystander.
            //
            // When it CANNOT run, say so. With no readiness endpoint the whole block was skipped and the
            // outcome still reported `harmed=false` — "we did not look" recorded as "nothing was broken",
            // which is the shape this project keeps having to unlearn (a check that cannot inform must
            // announce it). The outcome flag stays false because that is what the record carries, but the
            // log and the audit now distinguish the two.
            if (appHealth == null || appHealth.isBlank()) {
                System.out.println("HARM: NOT ASSESSED — no readiness endpoint for this stack, so a broken "
                        + "bystander would be invisible. Set CODEZAIKU_OPS_APP_HEALTH to enable the check.");
                authority.audit("harm", "not-assessed (no readiness endpoint)");
            }
            if (appHealth != null && !appHealth.isBlank()) {
                Set<String> healthyAfter = healthyDeps(exec, appHealth);
                Set<String> broke = new TreeSet<>(healthyBefore);
                broke.removeAll(healthyAfter);
                if (stackLoc != null && stackLoc.root() != null) broke.remove(stackLoc.root());
                harmed = !broke.isEmpty();
                String verdict = !broke.isEmpty() ? "YES — broke " + broke
                        : healthyBefore.isEmpty()
                            ? "none — but NOTHING WAS HEALTHY BEFORE the fix either, so this run could not "
                              + "have demonstrated harm (check the WARN lines above for a readiness probe "
                              + "that gave no input)"
                            : "none (no previously-healthy service broken)";
                System.out.println("HARM: " + verdict);
                if (harmed) authority.alert("harm", "fixing " + auditRoot + " broke bystander(s) " + broke);
                else authority.audit("harm", "none");
            }
            if (rolledBack) authority.alert("rollback", "the fix for " + auditRoot + " failed verify and was rolled back");
            authority.audit("remediate-end", "root=" + auditRoot + " verified=" + finalVerified
                    + " rolledBack=" + rolledBack
                    + " mutations=" + String.join(" ; ", remediation.observedMutations()));

            // LEARN-BACK: a RECON-derived fix (no card matched) that VERIFIED becomes a candidate card, so the
            // same fault hits the fast card-path next time. Only on a genuine recon success (not a card fix,
            // not a rolled-back one). Gated by CODEZAIKU_OPS_LEARN=<dir>.
            String learnDir = Config.get("CODEZAIKU_OPS_LEARN");
            if (learnDir != null && !learnDir.isBlank() && remediationCard.isBlank()
                    && Boolean.TRUE.equals(finalVerified) && !rolledBack && stackLoc != null) {
                OpsLearn.record(learnDir, stackLoc.root(), stackLoc.container(),
                        stackLoc.rootSymptom(), result.rootCause(), remediation.appliedSteps());
            }
            // AUTO-PROMOTE: a CANDIDATE card whose fix just VERIFIED earns a reuse; at the threshold it flips
            // candidate→validated (so it may auto-remediate, not just propose). Same gate as learning.
            if (learnDir != null && !learnDir.isBlank() && matchedCardPath != null && !cardValidated
                    && Boolean.TRUE.equals(finalVerified) && !rolledBack) {
                int n = 3;
                try { n = Integer.parseInt(Config.get("CODEZAIKU_OPS_PROMOTE_N", "3")); }
                catch (Exception ignored) { }
                OpsPromote.recordReuse(matchedCardPath, n);
            }
        }

        if (outPath != null) {
            try {
                var j = new ObjectMapper();
                ObjectNode o = (result != null) ? result.toJson(j) : j.createObjectNode();
                o.put("concluded", concluded);
                o.put("iterations", iterations);
                if (remediation != null) o.set("remediation", remediation.toJson(j));
                Files.writeString(Path.of(outPath), j.writerWithDefaultPrettyPrinter().writeValueAsString(o));
                System.out.println("wrote result → " + outPath);
            } catch (Exception e) {
                System.out.println("could not write result to " + outPath + ": " + e.getMessage());
            }
        }
        boolean loc = stackLoc != null && stackLoc.localized();
        return new OpsOutcome(loc, loc ? stackLoc.root() : null, rung.name(), acted,
                finalVerified, rolledBack, harmed);
    }

    /** Is anything in the stack actually degraded? Cheap gate for the watch loop so it only acts on a real
     *  fault (the localizer, asked to name a root, would otherwise pick one even on a healthy stack). */
    private static boolean stackDegraded(Exec exec, String project, String appHealth) {
        if (appHealth != null && !appHealth.isBlank()) {
            var r = exec.run("curl -s -m8 " + appHealth, 10);
            if (r.ok() && !r.out().isBlank()) {
                String o = r.out().toLowerCase(Locale.ROOT);
                if (o.matches("(?s).*\"status\" *: *\"(ok|up|healthy|pass|green)\".*")) return false;
                if (o.contains("down") || o.contains("degraded") || o.contains("unhealthy")
                        || o.contains("error") || o.contains("fail")) return true;
            }
        }
        var r = exec.run("docker ps -a --filter label=com.docker.compose.project=" + project
                + " --format '{{.Status}}'", 15);
        if (!r.ok()) return false;
        String o = r.out().toLowerCase(Locale.ROOT);
        return o.contains("unhealthy") || o.contains("exited") || o.contains("restarting") || o.contains("dead");
    }

    /**
     * MULTI-SERVICE / CASCADE fix — run the single-root pipeline REPEATEDLY within one trigger until the stack
     * is green (or no progress). Round 1 fixes the deepest root; re-sensing then reveals the NEXT fault (a
     * second independent outage, or — for a cascade — nothing, because the symptoms cleared). Stops when: the
     * stack is green, nothing localizes, the SAME root recurs (the fix didn't take — avoid a loop), or
     * {@code maxRounds}. Returns the last round's outcome.
     */
    private static OpsOutcome fixRounds(String target, String project, String ceiling, String baseUrl, int maxRounds) {
        var exec = Exec.forTarget(target);
        String appHealth = OpsDiscovery.appHealthUrl(exec, project);
        Set<String> actedOn = new HashSet<>();   // roots we auto-remediated
        String prevNoAction = null;                                  // last root that produced no action
        OpsOutcome last = new OpsOutcome(false, null, "OBSERVE", false, null, false, false);
        for (int round = 1; round <= maxRounds; round++) {
            if (round > 1 && !stackDegraded(exec, project, appHealth)) {
                System.out.println("fix: stack GREEN after " + (round - 1) + " round(s) — done");
                break;
            }
            String incident = "Find and fix any degraded service in the '" + project + "' stack "
                    + "(there may be more than one).";
            OpsOutcome o = ops(target, incident, baseUrl, 25, null, project, ceiling);
            last = o;
            if (!o.localized()) { System.out.println("fix: round " + round + " localized nothing — done"); break; }
            System.out.println("fix: round " + round + " → " + o.line());
            if (o.acted()) {
                // a real fix ran. If the SAME root was already acted-on, the fix isn't holding — stop.
                if (o.root() != null && !actedOn.add(o.root())) {
                    System.out.println("fix: root '" + o.root() + "' recurred after a fix — stopping (unresolved)");
                    break;
                }
                prevNoAction = null;
            } else {
                // no action (PROPOSE/LOCALIZE — e.g. a transient mislocalization or an un-actioned rung). Keep
                // going while other faults remain, but stop if the SAME no-action root repeats (it's stuck).
                if (o.root() != null && o.root().equals(prevNoAction)) {
                    System.out.println("fix: '" + o.root() + "' localized again with no action — stopping");
                    break;
                }
                prevNoAction = o.root();
            }
        }
        return last;
    }

    /** PROACTIVE WATCH — loop the trigger: each cycle, if the stack is degraded, run the fix pipeline; else
     *  idle. This is the continuous autonomous operator (Ctrl-C to stop). */
    private static void watch(String target, String project, String ceiling, int intervalSec, String baseUrl) {
        var exec = Exec.forTarget(target);
        String appHealth = OpsDiscovery.appHealthUrl(exec, project);
        System.out.println("watch: target=" + target + "  project=" + project + "  ceiling=" + ceiling
                + "  interval=" + intervalSec + "s  app-health=" + (appHealth == null ? "(none — docker health)" : appHealth));
        // P3 PROACTIVE (act-BEFORE-outage): deterministic sensors ride the same loop. A long-lived scan
        // instance accumulates samples, so disk/memory trends project hours-to-impact. Predictions are
        // SURFACED (audit + alert + a PROPOSE line naming the subject) — a forecast is not a verified
        // fault, so it never auto-remediates; the reactive path below still owns actual degradation.
        var proactive = new ProactiveScan(exec);
        var authority = OpsAuthority.fromEnv(ceiling);
        Set<String> alreadyWarned = new HashSet<>();
        // SECURITY on the same loop, at the cadence each signal deserves: a runtime INTRUSION is
        // time-critical so it is checked every tick, while POSTURE (published ports, weak creds, image
        // CVEs) changes only on a deploy and is expensive, so it runs periodically. Both REPORT ONLY —
        // security never auto-remediates regardless of the ceiling, because a wrong containment is an
        // outage you inflicted while preventing one that had not happened.
        var secScan = new SecurityScan(exec);
        var secAlerts = new SecurityAlerts(exec);
        boolean securityOn = !"off".equalsIgnoreCase(Config.get("CODEZAIKU_OPS_SECURITY"));
        int postureEvery = Math.max(1, 1800 / Math.max(1, intervalSec));   // ~every 30 minutes
        Set<String> seenDetections = new HashSet<>();
        long cycle = 0;
        while (true) {
            cycle++;
            try {
                if (stackDegraded(exec, project, appHealth)) {
                    System.out.println("\n[watch #" + cycle + "] DEGRADED — triggering fix");
                    OpsOutcome o = fixRounds(target, project, ceiling, baseUrl, 5);   // clear ALL current faults this cycle
                    System.out.println("[watch #" + cycle + "] " + o.line());
                } else {
                    var predictions = proactive.scan();
                    for (var p : predictions) {
                        if (!alreadyWarned.add(p.kind() + "|" + p.subject())) continue;  // once per subject per run
                        System.out.println("[watch #" + cycle + "] PROACTIVE " + p.line());
                        authority.audit("proactive", p.line());
                        authority.alert("proactive", p.line());
                    }
                    if (predictions.isEmpty())
                        System.out.println("[watch #" + cycle + "] all healthy — idle");
                }
                if (securityOn) {
                    // runtime detections: every tick, each distinct rule+subject reported once
                    if (secAlerts.available()) {
                        for (var d : secAlerts.recent(Math.max(5, intervalSec / 60 + 5))) {
                            if (!seenDetections.add(d.rule() + "|" + d.subject())) continue;
                            System.out.println("[watch #" + cycle + "] INTRUSION " + d.line());
                            authority.audit("intrusion", d.line());
                            authority.alert("intrusion", d.line());
                        }
                    } else if (cycle == 1) {
                        System.out.println("[watch #" + cycle + "] INTRUSION no runtime detection stream — "
                                + "nothing is watching syscalls (absence of alerts is not absence of intrusion)");
                    }
                    // posture: periodic, each finding announced once per run
                    if (cycle == 1 || cycle % postureEvery == 0) {
                        for (var f : secScan.scan()) {
                            if (!alreadyWarned.add("sec|" + f.check() + "|" + f.subject())) continue;
                            System.out.println("[watch #" + cycle + "] POSTURE " + f.line());
                            authority.audit("posture", f.line());
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("[watch #" + cycle + "] error: " + e);
            }
            try { Thread.sleep(intervalSec * 1000L); } catch (InterruptedException e) { break; }
        }
    }

    /** OPS DISPATCH SERVER — real triggering over HTTP (JDK built-in server, no deps). POST /fix with
     *  {scope, ceiling, incident} runs the pipeline and returns the OpsOutcome as JSON; GET /health is a
     *  liveness probe. Requests are SERIALIZED (one stack mutation at a time). */
    private static void serve(int port, String baseUrl) throws IOException {
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
        var mapper = new ObjectMapper();
        server.createContext("/health", ex -> respond(ex, 200, "{\"status\":\"ok\"}"));
        // Minimal dashboard: the audit trail (JSON lines) as a JSON array — recent actions/alerts for a UI.
        server.createContext("/audit", ex -> {
            String au = Config.get("CODEZAIKU_OPS_AUDIT");
            if (au == null || au.isBlank() || !Files.exists(Path.of(au))) { respond(ex, 200, "[]"); return; }
            try {
                List<String> all = Files.readAllLines(Path.of(au));
                int n = 100;
                var q = ex.getRequestURI().getQuery();
                if (q != null && q.startsWith("n=")) try { n = Integer.parseInt(q.substring(2)); } catch (Exception ignored) { }
                List<String> tail = all.subList(Math.max(0, all.size() - n), all.size());
                respond(ex, 200, "[" + String.join(",", tail) + "]");
            } catch (Exception e) { respond(ex, 500, "{\"error\":\"audit read failed\"}"); }
        });
        server.createContext("/fix", ex -> {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "{\"error\":\"POST only\"}"); return; }
            try {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                var req = mapper.readTree(body.isBlank() ? "{}" : body);
                var scope = OpsDiscovery.parseScope(req.path("scope").asText(""));
                if (scope.project() == null || scope.project().isBlank()) { respond(ex, 400, "{\"error\":\"'scope' required\"}"); return; }
                String ceiling = req.path("ceiling").asText("propose");
                boolean hasIncident = req.has("incident") && !req.path("incident").asText("").isBlank();
                String incident = hasIncident ? req.path("incident").asText()
                        : "Investigate the '" + scope.project() + "' stack and restore any degraded service.";
                System.out.println("\nserve: POST /fix target=" + scope.target() + " project=" + scope.project() + " ceiling=" + ceiling);
                OpsOutcome o = hasIncident
                        ? ops(scope.target(), incident, baseUrl, 25, null, scope.project(), ceiling)
                        : fixRounds(scope.target(), scope.project(), ceiling, baseUrl, 5);   // no explicit incident → clear all faults
                var res = mapper.createObjectNode();
                res.put("localized", o.localized()); res.put("root", o.root()); res.put("rung", o.rung());
                res.put("acted", o.acted());
                if (o.verified() != null) res.put("verified", o.verified()); else res.putNull("verified");
                res.put("rolledBack", o.rolledBack()); res.put("harmed", o.harmed());
                respond(ex, 200, mapper.writeValueAsString(res));
            } catch (Exception e) {
                respond(ex, 500, "{\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}");
            }
        });
        server.setExecutor(Executors.newSingleThreadExecutor());  // serialize: one fix at a time
        server.start();
        System.out.println("serve: ops dispatch server on :" + port
                + "  —  POST /fix {\"scope\":\"compose:<p>\",\"ceiling\":\"guarded\",\"incident\":\"...\"}  |  GET /health");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String json) {
        try {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
        } catch (Exception ignored) {
        } finally {
            ex.close();
        }
    }

    private static final Pattern BACKTICK = Pattern.compile("`([^`]+)`");
    // CLI verbs whose backtick-wrapped lines in a card ARE the fix command (vs prose / SQL snippets / values).
    private static final Set<String> CLI_VERBS = Set.of(
            "psql", "redis-cli", "rabbitmqctl", "rabbitmqadmin", "mongosh", "mongo", "cypher-shell",
            "ollama", "curl", "kubectl", "mysql", "clickhouse-client", "influx", "nats", "aws");

    /** Pull the exact fix command(s) out of a (grounded) card — its backtick-wrapped CLI lines — so they can
     *  be handed to the remediator as the suggested action it runs verbatim. A bare service CLI (psql,
     *  redis-cli, …) is wrapped in {@code docker exec <container>} so it runs inside the target. Longest
     *  first (prefer the full fix over a shorter diagnostic probe). */
    private static List<String> extractFixCommands(String card, String container) {
        List<String> out = new ArrayList<>();
        if (card == null || card.isBlank()) return out;
        var m = BACKTICK.matcher(card);
        while (m.find()) {
            String c = m.group(1).trim();
            if (c.isBlank()) continue;
            String first = c.split("\\s+")[0];
            if (!CLI_VERBS.contains(first)) continue;   // skip SQL snippets / values / prose
            if (c.matches("(?s).*<[a-zA-Z][^>]*>.*")) continue;   // skip a still-templated command (unfilled
                                                                 // <placeholder> — e.g. a k8s example whose
                                                                 // <pod>/<ns> this stack can't ground); never
                                                                 // surface or hand a non-runnable command
            if (!first.equals("curl") && !first.equals("kubectl") && !first.equals("aws")
                    && !c.startsWith("docker ") && container != null && !container.isBlank()) {
                c = "docker exec " + container + " " + c;   // run a bare service CLI inside the container
            }
            if (!out.contains(c)) out.add(c);
        }
        out.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return out;
    }

    /** Ground a general fix-procedure card against THIS stack: substitute its {@code <container>}, {@code
     *  <user>}, {@code <db>}, {@code <pass>}, {@code <host>}, {@code <port>} placeholders with the target's
     *  own declared values (container name from the localizer, credentials from the container env). A general
     *  card the 9B can't assemble becomes a concrete command it can run verbatim. */
    private static String groundCard(String card, String container, String service, String env) {
        if (card == null || card.isBlank()) return card;
        String user = envVal(env, "(^|_)USER="), db = envVal(env, "(^|_)(DB|DATABASE)=");
        String pass = envVal(env, "(^|_)(PASSWORD|PASS)="), port = envVal(env, "(^|_)PORT=");
        String out = card;
        if (container != null && !container.isBlank()) out = out.replace("<container>", container);
        if (!service.isBlank()) out = out.replace("<host>", service).replace("<service>", service);
        if (!user.isBlank()) out = out.replace("<user>", user);
        if (!db.isBlank())   out = out.replace("<db>", db).replace("<database>", db);
        if (!pass.isBlank()) out = out.replace("<pass>", pass).replace("<password>", pass);
        if (!port.isBlank()) out = out.replace("<port>", port);
        return out;
    }

    /** First env-line value whose KEY matches {@code keyRegex} (case-insensitive), or "". */
    private static String envVal(String env, String keyRegex) {
        if (env == null) return "";
        Pattern p = Pattern.compile(keyRegex, Pattern.CASE_INSENSITIVE);
        for (String line : env.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq + 1);   // include '=' so KEY= anchors match
            if (p.matcher(key).find()) return line.substring(eq + 1).trim();
        }
        return "";
    }

    /** Did the remediation write to a persistent CONFIG FILE (vs a runtime mechanism)? A config-file edit can
     *  be a LATENT time-bomb — a bad line loads only on the next restart, passing verify now but crash-looping
     *  the service later (this is how the 30B contaminated downstream runs by corrupting postgresql.conf). Such
     *  a fix is restart-validated; runtime fixes (CONFIG SET / ALTER / index-setting / pull / rabbitmqctl) are
     *  not, so they are never needlessly restarted. */
    private static boolean touchedConfigFile(List<String> steps) {
        if (steps == null) return false;
        for (String s : steps) {
            String l = s.toLowerCase(Locale.ROOT);
            // a config FILE (extension or a conf/config directory) — NOT a runtime "CONFIG SET" command
            boolean cfgFile = l.contains(".conf") || l.contains(".cnf") || l.contains(".ini")
                    || l.contains(".properties") || l.contains("/conf/") || l.contains("/config/")
                    || l.contains("postgresql.auto") || l.contains("pg_hba");
            boolean writeOp = l.contains(">") || l.contains(" tee ") || l.contains("sed ") || l.contains(" echo ")
                    || l.startsWith("echo ") || l.contains("truncate") || l.contains("<<") || l.contains("printf ");
            if (cfgFile && writeOp) return true;
        }
        return false;
    }

    /**
     * The flags a container was actually LAUNCHED with — argv elements that are flags in their own
     * right, never text found inside one.
     *
     * <p>This is the difference between a setting that is pinned and one that is merely mentioned. A
     * genuinely pinned config is separate argv:
     * {@code ["redis-server","--requirepass","s3cret"]}. A wrapper is ONE element containing a whole
     * shell script: {@code ["sh","-c","if [ -n \"$PW\" ]; then exec redis-server --requirepass ...; else
     * exec redis-server; fi"]} — where {@code --requirepass} appears in a branch that may never run.
     * Substring-matching the raw JSON cannot tell them apart, and measured, it did not: the launch-args
     * card fired on a RUNTIME fault because the flag sat in the untaken half of a conditional.
     *
     * <p>Deliberately STRICT. A wrapper that really does apply a flag is missed, and that is the right
     * direction to fail: a miss falls back to the certified behaviour (the generic card, its band-aid
     * caught by the latent check and rolled back), whereas a false positive hands the model the wrong
     * procedure for the fault it actually has.
     */
    static String launchFlags(String argvJson) {
        if (argvJson == null || argvJson.isBlank()) return "";
        StringBuilder out = new StringBuilder();
        try {
            var arr = new ObjectMapper().readTree(argvJson);
            if (!arr.isArray()) return "";
            for (var el : arr) {
                String v = el.asText("").trim();
                // a flag in its own right: starts with '-' and carries no whitespace (a shell script
                // element would), so `--requirepass` counts and `-c <script>` contributes nothing.
                if (v.startsWith("-") && !v.matches("(?s).*\\s.*")) out.append(v).append(' ');
            }
        } catch (Exception ignored) {
            return "";   // unparseable argv tells us nothing; say nothing rather than guess
        }
        return out.toString().trim();
    }

    /**
     * A RUNTIME setting write whose value is ALSO fixed on the container's command line — a fix that
     * verifies now and is gone at the next restart.
     *
     * <p>{@link #touchedConfigFile} deliberately excludes {@code CONFIG SET} on the grounds that
     * "runtime fixes do not touch config files, so they are never needlessly restarted". That holds
     * while the runtime value IS the source of truth, and fails exactly when something outranks it:
     * a redis started as {@code redis-server --requirepass s3cret} takes {@code CONFIG SET requirepass ''}
     * happily, passes the closed-loop verify, and comes back demanding the password on the next
     * restart. The fix is latent, and the run is recorded as a success.
     *
     * <p>The check is deliberately NARROW rather than "restart-validate every runtime write": that
     * would bounce a production service after every {@code CONFIG SET}, and an unnecessary restart is
     * itself a harm. Instead it asks whether THIS setting is pinned on the command line — one
     * {@code docker inspect}, and on a stack where the value is not pinned (the certified refstack,
     * whose redis runs with a bare {@code redis-server}) it correctly finds nothing and changes
     * nothing.
     */
    static boolean runtimeWriteOverriddenByCommandLine(Exec exec, String container,
                                                               List<String> steps) {
        if (exec == null || container == null || container.isBlank() || steps == null) return false;
        List<String> keys = new ArrayList<>();
        Matcher m;
        for (String s : steps) {
            m = Pattern.compile("\\bconfig\\s+set\\s+([a-z0-9_.-]+)",
                    Pattern.CASE_INSENSITIVE).matcher(s);
            while (m.find()) keys.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        if (keys.isEmpty()) return false;
        Exec.Result r = exec.run("docker inspect " + container
                + " --format '{{json .Config.Cmd}} {{json .Config.Entrypoint}}' 2>/dev/null", 15);
        if (!r.ok() || r.out() == null) return false;
        String cmdline = r.out().toLowerCase(Locale.ROOT);
        for (String k : keys) {
            if (cmdline.contains("--" + k) || cmdline.contains("\"" + k + "\"")) {
                System.out.println("ops: fix set '" + k + "' at RUNTIME while the container's command line "
                        + "also pins it — the fix will not survive a restart");
                return true;
            }
        }
        return false;
    }

    /** The dependencies the app reports healthy right now, from its /health readiness report — the before/
     *  after sets whose difference is the harm metric (a previously-healthy service the fix broke). */
    private static Set<String> healthyDeps(Exec exec, String appHealthUrl) {
        Set<String> ok = new HashSet<>();
        if (appHealthUrl == null || appHealthUrl.isBlank()) return ok;
        try {
            Exec.Result r = exec.run("curl -s -m8 " + appHealthUrl, 12);
            if (!r.ok() || r.out() == null || r.out().isBlank()) {
                // The wrong-SHAPE branch below already announces itself; an UNREACHABLE endpoint used to
                // return silently, which is the same failure wearing different clothes — an empty
                // healthy-set that reads as "everything is down" with nothing in the log to say why.
                System.err.println("WARN: readiness at " + appHealthUrl + " did not answer (exit "
                        + r.exit() + ") — the harm metric has NO input for this probe");
                return ok;
            }
            var deps = new ObjectMapper().readTree(r.out()).path("deps");
            if (deps.isMissingNode() || !deps.isObject()) {
                // Say so. This is the harm metric's only input, and a readiness endpoint in a shape we
                // cannot read produced an empty healthy-set indistinguishable from "everything is
                // down" — silently, because the shape is documented nowhere and the failure was a
                // swallowed exception. A control that cannot inform must announce it.
                // stderr, never stdout: stdout is the protocol channel on run/acp/mcp.
                System.err.println("WARN: readiness at " + appHealthUrl + " has no object field 'deps'"
                        + " — the harm metric has NO input and cannot tell a broken dependency from an"
                        + " unreadable report. Expected {\"deps\":{\"<name>\":\"ok\"}};"
                        + " see CONFIGURATION.md.");
                return ok;
            }
            deps.fieldNames().forEachRemaining(f -> { if ("ok".equals(deps.path(f).asText())) ok.add(f); });
        } catch (Exception e) {
            System.err.println("WARN: readiness at " + appHealthUrl + " could not be read (" + e
                    + ") — harm metric has no input");
        }
        return ok;
    }

    // Snapshot/rollback for decomposition — exclude build/dep dirs (huge, regenerable).
    private static final Set<String> SNAP_EXCLUDE =
            Set.of("target", ".git", ".godot", "node_modules", ".venv", "build", ".gradle");

    private static void snapshot(Path root, Path snap) throws IOException {
        deleteTree(snap);
        copyTree(root, snap);
    }

    private static void restore(Path snap, Path root) throws IOException {
        try (var s = Files.list(root)) {
            for (Path p : s.toList()) {
                if (!SNAP_EXCLUDE.contains(p.getFileName().toString())) deleteTree(p);
            }
        }
        copyTree(snap, root);
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (var walk = Files.walk(from)) {
            for (Path src : walk.toList()) {
                Path rel = from.relativize(src);
                if (rel.getNameCount() > 0 && SNAP_EXCLUDE.contains(rel.getName(0).toString())) continue;
                Path dst = to.resolve(rel);
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    if (dst.getParent() != null) Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(x -> {
                try { Files.delete(x); } catch (IOException ignored) { }
            });
        }
    }


    /** Index Rust crate sources into the Library: crate-index <framework> <crate-dir>... */
    private static void crateIndex(String[] args) {
        String framework = args[1];
        Path reg = cargoRegistrySrc();
        Path coll = libraryIndexDir();
        int total = 0;
        for (int i = 2; i < args.length; i++) {
            String dirName = args[i]; // e.g. sysinfo-0.38.4
            Path crateDir = reg.resolve(dirName);
            if (!Files.isDirectory(crateDir)) {
                System.out.println("MISSING crate dir: " + crateDir);
                continue;
            }
            String version = dirName.replaceFirst("^.*?-(\\d.*)$", "$1");
            String crateName = dirName.replaceFirst("-\\d.*$", "");
            try {
                int n = CrateIndexer.index(coll, framework, version, crateName, crateDir);
                System.out.println("indexed " + n + " chunks from " + dirName + "  framework=" + framework);
                total += n;
            } catch (Exception e) {
                System.out.println("ERROR indexing " + dirName + ": " + e);
            }
        }
        System.out.println("total: " + total + " chunks → " + coll);
    }

    /** Port the EVERGREEN knowledge-packs into the library (version-pinned rot is excluded). */
    private static void kpIndex() {
        String env = Config.get("CODEZAIKU_KNOWLEDGE_PACKS");
        Path root = (env != null && !env.isBlank()) ? Path.of(env)
                : Config.home().resolve("knowledge-packs");
        try {
            root = root.toRealPath(); // resolve the symlink — Files.walk won't traverse a symlink root
        } catch (Exception ignored) {
            // use as-is
        }
        Predicate<Path> evergreen = p -> {
            String s = p.toString();
            String n = p.getFileName().toString();
            if (n.startsWith("_") || n.equals("README.md")) return false;          // sidecars/audits
            if (s.contains("/templates/") || s.contains("/toolchains/")) return false; // rot, separate mechanism
            if (s.contains("/library-seed/")) return true;        // all evergreen now (version-pinned rot deleted)
            return s.contains("-patterns/");                      // conceptual patterns = evergreen
        };
        try {
            int n = KnowledgePackIndexer.indexTree(libraryIndexDir(), root, evergreen);
            System.out.println("indexed " + n + " evergreen knowledge-pack sections → " + libraryIndexDir());
        } catch (Exception e) {
            System.out.println("ERROR: " + e);
        }
    }

    private static Path cargoRegistrySrc() {
        Path base = Path.of(System.getProperty("user.home"), ".cargo", "registry", "src");
        try (var s = Files.list(base)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("index.crates.io-"))
                    .findFirst().orElse(base);
        } catch (Exception e) {
            return base;
        }
    }

    /** The framework-knowledge Library index dir (the Lucene `library` collection). */
    public static Path libraryIndexDir() {
        String env = Config.get("CODEZAIKU_OCEAN_DIR");
        Path ocean = (env != null && !env.isBlank()) ? Path.of(env)
                : Config.home().resolve("ocean");
        return ocean.resolve("library");
    }

    /** Smoke: query the real Library index and print top-k chunks (validates retrieval). */
    private static void librarySearch(String query, int k, String framework) {
        Path dir = libraryIndexDir();
        try (var index = new LibraryIndex(dir)) {
            System.out.println("index: " + dir + "  available=" + index.available() + "  docs=" + index.size());
            var chunks = index.search(query, framework, k);
            System.out.println("query: \"" + query + "\"  framework=" + framework + "  →  " + chunks.size() + " chunks");
            int n = 0;
            for (var c : chunks) {
                String txt = c.content() == null ? "" : c.content();
                if (txt.length() > 320) txt = txt.substring(0, 320) + "…";
                System.out.println("\n[" + (++n) + "] score=" + String.format("%.3f", c.score())
                        + "  framework=" + c.framework() + "  version=" + c.version() + "  title=" + c.title());
                System.out.println("    " + txt.replace("\n", " ").trim());
            }
        }
    }

    /** Run a gate against a project with no model in the loop — validates the gate itself. */
    private static void gateOnly(Path root, String gateName) {
        Gate gate = gateByName(gateName);
        if (gate == null) {
            System.err.println("unknown gate: " + gateName);
            System.exit(2);
        }
        GateResult r = gate.certify(root);
        System.out.println(r.evidence());
        System.out.println("=== GATE " + (r.pass() ? "PASS" : "FAIL") + " "
                + r.claimsPassed() + "/" + r.claimsTotal() + " ===");
        System.exit(r.pass() ? 0 : 1);
    }

    private static void loop(Path root, String goal, String baseUrl, int maxTurns, String gateName) {
        FamiliarLoop.Result result = runLoop(root, goal, baseUrl, maxTurns, gateName);
        System.out.println("\n=== LOOP " + (result.done() ? "task_done" : "STOPPED")
                + " after " + result.turns() + " turns ===");
        System.out.println(result.summary());
        System.exit(result.done() ? 0 : 1);
    }

    /** The coding-familiar loop, returnable (no System.exit, no printing of the banner) — used by the CLI
     *  {@code loop} verb AND by the MCP server's {@code code} tool. Same library/gate behavior either way. */
    public static FamiliarLoop.Result runLoop(Path root, String goal, String baseUrl, int maxTurns, String gateName) {
        return runLoop(root, goal, baseUrl, maxTurns, gateName, null);
    }

    /** As above, with an explicit C/M/R mode override (create|maintain|repair; null/"auto" ⇒ detect per §6.3). */
    public static FamiliarLoop.Result runLoop(Path root, String goal, String baseUrl, int maxTurns,
                                              String gateName, String modeOverride) {
        return runLoopTracked(root, goal, baseUrl, maxTurns, gateName, modeOverride).result();
    }

    /**
     * The loop result together with the run's write ledger.
     *
     * <p>{@code files} is exact for tool-mediated writes; {@code filesComplete} is false when an
     * unscoped shell command that could modify files also ran, in which case the list is a lower bound
     * and the caller should reconcile against git rather than present it as the whole set.
     */
    public record Tracked(FamiliarLoop.Result result, List<String> files, boolean filesComplete) { }

    /** As {@link #runLoop}, also returning which files the run wrote. */
    public static Tracked runLoopTracked(Path root, String goal, String baseUrl, int maxTurns,
                                         String gateName, String modeOverride) {
        return runLoopTracked(root, goal, baseUrl, maxTurns, gateName, modeOverride, MODEL);
    }

    /**
     * As above with an explicit model name, for a host that selects the model per invocation rather
     * than from this machine's configuration. Passed through rather than written to the config file:
     * a per-call flag must not mutate persistent state a later run would inherit.
     */
    public static Tracked runLoopTracked(Path root, String goal, String baseUrl, int maxTurns,
                                         String gateName, String modeOverride, String model) {
        return runLoopTracked(root, goal, baseUrl, maxTurns, gateName, modeOverride, model, null);
    }

    /**
     * Optional hooks for a protocol host: stream tool activity as it happens, and cancel a run in
     * flight. Both are null-safe — the CLI path passes neither.
     */
    public record Hooks(ToolRegistry.Listener listener,
                        BooleanSupplier cancelled,
                        java.util.List<org.codezaiku.tools.Tool> extraTools) {
        public Hooks(ToolRegistry.Listener listener, BooleanSupplier cancelled) { this(listener, cancelled, java.util.List.of()); }
    }

    /** As above, with host hooks for streaming and cancellation. */
    /** Apply the mode's loop configuration. Only ARTIFACT changes anything today: it is the one mode
     *  whose deliverable is not a codebase, so the gates that drive a model toward green tests would
     *  drive it to invent them instead. */
    private static FamiliarLoop forMode(FamiliarLoop loop, CodeMode mode) {
        return mode == CodeMode.ARTIFACT ? loop.artifact() : loop;
    }

    public static Tracked runLoopTracked(Path root, String goal, String baseUrl, int maxTurns,
                                         String gateName, String modeOverride, String model, Hooks hooks) {
        // C/M/R task-shape (SPEC_CODEZAIKU_AS_FAMILIAR §6): resolve the mode, surface it with evidence, and
        // prepend its shaping guidance to the goal so the small model gets the right posture.
        CodeMode mode = CodeMode.resolve(root, goal, modeOverride);
        System.out.println("code-mode: " + mode + " — " + CodeMode.evidence(root, goal, mode));
        // Project DNA (§17.6): load this project's accumulated CONVENTIONS into context so the familiar follows
        // the codebase's norms (fed via the MCP record_convention tool from bondholder accept/correct events).
        String conventions = ProjectConventions.promptBlock(root);
        if (!conventions.isEmpty()) System.out.println("project conventions: loaded");
        goal = mode.preamble() + "\n\n" + conventions + "TASK:\n" + goal;
        var drive = new DriveClient(baseUrl, model == null || model.isBlank() ? MODEL : model);
        var lsp = LspClient.forProject(root, ProjectFacts.language(root));
        var tools = ToolRegistry.standard(root, lsp); // no gate; lsp powers edit_file fallback + replace_symbol
        if (hooks != null && hooks.listener() != null) tools.listener(hooks.listener());
        // Tools the host brought: the MCP servers an ACP client named in session/new, already started and listed.
        if (hooks != null && hooks.extraTools() != null) for (var extra : hooks.extraTools()) tools.add(extra);
        BooleanSupplier cancel = hooks == null ? null : hooks.cancelled();
        FamiliarLoop.Result result;
        // LIBRARY A/B (CODEZAIKU_LIB_OFF=1): OFF arm passes null library+index -> no idioms, no error-grounding
        // injection, no worked-examples. ProjectShape (project-shape snapshot) stays ON either way (always-on).
        if ("1".equals(Config.get("CODEZAIKU_LIB_OFF")) || "off".equalsIgnoreCase(Config.get("CODEZAIKU_LIB_OFF"))) {
            System.out.println("library: OFF (A/B baseline -- idioms + error-grounding + worked-examples disabled; project-shape still on)");
            result = forMode(new FamiliarLoop(drive, tools, root, goal, maxTurns, null, null), mode)
                    .lsp(lsp).cancelIf(cancel).run();
        } else {
            try (var index = new LibraryIndex(libraryIndexDir())) {
                if (index.available()) {
                    System.out.println("library: " + index.size() + " chunks indexed at " + libraryIndexDir());
                }
                result = forMode(new FamiliarLoop(drive, tools, root, goal, maxTurns, new Library(), index), mode)
                        .lsp(lsp).cancelIf(cancel).run();
            }
        }
        var scope = tools.scope();
        return new Tracked(result,
                scope == null ? List.of() : scope.written(),
                scope != null && !scope.mayBeIncomplete());
    }

    /** Read-only code REVIEW → structured findings (no edits). Reuses the familiar's navigation with the
     *  read-only tool surface. {@code scopeSpec} = null/blank for the whole tree, a git ref/range, or {@code @path} to a unified diff on disk. */
    public static FamiliarLoop.Result review(Path root, String scopeSpec, String baseUrl, int maxTurns) {
        // HAND THE MODEL THE DIFF — do not ask it to go and fetch one. Measured: asked to review
        // "the changes in `git diff HEAD~2`", the 9B ran `git status --short`, saw a clean tree, and
        // concluded "no changes to review" — it never ran git diff at all, then wandered for 30 turns.
        // The harness already computes this diff to anchor findings, so making the model re-derive it
        // was pure risk. Machine-computed evidence outranks model judgment.
        String diff = gitDiff(root, scopeSpec);
        String what;
        String evidence = "";
        if (diff.isBlank()) {
            what = "the project's code";
        } else {
            what = "the change below";
            String shown = diff.length() > DIFF_BUDGET_CHARS
                    ? diff.substring(0, DIFF_BUDGET_CHARS) + "\n[... diff truncated — read the files for the rest]"
                    : diff;
            String origin = scopeSpec.startsWith("@") ? "supplied patch" : "git diff " + scopeSpec;
            evidence = "\n\nTHE CHANGE UNDER REVIEW (" + origin + "):\n"
                     + "```diff\n" + shown + "\n```\n";
        }
        // The model quotes the offending CODE; the harness computes the line (org.codezaiku.review).
        // Asking a model for file:line makes it do arithmetic over hunk headers, which is the usual
        // cause of a comment landing on the wrong line — and a 9B is worse at it than most.
        // The deliverable is stated as a LIST OF DEFECTS, positively and up front. Asked merely for a
        // "structured findings list", the 9B wrote an accurate prose summary of what the change does —
        // a description, not a review — and nothing parsed.
        // Findings are REPORTED THROUGH A TOOL, not written as formatted text. Measured: asked for a
        // one-line text format, this model wrote accurate prose and nothing could be anchored, three
        // variants running. Emitting a tool call is the one thing the harness already relies on it
        // doing well.
        String goal = "REVIEW (READ-ONLY — do NOT modify any files). Find DEFECTS in " + what + ": "
                + "bugs and incorrect logic, security problems, and maintainability traps. Use read_file "
                + "and shell (grep, cat) to read any surrounding code you need for context.\n"
                + "Report EVERY defect you find by calling the `report_finding` tool — one call per "
                + "defect, as soon as you find it. It takes the offending line of code itself, copied "
                + "exactly, and the harness works out which line that is. Report only problems: what a "
                + "change DOES is not a defect.\n"
                + "When you have reported every defect, call task_done. If the code is sound, call "
                + "task_done saying so without reporting any finding. Do NOT edit anything."
                + evidence;
        System.out.println("review: " + root + (scopeSpec == null ? "" : " @ " + scopeSpec));
        var drive = new DriveClient(baseUrl, MODEL);
        var lsp = LspClient.forProject(root, ProjectFacts.language(root));
        PathScope scope = new PathScope(root);
        var reporter = new ReportFindingTool(diff, f -> readWithinProject(scope, f));
        var tools = ToolRegistry.readOnly(root, lsp).add(reporter);
        // MULTI-PASS. Measured across 25 PRs at K=3: recall by the UNION of runs was 48% against 32%
        // for the mean — five of the thirteen PRs missed in one run had been found in another. The
        // findings are within reach; they just do not surface every pass. Repeating and unioning
        // converts that gap into output.
        //
        // OPT-IN, not the default: N passes cost N times the model time, and quietly tripling how
        // long a review takes is not a change to make on the user's behalf. It also unions the false
        // positives, which the recall figure does not show.
        int passes = Math.max(1, Math.min(5, Integer.parseInt(Config.get("CODEZAIKU_REVIEW_PASSES", "1"))));

        // BUNDLING. Measured on 25 real PRs: 12 had diffs larger than DIFF_BUDGET_CHARS, so the model
        // was shown only the first part of half the sample and reviewed the rest blind. That is the
        // largest known drag on recall and nothing to do with the model.
        //
        // A diff splits cleanly at file boundaries and each file's hunk headers already carry absolute
        // line numbers, so a per-file diff anchors exactly as well as the whole one. Only engaged when
        // the change actually overflows — a diff that already fits is best reviewed whole, where the
        // model sees every file at once and it costs one call.
        List<String> filteredOut = new ArrayList<>();
        List<DiffBundler.Bundle> bundles = diff.isBlank() || diff.length() <= DIFF_BUDGET_CHARS
                ? List.of()
                : DiffBundler.bundle(diff, DIFF_BUDGET_CHARS, filteredOut);
        if (!filteredOut.isEmpty()) {
            // Announced, never silent: a file dropped quietly is indistinguishable from one reviewed
            // and found clean. Measured motivation — a 676k pandas diff was 599k of pixi.lock.
            System.out.println("review: skipped " + filteredOut.size() + " generated file(s), not reviewed: "
                    + String.join(", ", filteredOut));
        }
        if (bundles.size() > 1) {
            System.out.println("review: change is " + diff.length() / 1000 + "k — splitting into "
                    + bundles.size() + " bundles so none of it is truncated");
            return reviewBundles(root, scopeSpec, baseUrl, maxTurns, bundles, passes, drive, lsp, scope);
        }
        try (var index = new LibraryIndex(libraryIndexDir())) {
            List<List<ReviewReport.Finding>> collected = new ArrayList<>();
            FamiliarLoop.Result last = null;
            for (int pass = 1; pass <= passes; pass++) {
                var reporterN = (pass == 1) ? reporter
                        : new ReportFindingTool(diff, f -> readWithinProject(scope, f));
                var toolsN = (pass == 1) ? tools : ToolRegistry.readOnly(root, lsp).add(reporterN);
                if (passes > 1) System.out.println("review: pass " + pass + "/" + passes);
                last = new FamiliarLoop(drive, toolsN, root, goal, maxTurns, new Library(), index)
                        .lsp(lsp).report().findings(() -> reporterN.findings().size()).run();
                collected.add(reporterN.findings());
            }
            lastReviewFindings = ReviewReport.union(collected);
            if (passes > 1) {
                int raw = collected.stream().mapToInt(List::size).sum();
                System.out.println("review: " + raw + " finding(s) across " + passes
                        + " passes → " + lastReviewFindings.size() + " after merging duplicates");
            }
            return last;
        }
    }

    /** Findings from the most recent {@link #review} call, reported through the tool. */
    private static List<ReviewReport.Finding> lastReviewFindings = List.of();

    /**
     * Review a large change one bundle at a time, unioning the findings.
     *
     * <p>Each bundle is a valid unified diff in its own right, so anchoring is unaffected — the hunk
     * headers still carry the file's absolute line numbers. Findings are merged with the same overlap
     * rule the multi-pass union uses, because two bundles can legitimately report the same defect when
     * a file appears in both.
     */
    private static FamiliarLoop.Result reviewBundles(
            Path root, String scopeSpec, String baseUrl, int maxTurns,
            List<DiffBundler.Bundle> bundles, int passes,
            DriveClient drive, LspClient lsp, PathScope scope) {
        List<List<ReviewReport.Finding>> collected = new ArrayList<>();
        FamiliarLoop.Result last = null;
        try (var index = new LibraryIndex(libraryIndexDir())) {
            int n = 0;
            for (DiffBundler.Bundle b : bundles) {
                n++;
                for (int pass = 1; pass <= passes; pass++) {
                    var reporter = new ReportFindingTool(b.diff(), f -> readWithinProject(scope, f));
                    var tools = ToolRegistry.readOnly(root, lsp).add(reporter);
                    System.out.println("review: bundle " + n + "/" + bundles.size()
                            + (passes > 1 ? " pass " + pass + "/" + passes : "")
                            + " — " + String.join(", ", b.files()));
                    last = new FamiliarLoop(drive, tools, root, bundleGoal(b), maxTurns,
                            new Library(), index)
                            .lsp(lsp).report().findings(() -> reporter.findings().size()).run();
                    collected.add(reporter.findings());
                }
            }
        }
        lastReviewFindings = ReviewReport.union(collected);
        int raw = collected.stream().mapToInt(List::size).sum();
        System.out.println("review: " + raw + " finding(s) across " + collected.size()
                + " bundle-passes -> " + lastReviewFindings.size() + " after merging duplicates");
        return last;
    }

    /** The per-bundle goal. Same contract as a whole-diff review, scoped to these files. */
    private static String bundleGoal(DiffBundler.Bundle b) {
        return "REVIEW (READ-ONLY — do NOT modify any files). Find DEFECTS in the change below: "
                + "bugs and incorrect logic, security problems, and maintainability traps. Use read_file "
                + "and shell (grep, cat) to read any surrounding code you need for context.\n"
                + "Report EVERY defect you find by calling the `report_finding` tool — one call per "
                + "defect, as soon as you find it. It takes the offending line of code itself, copied "
                + "exactly, and the harness works out which line that is. Report only problems: what a "
                + "change DOES is not a defect.\n"
                + "When you have reported every defect, call task_done. If the code is sound, call "
                + "task_done saying so without reporting any finding. Do NOT edit anything."
                + "\n\nTHE CHANGE UNDER REVIEW (" + String.join(", ", b.files()) + "):\n"
                + "```diff\n" + b.diff() + "\n```\n";
    }

    /**
     * How much diff to inline before falling back to "read the files".
     *
     * <p>Sized against the WINDOW, not against what looks generous. The goal is pinned for the whole run,
     * so every character here is spent permanently. At 60k this overran a 32k-token context outright —
     * measured: {@code request (33533 tokens) exceeds the context window} on the deadline turn, which
     * then could not even ask for the findings. ~12k chars is roughly 3-4k tokens, under a tenth of the
     * window, leaving room for the reading and reasoning that produce the actual review.
     */
    private static final int DIFF_BUDGET_CHARS = 12_000;


    /**
     * Write findings as SARIF when {@code CODEZAIKU_SARIF} names a path.
     *
     * <p>Off unless asked for: writing a file nobody requested is a surprise, and the console output
     * is what a person reads. The point is the other consumer — GitHub code scanning, DefectDojo, the
     * VS Code viewer — which needs a file rather than a terminal.
     */
    static void writeSarif(List<ReviewReport.Finding> findings) {
        String path = Config.get("CODEZAIKU_SARIF");
        if (path == null || path.isBlank()) return;
        try {
            // VERSION, not a literal: the release checklist names three places to bump and this was a
            // silent fourth. A stale version here does not look wrong — the SARIF is valid and the
            // findings are right — it just tells whatever ingests it that an older CodeZaiku found them.
            String json = Sarif.toJson(
                    Sarif.fromReview(findings), "CodeZaiku", VERSION);
            Files.writeString(Path.of(path), json);
            System.out.println("review: wrote " + findings.size() + " finding(s) as SARIF to " + path);
        } catch (Exception e) {
            // Never fail a completed review because a report file could not be written — the findings
            // are already on the console, which is the copy a human reads.
            System.err.println("review: could not write SARIF to " + path + ": " + e.getMessage());
        }
    }

    /**
     * Replace the model's quoted code with the line number the harness computed for it.
     *
     * <p>Falls back to the raw summary when nothing parses: the model ignoring the format is a reason to
     * show its output unchanged, not to discard a review.
     */
    public static String anchorReview(Path root, String scopeSpec, String summary) {
        // Findings reported through report_finding win: they arrived as structured arguments and were
        // anchored when they were made. Parsing prose is the fallback for a model that narrated its
        // findings instead of calling the tool.
        if (!lastReviewFindings.isEmpty()) return ReviewReport.render(lastReviewFindings);
        String diff = gitDiff(root, scopeSpec);
        PathScope scope = new PathScope(root);
        var findings = ReviewReport.parse(summary, diff, f -> readWithinProject(scope, f));
        if (findings.isEmpty()) return summary;
        return ReviewReport.render(findings);
    }

    /**
     * The reviewed change as a unified diff; empty for a whole-project review.
     *
     * <p>Scrubbed here, at the single source both consumers share — the prompt and the anchor matcher.
     * That matters twice over: a diff of a config file carries credentials straight to the drive, which
     * for anyone pointing CodeZaiku at a hosted API means shipping their secrets off the machine; and
     * redacting for the prompt but anchoring against the raw text would make every quote of a masked
     * line fail to match. Masking does NOT cost a finding — {@code password=‹redacted›} still shows a
     * hardcoded credential is there, which is the reviewable fact.
     */
    private static String gitDiff(Path root, String scopeSpec) {
        if (scopeSpec == null || scopeSpec.isBlank()) return "";
        // `@path` = review a unified diff that is already on disk, with no checkout. Matches the
        // @file convention the other verbs use for goals. Two reasons this exists: CI systems
        // typically have the patch and not the repo, and scoring the review surface against a corpus
        // of real PRs would otherwise mean cloning every repo those PRs came from — Kubernetes and
        // Rust among them. Anchoring still works: DiffAnchor's primary path reads the diff itself,
        // and only the whole-file fallback needs a checkout.
        if (scopeSpec.startsWith("@")) {
            try {
                return Redactor.scrub(Files.readString(Path.of(scopeSpec.substring(1))));
            } catch (Exception e) {
                System.err.println("could not read diff file " + scopeSpec + ": " + e);
                return "";
            }
        }
        try {
            Process p = new ProcessBuilder("git", "diff", "-U3", scopeSpec)
                    .directory(root.toFile()).redirectErrorStream(false).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return Redactor.scrub(out);
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /** Read a finding's file for the content fallback — through PathScope, so a quoted path cannot escape. */
    private static String readWithinProject(PathScope scope, String file) {
        try {
            // Scrubbed for the same reason as gitDiff: this is the anchor fallback, so it must agree
            // with the masked text the model was shown or a quote of a masked line can never match.
            return Redactor.scrub(Files.readString(scope.resolve(file)));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * RESEARCH — search → fetch → synthesize, in two shapes. {@code broad}: survey the landscape with many
     * queries, skim many sources, report options/comparisons/consensus. {@code depth}: few queries, read the
     * best sources thoroughly, answer one narrow question with cited evidence. Read-only + web tools.
     */
    /**
     * FAN-OUT research: decompose → parallel workers with fresh contexts → a critic that owns the
     * stop decision → synthesis. The lever the 2026-07 "harness levers exhausted" verdict could not
     * pull, because it was measured under one sequential loop: breadth questions (WideSearch's
     * whole shape) ground one context through facet after facet, each paying for all the others'
     * history. Here each sub-question gets its OWN loop and window (the deep-research-harness
     * shape, reviewed 2026-08-29), the fan-out is deterministic code — one worker per open
     * sub-question, never model-decided — and another round happens only when the CRITIC says
     * coverage is missing, not when the generator feels done (the held-out-grader lesson, applied
     * at runtime).
     */
    public static FamiliarLoop.Result researchFan(String question, String baseUrl, int maxTurns) {
        var drive = new DriveClient(baseUrl, MODEL);
        int workers = Config.getInt("CODEZAIKU_RESEARCH_WORKERS", 4);
        int workerTurns = Config.getInt("CODEZAIKU_RESEARCH_WORKER_TURNS", 14);
        int maxRounds = Config.getInt("CODEZAIKU_RESEARCH_ROUNDS", 2);
        var json = new ObjectMapper();

        // 1. Decompose — one deterministic call, JSON out. On any parse failure the question
        //    itself is the single sub-question and this degrades to depth-research + synthesis.
        // The library consults FIRST (the compounding loop): what ResearchZosho holds is pushed into
        // decompose so the fan runs only on the GAPS. "" when no daemon answers or it holds nothing.
        String libKnown = org.codezaiku.research.LibraryBridge.push(question, 6);
        java.util.List<String> open = new java.util.ArrayList<>();
        try {
            var msgs = json.createArrayNode();
            msgs.addObject().put("role", "user").put("content",
                    libKnown
                    + "Decompose this research question into 3-8 SELF-CONTAINED sub-questions that "
                    + "could each be researched independently by someone who sees nothing else. "
                    + "Cover every facet; where the question asks the same facts about many items, "
                    + "group items into a few sub-questions rather than one each. When the question "
                    + "names languages or regions, include language-specific sub-questions whose "
                    + "queries should be written in that language. Answer with a "
                    + "JSON array of strings and nothing else.\n\nQUESTION:\n" + question);
            String raw = drive.classify(msgs, 1200);
            var arr = json.readTree(raw.substring(raw.indexOf('['), raw.lastIndexOf(']') + 1));
            for (var q : arr) if (q.isTextual() && !q.asText().isBlank()) open.add(q.asText());
        } catch (Exception e) {
            System.err.println("fan: decompose unparseable — degrading to single sub-question");
        }
        if (open.isEmpty()) open.add(question);
        System.err.println("fan: " + open.size() + " sub-questions, " + workers + " workers");

        var findings = new java.util.ArrayList<String>();
        for (int round = 1; round <= maxRounds && !open.isEmpty(); round++) {
            // 2. Parallel workers — fresh depth-loop per sub-question. The pool caps concurrency;
            //    a worker that dies contributes an honest "unavailable" line, not silence.
            var pool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(workers, open.size()));
            var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (String sub : open) {
                futures.add(pool.submit(() -> {
                    try {
                        var r = research(sub + "\n\nEnd your answer with the URLs of the sources "
                                + "you actually used, one per line.", "depth", baseUrl, workerTurns);
                        String sum = r.summary() == null ? "" : r.summary();
                        return "SUB-QUESTION: " + sub + "\nFINDINGS:\n"
                                + (sum.length() > 3500 ? sum.substring(0, 3500) + " …[truncated]" : sum);
                    } catch (Exception e) {
                        return "SUB-QUESTION: " + sub + "\nFINDINGS: unavailable (worker failed: "
                                + e.getMessage() + ")";
                    }
                }));
            }
            pool.shutdown();
            for (var f : futures) {
                try { findings.add(f.get(30, java.util.concurrent.TimeUnit.MINUTES)); }
                catch (Exception e) { findings.add("SUB-QUESTION: (timed out)\nFINDINGS: unavailable"); }
            }
            open.clear();
            if (round == maxRounds) break;

            // 3. The critic — no tools, and ITS verdict decides another round, not the workers'.
            try {
                var msgs = json.createArrayNode();
                msgs.addObject().put("role", "user").put("content",
                        "You are reviewing research coverage, not writing the answer.\n\nQUESTION:\n"
                        + question + "\n\nFINDINGS SO FAR:\n" + String.join("\n\n", findings)
                        + "\n\nIs this enough to answer the question COMPLETELY? Answer with JSON "
                        + "only: {\"sufficient\": true} or {\"sufficient\": false, \"missing\": "
                        + "[\"<sub-question>\", ...]} (at most 4, each self-contained).");
                String raw = drive.classify(msgs, 800);
                var v = json.readTree(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1));
                if (!v.path("sufficient").asBoolean(true)) {
                    for (var q : v.path("missing")) if (q.isTextual()) open.add(q.asText());
                    System.err.println("fan: critic wants " + open.size() + " more (round " + (round + 1) + ")");
                }
            } catch (Exception e) {
                System.err.println("fan: critic unparseable — stopping rounds");
            }
        }

        // 4. Synthesis — the normal research loop, seeded with the findings as its notes. Tools
        //    stay available for verifying a doubtful cell, but the work is assembly.
        // TOKEN-AWARE notes budget (caught live 2026-09-02): seven workers' "3,500-char" notes
        // in Japanese and Chinese are ~1 token per CHARACTER, so the synthesis context hit 101%
        // of a 32k window, the per-turn output budget collapsed to 512 tokens, add_to_answer's
        // arguments truncated ("nothing to save") and the deadline task_done recorded "(done)".
        // A char cap is not a budget; this one counts tokens by script and fits ~35% of the
        // window, so the synthesis has room to READ and to WRITE.
        String notes = fitNotes(findings, drive.contextWindow());
        String synthGoal = question
                + "\n\n" + libKnown
                + "RESEARCH NOTES already gathered by parallel sub-investigations (treat as "
                + "your own notes; verify only what looks doubtful, then ASSEMBLE the complete "
                + "answer). Write the answer with add_to_answer in PIECES of at most 1500 characters "
                + "each — one section per call — then call task_done with only the sources and "
                + "caveats (a single huge save is cut off and lost):\n\n" + notes;
        FamiliarLoop.Result res = research(synthGoal, "broad", baseUrl, Math.min(maxTurns, 15));
        return res;
    }

    /** Rough token count by script: CJK ≈ 1 token per char, everything else ≈ 4 chars per token. */
    static int estTokens(String s) {
        if (s == null) return 0;
        int cjk = 0, other = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x3040 && c <= 0x30ff) || (c >= 0x4e00 && c <= 0x9fff) || (c >= 0xac00 && c <= 0xd7af)) cjk++;
            else other++;
        }
        return cjk + other / 4;
    }

    /** Trim a string to ~maxTokens by the same estimate, cutting at a line break when possible. */
    static String trimTokens(String s, int maxTokens) {
        if (estTokens(s) <= maxTokens) return s;
        int lo = 0, hi = s.length();
        while (lo < hi) {            // binary search the longest prefix within budget
            int mid = (lo + hi + 1) / 2;
            if (estTokens(s.substring(0, mid)) <= maxTokens) lo = mid; else hi = mid - 1;
        }
        int nl = s.lastIndexOf('\n', lo);
        return s.substring(0, nl > lo / 2 ? nl : lo) + " …[trimmed to fit the context]";
    }

    /** Fit worker findings into ~35% of the context window, each capped, the total capped. */
    static String fitNotes(java.util.List<String> findings, int ctxTokens) {
        int total = Math.max(2000, (int) (ctxTokens * 0.28));   // 0.35 hit 93% of the window live
        int each = Math.max(400, total / Math.max(1, findings.size()));
        var sb = new StringBuilder();
        int used = 0;
        for (String f : findings) {
            String t = trimTokens(f, each);
            int n = estTokens(t);
            if (used + n > total) t = trimTokens(t, Math.max(200, total - used));
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(t);
            used += estTokens(t);
        }
        return sb.toString();
    }

    public static FamiliarLoop.Result research(String question, String mode, String baseUrl, int maxTurns) {
        boolean broad = !"depth".equalsIgnoreCase(mode);
        String shape = broad
                ? "BROAD survey: run SEVERAL DIFFERENT web_search queries covering the different facets and "
                  + "phrasings of the question. Skim MANY sources (web_fetch the most promising few). Map the "
                  + "LANDSCAPE: the main options/positions, how they compare, where sources agree and disagree."
                  // ENTITY-SWEEP STRATEGY (WideSearch ws_en_064: batching 7 states into one query returned
                  // nothing useful 6 times in a row, then the run gave up — with a Wikipedia list page holding
                  // the entire gold table one search away). Positive strategy, stated up front.
                  + "\n\nWhen the question asks for the SAME facts about MANY items (states, companies, "
                  + "countries, products), first look for ONE page that already lists them all together — "
                  + "search for \"list of <things>\" or \"comparison of <things>\" (Wikipedia list pages often "
                  + "hold the entire table). Only if no list page exists, work through the items ONE PER "
                  + "web_search query — a query naming several items at once matches nothing useful. "
                  // Give-up rule per cell (regression ws_en_028: without it, the sweep advice sent the run
                  // hunting cells whose true answer is "not published" for all 40 turns — the question's own
                  // mark-as-unavailable instruction must outrank completionism).
                  + "Budget your attempts: after TWO failed tries on one fact, record it exactly as the "
                  + "question says to mark unavailable data (nan / NA / -) and MOVE ON — a complete answer "
                  + "with some cells honestly marked unavailable beats an unfinished hunt for one cell."
                : "DEPTH investigation: start with one or two precise web_search queries, then web_fetch and read "
                  + "the BEST sources THOROUGHLY (follow onward links/sources they cite when it matters). Answer "
                  + "the specific question with concrete, verified detail rather than breadth.";
        // DATA-API GROUNDING (conditional — the hint costs context, so only when the question is the kind
        // these sources answer). WideSearch financial/statistical family scored 0.00 across the board: the
        // data lives in stats databases and behind IR/SEC pages that 403 a plain fetcher, and the model only
        // knows search-then-fetch-pages. Tell it the direct data doors exist.
        String dataDoors = dataApiHint(question);
        // BIG-DELIVERABLE ASSEMBLY (measured, ws_en_013: the model fetched every source, had the data, and
        // could not emit a 109-row table in one completion — two prose attempts produced nothing and then a
        // description). For table-shaped questions, teach the accumulate-then-finish pattern up front.
        String assemble = question.toLowerCase().matches("(?s).*(table|list all|complete list|each of|every).*")
                ? "\n\nSAVE AS YOU GO: after reading each source, immediately call add_to_answer with the "
                  + "rows/facts you extracted from it (table header first, then rows). Saved content is "
                  + "automatically part of your final answer — never re-type it; your task_done summary then "
                  + "only needs sources and caveats."
                : "";
        // RESEARCH MEMORY POOL: seed the run with what earlier runs already established, so research is
        // CUMULATIVE (skip settled ground, push the frontier) instead of restarting from zero each time.
        String known = ResearchMemory.promptBlock(question);
        if (!known.isEmpty()) System.out.println("research memory: recalled prior findings");
        String goal = "RESEARCH (read-only; you have web_search and web_fetch).\n\nQUESTION: " + question
                + "\n\n" + known + shape + dataDoors + assemble
                + "\n\nRules: base every claim on a source you actually FETCHED — do not answer from memory. "
                // The steering the chat register and fan decompose already carry, missing HERE
                // until 2026-09-01: an e2e about JAPANESE models ran five English-only queries.
                + "When the question names languages or regions, write SOME of your web_search queries IN "
                + "those languages — English queries surface the English literature only. "
                + "Note when sources conflict or when something is uncertain. Finish by calling task_done with a "
                + "written answer that (a) answers the question directly up front, (b) gives the supporting "
                + "detail, and (c) ends with a SOURCES list of the URLs you actually used.";
        System.out.println("research (" + (broad ? "broad" : "depth") + "): " + question);
        System.out.println("search backend: " + WebSearchTool.endpoint());
        var drive = new DriveClient(baseUrl, MODEL);
        Path cwd = Path.of(System.getProperty("user.dir"));
        var draft = new AnswerDraftTool();
        var tools = ToolRegistry.research(cwd, question, draft);
        // The search controller: the steerer rides on web_search; its exhausted() is the loop's
        // early-finish signal. Stats are printed per run so over-search is a number, not a feeling.
        var ws = (org.codezaiku.tools.WebSearchTool) tools.find("web_search");
        FamiliarLoop.Result res = new FamiliarLoop(drive, tools, cwd, goal, maxTurns, null, null)
                .research().answerDraft(draft::draft)
                .finishEarlyIf(() -> ws != null && ws.steer().exhausted())
                .run();
        if (ws != null) {
            System.out.println("search controller: " + ws.steer().queries() + " queries, "
                    + ws.steer().queriesAfterSaturation() + " after saturation"
                    + (ws.steer().exhausted() ? " — finished on exhaustion" : ""));
        }
        // Harvest the answer into the pool so the NEXT run starts from here (dedup handled on write).
        int stored = ResearchMemory.harvest(question, res.summary());
        if (stored > 0) System.out.println("research memory: stored " + stored + " new finding(s)");
        return res;
    }

    /**
     * A short note naming the DIRECT data doors for question families where page-search fails (measured on
     * WideSearch: SEC/IR pages 403 a plain fetcher; country statistics live in databases search never
     * surfaces). Empty for questions outside those families — the hint costs context. Example codes are
     * examples, not a catalog: the note teaches the PATTERN (find the code/slug, then hit the API).
     */
    static String dataApiHint(String question) {
        String q = question.toLowerCase();
        boolean statistical = q.matches("(?s).*\\b(gdp|life expectancy|population|unemployment|inflation|"
                + "per capita|expenditure|emissions|mortality|literacy|enrollment|poverty)\\b.*")
                && q.matches("(?s).*\\b(20[0-2][0-9]|19[89][0-9])\\b.*");
        boolean financial = q.matches("(?s).*\\b(revenue|operating profit|net income|net profit|free cash flow|"
                + "fcf|market cap|gmv|earnings|quarterly report|financial report|annual report|10-k|10-q)\\b.*");
        // SECURITY questions are the WORST case for a local model answering from memory: a training cutoff
        // means its CVE knowledge is stale by construction, and "no known vulnerability" from a stale model
        // is a dangerous answer, not a neutral one. Point it at the authoritative live feeds instead.
        boolean security = q.matches("(?s).*\\b(cve-\\d{4}-\\d+|cve|vulnerabilit(y|ies)|exploit(ed|able)?|"
                + "advisory|advisories|security patch|patched version|end.of.life|eol|unsupported version|"
                + "known.exploited|kev|cvss|security update)\\b.*");
        StringBuilder sb = new StringBuilder();
        if (statistical)
            // Concrete-first: a 9B copies a complete literal URL faithfully but garbles <PLACEHOLDER>
            // templates (measured: given the template, it fetched the data.worldbank.org WEBSITE — a JS
            // shell — instead of the API, got nav chrome, and gave up).
            sb.append("\n\nCountry statistics (GDP, life expectancy, health expenditure, population…) are "
                    + "served as data by the World Bank API. Example — life expectancy for Finland 2020-2023, "
                    + "fetch EXACTLY this URL with web_fetch:\n"
                    + "https://api.worldbank.org/v2/country/FI/indicator/SP.DYN.LE00.IN?format=json&date=2020:2023\n"
                    + "Repeat it swapping the 2-letter country code (FI) and the indicator code, one country "
                    + "per fetch. Indicator codes: SP.DYN.LE00.IN = life expectancy, SH.XPD.CHEX.PC.CD = "
                    + "health expenditure per capita USD; find others by searching \"world bank indicator "
                    + "code <metric>\". Use api.worldbank.org exactly as shown — the data.worldbank.org "
                    + "website returns no data to a fetcher.");
        if (security)
            // Complete literal URLs, per the measured lesson that a small model copies literals faithfully
            // and garbles <PLACEHOLDER> templates. All four verified reachable and machine-readable.
            sb.append("\n\nYOUR OWN SECURITY KNOWLEDGE IS OUT OF DATE — you have a training cutoff and CVEs are "
                    + "published daily. Never answer a vulnerability question from memory; fetch the live "
                    + "feeds. Saying \"no known vulnerability\" from stale memory is a WRONG answer, not a safe "
                    + "one. Authoritative sources, all fetchable with web_fetch:\n"
                    + "- Is it actively exploited? CISA KEV catalog (updated continuously):\n"
                    + "  https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json\n"
                    + "- CVE details/severity by keyword — NVD (swap the keyword):\n"
                    + "  https://services.nvd.nist.gov/rest/json/cves/2.0?keywordSearch=redis%20authentication&resultsPerPage=5\n"
                    + "- CVEs for one CVE id — NVD:\n"
                    + "  https://services.nvd.nist.gov/rest/json/cves/2.0?cveId=CVE-2024-3094\n"
                    + "- Vulnerabilities for a specific package+version — OSV (POST not available to you; use the "
                    + "browsable form, swapping name/ecosystem):\n"
                    + "  https://api.osv.dev/v1/vulns/GHSA-xxxx  (find ids via NVD or the package advisory page)\n"
                    + "- Is a version still supported / when does it EOL? endoflife.date (swap the product):\n"
                    + "  https://endoflife.date/api/postgresql.json\n"
                    + "Report the CVE id, CVSS score, whether KEV lists it as exploited, and the fixed version.");
        if (financial)
            sb.append("\n\nCompany financial-statement pages (SEC EDGAR, corporate investor relations) usually "
                    + "BLOCK this fetcher — do not keep retrying them. Financial history (revenue, profit, FCF, "
                    + "store counts) is readable on aggregator pages instead: search \"<company> revenue "
                    + "macrotrends\" or \"<company> <metric> stockanalysis\" and fetch those results. Wikipedia "
                    + "company pages also carry annual key figures.");
        return sb.toString();
    }

    /**
     * SECURITY surface: static posture + runtime detections, and for a detected intrusion a bounded
     * read-only response proposing ONE surgical containment action. Reports; never acts — containment is
     * destructive and earns the right to run only behind a measured false-positive rate.
     */
    public static String secure(String target, String subject, String baseUrl) {
        var exec = Exec.forTarget(target);
        StringBuilder rep = new StringBuilder("security review on " + exec.describe() + "\n");
        for (var f : new SecurityScan(exec).scan())
            rep.append("[POSTURE] ").append(f.line()).append('\n');

        var sensor = new SecurityAlerts(exec);
        if (!sensor.available()) {
            rep.append("[INTRUSION] no runtime detection stream — nothing is watching; absence of "
                    + "alerts is NOT evidence of absence of intrusion. Install falco (linux, "
                    + "syscall-level) or wazuh (cross-platform, log-level)\n");
            return rep.toString();
        }
        var dets = sensor.recent(60);
        if (!subject.isBlank())
            dets = dets.stream().filter(d -> d.subject().contains(subject)).toList();
        for (var d : dets) rep.append("[INTRUSION] ").append(d.line()).append('\n');
        if (dets.isEmpty()) {
            // Name the sensor: "none detected" means different things depending on what was
            // watching, and a Wazuh-quiet host is a weaker statement than a Falco-quiet one.
            rep.append("[INTRUSION] none in the last 60m — sensor: ").append(sensor.coverage()).append('\n');
            return rep.toString();
        }
        String target1 = subject.isBlank() ? dets.get(0).subject() : subject;
        var p = new SecurityResponse(new DriveClient(baseUrl, MODEL), exec)
                .respond(target1, dets);
        rep.append("[RESPONSE] ").append(p.line()).append("\n[RESPONSE] NOT APPLIED — security "
                + "containment is proposed for a human; a wrong containment is a self-inflicted outage\n");
        return rep.toString();
    }

    /** Read-only INVESTIGATION / RCA: run the SRE operator capped at LOCALIZE (diagnose, never act) and return
     *  the localized root cause. The safe "just tell me what's wrong" entry point (no remediation). */
    public static String investigate(String target, String incident, String baseUrl) {
        var sc = OpsDiscovery.parseScope(target);
        OpsOutcome out = ops(sc.target(), incident, baseUrl, 25, null, sc.project(), "localize");
        return "investigation (diagnosis-only): " + out.line();
    }

    /** Proactive whole-machine WATCH: loop triage every {@code intervalSec} — fix issues as they arise. Runs
     *  until interrupted (a daemon; run under systemd for a real box). */
    public static void watchMachine(String target, int intervalSec, String baseUrl, String ceiling) {
        System.out.println("watch-machine: " + target + " every " + intervalSec + "s (ceiling=" + ceiling + ")");
        while (true) {
            try { triage(target, baseUrl, 20, ceiling); }
            catch (Exception e) { System.err.println("watch cycle error: " + e); }
            try { Thread.sleep(intervalSec * 1000L); } catch (InterruptedException e) { return; }
        }
    }

    private static Gate gateByName(String name) {
        if (name == null || name.isBlank() || name.equals("none")) return null;
        try {
            return GateLoader.gate(name); // loads /gates/<name>.json + selects the archetype engine
        } catch (RuntimeException e) {
            System.err.println("gate load failed for '" + name + "': " + e.getMessage());
            return null;
        }
    }

    /** Prove: /props n_ctx is readable, and tool_choice=required yields a tool call (not prose). */
    private static void smoke(String baseUrl) {
        var drive = new DriveClient(baseUrl, MODEL);
        int nctx = drive.contextWindow();
        System.out.println("n_ctx = " + nctx + "  → max_tokens budget = " + (nctx / 2));

        ObjectMapper j = drive.json();
        ArrayNode tools = j.createArrayNode();
        ObjectNode tool = tools.addObject();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", "write_file");
        fn.put("description", "Write text content to a file at the given path.");
        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("path").put("type", "string");
        props.putObject("content").put("type", "string");
        params.putArray("required").add("path").add("content");

        ArrayNode messages = j.createArrayNode();
        messages.addObject().put("role", "system")
                .put("content", "You build software by calling tools. Act, do not narrate.");
        messages.addObject().put("role", "user")
                .put("content", "Create a file hello.txt containing exactly: hello from the drive");

        ObjectNode assistant = drive.chat(messages, tools, nctx / 2);
        var toolCalls = assistant.path("tool_calls");
        System.out.println("--- assistant message ---");
        System.out.println(assistant.toPrettyString());
        if (toolCalls.isArray() && !toolCalls.isEmpty()) {
            System.out.println("\nSMOKE PASS: model emitted tool call '"
                    + toolCalls.get(0).path("function").path("name").asText() + "'");
        } else {
            System.out.println("\nSMOKE FAIL: no tool_calls — tool_choice=required not honored?");
            System.exit(1);
        }
    }
}
