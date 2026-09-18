package org.codezaiku.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The next turn sees the previous reply, so "do everything but 7" refers to a list the model has in front of it. */
class LastExchangeTest {

    // The store lives under user.home. Point it at the temp dir for the test and restore it after, never clear it.
    private static final String REAL_HOME = System.getProperty("user.home");
    private static ChatSession session(Path root) throws Exception {
        System.setProperty("user.home", root.toString());
       
        Files.createDirectories(root.resolve(".codezaiku"));
        return ChatSession.start(root, "test");
    }
    @org.junit.jupiter.api.AfterEach void home() { System.setProperty("user.home", REAL_HOME); }

    @Test
    void thePreviousAskAndReplyAreInTheNextTurnsContext(@TempDir Path root) throws Exception {
        ChatSession s = session(root);
        assertEquals("", s.lastExchange(6000), "nothing yet");
        s.log("user", "review the project");
        s.log("agent", "## Analysis\n\n1. sleep hypnogram\n2. heart rate heatmap\n...\n7. workout histogram\n\nWant me to build one?");
        s.turnDone();
        s.log("user", "do everything but 7");                        // this turn's ask is logged before the goal is built
        String x = s.lastExchange(6000);
        assertTrue(x.contains("They asked: review the project"), x);
        assertTrue(x.contains("7. workout histogram") && x.contains("You replied:"), x);
        assertFalse(x.contains("do everything but 7"), "the current ask is not the previous turn");
    }

    @Test
    void aLongReplyIsCutInTheMiddleAndKeepsItsEnd(@TempDir Path root) throws Exception {
        ChatSession s = session(root);
        s.log("user", "q");
        s.log("agent", "HEAD " + "x".repeat(9000) + " TAIL-OFFER");
        String x = s.lastExchange(2000);
        assertTrue(x.contains("HEAD") && x.contains("TAIL-OFFER") && x.contains("cut here"), x);
        assertTrue(x.length() < 2400, "bounded: " + x.length());
    }
}
