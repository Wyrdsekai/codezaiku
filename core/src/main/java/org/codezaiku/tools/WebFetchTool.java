package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Fetch a URL and return its readable text — the "read the source" half of research (web_search finds,
 * web_fetch reads). HTML is reduced to text (script/style stripped, tags removed, entities decoded) and
 * truncated, so a small model's context isn't blown by one page.
 */
public final class WebFetchTool implements Tool {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    // Page excerpts must stay SMALL: a 9B has a ~16K-token window shared with history. 12K-char pages filled
    // the context after a few fetches (out_budget collapsed 16384 -> 1909) leaving no room to WRITE the answer —
    // the loop could only keep making small tool calls and never concluded. Keep excerpts tight.
    private static final int MAX_CHARS = 2_500;
    // REPETITION GUARD (same lever as the ops RemediationLoop): a small model fixates — it re-fetches the same
    // URL instead of synthesizing. Serve each URL once; on a repeat, refuse and push it to move on/conclude.
    private final Set<String> fetched = new HashSet<>();

    @Override public String name() { return "web_fetch"; }

    @Override public String description() {
        return "Fetch a URL and return its readable text content. Use after web_search to actually READ a "
                + "promising source. Long pages are EXCERPTED, not truncated: pass `find` with the specific "
                + "thing you are looking for (e.g. 'sculpture 2009 height') and you get the passages that "
                + "mention it, from anywhere in the page — not just the opening.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("url").put("type", "string");
        props.putObject("find").put("type", "string");
        p.putArray("required").add("url");
        return p;
    }

    /**
     * The question this run is answering, used to CENTER the excerpt when the model doesn't say what it's
     * looking for. Without it a fetch returns the first {@value #MAX_CHARS} characters — for a Wikipedia
     * article, the lead section — so a fact in the body is never seen no matter how many times it is fetched
     * (measured: a run fetched the right page 11 times and still reported it could not find the answer).
     */
    private String focus = "";

    public WebFetchTool focus(String question) {
        this.focus = question == null ? "" : question;
        return this;
    }

