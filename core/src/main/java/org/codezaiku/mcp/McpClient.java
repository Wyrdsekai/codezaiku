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
 * <p>One reader thread per server takes every line the server writes: a response completes the
 * request waiting on its id, a request FROM the server ({@code ping}, {@code roots/list}) is
 * answered, a progress notification pushes the waiting request's deadline out. A second thread
 * drains the server's stderr, because an undrained pipe fills at 64 KB and the server then blocks
 * on its own logging. The wait for a response is a real timeout: a server that goes silent fails
 * the call instead of hanging the chat.
 */
public final class McpClient implements AutoCloseable {

    /** A tool as the remote server advertises it. */
    public record RemoteTool(String name, String description, ObjectNode schema) { }

    /** How long a request waits with no response and no progress notification. */
    static final int TIMEOUT_SECONDS = org.codezaiku.Config.getInt("CODEZAIKU_MCP_TIMEOUT_SECONDS", 60);
    /** A server that keeps returning a cursor is cut off here. */
    static final int MAX_PAGES = 50;

    private final String serverName;
    private final Process proc;
    private final OutputStream out;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong(1);
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.CompletableFuture<JsonNode>> waiting = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.ArrayDeque<String> stderrTail = new java.util.ArrayDeque<>();
    private volatile long lastProgressNanos = System.nanoTime();
    private final int timeoutSeconds;

    public McpClient(String serverName, List<String> command) throws IOException { this(serverName, command, TIMEOUT_SECONDS); }

    McpClient(String serverName, List<String> command, int timeoutSeconds) throws IOException { this(serverName, command, java.util.Map.of(), null, timeoutSeconds); }

    /** A server a host described: its own environment variables on top of ours, started in {@code cwd} when one is given. */
    public McpClient(String serverName, List<String> command, java.util.Map<String, String> env, java.nio.file.Path cwd) throws IOException { this(serverName, command, env, cwd, TIMEOUT_SECONDS); }

    McpClient(String serverName, List<String> command, java.util.Map<String, String> env, java.nio.file.Path cwd, int timeoutSeconds) throws IOException {
        this.serverName = serverName;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(false);
        if (env != null) pb.environment().putAll(env);
        if (cwd != null) pb.directory(cwd.toFile());
        this.proc = pb.start();
        this.out = proc.getOutputStream();
        daemon("mcp-" + serverName + "-out", this::readLoop);
        daemon("mcp-" + serverName + "-err", this::drainStderr);
        ObjectNode init;
        try {
            init = request("initialize", json.createObjectNode()
                    .put("protocolVersion", "2024-11-05")
                    .<ObjectNode>set("capabilities", json.createObjectNode())
                    .set("clientInfo", json.createObjectNode()
                            .put("name", "codezaiku").put("version", org.codezaiku.FamiliarMain.VERSION)));
        } catch (IOException e) { close(); throw e; }
        if (init == null) { String why = stderrTail(); close(); throw new IOException("mcp server '" + serverName + "' did not answer initialize" + (why.isEmpty() ? "" : " — it said: " + why)); }
        notify("notifications/initialized");
    }

    private static void daemon(String name, Runnable r) { Thread t = new Thread(r, name); t.setDaemon(true); t.start(); }

    public String serverName() {
        return serverName;
    }

    /** The last lines the server wrote to stderr: why it died, when it did. */
    String stderrTail() { synchronized (stderrTail) { return String.join(" | ", stderrTail); } }

