package org.codezaiku.ops;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Run a host process with a timeout, optional stdin, capturing merged stdout+stderr. Never throws. */
final class Procs {
    private Procs() { }

    static Exec.Result run(String stdin, int timeoutSec, String... argv) {
        Process proc = null;
        try {
            proc = new ProcessBuilder(argv).redirectErrorStream(true).start();
            if (stdin != null) {
                try (OutputStream os = proc.getOutputStream()) {
                    os.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                proc.getOutputStream().close();
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            // Drain stdout on a side thread so a chatty command can't deadlock on a full pipe while we wait.
            Process p = proc;
            Thread drain = new Thread(() -> {
                try { p.getInputStream().transferTo(buf); } catch (Exception ignored) { }
            });
            drain.setDaemon(true);
            drain.start();
            boolean done = proc.waitFor(Math.max(1, timeoutSec), TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                proc.waitFor(5, TimeUnit.SECONDS);
                drain.join(1000);
                return new Exec.Result(124, buf.toString(StandardCharsets.UTF_8)
                        + "\n[timed out after " + timeoutSec + "s]");
            }
            drain.join(2000);
            return new Exec.Result(proc.exitValue(), buf.toString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            if (proc != null) proc.destroyForcibly();
            return new Exec.Result(-1, "exec error: " + e.getMessage());
        }
    }

    /** Single-quote a string for safe embedding in a bash -c command. */
    static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
