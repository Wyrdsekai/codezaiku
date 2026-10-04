package org.codezaiku.loop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The planning turn's reply, as a 27B actually wrote it on 2026-09-30: the plan in the thinking and tool calls as text in the reply
 * (every run logged "0 steps parsed"), and, once the instruction asked for the plan alone, the plan in the reply with the same tool
 * calls after it.
 */
class PlanReplyTest {

    @Test
    void thePlanIsReadFromTheReplyAndTheToolCallsAfterItAreNotSteps() {
        String reply = """
                I'll start by planning this out, then dig into the data.

                PLAN:
                1. Explore the environment and install the dependencies.
                2. Build `measure.py`: find the release frame, print JSON.
                3. Build `evaluate.py` over all the clips.
                4. Write `requirements.txt` and `RESULTS.md`.

                Let me begin.

                <tool_call>
                <function=shell>
                <parameter=command>
                1. not a step
                2. not a step either
                </parameter>
                </function>
                </tool_call>
                """;
        List<String> plan = PlanReply.inReply(reply);
        assertEquals(4, plan.size());
        assertEquals("Explore the environment and install the dependencies.", plan.get(0));
        assertEquals("Write `requirements.txt` and `RESULTS.md`.", plan.get(3));
    }

    @Test
    void aReplyThatIsOnlyToolCallsHoldsNoPlan() {
        assertNull(PlanReply.inReply("I'll look first.\n\n<tool_call>\n<function=shell>\n<parameter=command>\nls -la\n</parameter>\n</function>\n</tool_call>"));
        assertNull(PlanReply.inReply(""));
        assertNull(PlanReply.inReply(null));
    }

    @Test
    void inTheThinkingThePlanIsTheLastNumberedListNotTheOptionsBeforeIt() {
        String thinking = """
                Let me think about the approach.

                Approach options:
                1. Pose estimation to get the shoulder and the wrist.
                2. Optical flow on the arm.

                - the pitcher is small in the frame
                - the camera is behind him

                Let me plan:
                1. Explore data and install deps.
                2. Build measure.py skeleton.
                3. Determine the release frame.
                4. Build evaluate.py.
                5. Write RESULTS.md + requirements.txt.

                Let me start.
                """;
        List<String> plan = PlanReply.inThinking(thinking);
        assertEquals(5, plan.size());
        assertEquals("Explore data and install deps.", plan.get(0));
    }

    @Test
    void aHeadedPlanWinsOverALaterList() {
        String reply = """
                PLAN:
                1. Parse the input.
                2. Compute the totals.
                3. Write the report.

                Risks I see:
                1. The input may be empty.
                2. The totals may overflow.
                """;
        assertEquals(List.of("Parse the input.", "Compute the totals.", "Write the report."), PlanReply.inReply(reply));
    }

    @Test
    void aLongPlanIsCutAtEightStepsAndBulletsAreAPlanOnlyInTheReply() {
        StringBuilder sb = new StringBuilder("PLAN:\n");
        for (int i = 1; i <= 11; i++) sb.append(i).append(". step ").append(i).append('\n');
        assertEquals(PlanReply.MAX_STEPS, PlanReply.inReply(sb.toString()).size());
        String bullets = "Plan:\n- read the file\n- fix the bug\n- run the tests\n";
        assertEquals(3, PlanReply.inReply(bullets).size());
        assertNull(PlanReply.inThinking(bullets));
    }
}
