package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * How many searches and page reads CodeZaiku made through each service, by month, with the credits the paid ones charged: kept in
 * {@code search-tally.json} in the settings folder, so a person can see what a month of research cost before the bill does. Every
 * backend is counted, the free ones with no credits; a failure to write the tally is never a failure of the search.
 */
public final class SearchTally {
    private SearchTally() { }

    static final ObjectMapper M = new ObjectMapper();
    /** The tally file; a test points it elsewhere. */
    static volatile Path override = null;

    static Path file() { return override != null ? override : Config.home().resolve("search-tally.json"); }

    /** One more call through {@code service} this month, charged {@code credits} (0 for a free one). */
    public static synchronized void count(String service, int credits) {
        try {
            Path f = file();
            ObjectNode all = Files.isRegularFile(f) ? (ObjectNode) M.readTree(f.toFile()) : M.createObjectNode();
            ObjectNode month = all.has(month()) && all.get(month()).isObject() ? (ObjectNode) all.get(month()) : all.putObject(month());
            ObjectNode s = month.has(service) && month.get(service).isObject() ? (ObjectNode) month.get(service) : month.putObject(service);
            s.put("calls", s.path("calls").asInt(0) + 1).put("credits", s.path("credits").asInt(0) + Math.max(0, credits));
            Files.createDirectories(f.getParent());
            Files.writeString(f, M.writerWithDefaultPrettyPrinter().writeValueAsString(all), StandardCharsets.UTF_8);
        } catch (Exception ignored) { /* the tally is a convenience; the search stands */ }
    }

    static String month() { return LocalDate.now().toString().substring(0, 7); }

    /** This month's calls per service, as kept: {calls, credits}. */
    public static Map<String, int[]> thisMonth() {
        Map<String, int[]> out = new TreeMap<>();
        try {
            Path f = file();
            if (!Files.isRegularFile(f)) return out;
            JsonNode month = M.readTree(f.toFile()).path(month());
            month.properties().forEach(e -> out.put(e.getKey(), new int[]{e.getValue().path("calls").asInt(0), e.getValue().path("credits").asInt(0)}));
        } catch (Exception ignored) { }
        return out;
    }

    /** The month's tally in a sentence: "This month, 2026-10: 123 searches through Serper, 123 credits; 40 through Brave; 12 pages through Firecrawl, 12 credits. …" */
    public static String summary() {
        Map<String, int[]> m = thisMonth();
        if (m.isEmpty()) return "Nothing counted this month yet: the tally in " + file() + " counts every search and every page read through a service, with the credits the paid ones charge.";
        List<String> parts = new ArrayList<>();
        for (var e : m.entrySet()) {
            int calls = e.getValue()[0], credits = e.getValue()[1];
            boolean pages = e.getKey().equals("Firecrawl");
            parts.add(calls + (pages ? (calls == 1 ? " page" : " pages") : (calls == 1 ? " search" : " searches")) + " through " + e.getKey() + (credits > 0 ? ", " + credits + (credits == 1 ? " credit" : " credits") : ""));
        }
        return "This month, " + month() + ": " + String.join("; ", parts) + ". The tally is in " + file() + ".";
    }
}
