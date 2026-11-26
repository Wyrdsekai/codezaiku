package org.codezaiku.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.Config;
import org.codezaiku.FamiliarMain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Where a router's providers are (ported from ResearchZosho 0.8.1). OpenRouter sends a request for a model to one of the providers
 * that serve it, the cheaper ones first, and says of each provider where it is based and where its machines stand
 * ({@code /api/v1/providers}: a slug, a {@code headquarters} country, {@code datacenters} countries; some state neither). A job on a
 * hosted model sends the code it reads to that provider, so every request CodeZaiku sends to OpenRouter tells it to leave out every
 * provider based or running in a place the person avoids ({@link Avoided}; {@code provider.ignore}, {@link #ignored}).
 *
 * <p>The list is the router's own, read once a day in the background and kept beside the settings. A copy as it was read when this
 * version was made ships in the program and is what is used until the first reading succeeds and whenever none does: what is left out
 * never depends on a request that can fail. Nothing here names a provider; which ones are left out comes from the list and from the
 * places the person set. A provider that states no place is not left out, and {@link #unplaced} says how many there are.
 */
public final class OpenRouterProviders {
    private OpenRouterProviders() { }

    private static final Logger log = LoggerFactory.getLogger(OpenRouterProviders.class);
    private static final ObjectMapper M = new ObjectMapper();
    static final String LIST = "https://openrouter.ai/api/v1/providers", ENDPOINTS = "https://openrouter.ai/api/v1/models/%s/endpoints";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** One provider as the router's list states it; a place not stated is "" or none. */
    public record Provider(String slug, String name, String headquarters, List<String> datacenters) {
        /** Whether it is based, or runs machines, in one of {@code places}. */
        public boolean in(Set<String> places) {
            if (places.contains(headquarters)) return true;
            for (String d : datacenters) if (places.contains(d)) return true;
            return false;
        }
        public boolean placed() { return !headquarters.isEmpty() || !datacenters.isEmpty(); }
    }

    /**
     * A body from the router's public lists (no key is sent), or null when it cannot be read. A test stands one in, and the suite
     * reads none: the build sets the property codezaiku.openrouter.lists=off for every test.
     */
    static volatile Function<String, String> FETCH = "off".equals(System.getProperty("codezaiku.openrouter.lists")) ? url -> null : OpenRouterProviders::fetch;
    /** The clock, for the day's one reading; a test stands it in. */
    static volatile LongSupplier NOW = System::currentTimeMillis;

    private static volatile Map<String, Provider> providers;   // by slug
    private static volatile String readOn = "";                  // the day the list in use was read
    private static volatile boolean shippedInUse = true;
    private static volatile long tried;                          // when a reading was last begun
    static final long EVERY_MS = 24 * 3600_000L;

    static String fetch(String url) {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Accept", "application/json")
                    .header("User-Agent", "CodeZaiku/" + FamiliarMain.VERSION + " (https://codezaiku.org)").GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? r.body() : null;
        } catch (IOException | IllegalArgumentException e) { return null; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
    }

    /** The list as a body gives it: {@code data[] {slug, name, headquarters, datacenters[]}}; null when the body is no such list. */
    static Map<String, Provider> parse(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            Map<String, Provider> out = new LinkedHashMap<>();
            for (JsonNode p : M.readTree(body).path("data")) {
                String slug = p.path("slug").asText("").strip().toLowerCase(Locale.ROOT);
                if (slug.isEmpty()) continue;
                List<String> dcs = new ArrayList<>();
                for (JsonNode d : p.path("datacenters")) if (d.isTextual() && !d.asText().isBlank()) dcs.add(d.asText().strip().toUpperCase(Locale.ROOT));
                out.put(slug, new Provider(slug, p.path("name").asText(slug), p.path("headquarters").isTextual() ? p.path("headquarters").asText().strip().toUpperCase(Locale.ROOT) : "", List.copyOf(dcs)));
            }
            return out.size() < 5 ? null : out;   // an answer of another shape, or a list cut short, is not taken for the list
        } catch (IOException e) { return null; }
    }

    static Path kept() { return Config.home().resolve("openrouter-providers.json"); }

    /** The list in use: the last one read, else the one kept from an earlier reading, else the copy that ships. A reading is begun in the background when the day's is due. */
    static Map<String, Provider> providers() {
        Map<String, Provider> now = providers;
        if (now == null) synchronized (OpenRouterProviders.class) {
            if ((now = providers) == null) {
                Map<String, Provider> fromDisk = null;
                String day = "";
                try { if (Files.isRegularFile(kept())) { JsonNode k = M.readTree(Files.readString(kept())); fromDisk = parse(k.toString()); day = k.path("read_on").asText(""); } } catch (IOException | RuntimeException e) { fromDisk = null; }
                if (fromDisk != null) { now = fromDisk; readOn = day; shippedInUse = false; }
                else {
                    try (InputStream in = OpenRouterProviders.class.getResourceAsStream("openrouter-providers.json")) {
                        JsonNode k = M.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        now = parse(k.toString()); readOn = k.path("read_on").asText("");
                    } catch (IOException | RuntimeException e) { now = null; }
                    if (now == null) now = Map.of();
                    shippedInUse = true;
                }
                providers = now;
            }
        }
        refreshWhenDue();
        return now;
    }

    private static void refreshWhenDue() {
        long t = NOW.getAsLong();
        if (t - tried < EVERY_MS && tried != 0) return;
        synchronized (OpenRouterProviders.class) {
            if (t - tried < EVERY_MS && tried != 0) return;
            tried = t;
        }
        int begun = generation;
        Path keep = kept();   // where the list is kept, as it is now: the reading ends later, and by then a test may have another home
        Thread reader = new Thread(() -> readNow(begun, keep), "openrouter-providers");
        reader.setDaemon(true);
        reader.start();
    }

    /** One reading of the router's list: taken into use and kept when it is a list, left alone when it is not. Returns whether it was. */
    static boolean readNow() { return readNow(generation, kept()); }

    private static volatile int generation;   // counts the times the list was forgotten: a reading begun before is not taken into use after

    private static boolean readNow(int begun, Path keep) {
        String body;
        try { body = FETCH.apply(LIST); } catch (RuntimeException e) { body = null; }
        Map<String, Provider> read = parse(body);
        if (read == null) { if (body != null) log.warn("OpenRouter's list of providers came back in a shape this program does not read; the list it had stays in use"); return false; }
        String day = LocalDate.now().toString();
        synchronized (OpenRouterProviders.class) {
            if (begun != generation) return false;   // the list was forgotten while this reading went on: it is taken into use by nobody, and kept nowhere
            providers = read; readOn = day; shippedInUse = false;
            try {
                ObjectNode k = (ObjectNode) M.readTree(body);
                k.put("read_on", day);
                Files.createDirectories(keep.getParent());
                Files.writeString(keep, M.writeValueAsString(k));
            } catch (IOException | RuntimeException e) { /* in use for this process all the same */ }
        }
        return true;
    }

    /** Forgets what was read: a test's way back to the copy that ships. */
    public static void forget() { synchronized (OpenRouterProviders.class) { generation++; providers = null; readOn = ""; shippedInUse = true; tried = 0; } SERVED.clear(); }

    /**
     * The company that controls a provider, where it is based in another country than the one the router's list states for the
     * provider. The list says where a provider is registered, and seven of its 123 stated Singapore on 2026-11-20 while being the
     * international arms of companies based in mainland China. Which they are is data, not code: a dated table that ships beside the
     * providers' copy ({@code openrouter-controllers.json}, ResearchZosho's table as recorded), a row per provider with one public
     * source, the company's own site or a filing, and the words of it. A provider with no row is taken at what the router's list states.
     */
    public record Controller(String slug, String country, String company, String source) { }

    private static volatile Map<String, Controller> controllers;
    private static volatile String controllersOn = "";

    /** The table that ships: by slug. Empty when it cannot be read: then only the router's own statements count. */
    public static Map<String, Controller> controllers() {
        Map<String, Controller> c = controllers;
        if (c != null) return c;
        Map<String, Controller> out = new LinkedHashMap<>();
        try (InputStream in = OpenRouterProviders.class.getResourceAsStream("openrouter-controllers.json")) {
            if (in != null) {
                JsonNode k = M.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                controllersOn = k.path("recorded_on").asText("");
                for (JsonNode r : k.path("rows")) {
                    String slug = r.path("slug").asText("").strip().toLowerCase(Locale.ROOT), country = r.path("country").asText("").strip().toUpperCase(Locale.ROOT);
                    if (!slug.isEmpty() && country.matches("[A-Z]{2}") && !r.path("source").asText("").isBlank()) out.put(slug, new Controller(slug, country, r.path("company").asText(slug), r.path("source").asText()));
                }
            }
        } catch (IOException | RuntimeException e) { out.clear(); }
        return controllers = Map.copyOf(out);
    }

    /** The day the owners' table was recorded. */
    public static String controllersRecordedOn() { controllers(); return controllersOn; }

    /** Whether a provider is owned from one of {@code places}, by the table. */
    static boolean ownedIn(String slug, Set<String> places) { Controller c = controllers().get(slug); return c != null && places.contains(c.country()); }

    /**
     * The providers a request leaves out for the places avoided, by their slugs, in order: every one based or running in such a place
     * by the router's list, and, unless the person allowed them ({@link Avoided#byOwner}), every one owned by a company based there by
     * the table. The person's own list (CODEZAIKU_OPENROUTER_IGNORE) is added beside these where the request is written.
     */
    public static List<String> ignored() { return ignored(Avoided.places(), Avoided.byOwner()); }

    public static List<String> ignored(Set<String> places, boolean byOwner) {
        if (places.isEmpty()) return List.of();
        Set<String> out = new TreeSet<>();
        for (Provider p : providers().values()) if (p.in(places)) out.add(p.slug());
        if (byOwner) for (Provider p : providers().values()) if (ownedIn(p.slug(), places)) out.add(p.slug());   // a row of the table bears on a provider the list holds
        return List.copyOf(out);
    }

    /** The providers left out by ownership alone: owned from an avoided place, and neither based nor running there by the router's list. */
    public static List<String> ignoredByOwner(Set<String> places) {
        Set<String> out = new TreeSet<>();
        Map<String, Provider> all = providers();
        for (Controller c : controllers().values()) if (places.contains(c.country()) && all.get(c.slug()) != null && !all.get(c.slug()).in(places)) out.add(c.slug());
        return List.copyOf(out);
    }

    /** How many providers of the list state no place at all: they are not left out, since nothing says where they are. */
    public static int unplaced() { int n = 0; for (Provider p : providers().values()) if (!p.placed()) n++; return n; }

    /** How many providers the list holds, and the day it was read ("" when it never was); whether it is the copy that ships. */
    public static int count() { return providers().size(); }
    public static String readOn() { providers(); return readOn; }
    public static boolean shipped() { providers(); return shippedInUse; }

    /**
     * Who serves one model, as the router lists its endpoints: how many providers, which of them are left out, which of those by their
     * owner alone ({@code byOwner}, a part of {@code leftOut}), and how many state no place.
     */
    public record Served(String model, int providers, List<String> leftOut, List<String> byOwner, int unplaced, boolean known) {
        public Served(String model, int providers, List<String> leftOut, int unplaced, boolean known) { this(model, providers, leftOut, List.of(), unplaced, known); }
        /** Every provider that serves it is left out: it will not answer while the places stay avoided. */
        public boolean none() { return known && providers > 0 && leftOut.size() == providers; }
        /** The providers that would serve it if the ones left out by their owner alone were allowed. */
        public boolean noneButOwned() { return none() && !byOwner.isEmpty(); }
    }

    private static final Map<String, Object[]> SERVED = new ConcurrentHashMap<>();   // model → {Served, when read}

    /** The providers of {@code model}'s endpoints against the places avoided now; not known when the router's list of them cannot be read. */
    public static Served served(String model) {
        Set<String> places = Avoided.places();
        boolean byOwner = Avoided.byOwner();
        String key = model + "|" + String.join(",", places) + "|" + byOwner;
        Object[] had = SERVED.get(key);
        if (had != null && NOW.getAsLong() - (Long) had[1] < EVERY_MS) return (Served) had[0];
        Served s = new Served(model, 0, List.of(), 0, false);
        String body;
        try { body = FETCH.apply(String.format(ENDPOINTS, model)); } catch (RuntimeException e) { body = null; }
        if (body != null) try {
            Map<String, Provider> all = providers();
            Set<String> slugs = new TreeSet<>(), out = new TreeSet<>(), owned = new TreeSet<>();
            int nowhere = 0;
            JsonNode endpoints = M.readTree(body).path("data").path("endpoints");
            if (endpoints.isArray()) {
                for (JsonNode e : endpoints) {
                    String tag = e.path("tag").asText("").strip().toLowerCase(Locale.ROOT);
                    String slug = tag.contains("/") ? tag.substring(0, tag.indexOf('/')) : tag;   // a provider's slug, then its variant or region
                    if (!slug.isEmpty()) slugs.add(slug);
                }
                for (String slug : slugs) {
                    Provider p = all.get(slug);
                    String name = p == null ? slug : p.name();
                    if (p != null && p.in(places)) out.add(name);
                    else if (byOwner && ownedIn(slug, places)) { out.add(name); owned.add(name); }   // registered elsewhere, owned from a place avoided
                    else if (p == null || !p.placed()) nowhere++;
                }
                s = new Served(model, slugs.size(), List.copyOf(out), List.copyOf(owned), nowhere, true);
            }
        } catch (IOException | RuntimeException e) { /* not known */ }
        // known: kept for the day; not readable now: not asked again for ten minutes, so a list of models does not wait on each one
        SERVED.put(key, new Object[]{s, s.known() ? NOW.getAsLong() : NOW.getAsLong() - EVERY_MS + 600_000L});
        return s;
    }

    /** Non-empty pieces of a text, one after another as sentences. */
    static String sentences(List<String> pieces) {
        List<String> kept = new ArrayList<>();
        for (String p : pieces) if (p != null && !p.isBlank()) kept.add(p.strip());
        return String.join(" ", kept);
    }

    /**
     * What a person is told when nobody is left to serve a model: what happened, the places, and how to allow it by CodeZaiku's own
     * settings (the router's answer points at its own settings page, which is not where these providers were left out).
     * {@code served} not known: the router said so itself, and which providers serve the model could not be read; the person's own
     * list of providers to leave out, when there is one, is named then too, since it may be what left nobody.
     */
    public static String noneLeft(Served served) {
        String places = Avoided.names(Avoided.places());
        String what = served.known()
                ? (Avoided.byOwner()
                    ? "Every provider that serves " + served.model() + " through OpenRouter is based or runs in a place CodeZaiku keeps away from, or is owned by a company based in one, so nothing was sent."
                    : "Every provider that serves " + served.model() + " through OpenRouter is based or runs in a place CodeZaiku keeps away from, so nothing was sent.")
                : "OpenRouter found no provider for " + served.model() + " among those CodeZaiku allows: CodeZaiku leaves out the providers of the places it avoids.";
        List<String> own = DriveClient.openRouterIgnored();
        String yours = served.known() || own.isEmpty() ? ""
                : "It also leaves out the providers you named in " + DriveClient.IGNORE_NAME + " (" + String.join(", ", own) + "); codezaiku config unset " + DriveClient.IGNORE_NAME + " lets them serve.";
        String how = served.known() && served.noneButOwned()
                ? "To let the providers left out only because of the company that owns them serve it (" + String.join(", ", served.byOwner()) + "): codezaiku config set " + Avoided.BY_OWNER_NAME + " off. To allow every provider: codezaiku config set " + Avoided.SETTING_NAME + " none. Or choose another model."
                : Avoided.byOwner() && !served.known()
                ? "To allow the providers left out only because of the company that owns them: codezaiku config set " + Avoided.BY_OWNER_NAME + " off. To allow every provider: codezaiku config set " + Avoided.SETTING_NAME + " none (or name the places to keep away from). Or choose another model."
                : "To let them serve it, change the places CodeZaiku avoids: codezaiku config set " + Avoided.SETTING_NAME + " none (or name the places to keep away from), or choose another model.";
        return sentences(List.of(what, "The places: " + places + ".", yours, how));
    }

    /** How one model is served, for the line a person reads when they add it or list their models; "" when the router's list of its providers cannot be read now. */
    public static String said(String model) {
        Served s = served(model);
        if (!s.known() || s.providers() == 0) return "";
        if (s.none()) return s.noneButOwned()
                ? "Every provider of this model is left out for a place CodeZaiku avoids or for its owner's: it will not answer unless you allow the providers left out only because of the company that owns them (" + String.join(", ", s.byOwner()) + ": codezaiku config set " + Avoided.BY_OWNER_NAME + " off) or all of them (codezaiku config set " + Avoided.SETTING_NAME + " none)."
                : "Every provider of this model is based or runs in a place CodeZaiku avoids: it will not answer unless you allow them (codezaiku config set " + Avoided.SETTING_NAME + " none).";
        String by = s.providers() == 1 ? "Served by 1 provider." : "Served by " + s.providers() + " providers.";
        List<String> forPlace = new ArrayList<>(s.leftOut()); forPlace.removeAll(s.byOwner());
        String out = s.leftOut().isEmpty() ? (Avoided.places().isEmpty() ? "" : "None of them is left out.")
                : forPlace.isEmpty() ? "" : forPlace.size() == 1 ? "1 is left out for the place it is based or runs in (" + forPlace.get(0) + ")." : forPlace.size() + " are left out for the place they are based or run in (" + String.join(", ", forPlace) + ").";
        String owned = s.byOwner().isEmpty() ? "" : s.byOwner().size() == 1 ? "1 is left out for its owner's place (" + s.byOwner().get(0) + "); codezaiku config set " + Avoided.BY_OWNER_NAME + " off lets it serve."
                : s.byOwner().size() + " are left out for their owner's place (" + String.join(", ", s.byOwner()) + "); codezaiku config set " + Avoided.BY_OWNER_NAME + " off lets them serve.";
        String nowhere = s.unplaced() == 0 ? "" : s.unplaced() == 1 ? "1 states no place and is not left out." : s.unplaced() + " state no place and are not left out.";
        return sentences(List.of(by, out, owned, nowhere));
    }

    /**
     * One line on the router's list itself, for the models list: how many providers it holds, how many are left out and why, how many
     * state no place, the places, and when each list was read; and the person's own list of providers to leave out, when there is one.
     */
    public static String listSaid() {
        Set<String> places = Avoided.places();
        List<String> own = DriveClient.openRouterIgnored();
        String yours = own.isEmpty() ? "" : "Left out of every request as well, by your own list (" + DriveClient.IGNORE_NAME + "): " + String.join(", ", own) + ".";
        if (places.isEmpty()) return sentences(List.of("OpenRouter: no place is avoided, so no provider is left out for its place (settings: " + Avoided.SETTING_NAME + ").", yours));
        List<String> owned = Avoided.byOwner() ? ignoredByOwner(places) : List.of();
        String line = "OpenRouter providers: " + count() + "; left out of every request for the place they are based or run in: " + ignored(places, false).size()
                + "; stating no place, not left out: " + unplaced() + " (the list as read on " + (readOn().isEmpty() ? "an unknown day" : readOn()) + ").";
        String more = owned.isEmpty() ? "" : "Left out as well, because the company that owns them is based in such a place: " + owned.size() + " (" + String.join(", ", owned) + "); by a table recorded on "
                + controllersRecordedOn() + " with a public source for each row; codezaiku config set " + Avoided.BY_OWNER_NAME + " off lifts this.";
        return sentences(List.of(line, more, yours, "The places CodeZaiku avoids: " + Avoided.names(places) + " (settings: " + Avoided.SETTING_NAME + ")."));
    }
}
