package org.codezaiku.research;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.codezaiku.Config;
import org.codezaiku.ResearchZoshoInstall;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A library that refuses anonymous readers (its catalog/patrons.md says {@code default: deny}) answers 403 to a call without a
 * token. CodeZaiku asked {@code /v1/status} without one and took the 403 for "no library": doctor said the daemon was not
 * answering and every use of the library was skipped, though a token was set and the library was up. The stand-in answers as
 * ResearchZosho 0.8.3 does: {@code /healthz} with no token, the protocol's calls only with the one token it knows.
 */
class LibraryDoorTest {

    HttpServer library;
    final List<String> asked = new CopyOnWriteArrayList<>();
    boolean health = true;   // false: a ResearchZosho from before 0.7.0, which has no health address
    final BooleanSupplier realInstalled = LibraryBridge.installed;

    @BeforeEach void standIn() throws IOException {
        library = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        library.createContext("/", x -> {
            String path = x.getRequestURI().getPath(), auth = x.getRequestHeaders().getFirst("Authorization");
            asked.add(x.getRequestMethod() + " " + path + (auth == null ? "" : " " + auth));
            if (path.equals("/healthz") && health) { reply(x, 200, "{\"version\":\"0.8.3\",\"ok\":true,\"library_id\":\"lib-1\",\"library_name\":\"t\"}"); return; }
            if (!path.startsWith("/v1/")) { reply(x, 404, "{\"error\":{\"code\":\"not_found\",\"message\":\"no such route\"}}"); return; }
            if (auth == null) { reply(x, 403, "{\"error\":{\"code\":\"forbidden\",\"message\":\"An anonymous patron may not read this library; the person who keeps it edits catalog/patrons.md.\"}}"); return; }
            if (!auth.equals("Bearer the-good-token")) { reply(x, 403, "{\"error\":{\"code\":\"forbidden\",\"message\":\"That token does not match any user. The library owner makes tokens with `researchzosho reader token <did>`.\"}}"); return; }
            if (path.equals("/v1/status")) reply(x, 200, "{\"library_id\":\"lib-1\",\"library_name\":\"t\",\"contract\":\"1.3\"}");
            else reply(x, 200, "{\"library_id\":\"lib-1\",\"holds_nothing\":false,\"rendered\":\"THE LIBRARIAN — holdings relevant to: lead paint\\n== F-0001 lead paint was banned in 1978\\n\"}");
        });
        library.start();
        LibraryBridge.urlOverride = "http://127.0.0.1:" + library.getAddress().getPort();
        LibraryBridge.installed = () -> true;
        LibraryBridge.reprobe();
        LibraryBridge.forgetRefusalSaid();
    }

    @AfterEach void down() {
        library.stop(0);
        LibraryBridge.urlOverride = null;
        LibraryBridge.tokenOverride = null;
        LibraryBridge.installed = realInstalled;
        LibraryBridge.reprobe();
        LibraryBridge.forgetRefusalSaid();
    }

    static void reply(HttpExchange x, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(code, b.length);
        try (var o = x.getResponseBody()) { o.write(b); }
    }

    void token(String t) { LibraryBridge.tokenOverride = t == null ? "" : t; LibraryBridge.reprobe(); }

    @Test
    void aLibraryThatRefusesAnonymousReadersIsFoundAndUsedWithTheToken() {
        token("the-good-token");
        assertTrue(LibraryBridge.answers(), "the library is up and lets CodeZaiku in: " + asked);
        String push = LibraryBridge.push("lead paint", 4);
        assertTrue(push.startsWith("THE LIBRARIAN") && push.contains("F-0001"), push);
        assertTrue(LibraryBridge.answer("lead paint", 5).contains("F-0001"));
        assertTrue(asked.contains("GET /healthz"), "the health address is asked with no token: " + asked);
        assertTrue(asked.stream().filter(a -> a.startsWith("POST /v1/")).allMatch(a -> a.endsWith("Bearer the-good-token")), "every call carries the token: " + asked);
    }

