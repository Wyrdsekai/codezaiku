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
    void theModeIsCheckUnlessSetAndTheStatusSaysSo() {
        assertEquals("check", SelfUpdate.mode());
        assertTrue(SelfUpdate.status().contains("installed:") && SelfUpdate.status().contains("mode:      check"), SelfUpdate.status());
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
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.List<String> asked = new java.util.ArrayList<>();
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
}
