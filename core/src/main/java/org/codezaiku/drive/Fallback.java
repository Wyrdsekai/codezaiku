package org.codezaiku.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.codezaiku.Config;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * The fallback (setting {@code fallback}): the saved model a job runs on when the one it would run on does not answer or is busy.
 * "Use the box down the hall, and this machine's own model when that one is crowded or off."
 *
 * <p>Asked once at a job's start, for the model the job chose (named, the default, or auto's pick), and only when a fallback is set:
 * a machine without one makes no request here at all. Three short questions, a few seconds each:
 * <ul>
 *   <li>does it answer: its model list ({@code /v1/models}); no reply, or a server error, is "does not answer". A hosted API is not
 *       asked: it answers.</li>
 *   <li>is it busy: a llama.cpp server whose every slot is working ({@code /slots}, each slot's {@code is_processing}). Behind a
 *       llama-swap proxy the slots are asked at {@code /upstream/<model>/slots}, and ONLY when the proxy's {@code /running} already
 *       lists the model as loaded: a request to {@code /upstream/<model>/…} for a model that is not loaded starts it, which is 13 GB
 *       on a card (learned on the machine this was written on). A proxy whose model is not loaded is free; a server that shows no
 *       slots is not busy.</li>
 * </ul>
 * The job moves to the fallback when its own model is not available and the fallback answers. A busy fallback is still taken when the
 * job's own model does not answer at all, since a busy server queues the job and a silent one fails it; when both are busy, or both
 * are silent, the job stays where it was. Either way one line says what was found and where the job runs.
 */
public final class Fallback {

    private Fallback() { }

    /** The setting. Unset, {@code off} or {@code none}: no fallback, and nothing is asked. */
    public static final String SETTING = "CODEZAIKU_FALLBACK";

    /** Tests set this in place of the setting (the suite pins the setting off, so the machine's own never reaches a test); null reads it. */
    public static volatile String NAMED_FOR_TESTS;

    /** How long each question may take. */
    static final Duration WAIT = Duration.ofSeconds(3);

    private static final ObjectMapper J = new ObjectMapper();

    /** The saved model's name the setting names, or null when there is none. */
    public static String named() {
        String v = NAMED_FOR_TESTS != null ? NAMED_FOR_TESTS : Config.get(SETTING);
        if (v == null || v.isBlank()) return null;
        String n = v.strip().toLowerCase(Locale.ROOT);
        return n.equals("off") || n.equals("none") ? null : n;
    }

    /** What a server said about itself: whether it answers (and what it answered when it did not), and its calls in use of its slots. */
    public record Reading(boolean answers, String trouble, int working, int slots) {
        static final Reading FREE = new Reading(true, null, 0, 0);
        public boolean busy() { return answers && slots > 0 && working >= slots; }
        public boolean available() { return answers && !busy(); }
        /** "does not answer", "is not ready (it answered 503)", "is busy (4 of 4 calls in use)", "answers". */
        public String words() {
            if (!answers) return trouble == null ? "does not answer" : "is not ready (" + trouble + ")";
            if (busy()) return "is busy (" + calls() + ")";
            return "answers";
        }
        String calls() { return working + " of " + slots + " call" + (slots == 1 ? "" : "s") + " in use"; }
    }

    /** What a job runs on after the check, and the line that says why it moved or why it could not; the line is null when there is nothing to say. */
    public record Outcome(SavedModels.Choice choice, String line) {
        public boolean moved(SavedModels.Choice from) { return choice != from; }
    }

    /**
     * The check itself, for a job that chose {@code chosen}. With no fallback set, or the job already on it, nothing is asked and the
     * choice comes back as it was, with no line.
     */
    public static Outcome decide(SavedModels.Choice chosen, Auto.Job job) {
        String name = named();
        if (name == null || chosen == null) return new Outcome(chosen, null);
        SavedModels.Entry fb = SavedModels.get(name);
        String who = label(chosen.entry(), chosen.drive(), chosen.model(), false);
        if (fb == null)
            return new Outcome(chosen, "The fallback setting names " + name + ", and no saved model has that name, so this job runs on " + who
                    + " with no fallback. `codezaiku model list` shows the names; `codezaiku config set fallback <name>` sets it.");
        if (same(fb, chosen)) return new Outcome(chosen, null);
        Reading first = read(chosen.drive(), chosen.model(), chosen.entry().hosted());
        if (first.available()) return new Outcome(chosen, null);
        String fbLabel = label(fb, fb.drive(), fb.model(), true);
        String cannot = job == null ? null : Auto.candidate(fb, job).whyNot();
        if (cannot != null)
            return new Outcome(chosen, who + " " + first.words() + ", and the fallback " + fbLabel + " cannot take it (" + cannot + "), so this job stays on " + who + ".");
        Reading second = read(fb.drive(), fb.model(), fb.hosted());
        SavedModels.Choice onFallback = new SavedModels.Choice(fb, fb.drive(), fb.model(), "fallback " + fb.name() + ": " + who + " " + first.words());
        if (second.available())
            return new Outcome(onFallback, who + " " + first.words() + ", so this job runs on " + fbLabel + ".");
        if (!first.answers() && second.answers())   // a busy server queues the job; a silent one fails it
            return new Outcome(onFallback, who + " " + first.words() + ", so this job runs on " + fbLabel + ", which is busy too (" + second.calls() + "): the job waits there for a free call.");
        String either = !second.answers() ? second.words() + (first.answers() ? "" : " either") : "is busy too (" + second.calls() + ")";
        return new Outcome(chosen, who + " " + first.words() + ", and the fallback " + fbLabel + " " + either + ", so this job stays on " + who
                + (first.busy() ? " and waits for a free call." : "."));
    }

    /** Whether the job's choice is the fallback already: the same saved name, or the same address and model id. */
    static boolean same(SavedModels.Entry fb, SavedModels.Choice c) {
        String n = c.entry().name();
        if (n != null && !n.isBlank() && n.equalsIgnoreCase(fb.name())) return true;
        return sameDrive(fb.drive(), c.drive()) && fb.model().equals(c.model());
    }

    private static boolean sameDrive(String a, String b) {
        if (a == null || b == null) return false;
        return Config.driveBase(a).replaceAll("/+$", "").equalsIgnoreCase(Config.driveBase(b).replaceAll("/+$", ""));
    }

    /**
     * A model as the line names it: its saved name ("homebox"), or the saved name of the same address and id when the job came by the
     * default ({@code model use homebox} makes the default the same server), else the model and where it is. {@code withModel} adds
     * the model id to a name: "local (gpt-oss-20b)".
     */
    static String label(SavedModels.Entry e, String drive, String model, boolean withModel) {
        String name = e == null ? null : e.name();
        if (name == null || name.isBlank() || name.equals(SavedModels.DEFAULT)) {
            String twin = null;
            for (SavedModels.Entry s : SavedModels.all().values())
                if (!s.name().equals(SavedModels.DEFAULT) && sameDrive(s.drive(), drive) && s.model().equals(model)) { twin = s.name(); break; }
            if (twin == null) {
                String where = model + " at " + where(drive);
                return name != null && name.equals(SavedModels.DEFAULT) ? "the default model (" + where + ")" : where;
            }
            name = twin;
        }
        return withModel ? name + " (" + model + ")" : name;
    }

    /** "homebox:8210", from an address; the address itself when it is not one. */
    static String where(String drive) {
        try {
            URI u = URI.create(drive.strip());
            if (u.getHost() == null) return drive;
            return u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (RuntimeException e) { return String.valueOf(drive); }
    }

    // ---- asking a server ----

    /** What the server at {@code drive} says now about serving {@code model}. A hosted API, or an address that is not http, is not asked. */
    public static Reading read(String drive, String model, boolean hosted) {
        if (hosted || drive == null || !drive.regionMatches(true, 0, "http", 0, 4)) return Reading.FREE;
        String base = Config.driveBase(drive).replaceAll("/+$", "");
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(WAIT).build();
        HttpResponse<String> models = get(http, Config.v1(base) + "/models");
        if (models == null) return new Reading(false, null, 0, 0);
        if (models.statusCode() >= 500) return new Reading(false, "it answered " + models.statusCode(), 0, 0);
        String slotsAt = base + "/slots";
        HttpResponse<String> running = get(http, base + "/running");
        JsonNode list = running == null || running.statusCode() != 200 ? null : tree(running.body()).path("running");
        if (list != null && list.isArray()) {
            // llama-swap: its own /slots is not the model's. Ask the model's only when the proxy already holds it loaded.
            if (!loaded(list, model)) return Reading.FREE;
            slotsAt = base + "/upstream/" + URLEncoder.encode(model, StandardCharsets.UTF_8).replace("+", "%20") + "/slots";
        }
        HttpResponse<String> slots = get(http, slotsAt);
        if (slots == null || slots.statusCode() != 200) return Reading.FREE;
        JsonNode arr = tree(slots.body());
        if (!arr.isArray() || arr.isEmpty()) return Reading.FREE;
        int working = 0;
        for (JsonNode s : arr) if (s.has("is_processing") ? s.path("is_processing").asBoolean(false) : s.path("state").asInt(0) != 0) working++;
        return new Reading(true, null, working, arr.size());
    }

    /** Whether llama-swap's /running list holds the model, loaded and ready (a model still starting is not asked: it is not busy yet). */
    static boolean loaded(JsonNode running, String model) {
        for (JsonNode r : running) {
            if (!model.equals(r.path("model").asText(""))) continue;
            String state = r.path("state").asText("");
            return state.isEmpty() || state.equalsIgnoreCase("ready");
        }
        return false;
    }

    private static HttpResponse<String> get(HttpClient http, String url) {
        try {
            HttpRequest req = DriveClient.auth(HttpRequest.newBuilder(URI.create(url))).timeout(WAIT).GET().build();
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonNode tree(String body) {
        try { return J.readTree(body == null ? "" : body); } catch (Exception e) { return J.missingNode(); }
    }
}
