package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.codezaiku.Config;

/**
 * Web search via a self-hosted SearXNG meta-search instance (keyless, aggregates many engines — fits
 * CodeZaiku's self-hosted ethos: local model, local embeddings, local search). Returns a compact ranked list
 * of {title, url, snippet} for the research loop to triage before fetching.
 *
 * <p>Endpoint from {@code CODEZAIKU_SEARXNG} (default {@code http://localhost:8888}); the instance must have
 * {@code json} in its {@code search.formats}.
 */
public final class WebSearchTool implements Tool {
    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    // Repetition guard (see WebFetchTool): don't let a fixating model re-run the identical query.
    private final Set<String> queried = new HashSet<>();
    // One strategy note per run (sparse — over-injection dilutes a weak model's attention).
    private boolean sweepNoted = false;

    /** ≥4 distinct capitalized terms ≈ several entities crammed into one query — engines AND terms
     *  together, so these return homepages or nothing (measured: 6 consecutive useless mega-queries). */
    private static boolean looksBatched(String query) {
        Set<String> caps = new HashSet<>();
        for (String w : query.split("[^A-Za-z]+"))
            if (w.length() > 2 && Character.isUpperCase(w.charAt(0))) caps.add(w);
        return caps.size() >= 4;
    }

    public static String endpoint() {
        String e = Config.get("CODEZAIKU_SEARXNG");
        return (e == null || e.isBlank()) ? "http://localhost:8888" : e.replaceAll("/+$", "");
    }

    @Override public String name() { return "web_search"; }

    @Override public String description() {
        return "Search the web (returns ranked title/url/snippet results). Use to FIND sources; then use "
                + "web_fetch to read the promising ones.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("query").put("type", "string");
        props.putObject("limit").put("type", "integer");
        p.putArray("required").add("query");
        return p;
    }

    /** "brave: too many requests, duckduckgo: timeout" — SearXNG reports which upstreams are down. */
    private static String degradedEngines(JsonNode body) {
        JsonNode ue = body.path("unresponsive_engines");
        if (!ue.isArray() || ue.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode e : ue) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.path(0).asText("?"));
            String why = e.path(1).asText("");
            if (!why.isBlank()) sb.append(": ").append(why);
        }
        return sb.toString();
    }

    @Override public String execute(JsonNode args) throws Exception {
        String query = args.path("query").asText("");
        if (query.isBlank()) return "ERROR: empty query";
        int limit = Math.min(Math.max(args.path("limit").asInt(8), 1), 20);
        if (!queried.add(query.strip().toLowerCase()))
            return "ALREADY SEARCHED: you already ran this exact query; its results are above in your history. "
                    + "Use a DIFFERENT query, web_fetch one of the results you have not read yet, or write your "
                    + "answer and call task_done.";
        String url = endpoint() + "/search?format=json&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        JsonNode body = null;
        // The free upstream engines rate-limit under sustained load, and SearXNG then SUSPENDS them —
        // every engine down comes back as an empty result list, which reads exactly like "the web does not
        // know this". Retry once through the backoff before believing an empty answer.
        for (int attempt = 1; attempt <= 2 && body == null; attempt++) {
            HttpResponse<String> resp;
            try {
                resp = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30)).header("Accept", "application/json").GET().build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                queried.remove(query.strip().toLowerCase());   // a failed search must stay retryable
                return "ERROR: search backend unreachable at " + endpoint() + " (" + e + "). Is the SearXNG "
                        + "container running (docker start codezaiku-searxng)?";
            }
            if (resp.statusCode() != 200) {
                queried.remove(query.strip().toLowerCase());
                return "ERROR: search returned HTTP " + resp.statusCode();
            }
            JsonNode parsed = M.readTree(resp.body());
            boolean empty = !parsed.path("results").isArray() || parsed.path("results").isEmpty();
            if (empty && attempt == 1 && parsed.path("unresponsive_engines").size() > 0) {
                try { Thread.sleep(4000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                continue;   // engines were suspended a moment ago; give the cooldown a chance
            }
            body = parsed;
        }
        JsonNode results = body.path("results");
        if (!results.isArray() || results.isEmpty()) {
            // A failed search must not burn its slot in the repetition guard — the query was never answered.
            queried.remove(query.strip().toLowerCase());
            String down = degradedEngines(body);
            if (!down.isEmpty())
                return "SEARCH BACKEND DEGRADED — no results came back because the upstream engines are "
                        + "currently rate-limited or blocked (" + down + "). This is a TRANSIENT infrastructure "
                        + "problem, not evidence that the information does not exist: do NOT conclude the answer "
                        + "is unavailable and do NOT answer from memory. Try a different phrasing, or fetch a "
                        + "likely source URL directly with web_fetch (e.g. the relevant Wikipedia page).";
            return "no results for: " + query;
        }
        StringBuilder sb = new StringBuilder("results for \"" + query + "\":\n");
        for (int i = 0; i < results.size() && i < limit; i++) {
            JsonNode r = results.get(i);
            String content = r.path("content").asText("").replaceAll("\\s+", " ").strip();
            if (content.length() > 240) content = content.substring(0, 240) + "…";
            sb.append(i + 1).append(". ").append(r.path("title").asText("")).append('\n')
              .append("   ").append(r.path("url").asText("")).append('\n');
            if (!content.isEmpty()) sb.append("   ").append(content).append('\n');
        }
        if (!sweepNoted && looksBatched(query)) {
            sweepNoted = true;
            sb.append("\nNOTE: this query names several distinct items at once — engines require ALL terms, "
                    + "so batched queries surface homepages, not data. Search for ONE page listing all the "
                    + "items (\"list of …\" / \"comparison of …\"), or query ONE item at a time.");
        }
        return sb.toString();
    }
}
