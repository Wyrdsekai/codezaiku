package org.codezaiku.drive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The providers of OpenRouter a person names in CODEZAIKU_OPENROUTER_IGNORE get no request: the list is read as it is written, and it
 * is put into a body as provider.ignore, beside the "only a provider that takes every field" a call with tools already carries.
 */
class OpenRouterIgnoredTest {

    static final ObjectMapper M = new ObjectMapper();

    @Test
    void theListIsReadAsAPersonWritesIt() {
        assertEquals(List.of("baidu", "alibaba", "deepinfra/turbo"), DriveClient.slugs(" Baidu, alibaba  deepinfra/turbo,baidu ,"));
        assertEquals(List.of(), DriveClient.slugs(null));
        assertEquals(List.of(), DriveClient.slugs("   "));
        assertEquals(List.of("z-ai"), DriveClient.slugs("z-ai, \"quoted\", ../up"), "what is no slug is left out");
    }

    @Test
    void theListGoesIntoTheBodyBesideWhatTheProviderObjectAlreadySays() {
        ObjectNode withTools = M.createObjectNode().put("model", "m");
        withTools.putObject("provider").put("require_parameters", true);
        DriveClient.leaveOut(withTools, List.of("baidu", "alibaba"));
        assertTrue(withTools.path("provider").path("require_parameters").asBoolean(), "what was there stays");
        assertEquals("[\"baidu\",\"alibaba\"]", withTools.path("provider").path("ignore").toString());

        ObjectNode plain = M.createObjectNode().put("model", "m");
        DriveClient.leaveOut(plain, List.of("baidu"));
        assertEquals("{\"ignore\":[\"baidu\"]}", plain.path("provider").toString());
    }

    @Test
    void withNoListTheBodyIsAsItWas() {
        ObjectNode plain = M.createObjectNode().put("model", "m");
        DriveClient.leaveOut(plain, List.of());
        assertFalse(plain.has("provider"));
    }
}
