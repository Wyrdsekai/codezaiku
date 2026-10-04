package org.codezaiku.loop;

import org.codezaiku.drive.DriveClient;
import org.codezaiku.tools.AnswerDraftTool;
import org.codezaiku.tools.ToolRegistry;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A research pass that reaches its last turn and finishes with a placeholder gets one text reply, and that text is the answer. A pass
 * before a coding task, told it had one turn left, thought 7,000 characters and called task_done("placeholder — will not be final")
 * (2026-10-04); the notes fell back to the start of each page it had read.
 */
class ResearchDeadlineAnswerTest {

    private static final String QUESTION = "A coding task is about to be built. Before it is, find out from the web: "
            + "1. Which open-source pose model does well on broadcast video on a CPU? 2. How is the arm angle at release defined by the source the task is judged against? "
            + "3. How is the release frame found in practice, and what usually goes wrong? " + "For each question: what is used in practice and why. ".repeat(12);

    @Test
    void anAnswerThatIsAStatusOfWorkFromAPassThatCannotBuild() {
        assertTrue(FamiliarLoop.statusAnswer("Built and verified the arm-angle measurement tool end-to-end.\n\nROOT CAUSE — …"));
        assertTrue(FamiliarLoop.statusAnswer("Research + build status: measure.py/evaluate.py pipeline exists (MediaPipe BlazePose pose → …"));
        assertTrue(FamiliarLoop.statusAnswer("Research complete (answer below); build of measure.py/evaluate.py in progress. Q1: MediaPipe Pose …"));
        assertFalse(FamiliarLoop.statusAnswer("MediaPipe Pose (BlazePose, Apache-2.0) is the practical CPU choice; it is built on a detector plus a landmark model. Savant's arm angle is …"));
        assertFalse(FamiliarLoop.statusAnswer("Q1: practitioners detect release at the wrist-velocity peak (Fleisig 2012); the angle is implemented in 2D as atan2 …"));
        assertFalse(FamiliarLoop.statusAnswer(""));
    }

    @Test
    void aStatusAnswerOnTheLastTurnGetsTheTextReplyToo(@TempDir Path tmp) throws Exception {
        try (StubDrive stub = new StubDrive(req -> {
            if (StubDrive.userText(req).contains("Time is up")) return StubDrive.says("MediaPipe Pose on the CPU; release at the wrist's velocity peak.\n\nSOURCES:\nhttps://example.org/fleisig");
            return StubDrive.calls("task_done", "{\"summary\":\"Built and verified the arm-angle measurement tool end-to-end. The pipeline exists and runs." + "x".repeat(500) + "\"}");
        })) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.researchWeb(QUESTION, new AnswerDraftTool()), tmp, QUESTION, 1, null, null).research().run();
            assertTrue(r.summary().contains("wrist's velocity peak") && !r.summary().startsWith("Built and verified"), "the status from a pass with no tools to build with is not the answer: " + r.summary());
        }
    }

    @Test
    void aPlaceholderOnTheLastTurnGetsOneTextReplyThatIsTheAnswer(@TempDir Path tmp) throws Exception {
        try (StubDrive stub = new StubDrive(req -> {
            if (StubDrive.userText(req).contains("Time is up")) return StubDrive.says("MediaPipe Pose on the CPU; the angle is shoulder to ball above horizontal, 0 to 90; release at the wrist's velocity peak.\n\nSOURCES:\nhttps://example.org/savant");
            return StubDrive.calls("task_done", "{\"summary\":\"placeholder — will not be final\"}");
        })) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.readOnly(tmp, null), tmp, QUESTION, 1, null, null).research().run();
            assertTrue(r.done(), r.summary());
            assertTrue(r.summary().contains("release at the wrist's velocity peak") && r.summary().contains("https://example.org/savant"), "the text reply is the answer: " + r.summary());
            assertFalse(r.summary().contains("placeholder"), r.summary());
            // the deadline turn offered the finishing tool alone; the text turn followed it (the run's other calls come before and after)
            int epilogue = -1;
            for (int i = 0; i < stub.requests.size(); i++) if (StubDrive.userText(stub.requests.get(i)).contains("Time is up")) { epilogue = i; break; }
            assertTrue(epilogue > 0, "one text turn was asked for");
            JsonNode asked = stub.requests.get(epilogue);
            assertTrue(StubDrive.userText(asked).contains("Time is up, and the answer is what you pass on") && StubDrive.userText(asked).contains("do not call any tool"), StubDrive.userText(asked));
            assertEquals(1, stub.requests.get(epilogue - 1).path("tools").size(), "the turn before it offered the finishing tool alone");
        }
    }
}
