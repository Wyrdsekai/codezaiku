package org.codezaiku.drive;

import org.codezaiku.Config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The places CodeZaiku keeps the code it reads away from (ported from ResearchZosho 0.8.1): country codes the person owns as a
 * setting. Unset, they are the countries of concern of the United States Department of Justice's rule on access to sensitive
 * personal data (28 CFR 202.601, in effect since 2025-04-08, read on 2026-11-20): China, which that rule defines with Hong Kong and
 * Macau (28 CFR 202.208), Cuba, Iran, North Korea, Russia and Venezuela. A list with a stated basis, not one picked by hand; the
 * person may name other places, or none.
 *
 * <p>A hosted model reached through OpenRouter is never served by a provider based or running in one of these places, nor, unless
 * the person allows it ({@link #BY_OWNER}), by one owned by a company based there ({@link OpenRouterProviders}). A model host that is
 * itself there is said to be where it is ({@link ModelPlaces}). The setting names places, never companies or models: which provider
 * is where comes from what each source states, read at the time. The settings and their meaning are ResearchZosho's, so a person
 * who uses both writes the same words in each.
 */
public final class Avoided {
    private Avoided() { }

    public static final String SETTING = "CODEZAIKU_AVOID_PLACES";
    /** What an unset setting means: the countries of concern of 28 CFR 202.601, China with Hong Kong and Macau as 28 CFR 202.208 defines it. */
    static final String UNSET = "CN, HK, MO, CU, IR, KP, RU, VE";
    /** Where the unset list comes from, as a person is told. */
    public static final String BASIS = "the countries of concern of the United States Department of Justice's data security rule (28 CFR 202.601, in effect since 2025-04-08)";

    /**
     * Whether a router's provider that is owned by a company based in an avoided place is left out too, whatever place it states for
     * itself: on unless the person set it off. ResearchZosho's owner decided it on 2026-11-20 ("chinese owned providers as excluded
     * unless the user turns them on"); the rule is the general one, for any avoided place.
     */
    public static final String BY_OWNER = "CODEZAIKU_AVOID_BY_OWNER";

    /** The settings as a person writes them in {@code codezaiku config set}. */
    public static final String SETTING_NAME = "avoid_places", BY_OWNER_NAME = "avoid_by_owner";

    public static boolean byOwner() {
        String v = Config.get(BY_OWNER);
        return v == null || v.isBlank() || !List.of("off", "no", "false", "0").contains(v.strip().toLowerCase(Locale.ROOT));
    }

    /** The codes a value of the setting names ("none", or anything that is no two-letter code, names no place); the unset list for null or blank. */
    public static Set<String> placesOf(String value) {
        String v = value == null || value.isBlank() ? UNSET : value;
        Set<String> out = new LinkedHashSet<>();
        for (String c : v.split("[,;\\s]+")) if (c.matches("[A-Za-z]{2}")) out.add(c.toUpperCase(Locale.ROOT));
        return out;
    }

    /** The country codes avoided, in capitals, in the order written; none when the setting says {@code none}. */
    public static Set<String> places() {
        return placesOf(Config.get(SETTING));   // "none", "off" or anything that is no two-letter code names no place
    }

    /** Whether {@code code} (a country code as a source states it, any case; null or blank: nowhere stated) is a place avoided. */
    public static boolean avoids(String code) { return code != null && !code.isBlank() && places().contains(code.strip().toUpperCase(Locale.ROOT)); }

    /** A place by the name a person knows: the eight of the unset list as English ordinarily names them, any other by its English name, else the code. */
    public static String name(String code) {
        String k = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        return switch (k) {
            case "CN" -> "China";
            case "HK" -> "Hong Kong";
            case "MO" -> "Macao";
            case "CU" -> "Cuba";
            case "IR" -> "Iran";
            case "KP" -> "North Korea";
            case "RU" -> "Russia";
            case "VE" -> "Venezuela";
            default -> {
                String n = k.isEmpty() ? "" : Locale.of("", k).getDisplayCountry(Locale.ENGLISH);
                yield n.isBlank() ? String.valueOf(code) : n;
            }
        };
    }

    /** Places by their names, as a sentence lists them: "China", "China and Hong Kong", "China, Hong Kong and Macao". */
    public static String names(Collection<String> codes) {
        List<String> n = new ArrayList<>();
        for (String code : codes) { String name = name(code); if (!n.contains(name)) n.add(name); }
        return and(n);
    }

    /** Words joined as an English list: "a", "a and b", "a, b and c". */
    static String and(List<String> words) {
        if (words.isEmpty()) return "";
        if (words.size() == 1) return words.get(0);
        return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.get(words.size() - 1);
    }
}
