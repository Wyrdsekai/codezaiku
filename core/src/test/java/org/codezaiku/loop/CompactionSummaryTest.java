package org.codezaiku.loop;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.drive.DriveClient;
import org.codezaiku.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compaction replaces the old messages with a checkpoint, so what it accepts as the checkpoint is the run's memory. The model's next
 * tool call written as text used to be accepted (2026-09-30: ten of twenty-nine compactions in three runs); now it is asked for once
 * more, and after that the harness writes the checkpoint from its own record.
 */
class CompactionSummaryTest {

    private static final String TOOL_CALL = "<tool_call>\n<function=read_file>\n<parameter=path>\nTASK.md\n</parameter>\n</function>\n</tool_call>";
    private static final String GOOD = """
            ## Goal
            Analyse one clip.

            ## Constraints
            - Python only

            ## Progress
            - Done:
              - Wrote `measure.py` and ran it on three clips
            - In progress:
              - `evaluate.py`

            ## Next steps
            - Run `python evaluate.py`

            ## Critical context
            - `measure.py <clip>` prints {"arm_angle": 31.2}
            """;

    /** A history long enough that compaction has to cut: twelve steps, each a shell call and a bulky result. */
    private static ArrayNode history() {
        ArrayNode h = StubDrive.J.createArrayNode();
        h.addObject().put("role", "user").put("content", "Begin.");
        for (int i = 0; i < 12; i++) {
            ObjectNode a = h.addObject().put("role", "assistant");
            ObjectNode c = a.putArray("tool_calls").addObject();
            c.put("id", "c" + i).put("type", "function");
            c.putObject("function").put("name", "shell").put("arguments", "{\"command\":\"python step" + i + ".py\"}");
            h.addObject().put("role", "tool").put("tool_call_id", "c" + i).put("content", "step " + i + " output " + "x".repeat(2400));
        }
        return h;
    }

    private static void compact(FamiliarLoop loop, ArrayNode history) throws Exception {
        Method m = FamiliarLoop.class.getDeclaredMethod("compact", ArrayNode.class);
        m.setAccessible(true);
        m.invoke(loop, history);
    }

    @Test
    void aToolCallForASummaryIsAskedForAgainAndTheSecondOneIsUsed(@TempDir Path tmp) throws Exception {
        int[] n = {0};
        try (StubDrive stub = new StubDrive(req -> StubDrive.says(n[0]++ == 0 ? TOOL_CALL : GOOD))) {
            FamiliarLoop loop = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp, "Build the tool.", 50, null, null);
            ArrayNode h = history();
            int before = h.size();
            compact(loop, h);

            assertEquals(2, stub.requests.size(), "the summary was asked for twice");
            assertFalse(stub.requests.get(0).path("chat_template_kwargs").path("enable_thinking").asBoolean(true),
                    "first without the model's thinking, which is counted against the same token limit");
            assertFalse(stub.requests.get(1).has("chat_template_kwargs"), "then the ordinary way");
            assertTrue(stub.requests.get(1).path("max_tokens").asInt() > stub.requests.get(0).path("max_tokens").asInt(), "with more room");
            String asked = StubDrive.userText(stub.requests.get(0));
            assertTrue(asked.startsWith("THE GOAL of the session"), "the summariser is given the goal");
            assertTrue(asked.contains("Build the tool."));
            assertTrue(asked.strip().endsWith("through \"## Constraints\", within about 600 words."), "the instruction is the last thing the summariser reads");
            assertTrue(h.size() < before, "the old messages are gone");
            String checkpoint = h.get(0).path("content").asText();
            assertTrue(checkpoint.startsWith("[EARLIER WORK"), checkpoint);
            assertTrue(checkpoint.contains("## Goal\nUnchanged: the GOAL at the end of the system prompt"), checkpoint);
            assertFalse(checkpoint.contains("Analyse one clip"), "the summary's own idea of the goal is not kept");
            assertTrue(checkpoint.contains("Wrote `measure.py` and ran it on three clips"), checkpoint);
            assertFalse(checkpoint.contains("<tool_call>"), checkpoint);
        }
    }

    @Test
    void twoUnusableSummariesAndTheHarnessWritesTheCheckpointFromItsRecord(@TempDir Path tmp) throws Exception {
        try (StubDrive stub = new StubDrive(req -> StubDrive.says(TOOL_CALL))) {
            FamiliarLoop loop = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp, "Build the tool.", 50, null, null);
            ArrayNode h = history();
            compact(loop, h);

            assertEquals(2, stub.requests.size(), "asked twice, no more");
            String checkpoint = h.get(0).path("content").asText();
            assertFalse(checkpoint.contains("<tool_call>"), "the tool call is never the checkpoint");
            assertTrue(checkpoint.contains("The harness wrote this part from its own record"), checkpoint);
            assertTrue(checkpoint.contains("- shell({\"command\":\"python step0.py\"}) → step 0 output"), checkpoint);
            assertTrue(checkpoint.contains("## Next steps"), checkpoint);
            assertTrue(checkpoint.contains("## Files"), checkpoint);
        }
    }
}
