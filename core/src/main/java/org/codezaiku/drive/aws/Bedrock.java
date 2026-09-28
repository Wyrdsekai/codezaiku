package org.codezaiku.drive.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * Amazon Bedrock as the model behind the program, used with the AWS account of the person at this machine. Chat goes through
 * Converse, which Bedrock gives to nearly every chat model it hosts (Claude, Llama, Nova, Qwen, Mistral and others); embeddings go
 * through InvokeModel. What the person may call is decided in their AWS account, by the permissions of the group or role they
 * sign in with: this class only asks, and says plainly what AWS answered when it refuses.
 *
 * <p>The setting is {@code drive = bedrock} (or {@code bedrock:us-east-1}), with {@code bedrock.region} and, when it is not their
 * default, {@code bedrock.profile}. The model is a Bedrock model id or an inference profile id or ARN.
 */
public final class Bedrock {

    public record Settings(String region, String profile) {
        /** amazonaws.com everywhere but China. GovCloud has its own regions under the same name. */
        String suffix() { return region.startsWith("cn-") ? "amazonaws.com.cn" : "amazonaws.com"; }
        public String runtimeHost() { return "bedrock-runtime." + region + "." + suffix(); }
        public String controlHost() { return "bedrock." + region + "." + suffix(); }
    }

    /** One exchange with AWS, so that the tests can stand in for it. */
    public interface Transport { Answer send(String method, URI uri, Map<String, String> headers, byte[] body, Duration timeout) throws Exception; }
    public record Answer(int status, String body) { }

    /** What went wrong, in words for the person: what AWS said, and what usually puts it right. */
    public static final class Refused extends RuntimeException {
        public final int status;
        public Refused(int status, String message) { super(message); this.status = status; }
    }

