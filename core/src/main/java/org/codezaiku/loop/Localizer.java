package org.codezaiku.loop;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.codezaiku.Config;

/**
 * Stall-localizer (battery49 follow-up). When the 9B drive STALLS on a recurring build/test failure it
 * cannot trace — the no-progress-failure detector fires (same failure N× despite edits) — escalate the ONE
 * step weak models reliably can't do: LOCALIZE the root cause. The research is unanimous (DeepMind: "LLMs
 * cannot find reasoning errors, but can correct them given the error location"; Olausson: "the weaker the
 * model, the more it depends on externally-supplied localization"; SWE-Protégé: weak model drives, a strong
 * model rescues the hard step SPARSELY, +25% at ~11% of tokens). So a stronger coder (the 30B distiller
 * already deployed on :8201, reused via CODEZAIKU_DISTILLER_URL) reads the REAL failing output + the
 * recently-edited source and returns a localized ROOT CAUSE — which file/function, the producer→consumer
 * contract mismatch (the {categories} returned vs .centroids read class), and the minimal fix. FamiliarLoop
 * surfaces that to the 9B, which applies the mechanical edit.
 *
 * <p>This composes the two levers: (1) escalate-on-stall to the 30B, (2) localize via the real execution
 * state (LDB/MGDebugger: surface WHERE the data flow goes empty, localize to one stage). Pure GROUNDING —
 * the 30B never edits, only diagnoses; never a gate. Off by default: no CODEZAIKU_DISTILLER_URL → unavailable
 * → the loop keeps its generic reframe. Bounded: called at most once per distinct failure-signature.
 */
public final class Localizer {

    private final String url;     // OpenAI-compatible base (the 30B on :8201); null = unavailable
    private final String model;

    private Localizer(String url, String model) {
        this.url = url;
        this.model = model;
    }

    public static Localizer fromEnv() {
        String url = Config.get("CODEZAIKU_DISTILLER_URL");
        if (url == null || url.isBlank()) return new Localizer(null, null);
        String m = Config.get("CODEZAIKU_LOCALIZER_MODEL");
        if (m == null || m.isBlank()) m = Config.get("CODEZAIKU_DISTILLER_MODEL");
        return new Localizer(url.replaceAll("/+$", ""), (m == null || m.isBlank()) ? "default" : m);
    }

    public boolean available() {
        return url != null;
    }

    /** One recently-edited source file the localizer should read. */
    public record FileCtx(String path, String content) {}

    /**
     * Localize the root cause of a recurring failure to ONE place, for the 9B to fix. Returns a short
     * diagnosis (ROOT CAUSE / MISMATCH / WHY EMPTY / FIX), or null on any failure (caller falls back).
     */
    public String localize(String goal, String failingCommand, String failingOutput, List<FileCtx> files) {
        if (url == null) return null;
        try {
            StringBuilder src = new StringBuilder();
            for (FileCtx f : files) {
                src.append("\n--- ").append(f.path()).append(" ---\n").append(cap(f.content(), 3500)).append('\n');
                if (src.length() > 16000) break;
            }
            String sys = "You are a senior debugger helping a junior dev whose multi-stage program BUILDS and "
                    + "RUNS but a test fails or it produces empty / degenerate output (e.g. 0 categories, 0 "
                    + "threads, an empty list). Your ONLY job is to LOCALIZE the root cause to ONE specific "
                    + "place and state the minimal fix — do NOT rewrite the program, do NOT propose a redesign. "
                    + "Look HARDEST for a CROSS-STAGE CONTRACT MISMATCH between the dev's own modules: a "
                    + "function returns one shape or field name and the caller reads a different one (e.g. it "
                    + "returns {categories} but the caller reads obj.centroids, so the result is always empty); "
                    + "an ingest/parse stage that yields 0 records so every downstream stage is empty; a stage "
                    + "wired to the wrong variable. Answer in EXACTLY these 4 short lines, each naming concrete "
                    + "code:\nROOT CAUSE: <one sentence naming the exact file + function (and line if you can)>\n"
                    + "MISMATCH: <producer returns X, consumer reads Y — or 'none'>\n"
                    + "WHY EMPTY: <the data-flow reason the output is degenerate>\n"
                    + "FIX: <the single concrete change to make, and exactly where>";
            String user = "GOAL (what the program must do):\n" + cap(goal, 2000)
                    + "\n\nThe command that keeps FAILING:\n" + cap(failingCommand, 500)
                    + "\n\nIts output (the real failure / degenerate result):\n" + cap(failingOutput, 4000)
                    + "\n\nThe dev's recently-edited source files:\n" + src
                    + "\n\nLocalize the root cause now (the 4 lines, be specific, name the file and function).";
            String body = "{\"model\":" + q(model) + ",\"temperature\":0.1,\"max_tokens\":600,"
                    + "\"messages\":[{\"role\":\"system\",\"content\":" + q(sys) + "},"
                    + "{\"role\":\"user\",\"content\":" + q(user) + "}]}";
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest r = HttpRequest.newBuilder(URI.create(url + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(90)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> resp = c.send(r, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return null;
            String content = extractContent(resp.body());
            return (content == null || content.isBlank()) ? null : content.strip();
        } catch (Exception e) {
            return null;
        }
    }

    private static String cap(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…[truncated]";
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

    /** Minimal, dependency-free extraction of choices[0].message.content. */
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
