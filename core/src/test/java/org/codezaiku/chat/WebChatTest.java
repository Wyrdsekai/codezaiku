package org.codezaiku.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The chat in the browser: the terminal chat's loop per project behind a page, several projects under one server, a switch that carries the place. */
class WebChatTest {
    static final HttpClient H = HttpClient.newHttpClient();
    static String get(int port, String path) throws Exception { return H.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString()).body(); }
    static HttpResponse<String> post(int port, String path, String form) throws Exception {
        return H.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }
    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    /** Polls the log until a line holds {@code want}, as the page does. */
    static JsonNode until(int port, String project, String want) throws Exception {
        ObjectMapper m = new ObjectMapper();
        for (int i = 0; i < 300; i++) {
            JsonNode j = m.readTree(get(port, "/chat/log?project=" + enc(project) + "&since=0"));
            for (JsonNode e : j.path("entries")) if (e.path("text").asText().contains(want)) return j;
            Thread.sleep(100);
        }
        fail("the log of " + project + " never held: " + want + "\n" + get(port, "/chat/log?project=" + enc(project) + "&since=0"));
        return null;
    }

    @Test
    void severalProjectsUnderOneServerEachWithItsOwnChatAndASwitchThatCarriesThePlace(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectories(tmp.resolve("alpha")), b = Files.createDirectories(tmp.resolve("beta")), registry = tmp.resolve("home/.codezaiku/web/projects");
        WebChat web = new WebChat(List.of(a), "http://127.0.0.1:9", "local-model", null, ChatConsent.Mode.ASK, 5, registry);
        web.start("127.0.0.1", 0);
        int port = web.port();
        try {
            assertTrue(get(port, "/health").contains("true"));
            String home = get(port, "/");
            assertTrue(home.contains("alpha") && home.contains("not started") && home.contains("/projects/add"), home);
            // a second project, added by its folder; the list is kept for the next start
            assertEquals(303, post(port, "/projects/add", "path=" + enc(b.toString())).statusCode());
            assertTrue(Files.readString(registry).contains("beta"), "the project list is kept");
            // an earlier conversation in alpha: the page opens on it and the chat continues it
            ChatSession earlier = ChatSession.start(a, "what is this project?"); earlier.log("user", "what is this project?"); earlier.log("agent", "A folder with nothing in it yet."); earlier.save();
            // opening a project starts its chat: the loop talks through the page
            String page = get(port, "/chat?project=alpha");
            assertTrue(page.contains("id=\"composer\"") && page.contains("/chat/log") && page.contains(">beta</button>") && page.contains("id=\"side\"") && page.contains("data-say=\"/undo\""), "the page: the composer, the poll, the switch to the other project in the menu, the menu's own buttons: " + page.substring(0, Math.min(600, page.length())));
            assertTrue(get(port, "/projects").contains("\"id\":\"alpha\""), "the menu's dots come from /projects");
            until(port, "alpha", "> ");   // the loop's own prompt, shown as pending
            JsonNode seeded = until(port, "alpha", "A folder with nothing in it yet.");
            assertTrue(seeded.path("entries").toString().contains("Earlier conversation, continued") && seeded.path("entries").toString().contains("what is this project?"), "the page opens on the last conversation: " + seeded.path("entries"));
            until(port, "alpha", "resumed ");   // and the chat picked it up
            assertTrue(post(port, "/chat/say", "project=alpha&say=" + enc("/sessions")).body().contains("true"));
            JsonNode j = until(port, "alpha", "what-is-this-project");   // /sessions lists the one the page opened on
            assertTrue(j.path("entries").toString().contains("\"me\""), "what the person sent is in the transcript");
            // the other project has its own chat, untouched
            get(port, "/chat?project=beta");
            until(port, "beta", "> ");
            String betaLog = get(port, "/chat/log?project=beta&since=0");
            assertFalse(betaLog.contains("what-is-this-project"), "beta's transcript is its own");
            // the switch: from alpha to beta, carrying where alpha got to as a note for beta's next message
            var sw = post(port, "/chat/switch", "from=alpha&to=beta");
            assertEquals(303, sw.statusCode()); assertTrue(sw.headers().firstValue("Location").orElse("").contains("project=beta"));
            until(port, "beta", "carried into your next message");
            String beta = get(port, "/chat/log?project=beta&since=0");
            assertTrue(beta.contains("Switched here from the project alpha"), beta);
            // the home page says where each one is
            String home2 = get(port, "/");
            assertTrue(home2.contains("ready") || home2.contains("asking you"), home2);
            assertEquals(303, post(port, "/chat/restart", "project=alpha").statusCode(), "a chat that ended can be started again from the page; a running one is left alone");
            assertEquals(404, H.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/chat/log?project=nope")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally { web.stop(); }
    }

    @Test
    void aFolderThatIsGoneIsLeftOffTheKeptListAndAProjectCanBeRemoved(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectories(tmp.resolve("alpha")), registry = tmp.resolve("web/projects");
        Files.createDirectories(registry.getParent());
        Files.writeString(registry, a + "\n" + tmp.resolve("gone-away") + "\n");
        WebChat web = new WebChat(List.of(), "http://127.0.0.1:9", "local-model", null, ChatConsent.Mode.ASK, 5, registry);
        assertEquals(1, web.projects().size()); assertEquals(1, web.skipped().size(), "the folder that is gone is said, not fatal");
        assertFalse(Files.readString(registry).contains("gone-away"), "and dropped from the kept list");
        web.start("127.0.0.1", 0);
        try {
            assertTrue(get(web.port(), "/").contains("/projects/remove"), "the front page offers to take a project off the list");
            assertEquals(303, post(web.port(), "/projects/remove", "project=alpha").statusCode());
            assertFalse(get(web.port(), "/projects").contains("alpha")); assertEquals("", Files.readString(registry).strip());
        } finally { web.stop(); }
    }

    @Test
    void onTheNetworkEveryRequestCarriesTheTokenTheBrowserWasGivenOnce(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectories(tmp.resolve("alpha"));
        WebChat web = new WebChat(List.of(a), "http://127.0.0.1:9", "local-model", null, ChatConsent.Mode.ASK, 5, null).token("open-sesame");
        web.start("127.0.0.1", 0);
        int port = web.port();
        HttpClient plain = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            assertTrue(get(port, "/health").contains("true"), "health needs no token");
            var r = plain.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(303, r.statusCode()); assertEquals("/login", r.headers().firstValue("Location").orElse(""));
            assertEquals(401, plain.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/chat/log?project=alpha")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "the page's own calls get a plain refusal");
            assertEquals(403, plain.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login")).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("token=wrong")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var ok = plain.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login")).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("token=open-sesame")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(303, ok.statusCode());
            String cookie = ok.headers().firstValue("Set-Cookie").orElse(""); assertTrue(cookie.startsWith("cz=open-sesame") && cookie.contains("HttpOnly"), cookie);
            var home = plain.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).header("Cookie", "cz=open-sesame").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, home.statusCode()); assertTrue(home.body().contains("alpha") && home.body().contains("Signed in with the token"), home.body());
            assertEquals(200, plain.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/chat/log?project=alpha")).header("Cookie", "cz=open-sesame").GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            String kept = WebChat.tokenKept(tmp.resolve("home/.codezaiku/web/token")); assertEquals(kept, WebChat.tokenKept(tmp.resolve("home/.codezaiku/web/token")), "a kept token stays the same"); assertTrue(kept.matches("[a-z0-9-]{23}"), kept);
        } finally { web.stop(); }
    }
}
