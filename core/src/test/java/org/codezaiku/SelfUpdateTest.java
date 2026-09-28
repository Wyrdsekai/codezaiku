package org.codezaiku;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.codezaiku.SelfUpdate.Outcome;
import org.codezaiku.SelfUpdate.Result;
/** The self-update: the release's tarball is fetched, checked against SHA256SUMS, and swapped in with two renames; a bad checksum leaves the install as it was. */
class SelfUpdateTest {

    /** A fake install root with one jar and one launcher, and a fake release: a tarball of a newer root plus SHA256SUMS, served locally. */
    static String serve(Path dir, HttpServer s) {
        // like GitHub: the release URL answers 302 to the store that holds the bytes; an updater that does not follow it fails
        s.createContext("/", x -> {
            String path = x.getRequestURI().getPath();
            if (!path.startsWith("/store/")) { x.getResponseHeaders().set("Location", "/store" + path); x.sendResponseHeaders(302, -1); return; }
            Path f = dir.resolve(path.substring("/store/".length()));
            if (!Files.exists(f)) { x.sendResponseHeaders(404, -1); return; }
            byte[] b = Files.readAllBytes(f);
            x.sendResponseHeaders(200, b.length);
            try (var o = x.getResponseBody()) { o.write(b); }
        });
        s.start();
        return "http://127.0.0.1:" + s.getAddress().getPort();
    }

    static void fakeRoot(Path root, String version) throws Exception {
        Files.createDirectories(root.resolve("lib")); Files.createDirectories(root.resolve("bin"));
        Files.writeString(root.resolve("lib").resolve("core-" + version + ".jar"), "jar " + version);
        Files.writeString(root.resolve("bin").resolve("codezaiku"), "#!/bin/sh\necho " + version + "\n");
    }

