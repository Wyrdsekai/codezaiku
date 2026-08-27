package org.codezaiku.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An ADVERSARIAL audit of the two scoped guards, kept as a test so the holes it found stay closed.
 *
 * <p>Written after a bare {@code rm /path} slipped the host-write guard during a real run. Probing the
 * obvious siblings then found four more there — and, far worse, SIX in the blast-radius guard, which
 * returned early unless the command contained the literal word {@code docker}. "Blast radius = 1" is a
 * load-bearing promise, and it was holding only against docker verbs: {@code podman restart},
 * {@code systemctl restart}, {@code pkill -f}, {@code kill -9 $(pgrep -f …)} and a write-shaped
 * {@code curl} all reached a bystander untouched.
 *
 * <p>The lesson these encode is that a guard's SHAPE, not its verb list, is what to audit: the engine a
 * fix happens to use is not what puts it out of scope — touching another service is.
 */
class ScopedGuardAuditTest {

    private static final ObjectMapper J = new ObjectMapper();

    private static String run(String cmd) {
        Exec noop = new Exec() {
            @Override public String describe() { return "fake"; }
            @Override public Result run(String c, int t) { return new Result(0, ""); }
            @Override public String read(String p) { return null; }
            @Override public boolean isFile(String p) { return false; }
            @Override public boolean isDir(String p) { return false; }
            @Override public void write(String p, String c) { }
        };
        return new OpsShellTool(noop, 6000, 30, false)
                .scopeTo("cache", Set.of("api"))
                .execute(J.createObjectNode().put("command", cmd));
    }

    private static void blocked(String cmd) {
        assertTrue(run(cmd).startsWith("blocked:"), "must be blocked: " + cmd);
    }

    private static void allowed(String cmd) {
        assertFalse(run(cmd).startsWith("blocked:"), "must be allowed: " + cmd);
    }

    @Test
    void aBystanderIsOutOfReachWHATEVERengineTheFixUses() {
        blocked("docker restart api");
        blocked("podman restart api");           // same CLI surface, different name
        blocked("systemctl restart api");        // host-tier service, no container involved
        blocked("pkill -f api");
        blocked("kill -9 $(pgrep -f api)");
        blocked("curl -XPOST http://api:8000/shutdown");   // a write-shaped call to its endpoint
    }

    @Test
    void theScopedServiceItselfStaysReachable() {
        allowed("docker restart cache");
        allowed("docker exec cache redis-cli CONFIG GET requirepass");
    }

    @Test
    void merelyNAMINGaBystanderInAReadIsNotOutOfScope() {
        // Or the guard would block ordinary inspection and push the model toward blunter commands.
        allowed("ps aux | grep api");
        allowed("cat /etc/hosts");
    }

    @Test
    void theHostFilesystemIsClosedToEveryShapeTheAuditFound() {
        blocked("rm /etc/redis/redis.conf");
        blocked("rm -rf /etc/redis");
        blocked("find /etc/redis -name '*.conf' -delete");
        blocked("cat /tmp/list | xargs rm");
        blocked("install -m 0644 /dev/null /etc/redis/redis.conf");
        blocked("python3 -c \"import os; os.remove('/etc/redis/redis.conf')\"");
        blocked("sh -c 'echo x > /etc/redis/redis.conf'");
        blocked("dd if=/dev/zero of=/etc/redis/redis.conf");
    }

    @Test
    void readingTheHostAndWritingToScratchStayAllowed() {
        allowed("cat /etc/redis/redis.conf");
        allowed("cp /etc/redis/redis.conf /tmp/backup");   // a BACKUP — blocking it was a false positive
        allowed("rm /tmp/probe.out");
    }

    @Test
    void actingOnAProcessByPIDisRefusedOutright() {
        // A number does not say which service it belongs to, so name-scoping cannot check it. Rather
        // than resolve every PID on the hot path, the few verbs whose purpose is to act on a process by
        // NUMBER are refused: a scoped fix has no business doing that, because it acts on its own
        // service through docker/systemctl/its API, all of which name what they touch.
        blocked("nsenter -t 1234 -n -- kill 1");
        blocked("kill -9 4711");
        blocked("renice 10 4711");
    }

    @Test
    void signallingINSIDEtheScopedContainerIsStillAllowed() {
        // `docker exec` names the service, so it is checkable and stays available — otherwise a
        // legitimate `kill -HUP 1` to reload the target's own config would be impossible.
        allowed("docker exec cache kill -HUP 1");
    }

    @Test
    void aNumberInAnORDINARYreadIsNotAPIDaction() {
        allowed("docker logs cache --tail 25");
        allowed("ps aux | grep 4711");
    }
}
