package org.codezaiku.ops;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The ALLOWLIST half of the read-only guard: is this a kind of command diagnosis runs at all?
 *
 * <p>The patterns elsewhere in {@link OpsSafety} answer a different question — is this particular USE a
 * mutation — and auditing them by claim showed what they structurally cannot do: a forbidden-verb list
 * says "not the shapes I thought of", never "this phase is incapable of mutation". The open tail is
 * unbounded: a busybox applet, a compiled helper, a tool nobody listed.
 *
 * <p>Diagnosis is bounded enough to invert that. Across 210 distinct commands from real runs there were
 * THIRTEEN leading verbs, and against the 112 untruncated ones this allowlist refuses NONE — so it
 * closes the tail at no measured cost to recon.
 */
class ReadOnlyAllowlistTest {

    @Test
    void theOpenTailADenylistCannotReachIsClosed() {
        assertNotNull(OpsSafety.rejectIfNotARead("busybox rm /etc/redis/redis.conf"));
        assertNotNull(OpsSafety.rejectIfNotARead("/opt/mytool --wipe /etc/redis"));
        assertNotNull(OpsSafety.rejectIfNotARead("gdb -p 1 -ex 'call (void)exit(0)'"));
        assertNotNull(OpsSafety.rejectIfNotARead("setsid /tmp/helper"));
    }

    @Test
    void everyShapeRealRunsActuallyUsedStillWorks() {
        for (String ok : List.of(
                "docker ps -a",
                "docker logs authstack-cache --tail 25",
                "docker exec authstack-cache redis-cli CONFIG GET requirepass",
                "cat /etc/redis/redis.conf",
                "ls -la /etc/redis",
                "grep -n requirepass /etc/redis/redis.conf",
                "find / -maxdepth 4 -name 'compose.yml' 2>/dev/null | head -5",
                "curl -s http://localhost:8080/health",
                "systemctl status redis",
                "pwd && ls -la")) {
            assertNull(OpsSafety.rejectIfNotARead(ok), "must stay allowed: " + ok);
        }
    }

    @Test
    void aPipeInsideQUOTESisNotASegmentBoundary() {
        // The mistake that made the first version unusable: a naive split tore
        // `grep -E 'a|b'` into fragments, and the fragment looked like an unknown verb. Measured — it
        // accounted for nearly all of the over-blocking against real commands.
        assertNull(OpsSafety.rejectIfNotARead(
                "docker exec c redis-cli info server | grep -E 'redis_version|tcp_port'"));
        assertNull(OpsSafety.rejectIfNotARead(
                "cat /etc/redis/redis.conf | grep -i 'requirepass\\|bind'"));
    }

    @Test
    void aGoTemplateIsNotASegmentBoundaryEither() {
        assertNull(OpsSafety.rejectIfNotARead(
                "docker inspect c --format '{{.Config.Entrypoint}}|{{.Config.Cmd}}'"));
    }

    @Test
    void theSplitterFindsRealSeparatorsAndOnlyThose() {
        assertEquals(2, OpsSafety.shellSegments("cat a | grep b").size());
        assertEquals(2, OpsSafety.shellSegments("pwd && ls").size());
        assertEquals(1, OpsSafety.shellSegments("grep -E 'a|b' f").size(), "quoted pipe is not a split");
        assertEquals(1, OpsSafety.shellSegments("docker inspect c --format '{{.A}}|{{.B}}'").size());
    }

    @Test
    void theREMEDIATIONphaseGetsTheSameTreatment() {
        // I claimed remediation could not have an allowlist because fixes are "open-ended by nature".
        // The commands the model actually issued say otherwise: across every mutating command recorded
        // in real runs there were THREE leading verbs, and 49 of 54 were `docker`.
        for (String ok : List.of(
                "docker restart authstack-cache",
                "docker stop authstack-cache && docker rm authstack-cache",
                "CACHE_PASSWORD= docker compose -p authstack up -d --force-recreate cache",
                "docker exec authstack-cache redis-cli CONFIG SET requirepass ''",
                "systemctl restart redis")) {
            assertNull(OpsSafety.rejectIfNotAFix(ok), "a real fix must work: " + ok);
        }
        // and the same open tail is closed
        assertNotNull(OpsSafety.rejectIfNotAFix("busybox rm /etc/redis/redis.conf"));
        assertNotNull(OpsSafety.rejectIfNotAFix("/opt/mytool --repair"));
        // installing packages is not a blast-radius-1 fix to one service
        assertNotNull(OpsSafety.rejectIfNotAFix("apt-get install -y redis-tools"));
    }

    @Test
    void aPathQualifiedOrPrefixedVerbIsStillJudgedOnTheVerb() {
        assertNull(OpsSafety.rejectIfNotARead("/usr/bin/cat /etc/redis/redis.conf"));
        assertNull(OpsSafety.rejectIfNotARead("sudo docker ps"));
        assertNull(OpsSafety.rejectIfNotARead("TERM=dumb docker ps"));
        assertNotNull(OpsSafety.rejectIfNotARead("/usr/local/bin/wipe-everything"));
    }
}
