package org.codezaiku.drive;

import org.codezaiku.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.Set;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Best judgement: the cheapest model that clears the tier and the wait; a free local model when the wait allows; the wait relaxed
 * before the tier; a private project goes local only; a model with no key or at its cap is passed over and the reason said.
 */
class AutoTest {

    static SavedModels.Entry entry(String name, String drive, String model, String prices, String tier) { return new SavedModels.Entry(name, drive, model, SavedModels.prices(prices), "", 0, "", tier); }
    static Auto.Candidate cand(SavedModels.Entry e, String tier, boolean ok, String whyNot, double min, double dollars, boolean free) { return new Auto.Candidate(e, tier, ok, whyNot, min, dollars, free); }

    SavedModels.Entry local = entry("local-27b", "http://localhost:8200", "qwen3.8-27b", "0/0", "A");
    SavedModels.Entry glm = entry("glm", "https://openrouter.ai/api", "z-ai/glm-5.3", "0.08/4.8", "A");
    SavedModels.Entry sonnet = entry("sonnet", "https://api.anthropic.com", "claude-sonnet-5-5", "3/15", "A");
    SavedModels.Entry small = entry("small", "http://localhost:9000", "tiny-7b", "0/0", "C");

    @Test
    void theCheapestThatClearsTheTierAndTheWaitAndWhy() {
        List<Auto.Candidate> all = List.of(cand(local, "A", true, null, 9, 0, true), cand(glm, "A", true, null, 3, 0.03, false), cand(sonnet, "A", true, null, 3, 0.7, false), cand(small, "C", true, null, 2, 0, true));
        Auto.Job waiting = new Auto.Job("code", "A", true, 5, false);
        Auto.Pick p = Auto.pick(all, waiting);
        assertEquals("glm", p.entry().name(), p.why());
        assertTrue(p.why().startsWith("auto chose glm: tier A for a tier-A code job, about 3 minutes, about $0.03"), p.why());
        assertTrue(p.why().contains("local-27b is free but about 9 minutes"), p.why());
        Auto.Pick batch = Auto.pick(all, new Auto.Job("code", "A", false, 0, false));
        assertEquals("local-27b", batch.entry().name(), "nobody waiting: the free local model");
        Auto.Pick easy = Auto.pick(all, new Auto.Job("chat", "C", true, 5, false));
        assertEquals("small", easy.entry().name(), "a tier-C job takes the free quick one: " + easy.why());
    }

    @Test
    void theWaitIsRelaxedBeforeTheTierAndAPrivateProjectGoesLocal() {
        List<Auto.Candidate> slow = List.of(cand(local, "A", true, null, 9, 0, true), cand(sonnet, "A", true, null, 7, 0.7, false));
        Auto.Pick p = Auto.pick(slow, new Auto.Job("code", "A", true, 5, false));
        assertEquals("local-27b", p.entry().name()); assertTrue(p.why().contains("the wait is relaxed"), p.why());
        List<Auto.Candidate> onlyB = List.of(cand(small, "B", true, null, 2, 0, true));
        Auto.Pick b = Auto.pick(onlyB, new Auto.Job("code", "A", true, 5, false));
        assertEquals("small", b.entry().name()); assertTrue(b.why().contains("nothing saved is tier A, the best available is B"), b.why());
        List<Auto.Candidate> priv = List.of(cand(local, "A", true, null, 9, 0, true), cand(sonnet, "A", false, "the project is private", 3, 0.7, false));
        Auto.Pick pr = Auto.pick(priv, new Auto.Job("code", "A", true, 5, true));
        assertEquals("local-27b", pr.entry().name()); assertTrue(pr.why().contains("sonnet: the project is private"), pr.why());
    }

    @Test
    void aFreeTwinIsTakenWhenNobodyIsWaitingAndTheListHasOne(@TempDir Path project) throws Exception {
        SavedModels.put("glm", "openrouter", "z-ai/glm-5.3", "0.08/4.8", null, "10", null);
        Config.set(DriveClient.hostKeySetting("openrouter.ai"), "sk-or");
        DriveClient.OpenRouterModels.OVERRIDE = Map.of("z-ai/glm-5.3", new DriveClient.OpenRouterModels.Entry(false, Set.of("tools", "reasoning")),
                "z-ai/glm-5.3:free", new DriveClient.OpenRouterModels.Entry(false, Set.of("tools", "reasoning")));
        try {
            SavedModels.Entry glm = SavedModels.get("glm");
            assertEquals("z-ai/glm-5.3:free", SavedModels.freeTwin(glm, new Auto.Job("code", "A", false, 0, false)), "a batch job takes the twin");
            assertNull(SavedModels.freeTwin(glm, new Auto.Job("code", "A", true, 5, false)), "somebody waiting keeps the paid one");
            SavedModels.Choice c = SavedModels.choose(new Auto.Job("code", "A", false, 0, false));
            assertEquals("z-ai/glm-5.3:free", c.model()); assertTrue(c.why().contains("its free twin"), c.why());
        } finally { DriveClient.OpenRouterModels.OVERRIDE = null; }
    }

