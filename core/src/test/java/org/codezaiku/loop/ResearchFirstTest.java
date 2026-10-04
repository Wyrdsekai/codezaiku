package org.codezaiku.loop;

import com.fasterxml.jackson.databind.JsonNode;
import org.codezaiku.drive.DriveClient;
import org.codezaiku.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Before a multi-step coding task is planned, the harness asks the model which questions decide the approach, answers them with a
 * bounded research pass, and keeps the notes in the prompt from the plan onward. A capable model finished the video brief because
 * it knew which pose library works on broadcast video; a 27B on the same harness did not know, and never searched.
 */
class ResearchFirstTest {

    /** A research answer long enough to be one: the loop sends a thin answer back once, and these stand-ins must not be sent back. */
    private static final String FILLER = " In practice the score is computed once per input and summed; practitioners validate against a held-out set.".repeat(4);

    /** A multi-step task on code that exists: long enough to be planned, and so to be asked about research first. */
    private static final String MAINTAIN_GOAL = "Rename load_rows to read_rows across a.py, b.py and c.py. " + "Update every caller and every test, keep the behaviour the same, and write NOTES.md saying what changed. ".repeat(8);
    private static final String GOAL = "Build a scorer. " + "Make the score as high as it can go, and the task is done when the score is at least 3. ".repeat(10)
            + "\n\n## Deliverables\n- `scorer.sh` prints `score: <number>`.\n- `RESULTS.md` holds the number.";

    // The research pass keeps what it finds in the research memory, which lives under the home folder: these tests get a home
    // of their own, so that nothing a stand-in "found" lands in the memory of whoever runs them.
    @TempDir Path home;
    private String realHome;

    @BeforeEach
    void aHomeOfItsOwn() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void theRealHomeAgain() {
        System.setProperty("user.home", realHome);
    }

    private static String kind(JsonNode req) {
        String system = req.path("messages").get(0).path("content").asText();
        if (system.startsWith("You are about to carry out a coding task. Before the work, a short piece of research")) return "questions";
        if (system.startsWith("You are about to carry out a coding task. This message is for the plan alone")) return "plan";
        if (system.startsWith("You compare a declared check")) return "second-look";
        for (JsonNode t : req.path("tools")) if (t.path("function").path("name").asText().equals("web_search")) return "research";
        return "work";
    }

