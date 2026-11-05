package org.codezaiku.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The web chat carries out a form only from its own page, and with no token answers only to this computer's own name. */
class OwnPageTest {
    static final HttpClient H = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    static HttpResponse<String> post(int port, String path, String form, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/x-www-form-urlencoded");
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return H.send(b.POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A request as a browser sends it after a name was pointed at 127.0.0.1: its Host is that name (the JDK's client will not send another Host). */
    static String underName(int port, String host, String path) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void anOriginOrAPagesAddressIsOwnOnlyWhenItIsTheHostTheRequestCameWith() {
        assertTrue(OwnPage.own("http://127.0.0.1:7072", "127.0.0.1:7072"));
        assertTrue(OwnPage.own("http://127.0.0.1:7072/chat?project=alpha", "127.0.0.1:7072"), "a page's address names its origin");
        assertTrue(OwnPage.own("http://localhost", "localhost:80"), "the default port is the same address");
        assertFalse(OwnPage.own("https://elsewhere.example", "127.0.0.1:7072"));
        assertFalse(OwnPage.own("http://127.0.0.1:7073", "127.0.0.1:7072"), "another port is another site's page");
        assertFalse(OwnPage.own("null", "127.0.0.1:7072"), "a sandboxed page's origin is no one's");
        assertFalse(OwnPage.own("http://127.0.0.1:7072@elsewhere.example", "127.0.0.1:7072"), "the address after the @ is the one that counts");
    }

    @Test
    void thisComputersNamesAreLoopbackOnes() {
        for (String h : List.of("127.0.0.1:7072", "localhost:7072", "LOCALHOST", "[::1]:7072", "localhost.")) assertTrue(OwnPage.thisComputer(h), h);
        for (String h : List.of("elsewhere.example:7072", "127.0.0.1.elsewhere.example:7072", "localhost.elsewhere.example", "192.168.1.5:7072", "", "[::2]:7072")) assertFalse(OwnPage.thisComputer(h), h);
        assertFalse(OwnPage.thisComputer(null));
    }

    @Test
    void aFormFromAnotherSiteIsRefusedAndOneFromTheChatsOwnPageIsCarriedOut(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectories(tmp.resolve("alpha")), b = Files.createDirectories(tmp.resolve("beta")), registry = tmp.resolve("home/.codezaiku/web/projects");
        WebChat web = new WebChat(List.of(a), "http://127.0.0.1:9", "local-model", null, ChatConsent.Mode.ASK, 5, registry);
        web.start("127.0.0.1", 0);
        int port = web.port();
        String own = "http://127.0.0.1:" + port;
        try {
            var fromElsewhere = post(port, "/projects/add", "path=" + b, "Origin", "https://elsewhere.example");
            assertEquals(403, fromElsewhere.statusCode());
            assertEquals(OwnPage.FORM_REFUSED, fromElsewhere.body());
            assertFalse(Files.isRegularFile(registry) && Files.readString(registry).contains("beta"), "nothing of a refused form is done");
            assertEquals(403, post(port, "/chat/say", "project=alpha&say=yes", "Sec-Fetch-Site", "cross-site").statusCode(), "the browser's own word that it came from elsewhere");
            assertEquals(403, post(port, "/chat/say", "project=alpha&say=yes", "Sec-Fetch-Site", "same-site", "Origin", own).statusCode(), "a neighbour on the same site is not the chat's own page");
            assertEquals(403, post(port, "/chat/say", "project=alpha&say=yes", "Referer", "https://elsewhere.example/page").statusCode(), "no Origin: the page it was on");
            assertEquals(303, post(port, "/projects/add", "path=" + b, "Origin", own).statusCode(), "the chat's own page");
            assertTrue(Files.readString(registry).contains("beta"));
            assertEquals(303, post(port, "/projects/remove", "project=beta", "Sec-Fetch-Site", "same-origin").statusCode());
            assertEquals(303, post(port, "/projects/add", "path=" + b, "Referer", own + "/").statusCode(), "no Origin, the chat's own page as the referrer");
            assertEquals(303, post(port, "/projects/remove", "project=beta").statusCode(), "a program's call that names no page goes on");
        } finally { web.stop(); }
    }

    @Test
    void withNoTokenTheChatAnswersOnlyToThisComputersName(@TempDir Path tmp) throws Exception {
        Path a = Files.createDirectories(tmp.resolve("alpha"));
        WebChat web = new WebChat(List.of(a), "http://127.0.0.1:9", "local-model", null, ChatConsent.Mode.ASK, 5, tmp.resolve("projects"));
        web.start("127.0.0.1", 0);
        int port = web.port();
        try {
            String renamed = underName(port, "elsewhere.example:" + port, "/");
            assertTrue(renamed.startsWith("HTTP/1.1 403") && renamed.contains(OwnPage.NAME_REFUSED), renamed);
            assertTrue(underName(port, "elsewhere.example:" + port, "/chat/log?project=alpha&since=0").startsWith("HTTP/1.1 403"), "no transcript read under another name");
            assertTrue(underName(port, "localhost:" + port, "/").startsWith("HTTP/1.1 200"), "this computer's own name");
            assertTrue(underName(port, "127.0.0.1:" + port, "/health").startsWith("HTTP/1.1 200"));
        } finally { web.stop(); }
        WebChat signed = new WebChat(List.of(a), "http://127.0.0.1:9", "local-model", null, ChatConsent.Mode.ASK, 5, tmp.resolve("projects2")).token("open-sesame");
        signed.start("127.0.0.1", 0);
        try {
            String any = underName(signed.port(), "chat.example:" + signed.port(), "/");
            assertFalse(any.startsWith("HTTP/1.1 403"), "with a token the name is free: the token is what signs a person in: " + any.lines().findFirst().orElse(""));
        } finally { signed.stop(); }
    }
}
