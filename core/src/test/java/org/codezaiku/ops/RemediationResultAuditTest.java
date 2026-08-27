package org.codezaiku.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the audit trail records about a run that CHANGED the box.
 *
 * <p>{@code appliedSteps} is the model's own account, and it exists only if the model calls
 * {@code remediation_done}. Measured on macOS: a run whose model ran {@code docker restart}, then
 * {@code docker stop}, then {@code docker rm} on the root service recorded {@code applied: []}. An
 * audit trail that omits the most destructive action of the run is the wrong shape — the record has
 * to say a container was destroyed, whether or not the model chose to mention it.
 */
class RemediationResultAuditTest {

    private static final ObjectMapper J = new ObjectMapper();

    @Test
    void theDestructiveActionSurvivesAModelThatReportedNothing() {
        RemediationResult r = new RemediationResult(
                List.of(), false, "", Boolean.FALSE,
                List.of("docker restart mstack-cache", "docker stop mstack-cache", "docker rm mstack-cache"));

        assertTrue(r.appliedSteps().isEmpty(), "the model claimed nothing — that is the case under test");
        assertTrue(r.observedMutations().contains("docker rm mstack-cache"),
                "the harness watched it delete the root container, so the record must say so");

        ObjectNode json = r.toJson(J);
        assertTrue(json.get("observed_mutations").toString().contains("docker rm mstack-cache"));
        assertEquals(0, json.get("applied_steps").size());
    }

    @Test
    void allMutationsUnionsBothAccountsWithoutDuplicating() {
        RemediationResult r = new RemediationResult(
                List.of("docker restart mstack-cache"), true, "curl ok", Boolean.TRUE,
                List.of("docker restart mstack-cache", "sed -i s/x/y/ /etc/redis/redis.conf"));

        assertEquals(List.of("docker restart mstack-cache", "sed -i s/x/y/ /etc/redis/redis.conf"),
                r.allMutations(),
                "the command both accounts name appears once, in first-seen order");
    }

    @Test
    void aConfigEditTheModelDidNotReportIsStillVisibleToTheLatentCorruptionCheck() {
        // The latent-config trigger asks "did the fix touch a config file?" — a restart-validate follows
        // if so, because a bad config line only bites on the NEXT restart. Consulting only the model's
        // claim means the one edit it forgot to mention is exactly the one that escapes the check.
        RemediationResult r = new RemediationResult(
                List.of(), true, "", Boolean.TRUE,
                List.of("sed -i 's/^requirepass.*/requirepass s3cret/' /etc/redis/redis.conf"));

        assertTrue(r.allMutations().stream().anyMatch(s -> s.contains("/etc/redis/redis.conf")));
    }

    @Test
    void nullListsAreEmptyRatherThanAnNpeAtAuditTime() {
        RemediationResult r = new RemediationResult(null, false, null, null, null);
        assertTrue(r.appliedSteps().isEmpty());
        assertTrue(r.observedMutations().isEmpty());
        assertTrue(r.allMutations().isEmpty());
        assertTrue(r.toJson(J).get("harness_verified").isNull());
    }
}
