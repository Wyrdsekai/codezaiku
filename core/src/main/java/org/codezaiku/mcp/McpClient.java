package org.codezaiku.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP from the OTHER side: {@code McpServer} makes CodeZaiku drivable by hosts; this makes
 * CodeZaiku a host — the chat can call tools that live in someone else's process (wyrdsekai's,
 * a browser bridge, anything speaking MCP over stdio).
 *
 * <p>Deliberately minimal, matching what our own server implements: stdio transport, JSON-RPC
 * 2.0, {@code initialize} → {@code tools/list} → {@code tools/call}. No resources, no prompts,
 * no HTTP transport — the first consumer decides what grows next.
 *
 * <p>One reader thread per server keeps requests strictly sequential; MCP servers are allowed
 * to interleave notifications between responses, and those are skipped by id-matching rather
 * than trusted to arrive in order.
 */
public final class McpClient implements AutoCloseable {

    /** A tool as the remote server advertises it. */
    public record RemoteTool(String name, String description, ObjectNode schema) { }

    private final String serverName;
    private final Process proc;
    private final BufferedReader in;
    private final OutputStream out;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong(1);

    public McpClient(String serverName, List<String> command) throws IOException {
        this.serverName = serverName;
        this.proc = new ProcessBuilder(command).redirectErrorStream(false).start();
        this.in = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        this.out = proc.getOutputStream();
        ObjectNode init = request("initialize", json.createObjectNode()
                .put("protocolVersion", "2024-11-05")
                .<ObjectNode>set("capabilities", json.createObjectNode())
                .set("clientInfo", json.createObjectNode()
                        .put("name", "codezaiku").put("version", org.codezaiku.FamiliarMain.VERSION)));
        if (init == null) throw new IOException("mcp server '" + serverName + "' did not answer initialize");
        notify("notifications/initialized");
    }

    public String serverName() {
        return serverName;
    }

    /** The server's tools, or an empty list when it advertises none. */
    public List<RemoteTool> listTools() throws IOException {
        ObjectNode r = request("tools/list", json.createObjectNode());
        List<RemoteTool> tools = new ArrayList<>();
        if (r == null) return tools;
        for (JsonNode t : r.path("tools")) {
            ObjectNode schema = t.path("inputSchema").isObject()
                    ? (ObjectNode) t.path("inputSchema").deepCopy()
                    : json.createObjectNode().put("type", "object");
            tools.add(new RemoteTool(t.path("name").asText(""),
                    t.path("description").asText(""), schema));
        }
        return tools;
    }

    /** Call one remote tool. Returns the concatenated text content, or the error text. */
    public String callTool(String tool, JsonNode args) throws IOException {
        ObjectNode params = json.createObjectNode();
        params.put("name", tool);
        params.set("arguments", args == null ? json.createObjectNode() : args.deepCopy());
        ObjectNode r = request("tools/call", params);
        if (r == null) return "ERROR: no response from mcp server " + serverName;
        StringBuilder b = new StringBuilder();
        for (JsonNode c : r.path("content")) {
            if ("text".equals(c.path("type").asText())) {
                if (!b.isEmpty()) b.append('\n');
                b.append(c.path("text").asText(""));
            }
        }
        if (r.path("isError").asBoolean(false)) {
            return "ERROR from " + serverName + "/" + tool + ": " + b;
        }
        return b.toString();
    }

    private synchronized ObjectNode request(String method, ObjectNode params) throws IOException {
        long id = nextId.getAndIncrement();
        ObjectNode req = json.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        req.set("params", params);
        out.write((json.writeValueAsString(req) + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        // Read until OUR response id; skip notifications and any stale interleavings.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        String line;
        while ((line = in.readLine()) != null) {
            if (System.nanoTime() > deadline) break;
            JsonNode msg;
            try {
                msg = json.readTree(line);
            } catch (Exception e) {
                continue;    // a server's stray stdout line is noise, not a protocol failure
            }
            if (msg.path("id").asLong(-1) != id) continue;
            if (msg.has("error")) {
                throw new IOException("mcp " + serverName + " " + method + ": "
                        + msg.path("error").path("message").asText(msg.path("error").toString()));
            }
            JsonNode result = msg.path("result");
            return result.isObject() ? (ObjectNode) result : json.createObjectNode();
        }
        return null;
    }

    private void notify(String method) throws IOException {
        ObjectNode n = json.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("method", method);
        out.write((json.writeValueAsString(n) + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @Override
    public void close() {
        proc.destroy();
        try {
            if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly();
        } catch (InterruptedException e) {
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Parse {@code CODEZAIKU_MCP_SERVERS}: {@code name=command args…} entries separated by
     * {@code ;}. Returns started clients; a server that fails to start is reported and skipped —
     * one broken config entry must not take the chat down.
     */
    public static List<McpClient> fromConfig(String spec, java.util.function.Consumer<String> report) {
        List<McpClient> clients = new ArrayList<>();
        if (spec == null || spec.isBlank()) return clients;
        for (String entry : spec.split(";")) {
            entry = entry.strip();
            if (entry.isEmpty()) continue;
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                report.accept("mcp: ignored malformed entry (want name=command): " + entry);
                continue;
            }
            String name = entry.substring(0, eq).strip();
            List<String> cmd = List.of("bash", "-lc", entry.substring(eq + 1).strip());
            try {
                clients.add(new McpClient(name, cmd));
            } catch (Exception e) {
                report.accept("mcp: server '" + name + "' failed to start: " + e.getMessage());
            }
        }
        return clients;
    }
}
