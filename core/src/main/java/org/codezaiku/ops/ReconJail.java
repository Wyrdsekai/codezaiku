package org.codezaiku.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The containment contract for the {@link ReconPhase}. Load-bearing, not optional.
 *
 * <p>The recon step acquires the domain knowledge that is the measured lever (how a stack is shaped and how
 * it fails), and the ONLY thing that keeps that acquisition honest is that it reads the stack and NEVER the
 * grader's answer. "Read the stack" on an eval box would happily read {@code record.csv} / {@code check.sh} /
 * {@code solution/} sitting in the tree — and a card built from those is the answer key, which is exactly the
 * leak that disqualified OpenRCA's oracle map. So this jail is what makes recon-derived knowledge sound BY
 * CONSTRUCTION: a blind step cannot leak an answer it was structurally prevented from reading.
 *
 * <p>It does two jobs at once:
 * <ul>
 *   <li><b>Soundness</b> (for eval): deny the grader/answer paths, so a card cannot encode a task's answer.</li>
 *   <li><b>Safety</b> (for a real box): read-only always — recon never mutates the machine it is learning.</li>
 * </ul>
 *
 * <p>Enforcement is structural: {@link #wrap} hands recon a {@link JailedExec} that rejects any read outside
 * the allowed roots, any denied path, any non-read-only command, and every write. Recon issues a FIXED,
 * harness-authored set of read-only probes (the model does not drive it), so there is no injection surface;
 * the jail is the guarantee, not the convention.
 */
public record ReconJail(List<String> allowedRoots, List<Pattern> denied, boolean allowAll) {

    /** Mutating tokens that make a command non-read-only regardless of the leading program. */
    private static final Pattern MUTATING = Pattern.compile(
            "(^|[\\s;&|])(rm|mv|cp|dd|mkfs|mount|umount|kill|pkill|killall|tee|truncate|chmod|chown|chattr|"
          + "ln|touch|install|apt|apt-get|yum|dnf|pip|npm|gem|cargo|make|gcc|shutdown|reboot|"
          + "start|stop|restart|reload|enable|disable|create|run|exec|prune|remove|delete|set|put|write)([\\s;&|]|$)",
            Pattern.CASE_INSENSITIVE);

    /** Benign redirects a read-only probe legitimately needs: discard stderr, merge streams. */
    private static final Pattern BENIGN_REDIR = Pattern.compile("\\d?>>?\\s*/dev/null|\\d?>&\\d");

    /** Writing constructs that survive after the benign redirects are stripped: a redirect to a real file,
     *  command substitution, an in-place sed. */
    private static final Pattern ESCAPE = Pattern.compile("[>`]|\\$\\(|\\bsed\\b[^|;]*\\s-i\\b");

    /** A real box: read anywhere, but never a credential store, and never mutate. */
    public static ReconJail forBox() {
        return new ReconJail(List.of("/"), List.of(
                Pattern.compile("/etc/(shadow|gshadow|sudoers)"),
                Pattern.compile("(^|/)\\.(env|netrc|pgpass)$"),
                Pattern.compile("(^|/)(id_rsa|id_ed25519|.*\\.pem|.*\\.key)$")), true);
    }

    /**
     * An eval box (SadServers / Terminal-Bench / OpenRCA): everything {@link #forBox} denies, PLUS the
     * grader's own files, so a recon card cannot be built from the answer. These are the paths a benchmark
     * hides from a submission: the tests, the reference solution, the reward/record/query files.
     */
    public static ReconJail forEvalBox() {
        ReconJail base = forBox();
        var denied = new ArrayList<>(base.denied());
        denied.add(Pattern.compile("(^|/)(tests?|solution|answer|grader|verifier)(/|$)"));
        denied.add(Pattern.compile("(^|/)(check|verify|solve|grade)\\.(sh|py)$"));
        denied.add(Pattern.compile("(^|/)(record|query|answer|scoring_points|reward|groundtruth)\\.(csv|json|ya?ml|txt)$"));
        return new ReconJail(base.allowedRoots(), denied, true);
    }

    /** May recon read this path? Inside an allowed root, and matching no denied pattern. */
    public boolean mayRead(String path) {
        if (path == null || path.isBlank()) return false;
        for (Pattern d : denied) if (d.matcher(path).find()) return false;
        if (allowAll) return true;
        for (String root : allowedRoots) if (path.equals(root) || path.startsWith(root)) return true;
        return false;
    }

    /**
     * May recon run this command? Only if it is read-only. Stderr/dev-null redirects are stripped first —
     * a probe that discards stderr ({@code 2>/dev/null}) or merges streams ({@code 2>&1}) is still read-only,
     * and rejecting those would refuse recon's own probes (it did: the first live run returned an empty map
     * because every probe used {@code 2>/dev/null}). After stripping, any remaining redirect to a real file,
     * command substitution, in-place sed, or mutating program disqualifies it.
     */
    public boolean mayRun(String command) {
        if (command == null || command.isBlank()) return false;
        String c = BENIGN_REDIR.matcher(command).replaceAll(" ");
        // A redirect/substitution only counts OUTSIDE quotes: `NR>1` in an awk program and `s/">"//` in a sed
        // program are NOT writes, and rejecting them refused a real probe (the ports scan). Strip quoted spans
        // before the redirect check. The mutating-PROGRAM check stays on the full command and fails closed —
        // over-refusing a quoted "rm" is safe; recon never needs one.
        String unquoted = c.replaceAll("'[^']*'", " ").replaceAll("\"[^\"]*\"", " ");
        if (ESCAPE.matcher(unquoted).find()) return false;
        return !MUTATING.matcher(c).find();
    }

    /** Hand recon an Exec it physically cannot escape. */
    public Exec wrap(Exec delegate) {
        return new JailedExec(delegate, this);
    }

    /**
     * An {@link Exec} that enforces the jail. A denied read returns null (as if the file were absent — recon
     * degrades, it does not crash); a disallowed command returns a refusal Result; every write throws, because
     * a recon step that writes to the box it is learning is a bug, and a loud one is better than a silent one.
     */
    static final class JailedExec implements Exec {
        private final Exec d;
        private final ReconJail jail;
        JailedExec(Exec d, ReconJail jail) { this.d = d; this.jail = jail; }

        @Override public String describe() { return d.describe() + " [recon-jailed]"; }

        @Override public Result run(String command, int timeoutSec) {
            if (!jail.mayRun(command)) return new Result(-1, "[recon-jail] refused non-read-only command");
            return d.run(command, timeoutSec);
        }
        @Override public String read(String path)  { return jail.mayRead(path) ? d.read(path) : null; }
        @Override public boolean isFile(String path) { return jail.mayRead(path) && d.isFile(path); }
        @Override public boolean isDir(String path)  { return jail.mayRead(path) && d.isDir(path); }
        @Override public void write(String path, String content) {
            throw new IllegalStateException("[recon-jail] recon must not write (" + path + ")");
        }
    }
}
