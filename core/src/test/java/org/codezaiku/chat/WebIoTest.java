package org.codezaiku.chat;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WebIoTest {
    @Test
    void whatTheChatPrintsIsNumberedAndWhatItAsksWaitsForThePage() throws Exception {
        WebIo io = new WebIo();
        io.println("hello");
        io.print("a stream"); io.print("ing line");
        io.printThinking("hmm");
        assertEquals("a streaming line", io.snapshot(0).partial()); assertEquals("hmm", io.snapshot(0).thinking());
        io.println(" done");   // closes the open line, the thinking first
        var s = io.snapshot(0);
        assertEquals(3, s.entries().size());
        assertEquals("out", s.entries().get(0).kind()); assertEquals("hello", s.entries().get(0).text());
        assertEquals("think", s.entries().get(1).kind()); assertEquals("hmm", s.entries().get(1).text());
        assertEquals("a streaming line done", s.entries().get(2).text());
        assertEquals("", s.partial()); assertNull(s.pending());
        assertEquals(1, io.snapshot(2).entries().size(), "since a sequence number: only what is newer");
        // a question waits for the page, and the answer is what the page sends
        CompletableFuture<String> answer = CompletableFuture.supplyAsync(() -> io.readLine("     [y]es / [n]o > "));
        Thread.sleep(100);
        assertEquals("     [y]es / [n]o > ", io.pending(), "the prompt is shown while it waits");
        io.say("y");
        assertEquals("y", answer.get(5, TimeUnit.SECONDS));
        assertNull(io.pending());
        var after = io.snapshot(0).entries();
        assertEquals("prompt", after.get(after.size() - 2).kind()); assertEquals("me", after.get(after.size() - 1).kind()); assertEquals("y", after.get(after.size() - 1).text());
        // the stop button is ctrl-C: nothing to stop when no turn runs, the handler when one does
        assertFalse(io.stop());
        boolean[] hit = {false};
        try (AutoCloseable c = io.onInterrupt(() -> hit[0] = true)) { assertTrue(io.stop()); }
        assertTrue(hit[0]); assertFalse(io.stop(), "the handler is gone with the turn");
        io.println("\u001B[2m  done · 4 step(s)\u001B[0m"); var e = io.snapshot(0).entries(); assertEquals("  done · 4 step(s)", e.get(e.size() - 1).text(), "the terminal's colour codes are not for a page");
        io.status("working"); assertEquals("working", io.snapshot(0).status()); io.clearStatus(); assertEquals("", io.snapshot(0).status());
    }
}
