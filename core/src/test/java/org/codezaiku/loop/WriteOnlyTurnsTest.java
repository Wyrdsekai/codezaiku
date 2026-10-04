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
 * A named file that is still unwritten gets turns where writing a file is the one tool offered: at half-time for a program, at the
 * start of the final stretch for a program or a document. Telling the model which files were missing did not get them written
 * (2026-09-30: two runs read the list every turn and the end-game note, and diagnosed to turn 99).
 */
class WriteOnlyTurnsTest {

    /** A model that only ever reads a note, unless one tool is all it is offered: then it uses that tool on the first missing file. */
    private static ObjectNode reply(JsonNode req) {
        JsonNode tools = req.path("tools");
        if (tools.size() != 1) return StubDrive.calls("read_file", "{\"path\":\"note.txt\"}");
        String tool = tools.get(0).path("function").path("name").asText();
        if (tool.equals("task_done")) return StubDrive.calls("task_done", "{\"summary\":\"where it stands\"}");
        String system = req.path("messages").get(0).path("content").asText();
        String file = system.contains(" - tool.py — not written yet") ? "tool.py" : "RESULTS.md";
        return StubDrive.calls(tool, "{\"path\":\"" + file + "\",\"content\":\"written on a write-only turn\\n\"}");
    }

    private static List<Integer> writeOnlyTurns(StubDrive stub) {
        List<Integer> turns = new ArrayList<>();
        for (int i = 0; i < stub.requests.size(); i++) {
            JsonNode tools = stub.requests.get(i).path("tools");
            if (tools.size() == 1 && tools.get(0).path("function").path("name").asText().equals("write_file")) turns.add(i + 1);
        }
        return turns;
    }

    @Test
    void theProgramIsWrittenAtHalfTimeAndTheDocumentInTheFinalStretch(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("note.txt"), "hello");
        try (StubDrive stub = new StubDrive(WriteOnlyTurnsTest::reply)) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp,
                    "Write `tool.py` and put the numbers in `RESULTS.md`.", 20, null, null).run();

            assertEquals(List.of(10, 16), writeOnlyTurns(stub), "one write-only turn at half-time (10 of 20), one at the final stretch (16)");
            String halfTime = StubDrive.userText(stub.requests.get(9));
            assertTrue(halfTime.contains("HALF-TIME — turn 10 of 20. The goal names `tool.py`, not written yet. For this turn write_file is the one tool"), halfTime);
            assertFalse(halfTime.contains("`RESULTS.md`, not written yet. For"), "a document is not asked for at half-time");
            assertTrue(Files.exists(tmp.resolve("tool.py")));
            JsonNode tools10 = stub.requests.get(9).path("tools");
            assertEquals("tool.py", tools10.get(0).path("function").path("parameters").path("properties").path("path").path("enum").get(0).asText(),
                    "the write tool's path is fixed to the file by its schema");
            assertTrue(StubDrive.userText(stub.requests.get(9)).contains("This turn: write `tool.py`, complete. If its text is already in another file of the project, pass that file's path as copy_from"), "and the turn says so");
            JsonNode params10 = tools10.get(0).path("function").path("parameters");
            assertEquals("string", params10.path("properties").path("copy_from").path("type").asText(), "the text may come from a file the project has");
            assertEquals("[\"path\"]", params10.path("required").toString(), "so content is not required on this turn");
            assertTrue(stub.requests.get(10).path("tools").size() > 1, "the full tools are back on the next turn");
            String stretch = StubDrive.userText(stub.requests.get(15));
            assertTrue(stretch.contains("FINAL STRETCH — about 4 turns remain. The goal names `RESULTS.md`, not written yet. For this turn write_file is the one tool"), stretch);
            assertTrue(Files.exists(tmp.resolve("RESULTS.md")));
            assertTrue(stub.requests.get(16).path("tools").size() > 1);
            assertFalse(r.summary().contains("not written"), r.summary());
        }
    }

    /** The code is already there under another name: the write-only turn takes it over with copy_from, and the next turn has the full tools back. */
    @Test
    void aWriteOnlyTurnCanTakeTheProgramFromAFileTheProjectHas(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("note.txt"), "hello");
        Files.writeString(Files.createDirectories(tmp.resolve("scratch")).resolve("tool_v2.py"), "print('the real program')\n");
        try (StubDrive stub = new StubDrive(req -> {
            JsonNode tools = req.path("tools");
            if (tools.size() == 1 && tools.get(0).path("function").path("name").asText().equals("write_file") && !Files.exists(tmp.resolve("tool.py")))
                return StubDrive.calls("write_file", "{\"path\":\"tool.py\",\"copy_from\":\"scratch/tool_v2.py\"}");
            return reply(req);
        })) {
            new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp, "Write `tool.py`.", 20, null, null).run();
            assertEquals("print('the real program')\n", Files.readString(tmp.resolve("tool.py")));
            assertEquals(List.of(10), writeOnlyTurns(stub), "one write-only turn was enough");
        }
    }

    @Test
    void aFileAlreadyWrittenGetsNoWriteOnlyTurn(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("note.txt"), "hello");
        try (StubDrive stub = new StubDrive(req -> {
            if (req.path("tools").size() > 1 && !Files.exists(tmp.resolve("tool.py"))) {
                return StubDrive.calls("write_file", "{\"path\":\"tool.py\",\"content\":\"print(1)\\n\"}");
            }
            return reply(req);
        })) {
            new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp, "Write `tool.py`.", 20, null, null).run();
            assertEquals(List.of(), writeOnlyTurns(stub));
        }
    }

    @Test
    void aFileAProgramProducesIsNeverAskedForOnAWriteOnlyTurn(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("note.txt"), "hello");
        try (StubDrive stub = new StubDrive(WriteOnlyTurnsTest::reply)) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp,
                    "Compute the verdicts and write them to `output/verdicts.jsonl`.", 20, null, null).run();
            assertEquals(List.of(), writeOnlyTurns(stub), "verdicts come from running the program; writing them by hand would be inventing them");
            assertTrue(r.summary().endsWith("Named in the goal and not written: `output/verdicts.jsonl`."), r.summary());
        }
    }

    @Test
    void aShortRunOrOneThatCannotWriteHasNone(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("note.txt"), "hello");
        try (StubDrive stub = new StubDrive(WriteOnlyTurnsTest::reply)) {
            new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp), tmp, "Write `tool.py`.", 12, null, null).run();
            assertEquals(List.of(), writeOnlyTurns(stub), "twelve turns: too short a run to take turns away from");
        }
        try (StubDrive stub = new StubDrive(WriteOnlyTurnsTest::reply)) {
            new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.readOnly(tmp, null), tmp, "Write `tool.py`.", 20, null, null).run();
            assertEquals(List.of(), writeOnlyTurns(stub), "no write tool among the tools: nothing to narrow to");
        }
    }
}
