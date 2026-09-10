package org.codezaiku;


import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Updating the installed program to the latest release: the same download the installers make, checked against the
 * release's own SHA256SUMS, unpacked beside the install and swapped in with two renames. Settings and sessions
 * live under ~/.codezaiku and are not touched.
 *
 * <p>{@code CODEZAIKU_UPDATE}: {@code check} (default) says when a newer release exists; {@code auto} lets the
 * daemon update itself at a quiet moment after the housekeeping, when no run is active, and restart; {@code off}
 * does neither. {@code codezaiku update now} does it by hand. A run from the source tree never updates.
 */
public final class SelfUpdate {

    private SelfUpdate() { }

    public static final String REPO = "Wyrdsekai/codezaiku";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    /** check | auto | off */
    public static String mode() {
        String m = Config.get("CODEZAIKU_UPDATE", "check").toLowerCase(Locale.ROOT).strip();
        return m.equals("auto") || m.equals("off") ? m : "check";
    }

    /** The install root: the parent of the lib/ directory holding this jar; null when running from the source tree. */
    public static Path root() {
        try {
            Path self = Path.of(SelfUpdate.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!self.toString().endsWith(".jar")) return null;
            Path lib = self.getParent();
            return lib == null || !lib.getFileName().toString().equals("lib") ? null : lib.getParent();
        } catch (Exception e) { return null; }
    }

    public record Outcome(boolean updated, String from, String to, String note) { }

    /** What `researchzosho update` says: the installed version, the latest, the mode, and what would happen. */
    public static String status() {
        String latest = latestVersion();
        StringBuilder b = new StringBuilder();
        b.append("installed: ").append(FamiliarMain.VERSION).append(root() == null ? " (from the source tree; updates are git pull)" : " at " + root()).append('\n');
        b.append("latest:    ").append(latest == null ? "unknown (could not reach GitHub)" : latest).append('\n');
        b.append("mode:      ").append(mode()).append(" (CODEZAIKU_UPDATE = check | auto | off; codezaiku update auto on|off)").append('\n');
        if (latest != null && ResearchZoshoInstall.compareVersions(latest, FamiliarMain.VERSION) > 0)
            b.append(mode().equals("auto") ? "the daemon updates itself after the next housekeeping, when idle; or now: codezaiku update now"
                                           : "a newer release: codezaiku update now  (the library and settings stay)").append('\n');
        return b.toString();
    }

    /** Update now, to the latest release (or {@code version}); restart the service when one is installed. */
    public static Outcome now(String version, java.io.PrintStream out) {
        String have = FamiliarMain.VERSION;
        Path root = root();
        if (root == null) return new Outcome(false, have, have, "cannot find the install root (no lib/ beside this jar)");
        if (packaged()) return new Outcome(false, have, have, howToUpdate());
        String target = version != null ? version : latestVersion();
        if (target == null) return new Outcome(false, have, have, "could not reach GitHub for the latest release");
        if (ResearchZoshoInstall.compareVersions(target, have) <= 0) return new Outcome(false, have, target, "already " + have);
        try {
            out.println("codezaiku: updating " + have + " → " + target);
            String base = System.getenv("CODEZAIKU_DOWNLOAD_BASE");
            swapIn(root, target, base == null || base.isBlank() ? "https://github.com/" + REPO + "/releases/download/v" + target : base, out);
            return new Outcome(true, have, target, "updated to " + target + "; the next codezaiku start runs it");
        } catch (Exception e) {
            return new Outcome(false, have, target, "not updated: " + e.getMessage());
        }
    }

