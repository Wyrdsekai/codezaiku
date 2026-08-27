package org.codezaiku.loop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The Create / Maintain / Repair task-shape triad (SPEC_CODEZAIKU_AS_FAMILIAR §6). Distinct optimal
 * behaviors: Create explores (new code, higher latitude), Maintain preserves working behavior with narrow
 * edits, Repair reproduces-then-minimally-patches with higher verification pressure. Shaping the loop's
 * guidance per mode is small-model leverage.
 *
 * <p>Detection (§6.3) uses PROJECT STATE as the primary signal and the goal's verb as a tie-breaker; an
 * explicit override (bondholder/agent declaration) always wins. The resolved mode + its evidence are
 * surfaced so the caller can correct.
 */
public enum CodeMode {
    CREATE, MAINTAIN, REPAIR, ARTIFACT;

    private static final Set<String> SOURCE_EXTS = Set.of(
            "py", "java", "kt", "rs", "go", "ts", "js", "tsx", "jsx", "c", "cc", "cpp", "h", "hpp",
            "rb", "cs", "swift", "scala", "php");

    /** Resolve the mode: honor {@code override} (create|maintain|repair; "auto"/null ⇒ detect), else §6.3. */
    public static CodeMode resolve(Path root, String goal, String override) {
        if (override != null && !override.isBlank() && !"auto".equalsIgnoreCase(override.trim())) {
            try { return CodeMode.valueOf(override.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { /* fall through to detection */ }
        }
        // §6.3 strong signal: no project / empty tree → Create (nothing to inspect; sketching).
        if (!hasSource(root)) return CREATE;
        String g = goal == null ? "" : goal.toLowerCase(Locale.ROOT);
        // Repair posture: the goal names a failure/breakage, or carries an error/traceback.
        if (g.matches("(?s).*\\b(fix|crash(es|ed)?|broken|bug|traceback|stack ?trace|regression|"
                + "fails?|failing|exception|error|500|does ?n.t work|incident)\\b.*")) return REPAIR;
        // Existing project + everything else (add/extend/refactor/update on a working tree) → Maintain.
        return MAINTAIN;
    }

    /** Short human evidence for WHY this mode was chosen (surfaced in the first line, per §6.3). */
    public static String evidence(Path root, String goal, CodeMode mode) {
        return switch (mode) {
            case CREATE -> "empty/greenfield project — nothing to inspect yet";
            case REPAIR -> "goal names a failure/breakage (repair posture: reproduce, then minimal patch)";
            case MAINTAIN -> "existing project, working tree — preserve behavior, narrow edits";
            case ARTIFACT -> "single deliverable requested — write it, then stop";
        };
    }

    /** The mode-shaping guidance prepended to the task (§6.2 behaviors). Kept terse — small-model attention. */
    public String preamble() {
        return switch (this) {
            case CREATE -> "MODE: CREATE — you are building something new in a greenfield project. Explore and "
                    + "sketch freely; get a working end-to-end skeleton first, then flesh it out. Verify it runs.";
            case MAINTAIN -> "MODE: MAINTAIN — you are updating EXISTING, WORKING code. PRESERVE existing behavior. "
                    + "Make the NARROWEST edits that achieve the goal; do not rewrite or restructure beyond what "
                    + "is asked. Verify the project's existing tests still pass after your change.";
            case REPAIR -> "MODE: REPAIR — something is BROKEN. First REPRODUCE the failure and identify its exact "
                    + "cause from the real error, THEN apply the MINIMAL patch that fixes it — do not rewrite "
                    + "working code around it. Verify the failure is gone and nothing else broke.";
            // Never inferred, only asked for: a caller that knows the task is one deliverable says so.
            // Guessing it from the goal text would eventually silence the test discipline on a task that
            // needed it, which is the expensive direction to be wrong in.
            case ARTIFACT -> "MODE: ARTIFACT — the deliverable is the FILE you were asked for, nothing more. "
                    + "Write it, read it back to confirm it is complete and correct, then call task_done. "
                    + "Do NOT create a test suite, a package layout, or supporting modules that were not "
                    + "asked for, and do not keep looking for more to do once the file is written.";
        };
    }

    private static boolean hasSource(Path root) {
        if (root == null || !Files.isDirectory(root)) return false;
        try (Stream<Path> w = Files.walk(root, 10)) {
            return w.filter(Files::isRegularFile).anyMatch(p -> {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.contains(".venv/") || rel.contains("node_modules/") || rel.contains("/target/")
                        || rel.contains("/build/") || rel.contains(".git/") || rel.contains("__pycache__/")) return false;
                if (rel.contains("test")) return false;
                String name = p.getFileName().toString();
                int dot = name.lastIndexOf('.');
                if (dot < 0) return false;
                if (!SOURCE_EXTS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) return false;
                try { return Files.size(p) >= 10; } catch (Exception e) { return false; } // skip empty stubs only
            });
        } catch (Exception e) {
            return true; // unreadable → assume existing (Maintain), safer than Create
        }
    }
}
