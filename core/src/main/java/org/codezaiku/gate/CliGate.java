package org.codezaiku.gate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.codezaiku.exec.Shell;

/**
 * The CLI / process archetype gate (non-HTTP): runs an ordered sequence of real commands — build,
 * test, and actually invoking the binary — and certifies on exit code + stdout/stderr assertions.
 * This is the behavior-door equivalent for a program that has no port to probe: it really compiles
 * and runs the thing. Failures are reported cause-first (the tail of the failing command's output).
 */
public final class CliGate implements Gate {
    private static final Logger log = LoggerFactory.getLogger(CliGate.class);

    private final String name;
    private final List<CliStep> steps;

    public CliGate(String name, List<CliStep> steps) {
        this.name = name;
        this.steps = steps;
    }

    @Override
    public GateResult certify(Path projectRoot) {
        StringBuilder ev = new StringBuilder();
        int passed = 0;
        for (CliStep s : steps) {
            StepRun r = run(projectRoot, s);
            ev.append(r.line).append('\n');
            if (r.pass) {
                passed++;
            } else if (!r.cause.isBlank()) {
                ev.append("        ↳ cause:\n").append(indent(r.cause)).append('\n');
            }
        }
        boolean pass = passed == steps.size();
        String head = "CLI GATE: " + (pass ? "PASS" : "FAIL") + " " + passed + "/" + steps.size()
                + " (" + name + ")\n";
        return new GateResult(pass, passed, steps.size(), head + ev);
    }

    private record StepRun(boolean pass, String line, String cause) {
    }

    private StepRun run(Path root, CliStep s) {
        log.info("cli gate[{}] step {}: {}", name, s.id(), s.cmd());
        try {
            Process p = Shell.pb(s.cmd())
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes());
            }
            int timeout = s.timeoutSec() > 0 ? s.timeoutSec() : 300;
            if (!p.waitFor(timeout, TimeUnit.SECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                return new StepRun(false, "  [✗] " + s.id() + " → TIMED OUT after " + timeout + "s", tail(out));
            }
            int exit = p.exitValue();
            // STUB-PROOF: derive ground-truth value(s) at gate-time the model couldn't hardcode, and
            // require the app's real output to contain EVERY one. Defeats hardcoded/empty/reverted stubs;
            // a list = "real means all N concerns" (each an exact value any correct impl must show).
            List<String> found = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String cmd : s.deriveCommands()) {
                String v = runCapture(root, cmd);
                if (v == null || v.isBlank()) continue; // derive produced nothing — can't assert, skip
                if (out.contains(v)) found.add(v); else missing.add(v + " (`" + cmd + "`)");
            }
            boolean exitOk = s.expectExit() == null || s.expectExit().isEmpty() || s.expectExit().contains(exit);
            boolean containsOk = s.contains() == null || out.contains(s.contains());
            boolean notContainsOk = s.notContains() == null || !out.contains(s.notContains());
            boolean derivedOk = missing.isEmpty();
            boolean ok = exitOk && containsOk && notContainsOk && derivedOk;
            String detail;
            if (ok) detail = "OK (exit " + exit + (found.isEmpty()
                    ? "" : ", shows real " + found) + ")";
            else if (!exitOk) detail = "FAIL exit " + exit + " (expected " + s.expectExit() + ")";
            else if (!containsOk) detail = "FAIL output missing \"" + s.contains() + "\"";
            else if (!notContainsOk) detail = "FAIL output contained forbidden \"" + s.notContains() + "\"";
            else detail = "FAIL output missing REAL ground-truth " + missing
                    + (found.isEmpty() ? "" : " (found " + found + ")")
                    + " — those concern(s) look hardcoded/stubbed/absent, not real";
            return new StepRun(ok, "  [" + (ok ? "✓" : "✗") + "] " + s.id() + " → " + detail, ok ? "" : tail(out));
        } catch (Exception e) {
            return new StepRun(false, "  [✗] " + s.id() + " → ERROR: " + e.getMessage(), "");
        }
    }

    /** Run a derive command at gate-time and return its first non-blank output line (the ground truth). */
    private String runCapture(Path root, String cmd) {
        try {
            Process p = Shell.pb(cmd)
                    .directory(root.toFile()).redirectErrorStream(true).start();
            String out;
            try (var in = p.getInputStream()) { out = new String(in.readAllBytes()); }
            p.waitFor(30, TimeUnit.SECONDS);
            for (String line : out.split("\\R")) {
                String t = line.strip();
                if (!t.isEmpty()) return t;
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String tail(String s) {
        s = s.strip();
        return s.length() <= 1600 ? s : s.substring(s.length() - 1600);
    }

    private static String indent(String s) {
        StringBuilder b = new StringBuilder();
        for (String line : s.split("\n")) b.append("          ").append(line).append('\n');
        return b.toString().stripTrailing();
    }
}
