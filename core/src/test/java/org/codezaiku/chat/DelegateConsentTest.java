package org.codezaiku.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Delegation is a consented act in every mode short of yolo — including auto-edit, which the
 * person granted for edits, not for autonomous agents. And the standing key carries the task
 * text: "always" for one delegated task must not silently cover a different one.
 */
class DelegateConsentTest {

    private static final ObjectMapper J = new ObjectMapper();

    private static ChatConsent consent(ChatConsent.Mode mode, ChatConsent.Answer answer,
                                       AtomicInteger asks, Path store) {
        return new ChatConsent(mode, (what, preview) -> {
            asks.incrementAndGet();
            return answer;
        });
    }

    @Test
    void delegateAsksEvenInAutoEdit(@TempDir Path tmp) {
        AtomicInteger asks = new AtomicInteger();
        ChatConsent c = consent(ChatConsent.Mode.AUTO_EDIT, ChatConsent.Answer.YES, asks, tmp);
        assertNull(c.permit("delegate", J.createObjectNode().put("task", "add a stats command")));
        assertEquals(1, asks.get(), "auto-edit covers edits, not autonomous agents");
    }

    @Test
    void aDeclineComesBackAsAnObservation(@TempDir Path tmp) {
        ChatConsent c = consent(ChatConsent.Mode.ASK, ChatConsent.Answer.NO, new AtomicInteger(), tmp);
        String denial = c.permit("delegate", J.createObjectNode().put("task", "rewrite everything"));
        assertNotNull(denial);
        assertTrue(denial.contains("declined"));
    }

    @Test
    void standingAnswersKeyOnTheTaskText(@TempDir Path tmp) {
        AtomicInteger asks = new AtomicInteger();
        ChatConsent c = consent(ChatConsent.Mode.ASK, ChatConsent.Answer.YES_ALWAYS, asks, tmp);
        assertNull(c.permit("delegate", J.createObjectNode().put("task", "task A")));
        assertNull(c.permit("delegate", J.createObjectNode().put("task", "task A")));
        assertEquals(1, asks.get(), "the always covered the SAME task");
        assertNull(c.permit("delegate", J.createObjectNode().put("task", "task B — different")));
        assertEquals(2, asks.get(), "a different task asks again");
    }

    @Test
    void thePreviewShowsTheFullInstructions(@TempDir Path tmp) {
        List<String> p = ChatPreview.of("delegate", J.createObjectNode()
                .put("task", "add a stats command with tests"));
        assertTrue(String.join("\n", p).contains("add a stats command with tests"));
        assertTrue(String.join("\n", p).toLowerCase().contains("sub-agent"));
    }
}
