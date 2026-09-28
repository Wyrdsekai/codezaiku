package org.codezaiku;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
/**
 * The door installs a checked release into a prefix, and refuses one that does not match its checksums. Where
 * ResearchZosho is installed already, the door asks its own updater and downloads nothing itself.
 */
class ResearchZoshoInstallTest {

    static Path fakeRelease(Path dir, String version, boolean tamper) throws Exception {
        Path tree = dir.resolve("tree").resolve("researchzosho").resolve("bin");
        Files.createDirectories(tree);
        Files.writeString(tree.resolve("researchzosho"), "#!/bin/sh\necho researchzosho " + version + "\n");
        Files.writeString(tree.resolve("zosho"), "#!/bin/sh\necho zosho\n");
        tree.resolve("researchzosho").toFile().setExecutable(true);
        Path rel = dir.resolve("release"); Files.createDirectories(rel);
        String tar = "researchzosho-" + version + ".tar.gz";
        Process p = new ProcessBuilder("tar", "czf", rel.resolve(tar).toString(), "-C", dir.resolve("tree").toString(), "researchzosho").start();
        assertEquals(0, p.waitFor());
        String sum = ResearchZoshoInstall.sha256(rel.resolve(tar));
        if (tamper) Files.write(rel.resolve(tar), new byte[]{1, 2, 3}, StandardOpenOption.APPEND);
        Files.writeString(rel.resolve("SHA256SUMS"), sum + "  " + tar + "\n");
        return rel;
    }

    @Test
    void aCheckedReleaseIsInstalledIntoThePrefixWithWrappersOnTheBin(@TempDir Path tmp) throws Exception {
        Assumptions.assumeFalse(ResearchZoshoInstall.windows());
        Path rel = fakeRelease(tmp, "9.9.9", false);
        Path prefix = tmp.resolve("prefix");
        var out = new ByteArrayOutputStream();
        Path launcher = ResearchZoshoInstall.install("9.9.9", prefix, rel.toUri().toString().replaceAll("/$", ""), false, new PrintStream(out));
        assertEquals(prefix.resolve("bin").resolve("researchzosho"), launcher);
        assertTrue(Files.isExecutable(launcher));
        assertTrue(Files.isRegularFile(prefix.resolve("share").resolve("researchzosho").resolve("bin").resolve("researchzosho")));
        assertTrue(out.toString().contains("checksum verified") && out.toString().contains("installed researchzosho 9.9.9"), out.toString());
        Process p = new ProcessBuilder(launcher.toString()).redirectErrorStream(true).start();
        assertEquals("researchzosho 9.9.9", new String(p.getInputStream().readAllBytes()).strip());
        assertTrue(Files.isRegularFile(prefix.resolve("bin").resolve("zosho")), "the short form too");
    }

    @Test
    void aTamperedReleaseIsRefused(@TempDir Path tmp) throws Exception {
        Assumptions.assumeFalse(ResearchZoshoInstall.windows());
        Path rel = fakeRelease(tmp, "9.9.9", true);
        Path prefix = tmp.resolve("prefix");
        IOException e = assertThrows(IOException.class, () -> ResearchZoshoInstall.install("9.9.9", prefix, rel.toUri().toString().replaceAll("/$", ""), false, new PrintStream(new ByteArrayOutputStream())));
        assertTrue(e.getMessage().startsWith("checksum mismatch"), e.getMessage());
        assertFalse(Files.exists(prefix.resolve("bin").resolve("researchzosho")), "nothing was installed");
    }

    @Test
    void aReleaseWithoutChecksumsIsRefused(@TempDir Path tmp) throws Exception {
        Path rel = fakeRelease(tmp, "9.9.9", false);
        Files.delete(rel.resolve("SHA256SUMS"));
        IOException e = assertThrows(IOException.class, () -> ResearchZoshoInstall.install("9.9.9", tmp.resolve("prefix"), rel.toUri().toString().replaceAll("/$", ""), false, new PrintStream(new ByteArrayOutputStream())));
        assertTrue(e.getMessage().contains("no SHA256SUMS"), e.getMessage());
    }

    @Test
    void versionsCompareNumericallyForTheUpdateCheck() {
        assertTrue(ResearchZoshoInstall.compareVersions("0.1.2", "0.1.1") > 0);
        assertTrue(ResearchZoshoInstall.compareVersions("0.10.0", "0.9.9") > 0);
        assertEquals(0, ResearchZoshoInstall.compareVersions("1.0.0", "1.0"));
        assertTrue(ResearchZoshoInstall.compareVersions("0.1.1", "0.1.2") < 0);
    }

    @Test
    void anInstalledResearchZoshoIsUpdatedByItsOwnUpdaterNotByADownloadHere(@TempDir Path tmp) throws Exception {
        Assumptions.assumeFalse(ResearchZoshoInstall.windows());
        var rz = new FakeResearchZosho(tmp.resolve("rz"), "0.5.0").now(ResearchZoshoUpdateTest.answer("updated", 0, "0.5.0", "0.5.1", false,
                "updated to 0.5.1; the service restarted"), "researchzosho: checksum verified\n", 0);
        var out = new ByteArrayOutputStream();
        assertEquals(0, ResearchZoshoInstall.door(new String[]{"--no-setup"}, new PrintStream(out, true), rz.launcher));
        String said = out.toString();
        assertEquals(List.of("--version", "update now --json"), rz.calls());
        assertTrue(said.contains("Asking its own updater for the latest release") && said.contains("codezaiku: ResearchZosho was updated from 0.5.0 to 0.5.1."), said);
        assertFalse(said.contains("downloading"), "CodeZaiku downloaded nothing: " + said);
        assertFalse(said.contains("service uninstall"), "its updater restarts its own service: " + said);
    }

