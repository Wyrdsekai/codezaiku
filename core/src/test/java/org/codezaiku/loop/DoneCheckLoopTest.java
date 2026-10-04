package org.codezaiku.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.drive.DriveClient;
import org.codezaiku.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The harness runs the goal's own check and the finish follows it. A run that writes a program the check judges gets the harness's
 * verdict as a note; when the check passes the turns left are for the files and task_done; a change after the pass that no
 * longer passes is put back; a finish whose check fails is sent back once.
 */
class DoneCheckLoopTest {

    private static final String GOAL = "Build a scorer. " + "Make the score as high as it can go, and the task is done when the score is at least 3. ".repeat(10)
            + "\n\n## Deliverables\n- `scorer.sh` prints `score: <number>`.\n- `RESULTS.md` holds the number.";

    private static boolean planningCall(JsonNode req) {
        String system = req.path("messages").get(0).path("content").asText();
        return system.startsWith("You are about to carry out a coding task") || system.startsWith("You compare a declared check");
    }

    private static boolean secondLook(JsonNode req) {
        return req.path("messages").get(0).path("content").asText().startsWith("You compare a declared check");
    }

    private static ObjectNode plan() {
        return StubDrive.says("PLAN:\n1. Write scorer.sh.\n2. Raise the score.\n3. Write RESULTS.md.\n\nDONE CHECK:\nrun: sh scorer.sh\npass: score >= 3\n");
    }

    private static ObjectNode write(String path, String content) {
        return StubDrive.calls("write_file", "{\"path\":\"" + path + "\",\"content\":\"" + content.replace("\n", "\\n") + "\"}");
    }

    private static List<String> toolNames(JsonNode req) {
        List<String> names = new ArrayList<>();
        for (JsonNode t : req.path("tools")) names.add(t.path("function").path("name").asText());
        return names;
    }

    /** The most times the note appears in any one request: compaction may have folded it away by the last one. */
    private static int countNotes(StubDrive stub, String text) {
        int most = 0;
        for (JsonNode req : stub.requests) {
            String all = StubDrive.userText(req);
            int n = 0;
            for (int at = all.indexOf(text); at >= 0; at = all.indexOf(text, at + 1)) n++;
            most = Math.max(most, n);
        }
        return most;
    }

