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
}
