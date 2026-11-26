package org.codezaiku.drive;

import org.codezaiku.Config;
import org.codezaiku.testsupport.StandInModelServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fallback: a job whose model does not answer, or has every call in use, runs on the fallback and a line says so; a model with a
 * free call keeps the job; a llama-swap proxy is asked for its model's slots only when it already holds that model loaded, since asking
 * for one that is not loaded starts it; nothing at all is asked when no fallback is set; a hosted API is not asked. Every server here is
 * a stand-in on a loopback port of its own, and the settings live in a scratch home.
 */
class FallbackTest {

    @TempDir Path home;
    @TempDir Path project;
    private String realHome;

    @BeforeEach void scratchHome() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".codezaiku"));
        Config.set("CODEZAIKU_DRIVE", "http://127.0.0.1:9");
        Config.set("CODEZAIKU_MODEL", "local-model");
    }

    @AfterEach void realHomeBack() throws Exception {
        Fallback.NAMED_FOR_TESTS = null;
        System.setProperty("user.home", realHome);
        Config.invalidate();   // the next read loads the real home's settings; nothing is changed there
    }

    Auto.Job job() { return Auto.Job.of("code", "B", true, project); }

    /** The job's choice of the saved model {@code name}, as a command line makes it. */
    static SavedModels.Choice chose(String name) { return SavedModels.choose(null, name); }

    @Test
    void theChosenServerDownSendsTheJobToTheFallbackAndSaysSo() throws Exception {
        try (StandInModelServer near = StandInModelServer.serving("near-model")) {
            SavedModels.put("far", StandInModelServer.nothingAt(), "far-model", null, null, null, null);
            SavedModels.put("near", near.address(), "near-model", null, null, null, null);
            Fallback.NAMED_FOR_TESTS = "near";
            SavedModels.Choice c = chose("far");
            Fallback.Outcome o = Fallback.decide(c, job());
            assertEquals("near", o.choice().entry().name()); assertEquals("near-model", o.choice().model()); assertEquals(near.address(), o.choice().drive());
            assertEquals("far does not answer, so this job runs on near (near-model).", o.line());
            assertTrue(o.moved(c));
        }
    }

    @Test
    void everySlotWorkingIsBusyAndOneFreeSlotKeepsTheJob() throws Exception {
        try (StandInModelServer far = StandInModelServer.serving("far-model").slots(4, 4); StandInModelServer near = StandInModelServer.serving("near-model")) {
            SavedModels.put("far", far.address(), "far-model", null, null, null, null);
            SavedModels.put("near", near.address(), "near-model", null, null, null, null);
            Fallback.NAMED_FOR_TESTS = "near";
            Fallback.Outcome busy = Fallback.decide(chose("far"), job());
            assertEquals("near", busy.choice().entry().name());
            assertEquals("far is busy (4 of 4 calls in use), so this job runs on near (near-model).", busy.line());
            assertTrue(far.paths().contains("/slots"), far.paths().toString());

            far.slots(3, 4);
            int asked = near.requests();
            SavedModels.Choice c = chose("far");
            Fallback.Outcome free = Fallback.decide(c, job());
            assertSame(c, free.choice(), "one call free: the job stays"); assertNull(free.line(), "and nothing is said");
            assertEquals(asked, near.requests(), "the fallback is not asked when the job's own model is free");

            far.noSlots().slots(4, 4);
            assertSame(c.entry(), Fallback.decide(c, job()).choice().entry(), "a server that shows no slots is not busy");
        }
    }

    @Test
    void aProxyIsAskedForItsModelsSlotsOnlyWhenThatModelIsLoaded() throws Exception {
        try (StandInModelServer far = StandInModelServer.serving("far-model").slots(2, 2).proxy(null, null); StandInModelServer near = StandInModelServer.serving("near-model")) {
            SavedModels.put("far", far.address(), "far-model", null, null, null, null);
            SavedModels.put("near", near.address(), "near-model", null, null, null, null);
            Fallback.NAMED_FOR_TESTS = "near";
            // nothing loaded: the proxy is free, and /upstream/ is never asked, since asking it would start the model
            SavedModels.Choice c = chose("far");
            Fallback.Outcome o = Fallback.decide(c, job());
            assertSame(c, o.choice()); assertNull(o.line());
            assertTrue(far.paths().stream().noneMatch(p -> p.startsWith("/upstream/")), far.paths().toString());
            assertTrue(far.paths().contains("/running"), far.paths().toString());
            assertEquals(0, near.requests());
            // another model loaded: the same
            far.proxy("other-model", "ready");
            assertSame(c, Fallback.decide(c, job()).choice());
            // ours still starting: not asked either
            far.proxy("far-model", "starting");
            assertSame(c, Fallback.decide(c, job()).choice());
            assertTrue(far.paths().stream().noneMatch(p -> p.startsWith("/upstream/")), far.paths().toString());
            // ours loaded and ready, every call in use: asked at its upstream, and busy
            far.proxy("far-model", "ready");
            Fallback.Outcome busy = Fallback.decide(c, job());
            assertEquals("near", busy.choice().entry().name(), busy.line());
            assertTrue(far.paths().contains("/upstream/far-model/slots"), far.paths().toString());
            assertFalse(far.paths().contains("/slots"), "the proxy's own /slots is not the model's");
        }
    }

    @Test
    void whenTheFallbackCannotHelpTheJobStaysAndTheLineSaysBothWereTried() throws Exception {
        String nowhere = StandInModelServer.nothingAt();
        try (StandInModelServer far = StandInModelServer.serving("far-model").slots(2, 2); StandInModelServer near = StandInModelServer.serving("near-model").slots(1, 1)) {
            SavedModels.put("down", nowhere, "down-model", null, null, null, null);
            SavedModels.put("far", far.address(), "far-model", null, null, null, null);
            SavedModels.put("near", near.address(), "near-model", null, null, null, null);
            SavedModels.put("gone", StandInModelServer.nothingAt(), "gone-model", null, null, null, null);

            Fallback.NAMED_FOR_TESTS = "gone";
            SavedModels.Choice down = chose("down");
            Fallback.Outcome both = Fallback.decide(down, job());
            assertSame(down, both.choice(), "neither answers: the job goes on as it would have");
            assertEquals("down does not answer, and the fallback gone (gone-model) does not answer either, so this job stays on down.", both.line());

            SavedModels.Choice busy = chose("far");
            Fallback.Outcome busyAndGone = Fallback.decide(busy, job());
            assertSame(busy, busyAndGone.choice());
            assertEquals("far is busy (2 of 2 calls in use), and the fallback gone (gone-model) does not answer, so this job stays on far and waits for a free call.", busyAndGone.line());

            Fallback.NAMED_FOR_TESTS = "near";
            Fallback.Outcome bothBusy = Fallback.decide(busy, job());
            assertSame(busy, bothBusy.choice());
            assertEquals("far is busy (2 of 2 calls in use), and the fallback near (near-model) is busy too (1 of 1 call in use), so this job stays on far and waits for a free call.", bothBusy.line());

            // a silent server fails the job and a busy one queues it: the busy fallback takes it
            Fallback.Outcome queued = Fallback.decide(down, job());
            assertEquals("near", queued.choice().entry().name());
            assertEquals("down does not answer, so this job runs on near (near-model), which is busy too (1 of 1 call in use): the job waits there for a free call.", queued.line());
        }
    }

    @Test
    void withNoFallbackSetNothingIsAskedAtAll() throws Exception {
        try (StandInModelServer far = StandInModelServer.serving("far-model").slots(2, 2); StandInModelServer near = StandInModelServer.serving("near-model")) {
            SavedModels.put("far", far.address(), "far-model", null, null, null, null);
            SavedModels.put("near", near.address(), "near-model", null, null, null, null);
            assertNull(Fallback.named(), "the suite pins the setting off, and this test sets none");
            SavedModels.Choice c = chose("far");
            for (String none : new String[]{null, "off", "none", " "}) {
                Fallback.NAMED_FOR_TESTS = none;
                Fallback.Outcome o = Fallback.decide(c, job());
                assertSame(c, o.choice()); assertNull(o.line());
            }
            assertEquals(0, far.requests(), far.paths().toString()); assertEquals(0, near.requests(), near.paths().toString());
            // the job already on the fallback: nothing asked either
            Fallback.NAMED_FOR_TESTS = "far";
            assertSame(c, Fallback.decide(c, job()).choice());
            Fallback.NAMED_FOR_TESTS = "default";
            Config.set("CODEZAIKU_DRIVE", far.address()); Config.set("CODEZAIKU_MODEL", "far-model");
            assertSame(c, Fallback.decide(c, job()).choice(), "the same address and id under another name is the same model");
            assertEquals(0, far.requests(), far.paths().toString());
        }
    }

    @Test
    void aHostedModelIsNotAskedAndANameNothingIsSavedUnderIsSaid() throws Exception {
        try (StandInModelServer near = StandInModelServer.serving("near-model")) {
            SavedModels.put("near", near.address(), "near-model", null, null, null, null);
            SavedModels.put("deep", "openrouter", "deepseek/deepseek-v4.1-flash", "0.024/0.72", null, null, null);
            Fallback.NAMED_FOR_TESTS = "near";
            SavedModels.Choice c = chose("deep");
            assertTrue(c.entry().hosted());
            Fallback.Outcome o = Fallback.decide(c, job());
            assertSame(c, o.choice(), "a hosted API answers"); assertNull(o.line());
            assertEquals(0, near.requests());
            // the reading itself: a hosted address is not asked, wherever it points
            assertTrue(Fallback.read(near.address(), "near-model", true).available());
            assertEquals(0, near.requests(), "hosted: no request made " + near.paths());

            Fallback.NAMED_FOR_TESTS = "nobody";
            SavedModels.Choice far = chose("near");
            Fallback.Outcome missing = Fallback.decide(far, job());
            assertSame(far, missing.choice());
            assertTrue(missing.line().startsWith("The fallback setting names nobody, and no saved model has that name, so this job runs on near with no fallback."), missing.line());
            assertEquals(0, near.requests());
        }
    }

    @Test
    void aPrivateProjectKeepsAHostedFallbackOut() throws Exception {
        Files.createDirectories(project.resolve(".codezaiku"));
        Files.writeString(project.resolve(".codezaiku").resolve("private"), "private\n");
        SavedModels.put("far", StandInModelServer.nothingAt(), "far-model", null, null, null, null);
        SavedModels.put("deep", "openrouter", "deepseek/deepseek-v4.1-flash", "0.024/0.72", null, null, null);
        Fallback.NAMED_FOR_TESTS = "deep";
        SavedModels.Choice c = chose("far");
        Fallback.Outcome o = Fallback.decide(c, job());
        assertSame(c, o.choice());
        assertEquals("far does not answer, and the fallback deep (deepseek/deepseek-v4.1-flash) cannot take it (the project is private), so this job stays on far.", o.line());
    }

    @Test
    void theDefaultIsNamedByTheSavedModelItIsAndOtherwiseByWhereItIs() throws Exception {
        try (StandInModelServer near = StandInModelServer.serving("near-model")) {
            String nowhere = StandInModelServer.nothingAt();
            SavedModels.put("near", near.address(), "near-model", null, null, null, null);
            Fallback.NAMED_FOR_TESTS = "near";
            Config.set("CODEZAIKU_DRIVE", nowhere); Config.set("CODEZAIKU_MODEL", "big-model");
            Fallback.Outcome plain = Fallback.decide(SavedModels.choose(null, null), job());
            assertEquals("the default model (big-model at " + nowhere.substring("http://".length()) + ") does not answer, so this job runs on near (near-model).", plain.line());
            SavedModels.put("far", nowhere, "big-model", null, null, null, null);   // `model use far` leaves the default the same server
            Fallback.Outcome twin = Fallback.decide(SavedModels.choose(null, null), job());
            assertEquals("far does not answer, so this job runs on near (near-model).", twin.line());
        }
    }
}
