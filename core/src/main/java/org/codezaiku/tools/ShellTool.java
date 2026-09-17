package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.codezaiku.Config;
import org.codezaiku.exec.Shell;

/**
 * Run a shell command in the project root. Network is ON (RESET §3.4 — let build tools resolve
 * deps live; no offline mode). stderr is merged into stdout; the whole output is returned if it
 * fits, else head+tail are kept so the actual failure marker survives (never a blind tail).
 */
public final class ShellTool implements Tool {
    private static final int CAP = 20_000;
    private static final long TIMEOUT_SEC = org.codezaiku.Config.getInt("CODEZAIKU_SHELL_TIMEOUT_SEC", 300);
    // A genuinely-long ML step (training / full-dataset generation) legitimately needs minutes, not 300s
    // (CodeML/MLE-bench: 20min–hours). A 300s cap on a ~8min train+generate caused a retrain-every-edit death
    // spiral (fine-tune sv9: 29 timeouts/rewrites, never completed). Heavy commands get a generous cap so a real
    // run completes in one go; quick `python -c` checks keep the short default.
    private static final long HEAVY_TIMEOUT_SEC =
            org.codezaiku.Config.getInt("CODEZAIKU_SHELL_HEAVY_TIMEOUT_SEC", 1200);
    private final PathScope scope;

    // The model's FIXTURE builds need JDK21; the box default `java` is JDK25 (the CodeZaiku core harness is
    // itself compiled to class-file v69 and RUNS on 25, so we cannot lower system java). gradle 8 / Spring
    // Boot 3.x reject JDK25 ("Unsupported class file major version 69"), and the model's self-generated
    // ./gradlew wrapper picks up system java unless JAVA_HOME is set — so library-api/email-intel-java builds
    // failed every attempt and the run burned out (battery46). provision.sh's shim only covered BARE `gradle`,
    // never ./gradlew. Fix: scope JDK21 to the MODEL's shell here (set JAVA_HOME + prepend its bin for every
    // command the model runs) WITHOUT touching the harness JVM, which stays on 25. Discover the mise JDK21
    // install provision.sh creates; allow an explicit override; null → no change (current behavior).
    private static final String FIXTURE_JAVA_HOME = discoverFixtureJavaHome();

    private static String discoverFixtureJavaHome() {
        String override = Config.get("CODEZAIKU_FIXTURE_JAVA_HOME");
        if (override != null && !override.isBlank()
                && Files.isDirectory(Path.of(override))) {
            return override;
        }
        try {
            Path base = Path.of(
                    System.getProperty("user.home"), ".local/share/mise/installs/java");
            if (!Files.isDirectory(base)) return null;
            try (var s = Files.list(base)) {
                return s.filter(p -> p.getFileName().toString().startsWith("21"))
                        .filter(Files::isDirectory)
                        .map(Path::toString)
                        .sorted().reduce((a, b) -> b)   // highest 21.x
                        .orElse(null);
            }
        } catch (Exception e) {
            return null;
        }
    }

    // The mise shims dir — put it AHEAD of /usr/bin so the model uses the SELF-PROVISIONED toolchain, not
    // stale distro versions. battery59: the model's shell resolved `gradle` to /usr/bin/gradle 4.4.1 (2017,
    // rejects the Spring Boot plugin) instead of mise's gradle 8.14.4 → every Spring Boot Java app failed to
    // build. mise installs the right versions but they only win if its shims lead PATH. General — covers any
    // mise-managed tool (gradle/node/cargo/...) a system copy would otherwise shadow.
    private static final String MISE_SHIMS = discoverMiseShims();

    private static String discoverMiseShims() {
        String override = Config.get("CODEZAIKU_MISE_SHIMS");
        if (override != null && !override.isBlank()
                && Files.isDirectory(Path.of(override))) {
            return override;
        }
        Path shims = Path.of(
                System.getProperty("user.home"), ".local/share/mise/shims");
        return Files.isDirectory(shims) ? shims.toString() : null;
    }

    private final boolean readOnly;

    public ShellTool(PathScope scope) { this(scope, false); }