    @Test
    void theDoorPassesAVersionAndSaysWhenTheUpdateFailed(@TempDir Path tmp) throws Exception {
        Assumptions.assumeFalse(ResearchZoshoInstall.windows());
        var rz = new FakeResearchZosho(tmp.resolve("rz"), "0.5.0").now(ResearchZoshoUpdateTest.answer("failed", 1, "0.5.0", "0.5.2", false,
                "not updated: HTTP 404 for researchzosho-0.5.2.tar.gz"), "", 1);
        var out = new ByteArrayOutputStream();
        assertEquals(1, ResearchZoshoInstall.door(new String[]{"--version", "0.5.2", "--no-setup"}, new PrintStream(out, true), rz.launcher));
        assertEquals("update now 0.5.2 --json", rz.calls().get(1));
        assertTrue(out.toString().contains("ResearchZosho was not updated. Its updater says: not updated: HTTP 404"), out.toString());
    }

    @Test
    void setupWaitsWhileAWindowsSwapFinishesAfterTheUpdaterEnds(@TempDir Path tmp) throws Exception {
        Assumptions.assumeFalse(ResearchZoshoInstall.windows());
        var rz = new FakeResearchZosho(tmp.resolve("rz"), "0.5.0").now(ResearchZoshoUpdateTest.answer("updated", 0, "0.5.0", "0.5.1", true,
                "0.5.1 is downloaded and checked. The update finishes when this command ends."), "", 0);
        var out = new ByteArrayOutputStream();
        assertEquals(0, ResearchZoshoInstall.door(new String[0], new PrintStream(out, true), rz.launcher));
        assertFalse(rz.calls().contains("setup"), "setup is not started over files being swapped: " + rz.calls());
        assertTrue(out.toString().contains("open a new terminal and run researchzosho setup"), out.toString());
    }

    /** A release with the plain tarball and this platform's build that carries its own Java ({@code jre}: whether it really does). */
    static Path fakeRuntimeRelease(Path dir, String version, boolean jre) throws Exception {
        Path rel = fakeRelease(dir, version, false);
        Path tree = dir.resolve("rtree").resolve("researchzosho");
        Files.createDirectories(tree.resolve("bin"));
        Files.writeString(tree.resolve("bin").resolve("researchzosho"), "#!/bin/sh\necho researchzosho " + version + " with its own java\n");
        if (jre) Files.createDirectories(tree.resolve("jre").resolve("bin"));
        String tar = "researchzosho-" + version + "-" + SelfUpdate.platformTag() + ".tar.gz";
        assertEquals(0, new ProcessBuilder("tar", "czf", rel.resolve(tar).toString(), "-C", dir.resolve("rtree").toString(), "researchzosho").start().waitFor());
        Files.writeString(rel.resolve("SHA256SUMS"), ResearchZoshoInstall.sha256(rel.resolve(tar)) + "  " + tar + "\n", StandardOpenOption.APPEND);
        return rel;
    }

    @Test
    void aFreshInstallBesideACodezaikuWithItsOwnJavaTakesTheBuildWithItsOwnJava(@TempDir Path tmp) throws Exception {
        Assumptions.assumeFalse(ResearchZoshoInstall.windows());
        Path rel = fakeRuntimeRelease(tmp, "9.9.9", true);
        Path prefix = tmp.resolve("prefix");
        var out = new ByteArrayOutputStream();
        ResearchZoshoInstall.install("9.9.9", prefix, rel.toUri().toString().replaceAll("/$", ""), true, new PrintStream(out, true));
        assertTrue(Files.isDirectory(prefix.resolve("share").resolve("researchzosho").resolve("jre")), "the build that carries its own Java");
        assertTrue(out.toString().contains("researchzosho-9.9.9-" + SelfUpdate.platformTag() + ".tar.gz"), out.toString());

        Path plain = tmp.resolve("plain");
        ResearchZoshoInstall.install("9.9.9", plain, rel.toUri().toString().replaceAll("/$", ""), false, new PrintStream(new ByteArrayOutputStream()));
        assertFalse(Files.exists(plain.resolve("share").resolve("researchzosho").resolve("jre")), "with Java on the machine, the small tarball");
    }

    @Test
    void aRuntimeBuildWithoutItsJavaIsRefused(@TempDir Path tmp) throws Exception {
        Assumptions.assumeFalse(ResearchZoshoInstall.windows());
        Path rel = fakeRuntimeRelease(tmp, "9.9.9", false);
        Path prefix = tmp.resolve("prefix");
        IOException e = assertThrows(IOException.class, () -> ResearchZoshoInstall.install("9.9.9", prefix, rel.toUri().toString().replaceAll("/$", ""), true, new PrintStream(new ByteArrayOutputStream())));
        assertTrue(e.getMessage().contains("carries no Java runtime"), e.getMessage());
        assertFalse(Files.exists(prefix.resolve("bin").resolve("researchzosho")), "nothing was installed");
    }
}
