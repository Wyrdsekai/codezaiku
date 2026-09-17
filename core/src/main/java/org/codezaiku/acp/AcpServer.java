package org.codezaiku.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.codezaiku.Config;
import org.codezaiku.FamiliarMain;
import org.codezaiku.run.ResultDocument;
import org.codezaiku.run.WorkspaceFiles;
import org.codezaiku.tools.ShellTool;
import org.codezaiku.tools.ToolRegistry;
import org.codezaiku.verify.ProjectTests;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An Agent Client Protocol (ACP) agent, speaking JSON-RPC 2.0 over stdio.
 *
 * <p>ACP v1 — confirmed against the schema at {@code schema/v1/meta.json} in the protocol repo, whose
 * {@code rust/version.rs} pins {@code VERSION = V1}. A {@code schema/v2} directory exists but is not
 * the current version, and v2 additionally drops the {@code fs/*} and {@code terminal/*} client
 * methods, which we do not use either way.
 *
 * <p><b>Framing</b> is newline-delimited JSON — one object per line, flushed — not LSP-style
 * {@code Content-Length} headers. Confirmed against a working client rather than assumed.
 *
 * <p>Two design points that are not incidental:
 *
 * <ul>
 *   <li><b>A prompt runs on a worker thread.</b> {@code session/prompt} takes minutes, and
 *       {@code session/cancel} arrives on the same stdin while it is running. Handling the prompt on
 *       the read loop would make cancellation unreceivable — the one message that must get through.
 *   <li><b>stdout belongs to the protocol.</b> The coding path narrates to stdout and would corrupt
 *       the stream, so stdout is swapped to stderr before anything else runs, and the real handle is
 *       kept privately for protocol writes.
 * </ul>
 *
 * <p>We never call {@code fs/*} or {@code terminal/*}: CodeZaiku has its own confined file tools and
 * shell, so a client that declines those capabilities loses nothing.
 */
public final class AcpServer {

    /** The `_meta` key carrying the result document. A wire contract: see the emission site. */
    static final String META_KEY = "codezaiku";
    /** Its pre-rename spelling, still emitted for the 0.x line. */
    static final String META_KEY_LEGACY = "codeplane";


    /** The protocol version we speak. */
    static final int PROTOCOL_VERSION = 1;

    private static final ObjectMapper J = new ObjectMapper();

    /** The real stdout, reserved for protocol messages. */
    private final PrintStream wire;
    private final ExecutorService prompts = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "acp-prompt");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong sessionSeq = new AtomicLong();
    private final AtomicLong outboundId = new AtomicLong();
    private volatile boolean initialized;

    private AcpServer(PrintStream wire) {
        this.wire = wire;
    }

    /** For tests: a server writing to {@code wire}, and the two session calls without the JSON-RPC envelope. */
    static AcpServer forTest(PrintStream wire) { return new AcpServer(wire); }
    ObjectNode newSessionForTest(JsonNode params) { return newSession(params); }
    ObjectNode setConfigOptionForTest(String sessionId, JsonNode params) { return setConfigOption(sessions.get(sessionId), params); }

    public static void main(String[] args) throws Exception {
        PrintStream wire = System.out;
        // Before anything can log or narrate: stdout is the protocol channel from here on.
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        new AcpServer(wire).serve();
    }

    /** Read messages until stdin closes. */
    private void serve() throws Exception {
        var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode msg;
            try {
                msg = J.readTree(line);
            } catch (Exception e) {
                send(error(null, -32700, "parse error: " + e.getMessage()));
                continue;
            }
            try {
                dispatch(msg);
            } catch (Exception e) {
                JsonNode id = msg.get("id");
                if (id != null && !id.isNull()) send(error(id, -32603, "internal error: " + e));
            }
        }
        prompts.shutdownNow();
        sessions.values().forEach(Session::closeMcp);      // the client went away: its servers go with it
    }

    private void dispatch(JsonNode msg) {
        JsonNode idNode = msg.get("id");
        boolean isRequest = idNode != null && !idNode.isNull();
        String method = msg.path("method").asText(null);

        if (method == null) {
            // A RESPONSE to a request we sent (currently only session/request_permission). Routing it
            // here, on the read loop, is why a prompt must run on a worker: the thread awaiting this
            // answer is blocked, and if it were this one the answer could never arrive.
            if (isRequest) {
                var waiting = pending.remove(idNode.asText());
                if (waiting != null) waiting.complete(msg);
            }
            return;
        }
        JsonNode p = msg.path("params");

        switch (method) {
            case "initialize" -> {
                initialized = true;
                if (isRequest) send(result(idNode, initializeResult()));
            }
            case "authenticate" -> {
                // No auth: the model endpoint is configured by environment, not by the client.
                if (isRequest) send(result(idNode, J.createObjectNode()));
            }
            case "session/new" -> {
                if (!isRequest) return;
                try {
                    send(result(idNode, newSession(p)));
                } catch (IllegalArgumentException e) {
                    send(error(idNode, -32602, e.getMessage()));
                }
            }
            case "session/prompt" -> {
                if (!isRequest) return;
                Session s = sessions.get(p.path("sessionId").asText(""));
                if (s == null) {
                    send(error(idNode, -32602, "unknown sessionId"));
                    return;
                }
                // One prompt at a time per session. Two concurrent prompts would run two loops
                // against the SAME workspace, interleaving edits to the same files, and the file
                // ledger could not attribute either. ACP gives a session one prompt slot; enforce it
                // rather than discovering it as corrupted output.
                if (!s.claimPromptSlot()) {
                    send(error(idNode, -32600, "a prompt is already running in this session — "
                            + "wait for it to finish, or send session/cancel first"));
                    return;
                }
                // On a worker, so session/cancel can still be read while this runs.
                prompts.submit(() -> runPrompt(idNode, s, p));
            }
            case "session/set_config_option" -> {
                if (!isRequest) return;
                Session s = sessions.get(p.path("sessionId").asText(""));
                if (s == null) { send(error(idNode, -32602, "unknown sessionId")); return; }
                try {
                    send(result(idNode, setConfigOption(s, p)));
                } catch (IllegalArgumentException e) {
                    send(error(idNode, -32602, e.getMessage()));
                }
            }
            case "session/cancel" -> {
                Session s = sessions.get(p.path("sessionId").asText(""));
                if (s != null) s.cancel();
                // A notification: the pending prompt answers with stopReason "cancelled" itself.
            }
            case "session/close", "session/delete" -> {
                Session s = sessions.remove(p.path("sessionId").asText(""));
                if (s != null) { s.cancel(); s.closeMcp(); }
                if (isRequest) send(result(idNode, J.createObjectNode()));
            }
            case "$/cancel_request" -> { }        // implementation-dependent; safe to ignore
            default -> {
                if (isRequest) send(error(idNode, -32601, "method not supported: " + method));
            }
        }
    }

    // ---- initialize -----------------------------------------------------------------------------

    static ObjectNode initializeResult() {
        ObjectNode r = J.createObjectNode();
        // We answer with the version we will actually speak. A client asking for something newer
        // learns we speak 1 and decides whether to proceed — which is the negotiation.
        r.put("protocolVersion", PROTOCOL_VERSION);

        ObjectNode caps = r.putObject("agentCapabilities");
        // We hold no persistent session state, so there is nothing to load back.
        caps.put("loadSession", false);
        ObjectNode prompt = caps.putObject("promptCapabilities");
        prompt.put("image", false);           // the coding path consumes text
        prompt.put("audio", false);
        prompt.put("embeddedContext", false);
        ObjectNode mcp = caps.putObject("mcpCapabilities");
        mcp.put("http", false);
        mcp.put("sse", false);

        r.putArray("authMethods");            // none: credentials come from the environment
        ObjectNode info = r.putObject("agentInfo");
        info.put("name", "CodeZaiku");
        info.put("version", FamiliarMain.VERSION);
        return r;
    }

    // ---- session/new ----------------------------------------------------------------------------

    private ObjectNode newSession(JsonNode p) {
        String cwd = p.path("cwd").asText("");
        if (cwd.isBlank()) throw new IllegalArgumentException("cwd is required");
        Path ws = Path.of(cwd);
        if (!ws.isAbsolute()) {
            // A Unix-shaped path on Windows is the common way to get here: a client launched from Git
            // Bash or MSYS sends /c/Users/... , which a Windows JVM cannot resolve. Say so, rather
            // than leaving a correct-looking absolute path rejected for no stated reason.
            String hint = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                    && cwd.startsWith("/")
                    ? " — this looks like a Unix/MSYS path; on Windows send the native form "
                      + "(e.g. C:\\Users\\you\\project), which is what an editor-side ACP client sends"
                    : "";
            throw new IllegalArgumentException("cwd must be an absolute path: " + cwd + hint);
        }
        if (!Files.isDirectory(ws)) throw new IllegalArgumentException("cwd is not a directory: " + cwd);

        // Reject what we cannot honour instead of ignoring it: a client that passes an option believes
        // the agent took it, and silently dropping it leaves the client reasoning about something that
        // is not there.
        var unsupported = unsupportedSessionOption(p);
        if (unsupported.isPresent()) throw new IllegalArgumentException(unsupported.get());

        String id = "cp-" + sessionSeq.incrementAndGet();
        Session session = new Session(id, ws.normalize());
        // The MCP servers the client named. Every agent must take the stdio kind. They are started here, not at the
        // first prompt, so a server that cannot start fails session/new with its own words instead of leaving the
        // client believing in tools that never came.
        session.attach(startMcpServers(p.path("mcpServers"), session.cwd));
        session.offeredModels = MODELS.apply(Config.get("CODEZAIKU_DRIVE", "http://localhost:8200"));
        sessions.put(id, session);
        ObjectNode r = J.createObjectNode();
        r.put("sessionId", id);
        r.set("configOptions", configOptions(session));
        return r;
    }

    /**
     * A session option we cannot honour, or empty if the request is servable.
     *
     * <p>Rejecting beats ignoring: a client that passes an option believes the agent took it. A stdio
     * MCP server is taken (see {@link #startMcpServers}); an http or sse one is refused, because the
     * capabilities we announce say we do not offer those transports.
     */
    static Optional<String> unsupportedSessionOption(JsonNode p) {
        for (JsonNode server : p.path("mcpServers")) {
            String type = server.path("type").asText("");
            if (type.equals("http") || type.equals("sse")) {
                return Optional.of("the MCP server '" + server.path("name").asText("") + "' uses the " + type
                        + " transport, which this agent does not offer (its capabilities say so). "
                        + "Pass stdio servers only.");
            }
            if (server.path("command").asText("").isBlank()) {
                return Optional.of("the MCP server '" + server.path("name").asText("") + "' has no command to start.");
            }
        }
        JsonNode extra = p.path("additionalDirectories");
        if (extra.isArray() && !extra.isEmpty()) {
            return Optional.of("additionalDirectories is not supported — the session is "
                    + "confined to cwd, and a path outside it would be refused by the file tools "
                    + "anyway. Pass an empty array.");
        }
        return Optional.empty();
    }

    // ---- session/prompt -------------------------------------------------------------------------

    private void runPrompt(JsonNode rpcId, Session s, JsonNode p) {
        String text = promptText(p.path("prompt"), s.cwd);
        if (text.isBlank()) {
            send(error(rpcId, -32602, "prompt contained no text content"));
            return;
        }
        s.begin();
        String mcpNotice = s.mcpNoticeOnce();
        if (mcpNotice != null) {
            ObjectNode n = J.createObjectNode();
            n.put("sessionUpdate", "agent_message_chunk");
            n.putObject("content").put("type", "text").put("text", mcpNotice + "\n\n");
            sessionUpdate(s.id, n);
        }
        // usage_update after every model call: how much of the context window the session holds. The window is asked
        // of the drive once, on the first call; a drive that will not say gets no update, since a made-up size would
        // draw a wrong meter in the host.
        final String driveUrl = Config.get("CODEZAIKU_DRIVE", "http://localhost:8200");
        final String driveModel = s.model;
        final int[] window = {0};
        org.codezaiku.drive.DriveClient.USAGE_SINK.set(used -> {
            if (window[0] == 0) {
                try { window[0] = new org.codezaiku.drive.DriveClient(driveUrl, driveModel).contextWindow(); } catch (RuntimeException e) { window[0] = -1; }
            }
            if (window[0] > 0 && used > 0) sessionUpdate(s.id, usageUpdate(used, window[0]));
        });
        var before = WorkspaceFiles.snapshot(s.cwd);
        FamiliarMain.Tracked t = null;
        String failure = null;
        try {
            t = FamiliarMain.runLoopTracked(
                    s.cwd, text, Config.get("CODEZAIKU_DRIVE", "http://localhost:8200"),
                    Config.getInt("CODEZAIKU_RUN_MAX_TURNS", 40), "none", null,
                    s.model,
                    new FamiliarMain.Hooks(new ToolStream(s), s::isCancelled, s.mcpTools()));
        } catch (RuntimeException | Error e) {
            failure = e.toString();
        } finally {
            org.codezaiku.drive.DriveClient.USAGE_SINK.remove();
        }

        boolean cancelled = s.isCancelled();
        boolean done = t != null && t.result().done();
        String summary = t == null ? null : t.result().summary();

        if (summary != null && !summary.isBlank()) {
            ObjectNode u = J.createObjectNode();
            u.put("sessionUpdate", "agent_message_chunk");
            u.putObject("content").put("type", "text").put("text", summary);
            sessionUpdate(s.id, u);
        }

        List<String> fromGit = WorkspaceFiles.changedSince(s.cwd, before);
        List<String> files = WorkspaceFiles.merge(t == null ? List.of() : t.files(), fromGit);
        // Same oracle as the CLI, on the same final state, so both surfaces report one truth.
        var verdict = ProjectTests.verdict(s.cwd);
        var doc = ResultDocument.of(s.cwd, ResultDocument.statusFor(done && !cancelled, verdict))
                .files(files)
                .filesExcluded(WorkspaceFiles.lastExcludedCount())
                .fileProvenance(t != null && t.filesComplete(), before.available())
                .turns(t == null ? null : t.result().turns())
                .summary(summary)
                .interrupted(cancelled)
                .error(failure)
                .model(Config.get("CODEZAIKU_MODEL", "local-model"))
                .verdict(verdict)
                .build();

        ObjectNode r = J.createObjectNode();
        // Cancellation is reported as a stop reason, not an error — the turn ended, it did not fail.
        r.put("stopReason", cancelled ? "cancelled" : done ? "end_turn" : "refusal");
        // The same document the CLI writes to stdout, so a host parses one shape for both surfaces.
        //
        // Written under BOTH names for the whole 0.x line. This key is a wire contract, and the
        // rename from CodePlane moved it with no alias — the env-var fallback cannot reach a JSON
        // key. A host still reading `_meta.codeplane` does not get an error when the key moves; it
        // gets a missing node, the turn still reports success, and every typed artifact silently
        // degrades to scraping the text. Silent partial failure is the reason both are emitted
        // rather than one plus a changelog entry. Drops to `codezaiku` alone at 1.0.
        ObjectNode meta = r.putObject("_meta");
        meta.set(META_KEY, doc);
        meta.set(META_KEY_LEGACY, doc);
        send(result(rpcId, r));
        s.finish();
    }

    /** Who lists a drive's models. A field so a test needs no drive. */
    static java.util.function.Function<String, List<String>> MODELS = org.codezaiku.Models::offered;

    /**
     * The session's one setting: which model on the drive answers. It is a config option of category "model", the
     * protocol's stable way to offer a model picker, and what a harness uses to apply its {@code --model} flag. The
     * choices are the drive's own list with the configured model first. A drive that lists nothing still gets the
     * configured model as the one choice, so the option is always there and always truthful.
     */
    static com.fasterxml.jackson.databind.node.ArrayNode configOptions(Session s) {
        var all = J.createArrayNode();
        ObjectNode o = all.addObject();
        o.put("id", "model");
        o.put("name", "Model");
        o.put("description", "The model on the drive that answers this session.");
        o.put("category", "model");
        o.put("type", "select");
        o.put("currentValue", s.model);
        var options = o.putArray("options");
        var seen = new java.util.LinkedHashSet<String>();
        seen.add(s.model);
        seen.addAll(s.offeredModels);
        for (String m : seen) options.addObject().put("value", m).put("name", m);
        return all;
    }

    /** {@code session/set_config_option}. Only "model" exists, and only a model the option offered is taken. */
    static ObjectNode setConfigOption(Session s, JsonNode p) {
        String configId = p.path("configId").asText("");
        if (!configId.equals("model")) throw new IllegalArgumentException("unknown config option: " + configId + " (this agent offers: model)");
        String value = p.path("value").asText("");
        var offered = new java.util.LinkedHashSet<String>(s.offeredModels);
        offered.add(s.model);
        if (!offered.contains(value)) throw new IllegalArgumentException("the drive does not offer the model '" + value + "'. It offers: " + String.join(", ", offered));
        s.model = value;
        ObjectNode r = J.createObjectNode();
        r.set("configOptions", configOptions(s));
        return r;
    }

    /** How many tools the client's MCP servers may add. Every tool's schema is sent with every model call, and a small window has no room for ninety of them. */
    static final int MAX_MCP_TOOLS = Config.getInt("CODEZAIKU_ACP_MCP_MAX_TOOLS", 40);

    /** What starting a session's MCP servers produced: the running clients, their tools as loop tools, and a note for the person when some tools were left out. */
    record McpAttachment(List<org.codezaiku.mcp.McpClient> clients, List<org.codezaiku.tools.Tool> tools, String notice) {
        static McpAttachment none() { return new McpAttachment(List.of(), List.of(), null); }
    }

    /**
     * Start each stdio server, list its tools, and wrap them as {@code mcp_<server>_<tool>}. A server that does not
     * start, or does not answer, fails the whole call with its reason and stops the ones already started. Tools past
     * {@link #MAX_MCP_TOOLS} are left out in the order given, and the notice says how many and how to raise the limit.
     */
    static McpAttachment startMcpServers(JsonNode servers, Path cwd) { return startMcpServers(servers, cwd, MAX_MCP_TOOLS); }

    static McpAttachment startMcpServers(JsonNode servers, Path cwd, int maxTools) {
        if (!servers.isArray() || servers.isEmpty()) return McpAttachment.none();
        List<org.codezaiku.mcp.McpClient> clients = new java.util.ArrayList<>();
        List<org.codezaiku.tools.Tool> tools = new java.util.ArrayList<>();
        int offered = 0;
        try {
            for (JsonNode server : servers) {
                String name = server.path("name").asText("server");
                List<String> command = new java.util.ArrayList<>();
                command.add(server.path("command").asText());
                for (JsonNode a : server.path("args")) command.add(a.asText());
                Map<String, String> env = new java.util.LinkedHashMap<>();
                for (JsonNode e : server.path("env")) if (!e.path("name").asText("").isBlank()) env.put(e.path("name").asText(), e.path("value").asText(""));
                org.codezaiku.mcp.McpClient client;
                try {
                    client = new org.codezaiku.mcp.McpClient(name, command, env, cwd);
                } catch (Exception e) {
                    throw new IllegalArgumentException("the MCP server '" + name + "' did not start: " + e.getMessage());
                }
                clients.add(client);
                List<org.codezaiku.mcp.McpClient.RemoteTool> listed;
                try {
                    listed = client.listTools();
                } catch (Exception e) {
                    throw new IllegalArgumentException("the MCP server '" + name + "' did not list its tools: " + e.getMessage());
                }
                for (var remote : listed) {
                    offered++;
                    if (tools.size() < maxTools) tools.add(new org.codezaiku.tools.McpBridgeTool(client, remote));
                }
            }
        } catch (RuntimeException e) {
            clients.forEach(org.codezaiku.mcp.McpClient::close);
            throw e;
        }
        String notice = offered > tools.size()
                ? "Using " + tools.size() + " of the " + offered + " tools your MCP servers offer. Each tool is sent to the model with every call, "
                  + "so the rest were left out. Set CODEZAIKU_ACP_MCP_MAX_TOOLS to use more, or pass fewer servers."
                : null;
        return new McpAttachment(List.copyOf(clients), List.copyOf(tools), notice);
    }

    /** {@code usage_update}: tokens in the context now, and the size of the window. */
    static ObjectNode usageUpdate(int used, int size) {
        ObjectNode u = J.createObjectNode();
        u.put("sessionUpdate", "usage_update");
        u.put("used", used);
        u.put("size", size);
        return u;
    }

    /** The prompt as the task text, with no workspace to make an attached file's path relative to. */
    static String promptText(JsonNode prompt) { return promptText(prompt, null); }

    /**
     * The text blocks of a prompt, and the files and resources the host attached to it.
     *
     * <p>{@code resource_link} is the one block besides text that every agent must accept: a host that is told we do
     * not take embedded context sends a link instead, which is what an @-mention of a file becomes. Dropped, the
     * task read "fix the bug in" with nothing after it. A link is rendered as one line naming the file, relative to
     * the workspace when it is inside it, so the model can read it with its own tools. An embedded text resource,
     * which we do not ask for, is included when a host sends one anyway. Images and audio are skipped.
     * A name or an address is one line with control characters removed: it is a label, never more task text.
     */
    static String promptText(JsonNode prompt, java.nio.file.Path cwd) {
        if (!prompt.isArray()) return "";
        StringBuilder b = new StringBuilder();
        for (JsonNode block : prompt) {
            String piece = switch (block.path("type").asText()) {
                case "text" -> block.path("text").asText("");
                case "resource_link" -> attached(block.path("name").asText(""), block.path("uri").asText(""), cwd);
                case "resource" -> {
                    JsonNode r = block.path("resource");
                    String text = r.path("text").asText("");
                    yield text.isBlank() ? "" : attached("", r.path("uri").asText(""), cwd) + "\n```\n" + text + "\n```";
                }
                default -> "";
            };
            if (piece.isBlank()) continue;
            if (b.length() > 0) b.append("\n\n");
            b.append(piece);
        }
        return b.toString().strip();
    }

    /** One line for an attached file or resource. */
    static String attached(String name, String uri, java.nio.file.Path cwd) {
        String cleanName = oneLine(name), cleanUri = oneLine(uri);
        if (cleanUri.startsWith("file:")) {
            try {
                java.nio.file.Path file = java.nio.file.Path.of(java.net.URI.create(cleanUri)).normalize();
                java.nio.file.Path root = cwd == null ? null : cwd.toAbsolutePath().normalize();
                String shown = root != null && file.startsWith(root) ? root.relativize(file).toString().replace('\\', '/') : file.toString();
                return "Attached file: " + shown;
            } catch (RuntimeException e) {
                // not a usable file address: shown as a resource below
            }
        }
        if (cleanUri.isEmpty() && cleanName.isEmpty()) return "";
        return "Attached resource: " + (cleanName.isEmpty() ? cleanUri : cleanName + (cleanUri.isEmpty() ? "" : " (" + cleanUri + ")"));
    }

    private static String oneLine(String s) {
        String t = s == null ? "" : s.replaceAll("[\\p{Cntrl}]+", " ").strip();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    // ---- streaming ------------------------------------------------------------------------------

    /** Turns tool dispatch into ACP tool_call / tool_call_update notifications. */
    private final class ToolStream implements ToolRegistry.Listener {
        private final Session s;
        private final AtomicLong permitSeq =
                new AtomicLong();

        ToolStream(Session s) {
            this.s = s;
        }

        /**
         * Route git-state writes to the client's consent flow.
         *
         * <p>Only git writes are gated, because that is the case the host asked for: a workspace where
         * the human owns commits, branches and history while the agent is free to edit files. Reading
         * git is untouched — the file ledger depends on `git status`. Widening this to another class of
         * command is a line here, deliberately not a config surface, so that what is gated stays
         * legible rather than becoming a policy engine nobody can read.
         */
        @Override
        public String permit(String tool, JsonNode args) {
            // A remote tool runs in someone else's process and nothing here knows whether it changes anything, so
            // the person is asked, per tool: "allow for this session" covers that one tool and no other.
            if (tool.startsWith("mcp_")) {
                return askPermission(s, "permit_" + permitSeq.incrementAndGet(), tool, args, "the MCP tool `" + tool + "`");
            }
            if (!"shell".equals(tool)) return null;
            String cmd = args == null ? null : args.path("command").asText(null);
            if (!ShellTool.isGitWrite(cmd)) return null;
            return askPermission(s, "permit_" + permitSeq.incrementAndGet(), tool, args,
                    "the git command `" + (cmd.length() > 100 ? cmd.substring(0, 100) + "…" : cmd) + "`");
        }

        @Override
        public void started(String callId, String tool, JsonNode args) {
            ObjectNode u = J.createObjectNode();
            u.put("sessionUpdate", "tool_call");
            u.put("toolCallId", callId);
            u.put("title", title(tool, args));
            u.put("name", tool);             // the tool's own name, optional since schema 1.22.0: a host can key on it, a title is for people
            u.put("kind", kind(tool));
            u.put("status", "in_progress");
            u.set("rawInput", args == null ? J.createObjectNode() : args);
            String path = args == null ? null : args.path("path").asText(null);
            if (path != null && !path.isBlank()) {
                u.putArray("locations").addObject().put("path", s.cwd.resolve(path).toString());
            }
            sessionUpdate(s.id, u);
        }

        @Override
        public void finished(String callId, String tool, String out, boolean failed) {
            ObjectNode u = J.createObjectNode();
            u.put("sessionUpdate", "tool_call_update");
            u.put("toolCallId", callId);
            u.put("status", failed ? "failed" : "completed");
            if (out != null && !out.isBlank()) {
                ObjectNode c = u.putArray("content").addObject();
                c.put("type", "content");
                c.putObject("content").put("type", "text").put("text", clip(out));
            }
            sessionUpdate(s.id, u);
        }

        /** A tool result can be a whole file; the client wants a readable trace, not the payload. */
        private String clip(String s) {
            return s.length() <= 2000 ? s : s.substring(0, 2000) + "\n… (" + s.length() + " chars)";
        }
    }

    /** ACP's tool taxonomy, so a client can render the right affordance. */
    static String kind(String tool) {
        return switch (tool) {
            case "read_file", "read_dep_source" -> "read";
            case "write_file", "edit_file", "replace_symbol" -> "edit";
            case "shell" -> "execute";
            case "web_search", "library_search" -> "search";
            case "web_fetch" -> "fetch";
            default -> "other";
        };
    }

    static String title(String tool, JsonNode args) {
        String path = args == null ? null : args.path("path").asText(null);
        String cmd = args == null ? null : args.path("command").asText(null);
        if (path != null && !path.isBlank()) return tool + " " + path;
        if (cmd != null && !cmd.isBlank()) return cmd.length() > 80 ? cmd.substring(0, 80) + "…" : cmd;
        return tool;
    }

    private void sessionUpdate(String sessionId, ObjectNode update) {
        ObjectNode n = J.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("method", "session/update");
        ObjectNode p = n.putObject("params");
        p.put("sessionId", sessionId);
        p.set("update", update);
        send(n);
    }

    // ---- session state --------------------------------------------------------------------------

    private static final class Session {
        final String id;
        final Path cwd;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean running = new AtomicBoolean();
        /**
         * Standing answers from allow_always / reject_always. Keyed by WHAT was asked rather than by
         * tool, so "always allow `git commit`" does not silently also permit `git push`.
         */
        private final Map<String, Boolean> standing = new ConcurrentHashMap<>();

        /** A remembered decision for {@code what}, or null if the client has not been asked. */
        Boolean remembered(String what) {
            return standing.get(what);
        }

        void remember(String what, boolean allowed) {
            standing.put(what, allowed);
        }

        Session(String id, Path cwd) {
            this.id = id;
            this.cwd = cwd;
        }

        /** The model this session's prompts go to, and the models the drive listed when the session was made. */
        volatile String model = Config.get("CODEZAIKU_MODEL", "local-model");
        volatile List<String> offeredModels = List.of();

        /** The client's MCP servers, running for as long as the session is. */
        private volatile McpAttachment mcp = McpAttachment.none();
        private final AtomicBoolean noticeGiven = new AtomicBoolean();

        void attach(McpAttachment a) { this.mcp = a; }
        List<org.codezaiku.tools.Tool> mcpTools() { return mcp.tools(); }
        /** The note about left-out tools, once. */
        String mcpNoticeOnce() { return mcp.notice() != null && noticeGiven.compareAndSet(false, true) ? mcp.notice() : null; }
        void closeMcp() { mcp.clients().forEach(org.codezaiku.mcp.McpClient::close); mcp = McpAttachment.none(); }

        /** Take the session's single prompt slot; false if one is already in flight. */
        boolean claimPromptSlot() {
            return running.compareAndSet(false, true);
        }

        void begin() {
            cancelled.set(false);
            running.set(true);
        }

        void finish() {
            running.set(false);
            cancelled.set(false);
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        /**
         * Cancellation is cooperative: the loop checks between turns. Child processes are killed
         * outright because a cancel that leaves a 10-minute build running has not cancelled anything.
         */
        void cancel() {
            if (!running.get()) return;
            cancelled.set(true);
            try {
                List<ProcessHandle> kids = ProcessHandle.current().descendants().toList();
                kids.forEach(ProcessHandle::destroy);
            } catch (RuntimeException ignored) { }
        }
    }

    // ---- JSON-RPC plumbing ----------------------------------------------------------------------

    private ObjectNode result(JsonNode id, JsonNode value) {
        ObjectNode n = J.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.set("id", id);
        n.set("result", value);
        return n;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode n = J.createObjectNode();
        n.put("jsonrpc", "2.0");
        if (id == null) n.putNull("id");
        else n.set("id", id);
        ObjectNode e = n.putObject("error");
        e.put("code", code);
        e.put("message", message);
        return n;
    }

    /** One JSON object per line, flushed. Synchronized: notifications race prompt responses. */
    private synchronized void send(ObjectNode msg) {
        try {
            wire.println(J.writeValueAsString(msg));
            wire.flush();
        } catch (Exception e) {
            System.err.println("acp: could not serialize outbound message: " + e);
        }
    }

    // ---- outbound requests (session/request_permission) -----------------------------------------

    /** Requests we have sent and are awaiting a response to, by id. */
    private final Map<String, CompletableFuture<JsonNode>> pending =
            new ConcurrentHashMap<>();

    /**
     * How long to wait for a permission answer. Generous because the far side may be asking a HUMAN,
     * and a consent prompt that expires while someone is reading it is worse than one that waits.
     */
    private static final long PERMISSION_TIMEOUT_SECONDS =
            Config.getInt("CODEZAIKU_ACP_PERMISSION_TIMEOUT", 600);

    /** Send a request and block for the response. Returns null on timeout or transport failure. */
    private JsonNode call(String method, ObjectNode params, long timeoutSeconds) {
        String id = "cp-req-" + outboundId.incrementAndGet();
        var future = new CompletableFuture<JsonNode>();
        pending.put(id, future);
        ObjectNode req = J.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        req.set("params", params);
        send(req);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            pending.remove(id);
            return null;
        }
    }

    /**
     * Ask the client to authorise a tool call.
     *
     * <p>Returns null to allow, or the reason to refuse. The decision belongs to the client — this
     * only carries it. An unanswered request REFUSES: a consent gate that proceeds when nobody
     * answered has not gated anything, and the failure we would rather have is a task that stops and
     * says why, over one that quietly did the thing consent existed to prevent.
     */
    private String askPermission(Session s, String callId, String tool, JsonNode args, String what) {
        Boolean remembered = s.remembered(what);
        if (remembered != null) {
            return remembered ? null : refusal(what, "the client denied this for the whole session");
        }

        ObjectNode p = J.createObjectNode();
        p.put("sessionId", s.id);
        ObjectNode tc = p.putObject("toolCall");
        tc.put("toolCallId", callId);
        tc.put("title", title(tool, args));
        tc.put("name", tool);
        tc.put("kind", kind(tool));
        tc.put("status", "pending");
        tc.set("rawInput", args == null ? J.createObjectNode() : args);
        var opts = p.putArray("options");
        opts.addObject().put("optionId", "allow_once").put("name", "Allow").put("kind", "allow_once");
        opts.addObject().put("optionId", "allow_always").put("name", "Allow for this session")
                .put("kind", "allow_always");
        opts.addObject().put("optionId", "reject_once").put("name", "Deny").put("kind", "reject_once");
        opts.addObject().put("optionId", "reject_always").put("name", "Deny for this session")
                .put("kind", "reject_always");

        JsonNode reply = call("session/request_permission", p, PERMISSION_TIMEOUT_SECONDS);
        if (reply == null) {
            return refusal(what, "no answer from the client within " + PERMISSION_TIMEOUT_SECONDS + "s");
        }
        if (reply.has("error")) {
            return refusal(what, "the client could not handle the permission request ("
                    + reply.at("/error/message").asText("") + ")");
        }
        JsonNode outcome = reply.at("/result/outcome");
        String kind = outcome.path("outcome").asText("");
        if ("cancelled".equals(kind)) return refusal(what, "the permission request was cancelled");

        String chosen = outcome.path("optionId").asText("");
        if (chosen.startsWith("allow")) {
            if ("allow_always".equals(chosen)) s.remember(what, true);
            return null;
        }
        if ("reject_always".equals(chosen)) s.remember(what, false);
        return refusal(what, "the client denied it");
    }

    /**
     * Phrased for the MODEL, which reads this as a tool result and must be able to act on it. It says
     * what was refused and that retrying will not help, so the run adapts instead of looping.
     */
    private static String refusal(String what, String why) {
        return "REFUSED: " + what + " was not permitted — " + why + ". This is the operator's decision, "
                + "not a tool error, and retrying the same command will be refused again. Leave the "
                + "change in the working tree and continue; report what you did rather than committing.";
    }
}
