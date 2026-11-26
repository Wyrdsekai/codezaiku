package org.codezaiku.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.codezaiku.Config;
import org.codezaiku.ResearchZoshoInstall;
import org.researchzosho.client.LibrarianClient;
import org.researchzosho.client.LibraryException;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * CodeZaiku's door to ResearchZosho, over HTTP through the published client. Nothing of the library
 * lives in this tree: when no daemon answers, every call here degrades to "nothing", and CodeZaiku's
 * own research memory ({@link ResearchMemory}) is what the person has. When the daemon answers, the
 * push before a turn comes from the library, {@code /research go} files a real run, and
 * {@code /librarian} asks the shelves.
 *
 * <p>Whether a library answers and whether it lets this program in are two things. A library answers when its health address
 * ({@code /healthz}, from ResearchZosho 0.7.0, asked with no token) says it is up, or, for an older one, when it answers
 * {@code /v1/status} at all; it lets CodeZaiku in when {@code /v1/status}, asked with CodeZaiku's token as every call of the client
 * is, answers. A library whose keeper refuses anonymous readers (catalog/patrons.md, {@code default: deny}) answers 403 to a call
 * without a token: until 0.3.21 that was taken for "no library", and every use of it was skipped without a word. {@code codezaiku
 * install researchzosho} mints a token and stores it as {@code CODEZAIKU_LIBRARIAN_TOKEN}; {@code --token} makes a new one.
 */
public final class LibraryBridge {

    private LibraryBridge() { }

    private static volatile long probedAt;
    private static volatile Seen seen = new Seen(Door.NONE, "");

    /** Tests point the bridge at a fake daemon, and give it a token of their own. */
    static volatile String urlOverride, tokenOverride;

    /** What the library is to this program: no library answers; it answers and lets CodeZaiku in; it answers and refuses CodeZaiku. */
    public enum Door { NONE, IN, REFUSED }

    /** {@code why}: for NONE what answered or did not, for REFUSED the library's own words; "" when CodeZaiku is let in. */
    public record Seen(Door door, String why) { }

    /** Where the daemon is: CODEZAIKU_LIBRARIAN_URL, or 127.0.0.1 on CODEZAIKU_LIBRARIAN_PORT (4649). */
    public static String url() {
        if (urlOverride != null) return urlOverride;
        String u = Config.get("CODEZAIKU_LIBRARIAN_URL", "");
        if (u != null && !u.isBlank()) return u.replaceAll("/+$", "");
        return "http://127.0.0.1:" + Config.get("CODEZAIKU_LIBRARIAN_PORT", "4649");
    }

    public static String token() {
        if (tokenOverride != null) return tokenOverride.isBlank() ? null : tokenOverride;
        String t = Config.get("CODEZAIKU_LIBRARIAN_TOKEN", "");
        return t == null || t.isBlank() ? null : t;
    }

    public static LibrarianClient client() {
        return new LibrarianClient(URI.create(url()), token(), "codezaiku", Duration.ofSeconds(60));
    }

    /** Whether a library answers on {@link #url()} and lets this program read it: the one question the silent uses ask. */
    public static boolean answers() { return seen().door() == Door.IN; }

    /** What the library is to this program; looked at at most once every 20 seconds. */
    public static Seen seen() {
        long now = System.currentTimeMillis();
        if (now - probedAt < 20_000) return seen;
        Seen s = look();
        seen = s; probedAt = now;
        return s;
    }

    /** The look itself: the health address with no token, then {@code /v1/status} through the client, which sends the token. */
    static Seen look() {
        String u = url();
        try {
            var c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            var r = c.send(HttpRequest.newBuilder(URI.create(u + "/healthz")).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 503) {   // a ResearchZosho that is up and does not take people now, and says why
                String why = "";
                try { why = new ObjectMapper().readTree(r.body()).path("why").asText(""); } catch (Exception notJson) { }
                return new Seen(Door.NONE, "the library at " + u + " does not take requests now" + (why.isBlank() ? "" : ": " + why));
            }
            // 200 is a ResearchZosho from 0.7.0 that is up; anything else may be an older one, which has no health address: its status says
        } catch (Exception nothing) {
            String words = systemWords(nothing);
            return new Seen(Door.NONE, words.isEmpty() ? "nothing answers at " + u : "could not reach " + u + ": " + words);
        }
        try {
            JsonNode st = new LibrarianClient(URI.create(u), token(), "codezaiku", Duration.ofSeconds(5)).status();
            return st.has("library_id") ? new Seen(Door.IN, "") : new Seen(Door.NONE, "what answers at " + u + " is not a ResearchZosho");
        } catch (LibraryException e) {
            if (e.status == 401 || e.status == 403) return new Seen(Door.REFUSED, e.getMessage() == null ? "" : e.getMessage());
            return new Seen(Door.NONE, "the library at " + u + " did not answer: " + e.getMessage());
        } catch (RuntimeException e) {
            return new Seen(Door.NONE, "the library at " + u + " did not answer: " + e.getMessage());
        }
    }

    /** Forget the probe result (after an install, a service start, a new token). */
    public static void reprobe() { probedAt = 0; }

    /**
     * Whether the library CodeZaiku is set to use is on another machine: its address names a host that is not this machine's loopback.
     * Such a library is used as one here is; what differs is where it is set up and where CodeZaiku's sign-in for it is made.
     */
    public static boolean elsewhere() {
        String h = hostOf(url());
        return h != null && !(h.equals("localhost") || h.equals("::1") || h.equals("[::1]") || h.startsWith("127."));
    }

