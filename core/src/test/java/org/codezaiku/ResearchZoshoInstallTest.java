package org.codezaiku;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The door installs a checked release into a prefix, and refuses one that does not match its checksums. */
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
        if (tamper) Files.write(rel.resolve(tar), new byte[]{1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);
        Files.writeString(rel.resolve("SHA256SUMS"), sum + "  " + tar + "\n");
        return rel;
    }

    @Test
    void aCheckedReleaseIsInstalledIntoThePrefixWithWrappersOnTheBin(@TempDir Path tmp) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(ResearchZoshoInstall.windows());
        Path rel = fakeRelease(tmp, "9.9.9", false);
        Path prefix = tmp.resolve("prefix");
        var out = new ByteArrayOutputStream();
        Path launcher = ResearchZoshoInstall.install("9.9.9", prefix, rel.toUri().toString().replaceAll("/$", ""), new PrintStream(out));
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
        org.junit.jupiter.api.Assumptions.assumeFalse(ResearchZoshoInstall.windows());
        Path rel = fakeRelease(tmp, "9.9.9", true);
        Path prefix = tmp.resolve("prefix");
        IOException e = assertThrows(IOException.class, () -> ResearchZoshoInstall.install("9.9.9", prefix, rel.toUri().toString().replaceAll("/$", ""), new PrintStream(new ByteArrayOutputStream())));
        assertTrue(e.getMessage().startsWith("checksum mismatch"), e.getMessage());
        assertFalse(Files.exists(prefix.resolve("bin").resolve("researchzosho")), "nothing was installed");
    }

    @Test
    void aReleaseWithoutChecksumsIsRefused(@TempDir Path tmp) throws Exception {
        Path rel = fakeRelease(tmp, "9.9.9", false);
        Files.delete(rel.resolve("SHA256SUMS"));
        IOException e = assertThrows(IOException.class, () -> ResearchZoshoInstall.install("9.9.9", tmp.resolve("prefix"), rel.toUri().toString().replaceAll("/$", ""), new PrintStream(new ByteArrayOutputStream())));
        assertTrue(e.getMessage().contains("no SHA256SUMS"), e.getMessage());
    }

    @Test
    void versionsCompareNumericallyForTheUpdateCheck() {
        assertTrue(ResearchZoshoInstall.compareVersions("0.1.2", "0.1.1") > 0);
        assertTrue(ResearchZoshoInstall.compareVersions("0.10.0", "0.9.9") > 0);
        assertEquals(0, ResearchZoshoInstall.compareVersions("1.0.0", "1.0"));
        assertTrue(ResearchZoshoInstall.compareVersions("0.1.1", "0.1.2") < 0);
    }
}
