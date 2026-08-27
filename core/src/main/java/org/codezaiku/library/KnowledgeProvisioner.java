package org.codezaiku.library;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.codezaiku.shape.ProjectFacts;

/**
 * Knowledge provisioner — STAGE 1+2 (MVP): at project intake, PROFILE the project and CHECK the library's
 * COVERAGE of what this project needs, then LOG a report. No acquisition yet (that's stage 3); this is the
 * measurement layer — it makes the library's gaps VISIBLE per run instead of discovered one crash at a
 * time (battery35: the mbox-parsing concern was ungrounded → the 9B hallucinated
 * {@code mailbox.Message.get_count}; nothing flagged it going in).
 *
 * <p>Profile = stack (ProjectFacts) + deps (manifest) + concerns (the spec's own section headers, with
 * their bodies). Coverage = for each concern SECTION, does the library push an idiom for it (or is it a
 * web concern the framework example covers)? The UNGROUNDED list is the per-project gap worklist that
 * drives stage 3 (fetch-from-source + distill). See SPEC_LIBRARY_PROVISIONING.md.
 */
public final class KnowledgeProvisioner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeProvisioner.class);
    private static final Pattern HEADER = Pattern.compile("^#{1,4}\\s+(.+?)\\s*$");
    // Structural / non-concern headers to skip — they describe the doc, not a capability to build.
    private static final Pattern META = Pattern.compile(
            "(overview|delivery|deliverables|testing|tests|constraints?|notes?|tech ?stack|setup|installation|"
            + "install|run|usage|spec|specification|requirements?|goal|objective|scope|background|summary|"
            + "introduction|architecture|glossary|appendix|references?|acceptance|getting started|prerequisites?|"
            + "configuration|config|environment|deployment|license|contributing|authoritative).*");

    private KnowledgeProvisioner() {}

    /** Profile + coverage-check + log. Never throws — a provisioner hiccup must not block a run. */
    public static void report(Path root, String goal, Library library) {
        try {
            String lang = safe(() -> ProjectFacts.language(root), "unknown");
            boolean fw = library.hasFrameworkExample(root);
            List<Section> sections = sections(goal);

            List<String> grounded = new ArrayList<>();
            List<String> ungrounded = new ArrayList<>();
            List<String[]> gaps = new ArrayList<>();                       // {header, body} for the acquirer
            for (Section s : sections) {
                List<String> idioms = library.idiomsForHeader(s.header);   // header-anchored, not body-incidental
                if (!idioms.isEmpty()) {
                    grounded.add(s.header + " → " + String.join(",", idioms));
                } else if (fw && isWebConcern(s.header)) {
                    grounded.add(s.header + " → framework-example");
                } else {
                    ungrounded.add(s.header);
                    gaps.add(new String[]{s.header, s.body});
                }
            }

            log.info("knowledge-coverage: stack={} framework-example={} concerns={} grounded={} ungrounded={}",
                    lang, fw, sections.size(), grounded.size(), ungrounded.size());
            for (String g : grounded) log.info("knowledge-coverage:   GROUNDED   {}", g);
            if (ungrounded.isEmpty()) {
                log.info("knowledge-coverage:   (no ungrounded concerns)");
            } else {
                log.warn("knowledge-coverage:   UNGROUNDED {} ← library gaps for THIS project (stage-3 acquisition worklist)",
                        ungrounded);
            }

            // STAGE 3: acquire grounding for the gaps. Inert (dry-run, logs only) until a distiller endpoint
            // is configured (CODEZAIKU_DISTILLER_URL → the 27B); then it reads the real dep source, distills
            // an idiom, validates, and caches it (picked up by Library on the next run).
            Acquirer.acquire(root, lang, gaps, Distiller.fromEnv());
        } catch (Exception e) {
            log.info("knowledge-coverage: skipped ({})", e.toString());
        }
    }

    private record Section(String header, String body) {}

    /** Split the spec into concern SECTIONS (header + body up to the next header), dropping meta sections. */
    private static List<Section> sections(String goal) {
        List<Section> out = new ArrayList<>();
        if (goal == null || goal.isBlank()) return out;
        String[] lines = goal.split("\\R");
        String curHeader = null;
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            Matcher m = HEADER.matcher(line.strip());
            if (m.matches()) {
                flush(out, curHeader, body);
                curHeader = m.group(1).strip();
                body.setLength(0);
            } else if (curHeader != null) {
                body.append(line).append('\n');
            }
        }
        flush(out, curHeader, body);
        return out;
    }

    private static void flush(List<Section> out, String header, StringBuilder body) {
        if (header == null) return;
        String h = header.toLowerCase();
        if (META.matcher(h).matches()) return;                // skip overview/delivery/testing/constraints…
        if (h.length() < 3 || h.length() > 60) return;        // skip noise/title lines
        out.add(new Section(header, body.toString()));
    }

    private static boolean isWebConcern(String header) {
        String h = header.toLowerCase();
        return h.contains("web") || h.contains("dashboard") || h.contains("api") || h.contains("endpoint")
                || h.contains("interface") || h.contains("http") || h.contains("rest") || h.contains("server");
    }

    private interface Sup<T> { T get() throws Exception; }
    private static <T> T safe(Sup<T> s, T def) { try { T v = s.get(); return v == null ? def : v; } catch (Exception e) { return def; } }
}
