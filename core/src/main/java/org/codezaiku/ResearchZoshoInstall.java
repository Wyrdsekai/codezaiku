package org.codezaiku;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code codezaiku install researchzosho}: the door from CodeZaiku to its sibling. Downloads the
 * ResearchZosho release for this platform, checks it against the release's own SHA256SUMS, puts the
 * command on the path, and hands over to {@code researchzosho setup}, which asks the few questions
 * that make a working library. If researchzosho is already installed, it goes straight to setup.
 *
 * <p>The same rules as ResearchZosho's own one-line installers: the artifact and the checksums come
 * from the same release, a mismatch is refused, and only the version named or the latest is fetched.
 * {@code CODEZAIKU_RESEARCHZOSHO_DOWNLOAD_BASE} is the test hook (a directory holding the tarball and
 * SHA256SUMS, as a URL).
 */
public final class ResearchZoshoInstall {

    static final String REPO = "Wyrdsekai/researchzosho";

    private ResearchZoshoInstall() { }

    /** The installed command, or null when none is on the path. */
    public static Path installed() {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            for (String name : windows() ? new String[]{"researchzosho.bat", "researchzosho.cmd"} : new String[]{"researchzosho"}) {
                Path p = Path.of(dir, name);
                if (Files.isRegularFile(p)) return p;
            }
        }
        Path home = defaultLauncher();
        return Files.isRegularFile(home) ? home : null;
    }

    static boolean windows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }

    static Path defaultPrefix() {
        String env = System.getenv("RESEARCHZOSHO_PREFIX");
        if (env != null && !env.isBlank()) return Path.of(env);
        if (windows()) {
            String lad = System.getenv("LOCALAPPDATA");
            return Path.of(lad == null ? System.getProperty("user.home") : lad, "Programs");
        }
        return Path.of(System.getProperty("user.home"), ".local");
    }

    static Path defaultLauncher() {
        Path prefix = defaultPrefix();
        return windows() ? prefix.resolve("researchzosho").resolve("bin").resolve("researchzosho.bat")
                         : prefix.resolve("share").resolve("researchzosho").resolve("bin").resolve("researchzosho");
    }

    /** The installed command's version ("0.1.2"), or null when none is installed or it does not say. */
    public static String installedVersion() {
        Path launcher = installed();
        if (launcher == null) return null;
        try {
            Process p = new ProcessBuilder(launcher.toString(), "--version").redirectErrorStream(true).start();
            String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            var m = java.util.regex.Pattern.compile("researchzosho\\s+(\\d+\\.\\d+\\.\\d+)").matcher(o);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) { return null; }
    }

    static Path latestCache() { return org.codezaiku.Config.home().resolve("research").resolve("researchzosho-latest.txt"); }
    private static volatile boolean refreshing;

    /** The latest release's version, from a cache refreshed in the background at most once a day; null until known. Never blocks. */
    public static String latestCached() {
        try {
            Path c = latestCache();
            long age = Files.exists(c) ? System.currentTimeMillis() - Files.getLastModifiedTime(c).toMillis() : Long.MAX_VALUE;
            String cached = Files.exists(c) ? Files.readString(c, StandardCharsets.UTF_8).strip() : null;
            if (age > 24L * 3600 * 1000 && !refreshing) {
                refreshing = true;
                Thread t = new Thread(() -> { try { String v = latestVersion(); if (v != null) { Files.createDirectories(c.getParent()); Files.writeString(c, v, StandardCharsets.UTF_8); } } catch (Exception ignored) { } finally { refreshing = false; } }, "researchzosho-version-check");
                t.setDaemon(true); t.start();
            }
            return cached == null || cached.isEmpty() ? null : cached;
        } catch (Exception e) { return null; }
    }

    /** "a.b.c" against "x.y.z": positive when the first is newer. */
    public static int compareVersions(String a, String b) {
        String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0, q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return p - q;
        }
        return 0;
    }

    /** One line when a newer ResearchZosho than the installed one is released, from the cache; "" otherwise or when unknown. */
    public static String updateNotice() {
        String have = installedVersion(), latest = latestCached();
        if (have == null || latest == null || compareVersions(latest, have) <= 0) return "";
        return "ResearchZosho " + latest + " is available (installed: " + have + "). `codezaiku install researchzosho` updates it; the library and settings stay.";
    }

    /** The latest release's version from GitHub, or null. */
    static String latestVersion() {
        try (InputStream in = new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openStream()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var m = java.util.regex.Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"").matcher(body);
            return m.find() ? m.group(1) : null;
        } catch (IOException e) { return null; }
    }

    /** Download, verify, unpack and put on the path. Returns the launcher. Throws with a plain reason. */
    public static Path install(String version, Path prefix, String base, PrintStream out) throws IOException, InterruptedException {
        if (version == null || version.isBlank()) {
            version = latestVersion();
            if (version == null) throw new IOException("could not find the latest ResearchZosho release; pass --version");
        }
        if (base == null || base.isBlank()) base = "https://github.com/" + REPO + "/releases/download/v" + version;
        String tar = "researchzosho-" + version + ".tar.gz";
        Path tmp = Files.createTempDirectory("researchzosho-install");
        try {
            out.println("codezaiku: downloading " + tar);
            Path tarPath = tmp.resolve(tar);
            fetch(base + "/" + tar, tarPath);
            Path sums = tmp.resolve("SHA256SUMS");
            try { fetch(base + "/SHA256SUMS", sums); }
            catch (IOException e) { throw new IOException("the release has no SHA256SUMS; refusing to install something unchecked"); }
            String expect = null;
            for (String line : Files.readAllLines(sums, StandardCharsets.UTF_8)) {
                String[] p = line.strip().split("\\s+");
                if (p.length >= 2 && (p[1].equals(tar) || p[1].equals("./" + tar) || p[1].equals("*" + tar))) expect = p[0].toLowerCase(Locale.ROOT);
            }
            if (expect == null) throw new IOException(tar + " is not listed in SHA256SUMS");
            String actual = sha256(tarPath);
            if (!expect.equals(actual)) throw new IOException("checksum mismatch for " + tar + "; refusing to install\n  expected " + expect + "\n  got      " + actual);
            out.println("codezaiku: checksum verified");

            Path unpack = tmp.resolve("x");
            Files.createDirectories(unpack);
            Process p = new ProcessBuilder("tar", "xzf", tarPath.toString(), "-C", unpack.toString()).redirectErrorStream(true).start();
            String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) throw new IOException("could not unpack " + tar + ": " + o.strip());
            Path unpacked = unpack.resolve("researchzosho");
            if (!Files.isDirectory(unpacked)) throw new IOException(tar + " did not contain a researchzosho folder");

            Path launcher;
            if (windows()) {
                Path dest = prefix.resolve("researchzosho");
                deleteTree(dest);
                Files.createDirectories(prefix);
                move(unpacked, dest);
                launcher = dest.resolve("bin").resolve("researchzosho.bat");
                addToUserPath(dest.resolve("bin"), out);
            } else {
                Path lib = prefix.resolve("share").resolve("researchzosho");
                Path bin = prefix.resolve("bin");
                deleteTree(lib);
                Files.createDirectories(lib.getParent());
                Files.createDirectories(bin);
                move(unpacked, lib);
                for (String name : new String[]{"researchzosho", "zosho"}) {
                    Path w = bin.resolve(name);
                    Files.writeString(w, "#!/bin/sh\nexec \"" + lib + "/bin/" + name + "\" \"$@\"\n", StandardCharsets.UTF_8);
                    w.toFile().setExecutable(true, false);
                }
                launcher = bin.resolve("researchzosho");
                String path = System.getenv("PATH");
                if (path != null && !(":" + path + ":").contains(":" + bin + ":")) out.println("codezaiku: " + bin + " is not on your PATH. Add it:  export PATH=\"" + bin + ":$PATH\"");
            }
            out.println("codezaiku: installed researchzosho " + version + " at " + launcher);
            return launcher;
        } finally {
            deleteTree(tmp);
        }
    }

    static void fetch(String url, Path to) throws IOException {
        try (InputStream in = new URL(url).openStream()) { Files.copy(in, to); }
        catch (IOException e) { throw new IOException("download failed: " + url); }
    }

    static String sha256(Path f) throws IOException {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(f)) { byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) > 0) d.update(buf, 0, n); }
            StringBuilder sb = new StringBuilder();
            for (byte b : d.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    static void move(Path from, Path to) throws IOException {
        try { Files.move(from, to); }
        catch (IOException e) {   // across file systems: copy
            Files.walk(from).forEach(src -> {
                try { Path dst = to.resolve(from.relativize(src).toString()); if (Files.isDirectory(src)) Files.createDirectories(dst); else Files.copy(src, dst); }
                catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
            });
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) { s.sorted(java.util.Comparator.reverseOrder()).forEach(q -> { try { Files.deleteIfExists(q); } catch (IOException ignored) { } }); }
    }

    static void addToUserPath(Path bin, PrintStream out) {
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "$b='" + bin + "'; $u=[Environment]::GetEnvironmentVariable('Path','User'); if ($u -notlike \"*$b*\") { [Environment]::SetEnvironmentVariable('Path', \"$b;$u\", 'User'); 'added' } else { 'present' }")
                    .redirectErrorStream(true).start();
            String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            p.waitFor();
            if (o.contains("added")) out.println("codezaiku: added " + bin + " to your user PATH (open a new terminal to pick it up)");
        } catch (Exception e) {
            out.println("codezaiku: could not add " + bin + " to your PATH; add it yourself");
        }
    }

    /** The verb: install if needed, then hand over to setup with the terminal. Returns the exit code. */
    public static int door(String[] args, PrintStream out) {
        String version = null; boolean setup = true;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--version") && i + 1 < args.length) version = args[++i];
            else if (args[i].equals("--no-setup")) setup = false;
        }
        Path launcher = installed();
        if (launcher != null) {
            // installed already: update it when a newer release exists (the same download, checked and swapped in place)
            String have = installedVersion(), latest = version != null ? version : latestVersion();
            if (have != null && latest != null && compareVersions(latest, have) > 0) {
                out.println("codezaiku: researchzosho " + have + " is installed; " + latest + " is available. Updating; the library and settings stay.");
                try {
                    launcher = install(latest, defaultPrefix(), System.getenv("CODEZAIKU_RESEARCHZOSHO_DOWNLOAD_BASE"), out);
                    out.println("codezaiku: if the researchzosho service is running, restart it: researchzosho service uninstall, then researchzosho service install");
                } catch (Exception e) {
                    out.println("codezaiku: could not update (" + e.getMessage() + "); the installed " + have + " stays");
                }
            } else {
                out.println("codezaiku: researchzosho " + (have == null ? "" : have + " ") + "is already installed at " + launcher + (latest == null ? "" : " (the latest release is " + latest + ")"));
            }
        } else {
            try {
                launcher = install(version, defaultPrefix(), System.getenv("CODEZAIKU_RESEARCHZOSHO_DOWNLOAD_BASE"), out);
            } catch (Exception e) {
                out.println("codezaiku: " + e.getMessage());
                return 1;
            }
        }
        if (!setup) return 0;
        out.println("codezaiku: handing over to researchzosho setup");
        int rc;
        try {
            List<String> cmd = new ArrayList<>(List.of(launcher.toString(), "setup"));
            Process p = new ProcessBuilder(cmd).inheritIO().start();
            rc = p.waitFor();
        } catch (Exception e) {
            out.println("codezaiku: could not start setup (" + e.getMessage() + "); run: " + launcher + " setup");
            return 1;
        }
        if (rc == 0) connect(launcher, out);
        return rc;
    }

    /** The did CodeZaiku writes to the library under. */
    static final String DID = "did:key:local-codezaiku";

    /**
     * After setup: the upgrade from CodeZaiku's own research memory to the library. A write token for
     * this program (so the chat can file runs), stored as CODEZAIKU_LIBRARIAN_TOKEN, and every question
     * the research memory has seen handed to the library's open questions, so the library knows what
     * the person has been asking and its housekeeping can pick them up.
     */
    static void connect(Path launcher, PrintStream out) {
        if (org.codezaiku.research.LibraryBridge.token() == null) {
            String token = mintToken(launcher);
            if (token != null) {
                try { org.codezaiku.Config.set("CODEZAIKU_LIBRARIAN_TOKEN", token); out.println("codezaiku: the chat can file research runs (token stored as CODEZAIKU_LIBRARIAN_TOKEN)"); }
                catch (Exception e) { out.println("codezaiku: could not store the token: " + e.getMessage()); }
            } else {
                out.println("codezaiku: no write token made — filing runs from the chat needs one: researchzosho reader token " + DID);
            }
        }
        org.codezaiku.research.LibraryBridge.reprobe();
        handOver(out);
    }

    /** `researchzosho reader allow <did> write codezaiku`, then `reader token <did>`; the token is the last line printed. */
    static String mintToken(Path launcher) {
        try {
            run(launcher, "reader", "allow", DID, "write", "codezaiku");
            String o = run(launcher, "reader", "token", DID);
            String[] ls = o.strip().split("\\R");
            String last = ls[ls.length - 1].strip();
            return last.isEmpty() || last.contains(" ") ? null : last;
        } catch (Exception e) {
            return null;
        }
    }

    /** The research memory's topics become the library's open questions; the answers stay readable here. */
    static void handOver(PrintStream out) {
        if (!org.codezaiku.research.LibraryBridge.answers()) return;
        java.util.LinkedHashSet<String> topics = new java.util.LinkedHashSet<>();
        for (var f : org.codezaiku.research.ResearchMemory.all()) if (f.topic() != null && !f.topic().isBlank()) topics.add(f.topic().strip());
        if (topics.isEmpty()) return;
        int n = 0;
        try {
            var client = org.codezaiku.research.LibraryBridge.client();
            for (String t : topics) { client.frontierAdd(t); n++; }
            out.println("codezaiku: " + n + " question(s) from the research memory are now on the library's open-questions list");
        } catch (Exception e) {
            out.println("codezaiku: handed over " + n + " of " + topics.size() + " research-memory questions (" + e.getMessage() + ")");
        }
    }

    private static String run(Path launcher, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(); cmd.add(launcher.toString()); cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String o = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IllegalStateException(String.join(" ", args) + ": " + o.strip());
        return o;
    }

    /**
     * `codezaiku librarian …`: the installed researchzosho command with the same arguments. Without
     * one, says how to get it; nothing of the library runs in this process.
     */
    public static int alias(String[] args, PrintStream out) {
        Path launcher = installed();
        if (launcher == null) {
            out.println("codezaiku: ResearchZosho is not installed. `codezaiku install researchzosho` fetches it and runs its setup;");
            out.println("           until then, `codezaiku research \"…\"` answers from the web and keeps what it finds in the research memory.");
            return 1;
        }
        try {
            List<String> cmd = new ArrayList<>(); cmd.add(launcher.toString()); cmd.addAll(List.of(args));
            return new ProcessBuilder(cmd).inheritIO().start().waitFor();
        } catch (Exception e) {
            out.println("codezaiku: could not run " + launcher + ": " + e.getMessage());
            return 1;
        }
    }
}
