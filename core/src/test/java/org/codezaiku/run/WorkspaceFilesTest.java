package org.codezaiku.run;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The file list is load-bearing for a host that syncs on it, and it can be wrong in two opposite
 * directions. These pin both: attributing a developer's pre-existing edits to the agent, and losing
 * files because porcelain paths are repo-root-relative rather than workspace-relative.
 */
class WorkspaceFilesTest {

    @TempDir Path repo;

    private void git(String... args) throws Exception {
        var cmd = new ArrayList<String>(List.of("git"));
        cmd.addAll(List.of(args));
        var pb = new ProcessBuilder(cmd).directory(repo.toFile());
        pb.redirectErrorStream(true);
        var p = pb.start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), "git " + String.join(" ", args) + " failed");
    }

    @BeforeEach void initRepo() throws Exception {
        git("init", "-q");
        git("config", "user.email", "t@example.com");
        git("config", "user.name", "t");
        Files.writeString(repo.resolve("tracked.txt"), "original\n");
        git("add", "-A");
        git("commit", "-qm", "base");
    }

    @Test void reportsAFileTheRunCreated() throws Exception {
        var before = WorkspaceFiles.snapshot(repo);
        Files.writeString(repo.resolve("new.py"), "x = 1\n");
        assertEquals(List.of("new.py"), WorkspaceFiles.changedSince(repo, before));
    }

    @Test void reportsAFileTheRunModified() throws Exception {
        var before = WorkspaceFiles.snapshot(repo);
        Files.writeString(repo.resolve("tracked.txt"), "changed\n");
        assertEquals(List.of("tracked.txt"), WorkspaceFiles.changedSince(repo, before));
    }

    /**
     * THE ONE THAT MATTERS: a file already dirty when the run started is the developer's work, not
     * the agent's. A single end-of-run `git status` would report it and the host would sync it.
     */
    @Test void doesNotAttributePreExistingChangesToTheRun() throws Exception {
        Files.writeString(repo.resolve("tracked.txt"), "developer edited this before the run\n");
        Files.writeString(repo.resolve("untracked-scratch.txt"), "also pre-existing\n");

        var before = WorkspaceFiles.snapshot(repo);          // run starts HERE
        Files.writeString(repo.resolve("agent.py"), "written by the run\n");

        List<String> changed = WorkspaceFiles.changedSince(repo, before);
        assertEquals(List.of("agent.py"), changed,
                "only the run's own file may appear, not the pre-existing dirt: " + changed);
    }

    /** A pre-existing dirty file that the run ALSO edits must still be reported. */
    @Test void reportsAPreExistingDirtyFileTheRunEditsFurther() throws Exception {
        Files.writeString(repo.resolve("untracked.txt"), "created before\n");
        git("add", "-A");                                     // now staged: status "A "
        var before = WorkspaceFiles.snapshot(repo);
        Files.writeString(repo.resolve("untracked.txt"), "and modified by the run\n");   // → "AM"

        assertTrue(WorkspaceFiles.changedSince(repo, before).contains("untracked.txt"),
                "a status change from A to AM means the run touched it");
    }

    @Test void reportsAModifiedFileTheRunModifiesAgainAndLeavesTheOtherDirtyFileOut() throws Exception {
        Files.writeString(repo.resolve("other.txt"), "base\n"); git("add", "-A"); git("commit", "-qm", "other");
        Files.writeString(repo.resolve("tracked.txt"), "the developer's own edit\n");      // " M" before the run
        Files.writeString(repo.resolve("other.txt"), "also the developer's\n");            // " M" before the run
        var before = WorkspaceFiles.snapshot(repo);
        Files.writeString(repo.resolve("tracked.txt"), "and the run's edit on top\n");     // still " M"

        assertEquals(List.of("tracked.txt"), WorkspaceFiles.changedSince(repo, before),
                "the status is ' M' on both sides; only the content says which one the run touched");
    }

    @Test void reportsAFileTheRunEditedAndThenCommitted() throws Exception {
        var before = WorkspaceFiles.snapshot(repo);
        Files.writeString(repo.resolve("tracked.txt"), "edited\n");
        Files.writeString(repo.resolve("new.txt"), "new\n");
        git("add", "-A"); git("commit", "-qm", "the run committed its work");               // clean before, clean after

        assertEquals(List.of("new.txt", "tracked.txt"), WorkspaceFiles.changedSince(repo, before));
    }

    @Test void aRepositoryNamedByTheCallersEnvironmentIsNotTheOneRead(@TempDir Path elsewhere) throws Exception {
        // The harness strips GIT_DIR and GIT_WORK_TREE from its own git calls. It cannot set them on this JVM to prove
        // that, so the test asks the runner what environment a git child sees.
        String env = WorkspaceFiles.git(repo, "-c", "alias.e=!env", "e");
        assertTrue(env != null, "the alias ran");
        assertTrue(env.contains("GIT_OPTIONAL_LOCKS=0") && env.contains("LC_ALL=C") && env.contains("GIT_TERMINAL_PROMPT=0"), env);
        assertFalse(env.lines().anyMatch(l -> l.startsWith("GIT_WORK_TREE=") || l.startsWith("GIT_INDEX_FILE=")), "stripped");
    }

    @Test void aGitThatHangsIsCutOffAtTheTimeout() throws Exception {
        // `git -c alias.h='!sleep 60' h` is a git that never answers. Read with readAllBytes first, the timeout never began.
        long t0 = System.nanoTime();
        Thread t = new Thread(() -> WorkspaceFiles.git(repo, "-c", "alias.h=!sleep 60", "h"));
        t.start(); t.join((WorkspaceFiles.GIT_TIMEOUT_SECONDS + 8) * 1000L);
        assertFalse(t.isAlive(), "the call returned by itself");
        assertTrue((System.nanoTime() - t0) / 1_000_000_000L <= WorkspaceFiles.GIT_TIMEOUT_SECONDS + 6);
    }

    /**
     * Git collapses a wholly untracked directory into one `?? pkg/` entry, so the default porcelain
     * output names the DIRECTORY and loses every file under it — the run's actual work, silently
     * missing from the list a host syncs on.
     */
    @Test void listsIndividualFilesInsideANewDirectoryRatherThanTheDirectory() throws Exception {
        var before = WorkspaceFiles.snapshot(repo);
        Files.createDirectories(repo.resolve("newpkg/sub"));
        Files.writeString(repo.resolve("newpkg/__init__.py"), "");
        Files.writeString(repo.resolve("newpkg/sub/thing.py"), "y = 2\n");

        List<String> changed = WorkspaceFiles.changedSince(repo, before);
        assertEquals(List.of("newpkg/__init__.py", "newpkg/sub/thing.py"), changed,
                "expected the files, not the directory: " + changed);
    }

    @Test void reportsADeletion() throws Exception {
        var before = WorkspaceFiles.snapshot(repo);
        Files.delete(repo.resolve("tracked.txt"));
        assertEquals(List.of("tracked.txt"), WorkspaceFiles.changedSince(repo, before));
    }

    /**
     * Porcelain paths are relative to the REPO ROOT. When the workspace is a subdirectory, using them
     * unchanged yields paths that do not resolve against the workspace the host is syncing.
     */
    @Test void rebasesPathsWhenTheWorkspaceIsASubdirectoryOfTheRepo() throws Exception {
        Path sub = Files.createDirectories(repo.resolve("services/api"));
        var before = WorkspaceFiles.snapshot(sub);
        Files.writeString(sub.resolve("handler.py"), "def handle(): pass\n");

        assertEquals(List.of("handler.py"), WorkspaceFiles.changedSince(sub, before),
                "must be relative to the WORKSPACE, not 'services/api/handler.py'");
    }

    /** A change elsewhere in the repo is not this workspace's business and must be dropped. */
    @Test void excludesChangesOutsideTheWorkspaceSubdirectory() throws Exception {
        Path sub = Files.createDirectories(repo.resolve("services/api"));
        var before = WorkspaceFiles.snapshot(sub);
        Files.writeString(repo.resolve("elsewhere.txt"), "not part of this workspace\n");
        Files.writeString(sub.resolve("mine.py"), "x\n");

        assertEquals(List.of("mine.py"), WorkspaceFiles.changedSince(sub, before));
    }

    /** Outside a repo there is nothing to reconcile against — say so rather than inventing a set. */
    @Test void reportsUnavailableOutsideAGitRepo(@TempDir Path plain) {
        var snap = WorkspaceFiles.snapshot(plain);
        assertFalse(snap.available(), "a non-repo must report unavailable, not empty-and-authoritative");
        assertTrue(WorkspaceFiles.changedSince(plain, snap).isEmpty());
    }

    @Test void mergeUnionsTheLedgerWithTheGitDeltaAndSorts() {
        assertEquals(List.of("a.py", "b.py", "c.py"),
                WorkspaceFiles.merge(List.of("c.py", "a.py"), List.of("b.py", "a.py")));
    }
}
