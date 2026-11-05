package org.codezaiku.chat;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a request is the web chat's own (2026-11-04). On this computer with no token, whoever reaches the port is the owner, and the
 * page's buttons answer the questions that let the chat write and run things in the projects. So a page on another site that the person
 * opens could post a form here in their name: add a project, say something to its chat, answer the question it asks. And a site whose
 * name is pointed at 127.0.0.1 could load the pages under its own name and read them. Two checks close both. A form must come from the
 * chat's own page: the browser says where it came from, in Sec-Fetch-Site when it sends that, else in Origin, else in Referer. And with
 * no token, every request must ask for the chat by this computer's own name. A program's call that names no page goes on as before.
 */
final class OwnPage {
    private OwnPage() { }

    /** What a form from another site is answered with. */
    static final String FORM_REFUSED = "This form came from another site, so it was not carried out.";
    /** What a request under another name is answered with. */
    static final String NAME_REFUSED = "This page was asked for under a name that is not this computer's, so it was not served. Open it at http://127.0.0.1 or http://localhost.";

    private static final Pattern ORIGIN = Pattern.compile("([A-Za-z][A-Za-z0-9+.-]*)://([^/?#\\s]*)");
    private static final Set<String> THIS_COMPUTER = Set.of("localhost", "127.0.0.1", "[::1]");

    /** Whether a request may go on: anything but a POST, or a POST from the chat's own page, or a program's call that names no page. */
    static boolean formAllowed(HttpExchange x) {
        if (!"POST".equals(x.getRequestMethod())) return true;
        Headers h = x.getRequestHeaders();
        String site = h.getFirst("Sec-Fetch-Site");
        if (site != null && !site.isBlank()) {   // the browser's own word on where the request came from, which no page can change
            String s = site.strip().toLowerCase(Locale.ROOT);
            return s.equals("same-origin") || s.equals("none");
        }
        String origin = h.getFirst("Origin");
        if (origin != null && !origin.isBlank()) return own(origin, h.getFirst("Host"));
        String referer = h.getFirst("Referer");
        if (referer != null && !referer.isBlank()) return own(referer, h.getFirst("Host"));
        return true;   // a browser names where every form it posts across sites came from: naming none, this is a program's call
    }

    /** Whether an origin, or the address of the page a form was on, is the chat's own as the request's Host says. "null" never is. */
    static boolean own(String address, String host) {
        Matcher m = ORIGIN.matcher(address.strip());
        if (!m.lookingAt() || host == null || host.isBlank()) return false;
        String scheme = m.group(1).toLowerCase(Locale.ROOT), authority = m.group(2).toLowerCase(Locale.ROOT);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        if (authority.isEmpty()) return false;
        return withoutDefaultPort(authority, scheme).equals(withoutDefaultPort(host.strip().toLowerCase(Locale.ROOT), scheme));
    }

    /** Whether the Host a request came with names this computer: localhost, 127.0.0.1 or [::1], with any port. */
    static boolean thisComputer(String host) {
        if (host == null || host.isBlank()) return false;
        String h = host.strip().toLowerCase(Locale.ROOT);
        String name = h.startsWith("[") ? (h.indexOf(']') > 0 ? h.substring(0, h.indexOf(']') + 1) : h) : h.indexOf(':') >= 0 ? h.substring(0, h.indexOf(':')) : h;
        if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        return THIS_COMPUTER.contains(name);
    }

    static String withoutDefaultPort(String hostPort, String scheme) {
        String port = scheme.equals("https") ? ":443" : scheme.equals("http") ? ":80" : null;
        return port != null && hostPort.endsWith(port) ? hostPort.substring(0, hostPort.length() - port.length()) : hostPort;
    }
}
