package org.codezaiku.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Cross-session memory: appends, recalls newest-first under budget, forgets, declares trims. */
class ChatMemoryTest {

    @Test
    void rememberAndRecallRoundTrip(@TempDir Path tmp) throws Exception {
        ChatMemory m = new ChatMemory(tmp);
        assertEquals("", m.recall(), "no memory is empty string, not a header");
        m.remember("we use spaces, never tabs", "person");
        m.remember("the flaky test is testFooTimeout — rerun before believing it", "model");
        String r = m.recall();
        assertTrue(r.contains("spaces, never tabs"));
        assertTrue(r.contains("(person)"));
        assertTrue(r.contains("(model)"), "who wrote it is part of the record");
        assertTrue(r.startsWith("[project memory"));
    }

    @Test
    void aSecondInstanceSeesTheFirstInstancesMemory(@TempDir Path tmp) throws Exception {
        new ChatMemory(tmp).remember("gson, not jackson", "person");
        // The cross-SESSION property: a fresh object over the same store recalls it.
        assertTrue(new ChatMemory(tmp).recall().contains("gson, not jackson"));
    }

    @Test
    void forgetRemovesByFragment(@TempDir Path tmp) throws Exception {
        ChatMemory m = new ChatMemory(tmp);
        m.remember("alpha fact", "person");
        m.remember("beta fact", "person");
        assertEquals(1, m.forget("alpha"));
        String r = m.recall();
        assertFalse(r.contains("alpha"));
        assertTrue(r.contains("beta"));
    }

    @Test
    void theBudgetKeepsTheNewestAndSaysWhatItDropped(@TempDir Path tmp) throws Exception {
        ChatMemory m = new ChatMemory(tmp);
        for (int i = 0; i < 100; i++) m.remember("entry number " + i + " " + "x".repeat(80), "person");
        String r = m.recall();
        assertTrue(r.contains("entry number 99"), "newest survives");
        assertFalse(r.contains("entry number 0 "), "oldest is what goes");
        assertTrue(r.contains("older entries not shown"), "the trim is declared, not silent");
        assertTrue(r.length() < ChatMemory.RECALL_BUDGET + 500, "budget holds");
    }
}
