package org.codezaiku.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Python a uv project asks for, installed by the person's own uv outside the sandbox where uv inside will look, because a closed
 * network cannot reach GitHub, where uv downloads Pythons from. Only a plain version reaches uv's command line, uv runs in an empty
 * folder with --no-config and --no-bin, and its home is the run's own. uv here is a stand-in that records what it was asked.
 */
class UvPythonTest {

    @TempDir Path project;
    @TempDir Path run;

    record Call(List<String> argv, Map<String, String> env, Path cwd) { }

    /** A stand-in uv: {@code python find} answers {@code found}, {@code python install} answers {@code installed}. */
    static UvPython.Runner uv(List<Call> calls, int found, int installed) {
        return (argv, env, cwd, seconds) -> {
            calls.add(new Call(argv, env, cwd));
            assertTrue(Files.isDirectory(cwd), "uv runs in a folder that exists");
            try (var s = Files.list(cwd)) { assertTrue(s.findAny().isEmpty(), "and is empty: no file of the project's near uv"); }
            return argv.contains("find") ? new UvPython.Result(found, found == 0 ? "/x/python3.12" : "error: No interpreter found")
                    : new UvPython.Result(installed, installed == 0 ? "Installed Python 3.12.12" : "error: Failed to download\n  Caused by: tunnel error");
        };
    }

    Map<String, String> env() throws Exception {
        Path bin = Files.createDirectories(run.resolve("bin"));
        Path uv = Files.writeString(bin.resolve("uv"), "#!/bin/sh\n");
        uv.toFile().setExecutable(true);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", bin + ":/usr/bin");
        env.put("HOME", run.resolve("home").toString());
        env.put("XDG_DATA_HOME", run.resolve("home/.local/share").toString());
        env.put("UV_PYTHON_BIN_DIR", "/somewhere/of/the/persons");
        return env;
    }

    @Test
    void theVersionIsReadFromThePythonVersionFileThenRequiresPythonAndOnlyAPlainOneCounts() throws Exception {
        assertNull(UvPython.wanted(project), "no file names one");
        Files.writeString(project.resolve("uv.lock"), "version = 1\nrequires-python = \">=3.11\"\n");
        assertEquals(new UvPython.Wanted("3.11", "requires-python in uv.lock"), UvPython.wanted(project));
        Files.writeString(project.resolve("pyproject.toml"), "[project]\nname = \"x\"\nrequires-python = \">=3.12, <4\"\n");
        assertEquals(new UvPython.Wanted("3.12", "requires-python in pyproject.toml"), UvPython.wanted(project), "pyproject before uv.lock");
        Files.writeString(project.resolve(".python-version"), "# pinned\n3.12.7\n");
        assertEquals(new UvPython.Wanted("3.12.7", ".python-version"), UvPython.wanted(project), ".python-version first");
        Files.writeString(project.resolve(".python-version"), "pypy@3.10\n");
        assertEquals("pypy@3.10", UvPython.wanted(project).version());
        for (String bad : List.of("3.12; curl x", "--mirror=https://evil.example", "3.12 --python-downloads-json-url x", "cpython-3.12.3-linux-x86_64-gnu", "3.12$(id)"))
        {
            Files.writeString(project.resolve(".python-version"), bad + "\n");
            assertNull(UvPython.wanted(project), "not a plain version, and uv's own pin: nothing is fetched for " + bad);
        }
        assertEquals("3.11", UvPython.lowerBound("~=3.11"));
        assertEquals("3.12", UvPython.lowerBound("==3.12.*"));
        assertNull(UvPython.lowerBound("<3.13"), "no lower bound: nothing named");
    }

    @Test
    void theWantedPythonIsInstalledWhereTheSandboxsUvLooksWithNothingOfTheProjectOrThePersonsHome() throws Exception {
        List<Call> calls = new ArrayList<>();
        Map<String, String> env = env();
        String said = UvPython.prepare(new UvPython.Wanted("3.12", ".python-version"), env, List.of(run), uv(calls, 2, 0));
        assertTrue(said.startsWith("uv: installed Python 3.12 (.python-version) with your uv, outside the sandbox"), said);
        assertEquals(2, calls.size());
        Path uv = run.resolve("bin/uv");
        assertEquals(List.of(uv.toString(), "python", "find", "--no-config", "--managed-python", "3.12"), calls.get(0).argv(),
                "only the Pythons uv manages in the run's folder count: one of the person's may be seen and not run inside");
        assertEquals(List.of(uv.toString(), "python", "install", "--no-config", "--no-bin", "3.12"), calls.get(1).argv());
        Map<String, String> used = calls.get(1).env();
        assertEquals(run.resolve("home/.local/share/uv/python").toString(), used.get("UV_PYTHON_INSTALL_DIR"), "where uv inside the sandbox looks");
        assertEquals(run.resolve("home").toString(), used.get("HOME"), "the run's home, not the person's");
        assertNull(used.get("UV_PYTHON_BIN_DIR"), "no bin folder of the person's");
        assertTrue(!calls.get(1).cwd().startsWith(project), "not in the project");
    }

    @Test
    void nothingIsDoneWhenUvFindsOneOrIsMissingOrTheFolderIsNotTheRunsAndAFailureIsOneLine() throws Exception {
        List<Call> calls = new ArrayList<>();
        Map<String, String> env = env();
        assertNull(UvPython.prepare(new UvPython.Wanted("3.13", ".python-version"), env, List.of(run), uv(calls, 0, 0)), "found as it is");
        assertEquals(1, calls.size(), "only asked");
        assertNull(UvPython.prepare(null, env, List.of(run), uv(calls, 2, 0)), "nothing named");
        Map<String, String> noUv = new LinkedHashMap<>(env);
        noUv.put("PATH", "/nonexistent");
        assertNull(UvPython.prepare(new UvPython.Wanted("3.12", ".python-version"), noUv, List.of(run), uv(calls, 2, 0)), "no uv on the machine");
        Map<String, String> elsewhere = new LinkedHashMap<>(env);
        elsewhere.put("UV_PYTHON_INSTALL_DIR", "/opt/pythons");
        String said = UvPython.prepare(new UvPython.Wanted("3.12", ".python-version"), elsewhere, List.of(run), uv(calls, 2, 0));
        assertTrue(said.contains("outside the run's own folders"), said);
        assertEquals(1, calls.size(), "and uv was not run");
        said = UvPython.prepare(new UvPython.Wanted("3.12", ".python-version"), env, List.of(run), uv(calls, 2, 1));
        assertTrue(said.startsWith("uv: Python 3.12 could not be installed (Caused by: tunnel error)"), said);
    }
}
