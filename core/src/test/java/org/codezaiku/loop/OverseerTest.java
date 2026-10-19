package org.codezaiku.loop;

import com.fasterxml.jackson.databind.JsonNode;
import org.codezaiku.drive.DriveClient;
import org.codezaiku.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The overseer seat: a stronger model reviews the work, at a finish and once mid-run when the check has stayed red, while the drive
 * plans and does the work. The worker never sees the review request; the review reaches it as a finding beside the harness's own,
 * and once the overseer says DONE the run finishes. The overseer does not write the plan: measured 2026-10-18, its longer plan sent
 * the worker churning to the turn cap on a long task.
 */
class OverseerTest {
    private static String system(JsonNode req) { return req.path("messages").get(0).path("content").asText(""); }
    private static boolean planning(JsonNode req) { return system(req).startsWith("You are about to carry out a coding task"); }
    private static boolean reviewing(JsonNode req) { return system(req).startsWith("You oversee a smaller model"); }

    @Test
    void theOverseerPlansAndReviewsAndTheWorkerDoes(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("app")); Files.writeString(tmp.resolve("app/calc.py"), "def add(a, b):\n    return a - b\n");
        int[] reviews = {0}; int[] step = {0};
        try (StubDrive overseer = new StubDrive(req -> {
                 if (reviewing(req)) { reviews[0]++; return StubDrive.says(reviews[0] == 1 ? "tests/test_calc.py is missing: the task requires a test for add. Write one that fails before the fix." : "DONE"); }
                 return StubDrive.says("the overseer was asked something it should not be: " + system(req).substring(0, 60));
             });
             StubDrive worker = new StubDrive(req -> {
                 if (planning(req)) return StubDrive.says("PLAN:\n1. Fix add in app/calc.py.\n2. Add a test.\n\nFILES:\n- app/calc.py — the fix\n- tests/test_calc.py — the test\n\nDONE CHECK:\nrun: none\n");
                 if (reviewing(req)) return StubDrive.says("THE WORKER WAS ASKED TO REVIEW");
                 String seen = StubDrive.userText(req);
                 if (step[0] == 0) { step[0] = 1; return StubDrive.calls("write_file", "{\"path\":\"app/calc.py\",\"content\":\"def add(a, b):\\n    return a + b\\n\"}"); }
                 if (step[0] == 1 && seen.contains("REVIEW — the overseer")) { step[0] = 2; return StubDrive.calls("write_file", "{\"path\":\"tests/test_calc.py\",\"content\":\"from app.calc import add\\n\\ndef test_add():\\n    assert add(2, 2) == 4\\n\"}"); }
                 return StubDrive.calls("task_done", "{\"summary\":\"fixed add\"}");
             })) {
            String brief = "Fix the bug in app/calc.py: add() returns the wrong sum. " + "The sum must be right for every pair of numbers, and the task is done when the check passes. ".repeat(10)
                    + "\n\n## Deliverables\n- `app/calc.py` adds correctly.\n- a test for it.";
            FamiliarLoop loop = new FamiliarLoop(new DriveClient(worker.url(), "w"), ToolRegistry.standard(tmp), tmp, brief, 16, null, null)
                    .overseer(new DriveClient(overseer.url(), "o"));
            FamiliarLoop.Result r = loop.run();
            assertTrue(r.done(), r.summary());
            assertFalse(overseer.requests.stream().anyMatch(OverseerTest::planning), "the worker writes its own plan");
            assertTrue(overseer.requests.stream().anyMatch(OverseerTest::reviewing), "the overseer reviewed the work");
            assertTrue(worker.requests.stream().anyMatch(OverseerTest::planning), "the worker planned");
            assertFalse(worker.requests.stream().anyMatch(OverseerTest::reviewing), "the worker was never asked to review");
            assertEquals(2, step[0], "the worker answered the overseer's review with the test");
            assertTrue(loop.overseerReviews() >= 2, "a review that found something, then one that said done: " + loop.overseerReviews());
            assertTrue(Files.readString(tmp.resolve("app/calc.py")).contains("a + b"), "the worker did the work");
            assertTrue(Files.exists(tmp.resolve("tests/test_calc.py")), "and the test the overseer asked for");
        }
    }
    /** A finish whose declared check passes is held once for the overseer's look too, then taken when the overseer says DONE. */
    @Test
    void aFinishWhoseCheckPassesGetsTheOverseersOneLook(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("app")); Files.writeString(tmp.resolve("app/calc.py"), "def add(a, b):\n    return a - b\n");
        Files.writeString(tmp.resolve("check.sh"), "grep -q 'return a + b' app/calc.py\n");
        int[] reviews = {0}; int[] step = {0}; int[] finishes = {0};
        try (StubDrive overseer = new StubDrive(req -> {
                 if (reviewing(req)) { reviews[0]++; return StubDrive.says(reviews[0] == 1 ? "app/calc.py has no docstring saying what add returns; the task asks for one." : "DONE"); }
                 return StubDrive.says("not expected");
             });
             StubDrive worker = new StubDrive(req -> {
                 if (planning(req)) return StubDrive.says("PLAN:\n1. Fix add.\n2. Test it.\n\nDONE CHECK:\nrun: sh check.sh\npass: exit 0\n");
                 String seen = StubDrive.userText(req);
                 if (step[0] == 0) { step[0] = 1; return StubDrive.calls("write_file", "{\"path\":\"app/calc.py\",\"content\":\"def add(a, b):\\n    return a + b\\n\"}"); }
                 if (step[0] == 1 && seen.contains("Before finishing: REVIEW")) { step[0] = 2; return StubDrive.calls("write_file", "{\"path\":\"app/calc.py\",\"content\":\"def add(a, b):\\n    \\\"\\\"\\\"The sum.\\\"\\\"\\\"\\n    return a + b\\n\"}"); }
                 finishes[0]++;
                 return StubDrive.calls("task_done", "{\"summary\":\"fixed add\"}");
             })) {
            String brief = "Add a docstring to add() in app/calc.py and fix its sum. " + "The sum must be right, and the task is done when the check passes. ".repeat(10)
                    + "\n\n## Deliverables\n- `app/calc.py` adds correctly, with a docstring.";
            FamiliarLoop loop = new FamiliarLoop(new DriveClient(worker.url(), "w"), ToolRegistry.standard(tmp), tmp, brief, 16, null, null)
                    .overseer(new DriveClient(overseer.url(), "o"));
            FamiliarLoop.Result r = loop.run();
            assertTrue(r.done(), r.summary());
            assertEquals(2, step[0], "the worker answered the overseer's look with the docstring");
            assertTrue(finishes[0] >= 2, "the first finish was held once, a later one taken: " + finishes[0]);
            assertTrue(Files.readString(tmp.resolve("app/calc.py")).contains("The sum."));
        }
    }
    /** A run whose check has stayed red for two thirds of its turns gets the overseer's look mid-run, once, and the worker acts on it. */
    @Test
    void aRunStuckRedGetsTheOverseersLookMidRun(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("app")); Files.writeString(tmp.resolve("app/calc.py"), "def add(a, b):\n    return a - b\n");
        Files.writeString(tmp.resolve("check.sh"), "grep -q 'return a + b' app/calc.py\n");
        int[] reviews = {0}; int[] step = {0};
        try (StubDrive overseer = new StubDrive(req -> {
                 if (reviewing(req)) { reviews[0]++; return StubDrive.says(reviews[0] == 1 ? "app/calc.py still subtracts: the fix is `return a + b`, not `a * b`." : "DONE"); }
                 return StubDrive.says("not expected");
             });
             StubDrive worker = new StubDrive(req -> {
                 if (planning(req)) return StubDrive.says("PLAN:\n1. Fix add.\n2. Run the check.\n\nDONE CHECK:\nrun: sh check.sh\npass: exit 0\n");
                 String seen = StubDrive.userText(req);
                 if (step[0] == 0) { step[0] = 1; return StubDrive.calls("write_file", "{\"path\":\"app/calc.py\",\"content\":\"def add(a, b):\\n    return a * b\\n\"}"); }
                 if (step[0] == 1 && seen.contains("REVIEW — the overseer")) { step[0] = 2; return StubDrive.calls("write_file", "{\"path\":\"app/calc.py\",\"content\":\"def add(a, b):\\n    return a + b\\n\"}"); }
                 if (step[0] == 2) return StubDrive.calls("task_done", "{\"summary\":\"fixed add\"}");
                 return StubDrive.waiting();   // stuck: the wrong fix, the check red, nothing new
             })) {
            String brief = "Fix the bug in app/calc.py: add() returns the wrong sum. " + "The sum must be right, and the task is done when the check passes. ".repeat(10)
                    + "\n\n## Deliverables\n- `app/calc.py` adds correctly.";
            FamiliarLoop loop = new FamiliarLoop(new DriveClient(worker.url(), "w"), ToolRegistry.standard(tmp), tmp, brief, 12, null, null)
                    .overseer(new DriveClient(overseer.url(), "o"));
            FamiliarLoop.Result r = loop.run();
            assertTrue(r.done(), r.summary());
            assertEquals(2, step[0], "the worker acted on the mid-run look");
            assertTrue(Files.readString(tmp.resolve("app/calc.py")).contains("a + b"));
            assertTrue(reviews[0] >= 1, "the overseer looked mid-run");
        }
    }
}
