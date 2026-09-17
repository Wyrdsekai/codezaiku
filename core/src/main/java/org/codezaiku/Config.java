package org.codezaiku;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Settings, from a config file or the environment.
 *
 * CodeZaiku has ~60 knobs. Requiring all of them to be exported by hand means a working setup lives in
 * someone's shell history and cannot be reproduced, shared or reviewed. So settings are read from a
 * file, and the environment overrides it.
 *
 * <p>Lookup order, first hit wins:
 * <ol>
 *   <li>the environment variable — an explicit export always wins, so scripts and CI keep working</li>
 *   <li>{@code $CODEZAIKU_CONFIG} if set, else {@code ~/.codezaiku/config}</li>
 *   <li>{@code /etc/codezaiku/config} — the system-wide file a package can drop</li>
 * </ol>
 *
 * <p>File format is deliberately boring: {@code key = value}, {@code #} comments, blank lines ignored.
 * Keys may be written in either the environment form or a dotted shorthand — {@code CODEZAIKU_DRIVE},
 * {@code drive} and {@code ops.authority} all resolve. Nobody should have to remember which.
 */
public final class Config {

    private static final Map<String, String> FILE = new LinkedHashMap<>();
    private static volatile boolean loaded = false;
    private static Path loadedFrom = null;

    /** The value for an environment-style key such as {@code CODEZAIKU_OPS_AUTHORITY}, or null. */
    public static String get(String envKey) {
        ensureLoaded();
        String v = resolve(env(envKey), FILE.get(normalize(envKey)), env(envKey + DEFAULT_SUFFIX));
        return envKey.endsWith("_DRIVE") ? driveBase(v) : v;
    }

    /**
     * A drive address as every caller expects it: the base, with no {@code /v1} on the end. Providers print their
     * address as {@code https://host/api/v1}, people paste that, and each request then went to {@code /v1/v1/…} and
     * came back 404. The pasted forms are accepted: a trailing {@code /v1}, {@code /v1/chat/completions} or
     * {@code /v1/models}, and trailing slashes. Anything that is not an http address ("off") is left as it is.
     */
    public static String driveBase(String url) {
        if (url == null) return null;
        String u = url.strip();
        if (!u.regionMatches(true, 0, "http", 0, 4)) return url;
        u = u.replaceAll("/+$", "");
        for (String tail : new String[]{"/chat/completions", "/completions", "/models"})
            if (u.endsWith("/v1" + tail)) { u = u.substring(0, u.length() - tail.length()); break; }
        if (u.endsWith("/v1")) u = u.substring(0, u.length() - 3);
        return u.replaceAll("/+$", "");
    }

    /** Read an environment variable. */
    public static String env(String key) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : null;
    }

    /**
     * Suffix a host appends to OFFER a value rather than impose one: {@code CODEZAIKU_DRIVE_DEFAULT}
     * is used only when this machine has not been configured itself.
     *
     * <p>An environment variable is a single channel carrying two different intentions — "the
     * operator chose this" and "I am a host filling in a blank" — and CodeZaiku cannot tell them
     * apart. A host that injects its own built-in default through the plain variable silently
     * overrides a machine that WAS deliberately configured, and the mismatch is invisible: the
     * config file still reads correctly, so {@code doctor} reports healthy while a spawned run goes
     * somewhere else entirely. Splitting the channel lets a host say which it meant.
     */
    public static final String DEFAULT_SUFFIX = "_DEFAULT";

    /**
     * Precedence, highest first: an explicit environment override, then this machine's config file,
     * then a host-supplied default, then nothing. Pure so the ordering can be tested without
     * arranging a process environment.
     */
    static String resolve(String envOverride, String fromFile, String hostDefault) {
        if (envOverride != null && !envOverride.isBlank()) return envOverride;
        if (fromFile != null && !fromFile.isBlank()) return fromFile;
        if (hostDefault != null && !hostDefault.isBlank()) return hostDefault;
        return null;
    }

    public static String get(String envKey, String fallback) {
        String v = get(envKey);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    public static boolean isOn(String envKey, boolean fallback) {
        String v = get(envKey);
        if (v == null || v.isBlank()) return fallback;
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.equals("on") || v.equals("true") || v.equals("yes") || v.equals("1");
    }

    public static int getInt(String envKey, int fallback) {
        try {
            String v = get(envKey);
            return (v == null || v.isBlank()) ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Which file the settings came from, or null when none was found. For `doctor` to report. */
    public static Path source() {
        ensureLoaded();
        return loadedFrom;
    }

    /** The default per-user config path, whether or not it exists yet. */
    public static Path userConfigPath() {
        String explicit = env("CODEZAIKU_CONFIG");
        if (explicit != null && !explicit.isBlank()) return Paths.get(explicit);
        return home().resolve("config");
    }

    /**
     * The per-user state directory, {@code ~/.codezaiku} -- or the old {@code ~/.codeplane} if that
     * is the one that actually exists. An install predating the rename keeps its cards, caches and
     * research memory rather than silently starting empty.
     */
    public static Path home() {
        Path base = Paths.get(System.getProperty("user.home", "."));
        Path now = base.resolve(".codezaiku");
        Path was = base.resolve(".codeplane");
        return (!Files.isDirectory(now) && Files.isDirectory(was)) ? was : now;
    }

    /**
     * The user's config file holds API keys, so only its owner may read it: the file becomes 600, and the settings
     * folder 700 when it is our own folder under the home directory. With a umask of 002, the default on many
     * desktops, both were group-readable. Done on every load as well as every write, so a file made by an older
     * version or by an editor is tightened the next time anything runs. A file system without POSIX permissions
     * (Windows) is left alone, and so is a file we do not own.
     */
    static void keepPrivate(Path cfg) {
        try {
            if (cfg == null || !Files.exists(cfg) || !Files.getFileStore(cfg).supportsFileAttributeView("posix")) return;
            var owner = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
            if (!Files.getPosixFilePermissions(cfg).equals(owner)) Files.setPosixFilePermissions(cfg, owner);
            Path dir = cfg.toAbsolutePath().getParent();
            String name = dir == null || dir.getFileName() == null ? "" : dir.getFileName().toString();
            if (dir != null && (name.equals(".codezaiku") || name.equals(".codeplane"))) {
                var mine = java.nio.file.attribute.PosixFilePermissions.fromString("rwx------");
                if (!Files.getPosixFilePermissions(dir).equals(mine)) Files.setPosixFilePermissions(dir, mine);
            }
        } catch (Exception ignored) {
            // not ours to change, or the file system said no: the settings still load
        }
    }

    private static void ensureLoaded() {
        if (loaded) return;
        synchronized (Config.class) {
            if (loaded) return;
            for (Path p : new Path[]{userConfigPath(), Paths.get("/etc/codezaiku/config")}) {
                if (p != null && Files.isReadable(p)) {
                    read(p);
                    if (p.equals(userConfigPath())) keepPrivate(p);
                    loadedFrom = p;
                    break;      // the user's file wins outright; we do not merge layers
                }
            }
            loaded = true;
        }
    }

    private static void read(Path p) {
        try {
            for (String raw : Files.readAllLines(p)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = normalize(line.substring(0, eq).trim());
                String val = line.substring(eq + 1).trim();
                // strip one layer of matching quotes, so values with spaces read naturally
                if (val.length() >= 2
                        && ((val.startsWith("\"") && val.endsWith("\""))
                         || (val.startsWith("'") && val.endsWith("'"))))
                    val = val.substring(1, val.length() - 1);
                if (!key.isEmpty()) FILE.put(key, val);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + p, e);
        }
    }

    /** `ops.authority`, `OPS_AUTHORITY` and `CODEZAIKU_OPS_AUTHORITY` are the same setting. */
    private static String normalize(String key) {
        String k = key.trim().toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        return k.startsWith("CODEZAIKU_") ? k : "CODEZAIKU_" + k;
    }

    /**
     * Set a key in the user config file, preserving comments, ordering and the spelling already used
     * in the file. An in-place edit rather than a rewrite: a config someone has commented and tuned
     * must survive `codezaiku config set`, or they will stop using the command and go back to $EDITOR.
     */
    public static void set(String key, String value) throws IOException {
        Path cfg = userConfigPath();
        Files.createDirectories(cfg.getParent());
        String want = normalize(key);
        List<String> lines = Files.exists(cfg)
                ? new ArrayList<>(Files.readAllLines(cfg))
                : new ArrayList<>();

        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int eq = trimmed.indexOf('=');
            if (eq < 0) continue;
            if (!normalize(trimmed.substring(0, eq).trim()).equals(want)) continue;
            // keep the key exactly as the user wrote it, and any leading indentation
            String lead = line.substring(0, line.indexOf(trimmed.charAt(0) == '#' ? '#' : trimmed.charAt(0)));
            lines.set(i, lead + trimmed.substring(0, eq).trim() + " = " + value);
            replaced = true;
            break;
        }
        if (!replaced) {
            // a commented-out template line for this key is the natural place to land
            for (int i = 0; i < lines.size(); i++) {
                String t = lines.get(i).trim();
                if (!t.startsWith("#")) continue;
                String body = t.substring(1).trim();
                int eq = body.indexOf('=');
                if (eq > 0 && normalize(body.substring(0, eq).trim()).equals(want)) {
                    lines.set(i, body.substring(0, eq).trim() + " = " + value);
                    replaced = true;
                    break;
                }
            }
        }
        if (!replaced) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) lines.add("");
            lines.add(shorthand(want) + " = " + value);
        }
        Files.writeString(cfg, String.join("\n", lines) + "\n");
        keepPrivate(cfg);
        invalidate();
    }

    /** Remove a key from the user config file. Returns true if something was removed. */
    public static boolean unset(String key) throws IOException {
        Path cfg = userConfigPath();
        if (!Files.exists(cfg)) return false;
        String want = normalize(key);
        List<String> out = new ArrayList<>();
        boolean removed = false;
        for (String line : Files.readAllLines(cfg)) {
            String t = line.trim();
            int eq = t.indexOf('=');
            if (!t.isEmpty() && !t.startsWith("#") && eq > 0
                    && normalize(t.substring(0, eq).trim()).equals(want)) {
                removed = true;
                continue;
            }
            out.add(line);
        }
        if (removed) {
            Files.writeString(cfg, String.join("\n", out) + "\n");
            keepPrivate(cfg);
            invalidate();
        }
        return removed;
    }

    /** Every setting currently in effect, with where each came from. */
    public static Map<String, String[]> effective(Collection<String> knownKeys) {
        ensureLoaded();
        Map<String, String[]> out = new LinkedHashMap<>();
        for (String k : knownKeys) {
            // Must follow the SAME order as get(), including the host-default channel — a listing
            // that reports "(unset)" for a value the next run will actually use is worse than no
            // listing, because it is the thing an operator checks when a run went somewhere
            // unexpected.
            String env = env(k);
            String f = FILE.get(normalize(k));
            String hostDefault = env(k + DEFAULT_SUFFIX);
            if (env != null && !env.isBlank()) {
                out.put(k, new String[]{env, "environment"});
            } else if (f != null && !f.isBlank()) {
                out.put(k, new String[]{f, "config file"});
            } else if (hostDefault != null && !hostDefault.isBlank()) {
                out.put(k, new String[]{hostDefault, "host default"});
            }
        }
        return out;
    }

    /** Config-file keys starting with a prefix — for settings that are a SET rather than one value,
     *  such as the named model endpoints (`model.<name>.url`). Environment-only keys are included too. */
    public static List<String> keysWithPrefix(String prefix) {
        ensureLoaded();
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String k : FILE.keySet()) if (k.startsWith(prefix)) keys.add(k);
        for (String k : System.getenv().keySet()) if (k.startsWith(prefix)) keys.add(k);
        return new ArrayList<>(keys);
    }

    /** CODEZAIKU_OPS_AUTHORITY -> ops.authority, the form the starter config uses. */
    private static String shorthand(String envKey) {
        String k = envKey.startsWith("CODEZAIKU_") ? envKey.substring("CODEZAIKU_".length()) : envKey;
        return k.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    static synchronized void invalidate() {
        FILE.clear();
        loaded = false;
        loadedFrom = null;
    }

    private Config() { }
}