    /** Everything a request says: the research loop carries its question in its system prompt. */
    private static String allText(JsonNode req) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode m : req.path("messages")) sb.append(m.path("content").asText("")).append('\n');
        return sb.toString();
    }

    private static List<String> kinds(StubDrive stub) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : stub.requests) out.add(kind(r));
        return out;
    }

    @Test
    void whenThePassRunsIsDecidedByTheSettingTheDriveAndTheSources() {
        assertNull(FamiliarLoop.researchFirstSkipped("auto", false, true), "a local model with a search backend: the pass runs");
        assertNull(FamiliarLoop.researchFirstSkipped(null, false, true), "unset means auto");
        assertTrue(FamiliarLoop.researchFirstSkipped("auto", true, true).contains("frontier"), "a frontier drive: not run");
        assertNull(FamiliarLoop.researchFirstSkipped("always", true, false), "always: on a frontier drive too, sources or not");
        assertTrue(FamiliarLoop.researchFirstSkipped("auto", false, false).contains("no search backend"), "nothing to read from: not run");
        assertTrue(FamiliarLoop.researchFirstSkipped("off", false, true).startsWith("CODEZAIKU_RESEARCH_FIRST=off"));
        assertTrue(FamiliarLoop.researchFirstSkipped("false", false, true).startsWith("CODEZAIKU_RESEARCH_FIRST=off"), "the old spelling still turns it off");
    }

    @Test
    void onAnExistingCodebaseTheModelsNoneIsTakenAtItsWord(@TempDir Path tmp) throws Exception {
        // three real source files: a pre-existing codebase, not a greenfield start
        for (String n : new String[]{"a", "b", "c"}) Files.writeString(tmp.resolve(n + ".py"), ("def " + n + "():\n    return 1\n\n" + "# a line of the module\n".repeat(30)));
        try (StubDrive stub = new StubDrive(req -> switch (kind(req)) {
            case "questions" -> StubDrive.says("QUESTIONS: none");
            case "research" -> StubDrive.calls("task_done", "{\"summary\":\"should not have run\"}");
            case "plan" -> StubDrive.says("PLAN:\n1. Rename it.\n\nDONE CHECK:\nrun: none\n");
            default -> StubDrive.calls("task_done", "{\"summary\":\"done\"}");
        })) {
            new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp, MAINTAIN_GOAL, 8, null, null).researchFirst(true).run();
            List<String> k = kinds(stub);
            assertEquals("questions", k.get(0), k.toString());
            assertFalse(k.contains("research"), "the model said none and the code exists: no pass: " + k);
            String planAsk = StubDrive.userText(stub.requests.get(k.indexOf("plan")));
            assertFalse(planAsk.contains("RESEARCH NOTES"), planAsk);
        }
    }

    @Test
    void theQuestionsAreAskedResearchedAndTheNotesStandFromThePlanOnward(@TempDir Path tmp) throws Exception {
        try (StubDrive stub = new StubDrive(req -> switch (kind(req)) {
            case "questions" -> StubDrive.says("QUESTIONS:\n1. Which scoring library works best for short shell scripts?\n2. How is a score defined in practice?\n3. What usually goes wrong?\n4. A fourth that is not taken");
            case "research" -> StubDrive.calls("task_done", "{\"summary\":\"Use the bc tool for arithmetic; scores are integers in practice." + FILLER + "\\n\\nSOURCES:\\nhttps://example.org/scoring\"}");
            case "plan" -> StubDrive.says("PLAN:\n1. Write scorer.sh.\n2. Write RESULTS.md.\n\nDONE CHECK:\nrun: none\n");
            default -> StubDrive.calls("task_done", "{\"summary\":\"done\"}");
        })) {
            new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp, GOAL, 8, null, null).researchFirst(true).run();

            List<String> k = kinds(stub);
            int research = k.indexOf("research"), plan = k.lastIndexOf("plan");
            assertTrue(k.get(0).equals("questions") && research > 0 && plan > research, "the questions, the research, then the plan: " + k);
            String researchAsk = allText(stub.requests.get(research));
            assertTrue(researchAsk.contains("1. Which scoring library works best for short shell scripts?") && researchAsk.contains("3. What usually goes wrong?")
                    && !researchAsk.contains("A fourth"), "the first three questions go to the research pass: " + researchAsk);
            assertTrue(researchAsk.contains("Build a scorer."), "with the task for context");
            List<String> researchTools = new ArrayList<>();
            for (JsonNode t : stub.requests.get(research).path("tools")) researchTools.add(t.path("function").path("name").asText());
            assertTrue(researchTools.contains("web_search") && researchTools.contains("web_fetch") && researchTools.contains("task_done"), researchTools.toString());
            assertFalse(researchTools.contains("read_file") || researchTools.contains("shell") || researchTools.contains("write_file"),
                    "the harness's own research pass has no file of the project — one spent its six turns reading code: " + researchTools);
            String planAsk = StubDrive.userText(stub.requests.get(plan));
            assertTrue(planAsk.contains("RESEARCH NOTES") && planAsk.contains("Use the bc tool for arithmetic"), "the plan is written with the notes in view: " + planAsk);
            assertTrue(planAsk.contains("the step says so in the notes' own terms"), "and is asked to carry them step by step: " + planAsk);
            assertTrue(researchAsk.contains("Where options trade accuracy for speed, name both") && researchAsk.contains("that source's definition of the quantity"), researchAsk);
            JsonNode turn1 = stub.requests.get(plan + 1);
            assertEquals("work", kind(turn1));
            String system = turn1.path("messages").get(0).path("content").asText();
            assertTrue(system.contains("RESEARCH NOTES") && system.contains("https://example.org/scoring"), "and they stand in every turn's prompt, sources included");
            assertTrue(system.contains("is built the way they describe") && !system.contains("not instructions"), "as the way the work is done, not as something to consider: " + system.substring(system.indexOf("RESEARCH NOTES"), system.indexOf("RESEARCH NOTES") + 400));
        }
    }

    @Test
    void whenTheModelSaysNoResearchIsNeededTheGeneralQuestionIsAskedAnyway(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("note.txt"), "hello");
        List<String> researchAsks = new ArrayList<>();
        try (StubDrive stub = new StubDrive(req -> switch (kind(req)) {
            case "questions" -> StubDrive.says("QUESTIONS: none");
            case "research" -> { researchAsks.add(allText(req)); yield StubDrive.calls("task_done", "{\"summary\":\"Nothing special is needed." + FILLER + "\"}"); }
            case "plan" -> StubDrive.says("PLAN:\n1. Write scorer.sh.\n2. Write RESULTS.md.\n\nDONE CHECK:\nrun: none\n");
            default -> StubDrive.calls("task_done", "{\"summary\":\"done\"}");
        })) {
            new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp, GOAL, 8, null, null).researchFirst(true).run();
            assertEquals(1, researchAsks.size(), "the pass still runs");
            assertTrue(researchAsks.get(0).contains("1. How is this done well in practice") && researchAsks.get(0).contains("Build a scorer."), researchAsks.get(0));
        }
    }

    /**
     * After three failing checks running, the harness researches the symptom — the check's numbers and the method the program's
     * header describes — and the notes grow a section on it, which the check's note and the program turn point at.
     */
    @Test
    void afterThreeFailingChecksTheSymptomIsResearchedAndTheNotesGrow(@TempDir Path tmp) throws Exception {
        List<String> researchAsks = new ArrayList<>();
        int[] step = {0};
        try (StubDrive stub = new StubDrive(req -> switch (kind(req)) {
            case "questions" -> StubDrive.says("QUESTIONS: none");
            case "research" -> { researchAsks.add(allText(req)); yield StubDrive.calls("task_done", "{\"summary\":\"" + (researchAsks.size() == 1
                    ? "Scores are integers; add them up." + FILLER : "A score printed as 1 every time means the counter is never advanced; practitioners read the input before summing." + FILLER + "\\n\\nSOURCES: https://example.org/stuck") + "\"}"); }
            case "plan" -> StubDrive.says("PLAN:\n1. Write scorer.sh.\n2. Raise the score.\n3. Write RESULTS.md.\n\nDONE CHECK:\nrun: sh scorer.sh\npass: score >= 3\n");
            case "second-look" -> StubDrive.says("DONE CHECK:\nrun: sh scorer.sh\npass: score >= 3\n");
            default -> {
                String seen = StubDrive.userText(req);
                // a scorer that stays at 1 through three versions, then the fix: the next version only once the last one's check has
                // reported (counted from the notes), so that every version is checked and the third failing check is reached
                int reported = 0; for (int at = seen.indexOf("does not pass yet"); at >= 0; at = seen.indexOf("does not pass yet", at + 1)) reported++;
                if (step[0] < 3 && reported >= step[0]) { step[0]++; yield StubDrive.calls("write_file", "{\"path\":\"scorer.sh\",\"content\":\"# version " + step[0] + "\\necho score: 1\\n\"}"); }
                if (step[0] == 3 && seen.contains("AFTER THE CHECK FAILED")) { step[0] = 4; yield StubDrive.calls("write_file", "{\"path\":\"scorer.sh\",\"content\":\"echo score: 5\\n\"}"); }
                if (seen.contains("PASSES")) { if (step[0] == 4) { step[0] = 5; yield StubDrive.calls("write_file", "{\"path\":\"RESULTS.md\",\"content\":\"score: 5\\n\"}"); } yield StubDrive.calls("task_done", "{\"summary\":\"5\"}"); }
                yield StubDrive.waiting();
            }
        })) {
            Files.writeString(tmp.resolve("TASK.md"), "the task");
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp, GOAL, 200, null, null).researchFirst(true).run();   // the stub waits 100 ms a turn while checks run
            assertTrue(r.done(), r.summary());
            assertEquals(2, researchAsks.size(), "the pass before the work, then the pass on the symptom: " + researchAsks.size());
            String stuck = researchAsks.get(1);
            assertTrue(stuck.contains("its check keeps failing") && stuck.contains("score 1 (needs >= 3: NO)") && stuck.contains("# version 3"), "the symptom and the program's own header go into the question: " + stuck);
            JsonNode last = stub.requests.get(stub.requests.size() - 1);
            String system = last.path("messages").get(0).path("content").asText();
            assertTrue(system.contains("AFTER THE CHECK FAILED (turn") && system.contains("counter is never advanced") && system.contains("https://example.org/stuck"), "the notes grew a section on it: " + system.substring(system.indexOf("RESEARCH NOTES")));
            assertEquals(1, countNotes(stub, "the harness researched the symptom"), "and the check's note said so, once");
        }
    }

    /** The most times the text appears in any one request's user messages. */
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

    /** A thin answer, or one that points at a file, is followed by the pages the pass read; a full answer stands alone. */
    @Test
    void aThinAnswerIsBackedByThePagesRead() {
        List<String> pages = List.of("source: https://a.example/paper — The release point\nRelease is found at the wrist's lateral extreme…",
                "source: https://b.example/defn — Arm angle\nMeasured from the shoulder to the ball…");
        String thin = FamiliarLoop.notesFrom("Research complete. Wrote full findings to RESEARCH.md in project root.", pages);
        assertTrue(thin.contains("What the pass read") && thin.contains("wrist's lateral extreme") && thin.contains("shoulder to the ball"), thin);
        assertTrue(thin.startsWith("Research complete."), "the answer itself is kept too");
        String full = "x".repeat(1300);
        assertEquals(full, FamiliarLoop.notesFrom(full, pages), "a full answer stands alone");
        assertEquals("short", FamiliarLoop.notesFrom("short", List.of()), "nothing to add when nothing was read");
        String pointer = FamiliarLoop.notesFrom("Findings:\n" + "y".repeat(1300) + "\nSee notes.md for the rest.", pages);
        assertTrue(pointer.contains("What the pass read"), "an answer that points at a file is backed even when it is long: " + pointer.length());
    }

    @Test
    void whatCountsAsAThinAnswer() {
        String brief = "Find out, from the web: 1. … 2. … 3. … " + "and the task for context. ".repeat(40);   // several questions
        assertTrue(FamiliarLoop.thinAnswer("Research complete; see report below.", brief));
        assertTrue(FamiliarLoop.thinAnswer("Research complete. Wrote full findings to RESEARCH.md in project root.", brief));
        assertTrue(FamiliarLoop.thinAnswer("", brief));
        assertTrue(FamiliarLoop.thinAnswer("Q1: MediaPipe. Q2: shoulder to wrist. " + "Details are written to notes.md. " + "x".repeat(500), brief));
        assertFalse(FamiliarLoop.thinAnswer("Q1 — Pose model: " + "RTMPose leads on accuracy (74.5 AP); MediaPipe is the fast CPU option. ".repeat(8) + "\nSOURCES: https://a.example", brief));
        assertFalse(FamiliarLoop.thinAnswer("x".repeat(2600) + " see the table below for the rest", brief), "a long answer that also says 'see below' is an answer");
        assertFalse(FamiliarLoop.thinAnswer("1987. Source: https://a.example", "In which year was the bridge opened?"), "a short question takes a short answer");
        assertTrue(FamiliarLoop.thinAnswer("See the report below.", "In which year was the bridge opened?"), "but not one that points elsewhere");
    }

    @Test
    void theQuestionsInAReply() {
        assertEquals(List.of("Which pose library works on broadcast video?", "How is the release frame defined in pitching?"),
                PlanReply.researchQuestions("Sure.\n\nQUESTIONS:\n1. Which pose library works on broadcast video?\n2. How is the release frame defined in pitching?\n"));
        assertEquals(List.of(), PlanReply.researchQuestions("QUESTIONS: none"));
        assertEquals(List.of(), PlanReply.researchQuestions("QUESTIONS:\nnone\n"));
        assertEquals(List.of(), PlanReply.researchQuestions(""));
        assertEquals(List.of(), PlanReply.researchQuestions("QUESTIONS:\n1. <question>\n2. <question>"), "the format's own placeholders are not questions");
        assertEquals(3, PlanReply.researchQuestions("QUESTIONS:\n1. First question here?\n2. Second question here?\n3. Third question here?\n4. Fourth question here?").size());
    }
}
