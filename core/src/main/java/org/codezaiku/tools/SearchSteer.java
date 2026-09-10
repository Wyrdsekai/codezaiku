package org.codezaiku.tools;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The STEERER — the search controller's first half, built against what the librarian's own
 * survey found (I-0003, 2026-09-01): 77–94% of search episodes add no new evidence and
 * "redundant re-querying is the cleanest behavioral predictor of failure" (arXiv 2608.01913).
 * The lever is not stopping earlier; it is never spending a query on ground already covered.
 *
 * <p>Entirely mechanical, computed from the run's own queries and results — no model judgment:
 * <ul>
 *   <li><b>near-repeat</b>: the new query's terms overlap a prior query's ≥ {@value #REPEAT_JACCARD}
 *       (Jaccard), or its results are ≥ {@value #SEEN_HOST_SHARE} pages from hosts already seen;</li>
 *   <li><b>saturation</b>: {@value #SATURATION_RUN} consecutive queries surfaced no new host —
 *       the marginal gain is zero (Charnov's patch-leaving rule, in its cheapest form);</li>
 *   <li><b>the axis</b>: when a repeat fires, the note names ONE direction to move — language
 *       (when the question names one and every query so far is in one script), source type
 *       (when no query has anchored on a scholarly/primary/reference host), or abstraction.</li>
 * </ul>
 * Notes are positive directives (the prompt-style rule) appended to the search result the
 * model already reads — a constrained choice space, never a rejected action.
 */
public final class SearchSteer {

    static final double REPEAT_JACCARD = 0.6;
    static final double SEEN_HOST_SHARE = 0.8;
    static final int SATURATION_RUN = 3;

    /** Session counters, so a run log / a battery can count how often the steerer fired — the
     *  note itself sits at the END of a result the loop logs truncated, hence invisible there. */
    public static final java.util.concurrent.atomic.AtomicInteger STEER_EVENTS =
            new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger SATURATION_EVENTS =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SearchSteer.class);

    private final List<Set<String>> priorTerms = new ArrayList<>();
    private final List<String> priorQueries = new ArrayList<>();
    private final Set<String> seenHosts = new HashSet<>();
    private final Set<String> scripts = new HashSet<>();
    private int zeroGainRun = 0;
    private int saturations = 0;
    private int queries = 0;
    private int queriesAfterSaturation = 0;
    private boolean anchoredSourceType = false;

    /** Session counters for the run summary: every query, and every query issued AFTER the
     *  steerer had already declared saturation — the over-search index (RUC's DAS frames
     *  over-search and under-search as the two errors of one decision boundary). */
    public static final java.util.concurrent.atomic.AtomicInteger QUERIES =
            new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger QUERIES_AFTER_SATURATION =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * The STOP RULE, mechanical: the patch is exhausted once saturation has fired twice — six
     * consecutive queries surfaced no new source. Charnov's patch-leaving rule in its cheapest
     * form, and JSAI's satisficing shape (an explicit reference level, here "no marginal gain",
     * met → stop). The loop consults this to offer the finishing tool early.
     */
    public boolean exhausted() {
        return enabled() && saturations >= 2;
    }

    public int queries() { return queries; }
    public int queriesAfterSaturation() { return queriesAfterSaturation; }
    private String focus = "";

    /** The question, for the language axis. */
    public SearchSteer focus(String question) {
        this.focus = question == null ? "" : question;
        return this;
    }

    /** CODEZAIKU_SEARCH_STEER=off silences the controller — the A/B's OFF arm. Counters still run,
     *  so the off arm reports what the steerer WOULD have said; only the notes and the stop are off. */
    static boolean enabled() {
        return !"off".equalsIgnoreCase(org.codezaiku.Config.get("CODEZAIKU_SEARCH_STEER", "on"));
    }

    /** Observe one search; return "" or a steering note to append to the result. */
    public String observe(String query, List<String> resultUrls) {
        String note = observeAndNote(query, resultUrls);
        return enabled() ? note : "";
    }

    private String observeAndNote(String query, List<String> resultUrls) {
        Set<String> terms = terms(query);
        String script = script(query);
        queries++;
        QUERIES.incrementAndGet();
        if (saturations > 0) { queriesAfterSaturation++; QUERIES_AFTER_SATURATION.incrementAndGet(); }
        // near-repeat by terms
        String repeatOf = null;
        for (int i = 0; i < priorTerms.size(); i++) {
            if (jaccard(terms, priorTerms.get(i)) >= REPEAT_JACCARD) { repeatOf = priorQueries.get(i); break; }
        }
        // host novelty
        Set<String> hosts = new LinkedHashSet<>();
        for (String u : resultUrls) { String h = host(u); if (h != null) hosts.add(h); }
        int newHosts = 0;
        for (String h : hosts) if (!seenHosts.contains(h)) newHosts++;
        boolean mostlySeen = hosts.size() >= 3 && (hosts.size() - newHosts) >= SEEN_HOST_SHARE * hosts.size();
        if (newHosts == 0 && !hosts.isEmpty()) zeroGainRun++; else zeroGainRun = 0;
        if (query.toLowerCase(Locale.ROOT).contains("site:") || hosts.stream().anyMatch(SearchSteer::scholarlyOrPrimary)) {
            anchoredSourceType = true;
        }
        // record
        priorTerms.add(terms);
        priorQueries.add(query);
        seenHosts.addAll(hosts);
        scripts.add(script);

        StringBuilder note = new StringBuilder();
        if (repeatOf != null || mostlySeen) {
            STEER_EVENTS.incrementAndGet();
            log.info("steer: near-repeat ({}) for \"{}\" → axis: {}",
                    repeatOf != null ? "terms" : "hosts", query, axis());
            note.append("\nSTEER: this query ");
            if (repeatOf != null) note.append("overlaps an earlier one (\"").append(repeatOf).append("\")");
            if (repeatOf != null && mostlySeen) note.append(" and ");
            if (mostlySeen) note.append("returned mostly hosts you have already seen");
            note.append(". Spend the next query on new ground — ").append(axis()).append('.');
        }
        if (zeroGainRun >= SATURATION_RUN) {
            SATURATION_EVENTS.incrementAndGet();
            saturations++;
            zeroGainRun = 0;    // a fresh run must be earned before the next saturation call
            log.info("steer: SATURATED after {} zero-gain queries → axis: {}", zeroGainRun, axis());
            note.append("\nSATURATED: the last ").append(zeroGainRun)
                .append(" queries surfaced no new source. Read the best of what you have and write "
                        + "the answer, or move on a different axis — ").append(axis()).append('.');
        }
        return note.toString();
    }

    /** ONE direction to move, chosen mechanically from what has NOT been tried. */
    String axis() {
        String lang = namedLanguage(focus);
        if (lang != null && scripts.size() == 1) {
            return "write it IN " + lang + " (every query so far is in one script)";
        }
        if (!anchoredSourceType) {
            return "anchor it on a source TYPE you have not used: a scholarly host (arxiv.org, "
                    + "jstage.jst.go.jp, doi.org), a primary one (github.com, huggingface.co), or a "
                    + "reference one (wikipedia)";
        }
        return "change the abstraction level: name ONE specific item from the question, or step up "
                + "to the general topic and look for a survey / list page";
    }

    // ---- mechanics ----------------------------------------------------------------

    static Set<String> terms(String q) {
        Set<String> out = new HashSet<>();
        if (q == null) return out;
        for (String w : q.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 2) out.add(w);
        }
        return out;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int inter = 0;
        for (String s : a) if (b.contains(s)) inter++;
        return (double) inter / (a.size() + b.size() - inter);
    }

    static String host(String url) {
        try {
            String h = URI.create(url.strip()).getHost();
            if (h == null) return null;
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) {
            return null;
        }
    }

    static String script(String q) {
        if (q == null) return "latin";
        if (q.codePoints().anyMatch(c -> (c >= 0x3040 && c <= 0x30ff) || (c >= 0x4e00 && c <= 0x9fff))) return "cjk";
        if (q.codePoints().anyMatch(c -> c >= 0xac00 && c <= 0xd7af)) return "hangul";
        if (q.codePoints().anyMatch(c -> c >= 0x0400 && c <= 0x04ff)) return "cyrillic";
        if (q.codePoints().anyMatch(c -> c >= 0x0600 && c <= 0x06ff)) return "arabic";
        return "latin";
    }

    static boolean scholarlyOrPrimary(String host) {
        return host.contains("arxiv") || host.contains("doi.org") || host.contains("jstage")
                || host.contains("github") || host.contains("huggingface") || host.endsWith(".edu")
                || host.endsWith(".ac.jp") || host.contains("scholar") || host.contains("ncbi");
    }

    /** The language a question names, or null. Only languages whose script the steerer can see. */
    static String namedLanguage(String question) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (q.contains("japanese") || q.contains("日本語") || q.contains("japan")) return "Japanese";
        if (q.contains("chinese") || q.contains("中文") || q.contains("中国")) return "Chinese";
        if (q.contains("korean") || q.contains("한국")) return "Korean";
        if (q.contains("russian") || q.contains("русск")) return "Russian";
        if (q.contains("arabic") || q.contains("عرب")) return "Arabic";
        return null;
    }
}
