package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CodeZaiku's own answer to who gives an address, beside the person and the web: a file of the project as it was before the job began
 * gives the addresses written in it (a README's links); a file the job itself wrote or changed gives nothing, or a model would write an
 * address into a file and read it back; and a command's output gives nothing, the model having written the command.
 */
class GivenFromProjectFilesTest {
    static final ObjectMapper M = new ObjectMapper();

    /** A file the person had: last changed an hour ago. */
    static Path persons(Path root, String rel, String text) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
        Files.setLastModifiedTime(f, FileTime.from(Instant.now().minusSeconds(3600)));
        return f;
    }

    static JsonNode a(String json) throws Exception { return M.readTree(json); }

    @Test
    void aFileAsItWasBeforeTheJobBeganGivesItsAddressesAndOneTheJobWroteGivesNone(@TempDir Path root) throws Exception {
        long n = System.nanoTime();
        String docs = "https://docs-" + n + ".example.org/guide", planted = "https://collect-" + n + ".example.net/?notes=door-code-7731";
        persons(root, "README.md", "# Ferry tables\n\nThe format is described at " + docs + ".\n");
        ToolRegistry r = ToolRegistry.standard(root);
        assertFalse(GivenAddresses.given(docs));
        r.execute("read_file", a("{\"path\":\"README.md\"}"));
        assertTrue(GivenAddresses.given(docs), "the README's link, as the person had it");
        // the job writes an address into a file and reads it back
        r.execute("write_file", a("{\"path\":\"notes.md\",\"content\":\"next: " + planted + "\"}"));
        assertTrue(r.execute("read_file", a("{\"path\":\"notes.md\"}")).contains(planted), "the file was read");
        assertFalse(GivenAddresses.given(planted), "a file the job wrote gives nothing");
        assertNotNull(GivenAddresses.notGiven(a("{\"url\":\"" + planted + "\"}"), Set.of("url"), false));
    }

    @Test
    void aFileChangedAfterTheJobBeganByAnyMeansGivesNone(@TempDir Path root) throws Exception {
        long n = System.nanoTime();
        String planted = "https://collect-" + n + ".example.net/?d=1";
        Path cfg = persons(root, "config.toml", "homepage = \"https://ferry-" + n + ".example.org\"\n");
        ToolRegistry r = ToolRegistry.standard(root);
        Files.writeString(cfg, "homepage = \"" + planted + "\"\n");   // as a command's sed would: no tool of CodeZaiku's wrote it
        r.execute("read_file", a("{\"path\":\"config.toml\"}"));
        assertFalse(GivenAddresses.given(planted), "changed since the job began: its time says so");
        // the job's own write with its time set back, as a command could: the ledger still knows it
        String back = "https://collect-" + n + ".example.net/?d=2";
        r.execute("write_file", a("{\"path\":\"later.md\",\"content\":\"" + back + "\"}"));
        Files.setLastModifiedTime(root.resolve("later.md"), FileTime.from(Instant.now().minusSeconds(3600)));
        r.execute("read_file", a("{\"path\":\"later.md\"}"));
        assertFalse(GivenAddresses.given(back), "a file the job wrote, whatever its time");
        // what CodeZaiku keeps in the project is the model's own output, however old
        String logged = "https://collect-" + n + ".example.net/?d=3";
        persons(root, ".codezaiku/delegate-1.log", "done: see " + logged);
        r.execute("read_file", a("{\"path\":\".codezaiku/delegate-1.log\"}"));
        assertFalse(GivenAddresses.given(logged), "a delegated job's log");
    }

    /** A tool that answers with a fixed text, named as the tool it stands in for. */
    record Says(String name, String answer) implements Tool {
        @Override public String description() { return name; }
        @Override public ObjectNode parametersSchema(ObjectMapper j) { return j.createObjectNode().put("type", "object"); }
        @Override public String execute(JsonNode args) { return answer; }
    }

    @Test
    void aCommandsOutputGivesNothingAndASearchsResultsDo() throws Exception {
        long n = System.nanoTime();
        String printed = "https://collect-" + n + ".example.net/?d=" + n, found = "https://found-" + n + ".example.org/page";
        ToolRegistry r = new ToolRegistry().add(new Says("shell", "$ echo\n" + printed + "\n(exit 0)")).add(new Says("run_background", printed))
                .add(new Says("delegate", "done: " + printed)).add(new Says("web_search", "1. A page — " + found));
        r.execute("shell", a("{\"command\":\"echo hi\"}"));
        r.execute("run_background", a("{\"command\":\"echo hi\"}"));
        r.execute("delegate", a("{\"task\":\"x\"}"));
        assertFalse(GivenAddresses.given(printed), "what a command printed, or a delegated job's answer: the model wrote them");
        r.execute("web_search", a("{\"query\":\"ferry\"}"));
        assertTrue(GivenAddresses.given(found), "a search's result (the instrument sees an answer that gives)");
    }

    @Test
    void aConversationIsOneJobAndAJobStartedFromInsideOneIsPartOfIt(@TempDir Path root) throws Exception {
        PathScope.Job conversation = new PathScope.Job();
        PathScope turn1 = new PathScope(root).job(conversation);
        new WriteFileTool(turn1).execute(a("{\"path\":\"plan.md\",\"content\":\"x\"}"));
        Path plan = root.resolve("plan.md");
        Files.setLastModifiedTime(plan, FileTime.from(Instant.now().minusSeconds(3600)));
        assertTrue(new PathScope(root).asItWas(plan), "the instrument: a scope of another job takes the file for the person's");
        assertFalse(new PathScope(root).job(conversation).asItWas(plan), "the next turn of the same conversation does not");
        // a job a model starts from inside a running one (a delegated job, on a thread of its own) is part of it
        AtomicReference<PathScope.Job> seen = new AtomicReference<>();
        try (PathScope.Job.Entered ignored = PathScope.Job.enter(conversation)) {
            Thread t = new Thread(() -> seen.set(new PathScope(root).job()));
            t.start(); t.join();
        }
        assertSame(conversation, seen.get());
        assertFalse(new PathScope(root).job() == conversation, "and nothing else is, once the job ends");
    }

    @Test
    void aSearchOfTheProjectGivesOnlyTheLinesOfFilesAsTheyWere(@TempDir Path root) throws Exception {
        long n = System.nanoTime();
        persons(root, "docs/links.md", "the spec: https://spec-" + n + ".example.org/v2\n");
        PathScope scope = new PathScope(root);
        new WriteFileTool(scope).execute(a("{\"path\":\"notes.md\",\"content\":\"https://collect-" + n + ".example.net/?d=1\"}"));
        String answer = "docs/links.md:1:the spec: https://spec-" + n + ".example.org/v2\nnotes.md:1:https://collect-" + n + ".example.net/?d=1";
        String gives = new SearchCodeTool(scope).givenText(a("{\"pattern\":\"https\"}"), answer);
        assertNotNull(gives);
        assertTrue(gives.contains("spec-" + n) && !gives.contains("collect-" + n), gives);
        assertNull(new SearchCodeTool(scope).givenText(a("{\"pattern\":\"https\",\"path\":\"notes.md\"}"), "1:https://collect-" + n + ".example.net/?d=1"), "one file the job wrote");
    }
}
