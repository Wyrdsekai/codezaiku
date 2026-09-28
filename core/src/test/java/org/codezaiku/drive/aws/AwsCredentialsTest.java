package org.codezaiku.drive.aws;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
/** The person's own AWS credentials, from where they keep them, asked for again when they run out. */
class AwsCredentialsTest {

    private static final Instant NOW = Instant.parse("2026-01-02T03:00:00Z");

    @Test
    void theCommandLinesAnswerWithAndWithoutAnExpiry() {
        AwsCredentials.Keys withExpiry = AwsCredentials.parse("{\"Version\":1,\"AccessKeyId\":\"ASIAX\",\"SecretAccessKey\":\"s\",\"SessionToken\":\"t\",\"Expiration\":\"2026-01-02T04:00:00+00:00\"}", NOW);
        assertEquals(Instant.parse("2026-01-02T04:00:00Z"), withExpiry.expires());
        assertTrue(withExpiry.fresh(NOW) && !withExpiry.fresh(Instant.parse("2026-01-02T03:56:00Z")), "asked for again five minutes before they run out");
        AwsCredentials.Keys without = AwsCredentials.parse("{\"Version\":1,\"AccessKeyId\":\"ASIAX\",\"SecretAccessKey\":\"s\",\"SessionToken\":\"t\"}", NOW);
        assertNull(without.expires(), "the command line leaves the expiry out when it does not know it");
        assertTrue(without.fresh(NOW.plusSeconds(3000)) && !without.fresh(NOW.plusSeconds(3700)), "temporary credentials of unknown age are kept an hour at most");
        assertTrue(AwsCredentials.parse("{\"AccessKeyId\":\"AKIAX\",\"SecretAccessKey\":\"s\"}", NOW).fresh(NOW.plusSeconds(999_999)), "long-term keys do not run out");
        assertThrows(AwsCredentials.Unavailable.class, () -> AwsCredentials.parse("Unable to locate credentials", NOW));
    }

    @Test
    void theEnvironmentFirstThenTheCommandLineAndForgottenWhenRefused() {
        AtomicInteger asked = new AtomicInteger();
        AwsCredentials.CommandLine cli = profile -> { asked.incrementAndGet(); return "{\"AccessKeyId\":\"FROM-" + profile + "\",\"SecretAccessKey\":\"s\",\"SessionToken\":\"t\",\"Expiration\":\"2026-01-02T09:00:00Z\"}"; };
        AwsCredentials.forget(""); AwsCredentials.forget("research");
        assertEquals("ENVKEY", AwsCredentials.get("", Map.of("AWS_ACCESS_KEY_ID", "ENVKEY", "AWS_SECRET_ACCESS_KEY", "s"), cli, NOW).accessKeyId());
        assertEquals(0, asked.get());
        assertEquals("FROM-research", AwsCredentials.get("research", Map.of("AWS_ACCESS_KEY_ID", "ENVKEY", "AWS_SECRET_ACCESS_KEY", "s"), cli, NOW).accessKeyId(), "a named profile is theirs to choose, whatever the environment holds");
        AwsCredentials.get("research", Map.of(), cli, NOW.plusSeconds(60));
        assertEquals(1, asked.get(), "held until shortly before they run out");
        AwsCredentials.forget("research");
        AwsCredentials.get("research", Map.of(), cli, NOW.plusSeconds(120));
        assertEquals(2, asked.get(), "asked for again once AWS has refused them");
        AwsCredentials.Unavailable u = assertThrows(AwsCredentials.Unavailable.class, () -> AwsCredentials.get("nobody", Map.of(), p -> { throw new IOException("Cannot run program \"aws\""); }, NOW));
        assertTrue(u.getMessage().contains("Install it") && u.getMessage().contains("aws configure sso"), u.getMessage());
    }
}
