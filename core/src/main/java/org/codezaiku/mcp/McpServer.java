package org.codezaiku.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.FamiliarMain;
import org.codezaiku.loop.FamiliarLoop;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.codezaiku.library.ProjectConventions;
import org.codezaiku.ops.OpsDiscovery;
import org.codezaiku.research.ResearchMemory;

/**
 * CodeZaiku MCP server (stdio, JSON-RPC 2.0). Exposes CodeZaiku's capabilities as MCP tools so any MCP
 * client — Claude Desktop, another agent, or a larger agent fabric — can invoke them. MCP is the whole
 * interface: driving CodeZaiku as a component requires no CodeZaiku-side code.
 *
 * <p>v1 tools:
 * <ul>
 *   <li>{@code code}  — the coding familiar (read/edit/verify a project via the growing-conversation loop)</li>
 *   <li>{@code fix}   — the autonomous-SRE operator (sense→localize→card|recon→act→verify→rollback→harm-check),
 *       bounded by the R0–R4 authority ladder + blast-radius, exactly as the CLI {@code fix} verb</li>
 * </ul>
 *
 * <p><b>stdout is the protocol channel.</b> The loop's own {@code System.out} banners are redirected to
 * stderr for the server's lifetime, and logback already targets stderr — so JSON-RPC replies (written to
 * the ORIGINAL stdout captured at startup) are never corrupted.
 */
public final class McpServer {
    private static final ObjectMapper M = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String DEFAULT_DRIVE = "http://localhost:8200";

    private McpServer() { }

    /** Only tools whose name passes this are listed or callable; null = all. Set by `librarian mcp`. */
    private static volatile java.util.function.Predicate<String> toolFilter = null;

    /** Restrict every entry point (stdio and the daemon's /rpc) to the tools {@code filter} admits. */
    public static void setToolFilter(java.util.function.Predicate<String> filter) { toolFilter = filter; }

    /** Serve MCP over stdio with only the tools {@code filter} admits — `codezaiku librarian mcp`. */
    public static void serveStdio(java.util.function.Predicate<String> filter) throws Exception {
        toolFilter = filter;
        serveStdio();
    }

