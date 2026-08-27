package org.codezaiku.gate;

import java.util.List;

/**
 * Declares how to certify an HTTP service: how to boot it (with a {@code {port}} placeholder so
 * the gate can pick a free port — never a hardcoded one), how to tell it's ready, and the ordered
 * behavior claims. Authoring these from acceptance criteria is the RequestNormalizer's job later;
 * for now they're hand-built per fixture in {@link Gates}.
 */
public record GateSpec(
        String name,
        String archetype,      // selects the engine: "http-service" (more later: "cli", "headless")
        String bootCommand,    // contains "{port}"
        String readinessPath,  // a GET path that returns a JSON array when the app is up
        int bootTimeoutSec,
        List<HttpClaim> claims) {
}
