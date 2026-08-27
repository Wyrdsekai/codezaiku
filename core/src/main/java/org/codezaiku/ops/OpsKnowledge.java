package org.codezaiku.ops;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The ops KNOWLEDGE layer: fault-class → fix-procedure cards, PUSHED by the harness.
 *
 * <p>This is what the OpenRCA oracle measurement actually pointed at: its 2.2× lift came from a DOMAIN MAP
 * (fault-class → the signals that evidence it), not from topology (measured ≈ net zero, and harmful where
 * blind) and not from statistics (loc_eval: 1.78× over random). Cards carry that map per technology.
 *
 * <p>The DELIVERY mechanism is measured, not assumed (AIOpsLab know3–know9, K=5 paired arms per step):
 * <ul>
 *   <li><b>The harness injects; the model never looks.</b> A fetchable catalog was inert (0/25 fetches) and
 *       a model-side trigger fired 0× (it required the model to already be reading the logs the card points
 *       at). The working shape: the harness greps the stack's recent logs for each card's
 *       {@code signature:} keywords and pushes the matched card itself.</li>
 *   <li><b>The scan is DEFERRED and bounded.</b> At init the fault signature is often not in the logs yet
 *       (no traffic has hit the broken path) and an unbounded sweep starves the turn budget — the init-time
 *       scan fired 0/8. One combined scan at ~turn 5, ≤30s total, fired 25/25.</li>
 *   <li><b>Push timing is fault-class knowledge, declared ON the card</b> ({@code push: immediate|rescue}).
 *       Immediate push flips fault classes the model cannot solve by exploration (a component dead at
 *       startup: OFF 1/10 → ON 7/10) and DESTROYS the ones it can (OFF 13/20 → ON 0/20 across two card
 *       versions — the procedure displaces the winning exploration). Rescue holds the matched card and
 *       injects only if the loop hasn't concluded by the rescue turn: harm gone, and on the 9B (which
 *       rarely wins by exploration) rescue itself lifts. Per-card policy scored mongo 7/25 → 15/25 on the
 *       30B and 2/25 → 10/25 on the shipping 9B, zero harmed cells. Default rescue — first, do no harm.</li>
 * </ul>
 *
 * <p>Soundness rules, load-bearing:
 * <ul>
 *   <li>A card is GENERAL technology knowledge, writable from the tech's public docs, blind to any task —
 *       the same discipline as the framework/ML cards (ORPO 0.27→1.0 was API knowledge, not an answer).</li>
 *   <li>Facts about how faults LOOK and the procedure that fixes the CLASS — never a diagnosis of this
 *       incident. Signature keywords are generic error strings from the technology's own docs.</li>
 * </ul>
 *
 * <p>Card format (markdown files in a directory): first line {@code match: kw1, kw2} (stack keywords);
 * optional {@code signature: kw1, kw2} (log strings evidencing the fault class — makes the card
 * scan-triggered); optional {@code push: immediate|rescue}; then the body. Enabled by pointing
 * {@code CODEZAIKU_OPS_KNOWLEDGE} at the directory (absent = off, the certified-baseline default).
 */
public final class OpsKnowledge {

    /**
     * A parsed, lint-clean card. {@code signature} empty = match-only card (pushed up-front by stack).
     * {@code status} is PROVENANCE for the two-tier library — {@code "validated"} = a controlled A/B
     * confirmed it lifts (mongo/k8s), {@code "candidate"} = docs-derived, lint-clean, content unproven.
     * It is never rendered into the prompt (the model gets the same procedure either way); it only gates
     * safety (a candidate may not push immediate) and feeds the load-time tier report.
     */
    public record Card(List<String> match, List<String> signature, String push, String status,
                       List<String> platform, String body, String path) {
        public boolean immediate() { return "immediate".equals(push); }
        public boolean validated() { return "validated".equals(status); }
        /** Empty = runs anywhere; otherwise the platforms whose tooling the PROCEDURE needs. */
        public boolean runsAnywhere() { return platform == null || platform.isEmpty(); }
    }

    /**
     * The declarable platforms, and the {@link TargetOs#stackTokens} token that evidences each.
     *
     * <p>A card's {@code platform:} line says where its PROCEDURE can run — which is not what
     * {@code match:} says (that is which fault it recognises) and not what {@code status:} says (that is
     * whether a controlled A/B confirmed it lifts). Keeping them separate is the point: the validated
     * tier was earned on AIOpsLab, which is Kubernetes, and five of seven validated cards turned out to
     * carry {@code kubectl}-only procedures while matching on product keywords like {@code redis} —
     * so they fired on docker and compose stacks and handed the model a procedure with no kubectl to
     * run it. "Validated" was silently asserting more than it had measured.
     */
    static final String[][] PLATFORM_TOKENS = {
            {"kubernetes", "kubectl"},
            {"docker",     "docker"},
            {"systemd",    "systemd"},
            {"macos",      "macos"},
    };

