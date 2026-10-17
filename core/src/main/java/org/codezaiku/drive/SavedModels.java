package org.codezaiku.drive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.Config;
import org.codezaiku.drive.anthropic.Claude;
import org.codezaiku.drive.aws.Bedrock;
import org.codezaiku.drive.openai.OpenAI;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The models CodeZaiku can run a job on, by name (0.3.13): each a server address, a model id, prices per million tokens, an effort and
 * a monthly cap, chosen per job — {@code codezaiku code … --model deepseek}, {@code /model fable} in the chat, the {@code model}
 * argument of the MCP tools, the model option of an ACP session, {@code run --model}. "DeepSeek through OpenRouter for this task,
 * the local 27B for that one, Claude for the careful one." Keys are kept once per host ({@code key.<host>}), so a key goes to its
 * own host and nowhere else; the current {@code drive} and {@code model} settings are the entry called {@code default}, so a
 * machine with nothing saved works as before.
 *
 * <p>Settings: {@code model.<name>.url} (what {@code codezaiku model add} has always written), {@code .id} (the model id the server
 * selects by), {@code .prices} ("in/out" or "in/out/cache" dollars per million tokens), {@code .effort}, {@code .cap} (dollars a
 * month), {@code .note}.
 */
public final class SavedModels {

    private SavedModels() { }

    public static final String DEFAULT = "default";
    static final String PREFIX = "CODEZAIKU_MODEL_";
    private static final List<String> FIELDS = List.of("URL", "ID", "PRICES", "EFFORT", "CAP", "NOTE", "TIER");

    /** One saved model: where, which, what it costs. {@code prices} are dollars per million tokens — in, out, cache read — or null when unknown. */
    public record Entry(String name, String drive, String model, double[] prices, String effort, double cap, String note, String tierSet) {
        public Entry(String name, String drive, String model, double[] prices, String effort, double cap, String note) { this(name, drive, model, prices, effort, cap, note, null); }
        /** The model's tier for best judgement: set by hand, else the measured default for its family, else C. */
        public String tier() { return tierSet != null && !tierSet.isBlank() ? tierSet.toUpperCase(Locale.ROOT) : ModelTiers.of(model); }
        public String host() { String h = DriveClient.hostOf(drive); return h == null ? (drive == null ? "" : drive) : h; }
        /** A metered API, as against a server of one's own. */
        public boolean hosted() { return DriveClient.hostedApi(drive) || Claude.is(drive) || OpenAI.is(drive) || Bedrock.is(drive); }
        /**
         * What tokens cost on this entry, in dollars; 0 when no prices are known. {@code usage}: prompt tokens (which include the cached
         * ones), tokens read from the server's cache, tokens written to it, completion tokens. A token written to the cache is priced as
         * input; the Claude API bills it at a quarter more, which the figures here leave out.
         */
        public double cost(long[] usage) {
            if (prices == null || usage == null) return 0;
            long cached = Math.max(0, usage[1]), uncached = Math.max(0, usage[0] - cached);
            double cacheRead = prices.length > 2 ? prices[2] : prices[0];
            return (uncached * prices[0] + cached * cacheRead + Math.max(0, usage[3]) * prices[1]) / 1e6;
        }
        public String pricesText() { return prices == null ? "" : trim(prices[0]) + "/" + trim(prices[1]) + (prices.length > 2 ? "/" + trim(prices[2]) : ""); }
        static String trim(double d) { String s = String.format(Locale.ROOT, "%.4f", d); s = s.replaceAll("0+$", "").replaceAll("\\.$", ""); return s.isEmpty() ? "0" : s; }
        /** A line a person reads: "deepseek: deepseek/deepseek-v4.1-flash via openrouter.ai — $0.024/0.72 per M". */
        public String line() {
            return name + ": " + model + " via " + host() + (prices == null ? "" : " — $" + pricesText() + " per M") + (effort == null || effort.isBlank() ? "" : ", effort " + effort)
                    + (cap > 0 ? ", cap $" + trim(cap) + " a month" : "") + ", tier " + tier() + (note == null || note.isBlank() ? "" : " (" + note + ")");
        }
        /** "deepseek (deepseek/deepseek-v4.1-flash via openrouter.ai)", or the model and host alone for an unnamed choice. */
        public String describe() { return (name == null || name.isBlank() ? "" : name + " (") + model + " via " + host() + (name == null || name.isBlank() ? "" : ")"); }
    }

