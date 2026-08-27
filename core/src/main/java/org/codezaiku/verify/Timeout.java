package org.codezaiku.verify;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A wall-clock bound around a shell command that works on more than Linux.
 *
 * <p>We reach for GNU {@code timeout} in several places, and **macOS ships neither {@code timeout} nor
 * {@code gtimeout}** unless someone installed coreutils. There the wrapper simply fails with 127
 * ("command not found") — the command never runs, and the caller reads that non-zero as the command
 * having FAILED. For the test oracle that meant every project on macOS reporting its suite as failed
 * whether or not it passed. Measured on macOS 26.5, not theorised.
 *
 * <p>The bound cannot just be dropped on those hosts: the Java-side {@code waitFor} cannot save us,
 * because reading the child's output blocks on the open pipe before any Java timeout is reached. So
 * this falls back to {@code perl}'s {@code alarm}, which is present on a stock macOS and preserved
 * across {@code exec} — the same guarantee by another route.
 */
public final class Timeout {

    /** Resolved once: probing the filesystem per command would be wasteful and never changes. */
    private static final String KIND = detect();

    private static String detect() {
        if (onPath("timeout")) return "timeout";
        if (onPath("gtimeout")) return "gtimeout";        // coreutils on macOS/BSD
        if (onPath("perl")) return "perl";
        return "none";
    }

    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        // `.exe` matters: Git Bash ships timeout.exe and perl.exe, and a bare-name probe found
        // NEITHER — so Windows resolved to "none" and every test command there ran UNBOUNDED, which
        // is the one thing this class exists to prevent. Measured with Timeout.mechanism() on a
        // Windows box that had /usr/bin/timeout perfectly available to its shell.
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String name : new String[]{exe, exe + ".exe"}) {
                try {
                    Path p = Path.of(dir, name);
                    if (Files.isExecutable(p)) return true;
                } catch (Exception ignored) {
                    // an unparseable PATH entry is not an executable
                }
            }
        }
        return false;
    }

    /** True when no mechanism is available and commands will run unbounded. */
    public static boolean unavailable() {
        return "none".equals(KIND);
    }

    /** Which mechanism is in use — for diagnostics that should say so rather than imply a bound. */
    public static String mechanism() {
        return KIND;
    }

    /**
     * Wrap {@code command} so it is killed after {@code seconds}.
     *
     * @param command a shell command string, already quoted as the caller needs it
     */
    public static String wrap(int seconds, String command) {
        return switch (KIND) {
            case "timeout", "gtimeout" -> KIND + " -k 10 " + seconds + " bash -c " + shq(command);
            // alarm(2) survives exec and its default disposition terminates the process, so the bound
            // holds even though perl is replaced by the command it launches.
            case "perl" -> "perl -e 'alarm shift @ARGV; exec @ARGV or exit 127' "
                    + seconds + " bash -c " + shq(command);
            // Unbounded. Better than refusing to run at all, and the caller is told via unavailable().
            default -> "bash -c " + shq(command);
        };
    }

    static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private Timeout() { }
}
