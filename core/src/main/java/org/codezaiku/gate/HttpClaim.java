package org.codezaiku.gate;

import java.util.ArrayList;
import java.util.List;

/**
 * One concrete HTTP behavior claim against the live service. The {@code path} may contain a
 * {@code {var}} placeholder bound from an earlier claim's {@link #captureField}.
 *
 * @param id           short label (e.g. "C3") for the evidence line
 * @param method       GET / POST / DELETE / ...
 * @param path         request path; may interpolate {@code {field}} captured earlier
 * @param bodyJson     request body (nullable)
 * @param expectStatus required HTTP status
 * @param bodyCheck    assertion on the response body
 * @param captureField on success, capture this JSON field's value into a var named {@code captureField}
 */
public record HttpClaim(
        String id,
        String method,
        String path,
        String bodyJson,
        int expectStatus,
        BodyCheck bodyCheck,
        String captureField,
        String containsDerived,     // nullable — STUB-PROOF: a cmd run at gate-time (in the project root)
                                    // whose output the response body must contain. Real-input ground-truth
                                    // (e.g. a real sender from the actual sample.mbox) the model can't hardcode.
        List<String> containsDerivedAll) { // nullable/empty — MULTI ground-truth: EVERY listed
                                    // derive-cmd's output must appear in the body. "Real means all N fields"
                                    // for a multi-concern endpoint, archetype- and language-agnostic.

    /** All derive commands for this claim — the single {@code containsDerived} plus {@code containsDerivedAll}. */
    public List<String> deriveCommands() {
        List<String> all = new ArrayList<>();
        if (containsDerived != null && !containsDerived.isBlank()) all.add(containsDerived);
        if (containsDerivedAll != null) {
            for (String c : containsDerivedAll) if (c != null && !c.isBlank()) all.add(c);
        }
        return all;
    }

    public static HttpClaim get(String id, String path, int status, BodyCheck check) {
        return new HttpClaim(id, "GET", path, null, status, check, null, null, null);
    }

    public static HttpClaim post(String id, String path, String body, int status, BodyCheck check, String capture) {
        return new HttpClaim(id, "POST", path, body, status, check, capture, null, null);
    }

    public static HttpClaim delete(String id, String path, int status) {
        return new HttpClaim(id, "DELETE", path, null, status, BodyCheck.ANY, null, null, null);
    }
}
