package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The request a model's tool call makes, checked as it leaves (ported from ResearchZosho 0.8.1, where it holds every request of the
 * program; here it holds the two that open a page for a model: {@link Fetch#get} and {@link Firecrawl#scrape}). While a model's call is
 * carried out on this thread ({@link #call}, begun by {@link ToolRegistry#execute}), a request is sent only when it is one of these:
 * <ul>
 * <li>(a) an address that was given ({@link GivenAddresses}), asked in the spelling it was given in;</li>
 * <li>(b) a service CodeZaiku is written for, asked in that service's own shape ({@link #SERVICES}): the model's words go there as the
 *     words of a lookup (a country code, a CVE number, a product, a search's words), never as an address they spell;</li>
 * <li>(c) where a server sent an allowed request on. A request for a given address carries nothing of the model's, so its server may
 *     send it anywhere, and the address it leads to is given from then on. A request of kind (b) may carry the model's words, so it is
 *     followed only within the same site, to another service of kind (b), or to a given address.</li>
 * </ul>
 * Anything else is refused before it is sent, and the refusal is kept with the call ({@link Call#refusals}) and written into the job's
 * record with the tool, the host and the rule ({@link ToolRegistry}). The argument check ({@link GivenAddresses#notGiven}) stays the
 * early refusal a model reads; this is what holds when that one did not look. With no model's call on the thread, the request is the
 * program's own and goes as it is.
 */
public final class Outbound {
    private Outbound() { }

    private static final Logger log = LoggerFactory.getLogger(Outbound.class);

    /** A request this program did not send while a model's call was carried out, with the rule that refused it. */
    public static final class NotOpened extends IllegalArgumentException {
        private final String host, rule;
        NotOpened(String host, String rule) {
            super(GivenAddresses.REFUSED);
            this.host = host; this.rule = rule;
        }
        public String host() { return host; }
        public String rule() { return rule; }
    }

    /** One refusal, for the job's record: the tool whose call it was, the host that was not asked, the rule's name. */
    public record Refusal(String tool, String host, String rule) {
        public String line() { return tool + " → " + host + ": " + rule; }
    }

    /** The rules' names in the record: an address nobody gave; such an address handed to a service that reads pages; a request that may carry the call's words, sent on to another site. */
    public static final String NOT_GIVEN = "an address nobody gave", NOT_GIVEN_TO_READER = "a page reader handed an address nobody gave", SENT_ON = "sent on to another site";

    /** How a request was let through, which decides where it may be sent on. */
    public enum Way { GIVEN, WORDS }

    /**
     * The services CodeZaiku is written for that a model reaches through web_fetch, each by its host and the shape of its requests: the
     * research prompt names their addresses ({@code FamiliarMain.dataApiHint}) and the fetch tool asks Wikipedia's title search after a
     * page that is not there. A request to one of these hosts goes only when its path and query have exactly that shape; the model's
     * words stand only in the places the service takes words. A test lists them, so that adding one is a visible change.
     */
    static final Map<String, List<Pattern>> SERVICES = Map.of(
            "api.worldbank.org", List.of(Pattern.compile("^/v2/country/[A-Za-z0-9;]{2,60}/indicator/[A-Za-z0-9._]{2,60}(?:\\?(?:(?:format=json|date=\\d{4}(?::\\d{4})?|per_page=\\d{1,5}|page=\\d{1,4}|mrv=\\d{1,3})&?){1,5})?$")),
            "services.nvd.nist.gov", List.of(Pattern.compile("^/rest/json/cves/2\\.0\\?(?:(?:keywordSearch=[A-Za-z0-9%+._-]{1,200}|keywordExactMatch|cveId=CVE-\\d{4}-\\d{4,7}|resultsPerPage=\\d{1,4}|startIndex=\\d{1,6})&?){1,5}$")),
            "endoflife.date", List.of(Pattern.compile("^/api/[a-z0-9._-]{1,60}(?:/[A-Za-z0-9._-]{1,40})?\\.json$")),
            "api.osv.dev", List.of(Pattern.compile("^/v1/vulns/[A-Za-z0-9._:-]{3,80}$")),
            "www.cisa.gov", List.of(Pattern.compile("^/sites/default/files/feeds/known_exploited_vulnerabilities\\.json$")),
            "*.wikipedia.org", List.of(Pattern.compile("^/w/rest\\.php/v1/search/page\\?limit=\\d{1,2}&q=[A-Za-z0-9%+._~*-]{1,300}$")));

    private static final Pattern WIKI_EDITION = Pattern.compile("^[a-z]{2,3}(?:-[a-z0-9]{1,12}){0,2}\\.wikipedia\\.org$");

    /** Whether an address asks a service CodeZaiku is written for, in that service's own shape ({@link #SERVICES}): https, its host, no user, no port of its own. */
    public static boolean service(String address) {
        if (address == null) return false;
        URI u;
        try { u = URI.create(GivenAddresses.trimmed(GivenAddresses.fold(address).strip())); } catch (IllegalArgumentException e) { return false; }
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getRawUserInfo() != null || u.getRawFragment() != null) return false;
        if (u.getPort() != -1 && u.getPort() != 443) return false;
        String host = u.getHost().toLowerCase(Locale.ROOT);
        List<Pattern> shapes = SERVICES.get(host);
        if (shapes == null && WIKI_EDITION.matcher(host).matches()) shapes = SERVICES.get("*.wikipedia.org");
        if (shapes == null) return false;
        String asked = (u.getRawPath() == null ? "" : u.getRawPath()) + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
        for (Pattern p : shapes) if (p.matcher(asked).matches()) return true;
        return false;
    }

    private static final ThreadLocal<Call> CURRENT = new ThreadLocal<>();

    /** A model's tool call being carried out on this thread, from its arguments; closing it ends the call. */
    public static Call call(String tool, JsonNode args) {
        Call c = new Call(tool, args, CURRENT.get());
        CURRENT.set(c);
        return c;
    }

    /** What a call's arguments hold, with no call begun: for what an answer may give ({@link GivenAddresses.Own}). */
    public static Call of(JsonNode args) { return new Call("", args, null); }

    /** The call being carried out on this thread, or null. */
    public static Call current() { return CURRENT.get(); }

    /**
     * Lets the first request of a fetch through, or refuses it: nothing while no model's call is carried out on this thread. Returns how
     * it was let through, which decides where its server may send it on ({@link #hop}).
     */
    public static Way check(URI uri) throws NotOpened {
        Call c = CURRENT.get();
        if (c == null || uri == null) return Way.GIVEN;
        String address = uri.toString();
        if (GivenAddresses.given(address) || GivenIdentifiers.opens(address)) return Way.GIVEN;
        if (service(address)) return Way.WORDS;
        throw refuse(c, host(uri), NOT_GIVEN);
    }

    /**
     * Where a server sent a request on, asked next. A request that carried nothing of the model's ({@link Way#GIVEN}) is followed
     * anywhere, and where it leads is given from then on; one that may carry the model's words is followed within its own site, to
     * another service, or to a given address. Nothing while no model's call is carried out.
     */
    public static Way hop(Way way, URI from, URI to) throws NotOpened {
        Call c = CURRENT.get();
        if (c == null || to == null) { if (to != null) GivenAddresses.receivedWhole(to.toString()); return Way.GIVEN; }
        if (way == Way.GIVEN) { GivenAddresses.receivedWhole(to.toString()); return Way.GIVEN; }
        if (GivenAddresses.given(to.toString())) return Way.GIVEN;
        if (service(to.toString()) || site(host(to)).equals(site(host(from)))) return Way.WORDS;
        throw refuse(c, host(to), SENT_ON);
    }

    /** A page's address handed to a service that reads pages for CodeZaiku ({@link Firecrawl}): it has to be given too. Null when it may go; else the refusal, kept with the call. */
    static NotOpened readerRefuses(String address) {
        Call c = CURRENT.get();
        if (c == null || GivenAddresses.given(address)) return null;
        return refuse(c, GivenAddresses.hostOf(address), NOT_GIVEN_TO_READER);
    }

    /** The argument check's refusal, kept with the call as the request check's are. */
    static void refusedEarly(Call c, String host) { if (c != null) refuse(c, host, NOT_GIVEN); }

    private static NotOpened refuse(Call c, String host, String rule) {
        c.refusals.add(new Refusal(c.tool, host, rule));
        log.warn("not opened, while the tool {} was carried out: {} ({})", c.tool.isEmpty() ? "(unnamed)" : c.tool, host, rule);
        return new NotOpened(host, rule);
    }

    static String host(URI u) { return u == null || u.getHost() == null ? "" : host(u.getHost()); }

    /** A host as it is compared: small letters, no dot after it, no brackets around it. */
    static String host(String h) {
        if (h == null) return "";
        String s = h.strip().toLowerCase(Locale.ROOT);
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static final Set<String> SECOND_LEVEL = Set.of("co", "com", "org", "net", "ac", "gov", "edu", "or", "ne", "go", "gr", "lg", "ed", "ad", "mil", "nom", "sch", "ltd", "plc", "gob", "nic");
    /** Names under which each further name is somebody else's: a host there is a site of its own. */
    private static final Set<String> SHARED = Set.of("github.io", "gitlab.io", "githubusercontent.com", "blogspot.com", "wordpress.com", "tumblr.com", "substack.com", "herokuapp.com", "appspot.com",
            "web.app", "firebaseapp.com", "pages.dev", "workers.dev", "vercel.app", "netlify.app", "amazonaws.com", "cloudfront.net", "azurewebsites.net", "windows.net", "googleusercontent.com",
            "neocities.org", "glitch.me", "onrender.com", "fly.dev", "repl.co", "ngrok.io", "ngrok-free.app", "trycloudflare.com", "ts.net", "duckdns.org", "wixsite.com", "weebly.com", "livejournal.com");

    /** The site a host belongs to: the name its owner registered (www.example.org and api.example.org are example.org; example.co.uk is a site). */
    static String site(String host) {
        String h = host(host);
        if (h.isEmpty() || h.indexOf('.') < 0 || h.matches("[0-9.]+|.*:.*")) return h;
        String[] l = h.split("\\.");
        int keep = l.length >= 3 && l[l.length - 1].length() == 2 && SECOND_LEVEL.contains(l[l.length - 2]) ? 3 : 2;
        if (l.length <= keep) return h;
        String s = String.join(".", Arrays.copyOfRange(l, l.length - keep, l.length));
        return SHARED.contains(s) ? h : s;
    }

    /** Every host a text of small letters names: two or more names joined by dots. Read by hand, one character after another. */
    static void dotted(String lower, Consumer<String> to) {
        int n = lower.length();
        for (int i = 0; i < n; ) {
            if (!ofAHost(lower.charAt(i))) { i++; continue; }
            int j = i;
            while (j < n && ofAHost(lower.charAt(j))) j++;
            int start = -1, names = 0;
            for (int at = i; at <= j; ) {
                int dot = at;
                while (dot < j && lower.charAt(dot) != '.') dot++;
                boolean name = dot > at && lower.charAt(at) != '-' && lower.charAt(dot - 1) != '-';
                if (name && names++ == 0) start = at;
                if (!name || dot >= j) { if (names >= 2) to.accept(lower.substring(start, name ? dot : at - 1)); names = 0; }
                at = dot + 1;
            }
            i = j;
        }
    }

    private static boolean ofAHost(char c) { return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-'; }
    private static final Pattern PERCENT = Pattern.compile("%([0-9a-fA-F]{2})"), BACKSLASH_U = Pattern.compile("\\\\u([0-9a-fA-F]{4})"), ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]{1,6});");

    /** The words of one call's arguments, in every reading a tool may give them, and what they name. */
    public static final class Call implements AutoCloseable {
        private final String words, tool;
        private final Set<String> hosts = new LinkedHashSet<>();
        private final List<Refusal> refusals = new CopyOnWriteArrayList<>();
        private final Call outer;

        private Call(String tool, JsonNode args, Call outer) {
            this.outer = outer; this.tool = tool == null ? "" : tool;
            StringBuilder raw = new StringBuilder();
            gather(args, raw);
            String folded = GivenAddresses.fold(raw.toString()).toLowerCase(Locale.ROOT);
            // as written; with its %XX escapes read; with its numeric entities read; with its backslash escapes read: a tool may read any of them
            String percent = replace(PERCENT, folded, m -> String.valueOf((char) Integer.parseInt(m.group(1), 16)));
            String entity = replace(ENTITY, folded, m -> { try { return new String(Character.toChars(Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16))); } catch (RuntimeException e) { return m.group(); } }).replace("&amp;", "&");
            String slash = replace(BACKSLASH_U, folded, m -> String.valueOf((char) Integer.parseInt(m.group(1), 16))).replace("\\/", "/");
            this.words = folded + "\n" + percent.toLowerCase(Locale.ROOT) + "\n" + entity.toLowerCase(Locale.ROOT) + "\n" + slash.toLowerCase(Locale.ROOT);
            dotted(words, hosts::add);
        }

        private static void gather(JsonNode v, StringBuilder out) {
            if (v == null) return;
            if (v.isTextual()) out.append(v.asText()).append('\n');
            else if (v.isNumber()) out.append(v.asText()).append('\n');
            else if (v.isContainerNode()) { if (v.isObject()) v.fieldNames().forEachRemaining(n -> out.append(n).append('\n')); for (JsonNode x : v) gather(x, out); }
        }

        private static String replace(Pattern p, String s, Function<Matcher, String> with) {
            Matcher m = p.matcher(s);
            StringBuilder b = new StringBuilder();
            while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(with.apply(m)));
            m.appendTail(b);
            return b.toString();
        }

        /** The tool whose call this is. */
        public String tool() { return tool; }

        /** The arguments' words as they were read, every reading of them: what a text that came back from this call does not make given. */
        public String said() { return words; }

        /** Whether the arguments name this host: it, a name under it, or a name it is under; a host of one word, as a word of its own. */
        public boolean names(String host) {
            String h = host(host);
            if (h.isEmpty()) return false;
            for (String t : hosts) if (h.equals(t) || h.endsWith("." + t) || t.endsWith("." + h)) return true;
            if (h.indexOf('.') < 0) return Pattern.compile("(?<![a-z0-9.-])" + Pattern.quote(h) + "(?![a-z0-9-])").matcher(words).find();
            return false;
        }

        /** Whether the arguments spell out this path and query: nothing after the host counts as spelled out, since the host alone is the address. */
        public boolean holds(String pathAndQuery) {
            String p = pathAndQuery == null ? "" : pathAndQuery.toLowerCase(Locale.ROOT);
            while (p.startsWith("/")) p = p.substring(1);
            while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
            if (p.isEmpty()) return true;
            return words.contains(p) || words.contains(GivenAddresses.unescaped(p).toLowerCase(Locale.ROOT));
        }

        /**
         * Whether an address is the arguments' own, written back: its host named in them and what follows the host spelled out in them.
         * An answer that holds such an address holds it only because the call did, and the answer does not make it given.
         */
        public boolean wrote(String address) {
            if (address == null) return false;
            String a = GivenAddresses.fold(address).strip();
            Matcher scheme = Pattern.compile("(?i)^[a-z][a-z0-9+.-]*://").matcher(a);
            String rest = scheme.find() ? a.substring(scheme.end()) : a;
            int hash = rest.indexOf('#'); if (hash >= 0) rest = rest.substring(0, hash);
            int cut = rest.length();
            for (char c : new char[]{'/', '?'}) { int i = rest.indexOf(c); if (i >= 0 && i < cut) cut = i; }
            String authority = rest.substring(0, cut), after = rest.substring(cut);
            int at = authority.lastIndexOf('@'); if (at >= 0) authority = authority.substring(at + 1);
            String h = authority.replaceFirst(":\\d{1,5}$", "");
            return names(h) && holds(after);
        }

        /** What was refused while this call was carried out; a nested call's are its outer call's too. */
        public List<Refusal> refusals() { return List.copyOf(refusals); }

        @Override public void close() { if (outer == null) CURRENT.remove(); else { outer.refusals.addAll(refusals); CURRENT.set(outer); } }
    }
}
