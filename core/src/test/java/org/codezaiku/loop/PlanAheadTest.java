package org.codezaiku.loop;

import com.fasterxml.jackson.databind.JsonNode;
import org.codezaiku.drive.DriveClient;
import org.codezaiku.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A multi-step task's plan is asked for before the first turn, in a call of its own, and pinned where every turn sees it. It used to
 * be the loop's first turn; on a 27B that turn never yielded a plan the harness could read (2026-09-30).
 */
class PlanAheadTest {

    private static final String GOAL = "Build a small report tool. " + "It reads the rows, adds them up and writes the totals. ".repeat(14)
            + "\n\n## Input\nrows.csv\n\n## Output\nThe totals.";

    private static boolean planningCall(JsonNode req) {
        return req.path("messages").get(0).path("content").asText().startsWith("You are about to carry out a coding task");
    }

    /** The plan's FILES and REVIEW FOCUS blocks are read and pinned beside the steps; a reply without them pins the steps alone. */
    @Test
    void theFilesAndTheReviewFocusArePinnedBesideTheSteps(@TempDir Path tmp) throws Exception {
        assertEquals(List.of("app/parser.py — the parser and its error", "tests/test_parser.py — the cases below"), FamiliarLoop.headedList(
                "PLAN:\n1. a\n2. b\n\nFILES:\n- app/parser.py — the parser and its error\n- tests/test_parser.py — the cases below\n\nREVIEW FOCUS:\n- an empty file\n\nDONE CHECK:\nrun: none\n", "files"));
        assertEquals(List.of("an empty file"), FamiliarLoop.headedList("REVIEW FOCUS:\n- an empty file\n\nDONE CHECK:\nrun: none\n", "review focus"));
        assertEquals(List.of(), FamiliarLoop.headedList("PLAN:\n1. a\n2. b\n", "files"));
        try (StubDrive stub = new StubDrive(req -> planningCall(req)
                ? StubDrive.says("PLAN:\n1. Read the rows.\n2. Add them up.\n3. Write the totals and the tests.\n\nFILES:\n- app/sum.py — the adder\n- tests/test_sum.py — the cases\n\nREVIEW FOCUS:\n- a row with no number\n- an empty file\n\nDONE CHECK:\nrun: none\n")
                : StubDrive.calls("task_done", "{\"summary\":\"done\"}"))) {
            new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.readOnly(tmp, null), tmp, GOAL, 8, null, null).run();
            String text = stub.requests.get(1).path("messages").get(0).path("content").asText();
            assertTrue(text.contains("FILES:\n  - app/sum.py — the adder"), "the files are pinned with the plan: " + text.substring(Math.max(0, text.indexOf("PLAN ("))));
            assertTrue(text.contains("REVIEW FOCUS (each gets a test in the step that owns it):") && text.contains("a row with no number"), "and the cases owed a test");
        }
    }

    /** A source file the run changed that the plan's FILES did not name is said once at the verify step; a planned file is not. */
    @Test
    void aFileThePlanDidNotNameIsNamedAtTheVerifyStep(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("app"));
        int[] step = {0};
        try (StubDrive stub = new StubDrive(req -> {
            if (planningCall(req)) return StubDrive.says("PLAN:\n1. Read the rows.\n2. Add them up.\n3. Write the totals and the tests.\n\nFILES:\n- app/sum.py — the adder\n\nREVIEW FOCUS:\n- an empty file\n\nDONE CHECK:\nrun: none\n");
            if (!req.has("tools")) return StubDrive.says("DONE CHECK:\nrun: none\n");
            if (step[0] == 0) { step[0] = 1; return StubDrive.calls("write_file", "{\"path\":\"app/sum.py\",\"content\":\"def add(a, b):\\n    return a + b\\n\"}"); }
            if (step[0] == 1) { step[0] = 2; return StubDrive.calls("write_file", "{\"path\":\"app/extra.py\",\"content\":\"x = 1\\n\"}"); }
            return StubDrive.calls("task_done", "{\"summary\":\"done\"}");
        })) {
            new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp, GOAL, 12, null, null).run();
            JsonNode last = stub.requests.get(stub.requests.size() - 1);
            int said = 0;
            for (JsonNode m : last.path("messages")) if ("user".equals(m.path("role").asText()) && m.path("content").asText("").contains("the run also changed `app/extra.py`")) said++;
            assertEquals(1, said, "said once, naming the file the plan did not");
        }
    }

    @Test
    void thePlanComesFromItsOwnCallAndIsPinnedFromTheFirstTurn(@TempDir Path tmp) throws Exception {
        try (StubDrive stub = new StubDrive(req -> planningCall(req)
                ? StubDrive.says("PLAN:\n1. Read the rows.\n2. Add them up.\n3. Write the totals and the tests.")
                : StubDrive.calls("task_done", "{\"summary\":\"done\"}"))) {
            new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.readOnly(tmp, null), tmp, GOAL, 8, null, null).run();

            JsonNode plan = stub.requests.get(0);
            assertTrue(planningCall(plan), "the first request is the planning call");
            assertFalse(plan.has("tools"), "it offers no tools");
            assertFalse(plan.path("chat_template_kwargs").path("enable_thinking").asBoolean(true), "and asks for no thinking");
            assertTrue(StubDrive.userText(plan).startsWith("THE TASK:\nBuild a small report tool."));

            JsonNode turn1 = stub.requests.get(1);
            assertFalse(planningCall(turn1));
            assertEquals("required", turn1.path("tool_choice").asText(), "the first turn is a working turn");
            String system = turn1.path("messages").get(0).path("content").asText();
            assertTrue(system.contains("PLAN (the outline for this task; do every step before task_done):\n  1. Read the rows.\n  2. Add them up.\n  3. Write the totals and the tests."),
                    "the plan is pinned, with no claim about which step the run is on");
            assertTrue(StubDrive.userText(turn1).contains("The PLAN in the system prompt is the outline for it"), StubDrive.userText(turn1));
        }
    }

    @Test
    void aServerThatAnswersTheShortCallWithNothingIsAskedTheOrdinaryWayAndThePlanIsReadFromItsThinking(@TempDir Path tmp) throws Exception {
        int[] planning = {0};
        try (StubDrive stub = new StubDrive(req -> {
            if (!planningCall(req)) return StubDrive.calls("task_done", "{\"summary\":\"done\"}");
            return planning[0]++ < 2 ? StubDrive.says("")
                    : StubDrive.says("").put("reasoning_content", "Options:\n1. one pass\n2. two passes\n\nLet me plan:\n1. Read the rows.\n2. Add them up.\n3. Write the totals.");
        })) {
            new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.readOnly(tmp, null), tmp, GOAL, 8, null, null).run();

            assertEquals(3, planning[0], "asked three times: without thinking, that once more, then the ordinary way");
            assertTrue(stub.requests.get(1).has("chat_template_kwargs"), "the no-thinking call is repeated as it was");
            assertFalse(stub.requests.get(2).has("chat_template_kwargs"), "the third planning call leaves thinking as the server has it");
            String system = stub.requests.get(3).path("messages").get(0).path("content").asText();
            assertTrue(system.contains("  1. Read the rows.\n  2. Add them up.\n  3. Write the totals."), "the plan came from the thinking");
            assertFalse(system.contains("two passes"), "the options weighed before it are not the plan");
        }
    }

    /** A planning call that fails (the server ran out of time) is made once more; the plan from the second one is the run's plan. */
    @Test
    void aPlanningCallThatFailsIsMadeOnceMore(@TempDir Path tmp) throws Exception {
        int[] planning = {0};
        try (StubDrive stub = new StubDrive(req -> {
            if (!planningCall(req)) return StubDrive.calls("task_done", "{\"summary\":\"done\"}");
            planning[0]++;
            if (planning[0] <= 2) return StubDrive.says("");                                // the no-thinking call answers with nothing, twice
            if (planning[0] == 3) return StubDrive.refuses(400, "{\"error\":\"too busy for this request\"}");   // the ordinary call fails (a 5xx the drive retries by itself)
            return StubDrive.says("PLAN:\n1. Read the rows.\n2. Add them up.\n3. Write the totals.\n\nDONE CHECK:\nrun: none\n");
        })) {
            new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.readOnly(tmp, null), tmp, GOAL, 8, null, null).run();
            JsonNode turn1 = null;
            for (JsonNode req : stub.requests) if (!planningCall(req)) { turn1 = req; break; }
            String system = turn1.path("messages").get(0).path("content").asText();
            assertTrue(system.contains("  1. Read the rows.\n  2. Add them up.\n  3. Write the totals."), "the plan came from the call made once more");
        }
    }

    @Test
    void withoutAPlanTheRunStartsAndSaysSoInItsFirstMessage(@TempDir Path tmp) throws Exception {
        try (StubDrive stub = new StubDrive(req -> planningCall(req) ? StubDrive.says("Sure, I will get started.") : StubDrive.calls("task_done", "{\"summary\":\"done\"}"))) {
            new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.readOnly(tmp, null), tmp, GOAL, 8, null, null).run();
            JsonNode turn1 = stub.requests.get(2);
            assertFalse(turn1.path("messages").get(0).path("content").asText().contains("\n\nPLAN ("));
            assertTrue(StubDrive.userText(turn1).contains("Work in vertical slices, each a real, runnable increment."));
        }
    }

    @Test
    void aShortTaskHasNoPlanningCall(@TempDir Path tmp) throws Exception {
        try (StubDrive stub = new StubDrive(req -> StubDrive.calls("task_done", "{\"summary\":\"done\"}"))) {
            new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.readOnly(tmp, null), tmp, "Fix the typo in the README.", 8, null, null).run();
            for (JsonNode req : stub.requests) assertFalse(planningCall(req));
        }
    }
}