    // The TERSE ceilings, measured: a 1258-char card dropped the submit rate 43% → 0% (the model followed
    // the checklist and never concluded); the working shape is ~600 chars ending in a stop cue.
    private static final int MAX_CHARS = 900, MAX_LINES = 14;

    /**
     * The four-axis card bar, mechanized (ops-knowledge/lint.py is the authoring-time mirror). A card below
     * bar doesn't just underperform — it silently VOIDS the measurement built on it, so a violating card is
     * REJECTED LOUDLY at load rather than pushed. CORRECTNESS is the one axis a lint cannot check; only a
     * controlled A/B validates content (ORPO 0.27 → 1.0).
     */
    static String lintViolation(String card) {
        String[] lines = card.split("\n", -1);
        if (lines.length == 0 || !lines[0].toLowerCase(Locale.ROOT).startsWith("match:")
                || Stream.of(lines[0].substring(6).split(",")).noneMatch(k -> !k.isBlank())) {
            return "PUSHED: needs a 'match: kw[, kw...]' first line (an untriggered card is inert)";
        }
        int i = 1;
        String push = "rescue", status = "candidate";
        while (i < lines.length) {
            String low = lines[i].toLowerCase(Locale.ROOT);
            if (low.startsWith("signature:")) { i++; continue; }
            if (low.startsWith("push:")) {
                push = lines[i].substring(5).strip().toLowerCase(Locale.ROOT);
                if (!push.equals("immediate") && !push.equals("rescue")) {
                    return "PUSH-POLICY: '" + push + "' — must be 'immediate' or 'rescue' (a typo would "
                            + "silently become the default)";
                }
                i++; continue;
            }
            if (low.startsWith("status:")) {
                status = lines[i].substring(7).strip().toLowerCase(Locale.ROOT);
                if (!status.equals("validated") && !status.equals("candidate")) {
                    return "STATUS: '" + status + "' — must be 'validated' or 'candidate'";
                }
                i++; continue;
            }
            if (low.startsWith("platform:")) {
                for (String pf : keywords(lines[i].substring(9))) {
                    if (Stream.of(PLATFORM_TOKENS).noneMatch(t -> t[0].equals(pf))) {
                        return "PLATFORM: '" + pf + "' — must be one of kubernetes, docker, systemd, macos "
                                + "(a typo would silently widen the card to every target)";
                    }
                }
                i++; continue;
            }
            if (low.startsWith("reuses:")) { i++; continue; }   // auto-promotion counter (OpsPromote)
            break;
        }
        if (status.equals("candidate") && push.equals("immediate")) {
            return "SAFETY: a 'candidate' (unvalidated) card must be push:rescue — an unproven immediate "
                    + "push can DESTROY fault classes the model already solves (13/20->0/20)";
        }
        String body = String.join("\n", Arrays.asList(lines).subList(i, lines.length)).strip();
        if (body.length() > MAX_CHARS) return "TERSE: body " + body.length() + " chars > " + MAX_CHARS
                + " (verbose cards kill convergence)";
        if (body.lines().count() > MAX_LINES) return "TERSE: " + body.lines().count() + " lines > " + MAX_LINES;
        if (!body.toLowerCase(Locale.ROOT).contains("conclude")) {
            return "STOP: body must contain 'conclude' — a card that opens an investigation must close it";
        }
        if (body.lines().filter(l -> l.startsWith("# ")).count() != 1) {
            return "SCOPED: exactly one '# ' heading — one fault-domain per card";
        }
        return null;
    }

