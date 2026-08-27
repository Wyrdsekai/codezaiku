package org.codezaiku;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * An environment variable carries two different intentions — "the operator chose this" and "I am a
 * host filling in a blank" — and CodeZaiku cannot tell them apart from the value alone. A host that
 * sends its own built-in default down the plain variable silently overrides a machine that WAS
 * configured, and the mismatch is invisible: the config file still reads correctly, so `doctor`
 * reports healthy while a spawned run goes somewhere else.
 *
 * `<KEY>_DEFAULT` is the channel for offering rather than imposing. These pin the ordering.
 */
class ConfigPrecedenceTest {

    private static String resolve(String env, String file, String hostDefault) throws Exception {
        Method m = Config.class.getDeclaredMethod("resolve", String.class, String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, env, file, hostDefault);
    }

    @Test
    void anExplicitOverrideBeatsEverything() throws Exception {
        assertEquals("forced", resolve("forced", "from-file", "host-default"));
    }

    @Test
    void aConfiguredMachineIsNotOverriddenByAHostsDefault() throws Exception {
        assertEquals("from-file", resolve(null, "from-file", "host-default"),
                "this is the whole point: install CodeZaiku, configure it, and a host summoning it "
                        + "must not silently redirect the run");
    }

    @Test
    void aHostDefaultFillsInForAnUnconfiguredMachine() throws Exception {
        assertEquals("host-default", resolve(null, null, "host-default"));
        assertEquals("host-default", resolve("", "  ", "host-default"));
    }

    @Test
    void nothingConfiguredAnywhereResolvesToNothing() throws Exception {
        assertNull(resolve(null, null, null));
        assertNull(resolve("", "", ""));
    }
}
