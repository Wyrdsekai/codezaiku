package org.codezaiku;

import org.codezaiku.ops.Exec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fix that verifies now and vanishes at the next restart must be caught.
 *
 * <p>The latent-config check looked only for config FILE edits, on the reasoning that runtime writes
 * do not touch files and so should never cost a restart. That is right while the runtime value is the
 * source of truth, and wrong the moment something outranks it: a redis started as
 * {@code redis-server --requirepass s3cret} accepts {@code CONFIG SET requirepass ''}, passes the
 * closed-loop verify, and demands the password again on the next restart — a latent failure recorded
 * as a success.
 *
 * <p>The check must stay NARROW. Restart-validating after every runtime write would bounce a
 * production service for nothing, and an unnecessary restart is itself a harm — so it fires only when
 * the command line pins that same setting.
 */
class LatentRuntimeWriteTest {

    private static Exec exec(String inspectOutput) {
        return new Exec() {
            @Override public String describe() { return "fake"; }
            @Override public Result run(String cmd, int t) {
                return cmd.contains("docker inspect") ? new Result(0, inspectOutput) : new Result(0, "");
            }
            @Override public String read(String p) { return null; }
            @Override public boolean isFile(String p) { return false; }
            @Override public boolean isDir(String p) { return false; }
            @Override public void write(String p, String c) { }
        };
    }

    private static final String PINNED = "[\"redis-server\",\"--requirepass\",\"s3cret\"] null";
    private static final String BARE   = "[\"redis-server\"] null";

    @Test
    void aRuntimeSetThatTheCommandLinePinsIsLatent() {
        assertTrue(FamiliarMain.runtimeWriteOverriddenByCommandLine(exec(PINNED), "authstack-cache",
                List.of("docker exec authstack-cache redis-cli CONFIG SET requirepass ''")));
    }

    @Test
    void theCertifiedRefstackShapeIsNotAffected() {
        // refstack's redis runs a BARE `redis-server`; the runtime value IS the source of truth there,
        // so this must stay silent or every certified run gains a needless restart.
        assertFalse(FamiliarMain.runtimeWriteOverriddenByCommandLine(exec(BARE), "refstack-redis-1",
                List.of("docker exec refstack-redis-1 redis-cli CONFIG SET requirepass ''")));
    }

    @Test
    void aDifferentSettingOnTheCommandLineDoesNotCount() {
        assertFalse(FamiliarMain.runtimeWriteOverriddenByCommandLine(
                exec("[\"redis-server\",\"--maxmemory\",\"1gb\"] null"), "c",
                List.of("redis-cli CONFIG SET requirepass ''")));
    }

    @Test
    void aFixWithNoRuntimeWriteNeverProbesAtAll() {
        // No CONFIG SET → no docker inspect. A check that cannot fire must not cost a round trip.
        Exec boom = new Exec() {
            @Override public String describe() { return "fake"; }
            @Override public Result run(String cmd, int t) { throw new AssertionError("must not run: " + cmd); }
            @Override public String read(String p) { return null; }
            @Override public boolean isFile(String p) { return false; }
            @Override public boolean isDir(String p) { return false; }
            @Override public void write(String p, String c) { }
        };
        assertFalse(FamiliarMain.runtimeWriteOverriddenByCommandLine(boom, "c",
                List.of("docker restart c", "docker start c")));
    }

    @Test
    void missingInputsAreSafeRatherThanThrowing() {
        assertFalse(FamiliarMain.runtimeWriteOverriddenByCommandLine(exec(PINNED), null, List.of("CONFIG SET x 1")));
        assertFalse(FamiliarMain.runtimeWriteOverriddenByCommandLine(exec(PINNED), "c", null));
        assertFalse(FamiliarMain.runtimeWriteOverriddenByCommandLine(null, "c", List.of("CONFIG SET x 1")));
    }
}
