package org.codezaiku.loop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The chat false-write bounce fires on summaries that tell the person a file was created.
 * The detector is deliberately conservative: a write-verb near a named file with an extension.
 * (The bounce itself additionally requires that NO mutating tool ran — that half is ground
 * truth from the run, not parsing.)
 */
class FalseWriteClaimTest {

    @Test
    void claimsAreDetected() {
        assertTrue(FamiliarLoop.observationClaimsWrite("Wrote PLAN.md with the full plan."));
        assertTrue(FamiliarLoop.observationClaimsWrite("Created PLAN.md with a 6-step build plan"));
        assertTrue(FamiliarLoop.observationClaimsWrite("I saved notes.txt and it holds 3 items"));
        assertTrue(FamiliarLoop.observationClaimsWrite("The plan was written to docs/PLAN.md as requested"));
    }

    @Test
    void plainRepliesAreNot() {
        assertFalse(FamiliarLoop.observationClaimsWrite("Here is the plan: 1. create app.py 2. add tests"));
        assertFalse(FamiliarLoop.observationClaimsWrite("The retry goes in Client.java around send()"));
        assertFalse(FamiliarLoop.observationClaimsWrite("You should run pytest first"));
        // Naming a file the person should create is advice, not a claim of having done it.
        assertFalse(FamiliarLoop.observationClaimsWrite("Next you could add a README.md"));
    }
}