    /** The server's tools, every page of them, or an empty list when it advertises none. */
    public List<RemoteTool> listTools() throws IOException {
        List<RemoteTool> tools = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            ObjectNode params = json.createObjectNode();
            if (cursor != null) params.put("cursor", cursor);
            ObjectNode r = request("tools/list", params);
            if (r == null) return tools;
            for (JsonNode t : r.path("tools")) {
                ObjectNode schema = t.path("inputSchema").isObject()
                        ? (ObjectNode) t.path("inputSchema").deepCopy()
                        : json.createObjectNode().put("type", "object");
                tools.add(new RemoteTool(t.path("name").asText(""),
                        t.path("description").asText(""), schema));
            }
            String next = r.path("nextCursor").asText("");
            if (next.isEmpty() || next.equals(cursor)) break;
            cursor = next;
        }
        return tools;
    }

    /**
     * Call one remote tool. Returns the concatenated text content, or the error text. A result that carries only
     * {@code structuredContent} comes back as that JSON. Arguments the model set to null are left out: a model writes
     * {@code "limit": null} for "not given", and a server checking its schema refuses null where it wants a number.
     */
    public String callTool(String tool, JsonNode args) throws IOException {
        ObjectNode params = json.createObjectNode();
        params.put("name", tool);
        params.set("arguments", args == null || !args.isObject() ? json.createObjectNode() : withoutNulls(args.deepCopy()));
        ObjectNode r = request("tools/call", params);
        if (r == null) return "ERROR: no response from mcp server " + serverName + " within " + timeoutSeconds + "s" + (proc.isAlive() ? "" : " (the server has exited" + (stderrTail().isEmpty() ? "" : ": " + stderrTail()) + ")");
        StringBuilder b = new StringBuilder();
        for (JsonNode c : r.path("content")) {
            if ("text".equals(c.path("type").asText())) {
                if (!b.isEmpty()) b.append('\n');
                b.append(c.path("text").asText(""));
            }
        }
        JsonNode structured = r.path("structuredContent");
        if (b.toString().isBlank() && (structured.isObject() || structured.isArray())) b.append(structured.toString());
        if (r.path("isError").asBoolean(false)) {
            return "ERROR from " + serverName + "/" + tool + ": " + b;
        }
        return b.toString();
    }

    /** The same tree with every null-valued object field removed, at any depth. Nulls inside arrays stay: there a null is a value. */
    static JsonNode withoutNulls(JsonNode node) {
        if (node.isObject()) {
            ObjectNode o = (ObjectNode) node;
            List<String> drop = new ArrayList<>();
            o.fields().forEachRemaining(e -> { if (e.getValue().isNull()) drop.add(e.getKey()); else withoutNulls(e.getValue()); });
            o.remove(drop);
        } else if (node.isArray()) {
            for (JsonNode n : node) withoutNulls(n);
        }
        return node;
    }

    private synchronized ObjectNode request(String method, ObjectNode params) throws IOException {
        long id = nextId.getAndIncrement();
        ObjectNode req = json.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        req.set("params", params);
        var future = new java.util.concurrent.CompletableFuture<JsonNode>();
        waiting.put(id, future);
        try {
            send(req);
            lastProgressNanos = System.nanoTime();
            JsonNode msg;
            while (true) {
                try {
                    msg = future.get(1, TimeUnit.SECONDS);
                    break;
                } catch (java.util.concurrent.TimeoutException notYet) {
                    if (!proc.isAlive() && !future.isDone()) return null;
                    if (System.nanoTime() - lastProgressNanos > TimeUnit.SECONDS.toNanos(timeoutSeconds)) return null;
                } catch (java.util.concurrent.ExecutionException e) {
                    return null;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (msg == null) return null;
            if (msg.has("error")) {
                throw new IOException("mcp " + serverName + " " + method + ": "
                        + msg.path("error").path("message").asText(msg.path("error").toString()));
            }
            JsonNode result = msg.path("result");
            return result.isObject() ? (ObjectNode) result : json.createObjectNode();
        } finally {
            waiting.remove(id);
        }
    }

    /** Every line the server writes. Ends when the server closes its stdout; whoever is waiting is released with nothing. */
    private void readLoop() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                JsonNode msg;
                try {
                    msg = json.readTree(line);
                } catch (Exception e) {
                    continue;    // a server's stray stdout line is noise, not a protocol failure
                }
                if (msg == null || !msg.isObject()) continue;
                if (msg.has("method")) {
                    if (msg.has("id")) answer(msg);
                    else if ("notifications/progress".equals(msg.path("method").asText())) lastProgressNanos = System.nanoTime();
                    continue;
                }
                var f = waiting.get(msg.path("id").asLong(-1));
                if (f != null) f.complete(msg);
            }
        } catch (IOException ignored) {
            // the pipe closed under us: same as end of stream
        }
        waiting.values().forEach(f -> f.complete(null));
    }

    /** A request FROM the server. We hold no roots and offer no sampling; a server that asks gets an answer, not silence. */
    private void answer(JsonNode req) {
        ObjectNode reply = json.createObjectNode();
        reply.put("jsonrpc", "2.0");
        reply.set("id", req.get("id"));
        switch (req.path("method").asText()) {
            case "ping" -> reply.set("result", json.createObjectNode());
            case "roots/list" -> reply.set("result", json.createObjectNode().set("roots", json.createArrayNode()));
            default -> reply.set("error", json.createObjectNode().put("code", -32601).put("message", "codezaiku does not implement " + req.path("method").asText()));
        }
        try { send(reply); } catch (IOException ignored) { }
    }

    private void drainStderr() {
        try (BufferedReader err = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = err.readLine()) != null) {
                synchronized (stderrTail) {
                    stderrTail.addLast(line.length() > 300 ? line.substring(0, 300) + "…" : line);
                    while (stderrTail.size() > 5) stderrTail.removeFirst();
                }
            }
        } catch (IOException ignored) { }
    }

    /** One JSON line to the server. Both the request path and the reader thread write, so the write is the locked part. */
    private void send(JsonNode msg) throws IOException {
        byte[] bytes = (json.writeValueAsString(msg) + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (out) { out.write(bytes); out.flush(); }
    }

    private void notify(String method) throws IOException {
        ObjectNode n = json.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("method", method);
        send(n);
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
