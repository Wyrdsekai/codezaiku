package org.codezaiku.ops;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R3 must actually restore the service, and must say so honestly when it cannot.
 *
 * <p>Measured on the refstack: the model ran {@code docker rm} on the root service and {@code docker run}
 * its own replacement, which keeps the NAME but is not compose-managed. {@code compose up --force-recreate}
 * then died on "Conflict. The container name is already in use" (exit 1), the fallback restarted the
 * IMPOSTOR, and {@code rollback()} returned true regardless — printing "R3 rollback applied" while redis sat
 * on the default bridge, unresolvable as {@code redis:6379}. The stack stayed broken through every later run.
 *
 * <p>R3 is the guarantee the whole GUARDED rung rests on. A rollback that reports success it did not
 * achieve is worse than one that fails loudly.
 */
class RemediationSnapshotRestoreTest {

    /** An Exec that replays scripted docker output and records what was run. */
    private static final class ScriptedExec implements Exec {
        final List<String> ran = new ArrayList<>();
        private final Function<String, Result> handler;
        ScriptedExec(Function<String, Result> handler) { this.handler = handler; }
        @Override public String describe() { return "scripted"; }
        @Override public Result run(String command, int timeoutSec) {
            ran.add(command);
            Result r = handler.apply(command);
            return r == null ? new Result(0, "") : r;
        }
        @Override public String read(String p) { return null; }
        @Override public boolean isFile(String p) { return false; }
        @Override public boolean isDir(String p) { return false; }
        @Override public void write(String p, String c) { }
        boolean ranMatching(String needle) { return ran.stream().anyMatch(c -> c.contains(needle)); }
    }

    private static RemediationSnapshot.Snap snap() {
        return new RemediationSnapshot.Snap("refstack-redis-1", "redis",
                "/stack/docker-compose.yml", List.of(), true);
    }

    @Test
    void aForeignContainerHoldingTheNameIsRemovedSoComposeCanRecreate() {
        // The impostor holds the name with no compose label; once `docker rm -f` has run, compose
        // recreates the real service and inspect reports it back on the compose network.
        ScriptedExec e = new ScriptedExec(new Function<>() {
            boolean removed = false;
            @Override public Exec.Result apply(String cmd) {
                if (cmd.startsWith("docker rm -f")) { removed = true; return new Exec.Result(0, ""); }
                if (cmd.contains("NetworkSettings.Networks")) {
                    return new Exec.Result(0, removed ? "redis|refstack_refstack-net " : "|bridge ");
                }
                if (cmd.contains("compose.service")) return new Exec.Result(0, removed ? "redis" : "");
                return new Exec.Result(0, "");
            }
        });
        assertTrue(new RemediationSnapshot(e).rollback(snap()));
        assertTrue(e.ranMatching("docker rm -f refstack-redis-1"),
                "the impostor must be cleared, or compose dies on the name conflict");
        assertTrue(e.ranMatching("up -d --force-recreate redis"));
    }

    @Test
    void theRealComposeContainerIsNotRemoved() {
        // When the service is still its compose self, rollback must recreate it in place — never rm it.
        ScriptedExec e = new ScriptedExec(cmd -> {
            if (cmd.contains("NetworkSettings.Networks")) return new Exec.Result(0, "redis|refstack_refstack-net ");
            if (cmd.contains("compose.service")) return new Exec.Result(0, "redis");
            return new Exec.Result(0, "");
        });
        assertTrue(new RemediationSnapshot(e).rollback(snap()));
        assertFalse(e.ranMatching("docker rm -f"), "a healthy compose container must not be destroyed");
    }

    @Test
    void aServiceLeftOnTheDefaultBridgeIsReportedAsNOTRestored() {
        // The exact refstack end state: container is Up, but on `bridge` with no compose identity.
        ScriptedExec e = new ScriptedExec(cmd -> {
            if (cmd.contains("NetworkSettings.Networks")) return new Exec.Result(0, "|bridge ");
            if (cmd.contains("compose.service")) return new Exec.Result(0, "");
            if (cmd.contains("compose -f")) return new Exec.Result(1, "Conflict. The container name is already in use");
            return new Exec.Result(0, "");
        });
        assertFalse(new RemediationSnapshot(e).rollback(snap()),
                "returning true here is what told every caller the target was safe");
    }

