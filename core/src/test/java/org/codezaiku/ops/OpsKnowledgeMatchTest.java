package org.codezaiku.ops;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The fast-path card match, and the identity it is gated on.
 *
 * <p>Cards name the PRODUCT ({@code match: redis, valkey}); real stacks name services for their ROLE
 * ({@code cache}, {@code db}, {@code queue}). Gating the match on the localized service NAME alone
 * therefore rejects every product-named card for a role-named service — before its signature is ever
 * read, so no amount of evidence can rescue it.
 *
 * <p>Measured, not reasoned: on a macOS fixture whose redis container is named {@code mstack-cache},
 * the log probe carried 25 lines of {@code -NOAUTH Authentication required.} and
 * {@code redis-auth-server} — a validated card whose signature contains that exact string — still
 * never fired. Every service on the certified Linux refstack is named for its product, which is why
 * this never showed there.
 *
 * <p>The identity must stay ROOT-SCOPED. Passing the whole stack was measured to be worse: shared
 * signature keywords let the wrong card win (postgres got a vector-store card). These tests pin both
 * halves — the role-named service matches, and a bystander's card still does not.
 */
class OpsKnowledgeMatchTest {

    /** The real card, verbatim from ops-knowledge/redis-auth-server.md. */
    private static OpsKnowledge.Card redisAuth() {
        return new OpsKnowledge.Card(
                List.of("redis", "valkey"),
                List.of("wasn't able to connect to redis", "noauth authentication required",
                        "authentication required", "wrongpass", "invalid password"),
                "rescue", "validated", List.of(), "body", "redis-auth-server.md");
    }

    /** A bystander card that must NOT win — its signature shares the generic "authentication required". */
    private static OpsKnowledge.Card mongoAuth() {
        return new OpsKnowledge.Card(
                List.of("mongo", "mongodb"),
                List.of("authentication required", "authentication failed"),
                "rescue", "validated", List.of(), "body", "mongo-auth.md");
    }

    private static final String PROBE =
            "cache probe failed: -NOAUTH Authentication required.\n"
            + "cache probe failed: -NOAUTH Authentication required.\n";

    @Test
    void productNamedCardMatchesARoleNamedService() {
        // The identity carries the root's own image, so "cache" still reaches the redis card.
        var m = OpsKnowledge.matchSignature(List.of(redisAuth(), mongoAuth()),
                "cache mstack-cache redis:7-alpine", PROBE);
        assertNotNull(m, "a redis container named 'cache' must still match the redis card");
        assertEquals("redis-auth-server.md", m.path());
    }

    @Test
    void serviceNameAloneIsNotEnoughIdentity() {
        // Pins the defect itself: this is exactly what the call site used to pass, and it finds nothing
        // even though the probe is saturated with the card's own signature string.
        assertNull(OpsKnowledge.matchSignature(List.of(redisAuth()), "cache", PROBE),
                "documents WHY the identity must include the image — the name alone rejects the card");
    }

    @Test
    void identityStaysRootScoped() {
        // A cache-rooted incident must not pull in the mongo card just because mongo runs elsewhere in
        // the stack. The identity is the ROOT's name+container+image, never the whole roster.
        var m = OpsKnowledge.matchSignature(List.of(mongoAuth()),
                "cache mstack-cache redis:7-alpine", PROBE);
        assertNull(m, "a bystander's card must not match on the root's identity");
    }

    @Test
    void strongestSignatureWinsWhenBothAreEligible() {
        // Both cards are eligible only if the identity names both products; redis matches two signature
        // strings to mongo's one, so ranking — not ordering — decides.
        var m = OpsKnowledge.matchSignature(List.of(mongoAuth(), redisAuth()),
                "cache redis:7-alpine mongo:7", PROBE);
        assertNotNull(m);
        assertEquals("redis-auth-server.md", m.path(), "the card matching more signature strings wins");
    }

    @Test
    void noSignatureInTheProbeMeansNoCard() {
        assertNull(OpsKnowledge.matchSignature(List.of(redisAuth()),
                "cache mstack-cache redis:7-alpine", "everything is fine\n"),
                "the image in the identity must not by itself fire a card");
    }
}
