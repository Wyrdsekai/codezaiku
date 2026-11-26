package org.codezaiku;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The update check CodeZaiku makes by itself, from doctor and from a chat's banner (in the terminal and in the web app): with
 * CODEZAIKU_UPDATE=off nothing is asked of GitHub; ResearchZosho's latest release is asked only where ResearchZosho is installed;
 * both are asked under CodeZaiku's own client name. What a person asks for by name, `codezaiku update`, still asks. GitHub is a
 * stand-in on the loopback, the home is the test's own, and ResearchZosho is a stand-in script.
 */
class UpdateCheckTest {

    @TempDir Path home;
    final String realHome = System.getProperty("user.home");
    final String realApi = System.getProperty("codezaiku.github.api");
    final Supplier<Path> realWhere = ResearchZoshoInstall.whereInstalled;
    /** What the stand-in GitHub was asked: the path, then the User-Agent. */
    final List<String> asked = new CopyOnWriteArrayList<>();
    HttpServer github;

    @BeforeEach void standIn() throws IOException {
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        github.createContext("/", x -> {
            asked.add(x.getRequestURI().getPath() + " " + x.getRequestHeaders().getFirst("User-Agent"));
            byte[] b = "{\"tag_name\":\"v99.0.0\"}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, b.length);
            try (var o = x.getResponseBody()) { o.write(b); }
        });
        github.start();
        System.setProperty("codezaiku.github.api", "http://127.0.0.1:" + github.getAddress().getPort());
        ResearchZoshoInstall.whereInstalled = () -> null;
    }

    @AfterEach void back() {
        joinChecks();
        github.stop(0);
        ResearchZoshoInstall.whereInstalled = realWhere;
        if (realApi == null) System.clearProperty("codezaiku.github.api"); else System.setProperty("codezaiku.github.api", realApi);
        System.setProperty("user.home", realHome);
        Config.invalidate();
    }

    void mode(String m) throws IOException {
        Files.createDirectories(home.resolve(".codezaiku"));
        Files.writeString(home.resolve(".codezaiku").resolve("config"), "update = " + m + "\n");
        Config.invalidate();
    }

    void researchZoshoInstalled(String version) throws IOException {
        Path launcher = new FakeResearchZosho(home.resolve("rz"), version).launcher;
        ResearchZoshoInstall.whereInstalled = () -> launcher;
    }

    /** The checks ask in a thread of their own; wait for every one that was started. */
    static void joinChecks() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals("codezaiku-version-check") || t.getName().equals("researchzosho-version-check")) {
                try { t.join(15_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }

    /** Everything doctor and a chat's banner call, as they call it. */
    static void everySurface() {
        SelfUpdate.maybeAuto();
        SelfUpdate.updateNotice();
        SelfUpdate.latestCached();
        ResearchZoshoInstall.updateNotice();
        if (ResearchZoshoInstall.installedVersion() != null) ResearchZoshoInstall.latestCached();
        joinChecks();
    }

    static final String CZ = "/repos/Wyrdsekai/codezaiku/releases/latest", RZ = "/repos/Wyrdsekai/researchzosho/releases/latest";

    @Test
    void offAsksGitHubNothingFromDoctorOrTheChat() throws Exception {
        Assumptions.assumeFalse(ResearchZoshoInstall.windows(), "the stand-in ResearchZosho is a shell script");
        researchZoshoInstalled("0.1.0");
        mode("off");
        assertEquals("off", SelfUpdate.mode());
        everySurface();
        assertEquals(List.of(), asked, "off: no request for either program's latest release");
        // what was known before the check was turned off is not said either
        Files.writeString(SelfUpdate.latestCache(), "99.0.0");
        Files.createDirectories(ResearchZoshoInstall.latestCache().getParent());
        Files.writeString(ResearchZoshoInstall.latestCache(), "99.0.0");
        assertEquals("", SelfUpdate.updateNotice());
        assertEquals("", ResearchZoshoInstall.updateNotice());
        assertNull(SelfUpdate.latestCached());
        joinChecks();
        assertEquals(List.of(), asked);

        // the control: the same calls in the default mode ask the stand-in, once for each program, so it would have seen a request
        mode("check");
        Files.delete(SelfUpdate.latestCache());
        Files.delete(ResearchZoshoInstall.latestCache());
        everySurface();
        assertTrue(asked.stream().anyMatch(a -> a.startsWith(CZ + " ")) && asked.stream().anyMatch(a -> a.startsWith(RZ + " ")), asked.toString());
    }

    @Test
    void aPersonsOwnUpdateCommandStillAsksWhenOff() throws Exception {
        mode("off");
        String status = SelfUpdate.status();
        assertTrue(status.contains("latest:    99.0.0") && status.contains("mode:      off"), status);
        assertTrue(SelfUpdate.statusJson().contains("\"latest\":\"99.0.0\""));
        assertEquals(List.of(CZ + " CodeZaiku/" + FamiliarMain.VERSION, CZ + " CodeZaiku/" + FamiliarMain.VERSION), asked);
    }

    @Test
    void researchZoshosLatestReleaseIsAskedOnlyWhereItIsInstalled() throws Exception {
        Assumptions.assumeFalse(ResearchZoshoInstall.windows(), "the stand-in ResearchZosho is a shell script");
        mode("check");
        assertEquals("", ResearchZoshoInstall.updateNotice());
        everySurface();
        assertFalse(asked.stream().anyMatch(a -> a.startsWith(RZ + " ")), "no ResearchZosho here: its latest release is not asked: " + asked);
        assertTrue(asked.stream().anyMatch(a -> a.startsWith(CZ + " ")), "CodeZaiku's own check went to the stand-in (the control): " + asked);
        assertFalse(Files.exists(ResearchZoshoInstall.latestCache()));

        researchZoshoInstalled("0.1.0");
        Files.delete(SelfUpdate.latestCache());
        asked.clear();
        everySurface();
        assertTrue(asked.contains(RZ + " CodeZaiku/" + FamiliarMain.VERSION), "installed: asked, under CodeZaiku's own name: " + asked);
        assertTrue(asked.contains(CZ + " CodeZaiku/" + FamiliarMain.VERSION), asked.toString());
        assertEquals("ResearchZosho 99.0.0 is available (installed: 0.1.0). `codezaiku update now` has ResearchZosho's own updater install it; the library and settings stay.",
                ResearchZoshoInstall.updateNotice());
    }

    @Test
    void theSuiteAsksNoGitHub() {
        Assumptions.assumeTrue(System.getenv("CODEZAIKU_CTX") != null, "under the Gradle test task");
        assertEquals("http://127.0.0.1:9", realApi, "the test task points GitHub's API at a port nothing listens on");
    }
}