    /** Read-only mode (review / investigate): commands that would modify files are refused before running. */
    public ShellTool(PathScope scope, boolean readOnly) {
        this.scope = scope;
        this.readOnly = readOnly;
    }

    /**
     * Commands that write git STATE, as opposed to reading it. Separate from {@link #RO_WRITE}, which
     * is about touching the working tree at all: a host may be perfectly happy for the agent to edit
     * files while reserving commits, branches and history for a human. Read-only git (`status`, `log`,
     * `diff`, `show`) is deliberately absent — the file ledger depends on those.
     */
    // A flag may carry a VALUE (`git -C /path commit`, `git -c user.name=x commit`), so the
    // pre-verb run allows flag+value pairs, not just bare flags. Without that, `git -C <path> commit`
    // read as ungated — a false negative, and the direction that actually matters here.
    private static final Pattern GIT_WRITE = Pattern.compile(
            "\\bgit\\b(?:\\s+-\\S+(?:\\s+[^-\\s]\\S*)?)*\\s+"
            + "(?:commit|add|rm|mv|checkout|switch|restore|reset|revert|merge|rebase|cherry-pick|"
            + "stash|push|pull|fetch|clean|tag|branch|apply|am|worktree|remote|config|init|clone)\\b",
            Pattern.CASE_INSENSITIVE);

    /** True if {@code cmd} would modify git state (history, index, refs or remotes). */
    public static boolean isGitWrite(String cmd) {
        return cmd != null && GIT_WRITE.matcher(cmd).find();
    }

    /**
     * True if {@code cmd} only READS — the same judgement read-only mode enforces at
     * {@link #execute}, exposed so a consent layer can decide whether to interrupt for it.
     *
     * <p>One pattern, two callers, on purpose. A separate list of "safe commands" in the chat layer
     * would drift from what the tool actually refuses, and the direction it drifts in is the
     * dangerous one: asking about something harmless is an annoyance, while staying silent about
     * something this tool would have blocked is a hole.
     *
     * <p>A blank or absent command is NOT read-only — an unknown shape is treated as mutating.
     */
    public static boolean isReadOnly(String cmd) {
        return cmd != null && !cmd.isBlank() && !RO_WRITE.matcher(cmd).find();
    }

    // Commands that MODIFY files — refused in read-only mode (a review/investigate must not touch
    // the code). Allows redirects to /dev/null, /tmp/, and fd-dups (2>&1); blocks writes to project files.
    private static final Pattern RO_WRITE = Pattern.compile(
            "(?:^|[;&|(]\\s*)(?:rm|mv|cp|tee|touch|mkdir|rmdir|chmod|chown|dd|truncate|ln|patch)\\b"
            + "|\\b(?:sed|perl)\\b[^|]*\\s-i\\b"
            + "|>>?\\s*(?!/dev/null|/tmp/|&)[\\w./~-]"
            + "|\\bgit\\s+(?:commit|checkout|reset|add|apply|stash|rm|mv|push|restore|clean)\\b"
            + "|\\b(?:pip|pip3|npm|yarn|pnpm|apt|apt-get|cargo|go)\\s+(?:install|add|get|build|update|upgrade|remove)\\b",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "shell";
    }

    @Override
    public String description() {
        return "Run a bash command in the project root and return its exit code + combined output. "
                + "Use for builds, tests, and inspection.";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        p.putObject("properties").putObject("command").put("type", "string");
        p.putArray("required").add("command");
        return p;
    }

    // Long-running foreground servers never return — running them here would wedge the loop on
    // the shell timeout. To check a long-running program, run it under a timeout (e.g. `timeout 3 ...`).
    private static final String[] SERVER_STARTS = {
            "bootrun", "spring-boot:run", "gradle run", "gradlew run", "quarkusdev", "quarkus:dev",
            "npm start", "npm run dev", "npm run start", "yarn dev", "yarn start", "pnpm dev",
            "next dev", "vite", "uvicorn", "gunicorn", "flask run", "http.server",
            "manage.py runserver", "rails server", "rails s ", "php -s", "serve ",
            "node src/server", "node server.js", "node src/app", "node app.js", "node src/index", "node index.js"
    };