    @Test
    void aContainerWithTheRightLabelButNoComposeNetworkIsNotRestored() {
        // Label alone is not enough — what broke the stack looked healthy and was reachable by nobody.
        ScriptedExec e = new ScriptedExec(cmd -> {
            if (cmd.contains("NetworkSettings.Networks")) return new Exec.Result(0, "redis|bridge ");
            if (cmd.contains("compose.service")) return new Exec.Result(0, "redis");
            return new Exec.Result(0, "");
        });
        assertFalse(new RemediationSnapshot(e).rollback(snap()));
    }

    @Test
    void aMultiFileComposeProjectGetsOneDashFPerFile() {
        // compose records project.config_files as a COMMA-SEPARATED list, and
        // docker-compose.yml + docker-compose.override.yml is the DEFAULT convention. Passing the whole
        // string to a single -f names a path that does not exist, so nothing is recreated — and since
        // the impostor is removed first, the service ends up deleted rather than merely broken.
        ScriptedExec e = new ScriptedExec(cmd -> {
            if (cmd.contains("NetworkSettings.Networks")) return new Exec.Result(0, "redis|proj_net ");
            if (cmd.contains("compose.service")) return new Exec.Result(0, "redis");
            return new Exec.Result(0, "");
        });
        var multi = new RemediationSnapshot.Snap("c", "redis", "/s/base.yml,/s/override.yml",
                List.of(), true);
        assertTrue(new RemediationSnapshot(e).rollback(multi));
        assertTrue(e.ranMatching("-f /s/base.yml -f /s/override.yml"),
                "each config file needs its own -f, in order; got: " + e.ran);
    }

    @Test
    void aFailedVOLUMErestoreAbortsRatherThanRecreatingOverBadData() {
        // Order matters: the container must not come back on top of a half-restored volume, and a
        // rollback that could not restore the DATA has not restored the target either.
        ScriptedExec e = new ScriptedExec(cmd ->
                cmd.contains("tar xzf") ? new Exec.Result(1, "tar: error") : new Exec.Result(0, "redis|proj_net "));
        var withVolume = new RemediationSnapshot.Snap("refstack-redis-1", "redis",
                "/stack/docker-compose.yml", List.of("pg_data|/var/lib/postgresql/data"), true);
        assertFalse(new RemediationSnapshot(e).rollback(withVolume));
        assertFalse(e.ranMatching("--force-recreate"),
                "a failed volume restore must not proceed to recreate the service");
    }

    @Test
    void volumesAreRestoredBEFOREtheContainerComesBack() {
        ScriptedExec e = new ScriptedExec(cmd -> new Exec.Result(0, "redis|proj_net "));
        new RemediationSnapshot(e).rollback(new RemediationSnapshot.Snap("c", "redis",
                "/stack/docker-compose.yml", List.of("vol|/data"), true));
        int vol = -1, up = -1;
        for (int i = 0; i < e.ran.size(); i++) {
            if (vol < 0 && e.ran.get(i).contains("tar xzf")) vol = i;
            if (up < 0 && e.ran.get(i).contains("--force-recreate")) up = i;
        }
        assertTrue(vol >= 0 && up > vol, "data first, then the container that reads it");
    }

    @Test
    void aFailedCaptureStillRefusesToRollBack() {
        ScriptedExec e = new ScriptedExec(cmd -> new Exec.Result(0, ""));
        var bad = new RemediationSnapshot.Snap("c", "s", "f", List.of(), false);
        assertFalse(new RemediationSnapshot(e).rollback(bad));
        assertTrue(e.ran.isEmpty(), "no snapshot means no restore attempt at all");
    }

}
