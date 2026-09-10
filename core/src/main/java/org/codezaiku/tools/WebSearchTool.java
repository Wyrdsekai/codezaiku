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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    /** Session-wide count of degraded-backend events — the acquisitions gate diffs this around a
     *  run to judge the run's SUBSTRATE (a refused draft names infrastructure, not the model).
     *  Same pattern as DriveClient's SESSION_*_TOKENS. Shared across parallel fan workers on
     *  purpose: the gate judges the whole run's substrate, not one worker's. */
    public static final java.util.concurrent.atomic.AtomicInteger DEGRADED_EVENTS =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Which backend answered, session-wide; and searches that found no backend at all. */
    public static final java.util.concurrent.atomic.AtomicInteger BRAVE_USED = new java.util.concurrent.atomic.AtomicInteger(),
            SEARXNG_USED = new java.util.concurrent.atomic.AtomicInteger(), FALLBACK_USED = new java.util.concurrent.atomic.AtomicInteger(),
            UNREACHABLE = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile long searxDownAt;

    /** The built-in fallback (Wikipedia plus Crossref and OpenAlex; no key, no install) is on unless CODEZAIKU_FALLBACK_SEARCH=off. */
    public static boolean fallbackOn() {
        String v = Config.get("CODEZAIKU_FALLBACK_SEARCH");
        return v == null || !(v.equalsIgnoreCase("off") || v.equalsIgnoreCase("false") || v.equals("0"));
    }

    /**
     * The fallback when Brave is not configured and SearXNG does not answer: Wikipedia's search in the query's
     * language, then papers from Crossref and OpenAlex. Reference pages and the literature, not the whole web;
     * chosen over scraping a public engine, which a home box measured as challenged on the second query (2026-09-09).
     */
    String fallbackSearch(String query, int limit) {
        if (!fallbackOn()) return null;
        List<String[]> rows = new ArrayList<>();
        try {
            String wiki = "en";
            for (int i = 0; i < query.length(); i++) {
                Character.UnicodeScript sc = Character.UnicodeScript.of(query.codePointAt(i));
                if (sc == Character.UnicodeScript.HIRAGANA || sc == Character.UnicodeScript.KATAKANA) { wiki = "ja"; break; }
                if (sc == Character.UnicodeScript.HANGUL) { wiki = "ko"; break; }
                if (sc == Character.UnicodeScript.HAN) wiki = "zh";
            }
            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create("https://" + wiki + ".wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit="
                            + Math.min(limit, 20) + "&srsearch=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(20)).header("User-Agent", ScholarSearch.UA).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                for (JsonNode h : M.readTree(resp.body()).path("query").path("search")) {
                    String title = h.path("title").asText("");
                    if (title.isEmpty()) continue;
                    String url = "https://" + wiki + ".wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8).replace("+", "%20").replace("%2F", "/").replace("%3A", ":").replace("%28", "(").replace("%29", ")").replace("%2C", ",");
                    String snippet = h.path("snippet").asText("").replaceAll("<[^>]+>", "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replaceAll("\\s+", " ").strip();
                    rows.add(new String[]{title, url, snippet});
                }
            }
        } catch (Exception ignored) { }
        int half = Math.max(2, limit / 2);
        if (rows.size() > half) rows = new ArrayList<>(rows.subList(0, half));
        for (ScholarSearch.Row r : ScholarSearch.merged(query, limit)) rows.add(new String[]{r.title(), r.url(), r.snippet()});
        if (rows.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("results for \"" + query + "\" (built-in fallback: Wikipedia, then papers from Crossref and OpenAlex: no web engine is configured; follow the pages' references for primary sources):\n");
        int shown = 0;
        for (String[] r : rows) {
            if (shown >= limit) break;
            shown++;
            sb.append(shown).append(". ").append(r[0]).append('\n').append("   ").append(r[1]).append('\n');
            if (!r[2].isEmpty()) sb.append("   ").append(r[2]).append('\n');
        }
        FALLBACK_USED.incrementAndGet();
        return sb.toString();
    }

    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    // Repetition guard (see WebFetchTool): don't let a fixating model re-run the identical query.
    private final Set<String> queried = new HashSet<>();
    private final SearchSteer steer = new SearchSteer();

    /** The steerer, for the loop's early-finish hook and the run summary. */
    public SearchSteer steer() { return steer; }

    /** The question, so the steerer can name the language axis. */
    public WebSearchTool focus(String question) {
        steer.focus(question);
        return this;
    }

    /** Append the steerer's note (if any) to a formatted result; hosts parsed from its url lines. */
    private String steered(String query, String result) {
        var urls = new java.util.ArrayList<String>();
        for (String line : result.split("\n")) if (line.startsWith("   http")) urls.add(line.strip());
        return result + steer.observe(query, urls);
    }
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

    /**
     * Brave Search API, first choice when a key is configured. Measured reason (2026-08-29,
     * first probe of each backend, same query): SearXNG's surviving free engine put spam at
     * ranks 1-2 with brave/ddg/startpage rate-limited or CAPTCHA'd; the Brave API returned the
     * paper, the official site and the dataset as its top three. Falls back to SearXNG on ANY
     * failure — a search tool that dies with its billing dies at the worst moment.
     * Returns null when Brave is unconfigured or unusable, and the caller falls through.
     */
    private String braveSearch(String query, int limit) {
        String key = Config.get("CODEZAIKU_BRAVE_KEY");
        if (key == null || key.isBlank()) return null;
        try {
            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create(
                            "https://api.search.brave.com/res/v1/web/search?count="
                            + Math.min(limit, 20) + "&q="
                            + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("X-Subscription-Token", key.strip())
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;   // 401/429/5xx → SearXNG carries on
            JsonNode rs = M.readTree(resp.body()).path("web").path("results");
            if (!rs.isArray() || rs.isEmpty()) return null;
            StringBuilder sb = new StringBuilder("results for \"" + query + "\":\n");
            for (int i = 0; i < rs.size() && i < limit; i++) {
                JsonNode r = rs.get(i);
                String desc = r.path("description").asText("").replaceAll("<[^>]+>", "")
                        .replaceAll("\\s+", " ").strip();
                if (desc.length() > 240) desc = desc.substring(0, 240) + "…";
                sb.append(i + 1).append(". ").append(r.path("title").asText("")).append('\n')
                  .append("   ").append(r.path("url").asText("")).append('\n');
                if (!desc.isEmpty()) sb.append("   ").append(desc).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
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
        String brave = braveSearch(query, limit);
        if (brave != null) {
            BRAVE_USED.incrementAndGet();
            if (!sweepNoted && looksBatched(query)) {
                sweepNoted = true;
                brave += "\nNOTE: this query names several distinct items at once: engines require ALL "
                        + "terms, so batched queries surface homepages, not data. Search for ONE page "
                        + "listing all the items (\"list of …\" / \"comparison of …\"), or query ONE "
                        + "item at a time.";
            }
            return steered(query, brave);
        }
        if (System.currentTimeMillis() - searxDownAt < 60_000) {   // SearXNG failed a minute ago: the fallback first, not 30 s of waiting
            String fb = fallbackSearch(query, limit);
            if (fb != null) return steered(query, fb);
        }
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
                searxDownAt = System.currentTimeMillis();
                String fb = fallbackSearch(query, limit);
                if (fb != null) return steered(query, fb);
                queried.remove(query.strip().toLowerCase());   // a failed search must stay retryable
                UNREACHABLE.incrementAndGet();
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
            String fb = fallbackSearch(query, limit);
            if (fb != null) return steered(query, fb);
            // A failed search must not burn its slot in the repetition guard — the query was never answered.
            queried.remove(query.strip().toLowerCase());
            String down = degradedEngines(body);
            if (!down.isEmpty()) {
                DEGRADED_EVENTS.incrementAndGet();
                return "SEARCH BACKEND DEGRADED: no results came back because the upstream engines are "
                        + "currently rate-limited or blocked (" + down + "). This is a TRANSIENT infrastructure "
                        + "problem, not evidence that the information does not exist: do NOT conclude the answer "
                        + "is unavailable and do NOT answer from memory. Try a different phrasing, or fetch a "
                        + "likely source URL directly with web_fetch (e.g. the relevant Wikipedia page).";
            }
            return "no results for: " + query;
        }
        SEARXNG_USED.incrementAndGet();
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
            sb.append("\nNOTE: this query names several distinct items at once: engines require ALL terms, "
                    + "so batched queries surface homepages, not data. Search for ONE page listing all the "
                    + "items (\"list of …\" / \"comparison of …\"), or query ONE item at a time.");
        }
        return steered(query, sb.toString());
    }
}
