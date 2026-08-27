package org.codezaiku.loop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checkpoints are snapshots of the project's own source that restore-on-regression and
 * keep-best-green copy back OVER the working tree. Round numbers are deterministic per run
 * (1000+turn, 9000+turn, selfVerifyCount), so when every run shared one directory a second run
 * found round-N already present, skipped the snapshot, and was handed the PREVIOUS run's code —
 * which it could then write over the current tree. Observed on a real project directory holding
 * rounds from three separate runs weeks apart.
 */
class CheckpointIsolationTest {

    @TempDir Path tmp;

    @Test
    void twoRunsNeverShareARoundDirectory() {
        Path project = tmp.resolve("myproject");

        Path a = FamiliarLoop.checkpointRoot(project, "20260101-120000-aaa").resolve("round-9003");
        Path b = FamiliarLoop.checkpointRoot(project, "20260220-090000-bbb").resolve("round-9003");

        assertFalse(a.equals(b), "the same round in two runs resolved to one directory — this is the bug");
    }

    @Test
    void checkpointsLiveBesideTheProjectNotInsideIt() {
        Path project = tmp.resolve("myproject");
        Path root = FamiliarLoop.checkpointRoot(project, "20260101-120000-aaa");

        assertFalse(root.startsWith(project),
                "checkpoints inside the project tree confuse boot and app-root detection");
        assertEquals(project.getParent(), root.getParent().getParent());
        assertEquals("myproject.cp-checkpoints", root.getParent().getFileName().toString());
    }

    /** The helper tests above prove the LAYOUT is run-scoped; this proves snapshotCheckpoint
     *  actually uses it, by taking the same round in two loops and reading what lands on disk. */
    @Test
    void twoLoopsSnapshottingTheSameRoundDoNotOverwriteEachOther() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        Files.writeString(project.resolve("app.py"), "VERSION = 1\n");

        Object loopA = newLoop(project);
        String a = snapshot(loopA, 9003);
        assertNotNull(a, "first snapshot failed");

        Files.writeString(project.resolve("app.py"), "VERSION = 2\n");   // the run edits the file
        Object loopB = newLoop(project);
        String b = snapshot(loopB, 9003);                                 // same round, second run
        assertNotNull(b, "second snapshot failed");

        assertFalse(a.equals(b), "the second run reused the first run's directory");
        assertEquals("VERSION = 1", Files.readString(Path.of(a, "app.py")).strip(),
                "run A's snapshot changed under it");
        assertEquals("VERSION = 2", Files.readString(Path.of(b, "app.py")).strip(),
                "run B was handed run A's stale code — this is the bug, and restoring it would "
                        + "write month-old source over the working tree");
    }

    /** The constructor reads the drive's context window, so it needs a real client — but not a
     *  reachable one. Port 1 refuses immediately, which is fast and offline, and the loop falls
     *  back to its default window. Nothing here sends a request. */
    private static Object newLoop(Path project) throws Exception {
        Constructor<?> c = FamiliarLoop.class.getConstructor(
                org.codezaiku.drive.DriveClient.class, org.codezaiku.tools.ToolRegistry.class,
                Path.class, String.class, int.class, org.codezaiku.library.Library.class);
        var drive = new org.codezaiku.drive.DriveClient("http://127.0.0.1:1", "test-model");
        return c.newInstance(drive, null, project, "goal", 1, null);
    }

    private static String snapshot(Object loop, int round) throws Exception {
        Method m = FamiliarLoop.class.getDeclaredMethod("snapshotCheckpoint", int.class);
        m.setAccessible(true);
        return (String) m.invoke(loop, round);
    }

    @Test
    void pruningKeepsTheNewestRunsAndNeverTheCurrentOne() throws Exception {
        Path runs = Files.createDirectories(tmp.resolve("p.cp-checkpoints"));
        for (String id : new String[]{"20260101-100000-a", "20260102-100000-b",
                                      "20260103-100000-c", "20260104-100000-d"}) {
            Files.createDirectories(runs.resolve("run-" + id).resolve("round-1"));
            Files.writeString(runs.resolve("run-" + id).resolve("round-1").resolve("x.py"), "x\n");
        }
        Path current = Files.createDirectories(runs.resolve("run-20260105-100000-e"));

        FamiliarLoop.pruneOldRuns(runs, current, 3);

        assertTrue(Files.exists(current), "the CURRENT run was deleted — a run would lose its own state");
        assertTrue(Files.exists(runs.resolve("run-20260104-100000-d")), "newest past run should survive");
        assertTrue(Files.exists(runs.resolve("run-20260103-100000-c")), "second-newest should survive");
        assertFalse(Files.exists(runs.resolve("run-20260102-100000-b")), "older run should be pruned");
        assertFalse(Files.exists(runs.resolve("run-20260101-100000-a")), "oldest run should be pruned");
    }

    @Test
    void pruningLeavesDirectoriesThatAreNotOurs() throws Exception {
        Path runs = Files.createDirectories(tmp.resolve("p.cp-checkpoints"));
        Path foreign = Files.createDirectories(runs.resolve("someone-elses-data"));
        Path legacy = Files.createDirectories(runs.resolve("round-1"));   // pre-fix layout
        Path current = Files.createDirectories(runs.resolve("run-20260105-100000-e"));

        FamiliarLoop.pruneOldRuns(runs, current, 1);

        assertTrue(Files.exists(foreign), "pruning deleted a directory it does not own");
        assertTrue(Files.exists(legacy), "pruning deleted an unrecognised directory");
    }
}
