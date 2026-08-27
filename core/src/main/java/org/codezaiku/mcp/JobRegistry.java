package org.codezaiku.mcp;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * In-memory job registry for the MCP server's ASYNC tool mode. The {@code code}/{@code fix}/
 * {@code explore_and_fix} tools block for minutes; a caller that is asynchronous by design can pass
 * {@code async:true} to get a job id back immediately and poll {@code job_status} — instead
 * of holding one MCP request open for the whole run. Jobs run on daemon threads and live for the process.
 */
public final class JobRegistry {
    public enum State { RUNNING, DONE, FAILED }

    /** One tool invocation's outcome (mirrors the sync tool result). */
    public record ToolResult(String text, boolean isError) { }

    public static final class Job {
        final String id;
        final String kind;
        final long startedMs = System.currentTimeMillis();
        volatile long endedMs = 0;
        volatile State state = State.RUNNING;
        volatile String result = "";
        volatile boolean isError = false;
        Job(String id, String kind) { this.id = id; this.kind = kind; }
        public String id() { return id; }
        public State state() { return state; }
        public String result() { return result; }
        public boolean isError() { return isError; }
        public long elapsedMs() { return (endedMs == 0 ? System.currentTimeMillis() : endedMs) - startedMs; }
    }

    private static final JobRegistry INSTANCE = new JobRegistry();
    public static JobRegistry get() { return INSTANCE; }

    private final ExecutorService exec = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "cp-mcp-job");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** Submit {@code work} to run on a background thread; return the job id immediately. */
    public String submit(String kind, Supplier<ToolResult> work) {
        String id = kind + "-" + seq.incrementAndGet();
        Job j = new Job(id, kind);
        jobs.put(id, j);
        exec.submit(() -> {
            try {
                ToolResult r = work.get();
                j.result = r.text();
                j.isError = r.isError();
                j.state = State.DONE;
            } catch (Throwable t) {
                j.result = "job error: " + t;
                j.isError = true;
                j.state = State.FAILED;
            } finally {
                j.endedMs = System.currentTimeMillis();
            }
        });
        return id;
    }

    public Job job(String id) { return jobs.get(id); }
}
