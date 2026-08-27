package org.codezaiku.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.tools.Tool;

import java.util.ArrayList;
import java.util.List;

/** Ends the remediation phase: what was changed + the command output proving the incident is resolved. */
public final class RemediationDoneTool implements Tool {
    private boolean called;
    private List<String> appliedSteps = new ArrayList<>();
    private String verification = "";

    @Override public String name() { return "remediation_done"; }

    @Override public String description() {
        return "Call once you have APPLIED the fix and CONFIRMED the incident is resolved. Provide the exact "
             + "changes you made and the command output that proves the box is healthy again.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        ObjectNode steps = props.putObject("applied_steps");
        steps.put("type", "array");
        steps.putObject("items").put("type", "string");
        steps.put("description", "The exact commands/changes you applied to fix the diagnosed cause.");
        props.putObject("verification").put("type", "string")
                .put("description", "The command you ran to confirm resolution and its output (e.g. "
                        + "'curl -I localhost → 200', 'systemctl is-active nginx → active').");
        p.putArray("required").add("verification");
        return p;
    }

    @Override public String execute(JsonNode args) {
        this.appliedSteps = new ArrayList<>();
        JsonNode s = args.path("applied_steps");
        if (s.isArray()) for (JsonNode e : s) { String v = e.asText("").trim(); if (!v.isBlank()) appliedSteps.add(v); }
        this.verification = args.path("verification").asText("").trim();
        this.called = true;
        return "remediation recorded";
    }

    boolean called() { return called; }
    List<String> appliedSteps() { return appliedSteps; }
    String verification() { return verification; }
}
