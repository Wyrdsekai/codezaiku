package org.codezaiku.library;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.codezaiku.Config;

/**
 * The provisioner's distiller — turns FETCHED authoritative material (real dep source / docs / algorithm
 * pack) into a pushable idiom. Pluggable so the acquirer code is GPU-free and testable: {@link None} is the
 * default (no endpoint → the acquirer dry-runs, logging what it WOULD acquire), {@link Remote} calls an
 * OpenAI-compatible coder server (a larger coder model on a multi-GPU box) once that's deployed.
 *
 * <p>Because the task is GROUNDED (the source is in the prompt) + the output is VALIDATED downstream, a
 * 27B coder suffices — this is faithful summarization, not frontier authoring (see SPEC_LIBRARY_PROVISIONING.md).
 */
public interface Distiller {

    boolean available();

    /** Distill the material into an idiom (markdown body), or null on failure. */
    String distill(Request req);

    /** concern = the ungrounded capability; spec = what the project's spec section asks for (so the idiom
     *  grounds the REQUESTED api, e.g. mbox not Maildir); stack = language; source = where material came from. */
    record Request(String concern, String spec, String stack, String source, String material) {}

    /** Pick a distiller from env: CODEZAIKU_DISTILLER_URL (+ _MODEL). Absent → None (dry-run). */
    static Distiller fromEnv() {
        String url = Config.get("CODEZAIKU_DISTILLER_URL");
        if (url == null || url.isBlank()) return new None();
        String model = Config.get("CODEZAIKU_DISTILLER_MODEL");
        return new Remote(url, model == null || model.isBlank() ? "default" : model);
    }

    /** No distiller configured: the acquirer runs in DRY-RUN (logs gaps, writes nothing). */
    final class None implements Distiller {
        public boolean available() { return false; }
        public String distill(Request req) { return null; }
    }

    /** Calls an OpenAI-compatible /v1/chat/completions endpoint (the deployed 27B coder). */
    final class Remote implements Distiller {
        private final String url;
        private final String model;
        Remote(String url, String model) { this.url = url.replaceAll("/+$", ""); this.model = model; }

        public boolean available() { return true; }

        public String distill(Request req) {
            try {
                String sys = "You distill AUTHORITATIVE source/doc material into ONE concise worked-example idiom "
                        + "for a coding assistant. Output MARKDOWN only: a short title, the correct API/algorithm "
                        + "as the material shows it (do NOT invent methods not present in the material), and a "
                        + "one-line strict-change test. Adapt names; no preamble.";
                String user = "Concern: " + req.concern() + "\nStack: " + req.stack() + "\nSource: " + req.source()
                        + "\n\nWHAT THE SPEC ASKS FOR (ground the idiom to THIS — use the exact API/class it names):\n"
                        + truncate(req.spec(), 2500)
                        + "\n\nAuthoritative source material (the real installed API — do not invent methods absent here):\n"
                        + truncate(req.material(), 12000)
                        + "\n\nWrite the idiom now (markdown only), using the API the spec asks for.";
                String body = "{\"model\":" + q(model) + ",\"temperature\":0.1,\"max_tokens\":1200,"
                        + "\"messages\":[{\"role\":\"system\",\"content\":" + q(sys) + "},"
                        + "{\"role\":\"user\",\"content\":" + q(user) + "}]}";
                HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                HttpRequest r = HttpRequest.newBuilder(URI.create(url + "/v1/chat/completions"))
                        .timeout(Duration.ofSeconds(120)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<String> resp = c.send(r, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) return null;
                String content = extractContent(resp.body());
                return (content == null || content.isBlank()) ? null : content;
            } catch (Exception e) {
                return null;
            }
        }

        // minimal, dependency-free extraction of choices[0].message.content
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
                    switch (n) { case 'n' -> sb.append('\n'); case 't' -> sb.append('\t');
                        case '"' -> sb.append('"'); case '\\' -> sb.append('\\'); default -> sb.append(n); }
                } else if (ch == '"') {
                    break;
                } else {
                    sb.append(ch);
                }
            }
            return sb.toString();
        }

        private static String truncate(String s, int n) { return s == null ? "" : (s.length() <= n ? s : s.substring(0, n)); }
        private static String q(String s) {
            StringBuilder b = new StringBuilder("\"");
            for (char ch : s.toCharArray()) switch (ch) {
                case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\"); case '\n' -> b.append("\\n");
                case '\t' -> b.append("\\t"); case '\r' -> { } default -> b.append(ch);
            }
            return b.append('"').toString();
        }
    }
}