    /** A name as the settings keep it: lower case, letters, digits and hyphens. */
    public static String nameOk(String name) {
        if (name == null) return "A saved model needs a name.";
        String n = name.strip().toLowerCase(Locale.ROOT);
        if (!n.matches("[a-z0-9][a-z0-9-]{0,39}")) return "A saved model's name is letters, digits and hyphens, up to forty: deepseek, local-27b, fable.";
        return "";
    }

    static String key(String name, String field) { return PREFIX + name.strip().toLowerCase(Locale.ROOT).replace('-', '_').toUpperCase(Locale.ROOT) + "_" + field.toUpperCase(Locale.ROOT); }

    /** The saved models by name, the {@code default} one first (the drive and model settings as they stand). */
    public static Map<String, Entry> all() {
        Map<String, Entry> out = new LinkedHashMap<>();
        out.put(DEFAULT, defaultEntry());
        TreeSet<String> names = new TreeSet<>();
        for (String k : Config.keysWithPrefix(PREFIX)) {
            String rest = k.substring(PREFIX.length());
            int cut = rest.lastIndexOf('_');
            if (cut <= 0) continue;
            if (!FIELDS.contains(rest.substring(cut + 1))) continue;   // CODEZAIKU_MODEL_DEFAULT is the host-default channel, not a saved model
            names.add(rest.substring(0, cut).toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        for (String n : names) { if (n.equals(DEFAULT)) continue; Entry e = get(n); if (e != null) out.put(n, e); }
        return out;
    }

    /** The entry called {@code default}: the drive and model settings, with the prices, effort and cap kept under that name when any are. */
    public static Entry defaultEntry() {
        String drive = Config.get("CODEZAIKU_DRIVE", "http://localhost:8200"), model = Config.get("CODEZAIKU_MODEL", "local-model");
        return new Entry(DEFAULT, drive, model, prices(Config.get(key(DEFAULT, "PRICES"))), effortOf(Config.get(key(DEFAULT, "EFFORT"))), capOf(Config.get(key(DEFAULT, "CAP"))), Config.get(key(DEFAULT, "NOTE"), ""), Config.get(key(DEFAULT, "TIER")));
    }

    /** The saved model by name, {@code default} included; null when there is none. A saved address with no id sends the model setting, which a local server ignores. */
    public static Entry get(String name) {
        if (name == null || name.isBlank() || name.strip().equalsIgnoreCase(DEFAULT)) return defaultEntry();
        String n = name.strip().toLowerCase(Locale.ROOT);
        if (!nameOk(n).isEmpty()) return null;
        String drive = Config.get(key(n, "URL")), id = Config.get(key(n, "ID"));
        if (drive == null || drive.isBlank()) return null;
        return new Entry(n, Config.driveBase(drive), id == null || id.isBlank() ? Config.get("CODEZAIKU_MODEL", "local-model") : id.strip(),
                prices(Config.get(key(n, "PRICES"))), effortOf(Config.get(key(n, "EFFORT"))), capOf(Config.get(key(n, "CAP"))), Config.get(key(n, "NOTE"), ""), Config.get(key(n, "TIER")));
    }

    /** Adds or changes a saved model; a null field is left as it is. The name must pass {@link #nameOk}; {@code default} is the settings themselves. */
    public static Entry put(String name, String drive, String model, String prices, String effort, String cap, String note) throws IOException { return put(name, drive, model, prices, effort, cap, note, null); }

    /** As above, with the tier (A, B or C, or blank to go back to the measured default). */
    public static Entry put(String name, String drive, String model, String prices, String effort, String cap, String note, String tier) throws IOException {
        String why = nameOk(name);
        if (name != null && name.strip().equalsIgnoreCase(Auto.NAME)) throw new IllegalArgumentException("The name auto is best judgement itself: codezaiku code <project> \"…\" --model auto.");
        if (!why.isEmpty()) throw new IllegalArgumentException(why);
        String n = name.strip().toLowerCase(Locale.ROOT);
        if (drive != null) {
            String address = hostAddress(drive.strip());
            if (!address.matches("^https?://\\S+$") && !Bedrock.is(address))
                throw new IllegalArgumentException("The address must start with http:// or https://, or be a host word: anthropic, openai, openrouter, gemini, nebius, huggingface, deepseek, moonshot.");
            Config.set(n.equals(DEFAULT) ? "CODEZAIKU_DRIVE" : key(n, "URL"), Bedrock.is(address) ? address : Config.driveBase(address));
        }
        if (model != null) Config.set(n.equals(DEFAULT) ? "CODEZAIKU_MODEL" : key(n, "ID"), model.strip());
        if (prices != null) {
            if (!prices.isBlank() && prices(prices) == null) throw new IllegalArgumentException("Prices are dollars per million tokens as in/out or in/out/cache, for example 0.024/0.72/0.005.");
            setOrUnset(key(n, "PRICES"), prices.strip());
        }
        if (effort != null) setOrUnset(key(n, "EFFORT"), effort.strip().toLowerCase(Locale.ROOT));
        if (cap != null) { if (!cap.isBlank() && capOf(cap) < 0) throw new IllegalArgumentException("A cap is dollars a month, for example 20."); setOrUnset(key(n, "CAP"), cap.strip()); }
        if (note != null) setOrUnset(key(n, "NOTE"), note.strip());
        if (tier != null) { String t = tier.strip().toUpperCase(Locale.ROOT); if (!t.isEmpty() && !t.matches("[ABC]")) throw new IllegalArgumentException("A tier is A, B or C."); setOrUnset(key(n, "TIER"), t); }
        Entry e = get(n);
        if (e == null) throw new IllegalArgumentException("A saved model needs an address: codezaiku model add " + n + " <address> [<model id>].");
        return e;
    }

    private static void setOrUnset(String key, String value) throws IOException { if (value.isEmpty()) Config.unset(key); else Config.set(key, value); }

    /** Takes a saved model out; the default cannot be removed. Returns whether there was one. */
    public static boolean remove(String name) throws IOException {
        String n = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        if (n.isEmpty() || n.equals(DEFAULT)) throw new IllegalArgumentException("The default is the drive and model settings; change those instead (codezaiku model use …).");
        boolean any = false;
        for (String f : FIELDS) any |= Config.unset(key(n, f));
        return any;
    }

    /** The words a host goes by, as an address; anything else is taken as the address it is. */
    public static String hostAddress(String word) {
        String w = word.strip().toLowerCase(Locale.ROOT);
        return switch (w) {
            case "anthropic", "claude" -> "https://api.anthropic.com";
            case "openai" -> OpenAI.BASE;
            case "openrouter" -> "https://openrouter.ai/api/v1";
            case "gemini", "google" -> "https://generativelanguage.googleapis.com/v1beta/openai";
            case "nebius" -> "https://api.tokenfactory.nebius.com/v1";
            case "huggingface", "hf" -> "https://router.huggingface.co/v1";
            case "deepseek" -> "https://api.deepseek.com/v1";
            case "moonshot", "kimi" -> "https://api.moonshot.ai/v1";
            default -> word.strip();
        };
    }

    static double[] prices(String s) {
        if (s == null || s.isBlank()) return null;
        String[] p = s.strip().split("/");
        if (p.length < 2 || p.length > 3) return null;
        try { double[] out = new double[p.length]; for (int i = 0; i < p.length; i++) { out[i] = Double.parseDouble(p[i].strip().replace("$", "")); if (out[i] < 0) return null; } return out; }
        catch (NumberFormatException e) { return null; }
    }

    static double capOf(String s) { if (s == null || s.isBlank()) return 0; try { double d = Double.parseDouble(s.strip().replace("$", "")); return d < 0 ? -1 : d; } catch (NumberFormatException e) { return -1; } }

    /** The entry's own effort, else the setting every drive reads (CODEZAIKU_REASONING_EFFORT), else none. */
    static String effortOf(String own) {
        if (own != null && !own.isBlank()) return own.strip();
        return Config.get("CODEZAIKU_REASONING_EFFORT", "");
    }

    /** Every saved model as a list, for a protocol or a page. The key itself is never in it, only whether one is kept. */
    public static ArrayNode list(ObjectMapper m) {
        ArrayNode out = m.createArrayNode();
        for (Entry e : all().values()) {
            ObjectNode o = out.addObject();
            o.put("name", e.name()); o.put("drive", e.drive()); o.put("model", e.model()); o.put("host", e.host()); o.put("hosted", e.hosted());
            if (e.prices() != null) o.put("prices", e.pricesText());
            if (e.effort() != null && !e.effort().isBlank()) o.put("effort", e.effort());
            if (e.cap() > 0) o.put("cap", e.cap());
            o.put("tier", e.tier());
            if (e.note() != null && !e.note().isBlank()) o.put("note", e.note());
            o.put("key", hasKey(e));
            o.put("line", e.line());
        }
        return out;
    }

    /** Whether a key is kept that would reach this entry's host: its own, or the one key setting. A local server needs none. */
    public static boolean hasKey(Entry e) {
        if (!e.hosted()) return true;
        String k = DriveClient.keyFor(e.drive());
        return k != null && !k.isBlank();
    }

    /** The names, for a picker or a usage line. */
    public static List<String> names() { return new ArrayList<>(all().keySet()); }

    // ---- choosing one for a job ----

    /** What a job runs on: the entry (named, or the default, or an unnamed one made of a drive and model given by hand), and its drive and model. */
    public record Choice(Entry entry, String drive, String model, String why) {
        public Choice(Entry entry, String drive, String model) { this(entry, drive, model, null); }
        public boolean named() { return entry.name() != null && !entry.name().isBlank(); }
    }

    /** Best judgement for a job: the pick and its reasoning as a choice. */
    public static Choice choose(Auto.Job job) {
        Auto.Pick p = Auto.choose(job);
        return new Choice(p.entry(), p.entry().drive(), p.entry().model(), p.why());
    }

    /**
     * The model for a job. {@code modelArg} may be a saved name, which brings its own address, id and effort; else it is a model id
     * on {@code driveArg} (or the drive setting). A drive and model given by hand that are the default's get the default's prices;
     * any other pair is priced by a saved model with the same address and id when there is one, else not at all.
     */
    public static Choice choose(String driveArg, String modelArg) {
        String want = modelArg == null ? "" : modelArg.strip();
        if (!want.isEmpty() && nameOk(want).isEmpty()) {
            Entry e = get(want);
            if (e != null) return new Choice(e, e.drive(), e.model());
        }
        String drive = Config.driveBase(driveArg == null || driveArg.isBlank() ? Config.get("CODEZAIKU_DRIVE", "http://localhost:8200") : driveArg);
        String model = want.isEmpty() ? Config.get("CODEZAIKU_MODEL", "local-model") : want;
        for (Entry e : all().values()) if (sameDrive(e.drive(), drive) && e.model().equals(model)) return new Choice(e, drive, model);
        return new Choice(new Entry("", drive, model, null, effortOf(null), 0, ""), drive, model);
    }

    private static boolean sameDrive(String a, String b) {
        if (a == null || b == null) return false;
        return Config.driveBase(a).replaceAll("/+$", "").equalsIgnoreCase(Config.driveBase(b).replaceAll("/+$", ""));
    }
}
