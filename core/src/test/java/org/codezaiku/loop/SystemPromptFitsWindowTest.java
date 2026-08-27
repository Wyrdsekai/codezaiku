package org.codezaiku.loop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The end of the chain, on the thing that actually failed: the assembled system prompt.
 *
 * A host pointed CodeZaiku at a real working tree of thousands of files with a 16k-token server and
 * could not land a single call — every turn came back
 * `exceed_context_size_error, n_prompt_tokens=23461, n_ctx=16384`, for a task whose text was ~80
 * tokens. The prompt, not the task, was the problem: the project-shape block is rebuilt every turn
 * and compaction only ever trims history, so nothing shrank it. `CODEZAIKU_CTX` did not help either,
 * because it lowers the window without lowering what is mandatory inside it.
 *
 * Testing the pieces separately would not have caught that — the shape block was already capped, at
 * 400 entries, which is not a bound the window can rely on. This asserts the assembled result.
 */
class SystemPromptFitsWindowTest {

    @TempDir Path repo;

    private static FamiliarLoop loopOver(Path root) throws Exception {
        var drive = new org.codezaiku.drive.DriveClient("http://127.0.0.1:1", "test-model");
        Constructor<?> c = FamiliarLoop.class.getConstructor(
                org.codezaiku.drive.DriveClient.class, org.codezaiku.tools.ToolRegistry.class,
                Path.class, String.class, int.class, org.codezaiku.library.Library.class,
                org.codezaiku.library.LibraryIndex.class);
        return (FamiliarLoop) c.newInstance(drive, null, root, "add a function", 10, null, null);
    }

    private static String systemPrompt(FamiliarLoop loop) throws Exception {
        Method m = FamiliarLoop.class.getDeclaredMethod("systemPrompt");
        m.setAccessible(true);
        return (String) m.invoke(loop);
    }

    @Test
    void abigRepositoryDoesNotFillTheWindowBeforeTheTaskIsRead() throws Exception {
        for (int i = 0; i < 400; i++) {
            Path d = repo.resolve("src/main/java/org/example/deeply/nested/feature" + i);
            Files.createDirectories(d);
            Files.writeString(d.resolve("SomeServiceImplementation" + i + ".java"),
                    "package org.example;\npublic class SomeServiceImplementation" + i + " {\n"
                            + "  public void handleRequest(String input) { }\n"
                            + "  public String describe() { return \"x\"; }\n}\n");
        }

        int nctx = Integer.parseInt(org.codezaiku.Config.get("CODEZAIKU_CTX", "8192"));
        String prompt = systemPrompt(loopOver(repo));
        int tokens = prompt.length() / FamiliarLoop.CHARS_PER_TOKEN;

        // The mandatory prompt has to leave room for the conversation, the tools and a reply. Half
        // the window is generous; the reported failure had it consuming the whole thing and more.
        assertTrue(tokens < nctx / 2,
                "the system prompt alone is " + tokens + " tokens of a " + nctx + "-token window — "
                        + "a large repository would overflow before the task is read");
    }
}
