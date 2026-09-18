package org.codezaiku.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

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

    /**
     * The estimate is calibrated on what the server counted. A request the fixed ratio put under the window was
     * 34,395 tokens into 32,768 (2026-09-18): code and shell output tokenize denser than the guess, and the tool
     * schemas were not counted at all.
     */
    @Test
    void theEstimateLearnsFromTheServersCountAndCountsTheSchemas(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        FamiliarLoop l = loop(tmp);
        ArrayNode c = conversation(3000);
        int before = tokens(l, c);
        Method cal = FamiliarLoop.class.getDeclaredMethod("calibrate", int.class, int.class);
        cal.setAccessible(true);
        cal.invoke(l, 30_000, 15_000);                      // the server saw 2 chars per token here
        int after = tokens(l, c);
        assertTrue(after > before * 1.4, "denser text raises the estimate: " + before + " -> " + after);
        cal.invoke(l, 30_000, 1);                           // one absurd reply cannot swing it past the clamp
        assertTrue(tokens(l, c) <= after * 1.2, "clamped");
        var schema = FamiliarLoop.class.getDeclaredField("schemaTokens"); schema.setAccessible(true);
        schema.setInt(l, 2500);
        assertEquals(tokens(l, c), (int) after * 0 + tokens(l, c), "sanity");
        assertTrue(tokens(l, c) >= 2500, "the schemas ride along with every request and are counted");
    }

    /**
     * The overflow was in the NEWEST messages: three parallel shell results at their cap in one step. The first pass
     * leaves the last two untouched, so the retry trimmed 0 chars and failed identically (2026-09-18). Now they are
     * trimmed too, once the older ones are exhausted.
     */
    @Test
    void anOverflowInTheNewestMessagesIsTrimmedToo(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        FamiliarLoop l = loop(tmp);
        ArrayNode a = J.createArrayNode();
        a.addObject().put("role", "system").put("content", "you are a coding agent");
        a.addObject().put("role", "user").put("content", "do the thing");
        a.addObject().put("role", "assistant").put("content", "running three commands");
        a.addObject().put("role", "tool").put("content", "x".repeat(20_000));
        a.addObject().put("role", "tool").put("content", "y".repeat(20_000));
        a.addObject().put("role", "tool").put("content", "z".repeat(20_000));
        var nctx = FamiliarLoop.class.getDeclaredField("nctx"); nctx.setAccessible(true); nctx.setInt(l, 8192);
        int before = tokens(l, a);
        int removed = fit(l, a);
        assertTrue(removed > 0, "something was trimmed");
        assertTrue(tokens(l, a) <= 8192 - 512 - 256, "fits now: " + before + " -> " + tokens(l, a));
        assertTrue(a.get(5).path("content").asText().startsWith("zzzz") && a.get(5).path("content").asText().contains("trimmed"), "the newest result was trimmed, head kept");
    }

    /** No single tool result may take more than an eighth of the window; head and tail are kept. */
    @Test
    void aToolResultIsBoundedToAnEighthOfTheWindow(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        FamiliarLoop l = loop(tmp);
        var nctx = FamiliarLoop.class.getDeclaredField("nctx"); nctx.setAccessible(true); nctx.setInt(l, 32768);
        Method b = FamiliarLoop.class.getDeclaredMethod("boundToWindow", String.class, String.class); b.setAccessible(true);
        String big = "HEAD-" + "m".repeat(30_000) + "-TAIL exit=0";
        String out = (String) b.invoke(l, big, "shell");
        assertTrue(out.length() < 14_000, "bounded: " + out.length());
        assertTrue(out.startsWith("HEAD-") && out.endsWith("-TAIL exit=0") && out.contains("chars cut from the middle"), out.substring(0, 40));
        assertEquals("small", b.invoke(l, "small", "shell"), "under the bound: untouched");
    }

    /** The task message is never trimmed, whatever else is: a checkpoint once read "exact deliverable was in the trimmed text". */
    @Test
    void theTaskMessageIsNeverTrimmed(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        FamiliarLoop l = loop(tmp);
        ArrayNode a = J.createArrayNode();
        a.addObject().put("role", "system").put("content", "you are a coding agent");
        String task = "TASK: build viz/index.html " + "with these details ".repeat(400) + " [THE PLAN THE PERSON APPROVED] files: viz/index.html";
        a.addObject().put("role", "user").put("content", task);
        a.addObject().put("role", "assistant").put("content", "ok");
        a.addObject().put("role", "tool").put("content", "x".repeat(20_000));
        a.addObject().put("role", "assistant").put("content", "more");
        a.addObject().put("role", "tool").put("content", "y".repeat(20_000));
        var nctx = FamiliarLoop.class.getDeclaredField("nctx"); nctx.setAccessible(true); nctx.setInt(l, 8192);
        fit(l, a);
        assertEquals(task, a.get(1).path("content").asText(), "the task is intact");
        assertTrue(a.get(3).path("content").asText().contains("trimmed") && a.get(5).path("content").asText().contains("trimmed"), "the results took the cut");
    }

    /** Two parallel calls share the step's eighth. */
    @Test
    void aBatchSharesTheStepsBound(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        FamiliarLoop l = loop(tmp);
        var nctx = FamiliarLoop.class.getDeclaredField("nctx"); nctx.setAccessible(true); nctx.setInt(l, 32768);
        Method b = FamiliarLoop.class.getDeclaredMethod("boundToWindow", String.class, String.class, int.class); b.setAccessible(true);
        String big = "m".repeat(30_000);
        int one = ((String) b.invoke(l, big, "shell", 1)).length();
        int two = ((String) b.invoke(l, big, "shell", 2)).length();
        assertTrue(two < one && two <= one / 2 + 200, one + " alone, " + two + " each of two");
    }
}
