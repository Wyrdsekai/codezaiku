package org.codezaiku.ops;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What has actually been done to this box during remediation — the harness's own record, kept
 * independently of anything the model says about its work.
 *
 * <p>It answers two questions that turn out to be the same question. <b>What changed?</b> is the audit
 * trail: {@code applied_steps} is the model's account and arrives only if it calls
 * {@code remediation_done} at all, so a run that restarted, stopped and then DELETED the root container
 * recorded {@code applied: []} (measured on macOS). An audit that omits the most destructive action of
 * the run is the wrong shape. <b>Is this command worth running again?</b> is the repetition guard:
 * at temperature 0 a small model fixates and re-emits one command forever (measured: the identical
 * probe 12+ times, remediation 0/3 without a guard, following the card with it).
 *
 * <p>They are the same question because a repeat is only pointless while <em>nothing has changed</em>.
 * A remember-forever set gets that wrong the moment the model mutates the box: on macOS the remediator
 * destroyed the root service with {@code docker stop} then {@code docker rm}, then correctly reached for
 * the {@code docker run} that would recreate it — a command tried earlier, when it had failed on a name
 * collision — and it was suppressed as a repeat on iterations 12, 13 and 14. The loop could not undo
 * what it had just done; only the R3 snapshot recovered the container. A guard against wasted turns had
 * become a guard against recovery.
 *
 * <p>The obvious repair — let a mutation invalidate earlier observations, so a destroyed service can be
 * recreated — was built, measured three times, and REMOVED. It never won: 8/8 vs 8/8 on the refstack
 * (where the branch never even runs), 0/3 vs 0/3 on a fixture that could not be won, and 3/4 vs 1/4
 * against it on the first fixture where success was achievable. Its first real run destroyed a fixture
 * outright. And the defect that motivated it — the loop cannot undo its own destruction — belongs to
 * R3, which now removes a foreign container holding the name, handles multi-file compose projects, and
 * reports honestly when it cannot restore. Recovery is R3's job; this class only has to avoid wasting
 * turns.
 *
 * <p>So suppression is plain and permanent: a command whose result is already in the transcript is not
 * run again. The MUTATION RECORD is unconditional — it only observes, and the audit gap it closes is
 * real on the certified stack (a successful refstack run logged {@code applied: []} while restarting
 * redis).
 */
final class ActionLedger {

    private final Set<String> seen = new LinkedHashSet<>();
    private final List<String> mutations = new ArrayList<>();

    /**
     * @return true if {@code command} is an exact repeat whose earlier result still stands, so running
     *         it again cannot produce new information. Records the command either way.
     */
    boolean isPointlessRepeat(String command) {
        String c = norm(command);
        if (c.isEmpty()) return false;
        return !seen.add(c);
    }

    /** Record that {@code command} actually executed; a command that changed the box is kept. */
    void executed(String command) {
        String c = norm(command);
        if (c.isEmpty() || !OpsSafety.isMutating(c)) return;
        mutations.add(c);
    }

    /** Every command the harness WATCHED change the box, in order, including repeats it allowed. */
    List<String> mutations() {
        return List.copyOf(mutations);
    }

    private static String norm(String command) {
        return command == null ? "" : command.trim();
    }
}
