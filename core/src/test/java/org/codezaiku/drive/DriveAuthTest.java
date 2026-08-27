package org.codezaiku.drive;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void noKeyMeansNoHeader() throws Exception {
        assertTrue(header(built(null)).isEmpty(), "a local server should get no Authorization header");
        assertTrue(header(built("")).isEmpty(), "an empty key must not become 'Bearer '");
        assertTrue(header(built("   ")).isEmpty(), "a blank key must not become a header");
    }
}
