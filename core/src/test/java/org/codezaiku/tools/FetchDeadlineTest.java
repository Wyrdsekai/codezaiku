package org.codezaiku.tools;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
/** The body read ends at the fetch's deadline; a request timeout alone stops at the response headers. */
class FetchDeadlineTest {

    /** A body that sends a few bytes and then nothing until it is closed, the way a socket does. {@code throwsOnClose}: the two ways a stream can answer a close. */
    static InputStream stalls(boolean throwsOnClose) {
        return new InputStream() {
            final CountDownLatch closed = new CountDownLatch(1);
            int sent = 0;
            @Override public int read() throws IOException {
                if (sent < 10) { sent++; return 'x'; }
                try { closed.await(); } catch (InterruptedException e) { throw new IOException(e); }
                if (throwsOnClose) throw new IOException("closed");
                return -1;
            }
            @Override public void close() { closed.countDown(); }
        };
    }

    @Test
    void aBodyThatNeverFinishesIsGivenUpAtTheDeadline() {
        for (boolean throwsOnClose : new boolean[]{true, false}) {
            long t0 = System.nanoTime();
            var e = assertThrows(IOException.class, () -> Fetch.readUntil(stalls(throwsOnClose), 1_000_000, System.nanoTime() + Duration.ofMillis(700).toNanos()));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(e.getMessage().contains("did not finish arriving"), e.getMessage());
            assertTrue(ms >= 600 && ms < 4000, "gave up after " + ms + " ms");
        }
    }

    @Test
    void aBodyThatArrivesIsReadWholeAndCappedAsBefore() throws Exception {
        byte[] page = "x".repeat(5000).getBytes();
        assertEquals(5000, Fetch.readUntil(new ByteArrayInputStream(page), 1_000_000, System.nanoTime() + Duration.ofSeconds(5).toNanos()).length);
        assertEquals(100, Fetch.readUntil(new ByteArrayInputStream(page), 100, System.nanoTime() + Duration.ofSeconds(5).toNanos()).length);
    }

    @Test
    void theWholeFetchGetsThreeRequestTimeoutsBetweenThirtySecondsAndFiveMinutes() {
        assertEquals(30, Fetch.totalBudget(Duration.ofSeconds(5)).toSeconds());
        assertEquals(60, Fetch.totalBudget(Duration.ofSeconds(20)).toSeconds());
        assertEquals(300, Fetch.totalBudget(Duration.ofSeconds(200)).toSeconds());
    }
}
