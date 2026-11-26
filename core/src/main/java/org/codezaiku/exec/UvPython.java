package org.codezaiku.exec;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Python a uv project asks for, installed by the person's own uv outside the sandbox, before the job's first command, into the
 * folder where uv inside the sandbox looks for Pythons. With the network closed uv cannot download one: it fetches Pythons from
 * GitHub (seen 2026-11-24: {@code https://github.com/astral-sh/python-build-standalone/releases/download/...}), which is not let in.
 *
 * <p>Nothing of the project runs: the version is read from the project's files when the job begins ({@code .python-version}, else the
 * lower bound of {@code requires-python} in {@code pyproject.toml}, else in {@code uv.lock}) and only a plain version reaches uv's
 * command line, digits and dots with an optional implementation that uv documents ({@code cpython@}, {@code pypy@}); uv runs in an
 * empty folder of CodeZaiku's with {@code --no-config}, so no file of the project is read, and {@code --no-bin}, so nothing is put in
 * the person's bin folder; its home and caches are the run's own, as inside the sandbox. Where the project names no version, uv is not
 * on the machine, or the run's folder already holds a matching Python, nothing is done. A Python of the person's own on PATH does not
 * count: the sandbox may see it and not run it. uv inside prefers the Pythons it manages (its default), so it takes this one.
 *
 * <p>Where uv inside the sandbox looks (uv's storage reference: {@code $XDG_DATA_HOME/uv/python}, {@code UV_PYTHON_INSTALL_DIR}
 * overrides it): the sandbox gives a command an {@code XDG_DATA_HOME} of the run's own, so {@code <run home>/.local/share/uv/python}
 * (shown by {@code uv python dir} inside, 2026-11-24).
 */
public final class UvPython {

    private UvPython() { }

    static final Pattern PLAIN = Pattern.compile("^(?:(?:cpython|pypy)@)?\\d+(?:\\.\\d+){0,2}$");
    private static final Pattern REQUIRES = Pattern.compile("(?m)^\\s*requires-python\\s*=\\s*[\"']([^\"'\\n]{1,200})[\"']");
    private static final Pattern LOWER = Pattern.compile("^\\s*(?:>=|~=|===?)\\s*(\\d+(?:\\.\\d+){0,2})(?:\\.\\*)?\\s*$");

    /** Runs a program: for tests, a stand-in. */
    interface Runner { Result run(List<String> argv, Map<String, String> env, Path cwd, long seconds) throws IOException; }

    record Result(int exit, String out) { }

    static final Runner REAL = (argv, env, cwd, seconds) -> {
        ProcessBuilder pb = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        pb.environment().clear();
        pb.environment().putAll(env);
        Process p = pb.start();
        String out;
        try (InputStream in = p.getInputStream()) { out = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        try {
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) { p.destroyForcibly(); return new Result(-1, out + "\n(timed out)"); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return new Result(-1, "interrupted");
        }
        return new Result(p.exitValue(), out);
    };

    /** What the project asks for and from which file, read now (when the job begins); null when it names no plain version. */
    record Wanted(String version, String from) { }

    static Wanted wanted(Path project) {
        String pin = firstLine(project.resolve(".python-version"));
        if (pin != null) return PLAIN.matcher(pin).matches() ? new Wanted(pin, ".python-version") : null;   // uv's own pin: nothing else is read
        for (String file : List.of("pyproject.toml", "uv.lock")) {
            String text = small(project.resolve(file));
            if (text == null) continue;
            Matcher m = REQUIRES.matcher(text);
            if (!m.find()) continue;
            String v = lowerBound(m.group(1));
            return v == null ? null : new Wanted(v, "requires-python in " + file);
        }
        return null;
    }

    /** The version a specifier starts from ({@code >=3.12}, {@code ~=3.11}, {@code ==3.12.*}): plain, or null when it names none. */
    static String lowerBound(String spec) {
        for (String clause : spec.split(",")) {
            Matcher m = LOWER.matcher(clause);
            if (m.matches()) return m.group(1);
        }
        return null;
    }

    private static String firstLine(Path f) {
        String text = small(f);
        if (text == null) return null;
        for (String line : text.split("\n")) {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("#")) return t;
        }
        return null;
    }

    private static String small(Path f) {
        try { return Files.isRegularFile(f) && Files.size(f) <= 1_000_000 ? Files.readString(f, StandardCharsets.UTF_8) : null; }
        catch (IOException | RuntimeException e) { return null; }
    }

    /** The person's uv: the first {@code uv} on the PATH of their login environment, or null. */
    static Path uv(Map<String, String> env) {
        for (String dir : env.getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            try {
                Path p = Path.of(dir, "uv");
                if (Files.isRegularFile(p) && Files.isExecutable(p)) return p;
            } catch (RuntimeException e) {
                // not a folder
            }
        }
        return null;
    }

    /**
     * Installs the wanted Python where uv in a sandboxed command of these folders will look. {@code env}: the environment a sandboxed
     * command gets (its home, caches and PATH); {@code mayWrite}: the folders the run owns. Returns one line, or null when nothing was
     * needed. Never throws.
     */
    static String prepare(Wanted w, Map<String, String> env, List<Path> mayWrite, Runner runner) {
        if (w == null) return null;
        Path uv = uv(env);
        if (uv == null) return null;
        String data = env.get("XDG_DATA_HOME");
        Path dir = env.get("UV_PYTHON_INSTALL_DIR") != null && !env.get("UV_PYTHON_INSTALL_DIR").isBlank() ? Path.of(env.get("UV_PYTHON_INSTALL_DIR"))
                : data == null ? null : Path.of(data, "uv", "python");
        if (dir == null || mayWrite.stream().noneMatch(dir.toAbsolutePath().normalize()::startsWith))
            return "uv: the sandbox's uv keeps its Pythons outside the run's own folders here, so Python " + w.version() + " was not installed for it.";
        Map<String, String> run = new LinkedHashMap<>(env);
        run.put("UV_PYTHON_INSTALL_DIR", dir.toString());
        run.remove("UV_PYTHON_BIN_DIR");
        Path cwd = null;
        try {
            Files.createDirectories(dir);
            cwd = Files.createTempDirectory("cz-uv-");                            // empty: no file of the project's is near uv
            // only the Pythons uv manages in the run's own folder count: one under the person's home (a link in ~/.local/bin to their own
            // uv's Pythons, seen 2026-11-24) is found from outside and refused inside, "Failed to spawn: `python` ... Permission denied"
            Result found = runner.run(List.of(uv.toString(), "python", "find", "--no-config", "--managed-python", w.version()), run, cwd, 60);
            if (found.exit() == 0) return null;                                     // already in the run's folder
            Result done = runner.run(List.of(uv.toString(), "python", "install", "--no-config", "--no-bin", w.version()), run, cwd, 900);
            if (done.exit() != 0)
                return "uv: Python " + w.version() + " could not be installed (" + lastLine(done.out()) + "); on a closed network uv's own download will be refused.";
            return "uv: installed Python " + w.version() + " (" + w.from() + ") with your uv, outside the sandbox, into the run's own folder: "
                    + "the sandbox's closed network does not reach GitHub, where uv downloads Pythons from.";
        } catch (IOException | RuntimeException e) {
            return "uv: Python " + w.version() + " could not be installed (" + e.getMessage() + "); on a closed network uv's own download will be refused.";
        } finally {
            if (cwd != null) deleteTree(cwd);
        }
    }

    private static String lastLine(String s) {
        List<String> lines = new ArrayList<>();
        for (String l : (s == null ? "" : s).split("\n")) if (!l.isBlank()) lines.add(l.strip());
        String l = lines.isEmpty() ? "no reason given" : lines.get(lines.size() - 1);
        return l.length() > 200 ? l.substring(0, 200) + "…" : l;
    }

    private static void deleteTree(Path p) {
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path q : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(q);
        } catch (IOException e) {
            // an empty folder left in the temp folder
        }
    }
}
