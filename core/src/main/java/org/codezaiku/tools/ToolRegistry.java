package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.codezaiku.lsp.LspClient;

/** Holds the familiar's tools, advertises their schemas, and dispatches calls by name. */
public final class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private PathScope scope;

    public ToolRegistry add(Tool t) {
        tools.put(t.name(), t);
        return this;
    }

    private ToolRegistry scope(PathScope s) {
        this.scope = s;
        return this;
    }

    /**
     * The confinement scope these tools share — and with it the run's write ledger, which a caller
     * needs to report which files the run touched. Null only for a registry built without one.
     */
    public PathScope scope() {
        return scope;
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /** The OpenAI-style {@code tools} array advertised to the drive. */
    public ArrayNode toolsArray(ObjectMapper j) {
        ArrayNode arr = j.createArrayNode();
        for (Tool t : tools.values()) {
            ObjectNode entry = arr.addObject();
            entry.put("type", "function");
            ObjectNode fn = entry.putObject("function");
            fn.put("name", t.name());
            fn.put("description", t.description());
            fn.set("parameters", t.parametersSchema(j));
        }
        return arr;
    }

    /**
     * Observes tool activity. Exists so a protocol host can stream what the agent is DOING rather than
     * only what it concluded — this dispatch point is the one place every tool call passes through, so
     * an observer here cannot miss one.
     */
    public interface Listener {
        /**
         * Consulted BEFORE the tool runs. Return a reason to prevent it, or null to allow.
         *
         * <p>This is NOT the harness overruling the model — that stays forbidden, and no rule in this
         * codebase rejects an action because we think it unwise. It is how an EXTERNAL authority (the
         * user, or the host acting for them) exercises consent it already holds: the decision is made
         * outside the harness and merely carried through here. Denial comes back to the model as an
         * ordinary observation it can adapt to, not an exception.
         *
         * <p>Nothing is gated by default. With no listener — the standalone CLI — every tool runs.
         */
        default String permit(String tool, JsonNode args) {
            return null;
        }

        /** Before the tool runs. */
        void started(String callId, String tool, JsonNode args);

        /** After it returns; {@code failed} is true when the result is a tool-layer error. */
        void finished(String callId, String tool, String result, boolean failed);
    }

    private Listener listener;
    private long callSeq;

    /** Stream tool activity to {@code l}. Null clears. */
    public ToolRegistry listener(Listener l) {
        this.listener = l;
        return this;
    }

    /** Run a tool by name; tool-layer errors come back as observations, not exceptions. */
    public String execute(String name, JsonNode args) {
        Tool t = tools.get(name);
        if (t == null) return "ERROR: unknown tool '" + name + "'";
        String callId = null;
        Listener l = listener;
        String denial = null;
        if (l != null) {
            callId = "call_" + (++callSeq);
            // An observer must never be able to break the run it is watching — but a DENIAL is a
            // decision, not a failure, so it is deliberately not swallowed the way an observer error is.
            try {
                denial = l.permit(name, args);
            } catch (RuntimeException e) {
                denial = null;                          // a broken gate must not block the run
            }
            try {
                l.started(callId, name, args);
            } catch (RuntimeException ignored) { }
        }
        String out;
        boolean failed = false;
        if (denial != null) {
            out = denial;
            failed = true;
        } else {
            try {
                out = t.execute(args);
            } catch (Exception e) {
                out = "ERROR: " + t.name() + " failed: " + e.getMessage();
                failed = true;
            }
        }
        if (l != null) {
            try {
                l.finished(callId, name, out, failed || out.startsWith("ERROR:"));
            } catch (RuntimeException ignored) { }
        }
        return out;
    }

    /** The standard tool surface with no language server (edit_file's symbol-span fallback disabled). */
    public static ToolRegistry standard(Path projectRoot) {
        return standard(projectRoot, null);
    }

    /**
     * The standard surface, sharing {@code lsp} (nullable) so edit_file gets the automatic symbol-span
     * fallback (PUSH) and replace_symbol is offered. Read / write / edit / shell + done.
     */
    public static ToolRegistry standard(Path projectRoot, LspClient lsp) {
        PathScope scope = new PathScope(projectRoot);
        ToolRegistry reg = new ToolRegistry().scope(scope)
                .add(new ReadFileTool(scope))
                .add(new SearchCodeTool(scope))
                .add(new ReadDepSourceTool(projectRoot))
                .add(new WriteFileTool(scope))
                .add(new EditFileTool(scope, lsp))
                .add(new ShellTool(scope))
                .add(new TaskDoneTool())
                .add(new TaskBlockedTool());
        if (lsp != null) {
            reg.add(new ReplaceSymbolTool(scope, lsp));
            // Only when a server actually started: an advertised tool that always answers
            // "unavailable" teaches the model to stop calling tools.
            reg.add(new FindSymbolTool(scope, lsp));
        }
        return reg;
    }

    /**
     * Read-only surface for review / investigate: no write / edit / replace tools — the familiar reads and
     * reasons and reports, it does not modify files. Shell stays (git diff, grep, cat are how you navigate a
     * review), bounded by the same PathScope.
     */
    public static ToolRegistry readOnly(Path projectRoot, LspClient lsp) {
        PathScope scope = new PathScope(projectRoot);
        ToolRegistry reg = new ToolRegistry().scope(scope)
                .add(new ReadFileTool(scope))
                .add(new SearchCodeTool(scope))
                .add(new ReadDepSourceTool(projectRoot))
                .add(new ShellTool(scope, true))   // read-only: no file-mutating commands
                .add(new TaskDoneTool())
                .add(new TaskBlockedTool());
        if (lsp != null) reg.add(new FindSymbolTool(scope, lsp));
        return reg;
    }

    /**
     * Research surface: web search + fetch, plus read-only local inspection (so research can also ground
     * itself in the project when relevant). Never writes.
     */
    public static ToolRegistry research(Path projectRoot) {
        return research(projectRoot, "");
    }

    /** {@code question} centres page excerpts on what this run is actually trying to find out. */
    public static ToolRegistry research(Path projectRoot, String question) {
        return research(projectRoot, question, new AnswerDraftTool());
    }

    /** Variant exposing the draft tool so the caller can hand it to the loop (the harness merges the
     *  accumulated draft into the final answer — see AnswerDraftTool). */
    public static ToolRegistry research(Path projectRoot, String question, AnswerDraftTool draft) {
        PathScope scope = new PathScope(projectRoot);
        return new ToolRegistry().scope(scope)
                .add(new WebSearchTool())
                .add(new WebFetchTool().focus(question))
                .add(draft)
                .add(new ResearchMemoryTool())   // A-RAG: recall is a TOOL in the loop, not just a prompt seed
                .add(new ReadFileTool(scope))
                .add(new ShellTool(scope, true))
                .add(new TaskDoneTool())
                .add(new TaskBlockedTool());
    }
}
