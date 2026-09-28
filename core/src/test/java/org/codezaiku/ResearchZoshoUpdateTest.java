package org.codezaiku;

import org.codezaiku.SelfUpdate.Result;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ResearchZosho is updated by its own updater: CodeZaiku runs `researchzosho update now --json` and says what came of
 * it; an older ResearchZosho without --json is read from its words and its exit code, and is never given the flag.
 */
class ResearchZoshoUpdateTest {

    @BeforeEach
    void unixOnly() { Assumptions.assumeFalse(ResearchZoshoInstall.windows(), "the fake launcher is a shell script"); }

    static String answer(String result, int code, String from, String to, boolean after, String note) {
        return "{\"result\":\"" + result + "\",\"code\":" + code + ",\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"finishesAfterExit\":" + after + ",\"note\":\"" + note + "\"}\n";
    }

    @Test
    void aJsonUpdaterIsAskedWithJsonAndItsProgressIsPassedOn(@TempDir Path tmp) throws Exception {
        var rz = new FakeResearchZosho(tmp.resolve("rz"), "0.5.0")
                .now(answer("updated", 0, "0.5.0", "0.5.1", false, "updated to 0.5.1; the service restarted"),
                     "researchzosho: updating 0.5.0 to 0.5.1\nresearchzosho: checksum verified\n", 0);
        var progress = new ByteArrayOutputStream();
        var a = ResearchZoshoUpdate.now(rz.launcher, null, new PrintStream(progress, true));
        assertEquals(Result.UPDATED, a.result());
        assertEquals("0.5.0", a.from());
        assertEquals("0.5.1", a.to());
        assertEquals(List.of("--version", "update now --json"), rz.calls());
        assertTrue(progress.toString().contains("researchzosho: checksum verified"), progress.toString());
        assertFalse(progress.toString().contains("\"result\""), "the JSON document is read, not shown as progress");
        assertEquals("ResearchZosho was updated from 0.5.0 to 0.5.1. Its updater says: updated to 0.5.1; the service restarted.", ResearchZoshoUpdate.words(a));
    }

    @Test
    void aVersionIsPassedToTheUpdater(@TempDir Path tmp) throws Exception {
        var rz = new FakeResearchZosho(tmp.resolve("rz"), "0.5.0").now(answer("updated", 0, "0.5.0", "0.5.2", false, "updated to 0.5.2"), "", 0);
        ResearchZoshoUpdate.now(rz.launcher, "0.5.2", new PrintStream(new ByteArrayOutputStream()));
        assertEquals("update now 0.5.2 --json", rz.calls().get(1));
    }

    @Test
    void busyIsNothingToDoNotAFailure(@TempDir Path tmp) throws Exception {
        var rz = new FakeResearchZosho(tmp.resolve("rz"), "0.5.0")
                .now(answer("busy", 75, "0.5.0", "0.5.0", false, "another update of ResearchZosho is running now; nothing was changed"), "", 75);
        var a = ResearchZoshoUpdate.now(rz.launcher, null, new PrintStream(new ByteArrayOutputStream()));
        assertEquals(Result.BUSY, a.result());
        assertEquals("ResearchZosho is being updated by another program now; nothing to do.", ResearchZoshoUpdate.words(a));
    }

    @Test
    void currentNotHereAndFailedAreEachSaidPlainly(@TempDir Path tmp) throws Exception {
        var rz = new FakeResearchZosho(tmp.resolve("rz"), "0.5.0").now(answer("current", 0, "0.5.0", "0.5.0", false, "already 0.5.0"), "", 0);
        var a = ResearchZoshoUpdate.now(rz.launcher, null, new PrintStream(new ByteArrayOutputStream()));
        assertEquals(Result.CURRENT, a.result());
        assertEquals("ResearchZosho 0.5.0 is up to date; nothing to do.", ResearchZoshoUpdate.words(a));

        rz.now(answer("not-here", 3, "0.5.0", "0.5.0", false, "this is a run from the source tree; update it with git"), "", 3);
        a = ResearchZoshoUpdate.now(rz.launcher, null, new PrintStream(new ByteArrayOutputStream()));
        assertEquals(Result.NOT_HERE, a.result());
        assertTrue(ResearchZoshoUpdate.words(a).startsWith("ResearchZosho cannot update itself where it is installed. Its updater says: this is a run from the source tree"), ResearchZoshoUpdate.words(a));

        rz.now(answer("failed", 1, "0.5.0", "0.5.1", false, "not updated: checksum mismatch for researchzosho-0.5.1.tar.gz"), "", 1);
        a = ResearchZoshoUpdate.now(rz.launcher, null, new PrintStream(new ByteArrayOutputStream()));
        assertEquals(Result.FAILED, a.result());
        String w = ResearchZoshoUpdate.words(a);
        assertTrue(w.startsWith("ResearchZosho was not updated. Its updater says: not updated: checksum mismatch") && w.contains("The installed version stays."), w);
    }

