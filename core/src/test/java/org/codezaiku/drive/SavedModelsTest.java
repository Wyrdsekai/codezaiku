package org.codezaiku.drive;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.codezaiku.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Saved models (0.3.13): a name keeps an address, a model id, prices, an effort and a cap; host words become addresses; the default is
 * the drive and model settings; a job's choice is a saved name or a model id on the drive; a key is kept per host and goes there only.
 * The settings live in a scratch home, never the developer's.
 */
class SavedModelsTest {

    @TempDir Path home;
    private String realHome;

    @BeforeEach void scratchHome() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".codezaiku"));
        Config.set("CODEZAIKU_DRIVE", "http://localhost:8200");   // writes the scratch config, and the next read loads it
        Config.set("CODEZAIKU_MODEL", "local-model");
    }

    @AfterEach void realHomeBack() throws Exception {
        System.setProperty("user.home", realHome);
        Config.unset("CODEZAIKU_DRIVE");   // read from the real home now: nothing of the scratch stays loaded
    }

    @Test
    void aSavedModelKeepsItsAddressIdPricesEffortAndCap() throws Exception {
        SavedModels.Entry e = SavedModels.put("deepseek", "openrouter", "deepseek/deepseek-v4.1-flash", "0.024/0.72/0.005", "low", "20", "cheap and quick");
        assertEquals("https://openrouter.ai/api", e.drive(), "the host word is its address, kept as a drive base");
        assertEquals("deepseek/deepseek-v4.1-flash", e.model()); assertEquals("openrouter.ai", e.host()); assertTrue(e.hosted());
        assertArrayEquals(new double[]{0.024, 0.72, 0.005}, e.prices()); assertEquals("low", e.effort()); assertEquals(20, e.cap()); assertEquals("cheap and quick", e.note());
        assertEquals("deepseek: deepseek/deepseek-v4.1-flash via openrouter.ai — $0.024/0.72/0.005 per M, effort low, cap $20 a month, tier B (cheap and quick)", e.line());
        assertEquals(e.line(), SavedModels.get("DeepSeek").line(), "names are case-free");
        // the cost: uncached input, the cache's share at its own price, the output
        assertEquals((1000 * 0.024 + 9000 * 0.005 + 500 * 0.72) / 1e6, e.cost(new long[]{10_000, 9_000, 0, 500}), 1e-12);
        assertEquals(0, new SavedModels.Entry("x", "http://h", "m", null, "", 0, "").cost(new long[]{10, 0, 0, 10}), "no prices, no cost");
        // changing one field leaves the others
        SavedModels.Entry changed = SavedModels.put("deepseek", null, null, null, null, "50", null);
        assertEquals(50, changed.cap()); assertEquals("deepseek/deepseek-v4.1-flash", changed.model()); assertEquals("low", changed.effort());
        assertEquals(List.of("default", "deepseek"), SavedModels.names());
        // a plain address, and the old form with no id: the model setting is sent, which a local server ignores
        SavedModels.Entry big = SavedModels.put("big-box", "http://localhost:8201/v1", null, null, null, null, null);
        assertEquals("http://localhost:8201", big.drive()); assertEquals("local-model", big.model()); assertFalse(big.hosted()); assertNull(big.prices());
        assertEquals("big-box: local-model via localhost, tier C", big.line());
        assertTrue(SavedModels.remove("big-box")); assertNull(SavedModels.get("big-box")); assertFalse(SavedModels.remove("big-box"));
        assertThrows(IllegalArgumentException.class, () -> SavedModels.remove("default"));
        assertThrows(IllegalArgumentException.class, () -> SavedModels.put("bad name", "openai", "m", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> SavedModels.put("x", "ftp://nowhere", "m", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> SavedModels.put("x", "openai", "m", "a/b/c/d", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> SavedModels.put("x", "openai", "m", null, null, "-3", null));
        assertNull(SavedModels.get("nobody")); assertNull(SavedModels.get("not a name"));
    }

    @Test
    void theDefaultIsTheSettingsAndAJobChoosesByNameOrById() throws Exception {
        SavedModels.Entry d = SavedModels.defaultEntry();
        assertEquals("http://localhost:8200", d.drive()); assertEquals("local-model", d.model()); assertEquals("default", d.name()); assertNull(d.prices());
        SavedModels.put("default", null, null, "0/0", null, null, null);
        assertArrayEquals(new double[]{0, 0}, SavedModels.get("default").prices(), "prices may be kept for the default too");
        SavedModels.put("fable", "anthropic", "claude-fable-5-1", "5/25/0.5", "high", null, null);
        SavedModels.Choice byName = SavedModels.choose("http://localhost:8200", "fable");
        assertTrue(byName.named()); assertEquals("https://api.anthropic.com", byName.drive()); assertEquals("claude-fable-5-1", byName.model()); assertEquals("high", byName.entry().effort());
        SavedModels.Choice byId = SavedModels.choose("http://localhost:8200", "qwen3.8-27b");
        assertFalse(byId.named()); assertEquals("http://localhost:8200", byId.drive()); assertEquals("qwen3.8-27b", byId.model()); assertNull(byId.entry().prices());
        SavedModels.Choice none = SavedModels.choose(null, null);
        assertEquals("default", none.entry().name()); assertEquals("local-model", none.model());
        SavedModels.Choice same = SavedModels.choose("https://api.anthropic.com", "claude-fable-5-1");
        assertEquals("fable", same.entry().name(), "a drive and id given by hand that a saved model has are priced by it");
        SavedModels.put("default", "openrouter", "x/y", null, null, null, null);
        assertEquals("https://openrouter.ai/api", Config.get("CODEZAIKU_DRIVE")); assertEquals("x/y", Config.get("CODEZAIKU_MODEL"), "default writes the settings themselves");
        var list = SavedModels.list(new ObjectMapper());
        assertEquals("default", list.get(0).path("name").asText()); assertEquals("fable", list.get(1).path("name").asText());
        assertFalse(list.get(1).path("key").asBoolean(), "no key is kept for api.anthropic.com"); assertFalse(list.get(1).has("secret"));
    }

    @Test
    void aKeyIsKeptPerHostAndGoesThereOnly() throws Exception {
        assertNull(DriveClient.keyFor("https://openrouter.ai/api/v1"));
        Config.set(DriveClient.hostKeySetting("openrouter.ai"), "sk-or-1");
        assertEquals("CODEZAIKU_KEY_OPENROUTER_AI", DriveClient.hostKeySetting("openrouter.ai"));
        assertEquals("sk-or-1", DriveClient.keyFor("https://openrouter.ai/api/v1/chat/completions"));
        assertNull(DriveClient.keyFor("https://api.openai.com/v1"), "another host gets nothing of it");
        Config.set("CODEZAIKU_API_KEY", "sk-any");
        assertEquals("sk-any", DriveClient.keyFor("https://api.openai.com/v1"), "the one key setting serves a host with no key of its own");
        assertEquals("sk-or-1", DriveClient.keyFor("https://openrouter.ai/api/v1"), "a host's own key wins over it");
        assertEquals("openrouter.ai", DriveClient.hostOf("https://openrouter.ai/api/v1")); assertNull(DriveClient.hostOf("not an address")); assertNull(DriveClient.hostOf(null));
        SavedModels.put("or", "openrouter", "m", null, null, null, null);
        assertTrue(SavedModels.hasKey(SavedModels.get("or")));
        SavedModels.put("local", "http://localhost:8200", "m", null, null, null, null);
        assertTrue(SavedModels.hasKey(SavedModels.get("local")), "a local server needs none");
    }
}
