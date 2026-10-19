package org.codezaiku.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the jobs cost (0.3.13): one line per job in {@code ~/.codezaiku/spend.jsonl} — when, which kind of job (code, review, research,
 * chat, run, acp, mcp) and where, which saved model, host and model id, the tokens and the dollars at the entry's prices — so a person
 * sees what a month on a host came to, and a cap on a saved model refuses a job before it starts. The figures are CodeZaiku's
 * arithmetic on the servers' usage counts at the prices the person wrote down, not the host's bill.
 */
public final class Spend {

    private Spend() { }

    private static final ObjectMapper M = new ObjectMapper();

    static Path file() { return Config.home().resolve("spend.jsonl"); }

    /** One job's line. {@code usage}: prompt, cached, written, completion. */
    public static synchronized void record(String kind, Path where, SavedModels.Entry entry, long[] usage, double cost) throws IOException { record(kind, where, entry, usage, cost, 0, null); }

    /** As above, with how long the job took and how it ended (success, untested, incomplete, failed), which best judgement reads back. */
    public static synchronized void record(String kind, Path where, SavedModels.Entry entry, long[] usage, double cost, long secs, String status) throws IOException { record(kind, where, entry, usage, cost, secs, status, null); }

    /** As above, with what the effort tree did on the run ({@code raised}: "thinking switch raised at turn N: why"), kept for the record a later first pick reads. */
    public static synchronized void record(String kind, Path where, SavedModels.Entry entry, long[] usage, double cost, long secs, String status, String raised) throws IOException {
        record(kind, where, entry, usage, cost, secs, status, raised, entry.model(), null);
    }

