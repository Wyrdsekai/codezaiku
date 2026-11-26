package org.codezaiku.tools;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a person means by a name or a number. A person says "read arXiv 2401.12345", "the paper 10.1000/xyz", "the repository
 * owner/name", "the Wikipedia article on Kestrel Sound", and the research prompts tell the model to read the Wikipedia page or the DOI it
 * found. None of these is an address, and a model that writes the address for one has put an address together, which CodeZaiku does
 * not open ({@link GivenAddresses}). So CodeZaiku builds the address itself, by rule: a model's call may hold an address that has
 * exactly one of the forms below, and it is opened when the name or the number in it was given: it stands in the person's words or in
 * a text that came from outside ({@link GivenWords}). Ported from ResearchZosho 0.8.1.
 *
 * <p>The forms, each on a host CodeZaiku is written for, with nothing before the host and nothing after the form (no user, no query,
 * and no port but the one the scheme has anyway):
 * <ul>
 * <li>{@code https://arxiv.org/abs/<number>} and {@code https://arxiv.org/pdf/<number>}: a preprint by its arXiv number;</li>
 * <li>{@code https://doi.org/<DOI>}: a work by its DOI;</li>
 * <li>{@code https://github.com/<owner>/<name>}: a repository by its owner and name;</li>
 * <li>{@code https://<language>.wikipedia.org/wiki/<Title>}: an article by its title;</li>
 * <li>{@code <scheme>://<host>/} and {@code <scheme>://<host>/robots.txt}: the front page and the robots file of a site of which an
 *     address was given, with the scheme that address had.</li>
 * </ul>
 * What is requested is the address built here from the parts.
 */
public final class GivenIdentifiers {
    private GivenIdentifiers() { }

    private static final Pattern ARXIV = Pattern.compile("^/(abs|pdf)/((?:[a-z-]+(?:\\.[A-Za-z-]+)?/)?[A-Za-z0-9.]{4,24}?)(v\\d{1,3})?(?:\\.pdf)?$");
    private static final Pattern DOI = Pattern.compile("^/(10\\.\\d{4,9}/(?:[-._;()/:A-Za-z0-9]|%[0-9A-Fa-f]{2}){1,200})$");
    private static final Pattern REPOSITORY = Pattern.compile("^/([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))/([A-Za-z0-9._-]{1,100}?)(?:\\.git)?/?$");
    private static final Pattern EDITION = Pattern.compile("^([a-z]{2,3}(?:-[a-z0-9]{1,12}){0,2})\\.wikipedia\\.org$");
    private static final Pattern ARTICLE = Pattern.compile("^/wiki/([^?#/][^?#]{0,250})$");

    /** Whether this address is one CodeZaiku opens for a name or a number that was given; when it is, the address as built here is given from here on. */
    static boolean opens(String address) {
        String built = built(address);
        if (built == null) return false;
        GivenAddresses.built(built);
        return true;
    }

    /** The address CodeZaiku builds for what {@code address} names, or null when it has none of the forms or its name was not given. */
    static String built(String address) {
        if (address == null) return null;
        String a = GivenAddresses.trimmed(GivenAddresses.fold(address).strip()).replace("<", "%3C").replace(">", "%3E");
        URI u;
        try { u = URI.create(a); } catch (IllegalArgumentException notAnAddress) { return null; }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT), host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("https") || scheme.equals("http")) || host.isEmpty() || u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null) return null;
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        int port = u.getPort() == (scheme.equals("https") ? 443 : 80) ? -1 : u.getPort();
        if (path.isEmpty() || path.equals("/") || path.equals("/robots.txt")) {
            String given = GivenAddresses.siteGiven(port >= 0 ? host + ":" + port : host);
            return given == null ? null : given + (path.equals("/robots.txt") ? path : "/");
        }
        if (port >= 0) return null;
        if (host.equals("arxiv.org") || host.equals("www.arxiv.org") || host.equals("export.arxiv.org")) {
            Matcher m = ARXIV.matcher(path);
            if (!m.matches()) return null;
            String number = m.group(2), version = m.group(3) == null ? "" : m.group(3);
            if (!(GivenWords.stated(number + version) || GivenWords.stated(number))) {
                if (!version.isEmpty()) return null;
                for (int v = 1; v <= 9 && version.isEmpty(); v++) if (GivenWords.stated(number + "v" + v)) version = "v" + v;
                if (version.isEmpty()) return null;
            }
            return "https://arxiv.org/" + m.group(1) + "/" + number + version;
        }
        if (host.equals("doi.org") || host.equals("dx.doi.org")) {
            Matcher m = DOI.matcher(path);
            return m.matches() && GivenWords.stated(GivenAddresses.unescaped(m.group(1))) ? "https://doi.org/" + m.group(1) : null;
        }
        if (host.equals("github.com") || host.equals("www.github.com")) {
            Matcher m = REPOSITORY.matcher(path);
            return m.matches() && GivenWords.stated(m.group(1) + " " + m.group(2)) ? "https://github.com/" + m.group(1) + "/" + m.group(2) : null;
        }
        Matcher edition = EDITION.matcher(host);
        if (edition.matches()) {
            Matcher m = ARTICLE.matcher(path);
            if (!m.matches()) return null;
            String title = GivenAddresses.unescaped(m.group(1)).replace('_', ' ');
            return GivenWords.stated(title) ? "https://" + host + "/wiki/" + m.group(1) : null;
        }
        return null;
    }
}
