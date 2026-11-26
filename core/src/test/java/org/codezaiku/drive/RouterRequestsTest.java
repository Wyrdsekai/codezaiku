package org.codezaiku.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.codezaiku.Config;
import org.codezaiku.Setup;
import org.codezaiku.loop.FamiliarLoop;
import org.codezaiku.run.ResultDocument;
import org.codezaiku.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The checks ResearchZosho 0.8.1 ran on its own requests to the router, ported: a request is never sent to the router without the list
 * of providers to leave out, whatever its body held and whichever path made it; the router is told apart from names that look like it;
 * and when nobody is left to serve the model every path says so in CodeZaiku's words and sends nothing, a job ending there on the path
 * of a model server that cannot be used. The router is a stand-in on the loopback ({@link OpenRouterProvidersTest}).
 */
class RouterRequestsTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String MODEL = "deepseek/deepseek-v4.1-flash";

    @TempDir Path home;
    String realHome;
    HttpServer router; String drive;
    final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile String answer = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ready\"},\"finish_reason\":\"stop\"}]}";
    Function<String, String> fetch; LongSupplier now;

    @BeforeEach void up() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.resolve("home").toString()); Config.invalidate();
        fetch = OpenRouterProviders.FETCH; now = OpenRouterProviders.NOW;
        OpenRouterProviders.FETCH = url -> null;
        OpenRouterProviders.forget();
        DriveClient.OpenRouterModels.OVERRIDE = Map.of();
        router = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        router.createContext("/", x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean chat = x.getRequestURI().getPath().endsWith("/chat/completions");
            if (chat) bodies.add(M.readTree(body));
            byte[] b = (chat ? answer : "{}").getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(chat ? status : 404, b.length); x.getResponseBody().write(b); x.close();
        });
        router.start();
        drive = "http://127.0.0.1:" + router.getAddress().getPort();
        DriveClient.ROUTER_FOR_TESTS = drive;
    }

    @AfterEach void down() {
        router.stop(0);
        DriveClient.ROUTER_FOR_TESTS = null;
        DriveClient.OpenRouterModels.OVERRIDE = null;
        OpenRouterProviders.FETCH = fetch; OpenRouterProviders.NOW = now; OpenRouterProviders.forget();
        System.setProperty("user.home", realHome); Config.invalidate();
    }

    static ArrayNode hello() { ArrayNode m = M.createArrayNode(); m.addObject().put("role", "user").put("content", "hi"); return m; }

    static List<String> ignored(JsonNode body) { List<String> out = new ArrayList<>(); for (JsonNode s : body.path("provider").path("ignore")) out.add(s.asText()); return out; }

    static String endpoints(String... tags) {
        StringBuilder b = new StringBuilder("{\"data\":{\"id\":\"" + MODEL + "\",\"endpoints\":[");
        for (int i = 0; i < tags.length; i++) b.append(i == 0 ? "" : ",").append("{\"provider_name\":\"x\",\"tag\":\"").append(tags[i]).append("\"}");
        return b.append("]}}").toString();
    }

    /**
     * What is handed to the client as a request's body and is none (nothing, a list, a word) is left as it is: there is no request
     * in it to add a list to, and the place that adds the list is not the place to fail. The control: a request's body gets the
     * list, and another host's body is not read at all.
     */
    @Test
    void aBodyThatIsNoRequestIsLeftAsItIs() throws Exception {
        DriveClient c = new DriveClient(drive, MODEL);
        Map<String, String> got = new LinkedHashMap<>();
        for (String body : List.of("", "   ", "[]", "null", "\"a word\"", "42", "[{\"model\":\"x\"}]")) {
            try { got.put(body, c.routed(body).equals(body) ? "left as it is" : "CHANGED"); } catch (Exception e) { got.put(body, "THREW " + e.getClass().getSimpleName()); }
        }
        assertTrue(ignored(M.readTree(c.routed("{\"model\":\"" + MODEL + "\",\"messages\":[]}"))).contains("baidu"), "a request's body gets the list (the control)");
        DriveClient.ROUTER_FOR_TESTS = null;
        assertEquals("not json at all {", new DriveClient(drive, MODEL).routed("not json at all {"), "another host's body is not read");
        assertEquals("{\"model\":\"m\"}", DriveClient.routed("http://127.0.0.1:8200", "{\"model\":\"m\"}", "m"), "nor is a request to another host given the list");
        for (Map.Entry<String, String> e : got.entrySet()) assertEquals("left as it is", e.getValue(), "the body " + e.getKey());
    }

    /**
     * The providers to leave out go with every request to the router, whatever stood in the request's provider field: the
     * request's own choices beside them when the field is the router's own form (an object), and in its place when it is not (a
     * word, a list, a number are no form the router takes). A request is never sent without the list because of what its body held.
     */
    @Test
    void theProvidersToLeaveOutGoWithTheRequestWhateverStoodInItsProviderField() throws Exception {
        List<String> all = OpenRouterProviders.ignored();
        assertTrue(all.contains("baidu") && all.size() > 3, "the list (the instrument): " + all);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("none", "{}");
        fields.put("the router's form, with choices of its own", "{\"provider\":{\"order\":[\"fireworks\"],\"ignore\":[\"some-other\"],\"allow_fallbacks\":false}}");
        fields.put("a word", "{\"provider\":\"deepinfra\"}");
        fields.put("a list", "{\"provider\":[\"deepinfra\"]}");
        fields.put("a number", "{\"provider\":7}");
        fields.put("nothing", "{\"provider\":null}");
        Map<String, String> got = new LinkedHashMap<>();
        for (Map.Entry<String, String> f : fields.entrySet()) {
            ObjectNode body = (ObjectNode) M.readTree(f.getValue());
            body.put("model", MODEL);
            DriveClient.routed(body, drive);
            got.put(f.getKey(), ignored(body).containsAll(all) ? "the list is there" : "NO LIST: " + body.path("provider"));
            if (f.getKey().startsWith("the router's form")) assertTrue(ignored(body).contains("some-other") && body.path("provider").path("order").path(0).asText().equals("fireworks") && !body.path("provider").path("allow_fallbacks").asBoolean(true), "its own choices are kept beside the list: " + body);
        }
        for (Map.Entry<String, String> e : got.entrySet()) assertEquals("the list is there", e.getValue(), e.getKey());
    }

    /**
     * Expected: the test for "this host is OpenRouter" says yes for the real host however its address is written, and no for a name
     * that only looks like it. A yes for another host tells the person what the router's providers are; a no for the real host sends
     * it requests without the list of providers to leave out.
     */
    @Test
    void theTestForTheRoutersHostIsRightForTheRealHostAndForLookAlikes() {
        DriveClient.ROUTER_FOR_TESTS = null;
        Map<String, Boolean> expected = new LinkedHashMap<>();
        expected.put("https://openrouter.ai/api/v1", true);
        expected.put("https://openrouter.ai/api/v1/", true);
        expected.put("https://OpenRouter.AI/api/v1", true);
        expected.put("https://openrouter.ai:443/api/v1", true);
        expected.put("https://openrouter.ai./api/v1", true);   // the same host, written with the dot that ends a full name
        expected.put("https://eu.openrouter.ai/api/v1", true);
        expected.put("https://notopenrouter.ai/api/v1", false);
        expected.put("https://my-openrouter.ai/api/v1", false);
        expected.put("https://openrouter.ai.example.org/api/v1", false);
        expected.put("https://openrouter.ai@example.org/api/v1", false);
        expected.put("https://example.org/openrouter.ai/api/v1", false);
        expected.put("https://example.org/api/v1?via=openrouter.ai", false);
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : expected.entrySet()) if (DriveClient.openRouter(e.getKey()) != e.getValue()) wrong.add(e.getKey() + " is taken " + (e.getValue() ? "for another host" : "for OpenRouter"));
        assertEquals(List.of(), wrong, "the test for the router's host is wrong for an address");
        assertTrue(DriveClient.hostIs("EU.API.Groq.com.", "api.groq.com") && !DriveClient.hostIs(null, "openrouter.ai"));
        // what each mistake does: the real host written with its dot gets the list; a look-alike gets none, and nothing is said of the router's providers for it
        ObjectNode withDot = M.createObjectNode().put("model", MODEL);
        assertFalse(DriveClient.routed(withDot, "https://openrouter.ai./api/v1").isEmpty());
        assertTrue(ignored(withDot).contains("baidu"), withDot.toString());
        ObjectNode lookAlike = M.createObjectNode().put("model", MODEL);
        assertTrue(DriveClient.routed(lookAlike, "https://notopenrouter.ai/api/v1").isEmpty() && !lookAlike.has("provider"), lookAlike.toString());
        OpenRouterProviders.FETCH = url -> url.contains("/endpoints") ? endpoints("baidu", "fireworks") : null;
        assertEquals("", ModelPlaces.said(new SavedModels.Entry("other", "https://notopenrouter.ai/api/v1", MODEL, null, "", 0, "")), "the person is told of the router's providers for a host that is not the router");
        assertTrue(ModelPlaces.said(new SavedModels.Entry("real", "https://openrouter.ai./api/v1", MODEL, null, "", 0, "")).startsWith("Served by 2 providers."), "the control");
    }

    /**
     * When every provider that serves a model is left out, nothing is sent and the person reads which setting allows them: in a turn,
     * in a decision, in the hello that setup sends, and as the end of a job, whose result says "failed" with the sentence. The control:
     * a model somebody else serves is asked, on every one of the paths.
     */
    @Test
    void whenNobodyIsLeftToServeAModelEveryPathSaysSoAndSendsNothing(@TempDir Path project) throws Exception {
        Setup.Probe probe = Setup.liveProbe();
        // somebody serves it: every path asks the router (the control, and the count)
        OpenRouterProviders.FETCH = url -> url.contains("/endpoints") ? endpoints("baidu/fp8", "fireworks") : null;
        DriveClient c = new DriveClient(drive, MODEL);
        c.chat(hello(), null, 32, "none");
        c.classify(hello(), 8);
        String said = probe.chat(drive, MODEL, "");
        assertEquals(3, bodies.size(), "a turn, a decision and setup's hello each reached the router: " + bodies.size());
        assertEquals("ready", said, "and setup's hello was answered");
        for (JsonNode b : bodies) assertTrue(ignored(b).contains("baidu"), "each with the list: " + b);
        // nobody is left
        bodies.clear();
        OpenRouterProviders.forget();
        OpenRouterProviders.FETCH = url -> url.contains("/endpoints") ? endpoints("baidu/fp8", "alibaba", "baidu") : null;
        Map<String, String> told = new LinkedHashMap<>();
        DriveClient none = new DriveClient(drive, MODEL);
        try { none.chat(hello(), null, 32, "none"); told.put("a turn", "ANSWERED"); } catch (RuntimeException e) { told.put("a turn", e.getMessage()); }
        told.put("setup's hello", probe.chat(drive, MODEL, ""));
        Files.writeString(project.resolve("note.txt"), "hello");
        FamiliarLoop.Result job = new FamiliarLoop(none, ToolRegistry.readOnly(project, null), project, "read note.txt and say what it holds", 12, null, null).run();
        told.put("a job", job.summary());
        FamiliarLoop.Result chat = new FamiliarLoop(none, ToolRegistry.readOnly(project, null), project, "what does note.txt hold?", 12, null, null).chat().run();
        told.put("a chat turn", chat.summary());
        assertEquals("", none.classify(hello(), 8), "a decision has no answer");
        int sent = bodies.size();
        assertEquals(0, sent, "something was sent though nobody is left to serve the model: " + bodies);
        for (Map.Entry<String, String> e : told.entrySet()) assertTrue(String.valueOf(e.getValue()).contains("Every provider that serves " + MODEL + " through OpenRouter") && e.getValue().contains("codezaiku config set avoid_places"), e.getKey() + " says which setting allows them: " + e.getValue());
        assertTrue(told.get("setup's hello").startsWith("!"), "as the reason a hello failed");
        assertFalse(job.done());
        assertTrue(job.summary().startsWith(ResultDocument.DRIVE_UNAVAILABLE + " Every provider"), "the path of a model server that cannot be used: " + job.summary());
        assertTrue(job.turns() <= 1, "it ends at once, not after eight failed calls: " + job.turns());
        assertEquals("failed", ResultDocument.statusForRun(job.done(), null, job.summary()), "codezaiku run's status");
        // and when who serves the model cannot be read: the hello goes with its list, and the router's own refusal is said the same way
        OpenRouterProviders.forget();
        OpenRouterProviders.FETCH = url -> null;
        status = 404; answer = "{\"error\":{\"message\":\"No endpoints found matching your data policy.\",\"code\":404}}";
        String refused = probe.chat(drive, MODEL, "");
        assertTrue(refused.startsWith("!OpenRouter found no provider for " + MODEL) && refused.contains("codezaiku config set avoid_places"), "the router's own refusal, said as CodeZaiku says it: " + refused);
        assertTrue(ignored(bodies.get(bodies.size() - 1)).contains("baidu"), "the hello carried the list");
        answer = "{\"error\":{\"message\":\"The model does not exist.\",\"code\":404}}";
        assertTrue(probe.chat(drive, MODEL, "").startsWith("!the server answered HTTP 404"), "any other 404 stays the server's");
    }

    static Path src() { return Files.exists(Path.of("src")) ? Path.of("src") : Path.of("core/src"); }

    /**
     * Read from the sources: every place that builds a request for a completion (an address ending in /chat/completions) passes its
     * body through the one place that adds the list of providers a router is to leave out ({@code routed}, or {@code hostedShape},
     * which calls it). A sender written later that builds its body by hand fails this until it does; setup's hello, the doctor's
     * probe and the recovery of a malformed call were such senders, and went without the list.
     */
    @Test
    void everyPlaceThatSendsACompletionAddsTheListOfProvidersToLeaveOut() throws IOException {
        List<String> senders = new ArrayList<>(), bare = new ArrayList<>();
        Set<String> files = new TreeSet<>();
        Pattern sender = Pattern.compile("\\+\\s*\"(/v1)?/chat/completions\"");
        Path java = src().resolve("main").resolve("java");
        try (Stream<Path> walk = Files.walk(java)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String[] lines = Files.readString(p).split("\n", -1);
                String name = java.relativize(p).toString().replace('\\', '/').replaceFirst("^org/codezaiku/", "");
                for (int i = 0; i < lines.length; i++) {
                    if (!sender.matcher(lines[i]).find()) continue;
                    senders.add(name + ":" + (i + 1)); files.add(name);
                    boolean shaped = false;
                    // the body is shaped a few lines before the request is built, or as it is handed to it in the same statement
                    for (int k = Math.max(0, i - 40); k <= Math.min(lines.length - 1, i + 6) && !shaped; k++) shaped = lines[k].contains("routed(") || lines[k].contains("hostedShape(");
                    if (!shaped) bare.add(name + ":" + (i + 1) + " " + lines[i].strip());
                }
            }
        }
        assertEquals(Set.of("Doctor.java", "ModelServer.java", "Setup.java", "drive/DriveClient.java", "library/Distiller.java", "loop/Localizer.java", "loop/WiringPlanner.java"), files,
                "a new sender of completions: see that its body passes DriveClient.routed, then name it here");
        assertTrue(senders.size() >= 9, "the scan found the senders (the control): " + senders);
        assertEquals(List.of(), bare, "a completion is built without the list of providers a router is to leave out");
    }
}
