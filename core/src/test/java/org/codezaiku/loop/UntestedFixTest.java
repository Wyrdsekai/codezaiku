package org.codezaiku.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.drive.DriveClient;
import org.codezaiku.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fix that changed no test is named once by the self-verify reflection, as a finding the model answers with a test; a change that
 * is not a fix gets no such line. Measured offline 2026-10-17: 58 of 144 passing fixes shipped no test at all.
 */
class UntestedFixTest {
    private static boolean planningCall(JsonNode req) {
        return req.path("messages").get(0).path("content").asText().startsWith("You are about to carry out a coding task");
    }
    private static ObjectNode write(String path, String content) {
        return StubDrive.calls("write_file", "{\"path\":\"" + path + "\",\"content\":\"" + content.replace("\n", "\\n") + "\"}");
    }
    /** How many user messages of the final request's history carry the text: the history holds each message once. */
    private static int count(StubDrive stub, String text) {
        int n = 0;
        JsonNode last = stub.requests.get(stub.requests.size() - 1);
        for (JsonNode m : last.path("messages")) if ("user".equals(m.path("role").asText()) && m.path("content").asText("").contains(text)) n++;
        return n;
    }

    /** The same on the path with a declared check that passes: the finish is held once for the test, then taken. */
    @Test
    void aFixWhoseCheckPassesIsStillAskedForItsTestOnce(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("app")); Files.writeString(tmp.resolve("app/calc.py"), "def add(a, b):\n    return a - b\n");
        Files.writeString(tmp.resolve("check.sh"), "grep -q 'return a + b' app/calc.py\n");   // passes once the fix is on disk, with nothing but the shell
        int[] step = {0}; int[] finishes = {0};
        try (StubDrive stub = new StubDrive(req -> {
            if (planningCall(req)) return StubDrive.says("PLAN:\n1. Fix add.\n2. Test it.\n\nDONE CHECK:\nrun: sh check.sh\npass: exit 0\n");
            String seen = StubDrive.userText(req);
            if (step[0] == 0) { step[0] = 1; return write("app/calc.py", "def add(a, b):\n    return a + b\n"); }
            if (step[0] == 1 && seen.contains("ships with no test")) { step[0] = 2; return write("tests/test_calc.py", "from app.calc import add\n\ndef test_add():\n    assert add(2, 2) == 4\n"); }
            finishes[0]++;
            return StubDrive.calls("task_done", "{\"summary\":\"fixed add\"}");
        })) {
            // a brief long enough to plan for, so the check is declared and the finish goes through the passing-check path
            String brief = "Fix the bug in app/calc.py: add() returns the wrong sum. " + "The sum must be right for every pair of numbers, and the task is done when the check passes. ".repeat(10)
                    + "\n\n## Deliverables\n- `app/calc.py` adds correctly.";
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp, brief, 14, null, null).run();
            assertTrue(r.done(), r.summary());
            assertEquals(1, count(stub, "ships with no test"), "held once for the test");
            assertEquals(2, step[0], "answered with a test");
            assertEquals(2, finishes[0], "the first task_done was held, the second was the finish");
        }
    }

    @Test
    void aFixWithoutATestIsAskedForOneOnceAndAFixWithOneIsNot(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("app")); Files.writeString(tmp.resolve("app/calc.py"), "def add(a, b):\n    return a - b\n");
        int[] step = {0};
        try (StubDrive stub = new StubDrive(req -> {
            if (planningCall(req)) return StubDrive.says("PLAN:\n1. Fix add.\n2. Test it.\n\nDONE CHECK:\nrun: none\n");
            String seen = StubDrive.userText(req);
            if (step[0] == 0) { step[0] = 1; return write("app/calc.py", "def add(a, b):\n    return a + b\n"); }
            if (step[0] == 1 && seen.contains("ships with no test")) { step[0] = 2; return write("tests/test_calc.py", "from app.calc import add\n\ndef test_add():\n    assert add(2, 2) == 4\n"); }
            return StubDrive.calls("task_done", "{\"summary\":\"fixed add\"}");
        })) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp,
                    "Fix the bug in app/calc.py: add() returns the wrong sum.", 14, null, null).run();
            StringBuilder tail = new StringBuilder();
            for (int i = Math.max(0, stub.requests.size() - 3); i < stub.requests.size(); i++) tail.append("\n--- request ").append(i).append(": ").append(StubDrive.userText(stub.requests.get(i)).replace("\n", " ").substring(0, Math.min(400, StubDrive.userText(stub.requests.get(i)).length())));
            assertTrue(r.done(), r.summary() + tail);
            assertEquals(1, count(stub, "ships with no test"), "the finding is said once, then the test is there");
            assertEquals(2, step[0], "the model answered it with a test");
        }
        Path tmp2 = Files.createTempDirectory(tmp, "feature");
        Files.createDirectories(tmp2.resolve("app"));
        int[] step2 = {0};
        try (StubDrive stub = new StubDrive(req -> {
            if (planningCall(req)) return StubDrive.says("PLAN:\n1. Add greet.\n2. Hand over.\n\nDONE CHECK:\nrun: none\n");
            if (step2[0] == 0) { step2[0] = 1; return write("app/greet.py", "def greet(n):\n    return 'hi ' + n\n"); }
            return StubDrive.calls("task_done", "{\"summary\":\"added greet\"}");
        })) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp2), tmp2,
                    "Add a greet function to app/greet.py that returns a greeting.", 14, null, null).run();
            assertTrue(r.done(), r.summary());
            assertEquals(0, count(stub, "ships with no test"), "not a fix: the line is not said");
        }
    }
    /** A fix written through the shell (a heredoc), which the edit tools never saw, is still a fix without a test: the finding reads the disk too. */
    @Test
    void aFixWrittenThroughTheShellIsSeen(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("app")); Files.writeString(tmp.resolve("app/calc.py"), "def add(a, b):\n    return a - b\n");
        int[] step = {0};
        try (StubDrive stub = new StubDrive(req -> {
            if (planningCall(req)) return StubDrive.says("PLAN:\n1. Fix add.\n2. Test it.\n\nDONE CHECK:\nrun: none\n");
            String seen = StubDrive.userText(req);
            if (step[0] == 0) { step[0] = 1; return StubDrive.calls("shell", "{\"command\":\"printf 'def add(a, b):\\n    return a + b\\n' > app/calc.py\"}"); }
            if (step[0] == 1 && seen.contains("ships with no test")) { step[0] = 2; return StubDrive.calls("shell", "{\"command\":\"mkdir -p tests && printf 'from app.calc import add\\n\\ndef test_add():\\n    assert add(2, 2) == 4\\n' > tests/test_calc.py\"}"); }
            return StubDrive.calls("task_done", "{\"summary\":\"fixed add\"}");
        })) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp,
                    "Fix the bug in app/calc.py: add() returns the wrong sum.", 14, null, null).run();
            assertTrue(r.done(), r.summary());
            assertEquals(1, count(stub, "ships with no test"), "the shell-written fix is seen, and the finding said once");
            assertEquals(2, step[0], "answered with a test, written through the shell too, which ends it");
        }
    }
}
