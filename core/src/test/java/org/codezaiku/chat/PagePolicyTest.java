package org.codezaiku.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one content policy of the web chat's pages, and what makes it possible (ported from ResearchZosho 0.8.1's PagePolicyTest): no
 * page holds a script or a handler of its own (its scripts are the chat's own files), and no page loads anything from another address. A
 * tag a model's reply got into a page could then neither run nor call out; that it does not is checked in a real browser outside the
 * suite. Sign-out is a button, refused from another site.
 */
class PagePolicyTest {

    /** One attribute of one tag of a page: the tag's name, the attribute's, its value. */
    record Attr(String tag, String name, String value) { }

    /** Every tag of a page with its attributes ({@code name} "" for a tag with none), as a browser reads them. */
    static List<Attr> tags(String html) {
        List<Attr> out = new ArrayList<>();
        int i = 0, n = html.length();
        while ((i = html.indexOf('<', i)) >= 0) {
            int j = i + 1;
            if (j >= n || !Character.isLetter(html.charAt(j))) { i = j; continue; }
            int k = j;
            while (k < n && (Character.isLetterOrDigit(html.charAt(k)) || html.charAt(k) == '-')) k++;
            String tag = html.substring(j, k).toLowerCase();
            boolean any = false;
            while (k < n && html.charAt(k) != '>') {
                while (k < n && (Character.isWhitespace(html.charAt(k)) || html.charAt(k) == '/')) k++;
                if (k >= n || html.charAt(k) == '>') break;
                int a = k;
                while (k < n && !Character.isWhitespace(html.charAt(k)) && "=>/".indexOf(html.charAt(k)) < 0) k++;
                String name = html.substring(a, k).toLowerCase(), value = "";
                while (k < n && Character.isWhitespace(html.charAt(k))) k++;
                if (k < n && html.charAt(k) == '=') {
                    k++;
                    while (k < n && Character.isWhitespace(html.charAt(k))) k++;
                    if (k < n && (html.charAt(k) == '"' || html.charAt(k) == '\'')) { char q = html.charAt(k); int e = html.indexOf(q, k + 1); if (e < 0) e = n; value = html.substring(k + 1, e); k = Math.min(n, e + 1); }
                    else { int v = k; while (k < n && !Character.isWhitespace(html.charAt(k)) && html.charAt(k) != '>') k++; value = html.substring(v, k); }
                }
                if (!name.isEmpty()) { out.add(new Attr(tag, name, value)); any = true; } else k++;
            }
            if (!any) out.add(new Attr(tag, "", ""));
            i = k;
        }
        return out;
    }

    /** What of a page breaks the policy's premise: a script written into it, a handler in a tag, anything loaded from another address. */
    static List<String> breaks(String html) {
        List<String> out = new ArrayList<>();
        int scripts = 0, withSrc = 0;
        for (Attr a : tags(html)) {
            if (a.name().startsWith("on")) out.add("a handler in a tag: <" + a.tag() + " " + a.name() + "=…>");
            if (a.tag().equals("script") && a.name().equals("src")) { withSrc++; if (!a.value().startsWith("/static/")) out.add("a script from " + a.value()); }
            if (a.tag().equals("img") && a.name().equals("src") && !(a.value().startsWith("/") && !a.value().startsWith("//"))) out.add("a picture from " + a.value());
            if (Set.of("base", "iframe", "object", "embed").contains(a.tag())) out.add("a <" + a.tag() + "> tag");
            if (a.tag().equals("form") && a.name().equals("action") && !a.value().startsWith("/")) out.add("a form posted to " + a.value());
            if (a.tag().equals("link") && a.name().equals("href") && !a.value().startsWith("/")) out.add("a file linked from " + a.value());
        }
        Matcher m = Pattern.compile("(?i)<script\\b").matcher(html);
        while (m.find()) scripts++;
        if (scripts != withSrc) out.add((scripts - withSrc) + " script(s) written into the page");
        return out;
    }

    static HttpResponse<String> get(HttpClient c, String url) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theInstrumentSeesAScriptAHandlerAndAnOutsidePicture() {
        assertEquals(List.of(), breaks("<p class=\"x\">on=1 <a href=\"https://example.org/?onclick=1\" title=\"turn on=now\">x</a><script src=\"/static/cz-chat.js\"></script></p>"));
        assertEquals(1, breaks("<script>alert(1)</script>").size());
        assertEquals(1, breaks("<form method=\"post\" onsubmit=\"return confirm('x')\" action=\"/x\">").size());
        assertEquals(1, breaks("<img alt=\"a > b\" src=\"https://collector.example.net/x\">").size());
        assertEquals(1, breaks("<img src=\"//collector.example.net/x\">").size());
        assertEquals(1, breaks("<base href=\"https://collector.example.net/\">").size());
        assertEquals(1, breaks("<script src=\"https://collector.example.net/x.js\"></script>").size());
    }

    @Test
    void everyPageCarriesTheOnePolicyAndHoldsNoScriptOrHandlerOfItsOwn(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectories(tmp.resolve("alpha"));
        WebChat web = new WebChat(List.of(a), "http://127.0.0.1:9", "local-model", null, ChatConsent.Mode.ASK, 5, null);
        web.start("127.0.0.1", 0);
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            String base = "http://127.0.0.1:" + web.port();
            int pages = 0;
            Set<String> scripts = new TreeSet<>();
            for (String path : List.of("/", "/chat?project=alpha", "/login", "/logout", "/people", "/projects", "/chat/log?project=alpha", "/health", "/no-such-page")) {
                HttpResponse<String> r = get(c, base + path);
                assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(""), path + ": nothing the chat serves is read as another type than it says");
                assertEquals(WebChat.POLICY, r.headers().firstValue("Content-Security-Policy").orElse(""), path);
                if (!r.headers().firstValue("Content-Type").orElse("").startsWith("text/html")) continue;
                pages++;
                assertEquals(List.of(), breaks(r.body()), path);
                for (Attr t : tags(r.body())) if (t.tag().equals("script") && t.name().equals("src")) scripts.add(t.value());
            }
            assertTrue(pages >= 5, "the pages were read: " + pages);
            assertTrue(WebChat.POLICY.startsWith("default-src 'none';") && WebChat.POLICY.contains("script-src 'self';") && WebChat.POLICY.contains("img-src 'self';")
                    && WebChat.POLICY.contains("connect-src 'self';") && WebChat.POLICY.contains("form-action 'self';") && WebChat.POLICY.contains("base-uri 'none'"), WebChat.POLICY);
            assertFalse(WebChat.POLICY.contains("http") || WebChat.POLICY.contains("*") || WebChat.POLICY.contains("unsafe-eval") || WebChat.POLICY.matches(".*script-src[^;]*unsafe-inline.*"), WebChat.POLICY);
            // each script a page names is a file of the chat's own, served as a script, with the program's text and nothing else
            assertEquals(Set.of("/static/cz-theme.js", "/static/cz-shell.js", "/static/cz-chat.js"), scripts);
            for (String s : scripts) {
                HttpResponse<String> r = get(c, base + s);
                assertEquals(200, r.statusCode(), s);
                assertEquals("text/javascript; charset=utf-8", r.headers().firstValue("Content-Type").orElse(""), s);
                assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(""), s);
                assertEquals(WebChat.SCRIPTS.get(s.substring("/static/cz-".length(), s.length() - 3)), r.body(), s);
            }
            assertEquals(404, get(c, base + "/static/cz-../../etc.js").statusCode());
            // a form that asks first says what it asks, for the chat's own script; signing out is a button, and a link only shows it
            assertTrue(get(c, base + "/").body().contains("data-confirm=\"Take alpha off the list?"));
            HttpResponse<String> out = get(c, base + "/logout");
            assertEquals(200, out.statusCode());
            assertTrue(out.body().contains("<form method=\"post\" action=\"/logout\">") && out.headers().allValues("Set-Cookie").isEmpty(), "a GET signs nobody out");
        } finally { web.stop(); }
    }

    @Test
    void signingOutFromAnotherSiteIsRefused(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectories(tmp.resolve("alpha"));
        WebChat web = new WebChat(List.of(a), "http://127.0.0.1:9", "local-model", null, ChatConsent.Mode.ASK, 5, null).token("open-sesame");
        web.start("127.0.0.1", 0);
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            String base = "http://127.0.0.1:" + web.port();
            HttpResponse<String> away = c.send(HttpRequest.newBuilder(URI.create(base + "/logout")).header("Cookie", "cz=open-sesame").header("Origin", "https://collector.example.net")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, away.statusCode());
            assertTrue(away.headers().allValues("Set-Cookie").isEmpty());
            HttpResponse<String> own = c.send(HttpRequest.newBuilder(URI.create(base + "/logout")).header("Cookie", "cz=open-sesame").header("Origin", base)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(303, own.statusCode());
            assertTrue(own.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=0"));
        } finally { web.stop(); }
    }

    @Test
    void theChatsScriptEscapesQuotesAndNeverMakesAPicture() {
        // the script is the page's only writer of a model's words; read in a browser outside the suite, and its premises pinned here
        String js = WebChat.SCRIPTS.get("chat");
        assertTrue(js.contains(".replace(/\"/g,'&quot;')") && js.contains(".replace(/'/g,'&#39;')"), "a quote in a reply cannot end an attribute");
        assertFalse(js.contains("<img") || js.contains("createElement('img')") || js.contains("new Image"), "no picture is ever made from a reply");
        assertTrue(js.contains("class=\"leads\""), "a link shows the host it leads to");
    }
}