    private static final ObjectMapper J = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private static final Transport REAL = (method, uri, headers, body, timeout) -> {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout);
        headers.forEach(b::header);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Answer(r.statusCode(), r.body());
    };

    private final Settings settings;
    private final Transport transport;
    private final Function<String, AwsCredentials.Keys> credentials;
    private final LongConsumer pause;

    public Bedrock(Settings settings) { this(settings, REAL, AwsCredentials::get, ms -> { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }); }

    Bedrock(Settings settings, Transport transport, Function<String, AwsCredentials.Keys> credentials, LongConsumer pause) {
        this.settings = settings; this.transport = transport; this.credentials = credentials; this.pause = pause;
    }

    /** Whether the drive setting means Bedrock: {@code bedrock}, {@code bedrock:<region>}, or the address of a bedrock-runtime endpoint. */
    public static boolean is(String drive) {
        String d = drive == null ? "" : drive.strip().toLowerCase(Locale.ROOT);
        return d.equals("bedrock") || d.startsWith("bedrock:") || d.matches("https?://bedrock-runtime\\.[a-z0-9-]+\\.amazonaws\\.com(\\.cn)?(/.*)?");
    }

    /** The region from the drive setting, else from the settings, else from the AWS environment. */
    public static Settings settings(String drive, Function<String, String> setting, Map<String, String> env) {
        String d = drive == null ? "" : drive.strip();
        String region = "";
        Matcher host = Pattern.compile("(?i)bedrock-runtime\\.([a-z0-9-]+)\\.amazonaws").matcher(d);
        if (d.toLowerCase(Locale.ROOT).startsWith("bedrock:")) region = d.substring(8).strip();
        else if (host.find()) region = host.group(1);
        if (region.isBlank()) region = blankToEmpty(setting.apply("CODEZAIKU_BEDROCK_REGION"));
        if (region.isBlank()) region = env.getOrDefault("AWS_REGION", env.getOrDefault("AWS_DEFAULT_REGION", "")).strip();
        if (region.isBlank()) throw new Refused(0, "Bedrock needs to know which AWS region your models are in, for example us-east-1. Add `--region us-east-1` to a `codezaiku bedrock` command, or put `bedrock.region = us-east-1` in the settings.");
        String profile = blankToEmpty(setting.apply("CODEZAIKU_BEDROCK_PROFILE"));
        if (profile.isBlank()) profile = env.getOrDefault("AWS_PROFILE", "").strip();
        return new Settings(region.toLowerCase(Locale.ROOT), profile);
    }

    private static String blankToEmpty(String s) { return s == null ? "" : s.strip(); }

    public Settings settings() { return settings; }

    /** A chat-completions request in, a chat completion out. */
    public ObjectNode chat(String model, JsonNode chatCompletionsBody, Duration timeout) {
        ObjectNode request = Converse.request(chatCompletionsBody);
        // a model that was found to write fewer tokens than it was asked for is asked for that many from then on
        Integer most = MOST_OUTPUT.get(model);
        if (most != null && request.path("inferenceConfig").path("maxTokens").asInt(0) > most) ((ObjectNode) request.path("inferenceConfig")).put("maxTokens", most);
        dump(request);
        URI uri = URI.create("https://" + settings.runtimeHost() + "/model/" + SigV4.encode(model) + "/converse");
        Answer a = null;
        for (int adjusted = 0; a == null; adjusted++) {
            try { a = call("POST", uri, request.toString(), timeout); }
            catch (Refused r) {
                String said = r.getMessage().toLowerCase(Locale.ROOT);
                Matcher limit = Pattern.compile("model limit of (\\d+)").matcher(said);
                // some models on Bedrock take tools but no choice among them: ask again and let the model choose
                if (adjusted < 2 && r.status == 400 && request.path("toolConfig").has("toolChoice") && said.contains("toolchoice")) request = Converse.withoutToolChoice(request);
                // each model writes so many tokens at most, and Bedrock refuses a request for more instead of giving what it can
                else if (adjusted < 2 && r.status == 400 && said.contains("maximum tokens") && limit.find()) { int n = Integer.parseInt(limit.group(1)); MOST_OUTPUT.put(model, n); ((ObjectNode) request.with("inferenceConfig")).put("maxTokens", n); }
                else throw r;
            }
        }
        try { return Converse.response(J.readTree(a.body()), model); }
        catch (Exception e) { throw new Refused(a.status(), "Bedrock answered something that is not a reply: " + a.body().substring(0, Math.min(300, a.body().length()))); }
    }

    private static final Map<String, Integer> MOST_OUTPUT = new ConcurrentHashMap<>();
    private static final AtomicInteger DUMPED = new AtomicInteger();

    /** CODEZAIKU_BEDROCK_DUMP=<folder>: every request as it goes to Bedrock, one file each. For finding out what a model was really sent. */
    private static void dump(ObjectNode request) {
        String dir = System.getenv("CODEZAIKU_BEDROCK_DUMP");
        if (dir == null || dir.isBlank()) return;
        try { Files.createDirectories(Path.of(dir)); Files.writeString(Path.of(dir, String.format("request-%03d.json", DUMPED.incrementAndGet())), request.toPrettyString()); }
        catch (IOException ignored) { }
    }

    /** One text as a vector. Titan and Cohere each have their own request; the model id says which. */
    public float[] embed(String model, String text, int dimensions) {
        ObjectNode body = J.createObjectNode();
        boolean cohere = model.toLowerCase(Locale.ROOT).contains("cohere");
        if (cohere) { body.putArray("texts").add(text); body.put("input_type", "search_document"); }
        else { body.put("inputText", text); if (dimensions > 0 && model.contains("v2")) { body.put("dimensions", dimensions); body.put("normalize", true); } }
        Answer a = call("POST", URI.create("https://" + settings.runtimeHost() + "/model/" + SigV4.encode(model) + "/invoke"), body.toString(), Duration.ofSeconds(60));
        try {
            JsonNode n = J.readTree(a.body());
            JsonNode v = cohere ? n.path("embeddings").path(0) : n.path("embedding");
            if (cohere && !v.isArray()) v = n.path("embeddings").path("float").path(0);
            if (!v.isArray() || v.isEmpty()) throw new Refused(a.status(), "Bedrock's embedding model " + model + " answered without a vector.");
            float[] out = new float[v.size()];
            for (int i = 0; i < out.length; i++) out[i] = (float) v.get(i).asDouble();
            return out;
        } catch (Refused r) { throw r; }
        catch (Exception e) { throw new Refused(a.status(), "Bedrock's embedding model " + model + " answered something that is not a vector."); }
    }

    /** A model or an inference profile the account can see in this region. {@code id} is what goes into the model setting. */
    public record Model(String id, String name, String provider, boolean readsPictures, boolean embeds, boolean throughProfileOnly, boolean profile) { }

    /** What the region offers this account: the foundation models, and the inference profiles that stand in front of some of them. */
    public List<Model> models() {
        List<Model> out = new ArrayList<>();
        try {
            JsonNode fm = J.readTree(call("GET", URI.create("https://" + settings.controlHost() + "/foundation-models"), null, Duration.ofSeconds(60)).body());
            for (JsonNode m : fm.path("modelSummaries")) {
                boolean text = contains(m.path("outputModalities"), "TEXT"), embeds = contains(m.path("outputModalities"), "EMBEDDING");
                if (!text && !embeds) continue;
                if (m.path("modelId").asText().toLowerCase(Locale.ROOT).contains("rerank")) continue;   // orders passages, does not converse
                if (!m.path("modelLifecycle").path("status").asText("ACTIVE").equals("ACTIVE")) continue;
                boolean onDemand = contains(m.path("inferenceTypesSupported"), "ON_DEMAND"), viaProfile = contains(m.path("inferenceTypesSupported"), "INFERENCE_PROFILE");
                if (!onDemand && !viaProfile) continue;
                out.add(new Model(m.path("modelId").asText(), m.path("modelName").asText(), m.path("providerName").asText(), contains(m.path("inputModalities"), "IMAGE"), embeds, !onDemand, false));
            }
            JsonNode ip = J.readTree(call("GET", URI.create("https://" + settings.controlHost() + "/inference-profiles?maxResults=1000"), null, Duration.ofSeconds(60)).body());
            for (JsonNode p : ip.path("inferenceProfileSummaries")) if (p.path("status").asText("ACTIVE").equals("ACTIVE"))
                out.add(new Model(p.path("inferenceProfileId").asText(), p.path("inferenceProfileName").asText(), "inference profile", false, false, false, true));
        } catch (Refused r) { throw r; }
        catch (Exception e) { throw new Refused(0, "The list of Bedrock models could not be read: " + e.getMessage()); }
        return out;
    }

    private static boolean contains(JsonNode array, String value) { for (JsonNode x : array) if (x.asText().equalsIgnoreCase(value)) return true; return false; }

    /**
     * The window of a model in tokens, where it is known: Bedrock has no way to ask. {@code bedrock.context} in the settings says it for
     * any model; without it a cautious number is used, because a window taken for larger than it is loses the start of a conversation.
     */
    public static int contextWindow(String model, int configured) {
        if (configured > 0) return configured;
        String m = model.toLowerCase(Locale.ROOT);
        if (m.contains("anthropic.claude")) return 200_000;
        if (m.contains("amazon.nova-micro")) return 128_000;
        if (m.contains("amazon.nova")) return 300_000;
        if (m.contains("meta.llama3-1") || m.contains("meta.llama3-2") || m.contains("meta.llama3-3") || m.contains("meta.llama4")) return 128_000;
        if (m.contains("mistral.mistral-large") || m.contains("qwen") || m.contains("deepseek") || m.contains("openai.gpt-oss")) return 128_000;
        return 32_000;
    }

    /** One signed call. Throttling waits and asks again; credentials AWS refuses as run out are fetched anew once; anything else is said plainly. */
    Answer call(String method, URI uri, String body, Duration timeout) {
        byte[] bytes = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        boolean refetched = false;
        for (int attempt = 1; ; attempt++) {
            AwsCredentials.Keys keys = credentials.apply(settings.profile());
            Map<String, String> headers = new LinkedHashMap<>(SigV4.sign(method, uri, bytes == null ? null : "application/json", bytes, settings.region(), "bedrock", keys, Instant.now()));
            if (bytes != null) headers.put("content-type", "application/json");
            Answer a;
            try { a = transport.send(method, uri, headers, bytes, timeout); }
            catch (Exception e) { throw new Refused(0, "Bedrock in " + settings.region() + " could not be reached: " + e.getMessage()); }
            if (a.status() / 100 == 2) return a;
            String said = message(a.body()), kind = (a.body() + " " + said).toLowerCase(Locale.ROOT);
            boolean staleKeys = a.status() == 403 && (kind.contains("expired") || kind.contains("security token") || kind.contains("invalidclienttokenid") || kind.contains("unrecognizedclient"));
            if (staleKeys && !refetched) { AwsCredentials.forget(settings.profile()); refetched = true; continue; }
            boolean busy = a.status() == 429 || a.status() == 503 || kind.contains("throttl") || kind.contains("modelnotready") || kind.contains("serviceunavailable");
            if (busy && attempt < 5) { pause.accept(1500L * attempt * attempt); continue; }
            throw new Refused(a.status(), explain(a.status(), said, kind));
        }
    }

    private static String message(String body) {
        try { JsonNode n = J.readTree(body); String m = n.path("message").asText(n.path("Message").asText("")); return m.isBlank() ? body : m; }
        catch (Exception e) { return body == null ? "" : body; }
    }

    private String explain(int status, String said, String kind) {
        String awsSaid = " AWS said: " + said.strip();
        if (status == 403 && (kind.contains("expired") || kind.contains("security token") || kind.contains("invalidclienttokenid")))
            return "Your AWS sign-in is not accepted any more. Sign in again (`aws sso login" + (settings.profile().isBlank() ? "" : " --profile " + settings.profile()) + "`, or however you usually sign in to AWS) and try again." + awsSaid;
        if (status == 403 || kind.contains("accessdenied"))
            return "Your AWS sign-in is not allowed to do this. Two things decide it, both in the AWS account and not here: the model must be switched on under \"Model access\" in the Bedrock console for the region " + settings.region()
                    + ", and the group or role you sign in with must allow bedrock:InvokeModel (and bedrock:InvokeModelWithResponseStream) on it. Ask whoever manages the AWS account." + awsSaid;
        if (kind.contains("use case details"))
            return "Anthropic's models need a short form filled in once for each AWS account before they can be used: in the AWS console, under Amazon Bedrock, open the model in the model catalog and submit the use case details. "
                    + "AWS says it can take fifteen minutes after that. A model called through an inference profile is served from several regions, so it can work one moment and be refused the next until the form has reached them all." + awsSaid;
        if (status == 404 || kind.contains("resourcenotfound") || (status == 400 && kind.contains("model identifier")))
            return "Bedrock does not know this model in the region " + settings.region() + ". Check the model id. Some models are called only through an inference profile, whose id begins with a region group such as us. or eu." + awsSaid;
        if (status == 400 && kind.contains("on-demand throughput")) return "This model is called through an inference profile, not by its own id: use the profile's id, which begins with a region group such as us. or eu." + awsSaid;
        if (status == 400 && kind.contains("image content block")) return "This model does not read pictures. `codezaiku bedrock models` marks the ones that do." + awsSaid;
        if (status == 429 || kind.contains("throttl")) return "Bedrock is asking for fewer requests than this account's limit allows right now. It was tried several times. The limit is set in the AWS account (Service Quotas)." + awsSaid;
        return "Bedrock refused the request (HTTP " + status + ")." + awsSaid;
    }
}
