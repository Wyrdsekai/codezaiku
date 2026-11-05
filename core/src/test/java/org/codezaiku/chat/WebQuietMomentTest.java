package org.codezaiku.chat;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The web app's update turn waits for a quiet moment: no project's chat working; one asking the person, not started, or ended is quiet. */
class WebQuietMomentTest {
    @Test void aWorkingChatIsNotQuietAndOneWaitingForThePersonIs() throws Exception {
        WebChat.Loop idle = new WebChat.Loop("a", Path.of("/tmp/a"));
        assertTrue(WebChat.quiet(List.of()), "no projects: quiet");
        assertTrue(WebChat.quiet(List.of(idle)), "not started: quiet");
        WebChat.Loop busy = new WebChat.Loop("b", Path.of("/tmp/b"));
        CountDownLatch go = new CountDownLatch(1);
        busy.thread = new Thread(() -> { try { go.await(); } catch (InterruptedException ignored) { } });
        busy.thread.start();
        try {
            assertFalse(WebChat.quiet(List.of(idle, busy)), "a chat working: not quiet");
            Thread asker = new Thread(() -> busy.io.readLine("Go ahead? (y/N)"));   // the chat asks the person and waits: quiet
            asker.setDaemon(true); asker.start();
            long until = System.currentTimeMillis() + 5_000;
            while (busy.io.pending() == null && System.currentTimeMillis() < until) Thread.sleep(5);
            assertTrue(busy.io.pending() != null, "the question is pending");
            assertTrue(WebChat.quiet(List.of(idle, busy)), "waiting for the person: quiet");
            asker.interrupt();
        } finally { go.countDown(); busy.thread.join(2_000); }
    }
}
