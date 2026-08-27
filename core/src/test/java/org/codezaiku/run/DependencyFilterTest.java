package org.codezaiku.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `files[]` is what a host syncs on. A request for ONE JavaScript file returned 4,324 paths, 4,320 of
 * them `node_modules`, because the model ran `npm install` in a workspace with no `.gitignore` — a
 * host acting on that list would copy an entire dependency tree.
 *
 * The reason there is no general filter list is still right: git's ignore rules are the project's own
 * statement about what is noise. This is narrower, and it answers that objection with the `??` check —
 * a TRACKED file is never held back, however it is named, and an IGNORED one never arrives at all. It
 * fires only where the project has said nothing and a tool put the file there.
 */
class DependencyFilterTest {

    private static boolean filtered(String path, String status) throws Exception {
        Method m = WorkspaceFiles.class.getDeclaredMethod("installedDependency", String.class, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, path, status);
    }

    @Test
    void anInstalledDependencyTreeIsHeldBack() throws Exception {
        assertTrue(filtered("node_modules/@babel/core/lib/config/caching.js", "??"));
        assertTrue(filtered(".venv/lib/python3.13/site-packages/pytest/__init__.py", "??"));
        assertTrue(filtered("__pycache__/mod.cpython-313.pyc", "??"));
        assertTrue(filtered("services/api/node_modules/left-pad/index.js", "??"),
                "a nested install counts too — the segment can appear anywhere");
    }

    @Test
    void aTrackedFileIsNeverHeldBackHoweverItIsNamed() throws Exception {
        assertFalse(filtered("node_modules/left-pad/index.js", " M"),
                "a project that COMMITTED its dependencies has said they matter; that is the whole "
                        + "objection to a filter list, and tracking is the answer to it");
        assertFalse(filtered("vendor/github.com/pkg/errors/errors.go", "A "),
                "a vendored dependency staged by the run is deliberate");
        assertFalse(filtered("build/generated/Version.java", "M "));
    }

    @Test
    void theRunsOwnWorkIsNeverHeldBack() throws Exception {
        assertFalse(filtered("wiki_briefing.js", "??"));
        assertFalse(filtered("src/main.py", "??"));
    }

    @TempDir Path repo;

    private void git(String... args) throws Exception {
        var cmd = new ArrayList<String>(List.of("git"));
        cmd.addAll(List.of(args));
        var p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor();
    }

    /**
     * The predicate above is only half of it — this drives the real delta, because the bug shipped in
     * the wiring, not in the rule. Depending on the model to run `npm install` would make the check
     * nondeterministic; creating the tree does not.
     */
    @Test
    void theDeltaHoldsBackAnInstallAndSaysHowMany() throws Exception {
        git("init", "-q");
        git("config", "user.email", "t@example.com");
        git("config", "user.name", "t");
        Files.writeString(repo.resolve("README.md"), "x\n");
        git("add", "-A");
        git("commit", "-qm", "base");

        var before = WorkspaceFiles.snapshot(repo);

        Files.writeString(repo.resolve("wiki_briefing.js"), "function briefing(t){return t;}\n");
        Path nm = Files.createDirectories(repo.resolve("node_modules/left-pad"));
        Files.writeString(nm.resolve("index.js"), "module.exports=1\n");
        Files.writeString(nm.resolve("package.json"), "{}\n");

        List<String> changed = WorkspaceFiles.changedSince(repo, before);

        assertTrue(changed.contains("wiki_briefing.js"), "the run's own work must survive: " + changed);
        assertTrue(changed.stream().noneMatch(f -> f.startsWith("node_modules/")),
                "an install must not reach a host that syncs on this list: " + changed);
        assertEquals(2, WorkspaceFiles.lastExcludedCount(),
                "and the caller must be TOLD the list was pruned, not handed a short one that looks whole");
    }

    @Test
    void aSegmentIsMatchedWholeNotAsASubstring() throws Exception {
        assertFalse(filtered("build.py", "??"), "a file named build.py is not a build directory");
        assertFalse(filtered("src/target_selection.py", "??"));
        assertFalse(filtered("distributed_queue.py", "??"));
        assertFalse(filtered("my_node_modules_helper.js", "??"));
    }
}
