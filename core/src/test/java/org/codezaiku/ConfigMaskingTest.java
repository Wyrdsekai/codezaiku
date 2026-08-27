package org.codezaiku;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * `codezaiku config list` output gets pasted into issue reports. A credential printed there is a
 * credential leaked, so secret-shaped settings report only that they are set — which is the part
 * anyone actually needs from a listing.
 */
class ConfigMaskingTest {

    private static String shown(String key, String value) throws Exception {
        Method m = FamiliarMain.class.getDeclaredMethod("displayValue", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, key, value);
    }

    @Test
    void credentialsAreNeverPrinted() throws Exception {
        assertEquals("(set)", shown("CODEZAIKU_API_KEY", "sk-live-abcdef123456"));
        assertEquals("(set)", shown("SOME_TOKEN", "ghp_abcdef"));
        assertEquals("(set)", shown("A_SECRET", "hunter2"));
        assertEquals("(set)", shown("DB_PASSWORD", "hunter2"));
    }

    @Test
    void ordinarySettingsStillShowTheirValue() throws Exception {
        assertEquals("http://localhost:8200", shown("CODEZAIKU_DRIVE", "http://localhost:8200"));
        assertEquals("propose", shown("CODEZAIKU_OPS_AUTHORITY", "propose"));
    }
}
