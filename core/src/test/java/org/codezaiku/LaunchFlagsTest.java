package org.codezaiku;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A flag a container was LAUNCHED with, versus a flag merely mentioned somewhere in its command.
 *
 * <p>Measured: authstack's cache ran
 * {@code ["sh","-c","if [ -n \"$PW\" ]; then exec redis-server --requirepass \"$PW\"; else exec
 * redis-server; fi"]}, so {@code --requirepass} sat in the branch that was NOT taken. Substring
 * matching on the raw argv JSON could not tell that from a genuinely pinned password, and the
 * launch-args card fired on a RUNTIME fault — the case the validated card must keep.
 *
 * <p>The rule is structural: a pinned setting is its OWN argv element; a wrapper is one element
 * holding an entire shell script.
 */
class LaunchFlagsTest {

    @Test
    void aPinnedFlagIsItsOwnArgvElement() {
        assertEquals("--requirepass",
                FamiliarMain.launchFlags("[\"redis-server\",\"--requirepass\",\"s3cret\"]"));
    }

    @Test
    void aFlagBuriedInAWrapperScriptDoesNotCount() {
        String wrapper = "[\"sh\",\"-c\",\"if [ -n \\\"$PW\\\" ]; then exec redis-server "
                + "--requirepass \\\"$PW\\\"; else exec redis-server; fi\"]";
        assertFalse(FamiliarMain.launchFlags(wrapper).contains("--requirepass"),
                "a flag inside an unexecuted branch is not a flag the container was launched with");
    }

    @Test
    void theShellsOwnDashCIsNotReportedAsAConfigFlag() {
        // `-c` is how the wrapper is invoked, not something the service was configured with. It is a
        // flag in its own right, so it survives the argv test — but it carries no setting name, and
        // no card signature names it.
        String wrapper = "[\"sh\",\"-c\",\"exec redis-server\"]";
        assertEquals("-c", FamiliarMain.launchFlags(wrapper));
    }

    @Test
    void theCertifiedRefstackShapeYieldsNoFlags() {
        assertEquals("", FamiliarMain.launchFlags("[\"redis-server\"]"));
        assertEquals("", FamiliarMain.launchFlags("[\"docker-entrypoint.sh\"]"));
    }

    @Test
    void severalPinnedFlagsAreAllReported() {
        assertEquals("--requirepass --maxmemory",
                FamiliarMain.launchFlags("[\"redis-server\",\"--requirepass\",\"x\",\"--maxmemory\",\"1gb\"]"));
    }

    @Test
    void unparseableOrAbsentArgvSaysNothingRatherThanGuessing() {
        assertEquals("", FamiliarMain.launchFlags(null));
        assertEquals("", FamiliarMain.launchFlags(""));
        assertEquals("", FamiliarMain.launchFlags("null"));
        assertEquals("", FamiliarMain.launchFlags("not json at all"));
    }

    @Test
    void theDiscriminationThatMattersHolds() {
        String pinned  = FamiliarMain.launchFlags("[\"redis-server\",\"--requirepass\",\"s3cret\"]");
        String wrapper = FamiliarMain.launchFlags("[\"sh\",\"-c\",\"if x; then exec redis-server "
                + "--requirepass y; else exec redis-server; fi\"]");
        assertTrue(pinned.contains("--requirepass"));
        assertFalse(wrapper.contains("--requirepass"));
    }
}