    /**
     * As above, naming the model the job actually ran on ({@code modelUsed}: the entry's, or its free twin) and a note on how that
     * changed on the way, if it did (a free twin that stayed overloaded, swapped for the paid one).
     */
    public static synchronized void record(String kind, Path where, SavedModels.Entry entry, long[] usage, double cost, long secs, String status, String raised, String modelUsed, String note) throws IOException {
        ObjectNode o = M.createObjectNode();
        if (note != null && !note.isBlank()) o.put("note", note);
        if (raised != null && !raised.isBlank()) o.put("raised", raised);
        if (secs > 0) o.put("secs", secs);
        if (status != null && !status.isBlank()) o.put("status", status);
        o.put("t", Instant.now().toString()); o.put("kind", kind == null ? "" : kind); o.put("where", where == null ? "" : where.toAbsolutePath().normalize().toString());
        o.put("entry", entry.name() == null ? "" : entry.name()); o.put("host", entry.host()); o.put("model", modelUsed == null ? entry.model() : modelUsed);
        o.put("prompt", usage[0]); o.put("cached", usage[1]); o.put("written", usage[2]); o.put("completion", usage[3]);
        o.put("cost", Math.round(cost * 10000) / 10000.0); o.put("priced", entry.prices() != null);
        Files.createDirectories(file().getParent());
        Files.writeString(file(), o.toString() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Every line, oldest first; none when nothing was recorded. */
    public static List<JsonNode> lines() {
        Path f = file();
        if (!Files.isReadable(f)) return List.of();
        List<JsonNode> out = new ArrayList<>();
        try {
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (l.isBlank()) continue;
                try { out.add(M.readTree(l)); } catch (Exception e) { /* a line that is not a record is skipped */ }
            }
        } catch (IOException e) { return List.of(); }
        return out;
    }

    /**
     * What a model took and cost here for jobs of a kind, from the ledger: {minutes, dollars, jobs}, or null before it has run three
     * such jobs with their times on record. The machine's own history, which beats any list price.
     */
    public static double[] average(String model, String kind) {
        double secs = 0, dollars = 0; int n = 0;
        for (JsonNode l : lines()) {
            if (!l.path("model").asText("").equals(model) || !l.path("kind").asText("").equals(kind) || l.path("secs").asLong(0) <= 0) continue;
            secs += l.path("secs").asLong(0); dollars += l.path("cost").asDouble(0); n++;
        }
        return n >= 3 ? new double[]{secs / n / 60.0, dollars / n, n} : null;
    }

    /** Dollars spent this calendar month on a saved model (by name) or on a host (by host name). */
    public static double monthTotal(String entryOrHost) {
        String ym = YearMonth.now().toString();
        double sum = 0;
        for (JsonNode l : lines()) {
            if (!l.path("t").asText("").startsWith(ym)) continue;
            if (entryOrHost.equalsIgnoreCase(l.path("entry").asText("")) || entryOrHost.equalsIgnoreCase(l.path("host").asText(""))) sum += l.path("cost").asDouble(0);
        }
        return sum;
    }

    /**
     * Why a job on this entry may not start, or null: its monthly cap is reached. The sentence names the figures and the command that
     * raises the cap.
     */
    public static String capReached(SavedModels.Entry e) {
        if (e == null || e.cap() <= 0 || e.name() == null || e.name().isBlank()) return null;
        double spent = monthTotal(e.name());
        if (spent < e.cap()) return null;
        return "The saved model " + e.name() + " has reached its cap for this month: " + dollars(spent) + " of " + dollars(e.cap())
                + " at its prices. Raise the cap (codezaiku model set " + e.name() + " --cap <dollars>) or choose another model.";
    }

    /** This month and all time, per host and per saved model: for the spend command. */
    public static ObjectNode summary() {
        String ym = YearMonth.now().toString();
        Map<String, double[]> byHost = new LinkedHashMap<>(), byEntry = new LinkedHashMap<>();   // {this month, all time, jobs this month, jobs all time}
        for (JsonNode l : lines()) {
            boolean thisMonth = l.path("t").asText("").startsWith(ym);
            String entry = l.path("entry").asText("");
            for (Map.Entry<Map<String, double[]>, String> part : List.of(Map.entry(byHost, l.path("host").asText("")), Map.entry(byEntry, entry.isEmpty() ? "(unnamed)" : entry))) {
                double[] v = part.getKey().computeIfAbsent(part.getValue(), k -> new double[4]);
                v[1] += l.path("cost").asDouble(0); v[3] += 1;
                if (thisMonth) { v[0] += l.path("cost").asDouble(0); v[2] += 1; }
            }
        }
        ObjectNode out = M.createObjectNode();
        out.put("month", ym);
        for (Map.Entry<String, Map<String, double[]>> part : List.of(Map.entry("hosts", byHost), Map.entry("entries", byEntry))) {
            ArrayNode a = out.putArray(part.getKey());
            for (Map.Entry<String, double[]> e : part.getValue().entrySet()) {
                ObjectNode o = a.addObject(); o.put("name", e.getKey());
                o.put("month", Math.round(e.getValue()[0] * 100) / 100.0); o.put("all", Math.round(e.getValue()[1] * 100) / 100.0);
                o.put("jobs_month", (int) e.getValue()[2]); o.put("jobs_all", (int) e.getValue()[3]);
                SavedModels.Entry en = part.getKey().equals("entries") ? SavedModels.get(e.getKey()) : null;
                if (en != null && en.cap() > 0) o.put("cap", en.cap());
            }
        }
        return out;
    }

    /** The summary as a person reads it. */
    public static String report() {
        ObjectNode s = summary();
        if (s.path("hosts").isEmpty()) return "No job has been recorded yet. Every code, review, research, chat and run job adds a line to " + file() + " with its tokens and, where the saved model has prices, its cost.";
        StringBuilder b = new StringBuilder("What the jobs cost at the prices you wrote down, this month (" + s.path("month").asText() + ") and in all — CodeZaiku's arithmetic on the servers' token counts, not a bill.\n\nBy host:\n");
        for (JsonNode r : s.path("hosts")) b.append(String.format(Locale.ROOT, "  %-34s %10s this month (%d job%s)   %10s in all (%d)%n", r.path("name").asText(), dollars(r.path("month").asDouble()), r.path("jobs_month").asInt(), r.path("jobs_month").asInt() == 1 ? "" : "s", dollars(r.path("all").asDouble()), r.path("jobs_all").asInt()));
        b.append("\nBy saved model:\n");
        for (JsonNode r : s.path("entries")) b.append(String.format(Locale.ROOT, "  %-34s %10s this month   %10s in all%s%n", r.path("name").asText(), dollars(r.path("month").asDouble()), dollars(r.path("all").asDouble()), r.has("cap") ? "   cap " + dollars(r.path("cap").asDouble()) + " a month" : ""));
        return b.toString();
    }

    /** Plain dollars with two places: "$0.13". */
    public static String dollars(double d) { return String.format(Locale.ROOT, "$%.2f", d); }

    /** "12,345 prompt (9,000 from the cache) + 678 completion tokens, $0.13". */
    public static String usageLine(long[] u, double cost, boolean priced) {
        return String.format(Locale.ROOT, "%,d prompt%s + %,d completion tokens%s", u[0], u[1] > 0 ? String.format(Locale.ROOT, " (%,d from the cache)", u[1]) : "", u[3], priced ? ", " + dollars(cost) : "");
    }
}
