package org.codezaiku.drive.aws;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Our signer against AWS's own: the expected values were made by botocore's SigV4Auth with AWS's example key, at a fixed time. */
class SigV4Test {

    private static final AwsCredentials.Keys KEYS = new AwsCredentials.Keys("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "TOKENEXAMPLE+/=", null, Instant.EPOCH);
    private static final Instant WHEN = Instant.parse("2026-01-02T03:04:05Z");

    @Test
    void aConverseCallWhoseModelIdHasAColonInIt() {
        Map<String, String> h = SigV4.sign("POST", URI.create("https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude-sonnet-4-20250514-v1%3A0/converse"), "application/json",
                "{\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\"hi\"}]}]}".getBytes(StandardCharsets.UTF_8), "us-east-1", "bedrock", KEYS, WHEN);
        assertEquals("20260102T030405Z", h.get("x-amz-date"));
        assertEquals("TOKENEXAMPLE+/=", h.get("x-amz-security-token"));
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20260102/us-east-1/bedrock/aws4_request, SignedHeaders=content-type;host;x-amz-date;x-amz-security-token, Signature=4af23517bfb7dca74e975015113d78ec39b177ace450aa93327b339979cbbf93", h.get("Authorization"));
    }

    @Test
    void aListingWithAQueryAndNoBody() {
        Map<String, String> h = SigV4.sign("GET", URI.create("https://bedrock.us-west-2.amazonaws.com/inference-profiles?maxResults=100&typeEquals=SYSTEM_DEFINED"), null, null, "us-west-2", "bedrock", KEYS, WHEN);
        assertTrue(h.get("Authorization").endsWith("SignedHeaders=host;x-amz-date;x-amz-security-token, Signature=d174915b9dbb8f27d906dfb9e22188bc60c63a6f6d5f30770cd0d6852ec208a1"), h.get("Authorization"));
    }

    @Test
    void anInferenceProfileArnInThePathAnotherPartitionAndABodyThatIsNotAscii() {
        Map<String, String> h = SigV4.sign("POST", URI.create("https://bedrock-runtime.us-gov-west-1.amazonaws.com/model/arn%3Aaws-us-gov%3Abedrock%3Aus-gov-west-1%3A123456789012%3Ainference-profile%2Fus-gov.anthropic.claude-3-5-sonnet-20240620-v1%3A0/invoke"),
                "application/json", "{\"inputText\":\"héllo 世界\"}".getBytes(StandardCharsets.UTF_8), "us-gov-west-1", "bedrock", KEYS, WHEN);
        assertTrue(h.get("Authorization").endsWith("Signature=b7a8f54d2beb065a483cfcf9f5d97fc5d3c592af67f3b8f2835e868353e550c6"), h.get("Authorization"));
    }

    @Test
    void longTermKeysHaveNoTokenHeader() {
        Map<String, String> h = SigV4.sign("GET", URI.create("https://bedrock.us-east-1.amazonaws.com/foundation-models"), null, null, "us-east-1", "bedrock", new AwsCredentials.Keys("AKIDEXAMPLE", "secret", "", null, Instant.EPOCH), WHEN);
        assertFalse(h.containsKey("x-amz-security-token"));
        assertTrue(h.get("Authorization").contains("SignedHeaders=host;x-amz-date,"), h.get("Authorization"));
    }
}
