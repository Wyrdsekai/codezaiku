package org.codezaiku.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The LEARN-BACK loop — turns a successful recon into a reusable CARD. When recon (no card matched) derives a
 * fix that the objective closed-loop check VERIFIES, that fix is captured as a {@code candidate} card: the
 * exact command(s) that actually worked, keyed by the fault's signature. The next time the same fault occurs
 * it hits the fast card-path instead of re-reasoning it from scratch — the library grows from experience.
 *
 * <p>Safety by construction: a learned card is always {@code status: candidate}, so it only ever PROPOSES in
 * production (never auto-applies) until a controlled A/B promotes it — the same do-no-harm rule as any
 * unvalidated card. It is lint-checked before it is written, and never overwrites an existing card. Learned
 * cards live in their own directory (provenance kept separate from curated cards) that the loader also reads.
 *
 * <p>What's captured is the {@code appliedSteps} the remediation actually ran — the gold. Even when recon's
 * prose diagnosis was rough, the remediation loop found the real command; that command is the card's fix. The
 * target container name is templatized to {@code <container>} so the card re-grounds against any stack.
 */
public final class OpsLearn {
    private static final Logger log = LoggerFactory.getLogger(OpsLearn.class);

    private OpsLearn() { }

    /**
     * Record a verified recon fix as a candidate card in {@code dir}. No-op (and never throws) when learning
     * is off, inputs are insufficient (no signature, no command-shaped step), the card fails lint, or a card
     * for this fault already exists.
     */
    public static void record(String dir, String root, String container, String symptom,
                              String diagnosis, List<String> appliedSteps) {
        if (dir == null || dir.isBlank() || root == null || root.isBlank()) return;
        String sig = signature(symptom);
        if (sig.isBlank()) { log.info("ops-learn: no reusable signature from symptom — not learning"); return; }
        List<String> fixes = fixCommands(appliedSteps, container);
        if (fixes.isEmpty()) { log.info("ops-learn: no command-shaped applied step — not learning"); return; }

        StringBuilder card = new StringBuilder();
        card.append("match: ").append(root).append('\n');
        card.append("signature: ").append(sig).append('\n');
        card.append("status: candidate\n");
        card.append("push: rescue\n");
        card.append("# ").append(root).append(" — ").append(firstWords(symptom, 8))
            .append(" — learned fix (recon-derived, verified once)\n");
        String diag = oneLine(diagnosis, 220);
        if (!diag.isBlank()) card.append(diag).append('\n');
        card.append("Fix (a recon-derived remediation VERIFIED with this; candidate until A/B'd):\n");
        for (String f : fixes) card.append('`').append(f).append("`\n");
        card.append("Confirm ").append(root).append(" recovers, then conclude.\n");

        String bad = OpsKnowledge.lintViolation(card.toString());
        if (bad != null) { log.warn("ops-learn: generated card failed lint ({}) — not learning", bad); return; }

        try {
            Files.createDirectories(Path.of(dir));
            Path p = Path.of(dir, root + "-" + slug(sig) + ".md");
            if (Files.exists(p)) { log.info("ops-learn: card already exists for {} — {}", root, p.getFileName()); return; }
            Files.writeString(p, card.toString());
            System.out.println("[ops-learn] LEARNED a candidate card → " + p + "  (fault: " + root + " / \"" + sig + "\")");
            log.info("ops-learn: wrote learned candidate card {}", p);
        } catch (Exception e) {
            log.warn("ops-learn: write failed: {}", e.getMessage());
        }
    }

    /** A reusable signature phrase from the app's error for the root. Kept as the ACTUAL error text (it recurs
     *  identically — stripping quotes/chars would stop it substring-matching the next probe); only the comma
     *  (which would split the signature CSV) and trailing punctuation are removed, and it is length-capped. */
    private static String signature(String symptom) {
        if (symptom == null) return "";
        String s = symptom.toLowerCase(Locale.ROOT).replaceFirst("^down:\\s*", "").strip();
        s = s.replace(",", " ").replaceAll("[.!]+$", "").replaceAll("\\s+", " ").strip();
        if (s.length() > 90) s = s.substring(0, 90).strip();
        return s.split("\\s+").length >= 2 ? s : "";   // ≥2 words, else too generic to key on
    }

    /** The command-shaped applied steps (docker/CLI), with the target container templatized to {@code <container>}. */
    private static List<String> fixCommands(List<String> steps, String container) {
        List<String> out = new ArrayList<>();
        if (steps == null) return out;
        for (String s : steps) {
            if (s == null || s.isBlank()) continue;
            String l = s.toLowerCase(Locale.ROOT);
            boolean cmd = l.contains("docker ") || l.contains("kubectl ") || l.startsWith("curl")
                    || l.matches("(?s).*\\b(psql|redis-cli|rabbitmqctl|cypher-shell|ollama|mongosh|aws)\\b.*");
            if (!cmd) continue;
            String t = (container == null || container.isBlank()) ? s : s.replace(container, "<container>");
            if (!out.contains(t)) out.add(t);
        }
        return out;
    }

    private static String firstWords(String s, int n) {
        if (s == null || s.isBlank()) return "fault";
        String[] w = s.replaceFirst("^down:\\s*", "").strip().split("\\s+");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(n, w.length); i++) b.append(i == 0 ? "" : " ").append(w[i]);
        return b.toString();
    }

    private static String oneLine(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > max ? t.substring(0, max).strip() + "…" : t;
    }

    private static String slug(String s) {
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return t.length() > 40 ? t.substring(0, 40) : t;
    }
}
