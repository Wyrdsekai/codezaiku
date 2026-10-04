package org.codezaiku.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which compaction summaries may replace the transcript, and the checkpoint the harness writes when none may. */
class CheckpointTest {

    private static final ObjectMapper J = new ObjectMapper();

    private static final String GOOD = """
            ## Constraints
            - Python 3.13, no GPU

            ## Progress
            - Done:
              - Installed `opencv-python-headless`, `mediapipe`
              - Wrote `measure.py` (release = fastest wrist frame)
            - In progress:
              - `evaluate.py`
            - Blocked:

            ## Key decisions
            - MediaPipe Pose, not YOLO

            ## Next steps
            - Run `python evaluate.py` and write `RESULTS.md`

            ## Critical context
            - `measure.py <clip>` prints {"arm_angle": float | null}
            """;

    @Test
    void aSummaryWithItsSectionsIsUsable() {
        assertNull(Checkpoint.defect(GOOD, false));
        assertNull(Checkpoint.defect(GOOD, true), "cut off after the sections a worker needs: still usable");
    }

    @Test
    void theNextToolCallWrittenAsTextIsNotASummary() {
        String reply = "<tool_call>\n<function=read_file>\n<parameter=path>\nTASK.md\n</parameter>\n</function>\n</tool_call>";
        assertEquals("a tool call instead of a summary", Checkpoint.defect(reply, false));
        assertEquals("empty", Checkpoint.defect("  ", false));
        assertEquals("empty", Checkpoint.defect(null, false));
    }

    @Test
    void aSummaryCutOffBeforeItsNextStepsIsNotUsable() {
        String cut = GOOD.substring(0, GOOD.indexOf("## Next steps"));
        assertEquals("cut off at the token limit, no section: Next steps", Checkpoint.defect(cut, true));
        assertEquals("no section: Progress, Next steps", Checkpoint.defect("I will now continue by reading the file and then running the tests. ".repeat(4), false));
    }

    @Test
    void aToolCallAfterTheSummaryIsCutAwayAndTheModelsGoalSectionIsDropped() {
        String reply = "## Goal\nAnalyse one video clip.\n\n" + GOOD + "\n<tool_call>\n<function=shell>\n<parameter=command>\nls\n</parameter>\n</function>\n</tool_call>";
        assertNull(Checkpoint.defect(reply, false));
        String text = Checkpoint.withoutSection(Checkpoint.text(reply), "Goal");
        assertFalse(text.contains("<tool_call>"), text);
        assertFalse(text.contains("Analyse one video clip"), text);
        assertTrue(text.startsWith("## Constraints"), text);
        assertTrue(text.contains("## Next steps"), text);
    }

    @Test
    void theHarnessWritesTheCheckpointFromItsRecord() {
        ArrayNode span = J.createArrayNode();
        span.addObject().put("role", "user").put("content", "Begin.");
        span.addObject().put("role", "assistant").putArray("tool_calls").addObject().putObject("function")
                .put("name", "shell").put("arguments", "{\"command\":\"pip install mediapipe\"}");
        span.addObject().put("role", "tool").put("content", "Successfully installed mediapipe-1.0.1");
        span.addObject().put("role", "assistant").putArray("tool_calls").addObject().putObject("function")
                .put("name", "shell").put("arguments", "{\"command\":\"python measure.py data/clips/a.mp4\"}");
        span.addObject().put("role", "tool").put("content", "Traceback (most recent call last):\n  File \"measure.py\", line 12\nValueError: no pose found");

        String cp = Checkpoint.fromRecord(span, "## Progress\n- Done: read the brief");
        assertNotNull(cp);
        assertNull(Checkpoint.defect(cp, false), "the harness's own checkpoint passes the check it replaces");
        assertTrue(cp.startsWith("## Earlier checkpoint (carried forward)\n## Progress\n- Done: read the brief"), cp);
        assertTrue(cp.contains("- shell({\"command\":\"pip install mediapipe\"}) → Successfully installed mediapipe-1.0.1"), cp);
        assertTrue(cp.contains("## Next steps"), cp);
        assertTrue(cp.contains("The last failing output: shell({\"command\":\"python measure.py data/clips/a.mp4\"})"), cp);
        assertTrue(cp.contains("ValueError: no pose found"), cp);
    }
}
