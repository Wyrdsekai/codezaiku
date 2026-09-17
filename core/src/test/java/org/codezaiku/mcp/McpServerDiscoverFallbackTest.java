package org.codezaiku.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A client on MCP revision 2026-07-28 opens with {@code server/discover}. A server from before that revision has to
 * answer "method not found" (-32601): that exact code is what sends the client back to the {@code initialize}
 * handshake. Any other answer (an internal error, silence, a closed pipe) and the client gives up. Checked against the
 * real Python SDK 2.2.0 client on 2026-09-17 with scripts/check-mcp-real-client.sh; this pins what that run relied on.
 */
class McpServerDiscoverFallbackTest {

    static final ObjectMapper M = new ObjectMapper();

    @Test
    void discoverIsMethodNotFoundWithTheRequestsIdAndTheHandshakeStillWorksAfterIt() throws Exception {
        var probe = McpServer.envelopeFor(M.readTree("{\"jsonrpc\":\"2.0\",\"id\":\"probe-1\",\"method\":\"server/discover\",\"params\":{\"protocolVersion\":\"2026-07-28\"}}"));
        assertEquals("probe-1", probe.path("id").asText(), "the error answers the probe's id");
        assertEquals(-32601, probe.path("error").path("code").asInt(), probe.toString());
        assertFalse(probe.has("result"));

        var init = McpServer.envelopeFor(M.readTree("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2026-07-28\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"0\"}}}"));
        assertEquals(2, init.path("id").asInt());
        assertTrue(init.path("result").path("protocolVersion").asText().matches("\\d{4}-\\d{2}-\\d{2}"), "a version we speak, whatever the client proposed");
        assertTrue(init.path("result").path("capabilities").has("tools"));

        assertNull(McpServer.envelopeFor(M.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")), "a notification gets no reply");
        var list = McpServer.envelopeFor(M.readTree("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}"));
        assertTrue(list.path("result").path("tools").size() > 0);
    }

    /** Wrong arguments are the calling model's to fix, so it has to see them: a tool result, marked as an error. An unknown tool is the host's mistake and stays a protocol error. */
    @Test
    void aMissingArgumentIsAToolResultTheModelCanReadAndAnUnknownToolIsAProtocolError() throws Exception {
        var missing = McpServer.envelopeFor(M.readTree("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"show_conventions\",\"arguments\":{}}}"));
        assertFalse(missing.has("error"), missing.toString());
        assertTrue(missing.path("result").path("isError").asBoolean(), missing.toString());
        String text = missing.path("result").path("content").path(0).path("text").asText();
        assertTrue(text.startsWith("missing required argument: project") && text.contains("show_conventions"), text);

        var status = McpServer.envelopeFor(M.readTree("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{\"name\":\"job_status\",\"arguments\":{}}}"));
        assertTrue(status.path("result").path("isError").asBoolean() && status.path("result").path("content").path(0).path("text").asText().contains("jobId"), status.toString());

        var unknown = McpServer.envelopeFor(M.readTree("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"no_such_tool\",\"arguments\":{}}}"));
        assertEquals(-32602, unknown.path("error").path("code").asInt(), unknown.toString());
    }
}
