package org.codezaiku.loop;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request larger than the window cannot be nudged into fitting — unlike every other failure the
 * loop recovers from, resending it fails identically. Reported by a host whose run spent 40 turns
 * and seven minutes on the same HTTP 400 and returned `files=[]` with no reason given; only the WARN
 * line said why.
 */
class ContextOverflowTest {

    private static boolean overflow(String m) throws Exception {
        Method f = FamiliarLoop.class.getDeclaredMethod("contextOverflow", String.class);
        f.setAccessible(true);
        return (boolean) f.invoke(null, m);
    }

    private static String detail(String m) throws Exception {
        Method f = FamiliarLoop.class.getDeclaredMethod("overflowDetail", String.class);
        f.setAccessible(true);
        return (String) f.invoke(null, m);
    }

    /** The exact body the reporter saw. */
    private static final String LLAMA_CPP = "drive HTTP 400: {\"error\":{\"code\":400,\"message\":"
            + "\"request (23461 tokens) exceeds the available context size (16384 tokens)...\","
            + "\"type\":\"exceed_context_size_error\",\"n_prompt_tokens\":23461,\"n_ctx\":16384}}";

    @Test
    void theReportedFailureIsRecognised() throws Exception {
        assertTrue(overflow(LLAMA_CPP));
    }

    @Test
    void otherProvidersPhraseItDifferently() throws Exception {
        assertTrue(overflow("This model's maximum context length is 8192 tokens, however you requested 9000"));
        assertTrue(overflow("context length exceeded"));
    }

    /** Everything the loop CAN recover from must keep recovering — this only short-circuits one case. */
    @Test
    void ordinaryFailuresAreNotMistakenForIt() throws Exception {
        assertFalse(overflow("parse error at column 42: missing closing quote"));
        assertFalse(overflow("drive HTTP 500: internal server error"));
        assertFalse(overflow("connection reset"));
        assertFalse(overflow(null));
        assertFalse(overflow("rate limit exceeded: too many tokens per minute"),
                "a rate limit is transient and retrying is right — do not abort the run for it");
    }

    /** The server's own numbers explain it better than anything we could restate. */
    @Test
    void theServersNumbersAreQuotedBack() throws Exception {
        assertEquals("23461 tokens into a 16384-token window", detail(LLAMA_CPP));
    }

    @Test
    void aMessageWithoutNumbersStillReportsCleanly() throws Exception {
        assertEquals("size not reported", detail("context length exceeded"));
        assertEquals("size not reported", detail(null));
    }
}
