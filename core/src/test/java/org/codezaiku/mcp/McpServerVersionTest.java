package org.codezaiku.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MCP server introduces itself as the release it is; it said "0.1" through 0.3.3. */
class McpServerVersionTest {
    @Test
    void initializeCarriesTheReleaseVersion() throws Exception {
        var reply = McpServer.handle("initialize", new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
        var info = reply.path("serverInfo");
        assertEquals("codezaiku", info.path("name").asText());
        assertEquals(org.codezaiku.FamiliarMain.VERSION, info.path("version").asText());
        assertTrue(info.path("version").asText().matches("\\d+\\.\\d+\\.\\d+"), reply.toString());
    }
}