    /** Serve MCP over stdio until stdin closes. */
    public static void serveStdio() throws Exception {
        PrintStream protocol = System.out;          // protocol owns the real stdout
        System.setOut(System.err);                  // loop banners -> stderr (logback already -> stderr)
        var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        System.err.println("[codezaiku-mcp] ready on stdio (protocol " + PROTOCOL_VERSION + ", tools: code, fix)");
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode req;
            try { req = M.readTree(line); } catch (Exception e) { continue; }
            ObjectNode env = envelopeFor(req);
            if (env == null) continue;   // JSON-RPC notification -> no reply
            protocol.println(M.writeValueAsString(env));
            protocol.flush();
        }
    }

    /** One request → its JSON-RPC envelope (result or error), or null for a notification. */
    public static ObjectNode envelopeFor(JsonNode req) {
        JsonNode id = req.get("id");
        if (id == null || id.isNull()) return null;
        String method = req.path("method").asText("");
        ObjectNode env = M.createObjectNode();
        env.put("jsonrpc", "2.0");
        env.set("id", id);
        try {
            env.set("result", handle(method, req.path("params")));
        } catch (RpcError e) {
            env.set("error", rpcError(e.code, e.getMessage(), e.data));
        } catch (Exception e) {
            env.set("error", rpcError(-32603, "internal error: " + e));
        }
        return env;
    }

    static JsonNode handle(String method, JsonNode params) {
        switch (method) {
            case "initialize": {
                ObjectNode r = M.createObjectNode();
                r.put("protocolVersion", PROTOCOL_VERSION);
                ObjectNode caps = M.createObjectNode();
                caps.set("tools", M.createObjectNode());
                r.set("capabilities", caps);
                ObjectNode info = M.createObjectNode();
                info.put("name", "codezaiku");
                info.put("version", org.codezaiku.FamiliarMain.VERSION);   // it said "0.1" whatever the release, through 0.3.3
                r.set("serverInfo", info);
                return r;
            }
            case "ping":
            case "notifications/initialized":
            case "initialized":
                return M.createObjectNode();
            case "tools/list":
                return toolsList();
            case "tools/call":
                return toolsCall(params);
            default:
                throw new RpcError(-32601, "method not found: " + method);
        }
    }

    private static JsonNode toolsList() {
        ArrayNode all = allTools();
        if (toolFilter == null) { ObjectNode r = M.createObjectNode(); r.set("tools", all); return r; }
        ArrayNode kept = M.createArrayNode();
        for (JsonNode t : all) if (toolFilter.test(t.get("name").asText())) kept.add(t);
        ObjectNode r = M.createObjectNode();
        r.set("tools", kept);
        return r;
    }

    private static ArrayNode allTools() {
        ArrayNode tools = M.createArrayNode();
        tools.add(tool("code",
                "Run CodeZaiku's coding familiar on an existing project: it reads the codebase, edits files, "
                + "runs the toolchain, and verifies via the project's own tests. Use for bug-fix, add-feature, "
                + "refactor, test-coverage, incident, and API-migration tasks. Blocks until the loop finishes.",
                schema(new String[]{"project", "goal"},
                        prop("project", "string", "Absolute path to the project directory on this machine."),
                        prop("goal", "string", "The coding task/spec in plain language."),
                        prop("mode", "string", "Optional C/M/R task-shape: create|maintain|repair "
                                + "(default: auto-detect from project state — empty->create, broken/bug->repair, else maintain)."),
                        prop("maxTurns", "integer", "Optional loop budget (default 40)."),
                        prop("drive", "string", "Optional model server URL (default " + DEFAULT_DRIVE + ")."),
                        prop("async", "boolean", "If true, run in the background and return a job id to poll with the job_status tool, instead of blocking until the tool concludes."))));
        tools.add(tool("fix",
                "Run CodeZaiku's autonomous-SRE operator on a stack or host: senses health, localizes the root "
                + "cause, applies a fix (validated card or read-only-recon-derived), verifies, rolls back on "
                + "failure, and harm-checks. Bounded by blast-radius and an authority ladder "
                + "(observe<localize<propose<guarded<unattended). Blocks until it concludes.",
                schema(new String[]{"scope"},
                        prop("scope", "string", "Target: 'local', a compose project name, "
                                + "ssh://[user@]host/<project>, or docker://<container>."),
                        prop("incident", "string", "Optional incident/symptom description to focus the operator."),
                        prop("ceiling", "string", "Optional authority ceiling: "
                                + "observe|localize|propose|guarded|unattended (default propose)."),
                        prop("drive", "string", "Optional model server URL (default " + DEFAULT_DRIVE + ")."),
                        prop("async", "boolean", "If true, run in the background and return a job id to poll with the job_status tool, instead of blocking until the tool concludes."))));
        tools.add(tool("explore_and_fix",
                "Explore an entire machine and fix what's broken — boundary = THAT machine. Enumerates all "
                + "docker compose stacks, failed systemd units, and host disk/memory pressure; runs the SRE "
                + "operator on each unhealthy compose stack (blast-radius + authority-ladder bounded); surfaces "
                + "systemd/host issues for attention. Use to autonomously triage a whole box, not one known stack.",
                schema(new String[]{},
                        prop("target", "string", "The machine: 'local' (default), ssh://[user@]host, or docker://<container>."),
                        prop("ceiling", "string", "Optional authority ceiling: "
                                + "observe|localize|propose|guarded|unattended (default propose)."),
                        prop("drive", "string", "Optional model server URL (default " + DEFAULT_DRIVE + ")."),
                        prop("async", "boolean", "If true, run in the background and return a job id to poll with the job_status tool, instead of blocking until the tool concludes."))));
        tools.add(tool("review",
                "READ-ONLY code review: navigate a project (or a git diff) and return a structured findings list "
                + "([high|med|low] file:line — problem — suggested fix) covering correctness, security, and "
                + "maintainability. Modifies nothing.",
                schema(new String[]{"project"},
                        prop("project", "string", "Absolute path to the project directory."),
                        prop("scope", "string", "Optional git ref/range to review (e.g. 'HEAD~1', 'main..HEAD'); omit for the whole tree."),
                        prop("maxTurns", "integer", "Optional loop budget (default 30)."),
                        prop("drive", "string", "Optional model server URL (default " + DEFAULT_DRIVE + ")."),
                        prop("async", "boolean", "If true, run in the background and return a job id to poll with job_status."))));
        tools.add(tool("research",
                "Research a question on the WEB (searches, fetches and reads real sources, then synthesizes a "
                + "cited answer). mode=broad surveys the landscape across many sources; mode=depth reads the best "
                + "sources thoroughly to answer one narrow question. Read-only.",
                schema(new String[]{"question"},
                        prop("question", "string", "The research question."),
                        prop("mode", "string", "broad (survey the landscape, default) | depth (deep-read a narrow question)."),
                        prop("maxTurns", "integer", "Optional loop budget (default 30)."),
                        prop("drive", "string", "Optional model server URL (default " + DEFAULT_DRIVE + ")."),
                        prop("async", "boolean", "If true, run in the background and return a job id to poll with job_status."))));
        tools.add(tool("research_memory",
                "Inspect the RESEARCH MEMORY POOL — findings accumulated from earlier research runs. Omit query "
                + "for a summary of what has been researched; pass a query to recall relevant prior findings "
                + "(so you can build on them instead of re-researching).",
                schema(new String[]{},
                        prop("query", "string", "Optional: recall findings relevant to this question."),
                        prop("limit", "integer", "Optional max findings to return (default 10)."))));
        tools.add(tool("investigate",
                "READ-ONLY incident diagnosis / RCA on a stack or host: senses and localizes the root cause but "
                + "does NOT remediate. The safe 'just tell me what's wrong' entry point.",
                schema(new String[]{"target"},
                        prop("target", "string", "Target: 'local', a compose project, ssh://[user@]host/<project>, or docker://<container>."),
                        prop("incident", "string", "Optional symptom/incident description to focus the diagnosis."),
                        prop("drive", "string", "Optional model server URL (default " + DEFAULT_DRIVE + ")."),
                        prop("async", "boolean", "If true, run in the background and return a job id to poll with job_status."))));
        tools.add(tool("secure",
                "SECURITY review of a machine or stack: static posture (privileged containers, docker.sock "
                + "mounts, datastores published on 0.0.0.0, weak/default credentials, image CVEs) PLUS runtime "
                + "intrusion detections from the syscall detector, and for a live detection a read-only "
                + "investigation proposing ONE surgical containment command. REPORTS ONLY — it never applies a "
                + "containment action, because a wrong containment is a self-inflicted outage.",
                schema(new String[]{"target"},
                        prop("target", "string", "Target: 'local', ssh://[user@]host, or docker://<container>."),
                        prop("subject", "string", "Optional container/service name to focus the review on."),
                        prop("drive", "string", "Optional model server URL (default " + DEFAULT_DRIVE + ")."),
                        prop("async", "boolean", "If true, run in the background and return a job id to poll with job_status."))));
        tools.add(tool("record_convention",
                "Record a CONVENTION for a project — a norm the familiar should follow HERE (typically from a "
                + "bondholder accept/correct on its work). Builds the project's coding DNA, which is loaded into "
                + "context on future `code` runs for that project.",
                schema(new String[]{"project", "convention"},
                        prop("project", "string", "Absolute path to the project directory."),
                        prop("convention", "string", "The norm, e.g. 'use snake_case for functions' or 'prefer stdlib over new deps'."),
                        prop("kind", "string", "Optional: correct (strong — a correction) | accept (weak positive)."))));
        tools.add(tool("show_conventions",
                "Show a project's accumulated conventions (its coding DNA).",
                schema(new String[]{"project"},
                        prop("project", "string", "Absolute path to the project directory."))));
        tools.add(tool("job_status",
                "Poll an async tool job submitted with async:true. Returns 'running', or the final result when "
                + "the job is done/failed.",
                schema(new String[]{"jobId"},
                        prop("jobId", "string", "The job id returned when a tool was submitted with async:true."))));
        return tools;
    }

    private static JsonNode toolsCall(JsonNode params) {
        String name = params.path("name").asText("");
        if (toolFilter != null && !toolFilter.test(name)) throw new RpcError(-32601, "unknown tool: " + name);
        JsonNode args = params.path("arguments");
        if ("job_status".equals(name)) return jobStatus(args);
        // ASYNC mode ({"async": true}): submit the (minutes-long) work to a background job and return a job id
        // immediately, so an asynchronous caller polls job_status instead of holding one MCP
        // request open. Default is synchronous (block until the tool concludes) for simple clients.
        if (args.path("async").asBoolean(false)) {
            String jobId = JobRegistry.get().submit(name, () -> execTool(name, args));
            return toolContent("job " + jobId + " submitted (running). Poll it with the job_status tool: "
                    + "{\"jobId\": \"" + jobId + "\"}.", false);
        }
        JobRegistry.ToolResult r = execTool(name, args);   // an unknown tool propagates to a JSON-RPC error; bad arguments come back as an isError result
        return toolContent(r.text(), r.isError());
    }

    /** Execute one tool synchronously → (text, isError). An unknown tool throws RpcError; a missing required
     *  argument and an execution failure both become an isError result the calling model can read. Shared by the sync and async paths. */
    private static JobRegistry.ToolResult execTool(String name, JsonNode args) {
        try {
            switch (name) {
                case "code": {
                    Path project = Path.of(reqStr(args, "project"));
                    String goal = reqStr(args, "goal");
                    int maxTurns = args.path("maxTurns").asInt(40);
                    String drive = args.path("drive").asText(DEFAULT_DRIVE);
                    String mode = args.hasNonNull("mode") ? args.get("mode").asText() : null;
                    FamiliarLoop.Result res = FamiliarMain.runLoop(project, goal, drive, maxTurns, "none", mode);
                    // isError is reserved for tool FAILURE. Hitting the turn budget is a completion STATUS, not
                    // an error — the loop ran and may have produced correct work; the caller re-invokes to continue.
                    return new JobRegistry.ToolResult("status: " + (res.done()
                                    ? "completed (task_done)"
                                    : "incomplete (reached maxTurns=" + maxTurns + "; re-invoke to continue)")
                            + "\nturns: " + res.turns() + "\n\n" + res.summary(), false);
                }
                case "fix": {
                    String scope = reqStr(args, "scope");
                    String incident = args.path("incident").asText("");
                    String ceiling = args.hasNonNull("ceiling") ? args.get("ceiling").asText() : null;
                    String drive = args.path("drive").asText(DEFAULT_DRIVE);
                    var sc = OpsDiscovery.parseScope(scope);
                    FamiliarMain.OpsOutcome out =
                            FamiliarMain.ops(sc.target(), incident, drive, 25, null, sc.project(), ceiling);
                    return new JobRegistry.ToolResult("fix concluded: " + out.line(), out.harmed());
                }
                case "explore_and_fix": {
                    String target = args.path("target").asText("local");
                    String ceiling = args.hasNonNull("ceiling") ? args.get("ceiling").asText() : null;
                    String drive = args.path("drive").asText(DEFAULT_DRIVE);
                    return new JobRegistry.ToolResult(FamiliarMain.triage(target, drive, 20, ceiling), false);
                }
                case "record_convention": {
                    Path project = Path.of(reqStr(args, "project"));
                    String convention = reqStr(args, "convention");
                    String kind = args.hasNonNull("kind") ? args.get("kind").asText() : "note";
                    ProjectConventions.record(project, convention, kind);
                    return new JobRegistry.ToolResult("recorded [" + kind + "] convention for " + project, false);
                }
                case "show_conventions": {
                    Path project = Path.of(reqStr(args, "project"));
                    String raw = ProjectConventions.raw(project).strip();
                    return new JobRegistry.ToolResult(
                            raw.isEmpty() ? "(no conventions recorded for this project yet)" : raw, false);
                }
                case "review": {
                    Path project = Path.of(reqStr(args, "project"));
                    String scope = args.hasNonNull("scope") ? args.get("scope").asText() : null;
                    int maxTurns = args.path("maxTurns").asInt(30);
                    String drive = args.path("drive").asText(DEFAULT_DRIVE);
                    // Anchor here too: an agent consuming this over MCP has even less ability than a
                    // human to notice that a line number was invented.
                    String summary = FamiliarMain.review(project, scope, drive, maxTurns).summary();
                    return new JobRegistry.ToolResult(FamiliarMain.anchorReview(project, scope, summary), false);
                }
                case "research": {
                    String question = reqStr(args, "question");
                    String mode = args.path("mode").asText("broad");
                    int maxTurns = args.path("maxTurns").asInt(30);
                    String drive = args.path("drive").asText(DEFAULT_DRIVE);
                    var res = FamiliarMain.research(question, mode, drive, maxTurns);
                    return new JobRegistry.ToolResult(res.summary(), false);
                }
                case "research_memory": {
                    String q = args.hasNonNull("query") ? args.get("query").asText() : null;
                    if (q == null || q.isBlank())
                        return new JobRegistry.ToolResult(ResearchMemory.summary(), false);
                    var hits = ResearchMemory.recall(q, args.path("limit").asInt(10));
                    if (hits.isEmpty())
                        return new JobRegistry.ToolResult("(nothing in the research pool matches: " + q + ")", false);
                    StringBuilder sb = new StringBuilder("recalled " + hits.size() + " finding(s):\n");
                    for (var h : hits) sb.append("- ").append(h.claim())
                            .append(h.source().isBlank() ? "" : "  [" + h.source() + "]").append('\n');
                    return new JobRegistry.ToolResult(sb.toString(), false);
                }
                case "investigate": {
                    String target = reqStr(args, "target");
                    String incident = args.path("incident").asText("");
                    String drive = args.path("drive").asText(DEFAULT_DRIVE);
                    return new JobRegistry.ToolResult(FamiliarMain.investigate(target, incident, drive), false);
                }
                case "secure": {
                    String target = reqStr(args, "target");
                    String subject = args.path("subject").asText("");
                    String drive = args.path("drive").asText(DEFAULT_DRIVE);
                    return new JobRegistry.ToolResult(FamiliarMain.secure(target, subject, drive), false);
                }
                default:
                    throw new RpcError(-32602, "unknown tool: " + name);
            }
        } catch (RpcError e) {
            throw e;
        } catch (ToolInputError e) {
            return new JobRegistry.ToolResult(e.getMessage() + ". Call " + name + " again with it; tools/list has the tool's arguments.", true);
        } catch (Exception e) {
            return new JobRegistry.ToolResult("tool error: " + e, true);
        }
    }

    private static JsonNode jobStatus(JsonNode args) {
        if (args.path("jobId").asText("").isBlank()) return toolContent("missing required argument: jobId. Call job_status again with the id a job submission returned.", true);
        String jobId = reqStr(args, "jobId");
        JobRegistry.Job j = JobRegistry.get().job(jobId);
        if (j == null) return toolContent("no such job: " + jobId, true);
        long secs = j.elapsedMs() / 1000;
        return switch (j.state()) {
            case RUNNING -> toolContent("status: running (" + secs + "s elapsed)", false);
            case DONE -> toolContent("status: done (" + secs + "s)\n\n" + j.result(), j.isError());
            case FAILED -> toolContent("status: failed (" + secs + "s)\n\n" + j.result(), true);
        };
    }


    private static ObjectNode arrayProp(String name, String description) {
        ObjectNode spec = M.createObjectNode();
        spec.put("type", "array");
        spec.put("description", description);
        ObjectNode wrap = M.createObjectNode();
        wrap.set(name, spec);
        return wrap;
    }

    private static JsonNode toolContent(String text, boolean isError) {
        ObjectNode content = M.createObjectNode();
        content.put("type", "text");
        content.put("text", text);
        ObjectNode r = M.createObjectNode();
        r.set("content", M.createArrayNode().add(content));
        r.put("isError", isError);
        return r;
    }

    // ---- schema/helper builders ----
    private static ObjectNode tool(String name, String desc, ObjectNode inputSchema) {
        ObjectNode t = M.createObjectNode();
        t.put("name", name);
        t.put("description", desc);
        t.set("inputSchema", inputSchema);
        return t;
    }

    /** Build a JSON-Schema object node from a required-list and (name -> {type,description}) property nodes. */
    private static ObjectNode schema(String[] required, ObjectNode... properties) {
        ObjectNode s = M.createObjectNode();
        s.put("type", "object");
        ObjectNode props = M.createObjectNode();
        for (ObjectNode p : properties) props.setAll(p);
        s.set("properties", props);
        ArrayNode req = M.createArrayNode();
        for (String r : required) req.add(r);
        s.set("required", req);
        return s;
    }

    private static ObjectNode prop(String name, String type, String description) {
        ObjectNode spec = M.createObjectNode();
        spec.put("type", type);
        spec.put("description", description);
        ObjectNode wrap = M.createObjectNode();
        wrap.set(name, spec);
        return wrap;
    }

    /**
     * A tool call whose arguments are wrong. It is answered as a tool RESULT marked isError, not as a JSON-RPC error:
     * the host shows a tool result to the model that made the call, so the model reads "missing required argument:
     * project" and calls again with it. A JSON-RPC error goes to the host's error path, where most hosts stop. The MCP
     * specification asks for this split: a protocol error for an unknown tool or a malformed request, a tool result
     * for input the tool did not accept.
     */
    static final class ToolInputError extends RuntimeException {
        ToolInputError(String message) { super(message); }
    }

    private static String reqStr(JsonNode args, String key) {
        JsonNode v = args.get(key);
        if (v == null || v.isNull() || v.asText().isBlank())
            throw new ToolInputError("missing required argument: " + key);
        return v.asText();
    }

    private static ObjectNode rpcError(int code, String message) { return rpcError(code, message, null); }

    private static ObjectNode rpcError(int code, String message, String dataCode) {
        ObjectNode e = M.createObjectNode();
        e.put("code", code);
        e.put("message", message);
        if (dataCode != null) e.putObject("data").put("code", dataCode);
        return e;
    }

    private static final class RpcError extends RuntimeException {
        final int code;
        final String data;   // the library protocol's stable string code, or null
        RpcError(int code, String msg) { this(code, msg, null); }
        RpcError(int code, String msg, String data) { super(msg); this.code = code; this.data = data; }
    }
}
