package org.codezaiku;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The wizard, scripted: a found server, a machine that serves one on demand, a hosted API with a key, and the registrations. */
class SetupTest {

    static final class FakeProbe implements Setup.Probe {
        final String answering; final List<String> ids;
        FakeProbe(String answering, List<String> ids) { this.answering = answering; this.ids = ids; }
        @Override public List<String> models(String base, String key) { return base.equals(answering) ? ids : null; }
        @Override public String chat(String base, String model, String key) { return base.equals(answering) ? "ready" : "!nothing is answering at that address"; }
        String searx = null; String braveKey = null;
        @Override public boolean searxng(String base) { return searx != null && base.equals(searx); }
        @Override public boolean brave(String key) { return braveKey != null && key.equals(braveKey); }
    }

    static final class FakeActs implements Setup.Acts {
        List<String> hosts = List.of(); final Map<String, List<String>> registered = new HashMap<>();
        String modelOffer = null; String modelServed = "!not on this machine"; boolean library = true; int installs = 0;
        @Override public boolean have(String command) { return hosts.contains(command); }
        @Override public String register(String command, List<String> args) { registered.put(command, new ArrayList<>(args)); return "connected"; }
        @Override public String launcher() { return "/opt/cz/bin/codezaiku"; }
        @Override public String modelOffer() { return modelOffer; }
        @Override public String modelServe(PrintStream out) { out.println("  (fake) installed"); return modelServed; }
        @Override public boolean libraryInstalled() { return library; }
        @Override public int installLibrary(PrintStream out) { installs++; return 0; }
    }

    private static String run(Path home, String script, Setup.Probe probe, FakeActs acts, boolean yes) throws Exception {
        String real = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        try {
            var out = new ByteArrayOutputStream();
            int rc = new Setup(new BufferedReader(new StringReader(script)), new PrintStream(out, true), probe, acts, yes).run(true, true);
            assertEquals(0, rc);
            return out.toString();
        } finally {
            System.setProperty("user.home", real);
            Config.invalidate();
        }
    }

    private static String cfg(Path home) throws Exception { return Files.readString(home.resolve(".codezaiku").resolve("config")); }

    /** A server as llama-swap lists it: alphabetical, so the embedding model is first. Only the chat model answers a chat request. */
    static final class TwoModelProbe implements Setup.Probe {
        final List<String> asked = new ArrayList<>();
        @Override public List<String> models(String base, String key) { return base.equals("http://localhost:8200") ? List.of("embed", "qwen3.8-27b") : null; }
        @Override public String chat(String base, String model, String key) { asked.add(model); return model.equals("qwen3.8-27b") ? "ready" : "!the server answered HTTP 404"; }
    }

    @Test
    void enterNeverSavesTheEmbeddingModelThatTheServerListsFirst(@TempDir Path home) throws Exception {
        var probe = new TwoModelProbe();
        String out = run(home, "\n\n\n\n\n\n\n\n", probe, new FakeActs(), false);          // Enter to everything
        assertTrue(out.contains("This server has 2 models. Pick the one CodeZaiku should work with. It has to be a chat model"), out);
        assertTrue(out.contains("1. embed") && out.contains("looks like an embedding or ranking model: it cannot chat"), out);
        assertTrue(out.contains("Which one? (number or name) [qwen3.8-27b]"), "the default is the chat model, not the first name: " + out);
        assertEquals(List.of("qwen3.8-27b"), probe.asked);
        assertTrue(cfg(home).contains("model = qwen3.8-27b"), cfg(home));
    }

    @Test
    void aModelThatDoesNotAnswerIsNotSavedAndThePersonIsAskedAgain(@TempDir Path home) throws Exception {
        var probe = new TwoModelProbe();
        String out = run(home, "\n1\n\n\n\n\n\n\n\n", probe, new FakeActs(), false);         // use the server, then pick number 1: embed
        assertTrue(out.contains("Asking embed for one word") && out.contains("no: the server answered HTTP 404"), out);
        assertTrue(out.contains("embed did not answer a chat request, so it is not saved. Pick another."), out);
        assertEquals(List.of("embed", "qwen3.8-27b"), probe.asked, "asked again, with the other model as the default");
        String c = cfg(home);
        assertTrue(c.contains("model = qwen3.8-27b") && !c.contains("model = embed"), c);
    }

    @Test
    void theModelInTheSettingsIsTheDefaultAndWhenNothingAnswersTheFirstChoiceIsSavedAndSaidToBeUnchecked(@TempDir Path home) throws Exception {
        Files.createDirectories(home.resolve(".codezaiku"));
        Files.writeString(home.resolve(".codezaiku").resolve("config"), "drive = http://localhost:8200\nmodel = zeta-chat\n");
        Setup.Probe loading = new Setup.Probe() {
            @Override public List<String> models(String base, String key) { return base.equals("http://localhost:8200") ? List.of("alpha-chat", "embed", "zeta-chat") : null; }
            @Override public String chat(String base, String model, String key) { return "!it did not answer in 120 seconds"; }
        };
        String out = run(home, "", loading, new FakeActs(), true);                                   // --yes
        assertTrue(out.contains("3. zeta-chat   (in your settings)") || out.contains("3. zeta-chat"), out);
        assertTrue(out.contains("Which one? (number or name) [zeta-chat]"), out);
        assertTrue(out.contains("None of them answered. Saved zeta-chat unchecked"), out);
        assertTrue(cfg(home).contains("model = zeta-chat"), cfg(home));
    }