    @Override
    public String execute(JsonNode args) throws Exception {
        String cmd = args.path("command").asText();
        if (cmd.isBlank()) return "ERROR: empty command";
        if (readOnly && RO_WRITE.matcher(cmd).find())
            return "REFUSED (read-only): this command would modify files. This is a read-only pass — inspect with "
                    + "git diff / grep / cat / read_file only, and report findings instead of editing.";
        String lc = cmd.toLowerCase();
        // SELF/COLLATERAL-KILL protection (environment safety, like the server-start refusal below): the
        // harness IS a java process on a shared box. A model "cleaning up stuck daemons" with a broad
        // process-kill killed the harness JVM mid-run (battery19: ps aux → killall → log ends mid-turn);
        // it could equally hit other users' processes. Refuse interpreter-wide kills; point at the safe
        // tool-specific alternative. Targeted kills (a PID it started, `gradle --stop`) remain allowed.
        Matcher killer = Pattern
                .compile("(?:^|[|;&]\\s*)(?:killall|pkill)\\b[^|;&]*\\b(java|gradle|node|python\\d?|cargo|godot)\\b")
                .matcher(lc.strip());
        if (killer.find()) {
            return "REFUSED: a broad process-kill ('" + killer.group() + "') can kill this coding session "
                    + "itself and unrelated processes on this shared machine. To stop gradle daemons use "
                    + "`gradle --stop`; to stop a process you started, kill its specific PID. Builds and "
                    + "tests here are already time-bounded — stuck processes clean themselves up.";
        }
        // Only refuse an UNBOUNDED server start. If the model already wrapped it in `timeout` (the exact
        // safe pattern this very refusal recommends), allow it — refusing the timeout-wrapped form is a
        // self-contradiction that wedged the loop (a 9B re-emitting `timeout 10 gradle bootRun` 69× because
        // we kept refusing the thing we told it to do). The async-drain + kill-tree below makes it safe.
        boolean timeBounded = lc.contains("timeout ");
        // A package INSTALL names server packages as arguments (e.g. `pip install fastapi uvicorn`) but does NOT
        // run them — don't let the run-a-server guard false-match the package name and refuse the dep install
        // (observed: docs-rag's `pip install ... uvicorn` refused → the 9B couldn't set up deps → thrashed app.py).
        boolean isInstall = lc.contains("pip install") || lc.contains("pip3 install") || lc.contains("uv pip install")
                || lc.contains("uv add") || lc.contains("poetry add") || lc.contains("pipenv install")
                || lc.contains("conda install") || lc.contains("mamba install");
        if (!timeBounded && !isInstall) {
            for (String s : SERVER_STARTS) {
                if (lc.contains(s)) {
                    return "REFUSED: '" + s.strip() + "' starts a long-running server, which would block. "
                            + "Run it under a timeout instead to confirm it starts, e.g. `timeout 5 " + cmd.strip()
                            + "` (it will exit 124 on timeout, which means it started and stayed up). But to "
                            + "verify a WEB API's endpoints, prefer an in-process integration test, not curl.";
                }
            }
        }
        // CRASH-PREVENTION (2026-06-29): a DETACHED heavy step (nohup / trailing &) escapes this tool's timeout
        // AND its kill-on-cleanup → it runs uncapped. That exact pattern (`nohup python train.py &`) accumulated
        // and exhausted host RAM, livelocking the box. Refuse to detach a TRAINING/generation step — foreground
        // only, where the generous heavy timeout + kill-the-whole-tree-on-exit apply.
        String t = lc.strip();
        boolean detached = lc.contains("nohup ") || lc.contains("setsid ") || lc.contains("& disown")
                || (t.endsWith("&") && !t.endsWith("&&"));
        // Only refuse an UNBOUNDED detached run — that's the crash pattern (`nohup python train.py &` ran ~8h
        // uncapped). A TIMEOUT-WRAPPED detached step (`timeout 60 python main.py &`) is runtime-bounded, so it
        // can't run away — and backgrounding-under-timeout is exactly how you boot+test a long-running SERVICE
        // (a service main.py false-matches isHeavyStep; refusing its timeout-wrapped background wedged docs-rag).
        if (detached && isHeavyStep(lc) && !timeBounded) {
            return "REFUSED — run this training / full-dataset step in the FOREGROUND instead (drop the `nohup`/`&`), "
                    + "or wrap it in a `timeout` so it can't run away (`timeout 120 <cmd> &` is fine — it's bounded): "
                    + "heavy steps get a generous " + HEAVY_TIMEOUT_SEC + "s timeout and the harness kills the whole process "
                    + "tree when they end, so a foreground run is safe and bounded. A detached run escapes that timeout and "
                    + "cleanup, so it can grow uncapped and exhaust host memory (it crashed the box once). For fast feedback, "
                    + "validate on a SMALL subset first, and keep training (run once, save the model) separate from inference.";
        }
        // Pre-flight memory guard: if the host is already low on RAM (a prior run didn't free), refuse a NEW heavy
        // step rather than pile on and livelock the machine.
        if (isHeavyStep(lc)) {
            long availMb = hostAvailableMemMb();
            if (availMb >= 0 && availMb < 12000) {
                return "REFUSED — the host is low on memory (" + availMb + " MB available). Let the current run finish "
                        + "and free its memory (heavy runs are killed on timeout), then retry this step. Starting another "
                        + "training/generation run while RAM is this low risks exhausting it and crashing the machine.";
            }
        }
        // Container-exec mode (env-gated): route the command into a Docker container's workdir instead of
        // the host shell. Off by default — see ContainerExec. The drain/timeout logic below is unchanged.
        ProcessBuilder pb = ContainerExec.active()
                ? new ProcessBuilder("docker", "exec", "-w", ContainerExec.workdir(),
                        ContainerExec.cid(), "bash", "-lc", cmd).redirectErrorStream(true)
                : Shell.pb(cmd)
                        .directory(scope.root().toFile())
                        .redirectErrorStream(true);
        // NON-INTERACTIVE env (field consensus / hang-class prevention): a 9B's command must never block
        // on a TTY prompt — apt/pip "[Y/n]" confirmations, a pager (git log/diff piping to less), git asking
        // for credentials or an editor. In a headless shell those wait forever until our timeout kills them,
        // burning a turn and feeding the repeated-timeout/hang loop. Force every tool non-interactive.
        var env = pb.environment();
        nonInteractive(env);
        // Self-provisioned mise toolchain ahead of /usr/bin (e.g. gradle 8.14.4, not /usr/bin/gradle 4.4.1).
        // Prepend this FIRST so the JDK21 bin below lands ahead of it — `java`/`javac` → JDK21, while `gradle`
        // (absent from the JDK bin) falls through to the mise shim. `bash -l` re-sources the profile but does
        // not put the shims first reliably in a non-interactive shell, so we set it explicitly.
        if (MISE_SHIMS != null) {
            String path = env.get("PATH");
            if (path == null) path = System.getenv("PATH");
            env.put("PATH", MISE_SHIMS + ":" + (path == null ? "" : path));
        }
        // Pin the model's Java toolchain to JDK21 (see FIXTURE_JAVA_HOME) so ./gradlew + bare java/javac
        // resolve to 21, not the box's JDK25. `bash -l` re-sources the profile but does not touch JAVA_HOME
        // (it's unset there), so this survives; the bin-prepend keeps 21 ahead of /usr/bin for bare `java`.
        if (FIXTURE_JAVA_HOME != null) {
            env.put("JAVA_HOME", FIXTURE_JAVA_HOME);
            String path = env.get("PATH");
            if (path == null) path = System.getenv("PATH");
            env.put("PATH", FIXTURE_JAVA_HOME + "/bin:" + (path == null ? "" : path));
        }
        // The shell is unscoped, so a `sed -i` or `mv` here writes files the ledger never sees. Rather
        // than flag EVERY shell call (most only read, which would make the incompleteness signal noise),
        // reuse the same pattern the read-only pass uses to recognise a file-mutating command. A match
        // means the run's file list is a LOWER BOUND and the caller should reconcile against git.
        if (!readOnly && RO_WRITE.matcher(cmd).find()) scope.noteShellRan();

        Process proc = pb.start();
        // Drain stdout in a daemon thread — do NOT readAllBytes() on the main thread, or a process that
        // never exits and never closes stdout (e.g. `godot --headless`, a stray server) blocks the read
        // forever and the timeout below never even applies. (This actually happened: a headless godot ran
        // 4h and wedged the whole loop.) With async drain, the timeout is real.
        StringBuilder sink = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (var in = proc.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    synchronized (sink) {
                        sink.append(new String(buf, 0, n));
                        if (sink.length() > 200_000) sink.delete(0, sink.length() - 200_000);
                    }
                }
            } catch (Exception ignored) {
                // process ended / stream closed
            }
        }, "shell-drain");
        drain.setDaemon(true);
        drain.start();

        long timeoutSec = isHeavyStep(lc) ? HEAVY_TIMEOUT_SEC : TIMEOUT_SEC;
        boolean done = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!done) {
            proc.descendants().forEach(ProcessHandle::destroyForcibly); // kill children too (the godot case)
            proc.destroyForcibly();
            proc.waitFor(5, TimeUnit.SECONDS);
            drain.join(2000);
            synchronized (sink) {
                return "ERROR: command timed out after " + timeoutSec + "s and was killed. "
                        + (isHeavyStep(lc)
                            ? "This training / generation step legitimately takes time and already gets a generous "
                              + "timeout — run it in the FOREGROUND, attached (heavy steps keep the short-timeout cap off "
                              + "and are cleaned up automatically; `nohup`/`&` is refused for them). "
                              + "To make it FIT the budget: validate first on a SMALL subset (a few rows, 1 epoch), "
                              + "SEPARATE training (run ONCE, save the model/adapter to disk) from inference (load the "
                              + "saved adapter — do not retrain every run), and BATCH the generation over all inputs."
                            // NEVER advise `timeout N` here. This text is what a model acts on, and the
                            // old wording ("run it under `timeout N ...`") was followed literally: a host
                            // watched a command killed at the 300s cap come back as `timeout 120 <cmd>`
                            // the very next turn — the harness talked it into guaranteeing failure faster,
                            // then it wandered for the rest of the budget. Say what happened instead.
                            : "The command was still running when the harness's per-command cap of "
                              + timeoutSec + "s expired — this is OUR limit, not a failure of your command, "
                              + "and it may simply need longer. Do NOT re-run it wrapped in a shorter "
                              + "`timeout`; that only fails sooner. Either run a smaller/faster slice of the "
                              + "same work to prove it behaves, or tell the operator this step needs a longer "
                              + "budget (CODEZAIKU_SHELL_TIMEOUT_SEC) and move on to what you can do now.")
                        + "\n" + clamp(sink.toString());
            }
        }
        drain.join(2000);
        synchronized (sink) {
            // cwd state echo on every result (SWE-agent/OpenHands/little-coder consensus): the model
            // cannot reliably TRACK that each command starts back at the root — a `cd X && ...` works
            // within its one command and silently "unhappens", which is how nested duplicate trees and
            // materialized absolute paths are born (battery22: three split-brain runs). Say it each time.
            String out = clamp(sink.toString());
            String cwdNote = ContainerExec.active()
                    ? "  [ran in " + ContainerExec.workdir() + " inside the container — every command starts there; cd does not persist]\n"
                    : "  [ran in project root " + scope.root() + " — every command starts there; cd does not persist]\n";
            return "exit=" + proc.exitValue() + cwdNote
                    + out + wrapperFallbackHint(cmd, proc.exitValue(), out);
        }
    }

    /**
     * Point-of-error grounding: a from-scratch tree (this harness's deps-free fixtures, and real fresh
     * checkouts that .gitignore the wrapper jar) often has NO committed `gradlew`/`mvnw` script, so invoking
     * it fails not-found — and a model can FIXATE on it instead of the system tool (battery58 library-api:
     * `./gradlew` 7×, bare `gradle` 0× → task_blocked, while the email-intel-java run that used bare `gradle`
     * built a jar fine). The system `gradle`/`mvn` IS on PATH and JDK21-pinned (env above). Nudge once, at the
     * point of failure — general to any wrapper-less JVM build, not fixture-specific.
     */
    private static String wrapperFallbackHint(String cmd, int exit, String out) {
        if (exit == 0 || cmd == null) return "";
        boolean notFound = exit == 127 || out.contains("No such file")
                || out.contains("command not found") || out.contains("Permission denied");
        if (!notFound) return "";
        if (cmd.contains("./gradlew"))
            return "\n[note: no `gradlew` wrapper exists in this project — run the system `gradle` directly "
                    + "(it is on PATH and pinned to JDK21), e.g. `gradle build`, instead of `./gradlew`.]";
        if (cmd.contains("./mvnw"))
            return "\n[note: no `mvnw` wrapper exists in this project — run the system `mvn` directly "
                    + "(on PATH), instead of `./mvnw`.]";
        return "";
    }

    /** Heavy ML step (training / full-dataset generation) that legitimately needs minutes → the generous timeout.
     *  Matches training commands and running a PIPELINE ENTRY script (python main/train/run/pipeline/generate.py);
     *  quick `python -c "..."` checks do NOT match, so they keep the short default. */
    private static boolean isHeavyStep(String lc) {
        if (lc.contains("finetune") || lc.contains("fine_tune") || lc.contains("fine-tune") || lc.contains("sft")
                || lc.contains("peft") || lc.contains("lora") || lc.contains("trainer.train") || lc.contains(".fit(")
                || lc.contains("epoch") || lc.contains("accelerate launch") || lc.contains("torchrun")
                || lc.contains(".generate(") || lc.contains("trainer")) return true;
        return lc.matches(".*python3?\\s+[^\\n]*?(main|train|run|pipeline|generate|infer)\\w*\\.py\\b.*");
    }

    /** Host available RAM in MB (from /proc/meminfo MemAvailable), or -1 if unreadable. Used to refuse a new heavy
     *  step when the box is already memory-starved (prevents the pile-on that livelocked the host). */
    private static long hostAvailableMemMb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    String[] p = line.trim().split("\\s+"); // "MemAvailable:  12345678 kB"
                    return Long.parseLong(p[1]) / 1024;
                }
            }
        } catch (Exception ignore) { /* /proc not available → skip the guard */ }
        return -1;
    }

    private static String clamp(String s) {
        if (s.length() <= CAP) return s;
        int half = CAP / 2;
        return s.substring(0, half) + "\n...[trimmed " + (s.length() - CAP) + " chars]...\n"
                + s.substring(s.length() - half);
    }

    /**
     * The environment of every command the model runs: nothing may wait for a person, and git looks at the workspace.
     *
     * <p>An editor is the prompt the first list missed. {@code git commit} with no {@code -m}, {@code git rebase
     * --continue}, {@code git merge} and {@code crontab -e} open {@code $EDITOR} and wait until the timeout kills them.
     * With {@code true} as the editor the message is left as it is: a merge or a rebase step goes through with its
     * default message, and a bare commit fails at once with "empty commit message", which tells the model to pass
     * {@code -m}. An askpass helper of {@code true} answers a password prompt with nothing, so the command fails
     * instead of waiting, and ssh is told never to ask.
     *
     * <p>{@code GIT_DIR} and its relatives are removed. When CodeZaiku is started from a git hook or a host's wrapper
     * they are set for THAT repository, and every git command the model ran would have acted on it, not on the
     * workspace.
     */
    static void nonInteractive(java.util.Map<String, String> env) {
        env.put("CI", "true");
        env.put("DEBIAN_FRONTEND", "noninteractive");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_PAGER", "cat");
        env.put("PAGER", "cat");
        env.put("PIP_NO_INPUT", "1");
        env.put("GIT_EDITOR", "true");
        env.put("GIT_SEQUENCE_EDITOR", "true");
        env.put("EDITOR", "true");
        env.put("VISUAL", "true");
        env.put("GIT_ASKPASS", "true");
        env.put("SSH_ASKPASS", "true");
        env.put("SSH_ASKPASS_REQUIRE", "never");
        for (String k : java.util.List.of("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY", "GIT_COMMON_DIR", "GIT_NAMESPACE", "GIT_PREFIX")) env.remove(k);
    }
}
