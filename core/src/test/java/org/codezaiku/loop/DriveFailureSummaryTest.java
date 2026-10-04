package org.codezaiku.loop;

import org.codezaiku.run.ResultDocument;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** When the model server fails call after call, the run says why, and the result stays "failed". */
class DriveFailureSummaryTest {

    @Test
    void aSlowServerIsCalledSlowNotAContextOverflow() {
        var e = new RuntimeException("chat() failed against http://box:8213", new HttpTimeoutException("request timed out"));
        String s = FamiliarLoop.driveFailureSummary(e, "http://box:8213", 8, 7.8, Duration.ofSeconds(300));
        assertTrue(s.startsWith(ResultDocument.DRIVE_UNAVAILABLE), s);
        assertTrue(s.contains("answered too slowly") && s.contains("8 calls in a row ran out of time") && s.contains("waited 300 seconds")
                && s.contains("about 7.8 tokens a second") && s.contains("CODEZAIKU_DRIVE_TIMEOUT_MAX"), s);
        assertFalse(s.contains("context overflow") || s.contains("api.anthropic.com"), "neither the old label nor the address advice: " + s);
        assertEquals("failed", ResultDocument.statusForRun(false, null, s), "the status is what it always was");
    }

    @Test
    void aServerThatDoesNotAnswerIsNamed() {
        var e = new RuntimeException("chat() failed against http://box:8213", new ConnectException("Connection refused"));
        String s = FamiliarLoop.driveFailureSummary(e, "http://box:8213", 8, 0, null);
        assertTrue(s.contains("did not answer 8 times in a row") && s.contains("CODEZAIKU_DRIVE points at it"), s);
        String other = FamiliarLoop.driveFailureSummary(new IllegalStateException("drive HTTP 404: not found"), "https://api.example.com/v1", 8, 0, null);
        assertTrue(other.contains("failed 8 times in a row") && other.contains("takes no path"), other);
        assertEquals("failed", ResultDocument.statusForRun(false, null, other));
        assertEquals("failed", ResultDocument.statusForRun(false, null, ResultDocument.UNRECOVERABLE + " x"), "a real context overflow is unchanged");
    }
}
