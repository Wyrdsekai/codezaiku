package org.codezaiku.drive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The two settings for a model's thinking: how hard (CODEZAIKU_REASONING_EFFORT) and how long at most (CODEZAIKU_THINKING_BUDGET). */
class ThinkingBudgetTest {

    @Test
    void theReasoningEffortIsSentAsWrittenAndUnsetSendsNothing() {
        assertEquals("xhigh", DriveClient.reasoningEffort(" XHigh "));
        assertEquals("low", DriveClient.reasoningEffort("low"));
        assertNull(DriveClient.reasoningEffort(null));
        assertNull(DriveClient.reasoningEffort("  "));
    }

    @Test
    void aPositiveNumberIsTheLimitAndAnythingElseIsNoLimit() {
        assertEquals(1024, DriveClient.thinkingBudget("1024"));
        assertEquals(512, DriveClient.thinkingBudget(" 512 "));
        assertEquals(0, DriveClient.thinkingBudget(null));
        assertEquals(0, DriveClient.thinkingBudget(""));
        assertEquals(0, DriveClient.thinkingBudget("off"));
        assertEquals(0, DriveClient.thinkingBudget("-5"));
    }
}
