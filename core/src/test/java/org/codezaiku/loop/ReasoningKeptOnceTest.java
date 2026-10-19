package org.codezaiku.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.drive.DriveClient;
import org.codezaiku.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On a hosted API none of the model's reasoning goes back on the next request, the shape of 2026-10-16 (GLM-5.3 through OpenRouter,
 * 34 of 40 at eight cents a run). Kept whole it rode along in every request at five times the cost; kept for the latest reply only,
 * the model collapsed. A local drive keeps it while there is room (ShedThinkingTest).
 */
class ReasoningKeptOnceTest {

    private static ObjectNode thinking(ObjectNode reply, int turn) {
        reply.put("reasoning", "thinking on turn " + turn);
        reply.putArray("reasoning_details").addObject().put("type", "reasoning.text").put("text", "thinking on turn " + turn);
        return reply;
    }

    private static int[] run(Path tmp, boolean hosted) throws Exception {
        int[] turn = {0};
        DriveClient.HOSTED_FOR_TESTS = hosted;
        try (StubDrive stub = new StubDrive(req -> {
            turn[0]++;
            if (turn[0] <= 3) return thinking(StubDrive.calls("shell", "{\"command\":\"echo turn " + turn[0] + "\"}"), turn[0]);
            return thinking(StubDrive.calls("task_done", "{\"summary\":\"looked around\"}"), turn[0]);
        })) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp,
                    "Say what is in the project.", 8, null, null).run();
            assertTrue(r.done(), r.summary());
            JsonNode last = stub.requests.get(stub.requests.size() - 1);
            int assistants = 0, withReasoning = 0, lastHasIt = 0;
            for (JsonNode m : last.path("messages")) {
                if (!"assistant".equals(m.path("role").asText())) continue;
                assistants++;
                lastHasIt = m.has("reasoning_details") || m.has("reasoning") ? 1 : 0;
                withReasoning += lastHasIt;
            }
            return new int[]{assistants, withReasoning, lastHasIt};
        } finally { DriveClient.HOSTED_FOR_TESTS = null; }
    }

    @Test
    void onAHostedApiNoReplysReasoningGoesBack(@TempDir Path tmp) throws Exception {
        int[] got = run(tmp, true);
        assertTrue(got[0] >= 3, "the tool-calling replies are in the history: " + got[0]);
        assertEquals(0, got[1], "none of them carries its reasoning: the shape of 2026-10-16, which worked");
    }

    @Test
    void aLocalDriveKeepsEveryReplysReasoningWhileThereIsRoom(@TempDir Path tmp) throws Exception {
        int[] got = run(tmp, false);
        assertEquals(got[0], got[1], "every reply still carries its reasoning (a short run is far below half the window)");
    }
}