    /** Whether a ResearchZosho was set to be configured at all: an address or a port given, rather than the default on this machine. */
    public static boolean configured() {
        return urlOverride != null || !Config.get("CODEZAIKU_LIBRARIAN_URL", "").isBlank() || !Config.get("CODEZAIKU_LIBRARIAN_PORT", "").isBlank();
    }

    /** Whether the library CodeZaiku uses is the one installed on this machine, so that this machine's researchzosho command reaches it. */
    public static boolean keptHere() { return !elsewhere() && installedHere(); }

    private static String hostOf(String url) {
        try { String h = URI.create(url).getHost(); return h == null ? null : h.toLowerCase(Locale.ROOT); } catch (RuntimeException e) { return null; }
    }

    /**
     * The commands that let CodeZaiku in. With the library on this machine, one command makes CodeZaiku's sign-in and stores it.
     * With the library elsewhere, two are run on the machine that keeps it, the second writing the sign-in into a file, and one here
     * reads that file, so the sign-in shows on no screen and in no shell's history.
     */
    public static String fixCommand(boolean keptHere) {
        return keptHere ? "codezaiku install researchzosho --token"
                : "on the computer that keeps the library, run researchzosho reader allow did:key:local-codezaiku write codezaiku and then "
                  + "researchzosho reader token did:key:local-codezaiku > codezaiku-signin.txt; copy that file here, run "
                  + "codezaiku config set CODEZAIKU_LIBRARIAN_TOKEN --file codezaiku-signin.txt, and delete the file";
    }

    /** What a person reads when the library refuses CodeZaiku: that it answers, what it refused, and the command that fixes it. */
    public static String refusedLine(String why, boolean keptHere) {
        String what = token() == null ? "CodeZaiku has not been given a sign-in for it (the setting CODEZAIKU_LIBRARIAN_TOKEN is empty)"
                : "it does not accept the sign-in CodeZaiku has for it (CODEZAIKU_LIBRARIAN_TOKEN)";
        String said = why == null ? "" : why.strip().replaceFirst("\\.+$", "");
        return "the library at " + url() + " answers, but does not let CodeZaiku in: " + what + (said.isEmpty() ? "" : " (the library says: " + said + ")")
                + ". To fix it: " + fixCommand(keptHere);
    }

    /**
     * What failed, in the system's own words: the last message along the exception's causes ("No route to host"); Java's HTTP client
     * gives none for a connection that could not be made, and then the kind of failure is named as it is.
     */
    static String systemWords(Throwable e) {
        String words = "";
        for (Throwable c = e; c != null; c = c.getCause())
            if (c.getMessage() != null && !c.getMessage().isBlank()) words = c.getMessage().strip();
        if (!words.isEmpty()) return words;
        if (e instanceof HttpConnectTimeoutException) return "the connection timed out";
        if (e instanceof ConnectException) return "the connection could not be made (ConnectException)";
        return e.getClass().getSimpleName();
    }

    /** No library answers: what to do about it here, or on the machine that keeps it. */
    public static String noneLine() {
        String why = seen.why(), said = why.startsWith("could not reach") ? " (" + why + ")" : "";
        return elsewhere()
                ? "no library answers at " + url() + ", the one CodeZaiku is set to use (CODEZAIKU_LIBRARIAN_URL)" + said
                  + ": check that the address is right, that the computer that keeps the library is on, and that its library is running there"
                : "no library answers on " + url() + said + " — /setup librarian installs ResearchZosho, or start its service";
    }

    /** For the commands a person types to use the library: why it cannot be used now, in the words of the case; "" when it can. */
    public static String unusable(boolean keptHere) {
        Seen s = seen();
        return switch (s.door()) {
            case IN -> "";
            case REFUSED -> refusedLine(s.why(), keptHere);
            case NONE -> noneLine();
        };
    }

    private static final AtomicBoolean REFUSAL_SAID = new AtomicBoolean();

    /** The refusal, said once in this program's life where the library is used without being asked for (the look before a turn); "" otherwise. */
    public static String refusedOnce(boolean installedHere) {
        Seen s = seen();
        return s.door() == Door.REFUSED && REFUSAL_SAID.compareAndSet(false, true) ? refusedLine(s.why(), installedHere) : "";
    }

    /** For a test: the refusal may be said again. */
    static void forgetRefusalSaid() { REFUSAL_SAID.set(false); }

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
        String not = unusable(keptHere());
        if (!not.isEmpty()) return not;
        try {
            JsonNode r = client().ask(question, k);
            String s = r.path("rendered").asText("");
            if (r.path("holds_nothing").asBoolean(false) || s.isBlank()) return "the library holds nothing on that";
            return s;
        } catch (Exception e) {
            return "! the library could not answer: " + e.getMessage();
        }
    }

    /** Whether ResearchZosho is installed on this machine, so that its own command can make CodeZaiku's token here. A test stands in for it. */
    static volatile BooleanSupplier installed = () -> ResearchZoshoInstall.installed() != null;

    public static boolean installedHere() {
        try { return installed.getAsBoolean(); } catch (RuntimeException e) { return false; }
    }

    public static String compress(String s, int max) {
        String t = s == null ? "" : s.strip().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
