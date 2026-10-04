package org.codezaiku.tools;

import org.codezaiku.Config;
import org.codezaiku.exec.Shell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Where a run's installs go: an environment of the project's own, not the user's Python or global package folders.
 *
 * <p>A run installs what it needs itself, and it used to do that with whatever {@code pip} was first on the path — on 2026-09-29, the
 * user's base conda Python, eleven packages in two runs. Now each project gets a folder under CodeZaiku's home (never inside the
 * project, so the run's list of changed files stays the project's own): a Python virtual environment that can still see the packages
 * already installed ({@code --system-site-packages}, so a globally installed pytest keeps working), and the global install folders of
 * npm, cargo, go and gem pointed at the same place. Every shell command starts by switching it on — inside the command, because the
 * login shell's profile ({@code conda init}) would otherwise put the user's Python back in front. CODEZAIKU_PROJECT_ENV=off turns it off.
 */
public final class ProjectEnv {

    private static final Logger log = LoggerFactory.getLogger(ProjectEnv.class);
    private static final Map<Path, String> PRELUDES = new ConcurrentHashMap<>();

    private ProjectEnv() { }

    /**
     * On unless CODEZAIKU_PROJECT_ENV is off. Not on native Windows yet: its shell is Git Bash, where the environment's paths need
     * another spelling (/c/Users/…) and its commands live in Scripts, and that has not been run there.
     */
    /** For tests: on whatever the setting says (the test run turns the setting off for every other test). */
    static volatile boolean forceOn = false;

    public static boolean enabled() {
        if (forceOn) return true;
        String v = Config.get("CODEZAIKU_PROJECT_ENV", "on");
        if (v.equalsIgnoreCase("off") || v.equalsIgnoreCase("false") || v.equals("0")) return false;
        return !(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") && !Shell.isWsl());
    }

    /** The project's folder: CodeZaiku's home, envs, the project's name and a short hash of its full path. */
    public static Path dirFor(Path projectRoot) {
        Path real;
        try { real = projectRoot.toRealPath(); } catch (IOException e) { real = projectRoot.toAbsolutePath().normalize(); }
        String name = real.getFileName() == null ? "root" : real.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
        return Config.home().resolve("envs").resolve(name + "-" + hash(real.toString()));
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** The shell text that switches the project's environment on, made once per project; empty when turned off. */
    public static String prelude(Path projectRoot) {
        if (!enabled()) return "";
        return PRELUDES.computeIfAbsent(projectRoot.toAbsolutePath().normalize(), root -> build(dirFor(root), root));
    }

    static String build(Path dir, Path projectRoot) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(PROJECT_FILE), projectRoot.toAbsolutePath().normalize().toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("project environment {} could not be made: {}", dir, e.toString());
            return "";
        }
        prune(dir.getParent());
        Path py = dir.resolve("py");
        Path pyBin = pythonBin(py);
        if (pyBin == null) {
            try {
                Process p = Shell.pb("python3 -m venv --system-site-packages " + quote(py.toString()) + " >/dev/null 2>&1 || python -m venv --system-site-packages "
                        + quote(py.toString()) + " >/dev/null 2>&1").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (!p.waitFor(180, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (IOException e) {
                log.info("no Python virtual environment for {}: {}", dir, e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            pyBin = pythonBin(py);
            log.info(pyBin != null ? "project environment for installs: {}" : "project environment {} has no Python part (no python3 with venv here)", dir);
        }
        String d = dir.toString();
        StringBuilder s = new StringBuilder();
        s.append("export NPM_CONFIG_PREFIX=").append(quote(d + "/npm"))
                .append(" CARGO_INSTALL_ROOT=").append(quote(d + "/cargo"))
                .append(" GOBIN=").append(quote(d + "/go/bin"))
                .append(" GEM_HOME=").append(quote(d + "/gem"))
                .append(" PYTHONUSERBASE=").append(quote(d + "/pyuser")).append("; ");
        s.append("export PATH=").append(quote(d + "/npm/bin")).append(":").append(quote(d + "/cargo/bin")).append(":")
                .append(quote(d + "/go/bin")).append(":").append(quote(d + "/gem/bin")).append(":\"$PATH\"; ");
        if (pyBin != null) {
            s.append("export VIRTUAL_ENV=").append(quote(py.toString())).append("; unset PYTHONHOME; export PATH=")
                    .append(quote(pyBin.toString())).append(":\"$PATH\"; ");
        }
        return s.toString();
    }

    /** The file in each environment that names its project. */
    static final String PROJECT_FILE = "project.txt";

    /**
     * Remove the environments whose project folder no longer exists, so they do not pile up. Only folders directly under the envs
     * folder that name their project in {@link #PROJECT_FILE} are ever removed.
     */
    static int prune(Path envs) {
        int removed = 0;
        if (envs == null || !Files.isDirectory(envs)) return 0;
        try (var list = Files.list(envs)) {
            for (Path env : list.toList()) {
                Path named = env.resolve(PROJECT_FILE);
                if (!Files.isRegularFile(named)) continue;
                String project = Files.readString(named, StandardCharsets.UTF_8).strip();
                if (project.isEmpty() || Files.isDirectory(Path.of(project))) continue;
                try (var walk = Files.walk(env)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                }
                removed++;
                log.info("removed the environment of {}, which no longer exists: {}", project, env);
            }
        } catch (IOException e) {
            log.info("could not tidy {}: {}", envs, e.toString());
        }
        return removed;
    }

    /** A virtual environment's command folder: bin on Linux and macOS, Scripts on Windows; null when there is none. */
    static Path pythonBin(Path venv) {
        for (String b : new String[]{"bin", "Scripts"}) {
            Path p = venv.resolve(b);
            if (Files.isRegularFile(p.resolve("python")) || Files.isRegularFile(p.resolve("python.exe")) || Files.isRegularFile(p.resolve("python3"))) return p;
        }
        return null;
    }

    /** The text as one shell word, in single quotes. */
    public static String quote(String s) { return "'" + s.replace("'", "'\"'\"'") + "'"; }

    /** For tests: forget what was made in this process. */
    static void forget() { PRELUDES.clear(); }
}
