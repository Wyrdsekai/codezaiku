package org.codezaiku.ops;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit of promotion against its claim: a candidate earns {@code validated} only through repeated
 * verified reuse, and nothing else about the card changes.
 *
 * <p>The claim itself held on every probe. What did not was what the claim does not mention: promotion
 * is a read-modify-write on a file other runs may be touching at the same moment — {@code serve}
 * dispatches concurrent /fix requests and {@code watch} runs unattended. Twenty concurrent promotions
 * of one card lost 19 counts (harmless) and DESTROYED the card (body gone, status line gone, no longer
 * lint-clean) — after which it is rejected at load and the fault class silently loses its procedure.
 *
 * <p>Promotion is what grants UNATTENDED, so the file it writes has to survive being written.
 */
class OpsPromoteAuditTest {

    private static final String CARD =
            "match: redis, valkey\nsignature: --requirepass\npush: rescue\nstatus: candidate\n"
          + "platform: docker\n# Redis auth — fix procedure\n1. Do the thing.\n2. Then conclude (submit).\n";

    private static String field(Path p, String key) throws Exception {
        for (String l : Files.readString(p).split("\n")) {
            if (l.toLowerCase().startsWith(key)) return l.trim();
        }
        return "(absent)";
    }

    @Test
    void aCandidateEarnsValidatedOnlyAtTheThreshold(@TempDir Path dir) throws Exception {
        Path card = dir.resolve("c.md");
        Files.writeString(card, CARD);

        OpsPromote.recordReuse(card.toString(), 3);
        assertEquals("reuses: 1", field(card, "reuses:"));
        assertEquals("status: candidate", field(card, "status:"));

        OpsPromote.recordReuse(card.toString(), 3);
        OpsPromote.recordReuse(card.toString(), 3);
        assertEquals("status: validated", field(card, "status:"));
    }

    @Test
    void promotionPreservesEverythingElseAboutTheCard(@TempDir Path dir) throws Exception {
        Path card = dir.resolve("c.md");
        Files.writeString(card, CARD);
        for (int i = 0; i < 3; i++) OpsPromote.recordReuse(card.toString(), 3);

        assertEquals("platform: docker", field(card, "platform:"));
        assertEquals("push: rescue", field(card, "push:"));
        assertTrue(Files.readString(card).contains("1. Do the thing."), "the procedure must survive");
        assertNull(OpsKnowledge.lintViolation(Files.readString(card)),
                "a card promotion corrupts is rejected at load, so the fault class loses its procedure");
    }

    @Test
    void anAlreadyValidatedCardIsLeftAlone(@TempDir Path dir) throws Exception {
        Path card = dir.resolve("c.md");
        Files.writeString(card, CARD.replace("status: candidate", "status: validated"));
        String before = Files.readString(card);
        OpsPromote.recordReuse(card.toString(), 3);
        assertEquals(before, Files.readString(card));
    }

    @Test
    void concurrentPromotionsNeitherCorruptTheCardNorLoseCounts(@TempDir Path dir) throws Exception {
        Path card = dir.resolve("c.md");
        Files.writeString(card, CARD);

        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try { go.await(); OpsPromote.recordReuse(card.toString(), 1000); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        String body = Files.readString(card);
        assertEquals("reuses: " + n, field(card, "reuses:"), "read-modify-write lost counts");
        assertNull(OpsKnowledge.lintViolation(body), "concurrent writes corrupted the card");
        assertTrue(body.contains("1. Do the thing."), "the procedure was destroyed");
        assertEquals(1, body.lines().filter(l -> l.startsWith("status:")).count());
        assertEquals(1, body.lines().filter(l -> l.startsWith("reuses:")).count());
    }

    @Test
    void aMissingOrUnreadableTargetIsANoOpRatherThanAFailure(@TempDir Path dir) throws Exception {
        OpsPromote.recordReuse(dir.resolve("nope.md").toString(), 3);
        OpsPromote.recordReuse(null, 3);
        OpsPromote.recordReuse("", 3);

        Path notACard = dir.resolve("x.md");
        Files.writeString(notACard, "not a card at all\n");
        OpsPromote.recordReuse(notACard.toString(), 3);
        assertTrue(!Files.readString(notACard).contains("status: validated"),
                "an arbitrary file must not become a validated card");
    }
}
