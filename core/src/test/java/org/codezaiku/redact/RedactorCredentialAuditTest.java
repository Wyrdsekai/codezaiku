package org.codezaiku.redact;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit of the redactor against its claim: a secret never reaches a log, the audit trail, or a prompt.
 *
 * <p>Auditing it by claim rather than by extending its pattern list found seven leaks, and the two that
 * matter most here are the ones this project generates constantly: {@code --requirepass <pass>} is what
 * our own fixtures start redis with, and {@code redis-cli -a <pass>} is what our own card TELLS the
 * model to type. The existing rules keyed on {@code key=value} and {@code key: value}, so a credential
 * passed as a command-line FLAG — separated by a space — was never examined at all.
 *
 * <p>The audit trail is, in the words of the code that writes it, "exactly what gets attached to a
 * ticket", so a leak here travels further than a leak anywhere else in the system.
 *
 * <p>oss-scan: synthetic-credentials — every credential-shaped string below is invented. The marker is
 * opt-in per file so that a real key pasted into a test is still caught, and it suppresses only the
 * credential rules: hostnames, handles and home paths are still refused here like anywhere else.
 */
class RedactorCredentialAuditTest {

    private static void hidden(String input, String secret) {
        String out = Redactor.scrub(input);
        assertFalse(out.contains(secret), "secret survived redaction in: " + out);
    }

    @Test
    void aCredentialPassedAsAFlagIsRedacted() {
        hidden("docker run redis --requirepass s3cretValue", "s3cretValue");
        hidden("redis-server --requirepass=s3cretValue", "s3cretValue");
        hidden("redis-cli --pass s3cretValue ping", "s3cretValue");
    }

    @Test
    void theFormOurOwnCardTellsTheModelToTypeIsRedacted() {
        hidden("docker exec cache redis-cli -a s3cretValue CONFIG GET requirepass", "s3cretValue");
        hidden("redis-cli -a s3cretValue ping", "s3cretValue");
    }

    @Test
    void engineSpecificShapesAreRedacted() {
        hidden("mysql -u root -ps3cretValue", "s3cretValue");
        hidden("curl -u admin:s3cretValue http://x", "s3cretValue");
        hidden("docker exec c redis-cli CONFIG SET requirepass s3cretValue", "s3cretValue");
    }

    @Test
    void aQuotedJsonKeyIsStillAKey() {
        // The rule required the key name to sit against its separator; a closing quote broke it.
        hidden("{\"password\": \"s3cretValue\"}", "s3cretValue");
        hidden("{\"api_key\":\"s3cretValue\"}", "s3cretValue");
    }

    @Test
    void theShapesThatAlreadyWorkedStillWork() {
        hidden("PGPASSWORD=s3cretValue psql -h db", "s3cretValue");
        hidden("export GITHUB_TOKEN=ghp_16CharsAndMoreHere1234567890abcd", "ghp_16CharsAndMoreHere1234567890abcd");
        hidden("-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEAsecretbody\n-----END RSA PRIVATE KEY-----",
                "MIIEowIBAAKCAQEAsecretbody");
    }

    @Test
    void ordinaryOpsOutputIsNotMangled() {
        // Over-redaction costs diagnosis: these are the strings a run is read for.
        assertTrue(Redactor.scrub("redis_version:7.2.5").contains("7.2.5"));
        assertTrue(Redactor.scrub("docker restart refstack-redis-1").contains("refstack-redis-1"));
        assertTrue(Redactor.scrub("-NOAUTH Authentication required.").contains("Authentication required"));
        assertTrue(Redactor.scrub("docker inspect c --format '{{.Config.Cmd}}'").contains("Config.Cmd"));
    }
}
