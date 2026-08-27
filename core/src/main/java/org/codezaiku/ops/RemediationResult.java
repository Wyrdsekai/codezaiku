package org.codezaiku.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * The outcome of the gated remediation phase (PLAN_CODEZAIKU_OPS.md §6). The differentiator vs
 * telemetry-only ops tools is the CLOSED LOOP: {@code harnessVerified} is the harness re-running an
 * objective check AFTER the fix — the model's own {@code modelClaimsResolved} is never taken at its word.
 *
 * <p>The same principle applies to the record of what was DONE. {@code appliedSteps} is the model's
 * account of its own work, and it arrives only if the model calls {@code remediation_done} at all;
 * {@code observedMutations} is what the harness watched itself execute. When they disagree, the
 * observed list is the evidence — machine-computed evidence outranks model judgment, here as elsewhere.
 */
public record RemediationResult(
        List<String> appliedSteps,
        boolean modelClaimsResolved,
        String verificationEvidence,
        Boolean harnessVerified,   // null = no objective check was supplied; else the closed-loop verdict
        List<String> observedMutations
) {
    public RemediationResult {
        appliedSteps = appliedSteps == null ? List.of() : List.copyOf(appliedSteps);
        observedMutations = observedMutations == null ? List.of() : List.copyOf(observedMutations);
    }

    /**
     * Everything known to have changed the box: what the model reported plus what we saw it run,
     * de-duplicated. This — not {@code appliedSteps} — is what a safety check about "did the fix touch
     * X?" must consult, because a mutation the model failed to report is exactly the one most likely to
     * be missed.
     */
    public List<String> allMutations() {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (String s : appliedSteps) if (s != null && !s.isBlank()) all.add(s.trim());
        for (String s : observedMutations) if (s != null && !s.isBlank()) all.add(s.trim());
        return List.copyOf(all);
    }

    public ObjectNode toJson(ObjectMapper j) {
        ObjectNode o = j.createObjectNode();
        ArrayNode a = o.putArray("applied_steps");
        for (String s : appliedSteps) if (s != null && !s.isBlank()) a.add(s);
        ArrayNode m = o.putArray("observed_mutations");
        for (String s : observedMutations) if (s != null && !s.isBlank()) m.add(s);
        o.put("model_claims_resolved", modelClaimsResolved);
        o.put("verification_evidence", verificationEvidence == null ? "" : verificationEvidence);
        if (harnessVerified == null) o.putNull("harness_verified");
        else o.put("harness_verified", harnessVerified);
        return o;
    }
}
