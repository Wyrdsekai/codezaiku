package org.codezaiku;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.drive.Auto;
import org.codezaiku.drive.Fallback;
import org.codezaiku.drive.SavedModels;
import org.codezaiku.run.ResultDocument;
import org.codezaiku.testsupport.StandInModelServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fallback where a job chooses its model: a coding job whose model does not answer runs on the fallback, says so before the work,
 * and the result document a host reads names the model that ran; the cap's check before a job asks no server; `model list` shows the
 * fallback beside the model it stands behind and asks nothing.
 */
class FallbackJobTest {

    @TempDir Path home;
    @TempDir Path project;
    private String realHome;
    private PrintStream realOut;

    @BeforeEach void scratchHome() throws Exception {
        realHome = System.getProperty("user.home");
        realOut = System.out;
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".codezaiku"));
        Config.set("CODEZAIKU_DRIVE", "http://127.0.0.1:9");
        Config.set("CODEZAIKU_MODEL", "local-model");
    }

    @AfterEach void realHomeBack() throws Exception {
        System.setOut(realOut);
        Fallback.NAMED_FOR_TESTS = null;
        System.setProperty("user.home", realHome);
        Config.invalidate();   // the next read loads the real home's settings; nothing is changed there
    }

    /** What {@code r} prints while it runs. */
    String printed(ThrowingRunnable r) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        System.setOut(new PrintStream(b, true, StandardCharsets.UTF_8));
        try { r.run(); } finally { System.setOut(realOut); }
        return b.toString(StandardCharsets.UTF_8);
    }

    interface ThrowingRunnable { void run() throws Exception; }

    @Test
    void aCodingJobWhoseModelDoesNotAnswerRunsOnTheFallbackAndTheDocumentNamesIt() throws Exception {
        try (StandInModelServer near = StandInModelServer.serving("near-model")) {
            SavedModels.put("far", StandInModelServer.nothingAt(), "far-model", null, null, null, null);
            SavedModels.put("near", near.address(), "near-model", null, null, null, null);
            Fallback.NAMED_FOR_TESTS = "near";
            FamiliarMain.Tracked[] t = new FamiliarMain.Tracked[1];
            String out = printed(() -> t[0] = FamiliarMain.runLoopTracked(project, "Say hello.", Config.get("CODEZAIKU_DRIVE"), 3, "none", null, "far"));
            assertTrue(t[0].result().done(), t[0].result().summary());
            assertTrue(out.contains("far does not answer, so this job runs on near (near-model).\n"), out);
            assertTrue(out.indexOf("far does not answer") < out.indexOf("model: near (near-model via 127.0.0.1)\n"), "the line comes before the work: " + out);
            assertEquals("near", t[0].run().name()); assertEquals("near-model", t[0].run().model());
            assertTrue(near.paths().contains("/v1/chat/completions"), near.paths().toString());
            // the result document, built as `codezaiku run` builds it: the fields as they were, naming the model that ran
            ObjectNode doc = ResultDocument.of(project, "completed").model(t[0].run().model()).savedModel(t[0].run().name()).host(t[0].run().host()).usage(t[0].run().usage()).build();
            assertEquals("near-model", doc.path("model").asText()); assertEquals("near", doc.path("savedModel").asText()); assertEquals("127.0.0.1", doc.path("host").asText());
        }
    }

    @Test
    void theCapCheckBeforeAJobAsksNothingAndTheJobsOwnChoiceAsksOnce() throws Exception {
        try (StandInModelServer far = StandInModelServer.serving("far-model").slots(2, 2); StandInModelServer near = StandInModelServer.serving("near-model")) {
            SavedModels.put("far", far.address(), "far-model", null, null, null, null);
            SavedModels.put("near", near.address(), "near-model", null, null, null, null);
            Fallback.NAMED_FOR_TESTS = "near";
            Auto.Job job = Auto.Job.of("review", "B", true, project);
            String quiet = printed(() -> FamiliarMain.refusedAtCapOrExit(null, "far", job));
            assertEquals("", quiet);
            assertEquals(0, far.requests()); assertEquals(0, near.requests());
            SavedModels.Choice[] c = new SavedModels.Choice[1];
            String said = printed(() -> c[0] = FamiliarMain.chosen(null, "far", job));
            assertEquals("near", c[0].entry().name());
            assertEquals("far is busy (2 of 2 calls in use), so this job runs on near (near-model).\n", said);
            assertEquals(3, far.requests(), "its model list, whether it is a proxy, its slots: " + far.paths());
        }
    }

    @Test
    void modelListShowsTheFallbackBesideTheModelItStandsBehindAndAsksNothing() throws Exception {
        try (StandInModelServer far = StandInModelServer.serving("far-model"); StandInModelServer near = StandInModelServer.serving("near-model")) {
            SavedModels.put("far", far.address(), "far-model", null, null, null, null);
            SavedModels.put("near", near.address(), "near-model", null, null, null, null);
            Config.set("CODEZAIKU_DRIVE", far.address()); Config.set("CODEZAIKU_MODEL", "far-model");   // `model use far`
            String before = printed(() -> Models.command(new String[]{"list"}));
            assertFalse(before.contains("fallback"), before);
            Fallback.NAMED_FOR_TESTS = "near";
            String out = printed(() -> Models.command(new String[]{"list"}));
            String farLine = out.lines().filter(l -> l.startsWith("  far: ")).findFirst().orElseThrow();
            String nearLine = out.lines().filter(l -> l.startsWith("  near: ")).findFirst().orElseThrow();
            assertTrue(farLine.endsWith("<- in use, falls back to near"), out);
            assertTrue(nearLine.endsWith("<- the fallback"), out);
            assertTrue(out.contains("When the model a job would run on does not answer, or has every call in use, the job runs on near (near-model) and says so."), out);
            Fallback.NAMED_FOR_TESTS = "nobody";
            assertTrue(printed(() -> Models.command(new String[]{"list"})).contains("The fallback setting names nobody, and no saved model has that name"));
            assertEquals(0, far.requests()); assertEquals(0, near.requests());
        }
    }
}
