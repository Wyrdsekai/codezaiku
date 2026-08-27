package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Signal that the CURRENT plan step is complete. The loop intercepts this (like task_done): it runs a
 * whole-project COMPILE check and only advances to the next step if the build is GREEN — otherwise it
 * returns the build errors and keeps the model on the current step. This is the monotonic "get it
 * building before adding the next feature" discipline (smallcode's biggest small-model reliability
 * lever) — a ground-truth build check, NOT a quality/done-ness oracle.
 */
public final class StepDoneTool implements Tool {
    public static final String NAME = "step_done";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Call this when the CURRENT plan step's code is written AND compiles. The harness verifies "
                + "the whole project still builds: if green it advances you to the next step; if not it "
                + "returns the build errors for you to fix before advancing. Do the steps in order.";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        p.putObject("properties").putObject("note").put("type", "string");
        return p; // note optional
    }

    @Override
    public String execute(JsonNode args) {
        return ""; // intercepted by the loop
    }
}
