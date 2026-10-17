package org.codezaiku.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.codezaiku.Config;
import org.codezaiku.drive.SavedModels;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The ACP model option offers the saved models by name, each shown with its id and host, beside the drive's own models; a host may set one. */
class AcpSavedModelOptionTest {

    @TempDir Path home;
    private String realHome;

    @BeforeEach void scratchHome() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".codezaiku"));
        Config.set("CODEZAIKU_DRIVE", "http://127.0.0.1:9");
        Config.set("CODEZAIKU_MODEL", "local-model");
    }

    @AfterEach void realHomeBack() throws Exception { System.setProperty("user.home", realHome); Config.unset("CODEZAIKU_DRIVE"); }

    private static JsonNode parse(String s) throws Exception { return new ObjectMapper().readTree(s); }

    @Test
    void savedModelsAreChoicesByName(@TempDir Path ws) throws Exception {
        SavedModels.put("fable", "anthropic", "claude-fable-5-1", "5/25", null, null, null);
        var saved = AcpServer.MODELS;
        AcpServer.MODELS = base -> List.of("qwen3.8-27b", "embed");
        try {
            var server = AcpServer.forTest(new PrintStream(new ByteArrayOutputStream()));
            var made = server.newSessionForTest(parse("{\"cwd\":\"" + ws.toString().replace("\\", "\\\\") + "\",\"mcpServers\":[]}"));
            JsonNode options = made.path("configOptions").path(0).path("options");
            assertEquals("local-model", options.path(0).path("value").asText(), "the configured model first");
            assertEquals("fable", options.path(1).path("value").asText(), "then the saved models by name");
            assertEquals("fable — claude-fable-5-1 via api.anthropic.com", options.path(1).path("name").asText(), "shown with what they are");
            assertEquals("qwen3.8-27b", options.path(2).path("value").asText(), "then the drive's own");
            assertFalse(options.toString().contains("\"embed\""));
            var set = server.setConfigOptionForTest(made.path("sessionId").asText(), parse("{\"configId\":\"model\",\"value\":\"fable\"}"));
            assertEquals("fable", set.path("configOptions").path(0).path("currentValue").asText());
        } finally { AcpServer.MODELS = saved; }
    }
}
