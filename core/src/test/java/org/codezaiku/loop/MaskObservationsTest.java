package org.codezaiku.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Masking old tool results is how the loop reclaims context without paying for a summary.
 *
 * <p>The property that matters is WHICH part survives. A build or test failure prints its marker at
 * the END, and the command that produced it sits at the START — so a mask that keeps only a prefix,
 * or replaces the result wholesale, destroys exactly what the model needs and forces a re-run. The
 * previous implementation replaced the whole result with "[elided]" and did precisely that.
 */
class MaskObservationsTest {

    private static final ObjectMapper J = new ObjectMapper();

    private static ArrayNode history(String... contents) {
        ArrayNode h = J.createArrayNode();
        for (String c : contents) {
            ObjectNode m = h.addObject();
            m.put("role", "tool");
            m.put("content", c);
        }
        return h;
    }

    private static int mask(ArrayNode h, int keepRecent) {
        return FamiliarLoop.maskObservations(h, keepRecent);
    }

    @Test void keepsBothTheCommandAtTheStartAndTheFailureAtTheEnd() {
        String out = "$ pytest -q\n" + "noise ".repeat(600) + "\nE   AssertionError: expected 6 got 9";
        ArrayNode h = history(out, "recent");
        int saved = mask(h, 1);

        String got = h.get(0).path("content").asText();
        assertTrue(saved > 0, "should have reclaimed characters");
        assertTrue(got.startsWith("$ pytest -q"), "the command must survive: " + got.substring(0, 40));
        assertTrue(got.endsWith("E   AssertionError: expected 6 got 9"),
                "THE FAILURE MARKER MUST SURVIVE — it is the whole point");
        assertTrue(got.contains("masked"), "the omission must be announced, not silent");
        assertTrue(got.length() < out.length(), "must be strictly smaller");
    }

    /** Running it twice must change nothing, or compaction could loop rewriting the same message. */
    @Test void isIdempotent() {
        ArrayNode h = history("x".repeat(5000), "recent");
        mask(h, 1);
        String once = h.get(0).path("content").asText();
        int second = mask(h, 1);
        assertEquals(0, second, "a second pass must reclaim nothing");
        assertEquals(once, h.get(0).path("content").asText());
    }

    @Test void leavesRecentObservationsUntouched() {
        String big = "y".repeat(5000);
        ArrayNode h = history(big, big, big);
        mask(h, 3);   // keep all three
        for (int i = 0; i < 3; i++) {
            assertEquals(big, h.get(i).path("content").asText(), "index " + i + " must be untouched");
        }
    }

    @Test void leavesSmallResultsAlone() {
        String small = "ok, 3 tests passed";
        ArrayNode h = history(small, "recent");
        assertEquals(0, mask(h, 1));
        assertEquals(small, h.get(0).path("content").asText());
    }

    /** Only tool observations are masked — assistant reasoning is the arc and must survive. */
    @Test void neverTouchesAssistantOrUserMessages() {
        ArrayNode h = J.createArrayNode();
        ObjectNode a = h.addObject();
        a.put("role", "assistant");
        a.put("content", "z".repeat(5000));
        ObjectNode u = h.addObject();
        u.put("role", "user");
        u.put("content", "w".repeat(5000));
        h.addObject().put("role", "tool").put("content", "recent");

        assertEquals(0, mask(h, 1), "nothing to mask: neither message is a tool result");
        assertEquals(5000, h.get(0).path("content").asText().length());
        assertEquals(5000, h.get(1).path("content").asText().length());
    }

    @Test void announcesHowMuchWasOmitted() {
        ArrayNode h = history("a".repeat(4000), "recent");
        mask(h, 1);
        String got = h.get(0).path("content").asText();
        assertTrue(got.matches("(?s).*\\d+ chars omitted.*"), "should state the size dropped: " + got);
        assertFalse(got.contains("[elided to save context"), "the old total-elision text is gone");
    }
}