    @Test
    void theHarnessRunsTheCheckAndThePassingRunHandsOver(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("note.txt"), "hello");
        int[] step = {0}, finishes = {0};
        try (StubDrive stub = new StubDrive(req -> {
            if (planningCall(req)) return plan();
            String seen = StubDrive.userText(req);
            if (step[0] == 0) { step[0] = 1; return write("scorer.sh", "echo score: 1\n"); }
            if (step[0] == 1 && seen.contains("does not pass yet")) { step[0] = 2; return write("scorer.sh", "echo score: 5\n"); }
            if (seen.contains("PASSES")) {
                if (step[0] < 3) { step[0] = 3; return write("RESULTS.md", "score: 5\n"); }
                finishes[0]++;
                return StubDrive.calls("task_done", "{\"summary\":\"the score is 5\"}");
            }
            return StubDrive.calls("read_file", "{\"path\":\"note.txt\"}");
        })) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp, GOAL, 60, null, null).run();

            assertEquals(1, finishes[0], "with the check passing, the first task_done is the finish: nothing sends it back");
            assertEquals(0, countNotes(stub, "VERIFY it before finishing"), "no round of self-verification after a check that passes");

            JsonNode turn1 = null;
            for (JsonNode req : stub.requests) if (req.has("tools")) { turn1 = req; break; }
            String system = turn1.path("messages").get(0).path("content").asText();
            assertTrue(system.contains("DONE CHECK (declared from the goal"), "pinned from the first turn");
            assertTrue(system.contains("run: `sh scorer.sh`\n pass when: score >= 3\n not run yet"), system.substring(system.indexOf("DONE CHECK")));
            assertEquals(1, countNotes(stub, "does not pass yet"), "the first version's verdict reached the model once");
            assertEquals(1, countNotes(stub, "The goal's own check passes: the work is done"), "and the passing one");
            JsonNode last = stub.requests.get(stub.requests.size() - 1);
            assertEquals(List.of("read_file", "write_file", "edit_file", "shell", "task_done"), toolNames(last), "after the pass, the hand-over tools only, the shell among them");
            assertTrue(r.done());
            assertTrue(r.summary().endsWith("Done check, run by the harness: `sh scorer.sh` → exit 0; score 5 (needs >= 3: yes) — PASSES"), r.summary());
            assertFalse(r.summary().contains("not written"), r.summary());
        }
    }

    /**
     * A check with a prepare command: the harness runs the prepare program once, before the check, tells the model how it went,
     * and does not run it again when only the checked program changes.
     */
    @Test
    void thePrepareProgramRunsOnceBeforeTheCheckAndNotAgainForAChangeElsewhere(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("note.txt"), "hello");
        int[] step = {0};
        try (StubDrive stub = new StubDrive(req -> {
            if (planningCall(req)) return secondLook(req) ? StubDrive.says("DONE CHECK:\nrun: sh scorer.sh\npass: score >= 3\n")
                    : StubDrive.says("PLAN:\n1. Write prepare.sh.\n2. Write scorer.sh.\n3. Write RESULTS.md.\n\nDONE CHECK:\nrun: sh scorer.sh\npass: score >= 3\nprepare: sh prepare.sh\n");
            String seen = StubDrive.userText(req);
            if (step[0] == 0) { step[0] = 1; return write("prepare.sh", "echo ran >> prepared.txt\necho 5 > stored.txt\n"); }
            if (step[0] == 1) { step[0] = 2; return write("scorer.sh", "echo score: 1\n"); }
            if (step[0] == 2 && seen.contains("does not pass yet")) { step[0] = 3; return write("scorer.sh", "echo score: $(cat stored.txt)\n"); }
            if (seen.contains("PASSES")) {
                if (step[0] < 4) { step[0] = 4; return write("RESULTS.md", "score: 5\n"); }
                return StubDrive.calls("task_done", "{\"summary\":\"the score is 5\"}");
            }
            return StubDrive.calls("read_file", "{\"path\":\"note.txt\"}");
        })) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp, GOAL, 80, null, null).run();

            assertTrue(r.done(), r.summary());
            assertTrue(r.summary().contains("score 5 (needs >= 3: yes) — PASSES"), r.summary());
            assertEquals(1, Files.readAllLines(tmp.resolve("prepared.txt")).size(), "prepare ran once, though scorer.sh changed after it");
            assertEquals(1, countNotes(stub, "PREPARE, run by the harness"), "and the model was told how it went");
            assertTrue(countNotes(stub, "What it stored is on disk now") == 1);
            JsonNode turn1 = null;
            for (JsonNode req : stub.requests) if (req.has("tools")) { turn1 = req; break; }
            String system = turn1.path("messages").get(0).path("content").asText();
            assertTrue(system.contains(" prepare: `sh prepare.sh` — does the check's slow step once") && system.contains("`prepare.sh` is not written yet"), system.substring(system.indexOf("DONE CHECK")));
        }
    }

    /** A slow check with no prepare command is answered once with the offer of one, named after the check's script; nothing runs until that program is written. */
    @Test
    void aSlowCheckIsOfferedAPrepareProgramAndTheHarnessRunsItWhenItIsWritten(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("note.txt"), "hello");
        int[] step = {0};
        try (StubDrive stub = new StubDrive(req -> {
            if (planningCall(req)) return plan();
            String seen = StubDrive.userText(req);
            if (step[0] == 0) { step[0] = 1; return write("scorer.sh", "echo score: 1\n"); }
            if (step[0] == 1 && seen.contains("The harness runs `sh prepare.sh` when `prepare.sh` is written")) { step[0] = 2; return write("prepare.sh", "echo ran >> prepared.txt\necho 5 > stored.txt\n"); }
            if (step[0] == 2) { step[0] = 3; return write("scorer.sh", "echo score: $(cat stored.txt)\n"); }
            if (seen.contains("PASSES")) {
                if (step[0] < 4) { step[0] = 4; return write("RESULTS.md", "score: 5\n"); }
                return StubDrive.calls("task_done", "{\"summary\":\"the score is 5\"}");
            }
            return StubDrive.calls("read_file", "{\"path\":\"note.txt\"}");
        })) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp, GOAL, 80, null, null).slowCheckAfter(-1).run();

            assertTrue(r.done(), r.summary());
            assertEquals(4, step[0], "the offer reached the model and it wrote the program");
            assertTrue(r.summary().contains("score 5 (needs >= 3: yes) — PASSES"), r.summary());
            assertEquals(1, Files.readAllLines(tmp.resolve("prepared.txt")).size());
            assertEquals(1, countNotes(stub, "into a program of its own, `prepare.sh`"), "offered once");
        }
    }

    /** A program written again with the same text is the same program: the check that passed stands, and is not run a second time. */
    @Test
    void aProgramWrittenAgainUnchangedDoesNotRunTheCheckAgain(@TempDir Path tmp) throws Exception {
        String scorer = "echo ran >> runs.txt\necho score: 5\n";
        int[] step = {0};
        try (StubDrive stub = new StubDrive(req -> {
            if (planningCall(req)) return plan();
            String seen = StubDrive.userText(req);
            if (step[0] == 0) { step[0] = 1; return write("scorer.sh", scorer); }
            if (seen.contains("PASSES")) {
                if (step[0] == 1) { step[0] = 2; return write("scorer.sh", scorer); }   // the same text, a new time on the file
                if (step[0] == 2) { step[0] = 3; return write("RESULTS.md", "score: 5\n"); }
                return StubDrive.calls("task_done", "{\"summary\":\"done\"}");
            }
            return StubDrive.calls("read_file", "{\"path\":\"scorer.sh\"}");
        })) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp, GOAL, 60, null, null).run();
            assertTrue(r.done());
            assertEquals(3, step[0], "the program was written a second time");
            assertEquals(1, Files.readAllLines(tmp.resolve("runs.txt")).size(), "the check ran once: " + Files.readString(tmp.resolve("runs.txt")));
        }
    }

    @Test
    void aChangeAfterThePassThatNoLongerPassesIsPutBack(@TempDir Path tmp) throws Exception {
        int[] step = {0};
        try (StubDrive stub = new StubDrive(req -> {
            if (planningCall(req)) return plan();
            String seen = StubDrive.userText(req);
            if (step[0] == 0) { step[0] = 1; return write("scorer.sh", "echo score: 5\n"); }
            if (seen.contains("PASSES")) {
                if (step[0] == 1) { step[0] = 2; return write("scorer.sh", "echo score: 0\n"); }   // "improving" after the pass
                if (step[0] == 2) { step[0] = 3; return write("RESULTS.md", "score: 5\n"); }
                return StubDrive.calls("task_done", "{\"summary\":\"done\"}");
            }
            return StubDrive.calls("read_file", "{\"path\":\"scorer.sh\"}");
        })) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp, GOAL, 60, null, null).run();
            assertTrue(r.done());
            assertEquals("echo score: 5\n", Files.readString(tmp.resolve("scorer.sh")), "the version that passed is what is left");
            assertTrue(r.summary().contains("the harness put back the version that passed"), r.summary());
        }
    }

    @Test
    void theBestScoringVersionIsKeptAndShippedWhenTheRunEndsOnAWorseOne(@TempDir Path tmp) throws Exception {
        int[] step = {0};
        try (StubDrive stub = new StubDrive(req -> {
            if (planningCall(req)) return StubDrive.says("PLAN:\n1. Write scorer.sh.\n2. Raise the score.\n\nDONE CHECK:\nrun: sh scorer.sh\npass: score >= 9\n");
            String seen = StubDrive.userText(req);
            if (step[0] == 0) { step[0] = 1; return write("scorer.sh", "echo score: 5\n"); }
            if (step[0] == 1 && seen.contains("best run of the check so far")) { step[0] = 2; return write("scorer.sh", "echo score: 1\n"); }
            if (step[0] == 2 && seen.contains("WORSE than the best run so far")) { step[0] = 3; return StubDrive.calls("task_done", "{\"summary\":\"stopping here\"}"); }
            if (step[0] == 3) return StubDrive.calls("task_done", "{\"summary\":\"stopping here\"}");
            return StubDrive.calls("read_file", "{\"path\":\"scorer.sh\"}");
        })) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp, GOAL, 80, null, null).run();
            assertTrue(r.done());
            assertEquals("echo score: 5\n", Files.readString(tmp.resolve("scorer.sh")), "the best-scoring version is what is left");
            assertTrue(r.summary().contains("score 5 (needs >= 9: NO)"), r.summary());
            assertTrue(r.summary().contains("put back the best-scoring version"), r.summary());
            assertEquals(1, countNotes(stub, "This is WORSE than the best run so far"));
        }
    }

    @Test
    void aFailingCheckWithTheProgramsUntouchedForTenTurnsGetsAProgramTurn(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("note.txt"), "hello");
        int[] step = {0};
        try (StubDrive stub = new StubDrive(req -> {
            if (planningCall(req)) return plan();
            if (step[0] == 0) { step[0] = 1; return write("scorer.sh", "echo score: 1\n"); }
            JsonNode tools = req.path("tools");
            boolean programTurn = tools.size() == 3 && tools.get(1).path("function").path("parameters").path("properties").path("path").has("enum");
            if (programTurn) return StubDrive.calls("task_done", "{\"summary\":\"seen\"}");   // ends the run once the turn arrives
            return StubDrive.calls("read_file", "{\"path\":\"note.txt\"}");
        })) {
            new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp, GOAL, 80, null, null).run();
            JsonNode found = null;
            for (JsonNode req : stub.requests) {
                JsonNode tools = req.path("tools");
                if (tools.size() == 3 && tools.get(1).path("function").path("parameters").path("properties").path("path").has("enum")) { found = req; break; }
            }
            assertTrue(found != null, "a turn offered only reading and changing the named programs");
            assertEquals(List.of("read_file", "write_file", "edit_file"), toolNames(found));
            assertEquals("scorer.sh", found.path("tools").get(1).path("function").path("parameters").path("properties").path("path").path("enum").get(0).asText());
            assertTrue(StubDrive.userText(found).contains("`scorer.sh` has not changed in"), StubDrive.userText(found));
        }
    }

    @Test
    void aSecondLookAtTheTaskAddsTheBoundTheDeclarationLeftOut(@TempDir Path tmp) throws Exception {
        try (StubDrive stub = new StubDrive(req -> {
            if (planningCall(req)) return secondLook(req) ? StubDrive.says("DONE CHECK:\nrun: sh scorer.sh\npass: score >= 3\npass: measured >= 2\n") : plan();
            return StubDrive.calls("task_done", "{\"summary\":\"done\"}");
        })) {
            new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp, GOAL, 20, null, null).run();
            String system = stub.requests.get(2).path("messages").get(0).path("content").asText();
            assertTrue(system.contains("pass when: score >= 3 and measured >= 2"), system.substring(system.indexOf("DONE CHECK")));
        }
    }

    @Test
    void aFinishWhoseCheckFailsIsSentBackOnceWithTheVerdict(@TempDir Path tmp) throws Exception {
        int[] dones = {0};
        try (StubDrive stub = new StubDrive(req -> {
            if (planningCall(req)) return plan();
            if (dones[0] == 0 && !Files.exists(tmp.resolve("scorer.sh"))) return write("scorer.sh", "echo score: 1\n");
            dones[0]++;
            return StubDrive.calls("task_done", "{\"summary\":\"done\"}");
        })) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp, GOAL, 60, null, null).run();
            assertEquals(1, countNotes(stub, "the harness ran the done check you declared, and it does not pass: `sh scorer.sh` → exit 0; score 1 (needs >= 3: NO)"), "sent back once, with the verdict");
            assertTrue(r.done(), "the second finish stands");
            assertTrue(r.summary().contains("Done check, run by the harness: `sh scorer.sh` → exit 0; score 1 (needs >= 3: NO) — does not pass yet"), r.summary());
        }
    }
}
