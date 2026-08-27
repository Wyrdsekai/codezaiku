package org.codezaiku.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of the ACP surface that are pure enough to pin here. The wire behaviour — framing,
 * dispatch, streaming, cancellation — is exercised end to end against a real client conversation
 * rather than mocked, since a mock of a protocol tends to agree with whatever the code does.
 */
class AcpServerTest {

    private static final ObjectMapper J = new ObjectMapper();

    private static JsonNode parse(String s) {
        try {
            return J.readTree(s);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ---- initialize -----------------------------------------------------------------------------

    /** We answer with the version we will actually speak; v1 is current per the protocol's own repo. */
    @Test void announcesProtocolVersionOne() {
        assertEquals(1, AcpServer.initializeResult().get("protocolVersion").asInt());
        assertEquals(1, AcpServer.PROTOCOL_VERSION);
    }

    @Test void declaresNoSessionLoadingBecauseWeHoldNoSessionState() {
        assertFalse(AcpServer.initializeResult().at("/agentCapabilities/loadSession").asBoolean());
    }

    /** The coding path consumes text; claiming image or audio would invite content we cannot use. */
    @Test void declaresOnlyTextPromptCapability() {
        JsonNode p = AcpServer.initializeResult().at("/agentCapabilities/promptCapabilities");
        assertFalse(p.get("image").asBoolean());
        assertFalse(p.get("audio").asBoolean());
        assertFalse(p.get("embeddedContext").asBoolean());
    }

    // ---- capabilities we do not honour are REJECTED, not silently dropped -----------------------

    /**
     * A client that passes mcpServers believes the agent gained those tools and will reason about
     * having them. Silently ignoring the field leaves it wrong about its own capabilities; rejecting
     * tells it the truth immediately. Taken from deepseek-harness, whose ACP server does the same.
     */
    @Test void rejectsMcpServersRatherThanPretendingToUseThem() {
        assertTrue(AcpServer.unsupportedSessionOption(
                parse("{\"cwd\":\"/tmp\",\"mcpServers\":[{\"name\":\"x\"}]}")).isPresent());
    }

    @Test void rejectsAdditionalDirectories() {
        assertTrue(AcpServer.unsupportedSessionOption(
                parse("{\"cwd\":\"/tmp\",\"additionalDirectories\":[\"/etc\"]}")).isPresent());
    }

    /** Empty arrays are the normal case and must pass — rejecting those would break every client. */
    @Test void acceptsEmptyCapabilityArrays() {
        assertTrue(AcpServer.unsupportedSessionOption(
                parse("{\"cwd\":\"/tmp\",\"mcpServers\":[],\"additionalDirectories\":[]}")).isEmpty());
        assertTrue(AcpServer.unsupportedSessionOption(parse("{\"cwd\":\"/tmp\"}")).isEmpty());
    }

    @Test void identifiesItselfWithNameAndVersion() {
        JsonNode info = AcpServer.initializeResult().get("agentInfo");
        assertEquals("CodeZaiku", info.get("name").asText());
        assertTrue(info.has("version"));
    }

    /** No auth: the model endpoint comes from the environment, not from the client. */
    @Test void advertisesNoAuthMethods() {
        assertEquals(0, AcpServer.initializeResult().get("authMethods").size());
    }

    // ---- prompt content -------------------------------------------------------------------------

    @Test void readsASingleTextBlock() {
        assertEquals("fix the bug",
                AcpServer.promptText(parse("[{\"type\":\"text\",\"text\":\"fix the bug\"}]")));
    }

    @Test void joinsMultipleTextBlocks() {
        assertEquals("first\n\nsecond", AcpServer.promptText(
                parse("[{\"type\":\"text\",\"text\":\"first\"},{\"type\":\"text\",\"text\":\"second\"}]")));
    }

    /** Content types we did not advertise must be skipped, not stringified into the task. */
    @Test void ignoresNonTextBlocksRatherThanGarblingThem() {
        assertEquals("keep this", AcpServer.promptText(parse(
                "[{\"type\":\"image\",\"data\":\"AAAA\"},{\"type\":\"text\",\"text\":\"keep this\"}]")));
    }

    @Test void emptyOrMalformedPromptYieldsNoText() {
        assertEquals("", AcpServer.promptText(parse("[]")));
        assertEquals("", AcpServer.promptText(parse("{}")));
        assertEquals("", AcpServer.promptText(parse("[{\"type\":\"image\"}]")));
    }

    // ---- tool taxonomy --------------------------------------------------------------------------

    /** A client renders a diff for an edit and a console for an execute; the mapping is user-visible. */
    @Test void mapsToolsToTheProtocolTaxonomy() {
        assertEquals("read", AcpServer.kind("read_file"));
        assertEquals("read", AcpServer.kind("read_dep_source"));
        assertEquals("edit", AcpServer.kind("write_file"));
        assertEquals("edit", AcpServer.kind("edit_file"));
        assertEquals("edit", AcpServer.kind("replace_symbol"));
        assertEquals("execute", AcpServer.kind("shell"));
        assertEquals("fetch", AcpServer.kind("web_fetch"));
        assertEquals("search", AcpServer.kind("web_search"));
    }

    /** Anything unmapped must still be a LEGAL ToolKind, or the client rejects the notification. */
    @Test void unknownToolsGetALegalKind() {
        assertEquals("other", AcpServer.kind("task_done"));
        assertEquals("other", AcpServer.kind("some_future_tool"));
    }

    @Test void titlesFileToolsWithTheirPath() {
        assertEquals("write_file app/main.py",
                AcpServer.title("write_file", parse("{\"path\":\"app/main.py\"}")));
    }

    @Test void titlesShellWithTheCommand() {
        assertEquals("pytest -q", AcpServer.title("shell", parse("{\"command\":\"pytest -q\"}")));
    }

    /** A pasted heredoc would otherwise put hundreds of lines in the client's tool list. */
    @Test void truncatesAnOverlongCommandTitle() {
        String long1 = "echo " + "x".repeat(300);
        String t = AcpServer.title("shell", parse(J.createObjectNode().put("command", long1).toString()));
        assertTrue(t.length() <= 81, "title should be clipped, was " + t.length());
        assertTrue(t.endsWith("…"));
    }

    @Test void fallsBackToTheToolNameWhenThereIsNothingBetter() {
        assertEquals("task_done", AcpServer.title("task_done", parse("{}")));
        assertEquals("shell", AcpServer.title("shell", null));
    }
}
