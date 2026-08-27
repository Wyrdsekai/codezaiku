package org.codezaiku.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.codezaiku.Config;
import org.codezaiku.FamiliarMain;
import org.codezaiku.verify.ProjectTests;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The {@code run} verb: one coding task, one machine-readable result, for a host driving CodeZaiku as
 * a subprocess.
 *
 * <p>The shape is set by the calling contract rather than by our other verbs:
 *
 * <pre>
 *   codezaiku run --text &lt;TASK&gt; --output-format json --no-session -q [--provider p] [--model m]
 * </pre>
 *
 * <p>Three properties the caller depends on, each of which needed deliberate work:
 *
 * <ul>
 *   <li><b>stdout carries exactly one JSON document.</b> The coding path narrates to stdout
 *       (task mode, conventions, library size). Under {@code --output-format json} stdout is swapped
 *       to stderr for the duration of the run, so narration stays visible to a human tailing stderr
 *       without corrupting the document a parser is reading.
 *   <li><b>A kill still reports.</b> The caller hard-kills at its own wallclock limit. A run that dies
 *       having written five files must not report nothing, so a shutdown hook emits the same document
 *       with whatever the ledger holds, and kills descendant processes so nothing is orphaned.
 *   <li><b>{@code files} does not silently under-report.</b> The write ledger is exact for tool
 *       writes; the shell is unscoped. Where the workspace is a git repo the two are reconciled (see
 *       {@link WorkspaceFiles}) and {@code filesComplete} says whether the result is authoritative.
 * </ul>
 */
public final class RunVerb {

    private static final ObjectMapper J = new ObjectMapper();

    /** Emitted once, by whichever of the normal path and the shutdown hook gets there first. */
    private static final AtomicBoolean EMITTED = new AtomicBoolean(false);

