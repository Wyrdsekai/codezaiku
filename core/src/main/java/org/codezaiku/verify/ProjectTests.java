package org.codezaiku.verify;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.codezaiku.shape.ProjectFacts;
import org.codezaiku.exec.Shell;

/**
 * The drive gate's cheap, model-blind "are the project's own tests GREEN right now?" check: resolve the
 * real (split-brain-safe) work dir + the stack's test command from {@link org.codezaiku.shape.ProjectFacts}
 * and run it. No test runner (e.g. godot) or any harness trouble → returns true so a plan item is never
 * blocked on something we can't measure. (Extracted from the deleted mutation gate, which is gone — the
 * 9B cannot satisfy an in-loop mutation gate and the held-out grader is the measurement of record.)
 */
public final class ProjectTests {

    private static final int RUN_BUDGET_MS = 180_000;

    private ProjectTests() {}

    /**
     * True if the project's own tests pass right now (or there's no runner / we can't measure).
     *
     * <p>Note the permissive default: unmeasurable reads as green. That is correct for the in-loop use
     * — never block a plan item on something we cannot measure — and WRONG as a report to a caller,
     * because "no tests exist" would become "tests passed". Anything reporting outward wants
     * {@link #verdict}, which keeps those two apart.
     */
    public static boolean testsGreen(Path projectRoot) {
        Verdict v = verdict(projectRoot);
        return !v.ran() || v.passed();
    }

    /**
     * What the project's test suite actually did, for a caller that reports it.
     *
     * <p>Distinguishes the three outcomes {@link #testsGreen} deliberately collapses: a suite that ran
     * and passed, one that ran and failed, and no measurement at all. A host deciding an outcome from
     * test results needs "we did not look" to be distinct from "nothing failed".
     *
     * @param ran     a real runner executed. False when the stack has no test command, or the attempt
     *                itself failed — never "there were no tests to run".
     * @param passed  meaningful only when {@code ran}.
     * @param passedCount / {@code failedCount} null when the runner's output could not be parsed. Null
     *                is NOT zero: reporting zero for an unparsed count would understate a green suite,
     *                which is exactly the failure this method exists to fix.
     */
    public record Verdict(boolean ran, boolean passed, Integer passedCount, Integer failedCount) {
        public static Verdict notRun() {
            return new Verdict(false, false, null, null);
        }

        /** True when we know how many tests ran, as opposed to only whether the suite was green. */
        public boolean counted() {
            return passedCount != null || failedCount != null;
        }
    }

    /** Run the project's tests and report what happened, counts included where the runner says so. */
    public static Verdict verdict(Path projectRoot) {
        try {
            Path work = findWorkDir(projectRoot);
            String testCmd = ProjectFacts.testCommand(work);
            if (testCmd == null) return Verdict.notRun();
            // OS-level timeout on the command itself — NOT just the Java waitFor below. A model test can
            // hang forever (e.g. `client = TestClient(app)` at module level when the app's startup blocks;
            // an accidental `while True`; a real socket op), and sh()'s readAllBytes() blocks on the open
            // stdout pipe BEFORE waitFor() is ever reached, so the Java-side timeout can never fire. `timeout`
            // guarantees the subtree dies (124 on expiry → not green), so a hung test can't stall the run
            // indefinitely (battery37 py-n2: a module-level TestClient hung the post-task_done verify ~40min).
            //
            // Output is CAPTURED rather than discarded, because the counts are in it.
            // Not a bare `timeout`: macOS ships neither it nor gtimeout, so the wrapper failed with
            // 127 and the caller read that as the SUITE failing — every project on macOS reporting
            // red whether or not it passed. Timeout picks a mechanism that exists on this host.
            int secs = RUN_BUDGET_MS / 1000;
            Sh r = sh(work.toString(), Timeout.wrap(secs, testCmd + " 2>&1"));
            if (r.exit == -1) return Verdict.notRun();          // we never got to run it
            // A suite that collected NOTHING did not fail — it did not run. pytest exits 5 for "no
            // tests collected", which a plain non-zero check reads as a failing suite, so a project
            // with a manifest and no tests reported `failed` and a host deciding outcomes from test
            // results would mark the task failed for having no tests. Measured, not theorised.
            if (noTestsCollected(r.exit, r.out)) return Verdict.notRun();
            // Nor is an ABSENT RUNNER a failing suite. A stock macOS box has no pytest, so without
            // this every python project there reported red — and the same holds for any Linux box
            // where the runner was never installed. Measured on macOS 26.5.
            if (runnerMissing(r.exit, r.out)) return Verdict.notRun();
            boolean green = r.exit == 0;
            int[] counts = TestCounts.parse(r.out);
            if (counts == null) counts = TestCounts.fromJUnitXml(work);
            Integer passed = counts == null ? null : counts[0];
            Integer failed = counts == null ? null : counts[1];
            return new Verdict(true, green, passed, failed);
        } catch (Exception e) {
            return Verdict.notRun();
        }
    }

