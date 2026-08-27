package org.codezaiku.gate;

/** Verdict of a gate run. {@code evidence} is human/model-readable, cause-first on failure. */
public record GateResult(boolean pass, int claimsPassed, int claimsTotal, String evidence) {
}