    public static void main(String[] args) {
        Args a;
        try {
            a = Args.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("codezaiku run: " + e.getMessage());
            System.exit(2);
            return;
        }

        Path workspace = a.workspace.toAbsolutePath().normalize();
        // The real stdout, captured before any redirection, so the document always lands on fd 1.
        PrintStream stdout = System.out;

        // The caller kills at its wallclock limit. Report what happened rather than dying silently,
        // and take child processes down with us — an orphaned build daemon outlives the run otherwise.
        //
        // Registered BEFORE the git baseline below, because that baseline shells out twice and a kill
        // arriving during it would otherwise find no hook installed and emit nothing at all. Measured:
        // a SIGTERM ~3s in produced zero bytes on stdout, which a parser reads as a crashed backend.
        var state = new State(a, workspace, stdout);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            killDescendants();
            state.emitIfFirst("failed", null, true);
        }, "codezaiku-run-shutdown"));

        // Baseline for the file delta, so pre-existing uncommitted work is never attributed to us.
        state.baseline();

        if (a.json) {
            // Narration must not share the channel with the document. Sending it to stderr keeps it
            // for a human without a parser ever seeing it.
            System.setOut(a.quiet ? new PrintStream(PrintStream.nullOutputStream(), true, StandardCharsets.UTF_8)
                                  : new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        }

        int exit;
        try {
            FamiliarMain.Tracked t = FamiliarMain.runLoopTracked(
                    workspace, a.text, a.drive, a.maxTurns, "none", a.mode, a.model);
            state.tracked = t;
            // Ask the project's own test suite what happened. Measured AFTER the loop, so it describes
            // the FINAL on-disk state — the loop's keep-best-green may restore an earlier snapshot at
            // exit, and the document has to describe the workspace the caller is about to sync, not an
            // intermediate one.
            state.verdict = ProjectTests.verdict(workspace);
            String status = ResultDocument.statusForRun(t.result().done(), state.verdict, t.result().summary());
            // Derived from the status so the exit code and the document can never contradict.
            exit = ResultDocument.exitFor(status);
            state.emitIfFirst(status, t, false);
        } catch (RuntimeException | Error e) {
            // A crash must not look like a clean "nothing happened" — report it as a failure, with
            // whatever the run had already written still listed.
            state.error = e.toString();
            state.emitIfFirst("failed", state.tracked, false);
            exit = 1;
        }
        System.exit(exit);
    }

    /** Everything the document needs, reachable from both the normal path and the shutdown hook. */
    static final class State {
        final Args a;
        final Path workspace;
        final PrintStream out;
        /** Null until {@link #baseline()} runs, so a kill during startup degrades to "no git delta". */
        volatile WorkspaceFiles.Snapshot before;
        volatile FamiliarMain.Tracked tracked;
        volatile String error;
        volatile ProjectTests.Verdict verdict;

        State(Args a, Path workspace, PrintStream out) {
            this.a = a;
            this.workspace = workspace;
            this.out = out;
        }

        /** Take the pre-run git reading. Separate from construction so the shutdown hook lands first. */
        void baseline() {
            this.before = WorkspaceFiles.snapshot(workspace);
        }

        void emitIfFirst(String status, FamiliarMain.Tracked t, boolean interrupted) {
            if (!EMITTED.compareAndSet(false, true)) return;
            ObjectNode d = document(status, t, interrupted);
            try {
                out.println(J.writerWithDefaultPrettyPrinter().writeValueAsString(d));
                out.flush();
            } catch (Exception e) {
                out.println("{\"status\":\"failed\",\"error\":\"could not serialize result\"}");
                out.flush();
            }
        }

        /** Build the result document. Separate from emitting so the contract can be asserted directly. */
        ObjectNode document(String status, FamiliarMain.Tracked t, boolean interrupted) {
            if (t == null) t = tracked;
            List<String> ledger = t == null ? List.of() : t.files();
            boolean ledgerComplete = t != null && t.filesComplete();

            // Reconcile with git where we can. The ledger misses shell writes; the git delta misses
            // nothing but is only available in a repo — and not at all if we died before the baseline.
            var base = before;
            List<String> fromGit = base == null ? List.of() : WorkspaceFiles.changedSince(workspace, base);
            boolean gitAvailable = base != null && base.available();
            List<String> files = WorkspaceFiles.merge(ledger, fromGit);

            // Built through ResultDocument, which the ACP surface also uses, so the two cannot drift.
            return ResultDocument.of(workspace, status)
                    .taskId(a.taskId)
                    .files(files)
                    .filesExcluded(WorkspaceFiles.lastExcludedCount())
                .fileProvenance(ledgerComplete, gitAvailable)
                    .turns(t == null ? null : t.result().turns())
                    .summary(t == null ? null : t.result().summary())
                    .interrupted(interrupted)
                    .error(error)
                    .model(a.model == null ? Config.get("CODEZAIKU_MODEL", "local-model") : a.model)
                    .provider(a.provider)
                    .verdict(verdict)
                    .build();
        }
    }

    /**
     * Kill anything this process started, so a hard kill leaves no build or test daemon behind.
     *
     * <p>The descendant set is snapshotted ONCE and both passes act on that same list. Re-enumerating
     * after the polite pass loses processes rather than killing them: destroying an intermediate shell
     * reparents its own children to init, which takes them out of our descendant tree while they are
     * still running. Measured — a `sleep 240` under `bash -lc` survived SIGTERM until this was fixed.
     */
    private static void killDescendants() {
        try {
            List<ProcessHandle> kids = ProcessHandle.current().descendants().toList();
            kids.forEach(ProcessHandle::destroy);
            Thread.sleep(200);                              // a moment to go down politely
            for (ProcessHandle h : kids) {
                if (h.isAlive()) h.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            // Shutdown is best-effort; never throw out of a hook.
        }
    }

    /** The parsed command line. Unknown flags are tolerated so the caller can pass extras safely. */
    static final class Args {
        String text;
        String taskId;
        String provider;
        String model;
        String mode;
        String drive = Config.get("CODEZAIKU_DRIVE", "http://localhost:8200");
        Path workspace = Path.of(".");
        int maxTurns = Config.getInt("CODEZAIKU_RUN_MAX_TURNS", 40);
        boolean json;
        boolean quiet;

        static Args parse(String[] argv) {
            Args a = new Args();
            for (int i = 0; i < argv.length; i++) {
                switch (argv[i]) {
                    case "--text", "-t" -> a.text = need(argv, ++i, "--text");
                    case "--task-id" -> a.taskId = need(argv, ++i, "--task-id");
                    case "--provider" -> a.provider = need(argv, ++i, "--provider");
                    case "--model" -> a.model = need(argv, ++i, "--model");
                    case "--mode" -> a.mode = need(argv, ++i, "--mode");
                    case "--drive" -> a.drive = need(argv, ++i, "--drive");
                    case "--workspace", "-C" -> a.workspace = Path.of(need(argv, ++i, "--workspace"));
                    case "--max-turns" -> a.maxTurns = Integer.parseInt(need(argv, ++i, "--max-turns"));
                    case "--output-format" -> a.json = "json".equalsIgnoreCase(need(argv, ++i, "--output-format"));
                    case "-q", "--quiet" -> a.quiet = true;
                    // Accepted and ignored: we hold no session state, so there is nothing to disable.
                    case "--no-session" -> { }
                    default -> {
                        if (a.text == null && !argv[i].startsWith("-")) a.text = argv[i];
                        // Anything else is an extra flag from a newer caller; ignoring it is kinder
                        // than failing a run over a flag that does not change what we do.
                    }
                }
            }
            if (a.text == null || a.text.isBlank()) {
                throw new IllegalArgumentException("--text <TASK> is required");
            }
            return a;
        }

        private static String need(String[] argv, int i, String flag) {
            if (i >= argv.length) throw new IllegalArgumentException(flag + " needs a value");
            return argv[i];
        }
    }

    private RunVerb() { }
}
