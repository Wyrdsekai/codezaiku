package org.codezaiku.drive;

import org.codezaiku.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Best judgement (0.3.13): which saved model a job should run on when nobody named one. The rule is plain and every pick says why.
 * Among the saved models that are reachable (a key kept where one is needed, the monthly cap not reached), drop those below the tier
 * the job needs, drop those slower than the wait the person accepts when somebody is waiting, then take the cheapest; a free local
 * model wins whenever it clears the tier and the wait. If nothing clears, the wait is relaxed first, then the cost, never the tier
 * below what is available. A private project restricts the choice to local models.
 *
 * <p>What it knows: each saved model's tier (measured defaults by family, or {@code model set <name> --tier A|B|C}), its prices, and
 * the machine's own ledger — what that model took and cost here, per kind of job, once it has run a few. The knobs:
 * {@code auto} (on by default: auto is the default once a second model is saved), {@code auto.wait_minutes} (5), {@code auto.max_dollars}
 * (none), {@code auto.local_only} (off), and the marker {@code .codezaiku/private} in a project.
 */
public final class Auto {

    private Auto() { }

    public static final String NAME = "auto";

    /** What the job is: its kind, the tier it needs, whether somebody is waiting for it, and the wait they accept in minutes (0 = any). */
    public record Job(String kind, String tier, boolean interactive, double waitMinutes, boolean privateProject) {
        public static Job of(String kind, String tier, boolean interactive, Path project) {
            return new Job(kind, tier, interactive, Config.getInt("CODEZAIKU_AUTO_WAIT_MINUTES", 5), isPrivate(project));
        }
        public Job waiting(double minutes) { return new Job(kind, tier, minutes > 0, minutes, privateProject); }
    }

    /** One candidate as the rule sees it. {@code minutes} and {@code dollars} are estimates for this job. */
    public record Candidate(SavedModels.Entry entry, String tier, boolean reachable, String whyNot, double minutes, double dollars, boolean free) { }

    /** The pick, with the reasoning a person reads. */
    public record Pick(SavedModels.Entry entry, String why) { }

    /** Whether auto is the default when no model is named: the setting is on and there is a choice to make. */
    public static boolean isDefault() {
        if (!Config.isOn("CODEZAIKU_AUTO", true)) return false;
        return SavedModels.all().size() >= 2;
    }

    /** The marker that makes a project private: only local models may read it. */
    public static boolean isPrivate(Path project) {
        if (Config.isOn("CODEZAIKU_AUTO_LOCAL_ONLY", false)) return true;
        if (project == null) return false;
        try { return Files.exists(project.resolve(".codezaiku").resolve("private")); } catch (Exception e) { return false; }
    }

    /** The tier a coding goal needs, read from its words and the project's state: A for a build from nothing, a migration, security, ML or a refactor; B for the rest of maintenance; C for a small, plain change. */
    public static String tierForCoding(String goal, boolean greenfield) {
        String g = goal == null ? "" : goal.toLowerCase(Locale.ROOT);
        if (greenfield) return "A";
        if (g.matches("(?s).*\\b(migrat|security|vulnerab|inject|refactor|architect|concurren|classif|train|model|forecast|predict|machine learning|embedding|algorithm|parser|protocol)\\w*.*")) return "A";
        if (g.length() < 300 && g.matches("(?s).*\\b(typo|rename|comment|docstring|readme|log line|message text|wording)\\b.*")) return "C";
        return "B";
    }

    static int rank(String tier) { return switch (tier == null ? "C" : tier.toUpperCase(Locale.ROOT)) { case "A" -> 3; case "B" -> 2; default -> 1; }; }

    /** The rule over the saved models, for a job. */
    public static Pick choose(Job job) {
        List<Candidate> all = new ArrayList<>();
        for (SavedModels.Entry e : SavedModels.all().values()) all.add(candidate(e, job));
        return pick(all, job);
    }

