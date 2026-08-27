package org.codezaiku.ops;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which operating system the OPERATED TARGET runs — not the one this JVM runs on.
 *
 * <p>That distinction is the whole point. CodeZaiku routinely operates a remote Linux host from a Mac
 * over {@code ssh://}, and equally can sense the Mac it is running on. Choosing probes from
 * {@code System.getProperty("os.name")} would pick the wrong ones in both directions: BSD probes
 * against a remote Linux server, and {@code /proc} probes against the local Mac.
 *
 * <p>Detected once per {@link Exec} and cached, because these probes run in loops and a `uname` per
 * probe is a network round trip when the target is remote.
 */
public final class TargetOs {

    public enum Kind { LINUX, MACOS, UNKNOWN }

    private static final Map<Exec, Kind> CACHE = new ConcurrentHashMap<>();
    private static final Map<Exec, String> STACK = new ConcurrentHashMap<>();

    public static Kind of(Exec exec) {
        if (exec == null) return Kind.UNKNOWN;
        return CACHE.computeIfAbsent(exec, TargetOs::probe);
    }

    public static boolean isMac(Exec exec) {
        return of(exec) == Kind.MACOS;
    }

    private static Kind probe(Exec exec) {
        try {
            Exec.Result r = exec.run("uname -s 2>/dev/null", 10);
            String s = r.out() == null ? "" : r.out().trim().toLowerCase(Locale.ROOT);
            if (s.contains("darwin")) return Kind.MACOS;
            if (s.contains("linux")) return Kind.LINUX;
            return Kind.UNKNOWN;
        } catch (RuntimeException e) {
            return Kind.UNKNOWN;
        }
    }

    /**
     * The environment tokens a fix card's {@code match:} keywords are tested against — what this target
     * ACTUALLY is, probed, rather than what the harness assumes.
     *
     * <p>This used to be the literal string {@code "linux systemd host docker "}, which is true of the
     * reference stack and false of every other target we now support. On a Mac, or a systemd-less
     * container host, that constant made host-tier {@code systemd} cards eligible and handed the model a
     * {@code systemctl} procedure that cannot run there — a confidently wrong fix procedure, which is
     * worse than no card at all. It is the same failure as the hardcoded {@code timeout}: a Linux
     * assumption baked into a path that reaches other platforms.
     *
     * <p>Presence is decided by {@code command -v}, so the claim is only ever "this target has the
     * tool". On the certified Linux refstack this yields {@code host linux systemd docker} — the same
     * token set as the old constant, so card eligibility there is unchanged.
     */
    public static String stackTokens(Exec exec) {
        if (exec == null) return "host";
        return STACK.computeIfAbsent(exec, TargetOs::probeStack);
    }

    private static String probeStack(Exec exec) {
        StringBuilder sb = new StringBuilder("host");
        switch (of(exec)) {
            case MACOS -> sb.append(" macos darwin");
            case LINUX -> sb.append(" linux");
            // UNKNOWN: name no OS rather than guess one. The tool probes below still run, so a target we
            // cannot identify is described by what it demonstrably has instead of by a default.
            case UNKNOWN -> { }
        }
        try {
            Exec.Result r = exec.run(
                    "for t in systemctl launchctl docker podman kubectl; do "
                  + "command -v $t >/dev/null 2>&1 && echo $t; done", 10);
            String out = r.out() == null ? "" : r.out();
            if (out.contains("systemctl")) sb.append(" systemd");
            if (out.contains("launchctl")) sb.append(" launchd");
            if (out.contains("docker")) sb.append(" docker");
            if (out.contains("podman")) sb.append(" podman");
            if (out.contains("kubectl")) sb.append(" kubectl k8s kubernetes");
        } catch (RuntimeException e) {
            // A target we cannot probe gets the OS token alone; an unmatched card beats a wrong procedure.
        }
        return sb.toString();
    }

    // ---- probes that differ by target -----------------------------------------------------------

    /**
     * Total and available memory in kB, one per line — the shape {@code /proc/meminfo} already gave.
     *
     * <p>The macOS page size is read from {@code vm_stat}'s own header rather than assumed: it is
     * 16384 on Apple Silicon and 4096 on Intel, and hardcoding either gets the answer wrong by 4x on
     * the other. "Available" counts free plus inactive, which is what macOS can actually hand out.
     */
    public static String memoryProbe(Exec exec) {
        if (isMac(exec)) {
            return "sysctl -n hw.memsize | awk '{printf \"%d\\n\", $1/1024}'; "
                 + "vm_stat | awk '/page size of/{ps=$8} "
                 + "/Pages free/{gsub(/\\./,\"\",$3); f=$3} "
                 + "/Pages inactive/{gsub(/\\./,\"\",$3); i=$3} "
                 + "END{printf \"%d\\n\", (f+i)*ps/1024}'";
        }
        return "awk '/MemTotal|MemAvailable/{print $2}' /proc/meminfo";
    }

    /**
     * Filesystems at or above {@code pct}% full, as "mountpoint percent".
     *
     * <p>BSD {@code df} has no {@code -x} (exclude by type) and no {@code -T}, which is exactly why
     * this probe reported nothing on macOS. {@code -P} is POSIX and works on both, giving capacity in
     * $5 and the mount point in $6; the pseudo-filesystems that {@code -x} would have dropped are
     * excluded by mount point instead.
     */
    public static String diskProbe(Exec exec, int pct) {
        if (isMac(exec)) {
            return "df -P -k 2>/dev/null | awk 'NR>1 {gsub(/%/,\"\",$5); "
                 + "if ($5+0 < " + pct + ") next; "
                 + "if ($6 ~ /^\\/(dev|System\\/Volumes\\/VM|private\\/var\\/vm)/) next; "
                 + "if ($1 ~ /^(devfs|map|auto_home)/) next; "
                 + "print $6\" \"$5\"%\"}'";
        }
        return "df -P -x tmpfs -x devtmpfs -x overlay 2>/dev/null "
             + "| awk 'NR>1 && $5+0>=" + pct + " {print $6\" \"$5}'";
    }

    /** Anything LISTENING on the given TCP ports. {@code ss} is Linux-only; macOS has {@code lsof}. */
    public static String listeningProbe(Exec exec, String portRegex, String lsofPorts) {
        if (isMac(exec)) {
            return "lsof -nP -sTCP:LISTEN " + lsofPorts + " 2>/dev/null | tail -n +2 || true";
        }
        return "ss -ltn 2>/dev/null | grep -E '" + portRegex + "' || true";
    }

    /**
     * A wall-clock bound that exists on the TARGET, or none at all.
     *
     * <p>macOS ships neither {@code timeout} nor {@code gtimeout}, so a hardcoded {@code timeout N …}
     * does not merely lose its bound there — the whole command fails with "command not found" and the
     * caller receives that error string as the command's OUTPUT. Measured consequence: the ops log
     * probe returned 31 characters of shell error, the card signature scanner matched against it, and
     * therefore NO fix card could ever fire on macOS. A missing bound is survivable; a probe that
     * silently returns an error message as evidence is not.
     */
    public static String timeoutPrefix(Exec exec, int seconds) {
        if (!isMac(exec)) return "timeout " + seconds + " ";
        // perl ships with macOS; alarm(2) survives exec and its default action terminates.
        return "perl -e 'alarm shift @ARGV; exec @ARGV or exit 127' " + seconds + " ";
    }

    private TargetOs() { }
}
