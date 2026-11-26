package org.codezaiku.tools;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Words that were given: what the person wrote, and text that came from outside in a job (a search's results, a page that was
 * opened, the answer of another program's tool whose addresses count). {@link GivenAddresses} holds the web addresses of the same
 * texts; this holds the words, for the addresses CodeZaiku builds itself from a name or a number ({@link GivenIdentifiers}): an
 * arXiv number, a DOI, a repository's owner and name, an encyclopedia article's title. Such a name counts when it stands in the
 * person's words or in an outside text, and a text that came back from a call whose own words held the name does not give it (a
 * search answers "results for …" with the words it was sent).
 *
 * <p>The comparison is made after both sides are written the same way: small letters, one space between words, nothing that is not
 * a letter or a digit. In the person's own words the words of a name may also stand close together in any order ("the ferry-tables
 * repository of harbour-works"). Ported from ResearchZosho 0.8.1 without its street-address part, which CodeZaiku has no use for.
 *
 * <p>Like the given addresses, the texts are the process's, the newest kept up to a limit; nothing is written to disk.
 */
public final class GivenWords {
    private GivenWords() { }

    /** One text as it is compared, the words of the call that brought it ("" for the person's own), and whose it is. */
    private record Text(String words, String callsOwn, boolean person) { }

    private static final Deque<Text> TEXTS = new ArrayDeque<>();
    private static long held;
    /** The most kept in all, and of one text: in characters as compared. */
    static final long KEEP = 24_000_000;
    static final int ONE = 1_000_000;
    /** How far apart, in words, the words of a name may stand in the person's own words. */
    static final int NEAR = 16;

    /** A text as it is compared: its words in small letters with one space between and one at each end, nothing that is not a letter or a digit. */
    static String normal(String s) {
        if (s == null || s.isEmpty()) return " ";
        String t = Normalizer.normalize(s.length() > ONE ? s.substring(0, ONE) : s, Normalizer.Form.NFKC);
        StringBuilder b = new StringBuilder(t.length() + 2).append(' ');
        boolean inWord = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c)) { b.append(Character.toLowerCase(c)); inWord = true; continue; }
            if (c == '\'' || c == '’') continue;   // O'Brien and OBrien are one word
            if (inWord) { b.append(' '); inWord = false; }
        }
        if (inWord) b.append(' ');
        return b.toString().toLowerCase(Locale.ROOT);
    }

    private static void keep(Text t) {
        synchronized (TEXTS) {
            TEXTS.addLast(t);
            held += t.words().length() + t.callsOwn().length();
            while (held > KEEP && TEXTS.size() > 1) { Text gone = TEXTS.removeFirst(); held -= gone.words().length() + gone.callsOwn().length(); }
        }
    }

    /** What the person wrote. */
    public static void person(String words) { if (words != null && !words.isBlank()) keep(new Text(normal(words), "", true)); }

    /** A text that came from outside; {@code callsOwn} the words of the call that brought it (its arguments), or null. */
    public static void outside(String text, String callsOwn) {
        if (text != null && !text.isBlank()) keep(new Text(normal(text), callsOwn == null || callsOwn.isBlank() ? "" : normal(callsOwn), false));
    }

    /**
     * Whether a name or a number was given: its letters and digits stand together and in order in the person's words or in a text from
     * outside that its own call's words did not bring; in the person's own words also close together in any order.
     */
    public static boolean stated(String words) {
        if (words == null) return false;
        String w = normal(words);
        if (w.isBlank()) return false;
        String[] parts = w.strip().split(" ");
        for (Text t : texts()) {
            if (t.words().contains(w)) { if (t.person() || !t.callsOwn().contains(w)) return true; continue; }
            if (t.person() && near(t.words(), parts)) return true;
        }
        return false;
    }

    /**
     * Whether the person's own words, and only theirs, hold {@code words}: together and in order, or close together in any order. For
     * an act only the person may ask for (a force push): a page or a search result that says it gives nothing.
     */
    public static boolean inPersonsWords(String words) {
        if (words == null) return false;
        String w = normal(words);
        if (w.isBlank()) return false;
        String[] parts = w.strip().split(" ");
        for (Text t : texts()) if (t.person() && (t.words().contains(w) || near(t.words(), parts))) return true;
        return false;
    }

    /** Forgets everything: a test's way to a clean start. */
    static void forget() { synchronized (TEXTS) { TEXTS.clear(); held = 0; } }

    private static List<Text> texts() { synchronized (TEXTS) { return new ArrayList<>(TEXTS); } }

    /** Whether every one of {@code parts} stands as a word of its own within {@link #NEAR} words of the others. */
    private static boolean near(String text, String[] parts) {
        if (parts.length < 2) return false;
        String[] words = text.strip().split(" ");
        for (int i = 0; i < words.length; i++) {
            if (!words[i].equals(parts[0])) continue;
            boolean all = true;
            for (int p = 1; p < parts.length && all; p++) {
                boolean found = false;
                for (int j = Math.max(0, i - NEAR); j < Math.min(words.length, i + NEAR + 1) && !found; j++) found = words[j].equals(parts[p]);
                all = found;
            }
            if (all) return true;
        }
        return false;
    }
}
