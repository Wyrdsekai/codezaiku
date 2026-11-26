package org.codezaiku;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What a job does at its own end, which the program's end does instead when the job does not get there: SIGTERM, a second ctrl-C. The
 * program does not wait for a job's thread when it is ended, so until 0.3.21 a job ended that way did not record what it spent (missing
 * from /cost, from the record of spending and from a model's monthly limit), a chat did not save its last turn, and `code` did not print
 * its closing lines. Each end is done once: by the job, when it claims it before the program is ending, or else by the program's end
 * ({@link FamiliarMain#endWhatThisProgramStarted}, and `run`'s own hook).
 */
public final class AtEnd {
    private AtEnd() { }

    private static volatile boolean ending;

    /** The program is ending: from here no job claims its own end, and no new model call starts (none would be answered). */
    public static boolean ending() { return ending; }

    /** One job's end. */
    public static final class Task {
        private final Runnable work;
        private final AtomicInteger state = new AtomicInteger();   // 0 not yet, 1 the job is doing it, 2 done
        private final CountDownLatch over = new CountDownLatch(1);
        private Task(Runnable work) { this.work = work; }

        /** The job's own end begins: true when the job is to do it; false when the program is ending, whose end does it. */
        public boolean claim() { return !ending && state.compareAndSet(0, 1); }

        /** The job's own end is done (a no-op when it was not the job's). */
        public void over() {
            if (state.compareAndSet(1, 2)) { over.countDown(); TASKS.remove(this); }
        }

        private void atEnd() {
            if (state.compareAndSet(0, 2)) {
                try { work.run(); } finally { over.countDown(); TASKS.remove(this); }
            } else {
                // the job had begun its own end when the program was ended: it is given a moment to finish it
                try { over.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private static final List<Task> TASKS = new CopyOnWriteArrayList<>();

    /** A job's end, to be done by the program's end if the job does not claim it first. */
    public static Task add(Runnable work) {
        Task t = new Task(work);
        TASKS.add(t);
        return t;
    }

    /** The program is ending; said first, before what it started is stopped, so that a job that stops then does not claim its end. */
    public static void programEnds() { ending = true; }

    /** Every job's end that was not done yet, in the order the jobs began. */
    public static void runAll() {
        ending = true;
        for (Task t : List.copyOf(TASKS)) {
            try { t.atEnd(); } catch (RuntimeException e) { /* the next job */ }
        }
    }
}