    /** Load every lint-clean card in {@code dir}; below-bar cards are rejected LOUDLY, never silently. */
    public static List<Card> cards(String dir) {
        List<Card> out = new ArrayList<>();
        if (dir == null || dir.isBlank()) return out;
        try (Stream<Path> files = Files.list(Path.of(dir))) {
            files.filter(p -> p.toString().endsWith(".md")).sorted().forEach(p -> {
                try {
                    String s = Files.readString(p);
                    String bad = lintViolation(s);
                    if (bad != null) {   // LOUD, never silent — a below-bar card must not reach a prompt
                        System.out.println("[ops-knowledge] REJECTED " + p.getFileName() + " — " + bad);
                        return;
                    }
                    String[] lines = s.split("\n", -1);
                    List<String> match = keywords(lines[0].substring(6));
                    List<String> sig = List.of();
                    String push = "rescue", status = "candidate";   // defaults: do no harm, unproven
                    List<String> platform = List.of();               // default: no declared constraint
                    int i = 1;
                    while (i < lines.length) {
                        String low = lines[i].toLowerCase(Locale.ROOT);
                        if (low.startsWith("signature:")) { sig = keywords(lines[i].substring(10)); i++; }
                        else if (low.startsWith("push:")) { push = lines[i].substring(5).strip().toLowerCase(Locale.ROOT); i++; }
                        else if (low.startsWith("status:")) { status = lines[i].substring(7).strip().toLowerCase(Locale.ROOT); i++; }
                        else if (low.startsWith("platform:")) { platform = keywords(lines[i].substring(9)); i++; }
                        else if (low.startsWith("reuses:")) { i++; }   // promotion counter — skip
                        else break;
                    }
                    String body = String.join("\n", Arrays.asList(lines).subList(i, lines.length)).strip();
                    out.add(new Card(match, sig, push, status, platform, body, p.toString()));
                } catch (Exception e) {
                    System.out.println("[ops-knowledge] UNREADABLE " + p.getFileName() + " — " + e.getMessage());
                }
            });
        } catch (Exception ignored) { }
        return out;
    }

    /**
     * The best signature-matched card for an ALREADY-localized fault (the fast path): the card's match
     * keywords must hit {@code stackText} AND the most of its signature strings must be present in
     * {@code probeText}. Null if none match. Same ranking the in-loop {@code signatureScan} uses, exposed so
     * a localized run can match a fix procedure without spending a diagnosis loop to conclude.
     */
    public static Card matchSignature(List<Card> cards, String stackText, String probeText) {
        return matchSignature(cards, stackText, probeText, null);
    }

    /**
     * As above, but also discarding cards whose PROCEDURE cannot run on this target.
     *
     * <p>{@code targetTools} is {@link TargetOs#stackTokens}: what the box demonstrably has. Null or
     * blank means "not probed" and disables the check, which is how the three-argument form keeps its
     * old behaviour.
     */
    public static Card matchSignature(List<Card> cards, String stackText, String probeText,
                                      String targetTools) {
        if (cards == null || probeText == null) return null;
        String stack = stackText == null ? "" : stackText.toLowerCase(Locale.ROOT);
        String probe = probeText.toLowerCase(Locale.ROOT);
        Card best = null;
        long bestHits = 0;
        for (Card c : cards) {
            if (!stack.isEmpty() && c.match().stream().noneMatch(stack::contains)) continue;
            if (c.signature().isEmpty()) continue;
            if (withheldTool(c, targetTools) != null) continue;
            long hits = c.signature().stream().filter(probe::contains).count();
            if (hits > bestHits) { bestHits = hits; best = c; }
        }
        return best;
    }

    /**
     * Tools whose ABSENCE makes a procedure unrunnable, and the {@link TargetOs#stackTokens} token that
     * evidences each. Only strongly platform-divergent tools are listed: the check must never reject a
     * card over a tool it cannot reliably detect.
     */
    private static final String[][] PROCEDURE_TOOLS = {
            {"systemctl",  "systemd"},
            {"journalctl", "systemd"},
            {"launchctl",  "launchd"},
            {"kubectl",    "kubectl"},
            {"docker",     "docker"},
    };

