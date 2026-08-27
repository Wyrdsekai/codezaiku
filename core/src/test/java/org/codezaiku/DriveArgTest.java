package org.codezaiku;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The drive is a trailing positional, so the documented way to reach a ceiling past it is
 * `codezaiku triage local "" 20 guarded`. That only works if a blank slot means "default" —
 * otherwise the README ships a command that builds an empty base URL.
 */
class DriveArgTest {

    private static String drive(String... args) throws Exception {
        Method m = FamiliarMain.class.getDeclaredMethod("driveArg", String[].class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(null, args, 2);
    }

    @Test
    void blankOrMissingSlotFallsBackToTheDefault() throws Exception {
        String dflt = drive("triage", "local");                 // absent
        assertEquals(dflt, drive("triage", "local", ""));       // skipped with ""
        assertEquals(dflt, drive("triage", "local", "   "));    // skipped with whitespace
    }

    @Test
    void anExplicitDriveStillWins() throws Exception {
        assertEquals("http://box:9999", drive("triage", "local", "http://box:9999"));
    }
}
