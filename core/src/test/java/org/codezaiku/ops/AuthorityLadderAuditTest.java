package org.codezaiku.ops;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.nio.file.Path;

import static org.codezaiku.ops.OpsAuthority.Rung.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The authority ladder, audited by claim like the shell guards were.
 *
 * <p>Recorded because of the CONTRAST it draws: the two pattern denylists audited alongside it had
 * eleven holes between them, and this had none. A guard expressed as LOGIC over a small set of inputs
 * can be checked exhaustively; a guard expressed as a list of forbidden verbs cannot, and the list is
 * never finished. Worth remembering next time a safety property needs a mechanism.
 */
class AuthorityLadderAuditTest {

    private static OpsAuthority auth(OpsAuthority.Rung ceiling, boolean halted, boolean trial) {
        try {
            Constructor<OpsAuthority> c = OpsAuthority.class.getDeclaredConstructor(
                    OpsAuthority.Rung.class, boolean.class, boolean.class,
                    Path.class, String.class);
            c.setAccessible(true);
            return c.newInstance(ceiling, halted, trial, null, null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void whatEachEvidenceLevelEARNS() {
        assertEquals(UNATTENDED, auth(UNATTENDED, false, false).effective(true, true, true));
        assertEquals(PROPOSE,    auth(UNATTENDED, false, false).effective(true, false, true));
        assertEquals(LOCALIZE,   auth(UNATTENDED, false, false).effective(false, false, true));
    }

    @Test
    void anUnsureLocalizationNeverAutoActs() {
        assertEquals(PROPOSE, auth(UNATTENDED, false, false).effective(true, true, false));
        assertEquals(PROPOSE, auth(UNATTENDED, false, true).effective(true, false, false));
    }

    @Test
    void theCeilingAlwaysCaps() {
        assertEquals(GUARDED,  auth(GUARDED,  false, false).effective(true, true, true));
        assertEquals(PROPOSE,  auth(PROPOSE,  false, false).effective(true, true, true));
        assertEquals(OBSERVE,  auth(OBSERVE,  false, false).effective(true, true, true));
        assertEquals(PROPOSE,  auth(PROPOSE,  false, true).effective(true, false, true));
    }

    @Test
    void theKillSwitchWinsOverEverything() {
        assertEquals(OBSERVE, auth(UNATTENDED, true, false).effective(true, true, true));
        assertEquals(OBSERVE, auth(UNATTENDED, true, true).effective(true, false, true));
    }

    @Test
    void theTrialLaneLetsACandidateActAndChangesNothingElse() {
        assertEquals(UNATTENDED, auth(UNATTENDED, false, true).effective(true, false, true));
        assertEquals(LOCALIZE,   auth(UNATTENDED, false, true).effective(false, false, true),
                "no card is still no card, trial or not");
    }

    @Test
    void onlyGuardedAndAboveApplyAFix() {
        assertFalse(OpsAuthority.autoRemediates(OBSERVE));
        assertFalse(OpsAuthority.autoRemediates(LOCALIZE));
        assertFalse(OpsAuthority.autoRemediates(PROPOSE));
        assertTrue(OpsAuthority.autoRemediates(GUARDED));
        assertTrue(OpsAuthority.autoRemediates(UNATTENDED));
    }
}
