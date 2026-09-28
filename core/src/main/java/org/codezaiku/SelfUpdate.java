package org.codezaiku;


import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.regex.Pattern;
/**
 * Updating the installed program to the latest release: the same download the installers make, checked against the
 * release's own SHA256SUMS, unpacked beside the install and swapped in with two renames. Settings and sessions
 * live under ~/.codezaiku and are not touched.
 *
 * <p>{@code CODEZAIKU_UPDATE}: {@code check} (default) says when a newer release exists; {@code auto} lets a chat
 * swap a newer release in when it starts, for the next start; {@code off} does neither. {@code codezaiku update now}
 * does it by hand. A run from the source tree never updates. Only CodeZaiku's files are replaced here: ResearchZosho
 * is updated by its own updater ({@link ResearchZoshoUpdate}).
 *
 * <p>Other programs update CodeZaiku too (Wyrdsekai does), so an update holds a lock file in the state folder from its
 * start to its end, and checks the version again under it against the files, which another update may have replaced.
 * The exit codes and the JSON are the same as ResearchZosho's updater gives, so a program that updates both reads one
 * contract. CodeZaiku has no way to swap its files after it has ended, as ResearchZosho does on Windows: on Windows the
 * running program holds its own jars, so the swap cannot work there, and {@code update now} says to run the installer
 * again instead.
 */
public final class SelfUpdate {

    private SelfUpdate() { }

    public static final String REPO = "Wyrdsekai/codezaiku";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();   // a GitHub release asset is a 302 to its store; the updater must follow it

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

    /** Whether this program runs on the Java runtime its own install carries (a jre/ beside bin/), so the machine may have no other. */
    public static boolean runsOnOwnRuntime() {
        Path r = root();
        String home = System.getProperty("java.home", "");
        try {
            return r != null && !home.isEmpty() && Path.of(home).toAbsolutePath().normalize().startsWith(r.resolve("jre").toAbsolutePath().normalize());
        } catch (RuntimeException e) { return false; }
    }

