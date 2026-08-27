package org.codezaiku.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * The structured outcome of a diagnosis run (opensre's {@code InvestigationResult}, PLAN_CODEZAIKU_OPS.md §6).
 * The closed-loop split — {@code validatedClaims} vs {@code nonValidatedClaims} — is the thing no
 * telemetry-only ops tool produces, and what the oracle grades against ground truth. Remediation is kept
 * SEPARATE ({@code remediationSteps}), surfaced not auto-run.
 */
public record InvestigationResult(
        String rootCauseCategory,   // machine-matchable slug: disk_full, service_down, port_conflict, ...
        String rootCause,           // one-paragraph human RCA
        List<String> evidence,      // tool outputs that ground the RCA (command → what it showed)
        List<String> validatedClaims,
        List<String> nonValidatedClaims,
        List<String> remediationSteps,
        String answer               // the final answer in a caller-specified format (may be empty)
) {
    /** Back-compat: an investigation with no caller-specified answer format. */
    public InvestigationResult(String rootCauseCategory, String rootCause, List<String> evidence,
                               List<String> validatedClaims, List<String> nonValidatedClaims,
                               List<String> remediationSteps) {
        this(rootCauseCategory, rootCause, evidence, validatedClaims, nonValidatedClaims,
             remediationSteps, "");
    }

    public ObjectNode toJson(ObjectMapper j) {
        ObjectNode o = j.createObjectNode();
        o.put("root_cause_category", rootCauseCategory == null ? "" : rootCauseCategory);
        o.put("root_cause", rootCause == null ? "" : rootCause);
        putArr(j, o, "evidence", evidence);
        putArr(j, o, "validated_claims", validatedClaims);
        putArr(j, o, "non_validated_claims", nonValidatedClaims);
        putArr(j, o, "remediation_steps", remediationSteps);
        o.put("answer", answer == null ? "" : answer);
        return o;
    }

    private static void putArr(ObjectMapper j, ObjectNode o, String key, List<String> vals) {
        ArrayNode a = o.putArray(key);
        if (vals != null) for (String v : vals) if (v != null && !v.isBlank()) a.add(v);
    }
}
