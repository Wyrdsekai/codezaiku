package org.codezaiku.drive;

import org.codezaiku.Config;

import java.util.List;
import java.util.Locale;

/**
 * What class of model a drive serves, as far as the harness can tell: frontier, or something smaller. It decides whether the harness
 * researches before a coding task (a 27B needed the research to finish the brief; Claude finished it without, at turn 38).
 * <p>
 * The address says where a model runs, not what it is: GPT-5 through OpenRouter looks like a local server, and a frontier family's
 * weights run on one card at a heavy quantization. So the class comes from the model's FAMILY NAME, wherever it runs — as a gateway
 * reports it ({@code openai/gpt-5}, {@code anthropic/claude-opus-5-5}) or as a local server does ({@code GLM-5.3-big-IQ2_M.gguf}) —
 * with the small variants of a family ({@code mini}, {@code flash}, {@code distill}, {@code haiku}, …) taken as smaller. The Claude API
 * is frontier by construction. {@code CODEZAIKU_DRIVE_CLASS=frontier|small} beats the list either way, for the model nobody has heard
 * of or the person who knows better. The list goes stale as the labs ship; a stale entry costs one research pass run or skipped.
 */
public final class ModelClass {

    private ModelClass() { }

    /** The frontier families, as the start of a model name after any provider prefix; a trailing dash means "any model of the family". */
    static final List<String> FRONTIER_FAMILIES = List.of("claude-", "gpt-5", "gpt-4.1", "o3", "o4", "gemini-2.5-pro", "gemini-3", "grok-4",
            "deepseek-v3", "deepseek-r1", "kimi-k2", "qwen3-max", "glm-5");

    /** The words that mark a family's smaller variants. */
    static final List<String> SMALL_MARKERS = List.of("mini", "nano", "flash", "lite", "small", "tiny", "distill", "haiku");

    /** Whether this drive serves a frontier-class model: the setting first, then the Claude API, then the name. */
    public static boolean frontier(String model, boolean claudeApi) {
        String set = Config.get("CODEZAIKU_DRIVE_CLASS");
        if (set != null && !set.isBlank()) {
            String s = set.strip().toLowerCase(Locale.ROOT);
            if (s.equals("frontier")) return true;
            if (s.equals("small") || s.equals("local")) return false;
        }
        return claudeApi || frontierName(model);
    }

    /** Whether a model name, as a gateway or a local server reports it, belongs to a frontier family and is not one of its small variants. */
    public static boolean frontierName(String model) {
        if (model == null || model.isBlank()) return false;
        String m = model.toLowerCase(Locale.ROOT).replace('_', '-');
        int slash = m.lastIndexOf('/');
        if (slash >= 0) m = m.substring(slash + 1);            // openai/gpt-5; /m/GLM-5.3-big.gguf
        if (m.endsWith(".gguf")) m = m.substring(0, m.length() - 5);
        for (String marker : SMALL_MARKERS) if (token(m, marker)) return false;
        for (String family : FRONTIER_FAMILIES) if (familyIn(m, family)) return true;
        return false;
    }

    /** The family name appears at the start of a name part: not inside another word, and not followed by a letter or digit (unless the family ends with a dash). */
    private static boolean familyIn(String m, String family) {
        int at = m.indexOf(family);
        while (at >= 0) {
            boolean startOk = at == 0 || !Character.isLetterOrDigit(m.charAt(at - 1));
            int end = at + family.length();
            boolean endOk = family.endsWith("-") || end >= m.length() || !Character.isLetterOrDigit(m.charAt(end));
            if (startOk && endOk) return true;
            at = m.indexOf(family, at + 1);
        }
        return false;
    }

    /** A whole word of the name, between dashes, dots or the ends. */
    private static boolean token(String m, String word) {
        int at = m.indexOf(word);
        while (at >= 0) {
            int end = at + word.length();
            boolean startOk = at == 0 || !Character.isLetterOrDigit(m.charAt(at - 1));
            boolean endOk = end >= m.length() || !Character.isLetterOrDigit(m.charAt(end));
            if (startOk && endOk) return true;
            at = m.indexOf(word, at + 1);
        }
        return false;
    }
}
