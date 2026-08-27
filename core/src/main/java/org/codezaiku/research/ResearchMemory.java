package org.codezaiku.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.codezaiku.Config;

/**
 * RESEARCH MEMORY POOL — findings accumulate across runs instead of evaporating.
 *
 * <p>Without this, every research run restarts from zero: the same questions get re-searched, the same
 * sources re-fetched, and nothing compounds. The pool makes research CUMULATIVE — a later run is seeded with
 * what earlier runs established, so it can skip settled ground and push the frontier.
 *
 * <p>Shape follows the field's agent-memory consensus (A-MEM's atomic notes, Mem0's incremental
 * summarize-and-dedup): store small ATOMIC entries (a claim + the source it came from + its topic), DEDUPE on
 * write, and RECALL by relevance at the start of the next run. Deliberately a simple append-only JSONL +
 * term-overlap retrieval: this pool holds hundreds of findings, not millions, and a dependency-free store
 * that always works beats a clever one that needs a running service.
 */
public final class ResearchMemory {
    private static final ObjectMapper M = new ObjectMapper();
    private ResearchMemory() { }

    /** One atomic finding: a claim, the source URL that supports it, and the question it came from. */
    public record Finding(String claim, String source, String topic, String at) { }

    /** Pool location. CODEZAIKU_RESEARCH_POOL redirects it — a separate pool per agent/project, and the
     *  isolation an A/B needs (a shared pool leaks arm A's findings into arm B and invalidates the run). */
    private static Path store() {
        String override = Config.get("CODEZAIKU_RESEARCH_POOL");
        if (override != null && !override.isBlank()) return Path.of(override);
        return Config.home().resolve("research").resolve("findings.jsonl");
    }

    // ---- write ----------------------------------------------------------------

    /** Record one atomic finding, skipping near-duplicates already in the pool. Returns true if stored. */
    public static synchronized boolean record(String topic, String claim, String source) {
        if (claim == null || claim.isBlank()) return false;
        String c = claim.strip();
        for (Finding f : all()) if (similar(f.claim(), c)) return false;   // dedup on write (Mem0 pattern)
        try {
            Path p = store();
            Files.createDirectories(p.getParent());
            ObjectNode n = M.createObjectNode();
            n.put("claim", c);
            n.put("source", source == null ? "" : source.strip());
            n.put("topic", topic == null ? "" : topic.strip());
            n.put("at", Instant.now().toString());
            Files.writeString(p, M.writeValueAsString(n) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Harvest findings from a completed research answer. The loop is told to end with a SOURCES list, so the
     * sources are parsed out and the substantive sentences stored as claims against them.
     */
    public static int harvest(String topic, String answer) {
        if (answer == null || answer.isBlank()) return 0;
        List<String> urls = new ArrayList<>();
        var m = Pattern.compile("https?://\\S+").matcher(answer);
        while (m.find()) {
            String u = m.group().replaceAll("[)\\],.;]+$", "");
            if (!urls.contains(u)) urls.add(u);
        }
        String primary = urls.isEmpty() ? "" : String.join(" ", urls.subList(0, Math.min(3, urls.size())));
        int stored = 0;
        // Claims = the answer's substantive sentences (skip the SOURCES list itself and bare URLs).
        for (String line : answer.split("\\r?\\n")) {
            String s = line.strip().replaceAll("^[-*\\d.)\\s]+", "").strip();
            if (s.length() < 40) continue;
            if (s.toLowerCase(Locale.ROOT).startsWith("sources")) continue;
            if (s.startsWith("http")) continue;
            for (String sent : s.split("(?<=[.!?])\\s+")) {
                String t = sent.strip();
                if (t.length() >= 40 && t.length() <= 400 && record(topic, t, primary)) stored++;
            }
        }
        return stored;
    }

    // ---- read -----------------------------------------------------------------

    /** Every finding in the pool (oldest first). */
    public static List<Finding> all() {
        Path p = store();
        if (!Files.exists(p)) return List.of();
        List<Finding> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    JsonNode n = M.readTree(line);
                    out.add(new Finding(n.path("claim").asText(""), n.path("source").asText(""),
                            n.path("topic").asText(""), n.path("at").asText("")));
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Recall the findings most relevant to {@code question}, best first (term overlap over claim + topic). */
    public static List<Finding> recall(String question, int limit) {
        Set<String> q = terms(question);
        if (q.isEmpty()) return List.of();
        record Scored(Finding f, int score) { }
        List<Scored> scored = new ArrayList<>();
        for (Finding f : all()) {
            Set<String> t = terms(f.claim() + " " + f.topic());
            int overlap = 0;
            for (String w : q) if (t.contains(w)) overlap++;
            if (overlap > 0) scored.add(new Scored(f, overlap));
        }
        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
        List<Finding> out = new ArrayList<>();
        for (Scored s : scored) {
            if (out.size() >= limit) break;
            out.add(s.f());
        }
        return out;
    }

    /** The "what you already know" block seeded into a research run, or "" when the pool has nothing relevant. */
    public static String promptBlock(String question) {
        List<Finding> hits = recall(question, 8);
        if (hits.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "WHAT YOU ALREADY ESTABLISHED (from earlier research — treat as known; do NOT re-search these, "
                + "build BEYOND them):\n");
        for (Finding f : hits) {
            sb.append("- ").append(f.claim());
            if (!f.source().isBlank()) sb.append("  [").append(f.source().split(" ")[0]).append(']');
            sb.append('\n');
        }
        return sb.append('\n').toString();
    }

    // ---- helpers --------------------------------------------------------------

    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "that", "this", "from", "are",
            "was", "were", "what", "which", "how", "why", "does", "did", "can", "could", "should", "would",
            "its", "their", "there", "then", "than", "into", "over", "under", "about", "when", "where", "who",
            "you", "your", "not", "but", "all", "any", "has", "have", "had", "use", "used", "using", "via");

    private static Set<String> terms(String s) {
        if (s == null) return Set.of();
        Set<String> out = new HashSet<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9+#.-]+")) {
            if (w.length() >= 3 && !STOP.contains(w)) out.add(w);
        }
        return out;
    }

    /** Near-duplicate check: high term overlap between two claims (dedup on write). */
    private static boolean similar(String a, String b) {
        if (a.equalsIgnoreCase(b)) return true;
        Set<String> ta = terms(a), tb = terms(b);
        if (ta.isEmpty() || tb.isEmpty()) return false;
        int inter = 0;
        for (String w : ta) if (tb.contains(w)) inter++;
        int smaller = Math.min(ta.size(), tb.size());
        return smaller > 0 && (double) inter / smaller >= 0.85;
    }

    /** A short human summary of the pool (for the research_memory tool). */
    public static String summary() {
        List<Finding> all = all();
        if (all.isEmpty()) return "(research memory pool is empty)";
        Set<String> topics = new HashSet<>();
        for (Finding f : all) if (!f.topic().isBlank()) topics.add(f.topic());
        StringBuilder sb = new StringBuilder(all.size() + " findings across " + topics.size() + " topics.\n");
        for (String t : topics) sb.append("- ").append(t).append('\n');
        return sb.toString();
    }
}
