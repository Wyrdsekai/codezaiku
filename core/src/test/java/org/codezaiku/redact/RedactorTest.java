package org.codezaiku.redact;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two failure directions matter equally here. Missing a secret leaks it to the drive and to
 * {@code audit.jsonl}. Masking ordinary code corrupts the material a review or a fix is built on,
 * which is the quieter and more damaging failure — so the over-redaction cases are tested as hard as
 * the leaks.
 *
 * <p>oss-scan: synthetic-credentials — every credential-shaped string below is invented and inert:
 * AWS's own documentation key id, a sequential-alphabet token, a PEM with a non-key body. The OSS
 * export scanner is told, by this marker, not to raise them as leaks. The marker suppresses only the
 * credential rules for this file; hostnames, handles and home paths are still scanned here, and a
 * REAL key pasted into this file would still need the marker removed to be caught — so treat adding
 * it to any other file as a decision, not a formality.
 */
class RedactorTest {

    private static void masked(String input, String mustNotContain) {
        Redactor.Result r = Redactor.redact(input);
        assertTrue(r.redacted(), "should have masked something in: " + input);
        assertFalse(r.text().contains(mustNotContain),
                "secret survived: " + r.text());
    }

    private static void untouched(String input) {
        Redactor.Result r = Redactor.redact(input);
        assertEquals(input, r.text(), "must not alter: " + input);
        assertEquals(0, r.count());
    }

    // ── things that must be masked ────────────────────────────────────────────

    /**
     * Asserts the RULE, not just the outcome. An earlier version checked only that the password was
     * gone, and still passed with url-credential disabled — the email rule was masking
     * {@code s3cr3tP4ss@db.internal} as an address local-part. The value was masked either way, so the
     * test proved nothing about the rule it named. Mutation testing found that; naming the rule fixes it.
     */
    @Test void masksPasswordInAConnectionString() {
        Redactor.Result r = Redactor.redact("postgres://svc_user:s3cr3tP4ss@db.internal:5432/app");
        assertFalse(r.text().contains("s3cr3tP4ss"), "password survived: " + r.text());
        assertTrue(r.rules().contains("url-credential"),
                "url-credential must be what caught it, not an incidental rule: " + r.rules());
        assertTrue(r.text().contains("svc_user") && r.text().contains("db.internal"),
                "user/host must survive — the ops localizer needs them: " + r.text());
    }

    @Test void masksAssignedCredentials() {
        masked("PGPASSWORD=hunter2horse", "hunter2horse");
        masked("api_key: \"a1b2c3d4e5f6g7h8\"", "a1b2c3d4e5f6g7h8");
        masked("client_secret = zyxwvu987654321", "zyxwvu987654321");
    }

    @Test void masksVendorTokens() {
        masked("AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE", "AKIAIOSFODNN7EXAMPLE");
        masked("token ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ012345", "ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ012345");
        masked("xoxb-1234567890-abcdefghijkl", "xoxb-1234567890-abcdefghijkl");
        masked("sk-ant-api03-AbCdEfGhIjKlMnOpQrStUvWx", "sk-ant-api03-AbCdEfGhIjKlMnOpQrStUvWx");
        masked("hf_QwErTyUiOpAsDfGhJkLzXcVbNm12", "hf_QwErTyUiOpAsDfGhJkLzXcVbNm12");
    }

    @Test void masksAuthorizationHeaderAndJwt() {
        masked("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N",
                "dozjgNryP4J3jVmNHl0w5N");
    }

    @Test void masksPrivateKeyBodyButKeepsTheHeader() {
        String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEAx7Zt9k2mQ==\nabcdef\n-----END RSA PRIVATE KEY-----";
        Redactor.Result r = Redactor.redact(pem);
        assertFalse(r.text().contains("MIIEowIBAAKCAQEAx7Zt9k2mQ"), "key body must be gone: " + r.text());
        assertTrue(r.text().contains("BEGIN RSA PRIVATE KEY"), "the header should survive so the reader knows what it was");
    }

    /** The domain carries the diagnostic meaning; the person does not. */
    @Test void masksEmailLocalPartAndKeepsDomain() {
        Redactor.Result r = Redactor.redact("failed login for alice.smith@corp.example.com");
        assertFalse(r.text().contains("alice.smith"), r.text());
        assertTrue(r.text().contains("@corp.example.com"), "the domain should survive: " + r.text());
    }

    // ── things that must NOT be touched ───────────────────────────────────────

    @Test void leavesEnvironmentLookupsAlone() {
        untouched("password = os.environ[\"PG_PASS\"]");
        untouched("password=$DB_PASSWORD");
        untouched("api_key: ${OPENAI_API_KEY}");
    }

    @Test void leavesPlaceholdersAlone() {
        untouched("password=changeme");
        untouched("api_key: <YOUR_API_KEY>");
        untouched("secret = placeholder");
    }

    @Test void leavesOrdinaryCodeAlone() {
        untouched("if (password.isEmpty()) throw new IllegalArgumentException();");
        untouched("private static final int TOKEN_LIMIT = 4096;");
        untouched("// the api_key is read from the config file at startup");
        untouched("SELECT id, email FROM users WHERE active = true");
    }

    /** A detection RULE that names these patterns is documentation, not a credential. */
    @Test void leavesOurOwnKnowledgePackPatternsAlone() {
        untouched("- **Known formats**: AWS keys (AKIA...), GitHub tokens (ghp_...), Slack (xoxb-...)");
    }

    @Test void shortValuesAreNotSecrets() {
        untouched("token=abc");
        untouched("password=1");
    }

    // ── the reporting contract ────────────────────────────────────────────────

    @Test void countsAndNamesTheRulesThatFired() {
        Redactor.Result r = Redactor.redact("PGPASSWORD=hunter2horse and AKIAIOSFODNN7EXAMPLE");
        assertEquals(2, r.count());
        assertTrue(r.rules().contains("assigned-credential"), r.rules().toString());
        assertTrue(r.rules().contains("aws-key-id"), r.rules().toString());
        assertTrue(r.note().contains("2 secret/PII value(s) masked"), r.note());
    }

    /** The model must be told masking happened, or it will treat the mask as the literal value. */
    @Test void noNoteWhenNothingWasMasked() {
        assertEquals("", Redactor.redact("nothing sensitive here").note());
    }

    @Test void handlesNullAndEmpty() {
        assertEquals(null, Redactor.redact(null).text());
        assertEquals("", Redactor.redact("").text());
        assertEquals(0, Redactor.redact("").count());
    }

    /** Masking must be idempotent — a second pass must not eat the mask or double-count. */
    @Test void isIdempotent() {
        String once = Redactor.scrub("postgres://u:s3cr3tP4ss@h/db");
        assertEquals(once, Redactor.scrub(once), "re-redacting must be a no-op");
    }
}