    /** The latest release's version from GitHub, or null. */
    static String latestVersion() {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + REPO + "/releases/latest")).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "CodeZaiku/" + FamiliarMain.VERSION).header("Accept", "application/vnd.github+json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            var m = java.util.regex.Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([0-9][^\"]*)\"").matcher(r.body());
            return m.find() ? m.group(1) : null;
        } catch (Exception e) { return null; }
    }

    static Path latestCache() { return Config.home().resolve("codezaiku-latest.txt"); }
    private static volatile boolean refreshing;

    /** The latest release's version, from a cache refreshed in the background at most once a day; null until known. Never blocks. */
    public static String latestCached() {
        try {
            Path c = latestCache();
            long age = Files.exists(c) ? System.currentTimeMillis() - Files.getLastModifiedTime(c).toMillis() : Long.MAX_VALUE;
            String cached = Files.exists(c) ? Files.readString(c, StandardCharsets.UTF_8).strip() : null;
            if (age > 24L * 3600 * 1000 && !refreshing) {
                refreshing = true;
                Thread t = new Thread(() -> { try { String v = latestVersion(); if (v != null) { Files.createDirectories(c.getParent()); Files.writeString(c, v, StandardCharsets.UTF_8); } } catch (Exception ignored) { } finally { refreshing = false; } }, "codezaiku-version-check");
                t.setDaemon(true); t.start();
            }
            return cached == null || cached.isEmpty() ? null : cached;
        } catch (Exception e) { return null; }
    }

    /** One line when a newer CodeZaiku than this one is released, from the cache; "" otherwise or when unknown. */
    public static String updateNotice() {
        String latest = latestCached();
        if (latest == null || ResearchZoshoInstall.compareVersions(latest, FamiliarMain.VERSION) <= 0) return "";
        return "CodeZaiku " + latest + " is available (this is " + FamiliarMain.VERSION + "). " + howToUpdate();
    }

    /** Whether a package manager owns this install: the root is not ours to write (the Debian package puts it under /opt or /usr). */
    public static boolean packaged() {
        Path r = root();
        return r != null && (!Files.isWritable(r) || !Files.isWritable(r.getParent()));
    }

    /** How this install updates: in place when we own the files, apt when a package manager does. */
    public static String howToUpdate() {
        Path r = root();
        if (r == null) return "Update the source tree with git.";
        if (packaged()) return "Installed by a package manager: sudo apt install the new .deb from https://codezaiku.org/download/";
        return "`codezaiku update now` installs it; your settings and sessions stay.";
    }

    /** The auto mode's turn, at the start of a chat: a newer release is swapped in for the NEXT start. Returns what it did, or "". */
    public static String maybeAuto() {
        if (!mode().equals("auto")) return "";
        Path r = root();
        if (r == null || packaged()) return "";
        String latest = latestCached();
        if (latest == null || ResearchZoshoInstall.compareVersions(latest, FamiliarMain.VERSION) <= 0) return "";
        var buf = new java.io.ByteArrayOutputStream();
        Outcome o = now(latest, new java.io.PrintStream(buf, true));
        return o.updated() ? "codezaiku: updated to " + o.to() + " in place; the next start runs it (this one keeps " + FamiliarMain.VERSION + ")" : "codezaiku: " + o.note();
    }

    /**
     * Download {@code researchzosho-<version>.tar.gz} and SHA256SUMS from {@code base}, verify, unpack beside the root,
     * and swap: root → root.old, new → root, then remove old. Throws with a plain reason on any failure, leaving the
     * install as it was.
     */
    static void swapIn(Path root, String version, String base, java.io.PrintStream out) throws Exception {
        String tar = "codezaiku-" + version + ".tar.gz";
        Path work = Files.createTempDirectory(root.toAbsolutePath().getParent(), ".codezaiku-update-");
        try {
            Path tarPath = work.resolve(tar);
            fetch(base + "/" + tar, tarPath);
            Path sums = work.resolve("SHA256SUMS");
            try { fetch(base + "/SHA256SUMS", sums); } catch (IOException e) { throw new IOException("the release has no SHA256SUMS; refusing an unchecked download"); }
            String expect = null;
            for (String line : Files.readAllLines(sums, StandardCharsets.UTF_8)) {
                String[] p = line.strip().split("\\s+");
                if (p.length >= 2 && (p[1].equals(tar) || p[1].equals("./" + tar) || p[1].equals("*" + tar))) expect = p[0].toLowerCase(Locale.ROOT);
            }
            if (expect == null) throw new IOException(tar + " is not listed in SHA256SUMS");
            String actual = sha256(tarPath);
            if (!expect.equals(actual)) throw new IOException("checksum mismatch for " + tar + "; refusing to install\n  expected " + expect + "\n  got      " + actual);
            out.println("codezaiku: checksum verified");
            Path unpack = work.resolve("x");
            Files.createDirectories(unpack);
            Process p = new ProcessBuilder("tar", "xzf", tarPath.toString(), "-C", unpack.toString()).redirectErrorStream(true).start();
            String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) throw new IOException("could not unpack " + tar + ": " + o.strip());
            Path fresh = unpack.resolve("codezaiku");
            if (!Files.isDirectory(fresh.resolve("lib"))) throw new IOException(tar + " does not contain codezaiku/lib");
            Path old = root.resolveSibling(root.getFileName() + ".old");
            deleteTree(old);
            try {
                Files.move(root, old, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                throw new IOException("could not move the running install aside (" + e.getMessage() + "); on Windows stop the service first, then run codezaiku update now from a new terminal");
            }
            try {
                Files.move(fresh, root, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(old, root, StandardCopyOption.ATOMIC_MOVE);   // put it back
                throw new IOException("could not put the new install in place (" + e.getMessage() + "); the old one is back");
            }
            deleteTree(old);
            out.println("codezaiku: " + version + " is in place at " + root);
        } finally {
            deleteTree(work);
        }
    }

    private static void fetch(String url, Path to) throws IOException {
        try {
            HttpResponse<Path> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "CodeZaiku/" + FamiliarMain.VERSION).GET().build(), HttpResponse.BodyHandlers.ofFile(to));
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
    }

    static String sha256(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(p)) { byte[] buf = new byte[1 << 16]; int n; while ((n = in.read(buf)) > 0) md.update(buf, 0, n); }
            StringBuilder sb = new StringBuilder(); for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    static void deleteTree(Path p) throws IOException {
        if (p == null || !Files.exists(p)) return;
        try (var s = Files.walk(p)) { for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(x); }
    }
}
