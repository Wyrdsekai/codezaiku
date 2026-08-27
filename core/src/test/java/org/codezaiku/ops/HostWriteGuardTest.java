package org.codezaiku.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A scoped fix may not reach the HOST filesystem — including by DELETING part of it.
 *
 * <p>The guard already refused redirects, {@code sed -i}, {@code tee} and {@code rm -f /path}. It let a
 * bare {@code rm /path} through, because the pattern required an intervening token between the verb and
 * the path. Measured, during a real guarded remediation: the model ran
 * {@code rm <path>/docker-compose.fault.yml} — deleting the compose override that DEFINED the
 * fault it had been asked to fix, and thereby "resolving" the incident by removing its definition. Two
 * runs then recorded a verified success on that basis.
 *
 * <p>Corrupting a config file was the failure this guard was built for; deleting one is strictly worse.
 */
class HostWriteGuardTest {

    private static final ObjectMapper J = new ObjectMapper();

    /** The tool scoped to one service, which is when the host-write guard is active. */
    private static OpsShellTool scoped() {
        Exec noop = new Exec() {
            @Override public String describe() { return "fake"; }
            @Override public Result run(String c, int t) { return new Result(0, "ran: " + c); }
            @Override public String read(String p) { return null; }
            @Override public boolean isFile(String p) { return false; }
            @Override public boolean isDir(String p) { return false; }
            @Override public void write(String p, String c) { }
        };
        // readOnly=FALSE: the host-write guard governs the REMEDIATION phase, where mutation is
        // allowed. Built read-only, every `rm` is blocked by the diagnosis guard instead and the test
        // proves nothing about the guard under test.
        return new OpsShellTool(noop, 6000, 30, false).scopeTo("cache", Set.of("api"));
    }

    private static String run(String cmd) {
        return scoped().execute(J.createObjectNode().put("command", cmd));
    }

    @Test
    void aBareDeleteOfAHostFileIsBlocked() {
        String out = run("rm /srv/authstack/docker-compose.fault.yml");
        assertTrue(out.startsWith("blocked:"),
                "the SHAPE a real run used — bare `rm <host path>`, single space; got: " + out);
    }

    @Test
    void theFormsThatAlreadyWorkedStillWork() {
        assertTrue(run("rm -f /etc/redis/redis.conf").startsWith("blocked:"));
        assertTrue(run("sed -i s/a/b/ /etc/redis/redis.conf").startsWith("blocked:"));
        assertTrue(run("echo x > /etc/redis/redis.conf").startsWith("blocked:"));
        assertTrue(run("mv /etc/redis/redis.conf /etc/redis/old.conf").startsWith("blocked:"));
    }

    /**
     * Shapes a probe found AFTER the first fix — a denylist of verbs is never finished, and each of
     * these reaches the same files without looking like the ones already covered.
     */
    @Test
    void theSiblingShapesAProbeFoundAreAlsoBlocked() {
        assertTrue(run("find /etc/redis -name '*.conf' -delete").startsWith("blocked:"),
                "reaches the files without a destructive leading verb");
        assertTrue(run("cat /tmp/list | xargs rm").startsWith("blocked:"),
                "paths arrive on stdin, so no path match can see them");
        assertTrue(run("python3 -c \"import os; os.remove('/etc/redis/redis.conf')\"").startsWith("blocked:"),
                "the deletion happens in-process");
    }

    @Test
    void anExemptPathFirstDoesNotHideTheRealTarget() {
        // `install -m 0644 /dev/null /etc/redis/redis.conf` truncates a host config THROUGH /dev/null.
        // Anchoring on the FIRST path lands on the exempt one and misses the target.
        assertTrue(run("install -m 0644 /dev/null /etc/redis/redis.conf").startsWith("blocked:"));
        assertTrue(run("cp /tmp/staged.conf /etc/redis/redis.conf").startsWith("blocked:"));
    }

    @Test
    void aScratchPathAloneIsStillNotBlocked() {
        // The other direction: the prefix must end in whitespace, or it swallows the leading `/tmp`
        // and matches an inner slash, blocking ordinary scratch cleanup.
        assertFalse(run("rm /tmp/probe.out").startsWith("blocked:"));
        assertFalse(run("rm -f /tmp/a/b/c.json").startsWith("blocked:"));
    }

    @Test
    void deletingInsideTheTargetContainerIsStillAllowed() {
        // The escape hatch the guard's own message points at: act INSIDE the service. `docker ...`
        // returns early, and the blast-radius guard governs which container may be touched.
        assertFalse(run("docker exec cache rm /data/dump.rdb").startsWith("blocked:"));
    }

    @Test
    void scratchPathsAreNotTheHostConfigWeAreProtecting() {
        assertFalse(run("rm /tmp/probe.out").startsWith("blocked:"));
        assertFalse(run("rm /dev/shm/x").startsWith("blocked:"));
    }

    @Test
    void readingTheHostIsNeverBlocked() {
        assertFalse(run("cat /etc/redis/redis.conf").startsWith("blocked:"));
        assertFalse(run("ls /etc/redis").startsWith("blocked:"));
    }
}
