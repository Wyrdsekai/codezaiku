package org.codezaiku.drive;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hosted OpenAI-compatible endpoint needs a credential. Without one every request comes back 401 —
 * and the health probe reads that as "nothing answered", sending the operator to look for a dead
 * server instead of a missing key. The docs offered hosted endpoints as the answer for machines with
 * no GPU before any request carried an Authorization header at all.
 */
class DriveAuthTest {

    private static HttpRequest built(String key) throws Exception {
        Method m = DriveClient.class.getDeclaredMethod("auth", HttpRequest.Builder.class, String.class);
        m.setAccessible(true);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://example.invalid/v1/models"));
        return ((HttpRequest.Builder) m.invoke(null, b, key)).GET().build();
    }

    private static Optional<String> header(HttpRequest r) {
        return r.headers().firstValue("Authorization");
    }

    @Test
    void aKeyIsSentAsBearer() throws Exception {
        assertEquals("Bearer sk-test-123", header(built("sk-test-123")).orElse(null));
    }

    @Test
    void aKeyThatAlreadySaysBearerIsNotDoubled() throws Exception {
        assertEquals("Bearer sk-test-123", header(built("Bearer sk-test-123")).orElse(null));
        assertEquals("bearer sk-test-123", header(built("bearer sk-test-123")).orElse(null));
    }

    @Test
    void surroundingWhitespaceIsTrimmed() throws Exception {
        assertEquals("Bearer sk-test-123", header(built("  sk-test-123\n")).orElse(null));
    }

    /** The Claude API takes its key in a header of its own; a Bearer header there is answered with 401. */
    @Test
    void theClaudeApiGetsItsOwnKeyHeader() throws Exception {
        Method m = DriveClient.class.getDeclaredMethod("auth", HttpRequest.Builder.class, String.class);
        m.setAccessible(true);
        HttpRequest r = ((HttpRequest.Builder) m.invoke(null, HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/models")), "Bearer sk-ant-test")).GET().build();
        assertEquals("sk-ant-test", r.headers().firstValue("x-api-key").orElse(null));
        assertEquals("2023-06-01", r.headers().firstValue("anthropic-version").orElse(null));
        assertTrue(header(r).isEmpty(), "no Authorization header goes to the Claude API");
    }

    @Test
    void theClaudeApiIsNeverAskedToForceAToolCall() {
        assertFalse(new DriveClient("https://api.anthropic.com", "claude-opus-5-5").forcesToolCalls());
        assertTrue(new DriveClient("http://example.invalid:8210", "local-model").forcesToolCalls());
    }

    @Test
    void noKeyMeansNoHeader() throws Exception {
        assertTrue(header(built(null)).isEmpty(), "a local server should get no Authorization header");
        assertTrue(header(built("")).isEmpty(), "an empty key must not become 'Bearer '");
        assertTrue(header(built("   ")).isEmpty(), "a blank key must not become a header");
    }
}
