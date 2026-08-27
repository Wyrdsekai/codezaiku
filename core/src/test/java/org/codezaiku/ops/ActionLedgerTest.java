package org.codezaiku.ops;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The harness's own record of what it did to the box — the repetition guard, and the mutation list
 * the audit trail depends on.
 *
 * <p>Suppressing an exact repeat is what stops a temp-0 small model fixating on one probe (measured:
 * the identical command 12+ times, remediation 0/3 without the guard). But "already run" only means
 * "pointless" while nothing has changed. Measured on macOS: the remediator ran
 * {@code docker stop} then {@code docker rm} on the root service, then reached for the {@code docker
 * run} that would recreate it — a command it had tried earlier, when it failed on a name collision.
 * The guard suppressed it on iterations 12, 13 and 14. The loop could not undo its own destruction;
 * only the R3 snapshot recovered the container.
 *
 * <p>So these tests pin BOTH edges: a mutation must re-open earlier commands, and it must not re-open
 * itself — or {@code docker restart} thrash returns, which is the failure the guard was built for.
 */
class ActionLedgerTest {

    @Test
    void anExactRepeatWithNoInterveningChangeIsSuppressed() {
        ActionLedger g = new ActionLedger();
        String probe = "docker ps -a";
        assertFalse(g.isPointlessRepeat(probe), "first run of a probe is always allowed");
        g.executed(probe);
        assertTrue(g.isPointlessRepeat(probe), "nothing changed, so the earlier result still stands");
    }

    @Test
    void suppressionIsPERMANENT_evenAfterTheModelDestroysSomething() {
        ActionLedger g = new ActionLedger();
        String recreate = "docker run -d --name mstack-cache redis:7 redis-server --requirepass s3cret";

        // First attempt fails on a name collision — the container still exists.
        assertFalse(g.isPointlessRepeat(recreate));
        g.executed(recreate);

        // The model then destroys the root service.
        assertFalse(g.isPointlessRepeat("docker stop mstack-cache"));
        g.executed("docker stop mstack-cache");
        assertFalse(g.isPointlessRepeat("docker rm mstack-cache"));
        g.executed("docker rm mstack-cache");

        // The identical recreate stays suppressed. Letting it through was built, measured three times,
        // and removed: it never won and it destroyed a fixture. Recovering a destroyed service is R3's
        // job, and R3 now does it — foreign container removed, multi-file compose handled, honest report
        // when it cannot. This class only has to avoid wasting turns.
        assertTrue(g.isPointlessRepeat(recreate),
                "recovery belongs to R3; the loop must not re-run a command whose result it already has");
    }

    @Test
    void aMutationDoesNotReopenItself() {
        ActionLedger g = new ActionLedger();
        String restart = "docker restart refstack-redis";
        assertFalse(g.isPointlessRepeat(restart));
        g.executed(restart);
        assertTrue(g.isPointlessRepeat(restart),
                "clearing on every mutation would let restart-thrash run forever");
    }

    @Test
    void aReadOnlyCommandDoesNotReopenAnything() {
        ActionLedger g = new ActionLedger();
        assertFalse(g.isPointlessRepeat("curl -s localhost:8080/ready"));
        g.executed("curl -s localhost:8080/ready");
        assertFalse(g.isPointlessRepeat("docker ps"));
        g.executed("docker ps");
        assertTrue(g.isPointlessRepeat("curl -s localhost:8080/ready"),
                "probes do not change the box, so an earlier probe result still stands");
    }

    @Test
    void aProbeStaysSuppressedEvenAfterAMutation() {
        // The closed-loop RE-CHECK is the harness's own job (harnessVerifyCmd), not something the model
        // has to re-issue, so the loop loses nothing by keeping this suppressed.
        ActionLedger g = new ActionLedger();
        String probe = "curl -s localhost:8080/ready";
        assertFalse(g.isPointlessRepeat(probe));
        g.executed(probe);
        g.executed("docker restart mstack-cache");
        assertTrue(g.isPointlessRepeat(probe));
    }

    @Test
    void blankAndNullCommandsAreIgnoredRatherThanCollapsedTogether() {
        ActionLedger g = new ActionLedger();
        assertFalse(g.isPointlessRepeat(""));
        assertFalse(g.isPointlessRepeat(null));
        assertFalse(g.isPointlessRepeat("   "));
        g.executed(null);
        g.executed("");
    }

    @Test
    void theMutationRecordIsKeptRegardless() {
        // The audit half carries none of the risk — it only observes — and the gap it closes is real on
        // the certified stack: a SUCCESSFUL refstack run logged `applied: []` while restarting redis.
        ActionLedger g = new ActionLedger();
        g.executed("docker restart refstack-redis-1");
        assertEquals(List.of("docker restart refstack-redis-1"), g.mutations());
    }

    @Test
    void surroundingWhitespaceIsNotANewCommand() {
        ActionLedger g = new ActionLedger();
        assertFalse(g.isPointlessRepeat("docker ps -a"));
        assertTrue(g.isPointlessRepeat("  docker ps -a  "));
    }

    // ---- the audit record ------------------------------------------------------------------------

    @Test
    void theDestructionOfTheRootServiceIsRecordedInOrder() {
        // The measured macOS sequence. The model reported none of it: `applied: []`.
        ActionLedger g = new ActionLedger();
        for (String c : new String[]{
                "curl -s localhost:8080/ready",
                "docker restart mstack-cache",
                "docker stop mstack-cache",
                "docker rm mstack-cache"}) {
            g.isPointlessRepeat(c);
            g.executed(c);
        }
        assertEquals(List.of("docker restart mstack-cache", "docker stop mstack-cache",
                "docker rm mstack-cache"), g.mutations(),
                "every command that changed the box, in the order it ran — and only those");
    }

    @Test
    void readOnlyProbesAreNotAuditedAsChanges() {
        ActionLedger g = new ActionLedger();
        for (String c : new String[]{"docker ps -a", "docker logs mstack-cache", "cat /etc/redis/redis.conf",
                "docker inspect mstack-cache", "curl -s localhost:8080/ready"}) {
            g.executed(c);
        }
        assertTrue(g.mutations().isEmpty(), "an audit of changes must not fill up with reads");
    }

    @Test
    void aServiceInternalWriteCountsAsAChangeToTheBox() {
        // `docker exec <svc> redis-cli CONFIG SET …` mutates through a container; the leading verb is
        // only "docker", which is exactly how this once slipped past a guard.
        ActionLedger g = new ActionLedger();
        g.executed("docker exec mstack-cache redis-cli CONFIG SET requirepass s3cret");
        assertEquals(1, g.mutations().size());
    }

    @Test
    void anAllowedRepeatOfAMutationIsRecordedEachTimeItRuns() {
        // Suppression is the loop's job; the ledger records what actually ran. A restart that genuinely
        // ran twice, separated by another change, is two events in the audit.
        ActionLedger g = new ActionLedger();
        g.executed("docker restart mstack-cache");
        g.executed("docker stop mstack-api");
        g.executed("docker restart mstack-cache");
        assertEquals(3, g.mutations().size());
    }
}
