package org.codezaiku.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Fast, dependency-free syntax check run right after a write/edit, fed back IN the tool result
 * (the opencode LSP-in-edit pattern) so the loop self-corrects on the next turn instead of
 * discovering the error later at the gate. Honest feedback, not an interceptor — the write still
 * happened; we just report what's wrong.
 *
 * <p>Python ({@code py_compile}) and JS ({@code node --check}) only: these need no project deps.
 * Java/Rust/etc. need the build to compile, which the behavior-door gate already runs.
 */
public final class SyntaxCheck {
    private SyntaxCheck() {
    }

    /** Returns "" if clean/unsupported/unavailable, else a cause-first error block to append. */
    public static String check(PathScope scope, String relPath) {
        if (ContainerExec.active()) return "";   // container-exec mode: host-side compile check is meaningless
        String lc = relPath.toLowerCase();
        Path root = scope.root();
        Path file;
        try {
            file = scope.resolve(relPath);
        } catch (RuntimeException e) {
            return "";
        }
        if (!Files.isRegularFile(file)) return "";

        List<String> cmd;
        if (lc.endsWith(".py")) {
            Path venvPy = root.resolve(".venv/bin/python");
            String py = Files.isExecutable(venvPy) ? venvPy.toString() : "python3";
            cmd = List.of(py, "-m", "py_compile", file.toString());
        } else if (lc.endsWith(".js") || lc.endsWith(".cjs") || lc.endsWith(".mjs")) {
            cmd = List.of("node", "--check", file.toString());
        } else {
            return ""; // the gate's real build covers Java/Rust/etc.
        }

        try {
            Process p = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true).start();
            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes());
            }
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            if (p.exitValue() == 0) return "";
            String err = out.strip();
            if (err.length() > 1500) err = err.substring(err.length() - 1500);
            return "\n⚠ SYNTAX ERROR — fix this before continuing:\n" + err;
        } catch (Exception e) {
            return ""; // checker not installed → silently skip; the gate still catches it
        }
    }
}
