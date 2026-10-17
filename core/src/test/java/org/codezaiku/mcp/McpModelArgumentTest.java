package org.codezaiku.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MCP code, review and research tools take a model: a saved model's name, or a model id on the drive. */
class McpModelArgumentTest {

    @Test
    void theCodeReviewAndResearchToolsTakeAModel() throws Exception {
        JsonNode tools = McpServer.handle("tools/list", new ObjectMapper().createObjectNode()).path("tools");
        int seen = 0;
        for (JsonNode t : tools) {
            String name = t.path("name").asText();
            if (!List.of("code", "review", "research").contains(name)) continue;
            JsonNode model = t.path("inputSchema").path("properties").path("model");
            assertEquals("string", model.path("type").asText(), name + " takes a model");
            assertTrue(model.path("description").asText().contains("saved model"), model.toString());
            seen++;
        }
        assertEquals(3, seen);
    }
}
