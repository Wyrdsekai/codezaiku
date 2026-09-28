package org.codezaiku;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.codezaiku.SelfUpdate.Outcome;
import org.codezaiku.SelfUpdate.Result;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `codezaiku update now` updates CodeZaiku and then asks ResearchZosho's own updater; neither outcome stops the other and
 * both are said. With --json it is one document about CodeZaiku alone, for a program that updates CodeZaiku.
 */
class UpdateCommandTest {

    @BeforeEach
    void unixOnly() { Assumptions.assumeFalse(ResearchZoshoInstall.windows(), "the fake launcher is a shell script"); }

    /** CodeZaiku's side answers {@code self}; ResearchZosho is {@code rz}, or absent. */
    static final class Fake implements UpdateCommand.Parts {
        final Outcome self; final FakeResearchZosho rz; final List<String> asked = new ArrayList<>();
        Fake(Outcome self, FakeResearchZosho rz) { this.self = self; this.rz = rz; }
        @Override public Outcome selfNow(String version, PrintStream progress) {
            asked.add(String.valueOf(version));
            progress.println("codezaiku: checksum verified");
            return self;
        }
        @Override public String selfStatus() { return "CodeZaiku\n  installed: 0.3.10\n"; }
        @Override public String selfStatusJson() { return "{\"program\":\"codezaiku\"}"; }
        @Override public Path researchZosho() { return rz == null ? null : rz.launcher; }
        @Override public ResearchZoshoUpdate.Status researchZoshoStatus(Path launcher) { return ResearchZoshoUpdate.status(launcher, () -> null); }
    }

    record Said(int code, String out, String err) { }

    static Said run(UpdateCommand.Parts parts, String... args) {
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
        int code = UpdateCommand.run(args, new PrintStream(out, true), new PrintStream(err, true), parts);
        return new Said(code, out.toString(), err.toString());
    }

    static final Outcome UPDATED = new Outcome(Result.UPDATED, "0.3.10", "0.3.11", "CodeZaiku was updated from 0.3.10 to 0.3.11. The next codezaiku start runs it; your settings and sessions stay.");
    static final Outcome FAILED = new Outcome(Result.FAILED, "0.3.10", "0.3.11", "CodeZaiku was not updated: HTTP 404 for the tarball. The installed 0.3.10 stays.");

    static FakeResearchZosho rz(Path tmp, String result, int code) throws Exception {
        return new FakeResearchZosho(tmp.resolve("rz"), "0.5.0").now(ResearchZoshoUpdateTest.answer(result, code, "0.5.0", "0.5.1", false,
                result.equals("failed") ? "not updated: HTTP 404" : "updated to 0.5.1; the service restarted"), "researchzosho: updating 0.5.0 to 0.5.1\n", code);
    }

    @Test
    void codezaikuThenResearchZoshoAndABusyResearchZoshoIsNothingToDo(@TempDir Path tmp) throws Exception {
        var r = rz(tmp, "busy", 75);
        Said s = run(new Fake(UPDATED, r), "update", "now");
        assertEquals(0, s.code(), "busy is not a failure");
        assertTrue(s.out().contains(UPDATED.note()), s.out());
        assertTrue(s.out().contains("ResearchZosho is being updated by another program now; nothing to do."), s.out());
        assertTrue(s.out().indexOf(UPDATED.note()) < s.out().indexOf("ResearchZosho is being updated"), "CodeZaiku first");
        assertEquals(List.of("--version", "update now --json"), r.calls());
    }

    @Test
    void aFailedCodezaikuUpdateStillAsksResearchZoshoAndSaysBoth(@TempDir Path tmp) throws Exception {
        var r = rz(tmp, "updated", 0);
        Said s = run(new Fake(FAILED, r), "update", "now");
        assertEquals(1, s.code());
        assertTrue(s.out().contains(FAILED.note()), s.out());
        assertTrue(s.out().contains("ResearchZosho was updated from 0.5.0 to 0.5.1."), s.out());
        assertTrue(s.out().contains("researchzosho: updating 0.5.0 to 0.5.1"), "ResearchZosho's progress is shown: " + s.out());
    }

    @Test
    void aFailedResearchZoshoUpdateIsSaidAfterCodezaikusAndTheCodeIsOne(@TempDir Path tmp) throws Exception {
        var r = rz(tmp, "failed", 1);
        Said s = run(new Fake(UPDATED, r), "update", "now");
        assertEquals(1, s.code());
        assertTrue(s.out().contains(UPDATED.note()) && s.out().contains("ResearchZosho was not updated. Its updater says: not updated: HTTP 404."), s.out());
    }

    @Test
    void withoutResearchZoshoOnlyCodezaikuIsUpdated() {
        Said s = run(new Fake(UPDATED, null), "update", "now");
        assertEquals(0, s.code());
        assertFalse(s.out().contains("ResearchZosho"), s.out());
    }

    @Test
    void aVersionIsCodezaikusAndResearchZoshoIsAskedForItsLatest(@TempDir Path tmp) throws Exception {
        var r = rz(tmp, "current", 0);
        var parts = new Fake(UPDATED, r);
        run(parts, "update", "now", "0.3.11");
        assertEquals(List.of("0.3.11"), parts.asked);
        assertEquals("update now --json", r.calls().get(1), "no CodeZaiku version handed to ResearchZosho");
    }

    @Test
    void jsonIsOneDocumentAboutCodezaikuAloneWithItsExitCode(@TempDir Path tmp) throws Exception {
        var r = rz(tmp, "updated", 0);
        Said s = run(new Fake(new Outcome(Result.BUSY, "0.3.10", "0.3.10", "CodeZaiku is being updated by another program now; nothing to do."), r), "update", "now", "--json");
        assertEquals(75, s.code());
        JsonNode doc = new ObjectMapper().readTree(s.out());
        assertEquals("busy", doc.get("result").asText());
        assertEquals(75, doc.get("code").asInt());
        assertEquals(1, s.out().strip().split("\\R").length, "one line, one document: " + s.out());
        assertTrue(s.err().contains("codezaiku: checksum verified"), "progress on stderr: " + s.err());
        assertEquals(List.of(), r.calls(), "a program that updates CodeZaiku asks ResearchZosho's updater itself");
    }

    @Test
    void theStatusShowsBothAndItsJsonIsCodezaikusAlone(@TempDir Path tmp) throws Exception {
        var r = new FakeResearchZosho(tmp.resolve("rz"), "0.4.6");
        Said s = run(new Fake(UPDATED, r), "update");
        assertEquals(0, s.code());
        assertTrue(s.out().startsWith("CodeZaiku\n  installed: 0.3.10\n\nResearchZosho\n  installed: 0.4.6"), s.out());

        Said j = run(new Fake(UPDATED, r), "update", "--json");
        assertEquals("{\"program\":\"codezaiku\"}", j.out().strip());
        Said js = run(new Fake(UPDATED, r), "update", "status", "--json");
        assertEquals(j.out(), js.out());
    }

    @Test
    void anUnknownWordIsAUsageError() {
        assertEquals(2, run(new Fake(UPDATED, null), "update", "now", "latest").code());
        assertEquals(2, run(new Fake(UPDATED, null), "update", "sideways").code());
        assertEquals(2, run(new Fake(UPDATED, null), "update", "auto", "maybe").code());
    }
}