    /**
     * True when the runner found no tests to run, as opposed to running some and failing.
     *
     * <p>{@code 5} is pytest's "no tests collected". The text checks cover the same condition for
     * runners that exit non-zero without a dedicated code, and are matched case-insensitively because
     * they appear in a summary line whose casing varies by version.
     */
    static boolean noTestsCollected(int exit, String out) {
        if (exit == 5) return true;
        // Same reasoning as runnerMissing: a zero exit means a runner ran this suite to completion,
        // so a phrase from an earlier failed fallback must not turn it into "nothing was measured".
        if (exit == 0) return false;
        if (out == null) return false;
        String o = out.toLowerCase(Locale.ROOT);
        return o.contains("no tests ran")
                || o.contains("collected 0 items")
                || o.contains("no tests found")
                || o.contains("no tests were found");
    }

    /**
     * True when the test RUNNER itself could not be found, as opposed to tests failing.
     *
     * <p>Deliberately narrow. Broad markers like "no such file or directory" also appear in the output
     * of tests that genuinely fail while opening a missing fixture, and swallowing those would turn a
     * real red suite into "untested" — the direction that hides a defect rather than merely
     * under-reporting one.
     */
    static boolean runnerMissing(int exit, String out) {
        // A suite that EXITED ZERO ran, whatever the noise above it says. The test command tries
        // several interpreters in a `||` chain, so the ones that do not exist print "command not
        // found" on the way to the one that works — on Windows `python3` always does, right before
        // `python` succeeds. Scanning the whole output without this check classified a GREEN suite
        // as "no runner installed" and reported it as no measurement at all.
        if (exit == 0) return false;
        if (exit == 127) return true;                       // the shell could not find the command
        if (out == null) return false;
        String o = out.toLowerCase(Locale.ROOT);
        return o.contains("no module named pytest")
                || o.contains("no module named unittest")
                || o.contains("command not found")
                || o.contains("is not recognized as an internal or external command");
    }

    private static final List<String> MANIFESTS = List.of(
            "build.gradle", "build.gradle.kts", "pom.xml", "package.json", "Cargo.toml",
            "requirements.txt", "pyproject.toml", "setup.py");

    /** Deepest dir holding a recognized build manifest (so we run in the REAL project, not a split-brain stub). */
    private static Path findWorkDir(Path root) {
        Path best = root;
        int bestDepth = -1;
        try (Stream<Path> w = Files.walk(root, 8)) {
            for (Path p : (Iterable<Path>) w.filter(ProjectTests::notVendor)::iterator) {
                if (!Files.isRegularFile(p)) continue;
                if (!MANIFESTS.contains(p.getFileName().toString())) continue;
                int d = p.getNameCount();
                if (d > bestDepth) { bestDepth = d; best = p.getParent(); }
            }
        } catch (Exception ignored) {
            // unreadable → fall back to root
        }
        return best;
    }

    private static boolean notVendor(Path p) {
        for (Path seg : p) {
            String s = seg.toString();
            if (s.equals(".venv") || s.equals("venv") || s.equals("node_modules") || s.equals(".git")
                    || s.equals("build") || s.equals("dist") || s.equals("target") || s.equals("__pycache__")) return false;
        }
        return true;
    }

    private record Sh(int exit, String out) {}

    private static Sh sh(String cwd, String command) {
        try {
            // The working directory is set on the PROCESS, never with a `cd` inside the command string.
            // On Windows `bash` resolves to C:\Windows\System32\bash.exe — the WSL launcher — whenever
            // WSL is installed, and a Windows-style path does not exist inside WSL. `cd 'C:\Users\...'`
            // then failed with exit 1 and "No such file or directory", which is neither 127 nor "command
            // not found", so runnerMissing() did not fire: the oracle reported ran=true with NO counts,
            // turning a passing suite into testsPassed=0 / status=failed. ProcessBuilder's directory is
            // translated by the launcher, which is why the model's own ShellTool never hit this.
            Process p = Shell.pb("( " + command + " )")
                    .directory(new java.io.File(cwd))
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean done = p.waitFor(RUN_BUDGET_MS + 20_000L, TimeUnit.MILLISECONDS);
            if (!done) { p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroyForcibly(); return new Sh(124, out); }
            return new Sh(p.exitValue(), out);
        } catch (Exception e) {
            return new Sh(-1, "");
        }
    }

    private static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
