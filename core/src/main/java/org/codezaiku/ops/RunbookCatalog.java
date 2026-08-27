package org.codezaiku.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The runbook catalog (HolmesGPT's {@code catalog.json} + markdown files, PLAN_CODEZAIKU_OPS.md §4).
 * Runbooks are MATCHED PROCEDURES (how to investigate a class of fault), NOT answers — they encode the
 * cause-chain-tracing steps a shallow diagnosis skips, so injecting one never leaks the root cause.
 * Loaded from classpath resources under {@code /ops/runbooks/}.
 */
public final class RunbookCatalog {
    public record Entry(String id, String description) { }

    private final List<Entry> entries;
    private static final ObjectMapper J = new ObjectMapper();

    private RunbookCatalog(List<Entry> entries) { this.entries = entries; }

    /** Load the catalog; returns an empty catalog (available()==false) if the resource is absent. */
    public static RunbookCatalog load() {
        try (InputStream in = RunbookCatalog.class.getResourceAsStream("/ops/runbooks/catalog.json")) {
            if (in == null) return new RunbookCatalog(List.of());
            JsonNode root = J.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            List<Entry> es = new ArrayList<>();
            for (JsonNode r : root.path("runbooks")) {
                String id = r.path("id").asText("");
                if (!id.isBlank()) es.add(new Entry(id, r.path("description").asText("")));
            }
            return new RunbookCatalog(es);
        } catch (Exception e) {
            return new RunbookCatalog(List.of());
        }
    }

    public boolean available() { return !entries.isEmpty(); }

    public List<Entry> entries() { return entries; }

    /** The catalog rendered for the system prompt: one {@code id — description} line per runbook. */
    public String listing() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) sb.append("  • ").append(e.id()).append(" — ").append(e.description()).append('\n');
        return sb.toString();
    }

    /**
     * PUSH (auto-inject) selection: pick a PROCEDURAL / symptom runbook to inject up front based on the
     * incident text. Only the non-leaking symptom runbooks (web-502, service-down) are eligible — a
     * specific-root runbook (disk-full, cert-expired, …) would either reveal the answer or, worse, mislead
     * (a "disk nearly full" mention must NOT push a disk-full runbook when the disk is a red herring). The
     * 9B does not proactively fetch (measured), so the effective delivery for a small model is push.
     * Returns the runbook body to inject, or "" if nothing clearly matches.
     */
    public String autoInject(String incident) {
        String t = incident == null ? "" : incident.toLowerCase();
        String id = null;
        if (t.contains("502") || t.contains("bad gateway") || t.contains("gateway") || t.contains("upstream")) {
            id = "web-502";
        } else if (t.contains("won't start") || t.contains("wont start") || t.contains("not start")
                || t.contains("not running") || t.contains("is down") || t.contains("won't come up")
                || t.contains("not serving")) {
            id = "service-down";
        }
        if (id == null) return "";
        String body = fetch(id);
        return body.startsWith("ERROR") ? "" : body;
    }

    /** Fetch a runbook's markdown body by id, or an error string if unknown/unreadable. */
    public String fetch(String id) {
        if (id == null) return "ERROR: no runbook id given";
        String key = id.trim().replaceAll("\\.md$", "");
        boolean known = entries.stream().anyMatch(e -> e.id().equalsIgnoreCase(key));
        if (!known) return "ERROR: no runbook '" + id + "'. Available: "
                + String.join(", ", entries.stream().map(Entry::id).toList());
        try (InputStream in = RunbookCatalog.class.getResourceAsStream("/ops/runbooks/" + key + ".md")) {
            if (in == null) return "ERROR: runbook '" + key + "' has no body file";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "ERROR: could not read runbook '" + key + "': " + e.getMessage();
        }
    }
}
