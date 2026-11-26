package org.codezaiku.research;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A library at a configured address with no ResearchZosho installed on this computer, as for a library kept on another computer:
 * the bridge says what it is to CodeZaiku in the three cases, and the fix for a library kept elsewhere is made there. The
 * stand-in answers as ResearchZosho 0.8.3 does with its default level at deny.
 */
class LibraryElsewhereTest {

    HttpServer library;
    final BooleanSupplier realInstalled = LibraryBridge.installed;

    @BeforeEach void standIn() throws IOException {
        library = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        library.createContext("/", x -> {
            String path = x.getRequestURI().getPath(), auth = x.getRequestHeaders().getFirst("Authorization");
            if (path.equals("/healthz")) { reply(x, 200, "{\"version\":\"0.8.3\",\"ok\":true,\"library_id\":\"lib-1\",\"library_name\":\"t\"}"); return; }
            if (!"Bearer the-good-one".equals(auth)) { reply(x, 403, "{\"error\":{\"code\":\"forbidden\",\"message\":\"An anonymous patron may not read this library; the person who keeps it edits catalog/patrons.md.\"}}"); return; }
            reply(x, 200, "{\"library_id\":\"lib-1\",\"holds_nothing\":true,\"rendered\":\"\"}");
        });
        library.start();
        LibraryBridge.urlOverride = "http://127.0.0.1:" + library.getAddress().getPort();
        LibraryBridge.installed = () -> false;   // no ResearchZosho on this computer
        LibraryBridge.reprobe();
    }

    @AfterEach void down() {
        library.stop(0);
        LibraryBridge.urlOverride = null;
        LibraryBridge.tokenOverride = null;
        LibraryBridge.installed = realInstalled;
        LibraryBridge.reprobe();
    }

    static void reply(HttpExchange x, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(code, b.length);
        try (var o = x.getResponseBody()) { o.write(b); }
    }

    void signIn(String t) { LibraryBridge.tokenOverride = t == null ? "" : t; LibraryBridge.reprobe(); }

    @Test
    void withNothingInstalledHereTheLibraryAtTheAddressIsStillSeenInTheThreeCases() {
        assertTrue(LibraryBridge.configured(), "an address is set");
        signIn("the-good-one");
        assertEquals(LibraryBridge.Door.IN, LibraryBridge.seen().door());
        assertEquals("the library holds nothing on that", LibraryBridge.answer("lead paint", 5));
        signIn("another-one");
        assertEquals(LibraryBridge.Door.REFUSED, LibraryBridge.seen().door());
        assertTrue(LibraryBridge.answer("lead paint", 5).contains("does not accept the sign-in CodeZaiku has for it"), LibraryBridge.answer("lead paint", 5));
        library.stop(0);
        LibraryBridge.reprobe();
        assertEquals(LibraryBridge.Door.NONE, LibraryBridge.seen().door());
    }

    @Test
    void anAddressOfAnotherComputerIsElsewhereAndItsFixIsMadeThere() {
        LibraryBridge.urlOverride = "http://192.168.1.20:4649";   // nothing is asked of it here: only the address is read
        assertTrue(LibraryBridge.elsewhere());
        assertFalse(LibraryBridge.keptHere(), "even with a ResearchZosho installed here, its command reaches this computer's library, not that one");
        LibraryBridge.installed = () -> true;
        assertFalse(LibraryBridge.keptHere());
        String fix = LibraryBridge.refusedLine("", LibraryBridge.keptHere());
        assertTrue(fix.contains("on the computer that keeps the library, run researchzosho reader allow did:key:local-codezaiku write codezaiku")
                && fix.contains("codezaiku config set CODEZAIKU_LIBRARIAN_TOKEN --file codezaiku-signin.txt") && !fix.contains("install researchzosho --token"), fix);
        assertTrue(LibraryBridge.noneLine().contains("the one CodeZaiku is set to use") && !LibraryBridge.noneLine().contains("/setup librarian"), LibraryBridge.noneLine());
        for (String here : new String[]{"http://127.0.0.1:4649", "http://localhost:4649", "http://[::1]:4649"}) {
            LibraryBridge.urlOverride = here;
            assertFalse(LibraryBridge.elsewhere(), here);
            assertTrue(LibraryBridge.keptHere(), here);
        }
    }

    @Test
    void aLibraryThatCannotBeReachedIsSaidInTheSystemsWordsAndNothingElse() {
        library.stop(0);
        LibraryBridge.urlOverride = "http://127.0.0.1:9";   // nothing listens there: the system refuses the connection
        LibraryBridge.reprobe();
        LibraryBridge.Seen seen = LibraryBridge.seen();
        assertEquals(LibraryBridge.Door.NONE, seen.door());
        assertEquals("could not reach http://127.0.0.1:9: the connection could not be made (ConnectException)", seen.why());
        String said = LibraryBridge.noneLine() + seen.why();
        for (String cause : new String[]{"Mac", "macOS", "Local Network", "Privacy", "permission"})
            assertFalse(said.contains(cause), "no cause nobody measured: " + said);
    }
}
