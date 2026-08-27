package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.gate.Gate;

import java.nio.file.Path;

/**
 * Lets the familiar run the authoritative behavior door itself, as real-execution feedback (it
 * boots the app on a free port and probes live HTTP). The harness re-runs the same gate at
 * task_done to certify — this tool just lets the model check before declaring done, which also
 * spares it from fighting a busy fixed port for its own probing.
 */
public final class VerifyTool implements Tool {
    private final Gate gate;
    private final Path projectRoot;

    public VerifyTool(Gate gate, Path projectRoot) {
        this.gate = gate;
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "verify_behavior";
    }

    @Override
    public String description() {
        return "Run the project's real verification gate — it compiles/runs/probes the actual "
                + "program (builds + runs a CLI, or boots a service and probes it) and returns the "
                + "verdict (which checks pass/fail). Run this to check your work before task_done.";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        p.putObject("properties");
        return p;
    }

    @Override
    public String execute(JsonNode args) {
        return gate.certify(projectRoot).evidence();
    }
}
