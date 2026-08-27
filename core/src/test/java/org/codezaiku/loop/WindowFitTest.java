package org.codezaiku.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request that cannot fit must never be SENT.
 *
 * Compaction is a threshold (70%) and the pinned block is budgeted, but a single large observation
 * lands after both: a host watched a mid-run file read take one request to 30,167 tokens against a
 * 16,384-token window. The tell was already in their log — `max_tokens=512`, which is the floor
 * `outputBudget` returns only once the input alone has overrun the window — and the request went out
 * regardless.
 */
class WindowFitTest {

    private static final ObjectMapper J = new ObjectMapper();

    private static FamiliarLoop loop(Path root) throws Exception {
        var drive = new org.codezaiku.drive.DriveClient("http://127.0.0.1:1", "test-model");
        Constructor<?> c = FamiliarLoop.class.getConstructor(
                org.codezaiku.drive.DriveClient.class, org.codezaiku.tools.ToolRegistry.class,
                Path.class, String.class, int.class, org.codezaiku.library.Library.class,
                org.codezaiku.library.LibraryIndex.class);
        return (FamiliarLoop) c.newInstance(drive, null, root, "goal", 10, null, null);
    }

    private static int fit(FamiliarLoop l, ArrayNode messages) throws Exception {
        Method m = FamiliarLoop.class.getDeclaredMethod("fitToWindow", ArrayNode.class);
        m.setAccessible(true);
        return (int) m.invoke(l, messages);
    }

    private static int tokens(FamiliarLoop l, ArrayNode messages) throws Exception {
        Method m = FamiliarLoop.class.getDeclaredMethod("estimateTokens", ArrayNode.class);
        m.setAccessible(true);
        return (int) m.invoke(l, messages);
    }

    private static ArrayNode conversation(int observationChars) {
        ArrayNode a = J.createArrayNode();
        a.addObject().put("role", "system").put("content", "you are a coding agent");
        for (int i = 0; i < 4; i++) {
            a.addObject().put("role", "tool").put("content", "x".repeat(observationChars));
        }
        a.addObject().put("role", "assistant").put("content", "thinking");
        a.addObject().put("role", "user").put("content", "continue");
        return a;
    }

    @Test
    void anOversizedConversationIsBroughtInsideTheWindow(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        FamiliarLoop l = loop(tmp);
        int nctx = Integer.parseInt(org.codezaiku.Config.get("CODEZAIKU_CTX", "8192"));

        ArrayNode messages = conversation(nctx * FamiliarLoop.CHARS_PER_TOKEN);   // ~4x the window
        assertTrue(tokens(l, messages) > nctx, "the fixture must start over the window");

        int removed = fit(l, messages);

        assertTrue(removed > 0, "nothing was trimmed from a conversation four times the window");
        assertTrue(tokens(l, messages) <= nctx - 512,
                "still " + tokens(l, messages) + " tokens against a " + nctx + "-token window — "
                        + "this request would be refused by the server");
    }

    @Test
    void aTrimmedObservationSaysSo(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        FamiliarLoop l = loop(tmp);
        int nctx = Integer.parseInt(org.codezaiku.Config.get("CODEZAIKU_CTX", "8192"));
        ArrayNode messages = conversation(nctx * FamiliarLoop.CHARS_PER_TOKEN);

        fit(l, messages);

        boolean marked = false;
        for (var m : messages) {
            if (m.path("content").asText("").contains("trimmed to fit")) marked = true;
        }
        assertTrue(marked, "a silently truncated file read is worse than the overflow — the model "
                + "would act on a partial file believing it whole");
    }

    @Test
    void aConversationThatAlreadyFitsIsUntouched(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        FamiliarLoop l = loop(tmp);
        ArrayNode messages = conversation(200);
        String before = messages.toString();

        assertTrue(fit(l, messages) == 0, "nothing should be trimmed from a small conversation");
        assertTrue(messages.toString().equals(before), "the conversation must be left alone");
    }
}
