package org.codezaiku.ops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * An adversarial audit of the READ-ONLY diagnosis guard, by the claim it makes: the diagnosis/recon
 * phase cannot change the box.
 *
 * <p>Auditing it that way — rather than by extending its verb list — found seven ways through, in a
 * phase whose whole point is that it is incapable of mutation. A shell wrapper hid the leading verb;
 * find/xargs/interpreter reached files without naming a destructive verb; a service's own API was open
 * via {@code SHUTDOWN} and, worst, {@code curl -XPUT .../_settings} — which is literally how the
 * refstack injects its own opensearch fault.
 */
class ReadOnlyGuardAuditTest {

    private static void blocked(String cmd) {
        assertNotNull(OpsSafety.rejectIfMutating(cmd), "must be blocked in diagnosis: " + cmd);
    }

    private static void allowed(String cmd) {
        assertNull(OpsSafety.rejectIfMutating(cmd), "must stay allowed in diagnosis: " + cmd);
    }

    @Test
    void aShellWrapperDoesNotHideTheVerb() {
        blocked("sh -c 'rm /etc/redis/redis.conf'");
        blocked("bash -c \"systemctl restart redis\"");
        blocked("sudo sh -c 'rm /etc/redis/redis.conf'");
    }

    @Test
    void reachingFilesWithoutADestructiveVerbIsStillMutation() {
        blocked("find /etc/redis -name '*.conf' -delete");
        blocked("cat /tmp/l | xargs rm");
        blocked("python3 -c \"import os; os.remove('/etc/redis/redis.conf')\"");
    }

    @Test
    void aServicesOwnApiIsNotAWayAround() {
        blocked("docker exec c redis-cli CONFIG SET requirepass x");
        blocked("redis-cli -h redis FLUSHALL");
        blocked("redis-cli -h redis SHUTDOWN");
        blocked("psql -c 'DROP TABLE users'");
    }

    @Test
    void writeShapedHttpIsMutationEvenThoughCurlIsNotAMutatingVerb() {
        blocked("curl -XPUT 'http://localhost:9200/docs/_settings' -d '{}'");
        blocked("curl -XDELETE http://localhost:9200/docs");
        blocked("curl -XPOST localhost:9200/docs/_close");
        blocked("curl -XPOST localhost:8000/shutdown");
    }

    @Test
    void aPOSTthatIsAQUERYmustStayAllowed() {
        // The tension worth pinning: `_search` is a POST and diagnosis depends on it, so POST cannot be
        // blocked wholesale — it is judged by the endpoint it targets.
        allowed("curl -XPOST localhost:9200/docs/_search -d '{\"query\":{}}'");
        allowed("curl -s -XPOST localhost:9200/_search -H 'c:j' -d '{}'");
    }

    @Test
    void aGETisAreadWhateverItTargets() {
        // `-XGET .../_settings` is how you CHECK whether an index is write-blocked. Blocking it would
        // forbid diagnosing the very fault we ship a card for.
        allowed("curl -XGET localhost:9200/docs/_settings");
        allowed("curl -s localhost:9200/_cluster/health");
    }

    @Test
    void ordinaryInvestigationIsUntouched() {
        allowed("docker ps -a");
        allowed("docker logs redis --tail 25");
        allowed("docker exec c redis-cli CONFIG GET requirepass");
        allowed("cat /etc/redis/redis.conf");
        allowed("systemctl status redis");
        allowed("df -h");
    }
}
