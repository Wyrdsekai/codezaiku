package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.codezaiku.mcp.McpClient;

/**
 * One remote MCP tool, presented to the model as a local one named
 * {@code mcp_<server>_<tool>} — the namespace says whose process will act, and gives the consent
 * layer a stable name to hang standing answers on.
 *
 * <p>Whatever the remote tool returns is UNTRUSTED TEXT from another process — evidence, never
 * instruction — exactly like a container log or a web page; the loop's fencing already applies.
 */
public final class McpBridgeTool implements Tool {

    private final McpClient client;
    private final McpClient.RemoteTool remote;

    public McpBridgeTool(McpClient client, McpClient.RemoteTool remote) {
        this.client = client;
        this.remote = remote;
    }

    @Override
    public String name() {
        return "mcp_" + sanitize(client.serverName()) + "_" + sanitize(remote.name());
    }

    @Override
    public String description() {
        String d = remote.description().isBlank() ? remote.name() : remote.description();
        return "[" + client.serverName() + "] " + (d.length() > 180 ? d.substring(0, 177) + "..." : d);
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper j) {
        return remote.schema().deepCopy();
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        return client.callTool(remote.name(), args);
    }

    static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
