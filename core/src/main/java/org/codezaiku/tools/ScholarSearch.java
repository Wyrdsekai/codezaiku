package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The scholarly literature, with no key and no install: Crossref (the DOI registry) and OpenAlex (a scholarly index).
 * Measured 2026-09-09 from a home box on the same queries as the web engines: both answered in under a second with
 * the primary papers (the 1978 "Ban on Lead-Containing Paint" paper, the Lancet's MMR retraction notices,
 * melatonin randomized trials) that a web engine ranks low or not at all. Every Crossref hit is a DOI, which the
 * research loop can fetch and cite. This backs {@code scholar_search}, offered in every research run, and joins
 * Wikipedia in {@code web_search}'s built-in fallback.
 */
public final class ScholarSearch {

    private ScholarSearch() { }

    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    public static final java.util.concurrent.atomic.AtomicInteger SCHOLAR_USED = new java.util.concurrent.atomic.AtomicInteger();
    static final String UA = "CodeZaiku/" + org.codezaiku.FamiliarMain.VERSION + " (a research library; https://codezaiku.org; mailto:support@codezaiku.org)";

    /** One work: the title, where to read it (a DOI URL when there is one), and a line of context. */
    public record Row(String title, String url, String snippet, String doi) { }

    /** Both sources, merged, one row per work (by DOI, else by URL); Crossref first. Never throws; empty when nothing answers. */
    public static List<Row> merged(String query, int limit) {
        Map<String, Row> byKey = new LinkedHashMap<>();
        for (Row r : crossref(query, limit)) byKey.putIfAbsent(key(r), r);
        for (Row r : openalex(query, limit)) byKey.putIfAbsent(key(r), r);
        List<Row> out = new ArrayList<>(byKey.values());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    static String key(Row r) {
        if (r.doi() != null && !r.doi().isBlank()) return "doi:" + r.doi().toLowerCase(Locale.ROOT);
        return "url:" + r.url().replaceFirst("^https?://(www\\.)?", "").replaceAll("/+$", "").toLowerCase(Locale.ROOT);
    }

    public static List<Row> crossref(String query, int limit) {
        try {
            String body = get("https://api.crossref.org/works?rows=" + Math.min(limit, 20) + "&select=DOI,URL,title,container-title,issued,author,type&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
            return body == null ? List.of() : parseCrossref(body);
        } catch (Exception e) { return List.of(); }
    }

    public static List<Row> openalex(String query, int limit) {
        try {
            String body = get("https://api.openalex.org/works?per-page=" + Math.min(limit, 20) + "&mailto=support@codezaiku.org&search=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
            return body == null ? List.of() : parseOpenAlex(body);
        } catch (Exception e) { return List.of(); }
    }

    private static String get(String url) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 200 ? r.body() : null;
    }

    /** Crossref's {@code message.items[]}: title[], URL, DOI, container-title[], issued.date-parts, author[]. */
    static List<Row> parseCrossref(String json) {
        List<Row> out = new ArrayList<>();
        try {
            for (JsonNode w : M.readTree(json).path("message").path("items")) {
                String title = w.path("title").path(0).asText("").replaceAll("\\s+", " ").strip();
                String doi = w.path("DOI").asText("");
                String url = w.path("URL").asText(doi.isEmpty() ? "" : "https://doi.org/" + doi);
                if (title.isEmpty() || url.isEmpty()) continue;
                StringBuilder s = new StringBuilder();
                String venue = w.path("container-title").path(0).asText("");
                if (!venue.isEmpty()) s.append(venue);
                JsonNode y = w.path("issued").path("date-parts").path(0).path(0);
                if (y.isNumber()) s.append(s.length() > 0 ? ", " : "").append(y.asInt());
                List<String> authors = new ArrayList<>();
                for (JsonNode a : w.path("author")) { if (authors.size() >= 3) break; String f = a.path("family").asText(""); if (!f.isEmpty()) authors.add(f); }
                if (!authors.isEmpty()) s.append(s.length() > 0 ? ": " : "").append(String.join(", ", authors)).append(w.path("author").size() > 3 ? " et al." : "");
                String type = w.path("type").asText("");
                if (!type.isEmpty()) s.append(s.length() > 0 ? " (" : "(").append(type.replace('-', ' ')).append(')');
                out.add(new Row(title, url, s.toString(), doi));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** OpenAlex's {@code results[]}: display_name, doi (a URL), primary_location.landing_page_url/source.display_name, publication_year, authorships[]. */
    static List<Row> parseOpenAlex(String json) {
        List<Row> out = new ArrayList<>();
        try {
            for (JsonNode w : M.readTree(json).path("results")) {
                String title = w.path("display_name").asText("").replaceAll("\\s+", " ").strip();
                String doiUrl = w.path("doi").asText("");
                String doi = doiUrl.replaceFirst("^https?://doi\\.org/", "");
                String url = w.path("primary_location").path("landing_page_url").asText("");
                if (url.isEmpty()) url = doiUrl;
                if (title.isEmpty() || url.isEmpty()) continue;
                StringBuilder s = new StringBuilder();
                String venue = w.path("primary_location").path("source").path("display_name").asText("");
                if (!venue.isEmpty()) s.append(venue);
                if (w.path("publication_year").isNumber()) s.append(s.length() > 0 ? ", " : "").append(w.path("publication_year").asInt());
                List<String> authors = new ArrayList<>();
                for (JsonNode a : w.path("authorships")) { if (authors.size() >= 3) break; String n = a.path("author").path("display_name").asText(""); if (!n.isEmpty()) authors.add(n); }
                if (!authors.isEmpty()) s.append(s.length() > 0 ? ": " : "").append(String.join(", ", authors)).append(w.path("authorships").size() > 3 ? " et al." : "");
                out.add(new Row(title, url, s.toString(), doi));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** The rows as the model sees them: numbered, title, where to read, one line of context. */
    public static String render(String query, String heading, List<Row> rows, int limit) {
        StringBuilder sb = new StringBuilder("results for \"" + query + "\"" + heading + ":\n");
        int shown = 0;
        for (Row r : rows) {
            if (shown >= limit) break;
            shown++;
            sb.append(shown).append(". ").append(r.title()).append('\n').append("   ").append(r.url()).append('\n');
            if (!r.snippet().isEmpty()) sb.append("   ").append(r.snippet()).append('\n');
        }
        return sb.toString();
    }
}
