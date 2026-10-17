package org.codezaiku.drive;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The measured tier of a model family, for best judgement when the person has not set one. From the batteries of 2026-10-16 (six
 * maintenance tasks at K=3, eight harder ones with real ML judgement at K=5; bench/CODING_MODELS_2026-10.md): A passed at least 85 %
 * of the hard set and all but one maintenance run (Sol 38/40, Kimi 37, GLM-5.3 34, Sonnet 34); B about three quarters of the hard
 * set, the ML-judgement tasks mostly missed, maintenance nearly all (DeepSeek V4.1 Flash 30/40, the local Qwen3.8 27B), or
 * maintenance only measured; C is unmeasured or weak. A family name is matched at the start of a name part, as {@link ModelClass}
 * does. A tier set on the saved model wins over this table.
 */
public final class ModelTiers {

    private ModelTiers() { }

    static final Map<String, String> FAMILIES = new LinkedHashMap<>();
    static {
        for (String a : new String[]{"claude-sonnet", "claude-opus", "claude-fable", "kimi-k3", "gpt-6.1-sol", "gpt-6-sol", "glm-5.3", "deepseek-v4-pro"}) FAMILIES.put(a, "A");
        for (String b : new String[]{"deepseek-v4.1-flash", "qwen3.8-27b", "qwen3-coder", "minimax-m3", "nemotron-3-ultra", "hy4", "mimo-v2.6", "gpt-6-luna", "deepseek-v4-flash", "glm-5.3-flash", "claude-haiku", "gemini-3"}) FAMILIES.put(b, "B");
    }

    /** The tier for a model id as a server or gateway reports it; C when the family is not known. */
    public static String of(String model) {
        if (model == null) return "C";
        String m = model.toLowerCase(Locale.ROOT).replace('_', '-');
        int slash = m.lastIndexOf('/');
        if (slash >= 0) m = m.substring(slash + 1);
        if (m.endsWith(".gguf")) m = m.substring(0, m.length() - 5);
        // the longest family name first: glm-5.3-flash is its own line, not glm-5.3
        for (Map.Entry<String, String> e : FAMILIES.entrySet().stream().sorted((a, b) -> b.getKey().length() - a.getKey().length()).toList()) {
            int at = m.indexOf(e.getKey());
            if (at < 0) continue;
            boolean startOk = at == 0 || !Character.isLetterOrDigit(m.charAt(at - 1));
            int end = at + e.getKey().length();
            boolean endOk = end >= m.length() || !Character.isLetterOrDigit(m.charAt(end)) || m.charAt(end) == '.';
            if (startOk && endOk) return e.getValue();
        }
        return "C";
    }
}