    @Test
    void aServerThatListsNoModelsAsksForTheName(@TempDir Path home) throws Exception {
        var probe = new FakeProbe("https://api.example.com/v1", List.of());
        String out = run(home, String.join("\n", "https://api.example.com/v1", "sk-test-123", "provider/big-chat", "", "n") + "\n", probe, new FakeActs(), false);
        assertTrue(out.contains("The server did not list its models. Which model should CodeZaiku use?"), out);
        assertTrue(cfg(home).contains("model = provider/big-chat"), cfg(home));
    }

    @Test
    void namesThatSayAModelCannotChat() {
        for (String id : List.of("embed", "Qwen/Qwen3-Embedding-0.6B", "text-embedding-3-small", "nomic-embed-text", "bge-m3", "bge-reranker-v2", "e5-large", "all-MiniLM-L6-v2", "whisper-1", "jina-embeddings-v3"))
            assertTrue(ModelChoice.looksUnableToChat(id), id);
        for (String id : List.of("qwen3.8-27b", "gpt-5.4", "local-model", "deepseek-chat", "gemma-3-12b", "glm-5.3", "phi-4-mini", "llama3.1:8b", "mistral-small", "embedded-systems-coder"))
            assertFalse(ModelChoice.looksUnableToChat(id), id);
        assertEquals("qwen3.8-27b", ModelChoice.preferred(List.of("embed", "qwen3.8-27b"), null));
        assertEquals("qwen3.8-27b", ModelChoice.preferred(List.of("embed", "qwen3.8-27b"), "embed"), "an embedding model in the settings is not offered again");
        assertEquals("embed", ModelChoice.preferred(List.of("embed"), null), "the only one is the only one");
    }

    @Test
    void aFoundServerIsUsedAndTheProgramsAreConnected(@TempDir Path home) throws Exception {
        var acts = new FakeActs(); acts.hosts = List.of("claude", "gemini");
        String out = run(home, "", new FakeProbe("http://localhost:8200", List.of("qwen3.8-27b", "other")), acts, true);
        assertTrue(out.contains("Found a model server at http://localhost:8200 offering qwen3.8-27b, other."), out);
        assertTrue(out.contains("it answered: \"ready\""), out);
        String c = cfg(home);
        assertTrue(c.contains("drive = http://localhost:8200") && c.contains("model = qwen3.8-27b"), c);
        assertEquals(List.of("mcp", "add", "--scope", "user", "codezaiku", "--", "/opt/cz/bin/codezaiku", "mcp"), acts.registered.get("claude"));
        assertEquals(List.of("mcp", "add", "-s", "user", "codezaiku", "/opt/cz/bin/codezaiku", "mcp"), acts.registered.get("gemini"));
        assertNull(acts.registered.get("codex"), "not installed, not asked");
        assertTrue(out.contains("ResearchZosho, the research library, is installed"), out);
    }

    @Test
    void withNoServerTheMachineOffersToServeOneOnDemand(@TempDir Path home) throws Exception {
        var acts = new FakeActs();
        acts.modelOffer = "Serve gpt-oss-20b on this machine on demand: it downloads once (about 14 GB), starts when a run needs it, and stops after 20 idle minutes.";
        acts.modelServed = "gpt-oss-20b";
        String out = run(home, "", new FakeProbe("http://nowhere", List.of()), acts, true);
        assertTrue(out.contains("No model server was found") && out.contains("Serve gpt-oss-20b"), out);
        String c = cfg(home);
        assertTrue(c.contains("drive = http://127.0.0.1:8211") && c.contains("model = gpt-oss-20b"), c);
        assertFalse(out.contains("Where is your model server?"), out);
    }

    @Test
    void aHostedApiTakesItsKeyAndTheLibraryIsOfferedWhenAbsent(@TempDir Path home) throws Exception {
        var acts = new FakeActs(); acts.library = false;
        var probe = new FakeProbe("https://api.example.com/v1", List.of("big-model")); probe.braveKey = "brave-123";
        String script = String.join("\n",
                "https://api.example.com/v1",   // where the server is
                "sk-test-123",                  // its key; the server has one model, so there is nothing to choose
                "brave-123",                    // web search: the Brave key
                "y"                             // install the library
        ) + "\n";
        String out = run(home, script, probe, acts, false);
        String c = cfg(home);
        assertTrue(c.contains("drive = https://api.example.com/v1") && c.contains("model = big-model") && c.contains("sk-test-123") && c.contains("brave-123"), c);
        assertTrue(out.contains("Brave Search accepts the key"), out);
        assertEquals(1, acts.installs, "the library was installed on a yes");
    }

    @Test
    void theLibraryStepDefaultsToNoAndSearchFallsBack(@TempDir Path home) throws Exception {
        var acts = new FakeActs(); acts.library = false;
        String out = run(home, "", new FakeProbe("http://localhost:11434", List.of("gemma")), acts, true);
        assertEquals(0, acts.installs);
        assertTrue(out.contains("Skipped. Later: codezaiku install researchzosho"), out);
        assertTrue(out.contains("The built-in fallback it is"), out);
    }

    @org.junit.jupiter.api.Test
    void theHelloTestReadsAReasoningModelAsAnswered() {
        org.junit.jupiter.api.Assertions.assertEquals("ready", Setup.helloReply("{\"choices\":[{\"message\":{\"content\":\" ready \"}}]}"));
        org.junit.jupiter.api.Assertions.assertTrue(Setup.helloReply("{\"choices\":[{\"message\":{\"content\":\"\",\"reasoning_content\":\"thinking about the word\"}}]}").startsWith("(it answered"));
        org.junit.jupiter.api.Assertions.assertTrue(Setup.helloReply("{\"choices\":[{\"message\":{\"content\":\"\"}}]}").contains("the address works"));
    }
}
