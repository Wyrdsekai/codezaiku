package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The addresses CodeZaiku opens for a model: one that stands as it is in the person's words (the task, a chat message, the project's
 * instruction files as they were when the job began), in what a search returned or a page opened from a given address says (its text
 * and its links), or in a file of the project as it was before the job began; never one a model put together, and never one a command
 * printed or a file the job itself wrote holds (the model wrote the command and the file). Ported from ResearchZosho 0.8.1, where on
 * 2026-11-18 a model read a page that held an address with blanks to fill in, put the person's notes into it and opened it: the page's
 * server had the notes. In CodeZaiku what would leak is the person's code and whatever a job read.
 *
 * <p>A search's words are the model's own by nature and are not held to this: they go to the search services CodeZaiku is written for,
 * as words ({@link Outbound}).
 *
 * <p>Kept for this process, the most recent {@link #MOST}: an address a model read in a result an hour ago may be opened now, in a
 * later turn of the chat or in a job the chat's model delegated.
 */
public final class GivenAddresses {
    private GivenAddresses() { }

    static final int MOST = 50_000;
    /** Each given address as it is compared ({@link #key}), with the address as it was given ({@link #exact}): a fetch requests that string, never the model's way of writing the same address. */
    private static final Map<String, String> SEEN = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > MOST; }
    });
    /** Every spelling an address was given in: a request is made in the spelling it was written in when that is one of these, and in the first one given otherwise ({@link #asRequested}). */
    private static final Set<String> EXACT = Collections.newSetFromMap(Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > 2 * MOST; }
    }));
    /** The sites of the given addresses, each with the scheme it was first given with: a site's own front page and robots file are its to open ({@link GivenIdentifiers}). */
    private static final Map<String, String> HOSTS = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > MOST; }
    });
    /**
     * The hosts a command may reach because the person typed them, as {@code host:port}: the task of {@code code} or {@code run}, a
     * chat message, {@code /research}, a change to a plan ({@link #personSaid}). Nothing else gives a command a host: not a search's
     * result, a page, another server's answer or a redirect, and not a file of the project, the instruction file included (a cloned
     * project's files are its author's words, and a README's link to github.com would open all of GitHub to every command). Those
     * give an address to {@code web_fetch} only. The owner's rule of 2026-11-24. Asked by the sandbox's proxy ({@link #givenToCommands}).
     */
    private static final Set<String> FOR_COMMANDS = Collections.newSetFromMap(Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > MOST; }
    }));

    /** Whether the person typed {@code host} on {@code port}: then a command may reach it ({@link #FOR_COMMANDS}). */
    public static boolean givenToCommands(String host, int port) {
        if (host == null) return false;
        String h = host.strip().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return FOR_COMMANDS.contains(h + ":" + port);
    }

    /** The addresses written out (with their scheme) in the person's words, for a command. */
    private static void forCommands(String text) {
        if (text == null || text.indexOf("://") < 0) return;
        Matcher m = URL.matcher(fold(text));
        while (m.find()) hostsForCommands(m.group(), false);
    }

    /**
     * The host and port of an address, kept for commands: the port written, else the scheme's; an address the person wrote without
     * its scheme counts for 443 and 80. A name with or without {@code www.} is the same site, as for {@code web_fetch}.
     */
    private static void hostsForCommands(String address, boolean bare) {
        Matcher m = SCHEME_AND_HOST.matcher(requested(trimmed(fold(address).strip())));
        if (!m.find()) return;
        String hp = m.group(2).toLowerCase(Locale.ROOT), host = hp;
        int port = -1;
        if (hp.startsWith("[")) {
            int end = hp.indexOf(']');
            if (end < 0) return;
            host = hp.substring(1, end);
            if (hp.length() > end + 2 && hp.charAt(end + 1) == ':') port = portOf(hp.substring(end + 2));
        } else {
            int colon = hp.lastIndexOf(':');
            if (colon > 0) { host = hp.substring(0, colon); port = portOf(hp.substring(colon + 1)); }
        }
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty()) return;
        List<Integer> ports = port > 0 ? List.of(port) : bare ? List.of(443, 80) : List.of(m.group(1).equalsIgnoreCase("http") ? 80 : 443);
        String twin = host.startsWith("www.") ? host.substring(4) : host.indexOf(':') < 0 && !NUMBERS_ALONE.matcher(host).matches() ? "www." + host : null;
        for (int p : ports) {
            FOR_COMMANDS.add(host + ":" + p);
            if (twin != null && !twin.isEmpty()) FOR_COMMANDS.add(twin + ":" + p);
        }
    }

    private static int portOf(String s) {
        try { int p = Integer.parseInt(s); return p > 0 && p < 65536 ? p : -1; } catch (NumberFormatException e) { return -1; }
    }

    /** An address written out. Braces and bars stay in it: a template is compared whole, never by its prefix. A host written as an IPv6 address stands in square brackets. */
    static final Pattern URL = Pattern.compile("(?i)https?://(?:\\[[0-9a-f:.]+\\](?::\\d{1,5})?(?:[/?#][^\\s<>\"'`\\[\\]]*)?|[^\\s<>\"'`\\[\\]]+)");
    /** An address a person writes without its scheme: example.org, example.org/page, host:8443/page. */
    static final Pattern BARE = Pattern.compile("(?i)(?<![\\w@./:%-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}(?::\\d{1,5})?(?:/[^\\s<>\"'`{}|\\\\^\\[\\]]*)?");
    /** The most names joined by dots that are read as a host's; a longer run is no host and is not handed to the pattern (a deep run used a thread's stack up in ResearchZosho). */
    static final int MOST_NAMES = 128;
    private static final Pattern HOST_RUN = Pattern.compile("(?<![\\w@./:%-])[A-Za-z0-9][A-Za-z0-9.-]*");

    private static int dots(String s, int from, int to) { int n = 0; for (int i = from; i < to; i++) if (s.charAt(i) == '.') n++; return n; }

    /** Every address without its scheme in a text, as {@link #BARE} finds them one after the other. */
    static List<String> bareIn(String text) {
        List<String> out = new ArrayList<>();
        Matcher run = HOST_RUN.matcher(text), bare = BARE.matcher(text).useTransparentBounds(true);
        for (int from = 0; from <= text.length() && run.find(from); ) {
            if (dots(text, run.start(), run.end()) <= MOST_NAMES && bare.region(run.start(), text.length()).lookingAt()) { out.add(bare.group()); from = Math.max(bare.end(), run.start() + 1); }
            else from = Math.max(run.end(), run.start() + 1);
        }
        return out;
    }

    /** Whether a whole value is an address without its scheme. */
    static boolean bareWhole(String s) {
        int host = 0;
        while (host < s.length() && (Character.isLetterOrDigit(s.charAt(host)) || s.charAt(host) == '.' || s.charAt(host) == '-')) host++;
        return dots(s, 0, host) <= MOST_NAMES && BARE.matcher(s).matches();
    }

    /** A machine a person writes by its numbers, with a port or a path after them: 192.0.2.20:8096/web. */
    static final Pattern NUMBERED = Pattern.compile("(?<![\\w@./:%-])(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5}(?:/[^\\s<>\"'`{}|\\\\^\\[\\]]*)?|/[^\\s<>\"'`{}|\\\\^\\[\\]]*)");
    /** A machine by its four numbers alone, as it stands in the person's words: "open 192.168.1.1". */
    static final Pattern NUMBERS_IN_WORDS = Pattern.compile("(?<![\\w@./:%-])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\w@%-]|:\\d|/|\\.\\d)");
    static final Pattern NUMBERS_ALONE = Pattern.compile("(?:\\d{1,3}\\.){3}\\d{1,3}");
    static final Pattern HREF = Pattern.compile("(?i)\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>\"']+))");
    /** The arguments that are a search's words, or words to look for in a page (web_fetch's {@code find}): the model writes them, and they are not held to the rule. */
    static final Set<String> QUERY_KEYS = Set.of("query", "q", "queries", "find");
    /** A value that names a file, not a host, whatever its ending. */
    static final Pattern FILE_LIKE = Pattern.compile("(?i)^[\\w.-]+\\.(md|txt|pdf|json|jsonl|csv|tsv|docx?|xlsx?|pptx?|odt|epub|png|jpe?g|gif|webp|svg|html?|xml|zip|cbz|cbr|mp3|mp4|m4a|wav|bib|ris|srt|vtt)$");

    /** The tools whose answer comes from outside and gives the addresses written in it (minus its own call's): the web and the literature. */
    public static final Set<String> FROM_OUTSIDE = Set.of("web_search", "scholar_search", "web_fetch");

    /** The hyphens that look alike, and every other look-alike NFKC folds: the person's ferry\u2011almanac.org is the model's ferry-almanac.org. */
    public static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC);
        return n.replaceAll("[\u2010\u2011\u2012\u2013\u2212\uFE63\uFF0D]", "-");
    }

    /**
     * What a call brought with it, which its own answer never makes given: the addresses its arguments hold, as compared, and the
     * arguments' words. An answer can hold an address only because the call did: the refusal that names it, "no results for …", or an
     * address whose host the arguments name and whose path they spell out ({@link Outbound.Call#wrote}).
     */
    public record Own(Set<String> keys, Outbound.Call call) {
        public static Own of(JsonNode args) { return new Own(keysIn(args), Outbound.of(args)); }
        static Own none() { return new Own(Set.of(), null); }
        boolean holds(String key, String address) { return keys.contains(key) || (call != null && call.wrote(trimmed(fold(address).strip()))); }
    }

    /** Every address in a text from outside (a search's, a page's), written with its scheme. */
    public static void received(String text) { received(text, Own.none()); }

    /** The same, without anything the call that brought it held itself ({@link Own}). */
    public static void received(String text, Own own) {
        GivenWords.outside(text, own.call() == null ? null : own.call().said());
        if (text == null || text.indexOf("://") < 0) return;
        Matcher m = URL.matcher(fold(text));
        while (m.find()) { String k = key(m.group()); if (k != null && !own.holds(k, m.group())) keep(k, m.group()); }
    }

    /** The addresses of a text alone, its words not kept as an outside text's are: for the person's own words, which are kept as theirs. */
    private static void receivedAddresses(String text) {
        if (text == null || text.indexOf("://") < 0) return;
        Matcher m = URL.matcher(fold(text));
        while (m.find()) { String k = key(m.group()); if (k != null) keep(k, m.group()); }
    }

    /** The addresses a call's arguments carry, as compared: what its own result may not make given. */
    public static Set<String> keysIn(JsonNode args) {
        Set<String> out = new HashSet<>();
        if (args == null) return out;
        if (args.isTextual()) {
            String v = fold(args.asText());
            Matcher m = URL.matcher(v);
            while (m.find()) { String k = key(m.group()); if (k != null) out.add(k); }
            String w = v.strip();
            if (bareWhole(w) || NUMBERED.matcher(w).matches() || NUMBERS_ALONE.matcher(w).matches() || w.matches("(?i)^https?://.*")) { String k = key(w); if (k != null) out.add(k); }
        } else for (JsonNode v : args) out.addAll(keysIn(v));
        return out;
    }

    /**
     * The person's own words: an address written out, or one without its scheme (example.org/page), a machine by its numbers with a
     * port or a path, and a machine's numbers alone. Only here: a text from outside and a file give an address only when it is written
     * with its scheme.
     */
    public static void personSaid(String words) {
        if (words == null || words.isBlank()) return;
        GivenWords.person(words);
        receivedAddresses(words);
        forCommands(words);                                   // the person's words give a host to a command too
        String said = fold(words);
        for (String bare : bareIn(said)) { add(bare); hostsForCommands(bare, true); }
        Matcher m = NUMBERED.matcher(said);
        while (m.find()) { addNumbers(m.group()); hostsForCommands(m.group(), true); }
        m = NUMBERS_IN_WORDS.matcher(said);
        while (m.find()) if (fourNumbers(m.group())) { addNumbers(m.group()); hostsForCommands(m.group(), true); }
    }

    /** Four numbers that are an address: each at most 255. */
    private static boolean fourNumbers(String s) {
        for (String p : s.split("\\.")) { try { if (Integer.parseInt(p) > 255) return false; } catch (NumberFormatException e) { return false; } }
        return true;
    }

    /**
     * A machine the person wrote by its numbers with no scheme before them: theirs by http as by https (a router or a printer answers by
     * plain http more often than not). With nothing after the numbers and the port, a slash after them is the same address.
     */
    private static void addNumbers(String written) {
        String k = key(written);
        if (k == null) return;
        String rest = exact(written).substring("https://".length());
        for (String scheme : new String[]{"https://", "http://"}) {
            kept(k, scheme + rest);
            if (rest.indexOf('/') < 0) kept(k, scheme + rest + "/");
        }
    }

    /** The links of a page CodeZaiku read, resolved against the page's own address. */
    public static void linksOf(String html, String pageUrl) {
        if (html == null || pageUrl == null) return;
        URI base;
        try { base = URI.create(pageUrl); } catch (IllegalArgumentException e) { return; }
        Matcher m = HREF.matcher(html);
        while (m.find()) {
            String h = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            if (h == null || h.isBlank()) continue;
            h = h.strip().replace("&amp;", "&");
            try {
                URI u = base.resolve(h.replace(" ", "%20"));
                if (u.getScheme() != null && (u.getScheme().equalsIgnoreCase("http") || u.getScheme().equalsIgnoreCase("https"))) addWhole(u.toString());
            } catch (IllegalArgumentException notAnAddress) { /* a link the page wrote wrong is no address */ }
        }
    }

    private static void add(String address) { String k = key(address); if (k != null) keep(k, address); }

    /** An address that is a value of its own (a page's link, where a server sent a request on): given whole, a full stop at its end being part of it. */
    private static void addWhole(String address) { String k = key(address); if (k != null) kept(k, whole(address)); }

    /** An address a server sent a request on to, or that an answer named as a value of its own: given, whole. */
    public static void receivedWhole(String address) { if (address != null && address.strip().matches("(?is)^https?://\\S+")) addWhole(address); }

    /** The first way an address was given stays its way: a later result that writes it otherwise does not change what is fetched. */
    private static void keep(String key, String address) { kept(key, exact(address)); }

    private static void kept(String key, String spelling) {
        SEEN.merge(key, spelling, (first, later) -> first);
        EXACT.add(spelling);
        Matcher m = SCHEME_AND_HOST.matcher(spelling);
        if (m.find()) { String scheme = m.group(1).toLowerCase(Locale.ROOT); HOSTS.putIfAbsent(site(scheme, m.group(2).toLowerCase(Locale.ROOT)), scheme); }
    }

    /** A site as it is kept: the host, and its port when the address named another than its scheme has anyway. */
    private static String site(String scheme, String hostAndPort) {
        String own = "http".equals(scheme) ? ":80" : ":443";
        if (!hostAndPort.endsWith(own)) return hostAndPort;
        String host = hostAndPort.substring(0, hostAndPort.length() - own.length());
        return !host.isEmpty() && (host.endsWith("]") || host.indexOf(':') < 0) ? host : hostAndPort;
    }

    /**
     * A host with "www." before it is the same given address as the host without it, both ways. Only that one label: m., www2. and en.
     * are other hosts, and "www." before a host of a single label or one written as numbers is compared as written.
     */
    static String withoutWww(String hostAndPort) {
        int at = hostAndPort.lastIndexOf('@') + 1;
        if (!hostAndPort.startsWith("www.", at)) return hostAndPort;
        String rest = hostAndPort.substring(at + 4);
        int colon = rest.lastIndexOf(':');
        String name = colon < 0 ? rest : rest.substring(0, colon);
        while (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || name.substring(dot + 1).matches("\\d*|0x[0-9a-f]*")) return hostAndPort;
        return hostAndPort.substring(0, at) + rest;
    }

    private static final Pattern SCHEME_AND_HOST = Pattern.compile("(?i)^(https?)://(?:[^/?#@]*@)?([^/?#]+)");

    /** A site as it was given, with the scheme its first given address had ({@code https://example.org}), or null when no address on it was given. */
    static String siteGiven(String site) {
        if (site == null) return null;
        String s = site.strip().toLowerCase(Locale.ROOT);
        String scheme = HOSTS.get(s);
        if (scheme != null) return scheme + "://" + s;
        String bare = withoutWww(s), twin = !bare.equals(s) ? bare : withoutWww("www." + s).equals(s) ? "www." + s : null;
        scheme = twin == null ? null : HOSTS.get(twin);
        return scheme == null ? null : scheme + "://" + twin;
    }

    /** An address CodeZaiku itself put together from a name or a number that was given ({@link GivenIdentifiers}): given from here on, as built. */
    static void built(String address) { String k = key(address); if (k != null) kept(k, whole(address)); }

    /** Whether CodeZaiku may open this address for a model: it, the fragment aside, is one the person gave or a result held. */
    public static boolean given(String address) { String k = key(address); return k != null && SEEN.containsKey(k); }

    /** An address as it was given, the one string a fetch of it requests: folded, the sentence's stop after it and its fragment off, https:// before it when it was written without a scheme. */
    static String exact(String a) {
        String s = trimmed(fold(a).strip());
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        return requested(s);
    }

    /** An address as a value of its own: folded, the fragment off, https:// before it when it was written without a scheme; nothing else taken off. */
    static String whole(String a) {
        String s = fold(a).strip();
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        return requested(s);
    }

    /**
     * The spelling a request for this address is made in, or null when the address was not given: its own when that is a spelling it
     * was given in, else the first one it was given in. Never the model's way of writing it, and never http where it was given with https.
     */
    public static String asRequested(String address) {
        String k = key(address);
        String first = k == null ? null : SEEN.get(k);
        if (first == null) return null;
        String w = whole(address);
        if (EXACT.contains(w)) return w;
        String e = exact(address);
        return EXACT.contains(e) ? e : first;
    }

    /** What a fetch requests for a value: the value, with https:// before it when it names no scheme (WebFetchTool does the same). */
    static String requested(String v) { return v.matches("(?is)^[a-z][a-z0-9+.-]*://.*") ? v : "https://" + v; }

    /** An address with the punctuation of the sentence after it taken off; a closing bracket stays when the address opened one ("…/Foo_(bar)"). */
    static String trimmed(String s) {
        while (!s.isEmpty()) {
            char c = s.charAt(s.length() - 1);
            if (".,;:!?'\"\u2019\u201d>".indexOf(c) >= 0) { s = s.substring(0, s.length() - 1); continue; }
            char open = c == ')' ? '(' : c == ']' ? '[' : c == '}' ? '{' : 0;
            if (open != 0 && s.chars().filter(x -> x == open).count() < s.chars().filter(x -> x == c).count()) { s = s.substring(0, s.length() - 1); continue; }
            break;
        }
        return s;
    }

    /**
     * The call's arguments with every address in them put back as it was given: what a tool is handed is a string the person, a
     * search, a page or a project file gave, wherever in the arguments it stands. A value that was not given is left as it is (the check
     * refuses it before this). {@code addresses}: the arguments this tool takes an address in beside the ones every tool does.
     */
    public static JsonNode asGiven(JsonNode args, Set<String> addresses) {
        if (args == null || !args.isObject()) return args;
        return putBack(args.deepCopy(), "", addresses == null ? Set.of() : addresses);
    }

    private static JsonNode putBack(JsonNode v, String key, Set<String> addresses) {
        if (v.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = v.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String name = e.getKey().toLowerCase(Locale.ROOT);
                if (QUERY_KEYS.contains(name) && !addressKey(name, addresses)) continue;   // a search's words go as the model wrote them
                e.setValue(putBack(e.getValue(), name, addresses));
            }
            return v;
        }
        if (v.isArray()) { ArrayNode list = (ArrayNode) v; for (int i = 0; i < list.size(); i++) list.set(i, putBack(list.get(i), key, addresses)); return list; }
        if (!v.isTextual()) return v;
        String was = asGiven(v.asText(), heldAsAddress(key, v.asText(), addresses));
        return was == null ? v : TextNode.valueOf(was);
    }

    private static final Pattern PIECE = Pattern.compile("\\S+");

    /** The spelling an address is requested in: as it was given; or, for an address written for a name or a number that was given, as CodeZaiku builds it. */
    private static String asRequestedOrBuilt(String address) {
        String as = asRequested(address);
        return as != null ? as : GivenIdentifiers.built(address);
    }

    /** A value with each address in it as it was given; null when nothing in it is a given address written another way ({@code bare}: the value is an address whatever it looks like). */
    private static String asGiven(String value, boolean bare) {
        String s = fold(value).strip();
        if (s.isEmpty()) return null;
        if (!s.matches("(?s).*\\s.*")) {
            if (!bare && !s.matches("(?i)^https?://.*")) return null;
            return asRequestedOrBuilt(requested(s));
        }
        if (bare) return asRequested(requested(s));
        Matcher m = PIECE.matcher(value);
        StringBuilder out = new StringBuilder();
        int at = 0;
        boolean changed = false;
        while (m.find()) {
            String piece = fold(m.group());
            String as = piece.matches("(?i)^https?://.*") ? asRequestedOrBuilt(piece) : null;
            out.append(value, at, m.start()).append(as == null ? m.group() : as);
            if (as != null && !as.equals(m.group())) changed = true;
            at = m.end();
        }
        return changed ? out.append(value, at, value.length()).toString() : null;
    }

    /**
     * The first address in a call's arguments that was not given, or null when there is none. An address anywhere in a value's text
     * counts; a search's words are not looked at. {@code addresses}: the arguments this tool says it takes an address in, beside the ones
     * every tool does ({@link #addressKey}). {@code othersTool}: a tool of another program's MCP server, whose {@code address} means
     * what that server means by it (a street for a map service, a mail address), and is held only when it is written as an address of the web.
     */
    public static String notGiven(JsonNode args, Set<String> addresses, boolean othersTool) {
        Set<String> declared = addresses == null ? Set.of() : addresses;
        if (othersTool && args != null && args.isObject() && args.has("address")) {
            String v = args.path("address").asText("");
            String s = fold(v).strip();
            if (webShaped(s) && !meant(requested(s))) return v.strip();
            ObjectNode a = ((ObjectNode) args).deepCopy();
            a.set("address_in_words", a.remove("address"));
            return notGiven(a, "", declared);
        }
        return notGiven(args, "", declared);
    }

    private static String notGiven(JsonNode v, String key, Set<String> addresses) {
        if (v == null) return null;
        if (v.isTextual()) return textNotGiven(v.asText(), heldAsAddress(key, v.asText(), addresses));
        if (v.isArray()) { for (JsonNode x : v) { String r = notGiven(x, key, addresses); if (r != null) return r; } return null; }
        if (v.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = v.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String k = e.getKey().toLowerCase(Locale.ROOT);
                if (QUERY_KEYS.contains(k) && !addressKey(k, addresses)) continue;
                String r = notGiven(e.getValue(), k, addresses);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** The arguments that are an address in every tool that has them: a fetch's {@code url} and {@code address}, a list of {@code urls}, a {@code feed}. */
    static final Set<String> BARE_KEYS = Set.of("url", "address", "urls", "feed");

    /** An argument whose name says address in a tool CodeZaiku does not know: uri, link, href, and a name that ends in url, uri or link. Held when its value is written as an address of the web. */
    static final Pattern NAMED_AN_ADDRESS = Pattern.compile("(?:.*_)?(?:url|urls|uri|uris|link|links|href)");

    static boolean addressKey(String name, Set<String> addresses) {
        return name != null && !name.isEmpty() && (BARE_KEYS.contains(name) || addresses.contains(name));
    }

    /** A value written as an address of the web without its scheme: a host that ends in letters, or a machine by its numbers, a path after it or none, a user before it or none. */
    static boolean webShaped(String value) {
        String s = fold(value).strip();
        if (s.isEmpty() || s.matches("(?s).*\\s.*") || FILE_LIKE.matcher(s).matches()) return false;
        int at = s.indexOf('@'), slash = s.indexOf('/');
        if (at > 0 && slash < 0) return false;                  // user@host with nothing after it is a mail address
        if (at > 0 && at < slash) s = s.substring(at + 1);      // user@host/path is a request to the host
        if (NUMBERS_ALONE.matcher(s).matches()) return !GivenWords.stated(s);
        return bareWhole(s) || NUMBERED.matcher(s).matches();
    }

    private static boolean heldAsAddress(String name, String value, Set<String> addresses) {
        return addressKey(name, addresses) || (name != null && NAMED_AN_ADDRESS.matcher(name).matches() && webShaped(value));
    }

    private static String textNotGiven(String v, boolean bareCounts) {
        String s = fold(v).strip();
        if (s.isEmpty()) return null;
        // a fetch's url is the address it will request, whatever it looks like: https:// is put before a value without a scheme
        if (bareCounts) return meant(requested(s)) ? null : v.strip();
        if (FILE_LIKE.matcher(s).matches()) return null;
        if (s.indexOf(' ') < 0 && URL.matcher(s).matches()) return meant(s) ? null : v.strip();
        // a piece that begins as an address is one, whole, to the next white space, whatever stands in it
        for (String piece : s.split("\\s+")) if (piece.matches("(?i)^https?://.*") && !meant(piece)) return piece;
        Matcher m = URL.matcher(s);
        while (m.find()) if (!meant(m.group())) return m.group();
        return null;
    }

    /**
     * Whether a call may hold this address: it was given, or it is an address CodeZaiku builds for a name or a number that was given
     * ({@link GivenIdentifiers}), or it asks a service CodeZaiku is written for in that service's own shape, the model's words in the
     * places the service takes words ({@link Outbound#service}).
     */
    static boolean meant(String address) { return given(address) || GivenIdentifiers.opens(address) || Outbound.service(address); }

    /** What the model is told of an address it was not given: what it can open, in two short sentences that name no address. */
    public static final String REFUSED = "NOT OPENED: this address was not given. Open an address as it stands in the person's words, in a search result, in a page you read or in a file of the project, or search for the page you need.";

    /**
     * An address as it is compared: the sentence's punctuation after it and the fragment taken off, the scheme set aside, the host in
     * small letters, the scheme's own port left off, the path's escapes read, a slash after the path left off, "www." before the host
     * left off. What is requested is the spelling that was given ({@link #asRequested}). Null for no address.
     */
    static String key(String a) {
        if (a == null) return null;
        String s = trimmed(fold(a).strip());
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        Matcher scheme = SCHEME.matcher(s);
        boolean written = scheme.find();
        String rest = written ? s.substring(scheme.end()) : s;
        if (rest.isEmpty() || rest.startsWith("/")) return null;
        int cut = rest.length();
        for (char c : new char[]{'/', '?'}) { int i = rest.indexOf(c); if (i >= 0 && i < cut) cut = i; }
        String host = withoutWww(site(written ? scheme.group(1).toLowerCase(Locale.ROOT) : "https", rest.substring(0, cut).toLowerCase(Locale.ROOT)));
        String after = rest.substring(cut);
        int q = after.indexOf('?');
        String path = q < 0 ? after : after.substring(0, q), query = q < 0 ? "" : after.substring(q);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return "//" + host + unescaped(path + query);
    }

    private static final Pattern SCHEME = Pattern.compile("(?i)^(https?)://");

    /** The %XX escapes of a path read as UTF-8; anything else as it is. */
    public static String unescaped(String p) {
        if (p.indexOf('%') < 0) return p;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i < p.length(); ) {
            if (p.charAt(i) == '%' && i + 2 < p.length()) {
                int hi = Character.digit(p.charAt(i + 1), 16), lo = Character.digit(p.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) { b.write(hi * 16 + lo); i += 3; continue; }
            }
            int cp = p.codePointAt(i);
            byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            b.write(bytes, 0, bytes.length);
            i += Character.charCount(cp);
        }
        return b.toString(StandardCharsets.UTF_8);
    }

    /** The host an address names, for the record of a refusal; the value itself when it names none. */
    public static String hostOf(String address) {
        if (address == null) return "";
        Matcher m = SCHEME_AND_HOST.matcher(requested(fold(address).strip()));
        return m.find() ? m.group(2).toLowerCase(Locale.ROOT).replaceFirst(":\\d{1,5}$", "") : address.strip();
    }
}
