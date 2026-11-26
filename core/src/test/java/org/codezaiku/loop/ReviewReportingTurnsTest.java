package org.codezaiku.loop;

import com.fasterxml.jackson.databind.JsonNode;
import org.codezaiku.drive.DriveClient;
import org.codezaiku.review.ReportFindingTool;
import org.codezaiku.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A review's findings are recorded by the reporting tool, so a model that reads until its turns run out must still get turns to report
 * in. A review of twenty-one bundles on a hosted model (2026-11-20) spent all thirty turns of each bundle reading and checking, met a
 * last turn that offered the finishing tool alone, and recorded nothing, though its summaries said "six findings reported". The turns
 * before the last now offer the reporting tool and the finishing tool alone, with one sentence saying so.
 */
class ReviewReportingTurnsTest {

    private static final String DIFF = """
            diff --git a/Calc.java b/Calc.java
            --- a/Calc.java
            +++ b/Calc.java
            @@ -1,3 +1,3 @@
             class Calc {
            -    int half(int n) { return n / 2; }
            +    int half(int n) { return n / 0; }
             }
            """;

    private static List<String> offered(JsonNode request) {
        List<String> names = new ArrayList<>();
        for (JsonNode t : request.path("tools")) names.add(t.path("function").path("name").asText());
        return names;
    }

    @Test
    void aModelThatReadsToTheEndReportsInTheClosingTurns(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("Calc.java"), "class Calc {\n    int half(int n) { return n / 0; }\n}\n");
        ReportFindingTool reporter = new ReportFindingTool(DIFF, f -> { try { return Files.readString(tmp.resolve(f)); } catch (Exception e) { return null; } });
        AtomicInteger reads = new AtomicInteger();
        int turns = 14;
        try (StubDrive stub = new StubDrive(req -> {
            List<String> names = offered(req);
            if (names.size() == 1) return StubDrive.calls("task_done", "{\"summary\":\"One defect, reported.\"}");
            if (names.size() == 2 && names.contains(ReportFindingTool.NAME))
                return StubDrive.calls(ReportFindingTool.NAME, "{\"severity\":\"high\",\"file\":\"Calc.java\",\"existing_code\":\"    int half(int n) { return n / 0; }\",\"problem\":\"half divides by zero for every n.\"}");
            return StubDrive.calls("shell", "{\"command\":\"echo reading " + reads.incrementAndGet() + "\"}");   // a model that never stops looking
        })) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.readOnly(tmp, null).add(reporter), tmp,
                    "Review this change.", turns, null, null).report().findings(() -> reporter.findings().size()).run();
            assertTrue(reporter.findings().size() >= 1, "the finding was recorded: " + r.summary());
            int closing = 0, said = 0;
            for (JsonNode req : stub.requests) {
                List<String> names = offered(req);
                if (names.size() == 2 && names.contains(ReportFindingTool.NAME) && names.contains("task_done")) closing++;
                if (StubDrive.userText(req).contains("The reading is done. Report now")) said++;
            }
            assertTrue(closing >= 1 && closing <= FamiliarLoop.REPORT_CLOSING_TURNS, "the turns before the last offered the two finishing tools alone: " + closing);
            assertTrue(said >= 1, "and the model was told so once it began");
            assertTrue(reads.get() >= turns - FamiliarLoop.REPORT_CLOSING_TURNS - 2, "the turns before those were its own to read in: " + reads.get());
        }
    }

    @Test
    void aShortRunKeepsItsTurnsForReading(@TempDir Path tmp) throws Exception {
        ReportFindingTool reporter = new ReportFindingTool(DIFF, f -> null);
        FamiliarLoop loop = new FamiliarLoop(new DriveClient("http://127.0.0.1:9", "t"), ToolRegistry.readOnly(tmp, null).add(reporter), tmp,
                "Review this change.", FamiliarLoop.REPORT_CLOSING_TURNS * 2 - 1, null, null).report();
        for (int t = 1; t < FamiliarLoop.REPORT_CLOSING_TURNS * 2; t++) assertEquals(false, loop.reportClosing(t), "turn " + t + " of a run too short to give turns away");
    }

    @Test
    void whichTurnsAreReportingTurns(@TempDir Path tmp) {
        ReportFindingTool reporter = new ReportFindingTool(DIFF, f -> null);
        FamiliarLoop review = new FamiliarLoop(new DriveClient("http://127.0.0.1:9", "t"), ToolRegistry.readOnly(tmp, null).add(reporter), tmp, "Review.", 30, null, null).report();
        assertEquals(false, review.reportClosing(24));
        for (int t = 25; t <= 29; t++) assertTrue(review.reportClosing(t), "turn " + t);
        assertEquals(false, review.reportClosing(30), "the last turn is the finishing tool's alone, as before");
        FamiliarLoop research = new FamiliarLoop(new DriveClient("http://127.0.0.1:9", "t"), ToolRegistry.readOnly(tmp, null), tmp, "Find out.", 30, null, null).research();
        assertEquals(false, research.reportClosing(27), "only a review has reporting turns");
    }
}
