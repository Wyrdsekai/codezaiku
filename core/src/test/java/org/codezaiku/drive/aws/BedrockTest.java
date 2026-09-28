package org.codezaiku.drive.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
/** Bedrock with somebody's own AWS account: where the request goes, what happens when AWS is busy or refuses, and what the person is told. */
class BedrockTest {

    private static final ObjectMapper J = new ObjectMapper();
    private static final AwsCredentials.Keys KEYS = new AwsCredentials.Keys("AKIDEXAMPLE", "secret", "token", null, Instant.now());
    private static final String REPLY = "{\"output\":{\"message\":{\"content\":[{\"text\":\"hello\"}]}},\"stopReason\":\"end_turn\",\"usage\":{\"inputTokens\":3,\"outputTokens\":1}}";

    private static ObjectNode ask() throws Exception { return (ObjectNode) J.readTree("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":50,\"tool_choice\":\"required\",\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"finish\"}}]}"); }

    @Test
    void whereItGoesAndHowItIsSigned() throws Exception {
        List<String> seen = new ArrayList<>();
        Bedrock b = new Bedrock(new Bedrock.Settings("us-gov-west-1", "research"), (method, uri, headers, body, timeout) -> { seen.add(method + " " + uri + " " + headers.keySet()); return new Bedrock.Answer(200, REPLY); }, p -> { assertEquals("research", p); return KEYS; }, ms -> { });
        ObjectNode r = b.chat("us-gov.anthropic.claude-3-5-sonnet-20240620-v1:0", ask(), Duration.ofSeconds(5));
        assertEquals("hello", r.path("choices").get(0).path("message").path("content").asText());
        assertTrue(seen.get(0).startsWith("POST https://bedrock-runtime.us-gov-west-1.amazonaws.com/model/us-gov.anthropic.claude-3-5-sonnet-20240620-v1%3A0/converse "), seen.get(0));
        assertTrue(seen.get(0).contains("Authorization") && seen.get(0).contains("x-amz-security-token") && seen.get(0).contains("content-type"), seen.get(0));
        assertEquals("bedrock-runtime.cn-north-1.amazonaws.com.cn", new Bedrock.Settings("cn-north-1", "").runtimeHost());
    }

    @Test
    void busyIsWaitedOutRunOutCredentialsAreFetchedAnewAndAToolChoiceTheModelDoesNotTakeIsDropped() throws Exception {
        AtomicInteger calls = new AtomicInteger(), fetched = new AtomicInteger(), waited = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        Bedrock b = new Bedrock(new Bedrock.Settings("us-east-1", ""), (method, uri, headers, body, timeout) -> {
            bodies.add(new String(body, StandardCharsets.UTF_8));
            return switch (calls.incrementAndGet()) {
                case 1 -> new Bedrock.Answer(429, "{\"message\":\"Too many requests, please wait before trying again.\"}");
                case 2 -> new Bedrock.Answer(403, "{\"message\":\"The security token included in the request is expired\"}");
                case 3 -> new Bedrock.Answer(400, "{\"message\":\"This model doesn't support the toolConfig.toolChoice.any field.\"}");
                default -> new Bedrock.Answer(200, REPLY);
            };
        }, p -> { fetched.incrementAndGet(); return KEYS; }, ms -> waited.incrementAndGet());
        assertEquals("hello", b.chat("meta.llama3-1-70b-instruct-v1:0", ask(), Duration.ofSeconds(5)).path("choices").get(0).path("message").path("content").asText());
        assertEquals(4, calls.get());
        assertEquals(1, waited.get());
        assertTrue(bodies.get(2).contains("toolChoice") && !bodies.get(3).contains("toolChoice"), "asked again without the choice: " + bodies.get(3));
    }

    @Test
    void aRefusalIsSaidInWordsThePersonCanActOn() throws Exception {
        Bedrock denied = new Bedrock(new Bedrock.Settings("us-east-1", "research"), (m, u, h, body, t) -> new Bedrock.Answer(403, "{\"message\":\"User: arn:aws:sts::1:assumed-role/x is not authorized to perform: bedrock:InvokeModel on resource: …\"}"), p -> KEYS, ms -> { });
        Bedrock.Refused r = assertThrows(Bedrock.Refused.class, () -> denied.chat("anthropic.claude", ask(), Duration.ofSeconds(5)));
        assertTrue(r.getMessage().contains("Model access") && r.getMessage().contains("bedrock:InvokeModel") && r.getMessage().contains("AWS said: User:"), r.getMessage());
        Bedrock stale = new Bedrock(new Bedrock.Settings("us-east-1", "research"), (m, u, h, body, t) -> new Bedrock.Answer(403, "{\"message\":\"The security token included in the request is invalid\"}"), p -> KEYS, ms -> { });
        assertTrue(assertThrows(Bedrock.Refused.class, () -> stale.chat("x", ask(), Duration.ofSeconds(5))).getMessage().contains("aws sso login --profile research"));
        Bedrock profileOnly = new Bedrock(new Bedrock.Settings("us-east-1", ""), (m, u, h, body, t) -> new Bedrock.Answer(400, "{\"message\":\"Invocation of model ID anthropic.claude-sonnet-4 with on-demand throughput isn't supported. Retry your request with the ID or ARN of an inference profile\"}"), p -> KEYS, ms -> { });
        assertTrue(assertThrows(Bedrock.Refused.class, () -> profileOnly.chat("x", ask(), Duration.ofSeconds(5))).getMessage().contains("inference profile"));
    }