    @Test
    void aVerifiedTarballIsSwappedInAndTheOldInstallIsGone(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("share").resolve("codezaiku"); fakeRoot(root, "0.1.1");
        Path release = tmp.resolve("release"); Files.createDirectories(release);
        Path stage = tmp.resolve("stage"); fakeRoot(stage.resolve("codezaiku"), "0.1.2");
        assertEquals(0, new ProcessBuilder("tar", "czf", release.resolve("codezaiku-0.1.2.tar.gz").toString(), "-C", stage.toString(), "codezaiku").inheritIO().start().waitFor());
        Files.writeString(release.resolve("SHA256SUMS"), SelfUpdate.sha256(release.resolve("codezaiku-0.1.2.tar.gz")) + "  codezaiku-0.1.2.tar.gz\n");
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            String base = serve(release, s);
            var out = new ByteArrayOutputStream();
            SelfUpdate.swapIn(root, "0.1.2", base, new PrintStream(out, true));
            assertTrue(Files.exists(root.resolve("lib").resolve("core-0.1.2.jar")), "the new jar is in place");
            assertFalse(Files.exists(root.resolve("lib").resolve("core-0.1.1.jar")), "the old jar is gone");
            assertFalse(Files.exists(root.resolveSibling("codezaiku.old")), "the old root was removed after the swap");
            assertTrue(out.toString().contains("checksum verified") && out.toString().contains("0.1.2 is in place"), out.toString());
            try (var l = Files.list(tmp.resolve("share"))) { assertEquals(1, l.count(), "no update work directory is left behind"); }
        } finally { s.stop(0); }
    }

    @Test
    void aBadChecksumIsRefusedAndNothingChanges(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("share").resolve("codezaiku"); fakeRoot(root, "0.1.1");
        Path release = tmp.resolve("release"); Files.createDirectories(release);
        Path stage = tmp.resolve("stage"); fakeRoot(stage.resolve("codezaiku"), "0.1.2");
        assertEquals(0, new ProcessBuilder("tar", "czf", release.resolve("codezaiku-0.1.2.tar.gz").toString(), "-C", stage.toString(), "codezaiku").inheritIO().start().waitFor());
        Files.writeString(release.resolve("SHA256SUMS"), "0000000000000000000000000000000000000000000000000000000000000000  codezaiku-0.1.2.tar.gz\n");
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            String base = serve(release, s);
            var e = assertThrows(Exception.class, () -> SelfUpdate.swapIn(root, "0.1.2", base, new PrintStream(new ByteArrayOutputStream())));
            assertTrue(e.getMessage().contains("checksum mismatch"), e.getMessage());
            assertTrue(Files.exists(root.resolve("lib").resolve("core-0.1.1.jar")), "the install is as it was");
        } finally { s.stop(0); }
    }

    @Test
    void theModeIsCheckUnlessSetAndTheStatusSaysSo(@TempDir Path tmp) {
        assertEquals("check", SelfUpdate.mode());
        String status = SelfUpdate.status(null, "99.0.0", tmp.resolve("update.lock"));
        assertTrue(status.startsWith("CodeZaiku\n  installed: " + FamiliarMain.VERSION + " (from the source tree") && status.contains("mode:      check"), status);
        assertTrue(status.contains("A newer release is out. Update the source tree with git."), status);
        assertTrue(SelfUpdate.howToUpdate(tmp, false, true).contains("run the installer again"), "Windows is told what works there");
        assertTrue(SelfUpdate.howToUpdate(tmp, true, false).contains("sudo apt install"), "a package manager's install too");
        assertTrue(SelfUpdate.howToUpdate(tmp, false, false).startsWith("`codezaiku update now` installs it"));
    }

    @Test
    void anInstallWithItsOwnRuntimeTakesTheNextVersionsPlatformBuild(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("codezaiku"); fakeRoot(root, "0.1.1");
        Files.createDirectories(root.resolve("jre").resolve("bin"));   // the mark of a build that carries its own Java
        Path release = tmp.resolve("release"); Files.createDirectories(release);
        Path stage = tmp.resolve("stage"); fakeRoot(stage.resolve("codezaiku"), "0.1.2");
        Files.createDirectories(stage.resolve("codezaiku").resolve("jre").resolve("bin"));
        String asset = "codezaiku-0.1.2-" + SelfUpdate.platformTag() + ".tar.gz";
        assertEquals(0, new ProcessBuilder("tar", "czf", release.resolve(asset).toString(), "-C", stage.toString(), "codezaiku").inheritIO().start().waitFor());
        // the plain tarball is there too, and must NOT be the one taken
        Path plainStage = tmp.resolve("plain"); fakeRoot(plainStage.resolve("codezaiku"), "0.1.2");
        assertEquals(0, new ProcessBuilder("tar", "czf", release.resolve("codezaiku-0.1.2.tar.gz").toString(), "-C", plainStage.toString(), "codezaiku").inheritIO().start().waitFor());
        Files.writeString(release.resolve("SHA256SUMS"), SelfUpdate.sha256(release.resolve(asset)) + "  " + asset + "\n" + SelfUpdate.sha256(release.resolve("codezaiku-0.1.2.tar.gz")) + "  codezaiku-0.1.2.tar.gz\n");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> asked = new ArrayList<>();
        server.createContext("/", ex -> {
            asked.add(ex.getRequestURI().getPath());
            Path f = release.resolve(ex.getRequestURI().getPath().substring(1));
            if (!Files.exists(f)) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            byte[] b = Files.readAllBytes(f); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        server.start();
        try {
            SelfUpdate.swapIn(root, "0.1.2", "http://127.0.0.1:" + server.getAddress().getPort(), new PrintStream(new ByteArrayOutputStream()));
            assertTrue(asked.contains("/" + asset), asked.toString());
            assertFalse(asked.contains("/codezaiku-0.1.2.tar.gz"), "the plain tarball was not taken: " + asked);
            assertTrue(Files.isDirectory(root.resolve("jre")), "still carries its runtime");
        } finally { server.stop(0); }
        assertTrue(SelfUpdate.platformTag().matches("(linux|macos|windows)-(x64|arm64)"), SelfUpdate.platformTag());
    }

    // ── one update at a time, and the contract a program that updates CodeZaiku reads ─────────────────────────────

    static final Supplier<Outcome> NEVER = () -> { throw new AssertionError("the update ran although another held the lock"); };
    static final Outcome DONE = new Outcome(Result.CURRENT, "0.1.1", "0.1.1", "done");
    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theExitCodesAndWordsAreResearchZoshosToo() {
        assertEquals(0, Result.UPDATED.code);
        assertEquals(0, Result.CURRENT.code);
        assertEquals(75, Result.BUSY.code);
        assertEquals(3, Result.NOT_HERE.code);
        assertEquals(1, Result.FAILED.code);
        assertEquals("not-here", Result.NOT_HERE.word());
        assertEquals(Result.BUSY, Result.of("busy"));
        assertNull(Result.of("sideways"));
    }

    @Test
    void anotherUpdateInThisProgramMakesItBusy(@TempDir Path tmp) throws Exception {
        Path lock = tmp.resolve("state").resolve("update.lock");
        Files.createDirectories(lock.getParent());
        // held on another channel in this program: tryLock throws OverlappingFileLockException, which is busy too
        try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock held = ch.lock()) {
            Outcome o = SelfUpdate.guarded(lock, NEVER);
            assertEquals(Result.BUSY, o.result());
            assertEquals("CodeZaiku is being updated by another program now; nothing to do.", o.note());
        }
        // and an update that starts while this program's own update runs
        var nested = new AtomicReference<Outcome>();
        SelfUpdate.guarded(lock, () -> { nested.set(SelfUpdate.guarded(lock, NEVER)); return DONE; });
        assertEquals(Result.BUSY, nested.get().result());
    }

    @Test
    void anotherProgramHoldingTheLockMakesItBusyUntilItEnds(@TempDir Path tmp) throws Exception {
        Path lock = tmp.resolve("update.lock");
        Path src = tmp.resolve("Hold.java");
        Files.writeString(src, """
                import java.nio.channels.FileChannel;
                import java.nio.file.Path;
                import java.nio.file.StandardOpenOption;
                public class Hold {
                    public static void main(String[] a) throws Exception {
                        try (FileChannel ch = FileChannel.open(Path.of(a[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                            ch.lock();
                            System.out.println("held");
                            System.out.flush();
                            System.in.read();
                        }
                    }
                }
                """);
        String java = Path.of(System.getProperty("java.home"), "bin", ResearchZoshoInstall.windows() ? "java.exe" : "java").toString();
        Process other = new ProcessBuilder(java, src.toString(), lock.toString()).redirectErrorStream(true).start();
        try {
            var said = new BufferedReader(new InputStreamReader(other.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("held", said.readLine(), "the other program took the lock");
            assertTrue(SelfUpdate.updating(lock), "the status says an update is running");
            assertEquals(Result.BUSY, SelfUpdate.guarded(lock, NEVER).result());
        } finally {
            other.getOutputStream().close();
            assertTrue(other.waitFor(30, TimeUnit.SECONDS));
        }
        // the system let go of the lock when that program ended, so the next update runs
        var ran = new AtomicBoolean();
        Outcome o = SelfUpdate.guarded(lock, () -> { ran.set(true); return DONE; });
        assertTrue(ran.get());
        assertEquals(Result.CURRENT, o.result());
    }

    @Test
    void theWorkRunsUnderTheLockAndLetsGoAfter(@TempDir Path tmp) {
        Path lock = tmp.resolve("state").resolve("update.lock");
        assertFalse(SelfUpdate.updating(lock));
        assertFalse(Files.exists(lock), "a check does not make the lock file");
        var during = new AtomicBoolean();
        SelfUpdate.guarded(lock, () -> { during.set(SelfUpdate.updating(lock)); return DONE; });
        assertTrue(during.get(), "while it runs, a check sees it");
        assertTrue(Files.exists(lock));
        assertFalse(SelfUpdate.updating(lock), "and after, nobody holds it");
    }

    @Test
    void theVersionIsCheckedAgainstTheFilesUnderTheLock(@TempDir Path tmp) throws Exception {
        // another program has put 99.0.0 in place since this one started on the older version
        Path root = tmp.resolve("share").resolve("codezaiku"); fakeRoot(root, "99.0.0");
        List<String> asked = new ArrayList<>();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", x -> { asked.add(x.getRequestURI().getPath()); x.sendResponseHeaders(404, -1); x.close(); });
        s.start();
        try {
            Outcome o = SelfUpdate.now(root, false, false, tmp.resolve("update.lock"), null, () -> "99.0.0",
                    "http://127.0.0.1:" + s.getAddress().getPort(), new PrintStream(new ByteArrayOutputStream()));
            assertEquals(Result.CURRENT, o.result());
            assertEquals("99.0.0", o.from());
            assertTrue(o.note().startsWith("CodeZaiku 99.0.0 is installed, the latest release; nothing to do.")
                    && o.note().contains("the next codezaiku start runs 99.0.0"), o.note());
            assertEquals(List.of(), asked, "nothing was downloaded");
        } finally { s.stop(0); }
    }

    @Test
    void anInstallThatCannotUpdateItselfEndsWithThreeAndTakesNoLock(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("codezaiku"); fakeRoot(root, "0.1.1");
        Path lock = tmp.resolve("update.lock");
        Supplier<String> latest = () -> { throw new AssertionError("nothing is asked of GitHub"); };
        var out = new PrintStream(new ByteArrayOutputStream());
        Outcome source = SelfUpdate.now(null, false, false, lock, null, latest, null, out);
        assertEquals(Result.NOT_HERE, source.result());
        assertTrue(source.note().contains("git pull"), source.note());
        Outcome apt = SelfUpdate.now(root, true, false, lock, null, latest, null, out);
        assertEquals(Result.NOT_HERE, apt.result());
        assertTrue(apt.note().contains("sudo apt install"), apt.note());
        Outcome windows = SelfUpdate.now(root, false, true, lock, null, latest, null, out);
        assertEquals(Result.NOT_HERE, windows.result());
        assertTrue(windows.note().contains("irm https://codezaiku.org/install.ps1 | iex"), windows.note());
        assertFalse(Files.exists(lock), "none of them took the lock");
        // this test runs from the classes folder, which is the source tree's case
        assertEquals(Result.NOT_HERE, SelfUpdate.now(null, out).result());
    }

    @Test
    void aNewerReleaseIsSwappedInUnderTheLockAndAFailureEndsWithOne(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("share").resolve("codezaiku"); fakeRoot(root, "0.1.1");
        Path lock = tmp.resolve("state").resolve("update.lock");
        Path release = tmp.resolve("release"); Files.createDirectories(release);
        Path stage = tmp.resolve("stage"); fakeRoot(stage.resolve("codezaiku"), "0.1.2");
        assertEquals(0, new ProcessBuilder("tar", "czf", release.resolve("codezaiku-0.1.2.tar.gz").toString(), "-C", stage.toString(), "codezaiku").inheritIO().start().waitFor());
        Files.writeString(release.resolve("SHA256SUMS"), SelfUpdate.sha256(release.resolve("codezaiku-0.1.2.tar.gz")) + "  codezaiku-0.1.2.tar.gz\n"
                + "0000000000000000000000000000000000000000000000000000000000000000  codezaiku-0.1.3.tar.gz\n");
        Files.copy(release.resolve("codezaiku-0.1.2.tar.gz"), release.resolve("codezaiku-0.1.3.tar.gz"));
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            String base = serve(release, s);
            var progress = new ByteArrayOutputStream();
            Outcome o = SelfUpdate.now(root, false, false, lock, null, () -> "0.1.2", base, new PrintStream(progress, true));
            assertEquals(Result.UPDATED, o.result());
            assertEquals("0.1.1", o.from());
            assertEquals("0.1.2", o.to());
            assertEquals("CodeZaiku was updated from 0.1.1 to 0.1.2. The next codezaiku start runs it; your settings and sessions stay.", o.note());
            assertTrue(progress.toString().contains("codezaiku: updating 0.1.1 to 0.1.2"), progress.toString());
            assertTrue(Files.exists(root.resolve("lib").resolve("core-0.1.2.jar")));
            assertFalse(SelfUpdate.updating(lock), "the lock is let go");

            Outcome bad = SelfUpdate.now(root, false, false, lock, "0.1.3", () -> "0.1.3", base, new PrintStream(new ByteArrayOutputStream()));
            assertEquals(Result.FAILED, bad.result());
            assertEquals(1, bad.result().code);
            assertTrue(bad.note().startsWith("CodeZaiku was not updated: checksum mismatch") && bad.note().endsWith("The installed 0.1.2 stays."), bad.note());
            assertTrue(Files.exists(root.resolve("lib").resolve("core-0.1.2.jar")), "the install is as it was");
        } finally { s.stop(0); }
    }

    @Test
    void theJsonHasResearchZoshosFields(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("codezaiku"); fakeRoot(root, "0.1.1");
        Path lock = tmp.resolve("update.lock");
        JsonNode st = JSON.readTree(SelfUpdate.statusJson(root, false, false, "0.1.2", lock));
        List<String> names = new ArrayList<>(); st.fieldNames().forEachRemaining(names::add);
        assertEquals(List.of("program", "installed", "running", "latest", "newer", "mode", "root", "canUpdate", "updating"), names);
        assertEquals("codezaiku", st.get("program").asText());
        assertEquals("0.1.1", st.get("installed").asText(), "the version whose files are in place");
        assertEquals(FamiliarMain.VERSION, st.get("running").asText());
        assertTrue(st.get("newer").asBoolean());
        assertEquals(root.toString(), st.get("root").asText());
        assertTrue(st.get("canUpdate").asBoolean());
        assertFalse(st.get("updating").asBoolean());
        assertFalse(JSON.readTree(SelfUpdate.statusJson(root, true, false, "0.1.2", lock)).get("canUpdate").asBoolean(), "a package manager's install");
        assertFalse(JSON.readTree(SelfUpdate.statusJson(root, false, true, "0.1.2", lock)).get("canUpdate").asBoolean(), "Windows");
        JsonNode unknown = JSON.readTree(SelfUpdate.statusJson(null, false, false, null, lock));
        assertTrue(unknown.get("latest").isNull() && !unknown.get("newer").asBoolean() && unknown.get("root").isNull() && !unknown.get("canUpdate").asBoolean(), unknown.toString());

        JsonNode oc = JSON.readTree(SelfUpdate.outcomeJson(new Outcome(Result.BUSY, "0.1.1", "0.1.1", "CodeZaiku is being updated by another program now; nothing to do.")));
        names.clear(); oc.fieldNames().forEachRemaining(names::add);
        assertEquals(List.of("result", "code", "from", "to", "finishesAfterExit", "note"), names);
        assertEquals("busy", oc.get("result").asText());
        assertEquals(75, oc.get("code").asInt());
        assertFalse(oc.get("finishesAfterExit").asBoolean(), "CodeZaiku swaps its files before it ends, or not at all");
    }

    @Test
    void autoModeUpdatesCodezaikuAloneAndOnlyWhenOn() {
        List<String> asked = new ArrayList<>();
        Function<String, Outcome> update = v -> { asked.add(v); return new Outcome(Result.UPDATED, FamiliarMain.VERSION, v, "updated"); };
        assertEquals("", SelfUpdate.maybeAuto("check", "99.0.0", update));
        assertEquals("", SelfUpdate.maybeAuto("off", "99.0.0", update));
        assertEquals("", SelfUpdate.maybeAuto("auto", FamiliarMain.VERSION, update));
        assertEquals("", SelfUpdate.maybeAuto("auto", null, update));
        assertEquals(List.of(), asked);
        String said = SelfUpdate.maybeAuto("auto", "99.0.0", update);
        assertEquals(List.of("99.0.0"), asked);
        assertTrue(said.startsWith("codezaiku: updated to 99.0.0 in place"), said);
        assertEquals("", SelfUpdate.maybeAuto("auto", "99.0.0", v -> new Outcome(Result.BUSY, "x", "x", "busy")), "another program's update is nothing to say at a chat's start");
        assertTrue(SelfUpdate.maybeAuto("auto", "99.0.0", v -> new Outcome(Result.FAILED, "x", "x", "CodeZaiku was not updated: HTTP 404.")).endsWith("CodeZaiku was not updated: HTTP 404."));
    }
}