    /** One saved model as a candidate for this job: reachable or why not, and the estimated minutes and dollars from the ledger's history or the tier's defaults. */
    public static Candidate candidate(SavedModels.Entry e, Job job) {
        String whyNot = null;
        if (job.privateProject() && e.hosted()) whyNot = "the project is private";
        else if (!SavedModels.hasKey(e)) whyNot = "no key kept for " + e.host();
        else { String cap = Spend.capReached(e); if (cap != null) whyNot = "its monthly cap is reached"; }
        double[] h = Spend.average(e.model(), job.kind());
        boolean free = e.prices() == null ? !e.hosted() : (e.prices()[0] == 0 && e.prices()[1] == 0);
        // measured 2026-10-16 on the maintenance battery: hosted tier-A models 2.4–4.6 minutes a job, the local 27B 6–14
        double minutes = h != null ? h[0] : (e.hosted() ? 3 : 8) * (job.tier().equals("A") ? 1.5 : 1);
        double dollars = h != null ? h[1] : free ? 0 : e.cost(TYPICAL.get(job.kind()) != null ? TYPICAL.get(job.kind()) : TYPICAL.get("code"));
        return new Candidate(e, e.tier(), whyNot == null, whyNot, minutes, dollars, free);
    }

    /** Typical tokens of a job of each kind, for a model with no history here: prompt, cached, written, completion. */
    static final java.util.Map<String, long[]> TYPICAL = java.util.Map.of(
            "code", new long[]{300_000, 180_000, 0, 15_000}, "run", new long[]{300_000, 180_000, 0, 15_000}, "decompose", new long[]{400_000, 240_000, 0, 20_000},
            "review", new long[]{150_000, 90_000, 0, 8_000}, "research", new long[]{600_000, 400_000, 0, 30_000}, "chat", new long[]{40_000, 20_000, 0, 3_000}, "quick", new long[]{4_000, 0, 0, 300});

    /** The rule itself, pure: given the candidates. */
    public static Pick pick(List<Candidate> all, Job job) {
        List<Candidate> able = all.stream().filter(Candidate::reachable).toList();
        if (able.isEmpty()) {
            SavedModels.Entry d = SavedModels.defaultEntry();
            return new Pick(d, "auto: no saved model can take this job (" + String.join("; ", all.stream().map(c -> c.entry().name() + ": " + c.whyNot()).toList()) + "); using the default");
        }
        int need = rank(job.tier());
        int best = able.stream().mapToInt(c -> rank(c.tier())).max().orElse(1);
        int floor = Math.min(need, best);   // never above what is available
        List<Candidate> tiered = able.stream().filter(c -> rank(c.tier()) >= floor).toList();
        double maxDollars = Config.getInt("CODEZAIKU_AUTO_MAX_DOLLARS", 0);
        List<Candidate> affordable = maxDollars > 0 ? tiered.stream().filter(c -> c.dollars() <= maxDollars).toList() : tiered;
        if (affordable.isEmpty()) affordable = tiered;
        List<Candidate> quick = job.interactive() && job.waitMinutes() > 0 ? affordable.stream().filter(c -> c.minutes() <= job.waitMinutes()).toList() : affordable;
        boolean relaxed = quick.isEmpty();
        List<Candidate> pool = relaxed ? affordable : quick;
        Candidate c = pool.stream().min(java.util.Comparator.comparingDouble(Candidate::dollars).thenComparingDouble(Candidate::minutes)).get();
        StringBuilder why = new StringBuilder("auto chose " + c.entry().name() + ": tier " + c.tier() + " for a tier-" + job.tier() + " " + job.kind() + " job, about " + minutes(c.minutes()) + ", " + (c.free() ? "free" : "about " + Spend.dollars(c.dollars())));
        if (floor < need) why.append("; nothing saved is tier " + job.tier() + ", the best available is " + tier(floor));
        if (relaxed) why.append("; nothing clears the " + minutes(job.waitMinutes()) + " wait, so the wait is relaxed");
        if (job.privateProject()) why.append("; the project is private, local models only");
        for (Candidate o : all) if (o != c && (!o.reachable() || rank(o.tier()) >= floor)) {
            why.append("; ").append(o.entry().name()).append(o.reachable() ? (o.free() ? " is free" : " about " + Spend.dollars(o.dollars())) + (job.interactive() && job.waitMinutes() > 0 && o.minutes() > job.waitMinutes() ? " but about " + minutes(o.minutes()) : (o.dollars() > c.dollars() || (o.dollars() == c.dollars() && o.minutes() > c.minutes()) ? ", dearer or slower" : "")) : ": " + o.whyNot());
        }
        return new Pick(c.entry(), why.toString());
    }

    static String tier(int rank) { return rank >= 3 ? "A" : rank == 2 ? "B" : "C"; }
    static String minutes(double m) { return m < 1 ? "under a minute" : m < 2 ? "a minute" : String.format(Locale.ROOT, "%.0f minutes", m); }
}