    @Test
    void aTokenTheLibraryRefusesIsSaidForWhatItIs() {
        token("a-wrong-token");
        assertFalse(LibraryBridge.answers());
        assertEquals(LibraryBridge.Door.REFUSED, LibraryBridge.seen().door());
        assertEquals("", LibraryBridge.push("lead paint", 4), "nothing is pushed from a library that refuses");
        String said = LibraryBridge.answer("lead paint", 5);
        assertTrue(said.startsWith("the library at " + LibraryBridge.url() + " answers, but does not let CodeZaiku in: it does not accept the sign-in CodeZaiku has for it"), said);
        assertTrue(said.contains("That token does not match any user") && said.endsWith("To fix it: codezaiku install researchzosho --token"), said);
        // the look before a turn says it once, not at every turn
        assertEquals(said, LibraryBridge.refusedOnce(true));
        assertEquals("", LibraryBridge.refusedOnce(true));
        // where ResearchZosho is kept on another machine, the fix is made there
        assertTrue(LibraryBridge.refusedLine("", false).contains("on the computer that keeps the library, run researchzosho reader allow did:key:local-codezaiku write codezaiku"));
    }

    @Test
    void noTokenSetIsSaidAsSuch() {
        token(null);
        assertEquals(LibraryBridge.Door.REFUSED, LibraryBridge.seen().door());
        String said = LibraryBridge.answer("lead paint", 5);
        assertTrue(said.contains("CodeZaiku has not been given a sign-in for it") && said.contains("An anonymous patron may not read this library"), said);
    }

    @Test
    void noLibraryIsSaidAsBefore() {
        library.stop(0);
        LibraryBridge.reprobe();
        assertEquals(LibraryBridge.Door.NONE, LibraryBridge.seen().door());
        assertTrue(LibraryBridge.answer("lead paint", 5).startsWith("no library answers on"), LibraryBridge.answer("lead paint", 5));
        assertEquals("", LibraryBridge.refusedOnce(true));
    }

    @Test
    void aLibraryFromBefore070WithoutTheHealthAddressStillWorks() {
        health = false;
        token("the-good-token");
        assertTrue(LibraryBridge.answers(), asked.toString());
        assertTrue(LibraryBridge.push("lead paint", 4).contains("F-0001"));
        token("a-wrong-token");
        assertEquals(LibraryBridge.Door.REFUSED, LibraryBridge.seen().door(), "its status says it refuses: " + asked);
    }

    @Test
    void theTokenFlagMakesANewTokenWithTheInstalledResearchZoshoAndSaysWhatTheLibraryThenSays(@TempDir Path tmp) throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"), "the stand-in ResearchZosho is a shell script");
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString());   // the new token is stored in this home's settings
        Config.invalidate();
        // the installed command: `reader allow` and `reader token`, the token on the last line, as researchzosho prints it
        Path rz = tmp.resolve("researchzosho");
        Files.writeString(rz, "#!/bin/sh\nprintf '%s\\n' \"$*\" >> '" + tmp.resolve("calls") + "'\n"
                + "if [ \"$1 $2\" = \"reader token\" ]; then echo 'Token for did:key:local-codezaiku (shown once):'; echo the-good-token; fi\nexit 0\n");
        rz.toFile().setExecutable(true);
        try {
            token("a-wrong-token");
            assertEquals(LibraryBridge.Door.REFUSED, LibraryBridge.seen().door());
            LibraryBridge.tokenOverride = null;   // from here the token is the one in the settings
            var out = new ByteArrayOutputStream();
            assertEquals(0, ResearchZoshoInstall.newToken(rz, new PrintStream(out, true)), out.toString());
            assertEquals(List.of("reader allow did:key:local-codezaiku write codezaiku", "reader token did:key:local-codezaiku"), Files.readAllLines(tmp.resolve("calls")));
            assertEquals("the-good-token", LibraryBridge.token());
            assertTrue(out.toString().contains("lets CodeZaiku in") && LibraryBridge.answers(), out.toString());
            out.reset();
            assertEquals(1, ResearchZoshoInstall.newToken(null, new PrintStream(out, true)));
            assertTrue(out.toString().contains("not installed on this machine") && out.toString().contains("researchzosho reader token did:key:local-codezaiku"), out.toString());
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }
}
