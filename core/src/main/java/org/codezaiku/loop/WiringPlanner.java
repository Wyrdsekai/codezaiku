package org.codezaiku.loop;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.codezaiku.Config;

/**
 * Per-spec WIRING SKELETON, generated ONCE at intake by the 30B (battery52 follow-up). The coder problem
 * we read from the produced code: a ~9B builds multi-stage apps by HARDCODING placeholder data (canned
 * endpoint responses) instead of wiring the real data flow — because the full pipeline is above its reach,
 * so faking is the only path it can complete (the capability-shortcut driver: Kevin arXiv:2507.11948;
 * NVIDIA SLM arXiv:2506.02153 "the constraint isn't capability but task structure").
 *
 * <p>A STATIC library worked-example doesn't solve this — there are infinite problem shapes, so hand-
 * authoring one per shape is overfitting at scale. The DYNAMIC fix is the strong-weak-collaboration result
 * (arXiv:2505.20182: strong plans the architecture, weak implements → strong-model quality at ~40% cost):
 * the 30B reads THIS spec and emits the end-to-end wiring SKELETON — the named stages, how data flows
 * between them, and the entry point that runs the REAL pipeline on the input and serves COMPUTED values —
 * for any shape. The 9B fills in each stage's real logic. The skeleton makes the real implementation
 * REACHABLE so hardcoding stops being the only completable path; it is NOT the full implementation (the 9B
 * still writes the real TF-IDF / threading / extraction — the 30B only lays out the wiring).
 *
 * <p>Pure GROUNDING (a pinned worked example, never a gate). Off by default: no CODEZAIKU_DISTILLER_URL →
 * unavailable → no sketch. Computed ONCE at construction (one 30B call/run); reuses the deployed 30B on :8201.
 */
public final class WiringPlanner {

    private final String url;     // OpenAI-compatible base (the 30B on :8201); null = unavailable
    private final String model;

    private WiringPlanner(String url, String model) {
        this.url = url;
        this.model = model;
    }

    public static WiringPlanner fromEnv() {
        String url = Config.get("CODEZAIKU_DISTILLER_URL");
        if (url == null || url.isBlank()) return new WiringPlanner(null, null);
        String m = Config.get("CODEZAIKU_WIRING_MODEL");
        if (m == null || m.isBlank()) m = Config.get("CODEZAIKU_DISTILLER_MODEL");
        return new WiringPlanner(url.replaceAll("/+$", ""), (m == null || m.isBlank()) ? "default" : m);
    }

    public boolean available() {
        return url != null;
    }

    /** Produce the end-to-end wiring skeleton for {@code goal} on {@code stack}, or null on any failure. */
    public String plan(String goal, String stack) {
        if (url == null || goal == null || goal.isBlank()) return null;
        try {
            String sys = "You are a senior software architect handing a SKELETON to a junior dev (a small "
                    + "model) who will implement it. The junior tends to HARDCODE canned/placeholder data into "
                    + "endpoints instead of wiring the real data flow — your skeleton's whole job is to make the "
                    + "REAL wiring obvious so they fill in logic, not fakes. Output a SHORT end-to-end WIRING "
                    + "SKELETON for this spec:\n"
                    + "1. STAGES: the named pipeline stages in order (e.g. parse the input → classify → extract → "
                    + "serve) and, for each, its function/class signature: what it TAKES and what it RETURNS "
                    + "(the data shape flowing between stages).\n"
                    + "2. ENTRY POINT: the one place that LOADS the real input (the bundled sample / the source "
                    + "the spec names), RUNS the pipeline on it, holds the results, and the routes/outputs that "
                    + "serve those COMPUTED results.\n"
                    + "3. THE RULE, stated once: every served value must be DERIVED from the input by calling the "
                    + "stages above — NEVER a hardcoded sample list. A test on real input must show non-empty, "
                    + "varied output.\n"
                    + "Write ONLY the wiring: signatures, the call chain, where data is loaded and served, in the "
                    + "spec's stack. Do NOT write the bodies / real algorithms — the junior writes those. Keep it "
                    + "under ~28 lines. No preamble.";
            String user = "Stack: " + truncate(stack, 60) + "\n\nSPEC:\n" + truncate(goal, 3000)
                    + "\n\nWrite the wiring skeleton now.";
            String body = "{\"model\":" + q(model) + ",\"temperature\":0.2,\"max_tokens\":900,"
                    + "\"messages\":[{\"role\":\"system\",\"content\":" + q(sys) + "},"
                    + "{\"role\":\"user\",\"content\":" + q(user) + "}]}";
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest r = HttpRequest.newBuilder(URI.create(url + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(120)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> resp = c.send(r, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return null;
            String content = extractContent(resp.body());
            return (content == null || content.isBlank()) ? null : content.strip();
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "…");
    }

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) switch (ch) {
            case '"' -> b.append("\\\"");
            case '\\' -> b.append("\\\\");
            case '\n' -> b.append("\\n");
            case '\t' -> b.append("\\t");
            case '\r' -> { }
            default -> b.append(ch);
        }
        return b.append('"').toString();
    }

    private static String extractContent(String json) {
        int i = json.indexOf("\"content\"");
        if (i < 0) return null;
        int colon = json.indexOf(':', i);
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int k = start + 1; k < json.length(); k++) {
            char ch = json.charAt(k);
            if (ch == '\\' && k + 1 < json.length()) {
                char n = json.charAt(++k);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(n);
                }
            } else if (ch == '"') {
                break;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
