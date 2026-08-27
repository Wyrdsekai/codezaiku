package org.codezaiku.gate;

import java.util.ArrayList;
import java.util.List;

/**
 * One step of the CLI/process gate: run {@code cmd} in the project root and check its exit code
 * (against {@code expectExit}) and optionally that the combined output {@code contains} /
 * {@code notContains} a marker. The cmd may embed its own {@code timeout ...} (e.g. to run a TUI
 * briefly); {@code timeoutSec} is the engine's hard backstop.
 */
public record CliStep(
        String id,
        String cmd,
        int timeoutSec,
        List<Integer> expectExit,
        String contains,    // nullable
        String notContains, // nullable
        String containsDerived,     // nullable — STUB-PROOF: a cmd run at gate-time whose output the app's
                                    // output must contain (env / real-input ground-truth the model couldn't
                                    // hardcode: `hostname`, `nproc`, grep a real value from the input corpus).
        List<String> containsDerivedAll) { // nullable/empty — MULTI ground-truth: EVERY listed derive-cmd's
                                           // output must appear in the captured output. The general "real
                                           // means all N panels" check: one exact, format-stable, gate-derived
                                           // value per concern that ANY correct impl (any language) must show.

    /** All derive commands for this step — the single {@code containsDerived} plus {@code containsDerivedAll}. */
    public List<String> deriveCommands() {
        List<String> all = new ArrayList<>();
        if (containsDerived != null && !containsDerived.isBlank()) all.add(containsDerived);
        if (containsDerivedAll != null) {
            for (String c : containsDerivedAll) if (c != null && !c.isBlank()) all.add(c);
        }
        return all;
    }
}
