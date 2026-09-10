package org.codezaiku.research;

import com.fasterxml.jackson.databind.JsonNode;
import org.codezaiku.Config;
import org.researchzosho.client.LibrarianClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * CodeZaiku's door to ResearchZosho, over HTTP through the published client. Nothing of the library
 * lives in this tree: when no daemon answers, every call here degrades to "nothing", and CodeZaiku's
 * own research memory ({@link ResearchMemory}) is what the person has. When the daemon answers, the
 * push before a turn comes from the library, {@code /research go} files a real run, and
 * {@code /librarian} asks the shelves.
 *
 * <p>Reading needs no token (the daemon's anonymous level is read). Filing a run needs a write token:
 * {@code codezaiku install researchzosho} mints one and stores it as {@code CODEZAIKU_LIBRARIAN_TOKEN}.
 */
public final class LibraryBridge {

    private LibraryBridge() { }

    private static volatile long probedAt;
    private static volatile boolean probed;

    /** Tests point the bridge at a fake daemon. */
    static volatile String urlOverride;

    /** Where the daemon is: CODEZAIKU_LIBRARIAN_URL, or 127.0.0.1 on CODEZAIKU_LIBRARIAN_PORT (4649). */
    public static String url() {
        if (urlOverride != null) return urlOverride;
        String u = Config.get("CODEZAIKU_LIBRARIAN_URL", "");
        if (u != null && !u.isBlank()) return u.replaceAll("/+$", "");
        return "http://127.0.0.1:" + Config.get("CODEZAIKU_LIBRARIAN_PORT", "4649");
    }

    public static String token() {
        String t = Config.get("CODEZAIKU_LIBRARIAN_TOKEN", "");
        return t == null || t.isBlank() ? null : t;
    }

    public static LibrarianClient client() {
        return new LibrarianClient(URI.create(url()), token(), "codezaiku", Duration.ofSeconds(60));
    }

    /** Whether a daemon answers on {@link #url()}; probed at most once every 20 seconds. */
    public static boolean answers() {
        long now = System.currentTimeMillis();
        if (now - probedAt < 20_000) return probed;
        boolean ok = false;
        try {
            var c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            var r = c.send(HttpRequest.newBuilder(URI.create(url() + "/v1/status")).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            ok = r.statusCode() == 200 && r.body().contains("library_id");
        } catch (Exception ignored) { }
        probed = ok; probedAt = now;
        return ok;
    }

    /** Forget the probe result (after an install, a service start). */
    public static void reprobe() { probedAt = 0; }

    /**
     * The library's push: what the shelves hold on {@code question}, rendered, ready to ride into a
     * prompt; "" when no daemon answers, when the library holds nothing, or on any error. Never throws.
     */
    public static String push(String question, int k) {
        if (!answers()) return "";
        try {
            JsonNode r = client().ask(question, k);
            if (r.path("holds_nothing").asBoolean(false)) return "";
            String s = r.path("rendered").asText("");
            return s.isBlank() ? "" : s.stripTrailing() + "\n\n";
        } catch (Exception e) {
            return "";
        }
    }

    /** The desk's answer package for a person to read: the entries themselves, or why there are none. */
    public static String answer(String question, int k) {
        if (!answers()) return "no library answers on " + url() + " — /setup librarian installs ResearchZosho, or start its service";
        try {
            JsonNode r = client().ask(question, k);
            String s = r.path("rendered").asText("");
            if (r.path("holds_nothing").asBoolean(false) || s.isBlank()) return "the library holds nothing on that";
            return s;
        } catch (Exception e) {
            return "! the library could not answer: " + e.getMessage();
        }
    }

    public static String compress(String s, int max) {
        String t = s == null ? "" : s.strip().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