    @Override public String execute(JsonNode args) throws Exception {
        String url = args.path("url").asText("");
        if (url.isBlank()) return "ERROR: empty url";
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        // The guard keys on url+find: re-reading the SAME page for a DIFFERENT thing is legitimate (the
        // excerpt shown is question-relevant, so a new question genuinely shows new text) — what we refuse
        // is the identical fetch, which is fixation.
        String findArg = args.path("find").asText("").strip().toLowerCase();
        if (!fetched.add(url + "|" + findArg))
            return "ALREADY FETCHED: you have already read " + url
                    + (findArg.isBlank() ? "" : " looking for \"" + findArg + "\"")
                    + " in this session — its content is above in your history. Do NOT repeat this fetch. "
                    + "Fetch a DIFFERENT source, or re-read this one with a different `find` if you need "
                    + "another part of it, or write the answer now and call task_done.";
        HttpResponse<byte[]> resp;
        try {
            resp = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(30))
                            .header("User-Agent", "Mozilla/5.0 (compatible; CodeZaiku-research/0.1)")
                            .header("Accept-Encoding", "gzip")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            return "ERROR: could not fetch " + url + " (" + e + ")";
        }
        if (resp.statusCode() >= 400) {
            // WIKIPEDIA 404 → real titles. A model GUESSES plausible article titles and a near-miss 404s
            // (measured: "List of state constitutions of the United States" — the page exists as
            // "List of U.S. state constitutions"; three fetch attempts, task lost). Wikipedia's own
            // title-search API resolves the guess; enrich the error with the top real titles.
            String didYouMean = wikiTitleSuggestions(url);
            return "ERROR: HTTP " + resp.statusCode() + " for " + url + didYouMean;
        }
        String text = readable(decode(resp));
        if (text.length() <= MAX_CHARS) return "source: " + url + "\n\n" + text;
        String terms = args.path("find").asText("");
        if (terms.isBlank()) terms = focus;
        return "source: " + url + "\n\n" + excerpt(text, terms);
    }

    /** On a wikipedia.org/wiki/<title> 404, ask Wikipedia's title-search API for the real titles and
     *  return a "did you mean" block (empty for non-wiki URLs or when the lookup itself fails). */
    private static String wikiTitleSuggestions(String url) {
        Matcher m = Pattern
                .compile("https?://([a-z]{2,3})\\.(?:m\\.)?wikipedia\\.org/wiki/([^?#]+)").matcher(url);
        if (!m.matches()) return "";
        String lang = m.group(1), title = URLDecoder.decode(m.group(2), StandardCharsets.UTF_8);
        try {
            String api = "https://" + lang + ".wikipedia.org/w/rest.php/v1/search/page?limit=5&q="
                    + URLEncoder.encode(title.replace('_', ' '), StandardCharsets.UTF_8);
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(api))
                            .timeout(Duration.ofSeconds(15))
                            .header("User-Agent", "Mozilla/5.0 (compatible; CodeZaiku-research/0.1)")
                            .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return "";
            var pages = new ObjectMapper().readTree(r.body()).path("pages");
            if (!pages.isArray() || pages.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("\nThat exact article title does not exist. Real articles matching it:\n");
            for (JsonNode p : pages) {
                sb.append("- ").append(p.path("title").asText("")).append(" → https://").append(lang)
                  .append(".wikipedia.org/wiki/").append(p.path("key").asText("")).append('\n');
            }
            return sb.append("Fetch one of THESE URLs instead of guessing further titles.").toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Decompress the body when the server compressed it. java.net.http does NOT auto-decompress, and some
     * CDNs return {@code Content-Encoding: gzip} even to a client that never offered Accept-Encoding
     * (measured: Yahoo Finance) — the model then received literal gzip bytes and reported the source as
     * "garbled", scoring 0 on a task whose data was right there. We now offer gzip explicitly (fewer
     * surprises than pretending we can't) and inflate it ourselves.
     */
    private static String decode(HttpResponse<byte[]> resp) {
        byte[] body = resp.body();
        String enc = resp.headers().firstValue("Content-Encoding").orElse("").toLowerCase();
        try {
            if (enc.contains("gzip") || (body.length > 2 && body[0] == (byte) 0x1f && body[1] == (byte) 0x8b)) {
                try (var in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                    body = in.readAllBytes();
                }
            } else if (enc.contains("deflate")) {
                try (var in = new InflaterInputStream(new ByteArrayInputStream(body))) {
                    body = in.readAllBytes();
                }
            }
        } catch (Exception e) {
            // fall through with the raw bytes — worse than decoded, better than an exception
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static final int LEAD_CHARS = 600;   // always keep the opening — it says what the page IS
    private static final Set<String> STOP = Set.of(
            "the", "a", "an", "of", "in", "on", "at", "to", "for", "and", "or", "is", "was", "were", "are",
            "what", "which", "who", "whom", "whose", "when", "where", "why", "how", "did", "does", "do",
            "that", "this", "it", "its", "his", "her", "their", "he", "she", "they", "by", "with", "from",
            "as", "be", "been", "has", "have", "had", "name", "named", "called", "many", "much", "first");

    /**
     * A query-RELEVANT window instead of the first N characters. Paragraphs are scored by how many of the
     * question's distinctive terms they contain; the best-scoring ones are returned in document order,
     * under the same character budget. The lead is always kept so the model can tell what the page is.
     * With no terms to go on this degrades to the old head-of-page behaviour.
     */
    static String excerpt(String text, String terms) {
        Set<String> want = new LinkedHashSet<>();
        for (String w : terms.toLowerCase().split("[^a-z0-9]+"))
            if (w.length() > 2 && !STOP.contains(w)) want.add(w);
        String lead = text.substring(0, Math.min(LEAD_CHARS, text.length()));
        if (want.isEmpty()) return text.substring(0, MAX_CHARS) + "\n\n…[truncated]";

        String[] paras = text.substring(lead.length()).split("\n+");
        record Scored(int idx, int hits, String body) { }
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < paras.length; i++) {
            String p = paras[i].strip();
            if (p.length() < 40) continue;
            String low = p.toLowerCase();
            int hits = 0;
            for (String w : want) if (low.contains(w)) hits++;
            // Data rows outrank prose ABOUT the data: a page's table usually IS what a data-seeking fetch
            // came for, but its rows echo few of the question's words — prose paragraphs out-scored the
            // rows and ate the whole budget (measured: the state-constitutions table survived readable()
            // and still never reached the model).
            if (hits > 0 && p.contains(" | ")) hits += 2;
            if (hits > 0) scored.add(new Scored(i, hits, p));
        }
        if (scored.isEmpty()) return text.substring(0, MAX_CHARS) + "\n\n…[truncated]";
        scored.sort((x, y) -> y.hits() - x.hits());   // best matches first, then restore document order

        List<Scored> keep = new ArrayList<>();
        Set<Integer> kept = new HashSet<>();
        int budget = MAX_CHARS - lead.length();
        for (Scored s : scored) {
            if (budget - s.body().length() < 0) continue;
            keep.add(s);
            kept.add(s.idx());
            budget -= s.body().length() + 2;
            // TABLE PULL-THROUGH: a matched line with ` | ` cells is a table header/row — the rows AFTER
            // it hold the data but rarely contain the question's words (a row says "Alabama | 1901 | 402",
            // not "constitution effective date"). Extend through the contiguous table block.
            if (s.body().contains(" | ")) {
                for (int i = s.idx() + 1; i < paras.length && budget > 80; i++) {
                    String p = paras[i].strip();
                    if (!p.contains(" | ")) break;
                    if (p.length() > budget || !kept.add(i)) break;
                    keep.add(new Scored(i, 0, p));
                    budget -= p.length() + 1;
                }
            }
            if (budget <= 80) break;
        }
        keep.sort((x, y) -> x.idx() - y.idx());
        StringBuilder sb = new StringBuilder(lead);
        int prev = -1;
        for (Scored s : keep) {
            sb.append(s.idx() == prev + 1 ? "\n" : "\n\n…\n\n").append(s.body());
            prev = s.idx();
        }
        sb.append("\n\n…[excerpted: the passages of this page matching your question, not the whole page. "
                + "Re-fetch with a different `find` to look for something else.]");
        return sb.toString();
    }

    /** Crude but effective HTML → text: drop script/style/head-noise, strip tags, decode common entities. */
    static String readable(String body) {
        if (body == null) return "";
        String s = body;
        if (s.contains("<")) {
            s = s.replaceAll("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>", " ");
            s = s.replaceAll("(?is)<!--.*?-->", " ");
            // Keep table STRUCTURE: cells become ` | `-separated, rows become lines. Without this a wiki
            // table collapses into undifferentiated prose — the row for "Alabama" no longer looks like a
            // data row, the relevance excerpt can't match it, and the model reports a page that holds the
            // entire gold table as "does not contain the data" (measured: ws_en_064 fetched the right
            // list page three times and never saw a row).
            s = s.replaceAll("(?i)</t[dh]>", " | ");
            s = s.replaceAll("(?i)<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>", "\n");
            s = s.replaceAll("(?s)<[^>]+>", " ");
        }
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n");
        return s.strip();
    }
}