    @Test
    void aWindowsUpdateThatFinishesAfterItEndsIsCarriedThrough(@TempDir Path tmp) throws Exception {
        var rz = new FakeResearchZosho(tmp.resolve("rz"), "0.5.0")
                .now(answer("updated", 0, "0.5.0", "0.5.1", true, "0.5.1 is downloaded and checked. The update finishes when this command ends."), "", 0);
        var a = ResearchZoshoUpdate.now(rz.launcher, null, new PrintStream(new ByteArrayOutputStream()));
        assertTrue(a.finishesAfterExit());
        assertEquals(Result.UPDATED, a.result());
    }

    @Test
    void anOlderUpdaterIsNotGivenJsonAndAlreadyMeansCurrent(@TempDir Path tmp) throws Exception {
        var rz = new FakeResearchZosho(tmp.resolve("rz"), "0.4.6").now("already 0.4.6\n", "", 1);
        var a = ResearchZoshoUpdate.now(rz.launcher, null, new PrintStream(new ByteArrayOutputStream()));
        assertEquals(List.of("--version", "update now"), rz.calls(), "no --json for an updater that does not know it");
        assertEquals(Result.CURRENT, a.result(), "an older updater exits 1 for 'already'; that is not a failure");
        assertEquals("ResearchZosho 0.4.6 is up to date; nothing to do.", ResearchZoshoUpdate.words(a));
    }

    @Test
    void anOlderUpdaterThatUpdatedOrFailedIsReadFromItsWordsAndCode(@TempDir Path tmp) throws Exception {
        var rz = new FakeResearchZosho(tmp.resolve("rz"), "0.4.6")
                .now("researchzosho: updating 0.4.6 → 0.4.7\nresearchzosho: checksum verified\nupdated to 0.4.7; the service restarted\n", "", 0);
        var progress = new ByteArrayOutputStream();
        var a = ResearchZoshoUpdate.now(rz.launcher, null, new PrintStream(progress, true));
        assertEquals(Result.UPDATED, a.result());
        assertEquals("0.4.7", a.to());
        assertEquals("updated to 0.4.7; the service restarted", a.note());
        assertTrue(progress.toString().contains("checksum verified"), "an older updater's progress is on stdout, and is shown: " + progress);

        rz.now("researchzosho: updating 0.4.6 → 0.4.7\nnot updated: checksum mismatch for researchzosho-0.4.7.tar.gz\n", "", 1);
        a = ResearchZoshoUpdate.now(rz.launcher, null, new PrintStream(new ByteArrayOutputStream()));
        assertEquals(Result.FAILED, a.result());
        assertTrue(ResearchZoshoUpdate.words(a).contains("not updated: checksum mismatch"), ResearchZoshoUpdate.words(a));
    }

    @Test
    void aNewerUpdaterWhoseOutputIsNotJsonFallsBackToItsExitCode(@TempDir Path tmp) throws Exception {
        var rz = new FakeResearchZosho(tmp.resolve("rz"), "0.5.0").now("something went sideways\n", "", 75);
        var a = ResearchZoshoUpdate.now(rz.launcher, null, new PrintStream(new ByteArrayOutputStream()));
        assertEquals(Result.BUSY, a.result());
    }

    @Test
    void aLauncherThatCannotRunIsAFailureWithTheReason(@TempDir Path tmp) {
        var a = ResearchZoshoUpdate.now(tmp.resolve("no-such-researchzosho"), null, new PrintStream(new ByteArrayOutputStream()));
        assertEquals(Result.FAILED, a.result());
        assertTrue(a.note().startsWith("could not run "), a.note());
    }

    @Test
    void theStatusComesFromTheUpdatersJson(@TempDir Path tmp) throws Exception {
        var rz = new FakeResearchZosho(tmp.resolve("rz"), "0.5.0").status("{\"program\":\"researchzosho\",\"installed\":\"0.5.0\",\"running\":\"0.5.0\",\"latest\":\"0.5.1\","
                + "\"newer\":true,\"mode\":\"check\",\"root\":\"/opt/rz\",\"canUpdate\":true,\"updating\":true}\n", 0);
        var s = ResearchZoshoUpdate.status(rz.launcher, () -> { throw new AssertionError("the JSON says the latest; nothing else is asked"); });
        assertTrue(s.fromJson());
        assertEquals("0.5.0", s.installed());
        assertEquals("0.5.1", s.latest());
        String text = ResearchZoshoUpdate.statusText(s, rz.launcher);
        assertTrue(text.startsWith("ResearchZosho\n  installed: 0.5.0 at /opt/rz\n  latest:    0.5.1\n  mode:      check"), text);
        assertTrue(text.contains("An update of ResearchZosho is running now.") && text.contains("A newer release is out. codezaiku update now"), text);
        assertEquals(List.of("update --json"), rz.calls());
    }

    @Test
    void anOlderUpdatersStatusIsItsVersion(@TempDir Path tmp) throws Exception {
        var rz = new FakeResearchZosho(tmp.resolve("rz"), "0.4.6");   // answers `update --json` with its usage, exit 2
        var s = ResearchZoshoUpdate.status(rz.launcher, () -> "0.5.0");
        assertFalse(s.fromJson());
        assertEquals("0.4.6", s.installed());
        assertTrue(s.newer());
        String text = ResearchZoshoUpdate.statusText(s, rz.launcher);
        assertTrue(text.contains("installed: 0.4.6 at " + rz.launcher) && text.contains("latest:    0.5.0") && !text.contains("mode:"), text);
    }
}
