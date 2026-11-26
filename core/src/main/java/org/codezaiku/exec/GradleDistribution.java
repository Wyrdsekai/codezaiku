package org.codezaiku.exec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The Gradle distribution a project's wrapper names, fetched by CodeZaiku itself before the job's first command, outside the sandbox,
 * and put where the wrapper inside the sandbox finds it. With the network closed the wrapper cannot fetch it: services.gradle.org
 * answers with a redirect to github.com (seen 2026-11-24: {@code 307 -> https://github.com/gradle/gradle-distributions/releases/...}),
 * and GitHub is not let in. So a project with {@code gradlew} still builds on a hosted model and nobody opens GitHub to commands.
 *
 * <p>Only an address under {@code https://services.gradle.org/distributions/} is fetched, and whatever it redirects to; any other is
 * left to the person. The checksum is the one the wrapper's settings name ({@code distributionSha256Sum}), else the {@code .sha256}
 * file Gradle publishes beside the distribution. The place and its layout are the wrapper's own (its PathAssembler and Install,
 * read in the wrapper of Gradle 9.7.0): {@code <GRADLE_USER_HOME>/wrapper/dists/<name>/<the URL's MD5 in base 36>/}, holding the
 * unpacked distribution as its only folder and a marker {@code <name>.zip.ok}; with both there the wrapper fetches nothing.
 */
public final class GradleDistribution {

    private GradleDistribution() { }

    static final String SERVICES = "https://services.gradle.org/distributions/";
    private static final Pattern NAME = Pattern.compile("gradle-[0-9A-Za-z.+-]+-(bin|all)\\.zip");
    private static final Pattern SHA256 = Pattern.compile("(?i)^[0-9a-f]{64}$");

    /** Fetches a URL into a stream: for tests, a stand-in for the network. */
    interface Fetcher { InputStream open(URI uri) throws IOException; }

    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20)).build();

    private static final Fetcher WEB = uri -> {
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(15)).header("User-Agent", "CodeZaiku").GET().build();
        try {
            HttpResponse<InputStream> r = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) { r.body().close(); throw new IOException(uri.getHost() + " answered " + r.statusCode()); }
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    };

    /** Where the wrapper keeps a distribution, as its PathAssembler works it out: the folder under the dists path. */
    static Path folder(Path gradleUserHome, String distributionPath, URI url) {
        String file = url.getPath().substring(url.getPath().lastIndexOf('/') + 1);
        String base = file.endsWith(".zip") ? file.substring(0, file.length() - 4) : file;
        return gradleUserHome.resolve(distributionPath).resolve(base).resolve(hash(url.toASCIIString()));
    }

    /** The wrapper's hash of the distribution's address: MD5 of its ASCII form, as a positive number in base 36. */
    static String hash(String address) {
        try {
            return new BigInteger(1, MessageDigest.getInstance("MD5").digest(address.getBytes(StandardCharsets.UTF_8))).toString(36);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Puts the distribution of {@code project}'s wrapper in place under {@code gradleUserHome}, once per project and version. Returns
     * one line for the job to print, or null when there is nothing to say (no wrapper, or already in place). Never throws: a failure
     * is the line, and the wrapper is then refused as before.
     */
    public static String prepare(Path project, Path gradleUserHome) { return prepare(project, gradleUserHome, WEB); }

    static String prepare(Path project, Path gradleUserHome, Fetcher fetcher) { return prepareFrom(settingsOf(project), gradleUserHome, fetcher); }

    /** The wrapper's settings as they are now: read when the job begins, so a file the job writes later is not what is fetched. Null when none. */
    public static String settingsOf(Path project) {
        Path settings = project.resolve("gradle/wrapper/gradle-wrapper.properties");
        try { return Files.isRegularFile(settings) && Files.size(settings) <= 65536 ? Files.readString(settings, StandardCharsets.ISO_8859_1) : null; }
        catch (IOException | RuntimeException e) { return null; }
    }

    /** As {@link #prepare(Path, Path)}, from the wrapper's settings read when the job began. */
    public static String prepareFrom(String settingsText, Path gradleUserHome) { return prepareFrom(settingsText, gradleUserHome, WEB); }

    static String prepareFrom(String settingsText, Path gradleUserHome, Fetcher fetcher) {
        if (settingsText == null) return null;
        Properties p = new Properties();
        try { p.load(new StringReader(settingsText)); } catch (IOException | IllegalArgumentException e) {
            return "gradle: the wrapper's settings could not be read (" + e.getMessage() + "); the wrapper will fetch nothing on a closed network.";
        }
        String address = p.getProperty("distributionUrl", "").strip();
        if (address.isEmpty()) return null;
        URI url;
        try { url = URI.create(address); } catch (IllegalArgumentException e) { return "gradle: the wrapper's distributionUrl is not an address; nothing was fetched."; }
        String file = url.getPath() == null ? "" : url.getPath().substring(url.getPath().lastIndexOf('/') + 1);
        if (!address.startsWith(SERVICES) || url.getRawQuery() != null || url.getRawUserInfo() != null || !NAME.matcher(file).matches()) {
            return "gradle: the wrapper names a distribution at " + (url.getHost() == null ? address : url.getHost()) + ", not on services.gradle.org, "
                    + "so CodeZaiku does not fetch it; on a closed network the wrapper is refused unless that host is let in (sandbox.hosts).";
        }
        for (String[] base : new String[][]{{"distributionBase", "distributionPath"}, {"zipStoreBase", "zipStorePath"}}) {
            if (!p.getProperty(base[0], "GRADLE_USER_HOME").equals("GRADLE_USER_HOME") || !p.getProperty(base[1], "wrapper/dists").equals("wrapper/dists"))
                return "gradle: the wrapper keeps its distribution somewhere of its own (" + base[0] + ", " + base[1] + "), so CodeZaiku does not fetch it.";
        }
        Path dir = folder(gradleUserHome, "wrapper/dists", url);
        Path marker = dir.resolve(file + ".ok");
        if (Files.isRegularFile(marker) && oneFolder(dir) != null) return null;          // in place: the wrapper fetches nothing
        String version = file.substring("gradle-".length(), file.length() - ".zip".length()).replaceFirst("-(bin|all)$", "");
        try {
            Files.createDirectories(dir);
            String expected = p.getProperty("distributionSha256Sum", "").strip();
            String from = "the checksum in the wrapper's settings";
            if (expected.isEmpty()) {
                try (InputStream in = fetcher.open(URI.create(address + ".sha256"))) {
                    expected = new String(in.readNBytes(256), StandardCharsets.US_ASCII).strip().split("\\s+")[0];
                }
                from = "the checksum Gradle publishes beside it";
            }
            if (!SHA256.matcher(expected).matches()) return "gradle: no usable checksum for Gradle " + version + "; nothing was fetched.";
            Path part = Files.createTempFile(dir, file, ".part");
            MessageDigest sha;
            try {
                sha = MessageDigest.getInstance("SHA-256");
                try (InputStream raw = fetcher.open(url); InputStream in = new DigestInputStream(raw, sha); OutputStream out = Files.newOutputStream(part)) {
                    in.transferTo(out);
                }
                String got = HexFormat.of().formatHex(sha.digest());
                if (!got.equalsIgnoreCase(expected)) {
                    return "gradle: the Gradle " + version + " distribution did not match " + from + " (" + expected.substring(0, 12) + "… against "
                            + got.substring(0, 12) + "…); it was not used.";
                }
                Path unpacked = Files.createTempDirectory(dir, "unpack");
                unzip(part, unpacked);
                Path top = oneFolder(unpacked);
                if (top == null || !Files.isRegularFile(top.resolve("bin/gradle"))) {
                    deleteTree(unpacked);
                    return "gradle: the Gradle " + version + " distribution is not laid out as Gradle's are; it was not used.";
                }
                try (Stream<Path> old = Files.list(dir)) { for (Path o : old.toList()) if (Files.isDirectory(o) && !o.equals(unpacked)) deleteTree(o); }
                Path placed = dir.resolve(top.getFileName().toString());
                Files.move(top, placed, StandardCopyOption.ATOMIC_MOVE);
                deleteTree(unpacked);
                executable(placed.resolve("bin/gradle"));
                Files.writeString(marker, "");
            } finally {
                Files.deleteIfExists(part);
            }
            return "gradle: fetched the Gradle " + version + " distribution the project's wrapper names, checked against " + from
                    + ", for the sandbox, whose closed network does not reach GitHub, where services.gradle.org sends it.";
        } catch (IOException | RuntimeException | NoSuchAlgorithmException e) {
            return "gradle: the Gradle " + version + " distribution could not be fetched (" + e.getMessage() + "); on a closed network the wrapper will be refused.";
        }
    }

    /** The one folder in {@code dir}, or null when there is none or more than one: the wrapper's own test of a distribution. */
    static Path oneFolder(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            var folders = s.filter(Files::isDirectory).filter(f -> !f.getFileName().toString().startsWith("unpack")).toList();
            return folders.size() == 1 ? folders.get(0) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Unpacks a zip into {@code into}, refusing an entry whose path would leave it. */
    static void unzip(Path zip, Path into) throws IOException {
        Path root = into.toAbsolutePath().normalize();
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                Path to = root.resolve(e.getName()).normalize();
                if (!to.startsWith(root) || to.equals(root)) throw new IOException("an entry leaves the folder: " + e.getName());
                if (e.isDirectory()) { Files.createDirectories(to); continue; }
                Files.createDirectories(to.getParent());
                Files.copy(z, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void executable(Path f) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(f);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(f, perms);
        } catch (IOException | UnsupportedOperationException e) {
            // the wrapper sets it itself where it can
        }
    }

    private static void deleteTree(Path p) {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path q : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) Files.deleteIfExists(q);
        } catch (IOException e) {
            // left for the next try
        }
    }
}
