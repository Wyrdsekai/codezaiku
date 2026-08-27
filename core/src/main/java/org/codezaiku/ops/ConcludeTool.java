package org.codezaiku.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.tools.Tool;

import java.util.ArrayList;
import java.util.List;

/**
 * The one way to end an investigation: emit the structured {@link InvestigationResult}. The loop's
 * verification-first gate (opensre, PLAN_CODEZAIKU_OPS.md §5) inspects the captured result and may reject +
 * nudge the FIRST attempt when required RCA markers are missing (root cause + a category + grounded
 * evidence), so this tool just captures — the loop decides acceptance.
 */
public final class ConcludeTool implements Tool {
    private InvestigationResult captured;   // last conclusion the model emitted
    private boolean called;

    @Override public String name() { return "conclude"; }

    @Override public String description() {
        return "Conclude the investigation with your root-cause diagnosis. Call ONLY after you have grounded "
             + "the root cause in actual command output. Provide the machine category, a one-paragraph root "
             + "cause, the evidence you verified, and (separately) the remediation you would apply.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("root_cause_category").put("type", "string")
                .put("description", "A short machine slug for the fault, e.g. disk_full, service_down, "
                        + "port_conflict, cert_expired, oom, bad_config, dependency_down.");
        props.putObject("root_cause").put("type", "string")
                .put("description", "One paragraph: the TRUE underlying cause (not a symptom).");
        arr(j, props, "evidence", "Specific command outputs that prove the root cause (command → what it showed).");
        arr(j, props, "validated_claims", "Claims you verified against real command output.");
        arr(j, props, "remediation_steps", "What you would DO to fix it (surfaced, not executed here).");
        // Free-form final answer in a caller-specified format. Real callers (an alerting system, a
        // benchmark, a ticket template) often demand an EXACT output shape, and hitting that shape is a
        // separate skill from finding the cause — it is precisely the last-mile-fidelity gap. When the
        // incident specifies an output format, the answer goes here, verbatim in that format.
        props.putObject("answer").put("type", "string")
                .put("description", "If the incident asked for the answer in a SPECIFIC format, put the "
                        + "final answer here, exactly in that format (e.g. the requested JSON).");
        p.putArray("required").add("root_cause_category").add("root_cause");
        return p;
    }

    private static void arr(ObjectMapper j, ObjectNode props, String name, String desc) {
        ObjectNode a = props.putObject(name);
        a.put("type", "array");
        a.putObject("items").put("type", "string");
        a.put("description", desc);
    }

    @Override public String execute(JsonNode args) {
        this.captured = new InvestigationResult(
                args.path("root_cause_category").asText("").trim(),
                args.path("root_cause").asText("").trim(),
                strList(args.path("evidence")),
                strList(args.path("validated_claims")),
                new ArrayList<>(),
                strList(args.path("remediation_steps")),
                args.path("answer").asText("").trim());
        this.called = true;
        return "conclusion recorded";   // the loop replaces/accepts this; visible text is minimal
    }

    boolean called() { return called; }
    InvestigationResult captured() { return captured; }
    void reset() { called = false; }

    private static List<String> strList(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            for (JsonNode e : n) { String s = e.asText("").trim(); if (!s.isBlank()) out.add(s); }
        } else if (n != null && n.isTextual() && !n.asText().isBlank()) {
            out.add(n.asText().trim());
        }
        return out;
    }
}
