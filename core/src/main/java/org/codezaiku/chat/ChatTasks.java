package org.codezaiku.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Background work for the chat: a registry of jobs that outlive the turn that started them.
 *
 * <p>The problem this solves is a conversation held hostage: a ten-minute test suite or a
 * delegated sub-task blocked the whole REPL, so long work and conversation could not coexist —
 * the single biggest runtime difference the frontier-drive comparison surfaced
 * ({@code docs/FINDINGS_FRONTIER_DRIVE.md}). A job here runs on its own thread, its output goes
 * to a file (the artifact, kept), and completion NOTIFIES: the person via the console line the
 * watcher prints, the model via {@link #digestInto} which rides the next restate.
 *
 * <p>Notification is pull-plus-push on purpose. The push (console line) can land while the person
 * is mid-thought at the prompt; the pull (restate digest + /tasks) is what the MODEL sees, and it
 * survives the person ignoring the push. Nothing here re-invokes the model on its own — a
 * background completion never spends drive tokens until the person's next turn.
 */
public final class ChatTasks {

    public enum State { RUNNING, DONE, FAILED }

    public static final class Job {
        public final int id;
        public final String label;
        public final Path outFile;
        final Thread runner;
        volatile State state = State.RUNNING;
        volatile int exit = -1;
        volatile String result = "";     // short completion summary for the digest
        volatile boolean announced = false; // the console push happened
        volatile boolean digested = false;  // the model has seen it in a restate

        Job(int id, String label, Path outFile, Thread runner) {
            this.id = id;
            this.label = label;
            this.outFile = outFile;
            this.runner = runner;
        }
    }

    private final Map<Integer, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Path workDir;
    private final Consumer<String> notify;

    /** @param notify receives one console line per completion — ChatIo::notifyLine. */
    public ChatTasks(Path workDir, Consumer<String> notify) {
        this.workDir = workDir;
        this.notify = notify;
    }

    /** Start a shell command in the background. Returns the job (id is what the model quotes). */
    public Job startShell(String command, String label) throws IOException {
        Files.createDirectories(workDir);
        int id = nextId.getAndIncrement();
        Path out = workDir.resolve("task-" + id + ".log");
        ProcessBuilder pb = new ProcessBuilder("bash", "-lc", command)
                .redirectErrorStream(true).redirectOutput(out.toFile());
        Process p = pb.start();
        Thread t = new Thread(() -> {
            int rc;
            try {
                rc = p.waitFor();
            } catch (InterruptedException e) {
                p.destroyForcibly();
                rc = -1;
            }
            Job j = jobs.get(id);
            j.exit = rc;
            j.state = rc == 0 ? State.DONE : State.FAILED;
            j.result = "exit " + rc + tail(out, 200);
            j.announced = true;
            notify.accept("[task " + id + " " + j.state + "] " + label + " (exit " + rc
                    + ") — output " + out + "  · /tasks for details");
        }, "cz-task-" + id);
        t.setDaemon(true);
        Job j = new Job(id, label, out, t);
        jobs.put(id, j);
        t.start();
        return j;
    }

    /** Register an already-running piece of work (a delegated sub-loop). The caller completes it. */
    public Job startExternal(String label, Path outFile, Thread runner) {
        int id = nextId.getAndIncrement();
        Job j = new Job(id, label, outFile, runner);
        jobs.put(id, j);
        return j;
    }

    /** Completion callback for {@link #startExternal} jobs. */
    public void complete(Job j, boolean ok, String result) {
        j.exit = ok ? 0 : 1;
        j.state = ok ? State.DONE : State.FAILED;
        j.result = result == null ? "" : result;
        j.announced = true;
        notify.accept("[task " + j.id + " " + j.state + "] " + j.label + " · /tasks for details");
    }

    public List<Job> all() {
        return new ArrayList<>(jobs.values().stream()
                .sorted((a, b) -> Integer.compare(a.id, b.id)).toList());
    }

    public Job get(int id) {
        return jobs.get(id);
    }

    public boolean anyRunning() {
        return jobs.values().stream().anyMatch(j -> j.state == State.RUNNING);
    }

    /**
     * Completed-but-not-yet-seen jobs, rendered for the next turn's restate — then marked seen.
     * Empty string when there is nothing new, so the caller can plain-concatenate.
     */
    public String digestInto() {
        StringBuilder b = new StringBuilder();
        for (Job j : all()) {
            if (j.state != State.RUNNING && !j.digested) {
                j.digested = true;
                b.append("background task ").append(j.id).append(" (").append(j.label)
                 .append(") finished: ").append(j.state).append(", ").append(j.result.strip())
                 .append(" — full output in ").append(j.outFile).append('\n');
            }
        }
        return b.isEmpty() ? "" : "Since the last turn:\n" + b;
    }

    /** Stop everything still running (REPL exit). Artifacts stay on disk. */
    public void shutdown() {
        for (Job j : jobs.values()) {
            if (j.state == State.RUNNING) {
                j.runner.interrupt();
            }
        }
    }

    static String tail(Path f, int chars) {
        try {
            String s = Files.readString(f, StandardCharsets.UTF_8);
            s = s.strip();
            return s.isEmpty() ? "" : "; tail: " + s.substring(Math.max(0, s.length() - chars))
                    .replaceAll("\\s+", " ");
        } catch (IOException e) {
            return "";
        }
    }

    static String stamp() {
        return Instant.now().toString();
    }
}
