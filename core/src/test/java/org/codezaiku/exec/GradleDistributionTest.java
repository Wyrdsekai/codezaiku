package org.codezaiku.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Gradle distribution a project's wrapper names, fetched by CodeZaiku before the job's first command and put where the wrapper
 * finds it, because a closed network cannot reach GitHub, where services.gradle.org sends the download. The network here is a
 * stand-in that serves a small zip made by the test; nothing is fetched from anywhere.
 */
class GradleDistributionTest {

    static final String URL = "https://services.gradle.org/distributions/gradle-9.7.0-bin.zip";

    /** A distribution as Gradle's are laid out: one folder, its bin/gradle and a launcher jar. */
    static byte[] distribution(String top, Map<String, String> more) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            z.putNextEntry(new ZipEntry(top + "/bin/gradle")); z.write("#!/bin/sh\necho gradle\n".getBytes(StandardCharsets.UTF_8)); z.closeEntry();
            z.putNextEntry(new ZipEntry(top + "/lib/gradle-gradle-cli-main-9.7.0.jar")); z.write(new byte[]{1, 2, 3}); z.closeEntry();
            for (Map.Entry<String, String> e : more.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue().getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
        }
        return b.toByteArray();
    }

    static String sha256(byte[] b) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }

    /** The stand-in network: what each address answers, and every address asked. */
    record Net(Map<String, byte[]> answers, List<String> asked) implements GradleDistribution.Fetcher {
        Net(Map<String, byte[]> answers) { this(answers, new ArrayList<>()); }
        @Override public InputStream open(URI uri) throws IOException {
            asked.add(uri.toString());
            byte[] b = answers.get(uri.toString());
            if (b == null) throw new IOException(uri.getHost() + " answered 404");
            return new ByteArrayInputStream(b);
        }
    }

    static Path wrapper(Path project, String url, String sha) throws IOException {
        Path f = project.resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\ndistributionUrl=" + url.replace(":", "\\:") + "\n"
                + (sha == null ? "" : "distributionSha256Sum=" + sha + "\n") + "zipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n");
        return f;
    }

    @Test
    void theFolderIsTheOneTheWrapperLooksIn() {
        // seen on 2026-11-24: Gradle 9.7.0's own wrapper ran a build from .../wrapper/dists/gradle-9.7.0-bin/d4tj7w02tcgubx9zk9hbippn6/gradle-9.7.0
        assertEquals("d4tj7w02tcgubx9zk9hbippn6", GradleDistribution.hash(URL));
        assertEquals(Path.of("/g/wrapper/dists/gradle-9.7.0-bin/d4tj7w02tcgubx9zk9hbippn6"), GradleDistribution.folder(Path.of("/g"), "wrapper/dists", URI.create(URL)));
    }

    @Test
    void aWrappersDistributionIsFetchedCheckedAgainstGradlesPublishedChecksumAndLaidOutOnce(@TempDir Path project, @TempDir Path home) throws Exception {
        byte[] zip = distribution("gradle-9.7.0", Map.of());
        wrapper(project, URL, null);
        Net net = new Net(Map.of(URL, zip, URL + ".sha256", (sha256(zip) + "\n").getBytes(StandardCharsets.US_ASCII)));
        String said = GradleDistribution.prepare(project, home, net);
        assertTrue(said != null && said.contains("Gradle 9.7.0 ") && said.contains("the checksum Gradle publishes beside it"), said);
        Path dir = home.resolve("wrapper/dists/gradle-9.7.0-bin/d4tj7w02tcgubx9zk9hbippn6");
        assertTrue(Files.isRegularFile(dir.resolve("gradle-9.7.0-bin.zip.ok")), "the wrapper's marker");
        assertTrue(Files.isExecutable(dir.resolve("gradle-9.7.0/bin/gradle")), "the distribution, its launcher executable");
        assertEquals(dir.resolve("gradle-9.7.0"), GradleDistribution.oneFolder(dir), "and nothing else beside it that the wrapper would count");
        assertEquals(List.of(URL + ".sha256", URL), net.asked());
        assertNull(GradleDistribution.prepare(project, home, net), "once per project and version: in place, nothing said");
        assertEquals(2, net.asked().size(), "and nothing asked again");
    }

    @Test
    void theChecksumInTheWrappersSettingsIsTheOneUsedAndAMismatchIsNotUsed(@TempDir Path project, @TempDir Path home) throws Exception {
        byte[] zip = distribution("gradle-9.7.0", Map.of());
        wrapper(project, URL, "0".repeat(64));
        Net net = new Net(Map.of(URL, zip));
        String said = GradleDistribution.prepare(project, home, net);
        assertTrue(said.contains("did not match the checksum in the wrapper's settings") && said.contains("not used"), said);
        Path dir = home.resolve("wrapper/dists/gradle-9.7.0-bin/d4tj7w02tcgubx9zk9hbippn6");
        assertFalse(Files.exists(dir.resolve("gradle-9.7.0-bin.zip.ok")), "no marker: the wrapper will not take it");
        assertNull(GradleDistribution.oneFolder(dir), "and nothing unpacked");
        wrapper(project, URL, sha256(zip));
        assertTrue(GradleDistribution.prepare(project, home, net).contains("checked against the checksum in the wrapper's settings"));
        assertEquals(List.of(URL, URL), net.asked(), "the settings' checksum: Gradle's published one is not asked");
    }

    @Test
    void anAddressNotOnServicesGradleOrgIsLeftToThePersonAndAFailureIsOneLine(@TempDir Path project, @TempDir Path home) throws Exception {
        Net net = new Net(Map.of());
        assertNull(GradleDistribution.prepare(project, home, net), "no wrapper: nothing to say");
        wrapper(project, "https://downloads.example.com/gradle-9.7.0-bin.zip", null);
        String said = GradleDistribution.prepare(project, home, net);
        assertTrue(said.contains("downloads.example.com") && said.contains("does not fetch it") && said.contains("sandbox.hosts"), said);
        wrapper(project, "http://services.gradle.org/distributions/gradle-9.7.0-bin.zip", null);
        assertTrue(GradleDistribution.prepare(project, home, net).contains("does not fetch it"), "plain http is not the address either");
        assertTrue(net.asked().isEmpty(), "nothing asked");
        wrapper(project, URL, null);
        said = GradleDistribution.prepare(project, home, net);
        assertTrue(said.startsWith("gradle: the Gradle 9.7.0 distribution could not be fetched") && said.contains("404"), said);
    }

    @Test
    void anEntryThatWouldLeaveTheFolderIsRefused(@TempDir Path project, @TempDir Path home) throws Exception {
        byte[] zip = distribution("gradle-9.7.0", Map.of("../../outside.txt", "planted"));
        wrapper(project, URL, sha256(zip));
        String said = GradleDistribution.prepare(project, home, new Net(Map.of(URL, zip)));
        assertTrue(said.contains("could not be fetched") && said.contains("leaves the folder"), said);
        assertFalse(Files.exists(home.resolve("wrapper/dists/outside.txt")) || Files.exists(home.resolve("wrapper/outside.txt")), "nothing written outside");
    }
}