    /** The overseer is the strongest saved model above the worker; none when the worker is the strongest; a private project keeps it local. */
    @Test
    void theOverseerIsTheStrongestSavedModelAboveTheWorker(@TempDir Path project) throws Exception {
        SavedModels.put("local", "http://127.0.0.1:1", "qwen3.8-27b", "0/0", null, "1", null);
        SavedModels.put("glm", "openrouter", "z-ai/glm-5.3", "0.08/4.8", null, "10", null);
        Config.set(DriveClient.hostKeySetting("openrouter.ai"), "sk-or");
        Auto.Job job = new Auto.Job("code", "B", true, 5, false);
        Auto.Pick p = Auto.chooseOverseer(job, SavedModels.get("local"));
        assertNotNull(p, "a tier-A model is saved above the tier-B worker"); assertEquals("glm", p.entry().name()); assertTrue(p.why().contains("writes the plan and reviews"), p.why());
        assertNull(Auto.chooseOverseer(job, SavedModels.get("glm")), "the worker is the strongest: it plans for itself, as before");
        assertNull(Auto.chooseOverseer(new Auto.Job("code", "B", true, 5, true), SavedModels.get("local")), "a private project: no hosted overseer");
    }

    @Test
    void tiersForAGoal() {
        assertEquals("A", Auto.tierForCoding("Build a CLI from scratch", true));
        assertEquals("A", Auto.tierForCoding("Migrate the startup hook to the lifespan API", false));
        assertEquals("A", Auto.tierForCoding("Train a real classifier on the labelled file", false));
        assertEquals("B", Auto.tierForCoding("Add a search endpoint returning matching emails", false));
        assertEquals("C", Auto.tierForCoding("Fix the typo in the README", false));
        assertEquals("A", ModelTiers.of("moonshotai/kimi-k3")); assertEquals("A", ModelTiers.of("z-ai/glm-5.3")); assertEquals("B", ModelTiers.of("z-ai/glm-5.3-flash"));
        assertEquals("B", ModelTiers.of("deepseek/deepseek-v4.1-flash"), "three quarters of the hard set"); assertEquals("B", ModelTiers.of("deepseek/deepseek-v4-flash")); assertEquals("B", ModelTiers.of("/m/Qwen3.8-27B-Q4_K_M.gguf"), "the local 27B misses the ML-judgement tasks");
        assertEquals("A", ModelTiers.of("gpt-6.1-sol")); assertEquals("A", ModelTiers.of("claude-sonnet-5-5"));
        assertEquals("C", ModelTiers.of("nobody/knows-this")); assertEquals("C", ModelTiers.of(null));
    }

    @TempDir Path home;
    private String realHome;
    @BeforeEach void scratchHome() throws Exception { realHome = System.getProperty("user.home"); System.setProperty("user.home", home.toString()); Files.createDirectories(home.resolve(".codezaiku")); Config.set("CODEZAIKU_DRIVE", "http://localhost:8200"); Config.set("CODEZAIKU_MODEL", "qwen3.8-27b"); }
    @AfterEach void realHomeBack() throws Exception { System.setProperty("user.home", realHome); Config.unset("CODEZAIKU_DRIVE"); }

    @Test
    void autoIsTheDefaultOnceThereIsAChoiceAndCandidatesComeFromTheSettings(@TempDir Path project) throws Exception {
        assertFalse(Auto.isDefault(), "one model saved: nothing to choose");
        SavedModels.put("glm", "openrouter", "z-ai/glm-5.3", "0.08/4.8", null, "10", null);
        assertTrue(Auto.isDefault());
        Auto.Job job = Auto.Job.of("code", "A", true, project);
        Auto.Candidate g = Auto.candidate(SavedModels.get("glm"), job);
        assertFalse(g.reachable()); assertEquals("no key kept for openrouter.ai", g.whyNot());
        Config.set(DriveClient.hostKeySetting("openrouter.ai"), "sk-or");
        g = Auto.candidate(SavedModels.get("glm"), job);
        assertTrue(g.reachable()); assertEquals("A", g.tier()); assertTrue(g.dollars() > 0 && g.dollars() < 0.2, "priced from typical tokens: " + g.dollars());
        Auto.Candidate d = Auto.candidate(SavedModels.defaultEntry(), job);
        assertTrue(d.free() && d.reachable() && d.minutes() > 5, "the local default: free, slow by default");
        SavedModels.Choice c = SavedModels.choose(job);
        assertEquals("glm", c.entry().name(), c.why());
        Files.createDirectories(project.resolve(".codezaiku")); Files.writeString(project.resolve(".codezaiku/private"), "private");
        SavedModels.Choice p = SavedModels.choose(Auto.Job.of("code", "A", true, project));
        assertEquals("default", p.entry().name(), p.why()); assertTrue(p.why().contains("the project is private"), p.why());
        Spend.record("code", project, SavedModels.get("glm"), new long[]{1000, 0, 0, 100}, 9.99, 120, "success");
        assertEquals("its monthly cap is reached", Auto.candidate(SavedModels.put("glm", null, null, null, null, "5", null), job).whyNot());
        assertNull(Spend.average("z-ai/glm-5.3", "code"), "no history before three timed jobs");
        Spend.record("code", project, SavedModels.get("glm"), new long[]{1000, 0, 0, 100}, 0.01, 180, "success");
        Spend.record("code", project, SavedModels.get("glm"), new long[]{1000, 0, 0, 100}, 0.02, 240, "success");
        double[] h = Spend.average("z-ai/glm-5.3", "code");
        assertNotNull(h); assertEquals(3, h[0], 1e-9, "minutes"); assertEquals(3, (int) h[2]);
        assertThrows(IllegalArgumentException.class, () -> SavedModels.put("auto", "openrouter", "x", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> SavedModels.put("glm", null, null, null, null, null, null, "D"));
        assertEquals("B", SavedModels.put("glm", null, null, null, null, null, null, "b").tier(), "a tier set by hand");
    }
}