    /**
     * The tool a card's procedure invokes that this target does not have, or null if it can run here.
     *
     * <p>The keyword gate cannot do this job, and believing it could was a real defect. Host-tier cards
     * declare {@code match: linux, host, systemd, …}; {@code host} is true of every target, so
     * {@code host-systemd-unit-failure} passed the gate on a Mac no matter how honestly the environment
     * was described — its procedure is {@code systemctl restart}, which cannot run there. Measured by
     * set-diff: making the environment tokens honest removed ZERO systemctl-bearing cards from a Mac,
     * because they were never getting in through the {@code systemd} keyword.
     *
     * <p>A card that cannot run is worse than no card: the model follows a confident procedure into
     * "command not found" and burns the run. This is a filter on harness-supplied KNOWLEDGE, decided by
     * a probe — not an interceptor on the model's own actions, which the design forbids.
     */
    static String missingTool(Card c, String targetTools) {
        if (targetTools == null || targetTools.isBlank()) return null;   // not probed → do not filter
        String have = targetTools.toLowerCase(Locale.ROOT);

        // DECLARED platform wins. A card that says where it runs is stating a fact its author knew;
        // sniffing its body for tool names is a guess about that same fact, and a worse one.
        if (!c.runsAnywhere()) {
            for (String pf : c.platform()) {
                for (String[] t : PLATFORM_TOKENS) {
                    if (t[0].equals(pf) && has(have, t[1])) return null;   // one supported platform is enough
                }
            }
            return c.platform().get(0);
        }

        // UNDECLARED → fall back to what the procedure visibly invokes. Weaker, and the reason the
        // platform: line exists at all, but it covers a corpus written before the field did.
        String body = c.body() == null ? "" : c.body().toLowerCase(Locale.ROOT);
        String firstMissing = null;
        for (String[] pair : PROCEDURE_TOOLS) {
            String tool = pair[0], token = pair[1];
            if (!has(body, tool)) continue;
            // ANY available tool makes the card runnable: cards routinely name an alternative path
            // ("or, on kubernetes, kubectl exec …") beside the one that works here.
            if (has(have, token)) return null;
            if (firstMissing == null) firstMissing = tool;
        }
        return firstMissing;
    }

    private static boolean has(String haystack, String word) {
        return haystack.matches("(?s).*\\b" + word + "\\b.*");
    }

    /**
     * DEFAULT ON, earned by a positive controlled flip. Disable with
     * {@code CODEZAIKU_OPS_CARD_APPLICABILITY=off}.
     *
     * <p>Measured on a macOS docker target whose TLS certificate had expired (bench/ops-eval/certstack).
     * With the filter OFF the localizer names {@code root=tls}, and {@code host-tls-cert-expiry} wins the
     * fast path on two signature hits — a card that declares {@code platform: systemd} and whose
     * procedure is {@code systemctl}, on a host with no systemctl. Worse than useless: matching on the
     * fast path SKIPS the diagnosis loop, so an unrunnable procedure displaces the recon that would have
     * derived a working fix. With the filter ON the card is withheld, no card wins, and the run falls
     * back to {@code OPS RECON} — measured, both arms, same fixture.
     *
     * <p>It costs the certified refstack NOTHING, which is why default-on is safe: the only cards it
     * withholds there are the nine that declare {@code platform: kubernetes}, and a set-diff over the
     * corpus shows NONE of them can fire on that stack anyway — root-scoped identity blocks the fast
     * path (no refstack service is named for a k8s object) and the probed {@code knowStack} carries no
     * {@code kubectl} token to open the in-loop scan. Confirmed by battery: 6/6 verified, 0 rollbacks.
     *
     * <p>Known limit: this asks whether the TOOL exists, not whether it is right for the target. A Mac
     * with kubectl installed, operating a local docker stack, still passes a kubernetes card as
     * applicable. Narrower than the misfire above, and not yet observed.
     */
    private static final boolean APPLICABILITY_FILTER =
            !"off".equalsIgnoreCase(org.codezaiku.Config.get("CODEZAIKU_OPS_CARD_APPLICABILITY"));

    /**
     * {@link #missingTool} as a POLICY: the same verdict, but only when the filter is enabled. The
     * predicate stays pure so it can be tested on its own; the default-off decision lives here, at the
     * one place that acts on it.
     */
    static String withheldTool(Card c, String targetTools) {
        return APPLICABILITY_FILTER ? missingTool(c, targetTools) : null;
    }

    private static List<String> keywords(String csv) {
        return Stream.of(csv.toLowerCase(Locale.ROOT).split(","))
                .map(String::strip).filter(k -> !k.isBlank()).toList();
    }

    /**
     * Render the MATCH-ONLY cards (no {@code signature:} line) whose keywords appear in {@code stackText}.
     * Signature cards are the loop's job ({@link OpsLoop#knowledge}) — they inject on log evidence at the
     * card's declared time, and must never be dumped up-front by stack match alone.
     */
    public static String block(String dir, String stackText) {
        if (stackText == null) return "";
        String hay = stackText.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (Card c : cards(dir)) {
            if (c.signature().isEmpty() && c.match().stream().anyMatch(hay::contains)) {
                out.append(c.body()).append("\n\n");
            }
        }
        if (out.length() == 0) return "";
        return "## OPS KNOWLEDGE (general technology reference for the detected stack — not a diagnosis)\n\n" + out;
    }
}