    /**
     * What an update came to, and the exit code `update now` ends with, for a program that runs it: 0 when it updated or
     * was already current, 75 when another update is running (the usual code for "try again later"), 3 when this install
     * cannot update itself (the source tree, a package manager's install, Windows), 1 when it failed. A usage mistake
     * ends with 2, as every command's does. The same codes as ResearchZosho's updater.
     */
    public enum Result {
        UPDATED(0), CURRENT(0), BUSY(75), NOT_HERE(3), FAILED(1);
        public final int code;
        Result(int code) { this.code = code; }
        /** The word the JSON gives: updated, current, busy, not-here, failed. */
        public String word() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }
        /** The result a JSON word names, or null for a word this version does not know. */
        public static Result of(String word) {
            for (Result r : values()) if (r.word().equals(word)) return r;
            return null;
        }
    }

    public record Outcome(Result result, String from, String to, String note) {
        public boolean updated() { return result == Result.UPDATED; }
    }

    // ── one update at a time ──────────────────────────────────────────────────────────────────────────────────────

    /** The file an update holds locked from its start to its end. The system lets go of it when the program ends, however it ends. */
    static Path lockFile() { return Config.home().resolve("update.lock"); }

    /*
     * Within this program the lock is also kept in a flag. On Linux and macOS, closing any channel to the lock file drops
     * every lock this process holds on it, so a check that opened and closed a second channel while an update held the
     * lock would hand the lock to another program in the middle of the swap. With the flag, nothing opens the file while
     * this program holds it.
     */
    private static final Object LOCKING = new Object();
    private static boolean heldHere;

    /** Whether an update of this install is going on now, in this or another program. */
    public static boolean updating() { return updating(lockFile()); }

    static boolean updating(Path lock) {
        synchronized (LOCKING) {
            if (heldHere) return true;
            if (!Files.exists(lock)) return false;   // every update makes the file before it locks it; a check need not make it
            try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.WRITE)) {
                FileLock l;
                try { l = ch.tryLock(); } catch (OverlappingFileLockException inThisProgram) { return true; }
                if (l == null) return true;
                l.release();
                return false;
            } catch (IOException unreadable) { return false; }
        }
    }

    /**
     * Runs an update under the lock. Another update holding it makes this one BUSY at once: it does not wait, and the
     * program that asked can ask again later, when a check of the version finds the install current.
     */
    static Outcome guarded(Path lock, Supplier<Outcome> work) {
        String have = FamiliarMain.VERSION;
        Outcome busy = new Outcome(Result.BUSY, have, have, "CodeZaiku is being updated by another program now; nothing to do.");
        synchronized (LOCKING) {
            if (heldHere) return busy;
            heldHere = true;
        }
        try {
            Files.createDirectories(lock.getParent());
            try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock held;
                try { held = ch.tryLock(); } catch (OverlappingFileLockException inThisProgram) { held = null; }
                if (held == null) return busy;
                try {
                    return work.get();
                } finally {
                    if (held.isValid()) held.release();
                }
            }
        } catch (IOException e) {
            return new Outcome(Result.FAILED, have, have, "CodeZaiku was not updated: the update lock " + lock + " could not be taken (" + e.getMessage() + ").");
        } finally {
            synchronized (LOCKING) { heldHere = false; }
        }
    }

    private static final Pattern INSTALLED_JAR = Pattern.compile("^core-(\\d+\\.\\d+\\.\\d+)\\.jar$");

    /** The version whose files are in the install now: another program may have updated them since this one started. */
    static String installedVersion(Path root) {
        try (var s = Files.list(root.resolve("lib"))) {
            for (Path p : s.toList()) {
                Matcher m = INSTALLED_JAR.matcher(p.getFileName().toString());
                if (m.matches()) return m.group(1);
            }
        } catch (IOException | RuntimeException unreadable) { }
        return FamiliarMain.VERSION;
    }

    // ── what a program that updates CodeZaiku reads ───────────────────────────────────────────────────────────────

    private static final ObjectMapper JSON = new ObjectMapper();

    /** `update --json`: what a program that updates CodeZaiku reads before it asks for an update. The fields of ResearchZosho's. */
    public static String statusJson() {
        Path root = root();
        return statusJson(root, root != null && packaged(root), ResearchZoshoInstall.windows(), latestVersion(), lockFile());
    }

    static String statusJson(Path root, boolean packaged, boolean windows, String latest, Path lock) {
        String installed = root == null ? FamiliarMain.VERSION : installedVersion(root);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("program", "codezaiku");
        m.put("installed", installed);
        m.put("running", FamiliarMain.VERSION);
        m.put("latest", latest);
        m.put("newer", latest != null && ResearchZoshoInstall.compareVersions(latest, installed) > 0);
        m.put("mode", mode());
        m.put("root", root == null ? null : root.toString());
        m.put("canUpdate", root != null && !packaged && !windows);
        m.put("updating", updating(lock));
        try { return JSON.writeValueAsString(m); } catch (IOException e) { return "{}"; }
    }

    /** `update now --json`: what the update came to. finishesAfterExit is always false: CodeZaiku swaps its files before it ends, or not at all. */
    public static String outcomeJson(Outcome o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("result", o.result().word());
        m.put("code", o.result().code);
        m.put("from", o.from());
        m.put("to", o.to());
        m.put("finishesAfterExit", false);
        m.put("note", o.note());
        try { return JSON.writeValueAsString(m); } catch (IOException e) { return "{}"; }
    }

    /** What `codezaiku update` says about CodeZaiku: the installed version, the latest, the mode, and what would happen. */
    public static String status() { return status(root(), latestVersion(), lockFile()); }

    static String status(Path root, String latest, Path lock) {
        String installed = root == null ? FamiliarMain.VERSION : installedVersion(root);
        StringBuilder b = new StringBuilder("CodeZaiku\n");
        b.append("  installed: ").append(installed).append(root == null ? " (from the source tree; updates are git pull)" : " at " + root).append('\n');
        if (!installed.equals(FamiliarMain.VERSION)) b.append("  running:   ").append(FamiliarMain.VERSION).append(" (the next codezaiku start runs ").append(installed).append(")\n");
        b.append("  latest:    ").append(latest == null ? "unknown (could not reach GitHub)" : latest).append('\n');
        b.append("  mode:      ").append(mode()).append(" (CODEZAIKU_UPDATE = check | auto | off; codezaiku update auto on|off)").append('\n');
        if (updating(lock)) b.append("  An update of CodeZaiku is running now.\n");
        if (latest != null && ResearchZoshoInstall.compareVersions(latest, installed) > 0) {
            boolean packaged = root != null && packaged(root), windows = ResearchZoshoInstall.windows();
            boolean inPlace = root != null && !packaged && !windows;
            b.append("  A newer release is out. ").append(inPlace && mode().equals("auto")
                    ? "The next chat installs it when it starts, for the start after; codezaiku update now installs it now."
                    : howToUpdate(root, packaged, windows)).append('\n');
        }
        return b.toString();
    }

    // ── the update ────────────────────────────────────────────────────────────────────────────────────────────────

    /** Update now, to the latest release (or {@code version}). */
    public static Outcome now(String version, PrintStream out) {
        Path root = root();
        String base = System.getenv("CODEZAIKU_DOWNLOAD_BASE");
        return now(root, root != null && packaged(root), ResearchZoshoInstall.windows(), lockFile(), version, SelfUpdate::latestVersion,
                base == null || base.isBlank() ? null : base, out);
    }

    /** The update for an install at {@code root}; {@code base} is where the release's files are, null for GitHub. */
    static Outcome now(Path root, boolean packaged, boolean windows, Path lock, String version, Supplier<String> latest, String base, PrintStream out) {
        String running = FamiliarMain.VERSION;
        if (root == null) return new Outcome(Result.NOT_HERE, running, running, "This CodeZaiku runs from its source tree, not from an install, so it does not update itself. Update it with git pull and a new build.");
        if (packaged) return new Outcome(Result.NOT_HERE, running, running, howToUpdate(root, true, false));
        if (windows) return new Outcome(Result.NOT_HERE, running, running, howToUpdate(root, false, true));
        return guarded(lock, () -> nowLocked(root, version, latest, base, out));
    }

    /** The update itself, under the lock: the version is checked again against the files, which another update may have replaced. */
    static Outcome nowLocked(Path root, String version, Supplier<String> latest, String base, PrintStream out) {
        String have = installedVersion(root);
        String target = version != null ? version : latest.get();
        if (target == null) return new Outcome(Result.FAILED, have, have, "CodeZaiku was not updated: GitHub could not be reached to find the latest release. Check the network and try again.");
        if (ResearchZoshoInstall.compareVersions(target, have) <= 0) {
            String note = version == null ? "CodeZaiku " + have + " is installed, the latest release; nothing to do."
                    : ResearchZoshoInstall.compareVersions(target, have) == 0 ? "CodeZaiku " + have + " is installed already; nothing to do."
                    : "CodeZaiku " + have + " is installed, which is newer than " + target + "; nothing was changed.";
            if (!have.equals(FamiliarMain.VERSION)) note += " This command ran " + FamiliarMain.VERSION + "; the next codezaiku start runs " + have + ".";
            return new Outcome(Result.CURRENT, have, target, note);
        }
        try {
            out.println("codezaiku: updating " + have + " to " + target);   // "to", not an arrow: a Windows console has no arrow and prints "?"
            swapIn(root, target, base != null ? base : "https://github.com/" + REPO + "/releases/download/v" + target, out);
            return new Outcome(Result.UPDATED, have, target, "CodeZaiku was updated from " + have + " to " + target + ". The next codezaiku start runs it; your settings and sessions stay.");
        } catch (Exception e) {
            return new Outcome(Result.FAILED, have, target, "CodeZaiku was not updated: " + e.getMessage() + ". The installed " + have + " stays.");
        }
    }

    /** The latest release's version from GitHub, or null. */
    static String latestVersion() {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + REPO + "/releases/latest")).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "CodeZaiku/" + FamiliarMain.VERSION).header("Accept", "application/vnd.github+json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            var m = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([0-9][^\"]*)\"").matcher(r.body());
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
        return r != null && packaged(r);
    }

    static boolean packaged(Path r) { return !Files.isWritable(r) || r.getParent() == null || !Files.isWritable(r.getParent()); }

    /** How this install updates: in place when we own the files, apt when a package manager does, the installer again on Windows. */
    public static String howToUpdate() {
        Path r = root();
        return howToUpdate(r, r != null && packaged(r), ResearchZoshoInstall.windows());
    }

    static String howToUpdate(Path root, boolean packaged, boolean windows) {
        if (root == null) return "Update the source tree with git.";
        if (packaged) return "A package manager installed this CodeZaiku, so it updates it too: download the new .deb from https://codezaiku.org/download/ and install it with sudo apt install ./codezaiku_<version>_all.deb.";
        if (windows) return "On Windows CodeZaiku cannot replace its own files while it runs. Close CodeZaiku and run the installer again in PowerShell: irm https://codezaiku.org/install.ps1 | iex. Your settings and sessions stay.";
        return "`codezaiku update now` installs it; your settings and sessions stay.";
    }

    /** The auto mode's turn, at the start of a chat: a newer release is swapped in for the NEXT start. Returns what it did, or "". */
    public static String maybeAuto() {
        return maybeAuto(mode(), latestCached(), v -> now(v, new PrintStream(new ByteArrayOutputStream(), true)));
    }

    /**
     * Auto mode updates CodeZaiku and nothing else. ResearchZosho has its own auto mode, which its own service runs after
     * its housekeeping, when no research run is active; a chat starting here is no moment to restart it.
     */
    static String maybeAuto(String mode, String latest, Function<String, Outcome> update) {
        if (!mode.equals("auto")) return "";
        if (latest == null || ResearchZoshoInstall.compareVersions(latest, FamiliarMain.VERSION) <= 0) return "";
        Outcome o = update.apply(latest);
        return switch (o.result()) {
            case UPDATED -> "codezaiku: updated to " + o.to() + " in place; the next start runs it (this one keeps " + FamiliarMain.VERSION + ")";
            case FAILED -> "codezaiku: the automatic update did not work. " + o.note();
            // the source tree, a package manager's install, Windows, another update running, or one that already finished: nothing to say
            default -> "";
        };
    }

    /**
     * Download {@code codezaiku-<version>.tar.gz} and SHA256SUMS from {@code base}, verify, unpack beside the root,
     * and swap: root → root.old, new → root, then remove old. Throws with a plain reason on any failure, leaving the
     * install as it was.
     */
    static void swapIn(Path root, String version, String base, PrintStream out) throws Exception {
        // an install that carries its own Java (a jre/ beside bin/) stays one: the next version's build for this platform
        boolean runtime = Files.isDirectory(root.resolve("jre"));
        String tar = "codezaiku-" + version + (runtime ? "-" + platformTag() : "") + ".tar.gz";
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
            if (runtime && !Files.isDirectory(fresh.resolve("jre"))) throw new IOException(tar + " carries no runtime; this install has one, and the next must too");
            Path old = root.resolveSibling(root.getFileName() + ".old");
            deleteTree(old);
            try {
                Files.move(root, old, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                throw new IOException("could not move the running install aside (" + e.getMessage() + ")");
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

    /** The release's name for this machine's build with its own runtime: linux-x64, linux-arm64, macos-x64, macos-arm64, windows-x64. */
    static String platformTag() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String o = os.contains("win") ? "windows" : os.contains("mac") || os.contains("darwin") ? "macos" : "linux";
        String a = arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "x64";
        return o + "-" + a;
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
        } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    static void deleteTree(Path p) throws IOException {
        if (p == null || !Files.exists(p)) return;
        try (var s = Files.walk(p)) { for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x); }
    }
}
