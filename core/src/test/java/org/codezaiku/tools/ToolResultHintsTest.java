package org.codezaiku.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.loop.FamiliarLoop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a tool result says beyond its content: the next read written out whole, no read-back after a write, a checkpoint's open questions. */
class ToolResultHintsTest {
    private static ObjectNode args(String json) throws Exception { return (ObjectNode) new ObjectMapper().readTree(json); }

    @Test
    void aCutRangeAndALargeFileNameTheNextCallWhole(@TempDir Path tmp) throws Exception {
        StringBuilder big = new StringBuilder();
        for (int i = 1; i <= 2000; i++) big.append("line ").append(i).append(" of a long file with some words in it to fill the cap\n");
        Files.writeString(tmp.resolve("big.txt"), big.toString());
        ToolRegistry tools = ToolRegistry.standard(tmp);
        String whole = tools.execute("read_file", args("{\"path\":\"big.txt\"}"));
        assertTrue(whole.contains("read_file(path=\"big.txt\", offset=61, limit=200)"), whole.substring(0, 300));
        String range = tools.execute("read_file", args("{\"path\":\"big.txt\",\"offset\":100,\"limit\":1500}"));
        assertTrue(range.contains("the range continues: read_file(path=\"big.txt\", offset="), range.substring(range.length() - 200));
    }

    @Test
    void aWriteAndAnEditSayThereIsNoNeedToReadBack(@TempDir Path tmp) throws Exception {
        ToolRegistry tools = ToolRegistry.standard(tmp);
        String wrote = tools.execute("write_file", args("{\"path\":\"a.txt\",\"content\":\"one\\ntwo\\n\"}"));
        assertTrue(wrote.endsWith(WriteFileTool.NO_READ_BACK), wrote);
        String edited = tools.execute("edit_file", args("{\"path\":\"a.txt\",\"old_string\":\"two\",\"new_string\":\"three\"}"));
        assertTrue(edited.endsWith(WriteFileTool.NO_READ_BACK), edited);
    }

    @Test
    void theCheckpointAsksForOpenQuestionsAndMarksTheUnverified() {
        assertTrue(FamiliarLoop.SUMMARIZE_PROMPT.contains("## Open questions"));
        assertTrue(FamiliarLoop.SUMMARIZE_PROMPT.contains("written as unverified"));
    }
}
