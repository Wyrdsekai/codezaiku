package org.codezaiku.drive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The class of a model from its name, wherever it runs: a gateway's prefix, a local server's GGUF filename, a family's small variants. */
class ModelClassTest {

    @Test
    void frontierFamiliesThroughAGatewayOrOnALocalCard() {
        for (String m : new String[]{"claude-opus-5-5", "anthropic/claude-sonnet-5-5", "openai/gpt-5", "gpt-5.1", "gpt-4.1", "o3", "o3-pro", "o4",
                "google/gemini-2.5-pro", "gemini-3-pro-preview", "x-ai/grok-4-fast", "deepseek/deepseek-v3.2", "deepseek-r1", "moonshotai/kimi-k2-thinking",
                "qwen3-max-2026-01", "glm-5", "/m/GLM-5.3-big-IQ2_M.gguf", "GLM_5.3_big_IQ2_M.gguf"})
            assertTrue(ModelClass.frontierName(m), m);
    }

    @Test
    void smallerModelsAndSmallVariantsOfAFamily() {
        for (String m : new String[]{"qwen3.8-27b", "/m/Qwen3.8-27B-Q4_K_M.gguf", "qwen3.5-9b", "gpt-oss-20b", "local-model", "gemini-2.5-flash", "gpt-5-mini",
                "gpt-5-nano", "o4-mini", "claude-haiku-4-5-20251001", "deepseek-r1-distill-qwen-7b", "glm-4.5-air", "llama-3.3-70b", "", null})
            assertFalse(ModelClass.frontierName(m), String.valueOf(m));
    }

    @Test
    void theSettingBeatsTheListAndTheClaudeApiIsFrontierByConstruction() {
        assertTrue(ModelClass.frontier("qwen3.8-27b", true), "the Claude API, whatever the name");
        assertFalse(ModelClass.frontier("qwen3.8-27b", false));
        assertTrue(ModelClass.frontier("anthropic/claude-opus-5-5", false), "a frontier name through a gateway");
        // the setting is read through Config; its two values are the only words it knows (anything else leaves the list to decide)
        assertTrue(ModelClass.frontier("gpt-5", false));
    }
}
