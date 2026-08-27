package org.codezaiku.ops;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The shipped {@code redis-auth-server} card must recognise how a CLIENT reports an auth failure,
 * not only how the server names it.
 *
 * <p>Its signature listed {@code wrongpass} — the redis error CODE. redis-py raises
 * {@code AuthenticationError("invalid username-password pair or user is disabled.")}, stripping that
 * code, so an app whose credentials are wrong surfaces text the card did not match: a validated card
 * silently failing to fire on the fault class it exists for. Measured on a fixture whose client held
 * the wrong password.
 *
 * <p>Loads the REAL card directory rather than an inline copy, because the point is the shipped
 * content — a test built from a hand-written card would pass while the file stayed wrong.
 */
class RedisAuthSignatureTest {

    private static final Path CARDS = Path.of("..", "ops-knowledge");

    static boolean cardsPresent() {
        return Files.isDirectory(CARDS);
    }

    private static OpsKnowledge.Card redisAuth() {
        return OpsKnowledge.cards(CARDS.toString()).stream()
                .filter(c -> c.path().endsWith("redis-auth-server.md"))
                .findFirst().orElse(null);
    }

    @Test
    @EnabledIf("cardsPresent")
    void theClientSideRenderingOfAWrongPasswordIsMatched() {
        OpsKnowledge.Card c = redisAuth();
        assertNotNull(c, "redis-auth-server.md must load and lint clean");
        String probe = "down: invalid username-password pair or user is disabled.";
        assertNotNull(OpsKnowledge.matchSignature(List.of(c), "cache authstack-cache redis:7-alpine", probe),
                "a client reporting a wrong password must still fire the card");
    }

    @Test
    @EnabledIf("cardsPresent")
    void theServerSideNoauthRenderingStillMatches() {
        // The refstack shape. Adding a signature must never cost an existing match.
        OpsKnowledge.Card c = redisAuth();
        assertNotNull(OpsKnowledge.matchSignature(List.of(c), "redis refstack-redis-1 redis:7-alpine",
                "-NOAUTH Authentication required."), "the NOAUTH path is what R4 was certified on");
    }

    /**
     * The runtime fault and the launch-args fault are INDISTINGUISHABLE in the evidence — both report
     * "Authentication required" — and they need opposite fixes: {@code CONFIG SET} resolves the first
     * and is a band-aid on the second. What separates them is the container's command line, which is
     * why it is now in the matching probe. These two cases must therefore pick DIFFERENT cards, and by
     * signature-hit count rather than by filename tie-break, which a rename would silently flip.
     */
    @Test
    @EnabledIf("cardsPresent")
    void theRuntimeFaultAndTheLaunchArgsFaultPickDifferentCards() {
        // The probe strings here are the REAL ones. An earlier version of this test invented a probe
        // containing "NOAUTH", which gave the validated card 2 hits and the launch-args card 1, so the
        // discrimination looked robust. The refstack app actually reports a PLAIN
        // "Authentication required.", both cards scored 1, and the launch-args card won the tie on
        // FILENAME ORDER — displacing the validated card on the certified stack, where it then matched
        // a card the ladder caps at PROPOSE and the fault went unfixed. Never hand a set-diff a probe
        // you wrote yourself.
        List<OpsKnowledge.Card> all = OpsKnowledge.cards(CARDS.toString());

        var runtime = OpsKnowledge.matchSignature(all, "redis refstack-redis-1 redis:7-alpine",
                "down: Authentication required.\n[\"redis-server\"] [\"docker-entrypoint.sh\"]");
        assertNotNull(runtime);
        assertTrue(runtime.path().endsWith("redis-auth-server.md"),
                "the CERTIFIED runtime case must keep the validated card, got " + runtime.path());

        var launchArgs = OpsKnowledge.matchSignature(all, "cache authstack-cache redis:7-alpine",
                "down: Authentication required.\n[\"sh\",\"-c\",\"exec redis-server --requirepass x\"] null");
        assertNotNull(launchArgs);
        assertTrue(launchArgs.path().endsWith("redis-auth-launch-args.md"),
                "a password on the command line needs the recreate procedure, got " + launchArgs.path());
    }

    @Test
    @EnabledIf("cardsPresent")
    void aHealthyRedisMatchesNeither() {
        assertNull(OpsKnowledge.matchSignature(OpsKnowledge.cards(CARDS.toString()),
                "redis refstack-redis-1 redis:7-alpine",
                "Ready to accept connections\n[\"redis-server\"] null"),
                "the launch-args card must need a FAULT, not merely the presence of --requirepass");
    }

    @Test
    @EnabledIf("cardsPresent")
    void theLaunchArgsCardCarriesALiteralCommandNotATemplate() {
        // The measured reason the model fails this fault: it HAS the concept (it answers correctly that
        // a shell value overrides .env) but emits `docker compose up -e VAR=...`, and `-e` does not
        // exist on `compose up`. A small model copies literals and garbles templates, so the card has
        // to carry a runnable command line, not a description of one.
        OpsKnowledge.Card c = OpsKnowledge.cards(CARDS.toString()).stream()
                .filter(x -> x.path().endsWith("redis-auth-launch-args.md")).findFirst().orElse(null);
        assertNotNull(c);
        assertTrue(c.body().contains("docker compose") && c.body().contains("--force-recreate"),
                "the card must name the actual recreate command");
        assertTrue(c.body().contains("= docker compose"),
                "and must show the shell-variable override form, which is the part the model gets wrong");
    }

    @Test
    @EnabledIf("cardsPresent")
    void theCardIsStillValidatedAndStillRedisScoped() {
        OpsKnowledge.Card c = redisAuth();
        assertEquals("validated", c.status());
        assertEquals(List.of("redis", "valkey"), c.match(),
                "widening the signature must not widen which services the card claims");
    }
}
