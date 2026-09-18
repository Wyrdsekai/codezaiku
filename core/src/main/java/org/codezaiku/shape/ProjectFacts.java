package org.codezaiku.shape;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The derived "project-shape snapshot" — build system, language, source root, test framework, and
 * the EXISTING module inventory — recomputed from disk every turn (SPEC_CODEZAIKU_AS_FAMILIAR §5.4
 * structural compartment / SPEC_CODEZAIKU_PROJECT_MEMORY Cut-1 derived layer). It is authoritative
 * and cannot drift, and it tells the 9B "this is the established structure — add files UNDER it",
 * which is what stops the competing-roots thrash on multi-file projects.
 *
 * <p>DERIVED, never narrated — so it is not the L1 memory-store trap (the growing conversation
 * already holds decisions/attempts; this layer holds ground truth the harness computes).
 */
public final class ProjectFacts {
    private static final Set<String> IGNORE = Set.of(
            ".git", "node_modules", "build", "target", "__pycache__", "dist", ".gradle",
            ".venv", "venv", ".idea", "bin", "obj", ".pytest_cache", "fixtures");

    private ProjectFacts() {
    }

    /** Detected primary language (e.g. "rust", "python", "java"), or null. Used as the library
     *  framework filter so retrieval is stack-scoped. */
    public static String language(Path root) {
        Path base = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) return null;
        return detectLanguage(base, detectBuild(base));
    }

    public static String render(Path root) {
        Path base = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) return "";

        List<String> lines = new ArrayList<>();
        String build = detectBuild(base);
        String lang = detectLanguage(base, build);
        String srcRoot = sourceRoot(base, build);

        if (build != null) lines.add("build: " + build);
        String iterate = iterateHint(build);
        if (iterate != null) lines.add(iterate);
        if (lang != null) lines.add("language: " + lang);
        if (srcRoot != null) lines.add("source root: " + srcRoot);
        String test = detectTest(base, build);
        if (test != null) lines.add("test: " + test);
        if (srcRoot != null) {
            List<String> inv = inventory(base.resolve(srcRoot));
            if (!inv.isEmpty()) lines.add("existing under source root: " + String.join(", ", inv));
        }

        if (lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "PROJECT SHAPE (derived from disk — add files under this structure, do not scatter):");
        for (String l : lines) sb.append("\n - ").append(l);
        sb.append(symbols(base, srcRoot, lang));
        return sb.append("\n\n").toString();
    }

    // Top-level / exported symbols per language — so the model imports & reuses what exists instead
    // of rewriting an established contract (the get_db/SessionLocal break we saw on email-intel-py).
    private static final Map<String, Pattern> SYMBOL_PATTERNS = Map.of(
            "python", Pattern.compile("(?m)^(?:async +)?(?:def|class)\\s+(\\w+)|^([A-Za-z_]\\w*)\\s*(?::[^=\\n]+)?=(?!=)"),
            "java", Pattern.compile("(?m)^\\s*(?:public|private|protected)?\\s*(?:static\\s+|final\\s+|abstract\\s+|sealed\\s+)*(?:class|interface|record|enum)\\s+(\\w+)"),
            "javascript", Pattern.compile("module\\.exports\\.(\\w+)|exports\\.(\\w+)|module\\.exports\\s*=\\s*(\\w+)|(?m)^(?:async +)?function\\s+(\\w+)|(?m)^class\\s+(\\w+)|(?m)^const\\s+(\\w+)\\s*="),
            "rust", Pattern.compile("(?m)^\\s*pub\\s+(?:async\\s+)?(?:fn|struct|enum|trait|mod)\\s+(\\w+)"));
    private static final Map<String, String> SYMBOL_EXT = Map.of(
            "python", ".py", "java", ".java", "javascript", ".js", "rust", ".rs");

    private static String symbols(Path base, String srcRootRel, String lang) {
        if (srcRootRel == null || lang == null) return "";
        Pattern pat = SYMBOL_PATTERNS.get(lang);
        String ext = SYMBOL_EXT.get(lang);
        if (pat == null || ext == null) return "";
        Path sr = base.resolve(srcRootRel);
        if (!Files.isDirectory(sr)) return "";
        // Caps tuned for the 16K-ctx tier (prompt-weight audit): this block is re-pinned every turn.
        List<Path> files = TreeWalk.files(sr, IGNORE).stream()
                .filter(p -> p.getFileName().toString().endsWith(ext))
                .sorted()
                .limit(10)
                .toList();
        StringBuilder sb = new StringBuilder();
        for (Path f : files) {
            List<String> syms = extractSymbols(f, pat);
            if (!syms.isEmpty()) {
                sb.append("\n - ").append(base.relativize(f)).append(": ").append(String.join(", ", syms));
            }
        }
        return sb.isEmpty() ? "" : "\nEXISTING SYMBOLS (import/reuse these; do not redefine or duplicate):" + sb;
    }

    private static List<String> extractSymbols(Path f, Pattern pat) {
        String src;
        try {
            src = Files.readString(f);
        } catch (IOException e) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher m = pat.matcher(src);
        while (m.find() && out.size() < 8) {
            for (int g = 1; g <= m.groupCount(); g++) {
                String s = m.group(g);
                if (s != null && !s.isBlank() && !s.equals("__init__")) {
                    out.add(s);
                    break;
                }
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Derived per-BUILD-SYSTEM iteration economics (general — applies to any project of that stack, on or
     * off the canon): tell the model the CHEAP compile-feedback command for tight iteration and the FULL
     * verify command for when compilation is clean. A small model otherwise reaches for the expensive
     * habit it memorized (e.g. CI-style cold-start full builds ~90s/cycle, measured eating half a run's
     * wall-clock). Positive phrasing only; one line, only the detected stack's row is shown.
     */
    private static String iterateHint(String build) {
        if (build == null) return null;
        // The workflow's last step is WRITING tests, then running them — phrasing the tail as just
        // "run <test task>" scripted a false-green exit: `gradle test` with zero test sources passes
        // vacuously, and the model read that as verification and declared done (battery11 n1/n2).
        return switch (build) {
            case "gradle" -> "iterate fast with `gradle classes` (compile only; the gradle daemon makes "
                    + "repeat builds fast) and test just the class you're working on with `gradle test "
                    + "--tests <ClassName>`; run the full `gradle test` one final time before task_done";
            case "maven" -> "iterate fast with `mvn -q compile` and test one class with `mvn test "
                    + "-Dtest=<ClassName>`; run the full `mvn test` one final time before task_done";
            case "cargo" -> "iterate fast with `cargo check` (no codegen) and test one item with "
                    + "`cargo test <name>`; run the full `cargo test` one final time before task_done";
            case "npm" -> "iterate fast with `node --check <file>` (or `npx tsc --noEmit` for TS); once "
                    + "syntax is clean, write your tests and run `npm test`";
            case "pip" -> "iterate fast with `python3 -m pyflakes .` (catches undefined names) and test one "
                    + "file with `pytest tests/<file> -x`; run the full `pytest` one final time before task_done";
            default -> null; // godot etc. — no cheap compile loop
        };
    }

    /**
     * A whole-project COMPILE check command (ground truth, not a quality oracle) for green-gated step
     * advancement, or null when the language has no cheap compile step (then advance on the model's word).
     * Compile-only on purpose — it answers "does it build", never "is it good" (that stays code-read).
     */
    public static String buildCheckCommand(Path root) {
        String build = detectBuild(root.toAbsolutePath().normalize());
        if (build == null) return null;
        return switch (build) {
            case "cargo" -> "cargo build 2>&1";
            case "gradle" -> "(./gradlew -q classes 2>&1 || gradle -q classes 2>&1)";
            case "maven" -> "mvn -q -o compile 2>&1";
            case "npm" -> "npx --no-install tsc --noEmit 2>&1"; // only meaningful for TS projects
            // py_compile only checks SYNTAX — a NameError / missing import (e.g. using FastAPI without
            // importing it) passes it, so the gate goes falsely GREEN while the app can't even import. Add
            // pyflakes (static, no execution, no side effects) to catch UNDEFINED NAMES — that's a real
            // build failure. Degrades gracefully if pyflakes isn't installed (its error lacks "undefined
            // name", so the grep is empty and we pass on py_compile alone). Prefer the venv interpreter.
            case "pip" -> "PY=$( [ -x .venv/bin/python ] && echo .venv/bin/python || echo python3 ); "
                    + "$PY -m py_compile $(find . -name '*.py' -not -path './.venv/*' -not -path './build/*' "
                    + "2>/dev/null) 2>&1 || exit 1; "
                    // pyflakes via SYSTEM python3 (pure static AST — needs no project deps); catches the
                    // undefined-name / missing-import that py_compile passes. Graceful if pyflakes absent.
                    + "und=$(python3 -m pyflakes . 2>&1 | grep 'undefined name'); "
                    + "[ -n \"$und\" ] && { echo \"$und\"; exit 1; } || exit 0";
            default -> null; // godot etc. — no cheap compile check; advance on the model's word
        };
    }

    /**
     * Command to RUN the project's own test suite (definition-of-done evidence, not a quality oracle):
     * the field-standard end-to-end check (smallcode/little-coder run the real runner and gate on green).
     * Returns null when the stack has no standard runner (e.g. godot) → the done-gate skips the test check.
     * Runs the project's OWN tests — so it verifies wiring/behavior the model itself asserted, never a
     * bespoke harness oracle.
     */
    public static String testCommand(Path root) {
        String build = detectBuild(root.toAbsolutePath().normalize());
        if (build == null) return null;
        return switch (build) {
            case "cargo" -> "cargo test 2>&1";
            // --rerun-tasks: gradle caches a passing test task as UP-TO-DATE and emits no result on re-run,
            // which reads as "no tests" — force it to actually execute. Then dump the JUnit XML testsuite
            // line(s): gradle's console output is silent on success (no "N passed"), so the XML
            // (tests=/failures=/errors=) is the reliable pass/fail signal the classifier reads.
            case "gradle" -> "(./gradlew test --rerun-tasks 2>&1 || gradle test --rerun-tasks 2>&1); "
                    + "grep -ho '<testsuite[^>]*>' build/test-results/test/*.xml 2>/dev/null";
            case "maven" -> "mvn -q -o test 2>&1";
            case "npm" -> "npm test 2>&1"; // the package.json test script
            // Four candidates, not two. A venv puts its interpreter in Scripts/ on Windows and bin/
            // everywhere else, and `python3` does not exist on Windows at all — only `python`. With
            // only the two POSIX spellings the oracle could never run pytest there: every branch
            // failed, and the run reported a GREEN suite as testsPassed=0 with status=failed.
            case "pip" -> "{ .venv/bin/python -m pytest -q 2>&1 || .venv/Scripts/python.exe -m pytest -q 2>&1"
                    + " || python3 -m pytest -q 2>&1 || python -m pytest -q 2>&1; }";
            // godot CAN be tested headlessly: a standalone `extends SceneTree` script with an `_init()`
            // full of assert()s runs via `godot --headless -s <file>` and the asserts fire in the editor
            // build. (GUT-style tests need the addon, which the model rarely installs — so we target the
            // runnable SceneTree pattern, the one the 9B actually produces.) Two gotchas drive the recipe:
            // (1) a CLEAN run still prints a benign "ERROR: N resources still in use at exit", so we never
            // fail on that; (2) a FAILED assert() triggers a debugger break that HANGS headless (it never
            // reaches quit()), so a bare wait would block — each run is `timeout`-bounded and a timeout (124)
            // counts as RED. So: PASS = clean exit within the timeout; RED = nonzero/timeout OR a real failure
            // marker (assertion failed / script error / parse error). No SceneTree test → exit 0 (don't block).
            case "godot" ->
                    "T=$(grep -rIl 'extends SceneTree' --include=*.gd . 2>/dev/null | grep -v '/addons/' | sort -u); "
                    + "[ -z \"$T\" ] && exit 0; rc=0; "
                    + "for f in $T; do echo \"== $f ==\"; o=$(timeout 60 godot --headless -s \"$f\" 2>&1); ec=$?; echo \"$o\"; "
                    + "{ [ $ec -ne 0 ] || printf '%s' \"$o\" | grep -qiE 'assertion failed|script error|parse error'; } && rc=1; done; "
                    + "exit $rc";
            default -> null;
        };
    }

    private static String detectBuild(Path base) {
        if (exists(base, "build.gradle") || exists(base, "build.gradle.kts")) return "gradle";
        if (exists(base, "pom.xml")) return "maven";
        if (exists(base, "package.json")) return "npm";
        if (exists(base, "Cargo.toml")) return "cargo";
        if (exists(base, "requirements.txt") || exists(base, "pyproject.toml") || exists(base, "setup.py")) return "pip";
        if (exists(base, "project.godot")) return "godot";
        // A python suite with no manifest is still a python suite. A task-scoped workspace routinely
        // looks like `mod.py` + `test_mod.py` and nothing else, and requiring requirements.txt meant
        // testCommand() returned null there — so a genuinely green suite reported as "no tests ran",
        // which understates the one thing a caller most wants to know. Reported from the field.
        if (hasPythonTests(base)) return "pip";
        return null;
    }

    /** A pytest-discoverable test file near the root: {@code test_*.py} or {@code *_test.py}. */
    private static boolean hasPythonTests(Path base) {
        try (Stream<Path> w = Files.walk(base, 3)) {
            return w.filter(Files::isRegularFile).anyMatch(p -> {
                String n = p.getFileName().toString();
                if (!n.endsWith(".py")) return false;
                for (Path seg : p) {
                    String s = seg.toString();
                    if (s.equals(".venv") || s.equals("venv") || s.equals("node_modules")
                            || s.equals(".git") || s.equals("__pycache__")) return false;
                }
                return n.startsWith("test_") || n.endsWith("_test.py");
            });
        } catch (Exception e) {
            return false;
        }
    }

    private static String detectLanguage(Path base, String build) {
        // dominant source extension, with build as a tiebreaker
        int java = 0, py = 0, js = 0, rs = 0, gd = 0;
        // a vote over the first few thousand files is as good as one over all of them, and a home directory has millions
        for (Path p : TreeWalk.files(base, IGNORE, 5_000)) {
            String n = p.getFileName().toString();
            if (n.endsWith(".java")) java++;
            else if (n.endsWith(".py")) py++;
            else if (n.endsWith(".js") || n.endsWith(".mjs") || n.endsWith(".cjs")) js++;
            else if (n.endsWith(".rs")) rs++;
            else if (n.endsWith(".gd")) gd++;
        }
        int max = Math.max(java, Math.max(py, Math.max(js, Math.max(rs, gd))));
        if (max > 0) {
            if (max == java) return "java";
            if (max == py) return "python";
            if (max == js) return "javascript";
            if (max == rs) return "rust";
            return "gdscript";
        }
        if ("gradle".equals(build) || "maven".equals(build)) return "java";
        if ("pip".equals(build)) return "python";
        if ("npm".equals(build)) return "javascript";
        if ("cargo".equals(build)) return "rust";
        if ("godot".equals(build)) return "gdscript";
        return null;
    }

    private static String sourceRoot(Path base, String build) {
        if ("gradle".equals(build) || "maven".equals(build)) {
            if (isDir(base, "src/main/java")) return "src/main/java";
            if (isDir(base, "src/main/kotlin")) return "src/main/kotlin";
        }
        if ("pip".equals(build)) {
            if (isDir(base, "app")) return "app";
            if (isDir(base, "src")) return "src";
        }
        if ("npm".equals(build) || "cargo".equals(build)) {
            if (isDir(base, "src")) return "src";
        }
        // generic fallbacks
        for (String c : List.of("src", "app", "lib")) if (isDir(base, c)) return c;
        return null;
    }

    private static String detectTest(Path base, String build) {
        String manifests = readAny(base, "build.gradle", "build.gradle.kts", "pom.xml", "package.json",
                "requirements.txt", "pyproject.toml", "Cargo.toml");
        if ("pip".equals(build) && manifests.contains("pytest")) return "pytest (tests/)";
        if (("gradle".equals(build) || "maven".equals(build))
                && (manifests.contains("junit") || isDir(base, "src/test"))) return "junit (src/test)";
        if ("npm".equals(build)) {
            if (manifests.contains("vitest")) return "vitest";
            if (manifests.contains("jest")) return "jest";
            if (manifests.contains("node --test") || manifests.contains("node:test")) return "node:test (test/)";
        }
        if ("cargo".equals(build)) return "cargo test";
        return null;
    }

    /** Immediate modules/files under the source root, descending java package nesting first. */
    private static List<String> inventory(Path srcRoot) {
        if (!Files.isDirectory(srcRoot)) return List.of();
        Path cur = srcRoot;
        for (int i = 0; i < 12; i++) {
            List<Path> kids = children(cur);
            List<Path> dirs = kids.stream().filter(Files::isDirectory).toList();
            long files = kids.stream().filter(Files::isRegularFile).count();
            if (dirs.size() == 1 && files == 0) cur = dirs.get(0); // descend com/library/api
            else break;
        }
        List<String> out = new ArrayList<>();
        for (Path k : children(cur)) {
            out.add(Files.isDirectory(k) ? k.getFileName() + "/" : k.getFileName().toString());
        }
        Collections.sort(out);
        return out.size() > 24 ? out.subList(0, 24) : out;
    }

    private static List<Path> children(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> !IGNORE.contains(p.getFileName().toString())).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean exists(Path base, String rel) {
        return Files.isRegularFile(base.resolve(rel));
    }

    private static boolean isDir(Path base, String rel) {
        return Files.isDirectory(base.resolve(rel));
    }

    private static boolean notIgnored(Path p) {
        for (Path part : p) if (IGNORE.contains(part.toString())) return false;
        return true;
    }

    private static String readAny(Path base, String... names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            Path p = base.resolve(n);
            if (Files.isRegularFile(p)) {
                try {
                    sb.append(Files.readString(p)).append('\n');
                } catch (IOException ignored) {
                    // skip
                }
            }
        }
        return sb.toString();
    }
}