    @Test
    void theSettingsTheModelsAndAVector() throws Exception {
        assertTrue(Bedrock.is("bedrock") && Bedrock.is("bedrock:eu-west-1") && Bedrock.is("https://bedrock-runtime.us-east-1.amazonaws.com/openai/v1") && !Bedrock.is("http://localhost:8211"));
        assertEquals("eu-west-1", Bedrock.settings("bedrock:eu-west-1", k -> "", Map.of()).region());
        assertEquals(new Bedrock.Settings("ap-northeast-1", "team"), Bedrock.settings("bedrock", k -> k.endsWith("REGION") ? "ap-northeast-1" : "team", Map.of("AWS_REGION", "us-east-1")));
        assertEquals("us-west-2", Bedrock.settings("bedrock", k -> "", Map.of("AWS_DEFAULT_REGION", "us-west-2")).region());
        assertTrue(assertThrows(Bedrock.Refused.class, () -> Bedrock.settings("bedrock", k -> "", Map.of())).getMessage().contains("bedrock.region"));
        Bedrock b = new Bedrock(new Bedrock.Settings("us-east-1", ""), (m, u, h, body, t) -> new Bedrock.Answer(200, u.getPath().endsWith("/foundation-models")
                ? "{\"modelSummaries\":[{\"modelId\":\"anthropic.claude-sonnet-4-20250514-v1:0\",\"modelName\":\"Claude Sonnet 4\",\"providerName\":\"Anthropic\",\"inputModalities\":[\"TEXT\",\"IMAGE\"],\"outputModalities\":[\"TEXT\"],\"inferenceTypesSupported\":[\"INFERENCE_PROFILE\"],\"modelLifecycle\":{\"status\":\"ACTIVE\"}},"
                  + "{\"modelId\":\"amazon.titan-embed-text-v2:0\",\"modelName\":\"Titan Text Embeddings V2\",\"providerName\":\"Amazon\",\"inputModalities\":[\"TEXT\"],\"outputModalities\":[\"EMBEDDING\"],\"inferenceTypesSupported\":[\"ON_DEMAND\"],\"modelLifecycle\":{\"status\":\"ACTIVE\"}},"
                  + "{\"modelId\":\"stability.sd3\",\"outputModalities\":[\"IMAGE\"],\"inferenceTypesSupported\":[\"ON_DEMAND\"]},{\"modelId\":\"old.model\",\"outputModalities\":[\"TEXT\"],\"inferenceTypesSupported\":[\"ON_DEMAND\"],\"modelLifecycle\":{\"status\":\"LEGACY\"}}]}"
                : u.getPath().endsWith("/inference-profiles") ? "{\"inferenceProfileSummaries\":[{\"inferenceProfileId\":\"us.anthropic.claude-sonnet-4-20250514-v1:0\",\"inferenceProfileName\":\"US Claude Sonnet 4\",\"status\":\"ACTIVE\"}]}"
                : "{\"embedding\":[0.5,-0.25],\"inputTextTokenCount\":2}"), p -> KEYS, ms -> { });
        List<Bedrock.Model> models = b.models();
        assertEquals(List.of("anthropic.claude-sonnet-4-20250514-v1:0", "amazon.titan-embed-text-v2:0", "us.anthropic.claude-sonnet-4-20250514-v1:0"), models.stream().map(Bedrock.Model::id).toList());
        assertTrue(models.get(0).throughProfileOnly() && models.get(0).readsPictures() && models.get(1).embeds() && models.get(2).profile());
        assertArrayEquals(new float[]{0.5f, -0.25f}, b.embed("amazon.titan-embed-text-v2:0", "a text", 1024));
        assertEquals(200_000, Bedrock.contextWindow("us.anthropic.claude-sonnet-4-20250514-v1:0", 0));
        assertEquals(64_000, Bedrock.contextWindow("anything", 64_000));
        assertEquals(32_000, Bedrock.contextWindow("some.new-model", 0));
    }

    @Test
    void aModelThatWritesFewerTokensThanAskedForIsAskedForWhatItAllows() throws Exception {
        List<String> bodies = new ArrayList<>();
        Bedrock b = new Bedrock(new Bedrock.Settings("us-east-1", ""), (method, uri, headers, body, timeout) -> {
            String sent = new String(body, StandardCharsets.UTF_8); bodies.add(sent);
            return sent.contains("\"maxTokens\":64000") ? new Bedrock.Answer(400, "{\"message\":\"The maximum tokens you requested exceeds the model limit of 32768. Try again with a maximum tokens value that is lower than 32768.\"}") : new Bedrock.Answer(200, REPLY);
        }, p -> KEYS, ms -> { });
        ObjectNode ask = ask(); ask.put("max_tokens", 64000);
        assertEquals("hello", b.chat("small.writer-v1", ask, Duration.ofSeconds(5)).path("choices").get(0).path("message").path("content").asText());
        assertTrue(bodies.get(1).contains("\"maxTokens\":32768"), bodies.get(1));
        b.chat("small.writer-v1", ask, Duration.ofSeconds(5));
        assertEquals(3, bodies.size(), "the limit is remembered: the next request is not refused first");
        assertTrue(bodies.get(2).contains("\"maxTokens\":32768"));
    }
}
