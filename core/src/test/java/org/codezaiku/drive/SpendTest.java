package org.codezaiku.drive;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The ledger: one line a job, totals by host and by saved model for the month and in all, a cap that refuses the next job with the figures. */
class SpendTest {

    @TempDir Path home;
    private String realHome;

    @BeforeEach void scratchHome() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".codezaiku"));
        Config.set("CODEZAIKU_DRIVE", "http://localhost:8200");
    }

    @AfterEach void realHomeBack() throws Exception { System.setProperty("user.home", realHome); Config.unset("CODEZAIKU_DRIVE"); }

    @Test
    void jobsAddUpByHostAndByNameAndACapRefusesTheNextOne() throws Exception {
        assertTrue(Spend.lines().isEmpty()); assertTrue(Spend.report().startsWith("No job has been recorded yet"));
        SavedModels.Entry cheap = SavedModels.put("cheap", "openrouter", "x/flash", "1/2", null, "0.5", null);
        SavedModels.Entry dear = SavedModels.put("dear", "anthropic", "claude-x", "5/25/0.5", null, null, null);
        Spend.record("code", home, cheap, new long[]{100_000, 0, 0, 50_000}, cheap.cost(new long[]{100_000, 0, 0, 50_000}));   // $0.10 + $0.10
        Spend.record("review", home, dear, new long[]{1_000_000, 0, 0, 0}, 5.0);
        Spend.record("chat", home, cheap, new long[]{100_000, 0, 0, 50_000}, 0.2);
        assertEquals(3, Spend.lines().size());
        assertEquals("code", Spend.lines().get(0).path("kind").asText()); assertEquals("cheap", Spend.lines().get(0).path("entry").asText()); assertEquals(100_000, Spend.lines().get(0).path("prompt").asLong());
        assertEquals(0.4, Spend.monthTotal("cheap"), 1e-9); assertEquals(0.4, Spend.monthTotal("openrouter.ai"), 1e-9); assertEquals(5.0, Spend.monthTotal("api.anthropic.com"), 1e-9); assertEquals(0, Spend.monthTotal("nobody"));
        ObjectNode s = Spend.summary();
        assertEquals("openrouter.ai", s.path("hosts").get(0).path("name").asText()); assertEquals(0.4, s.path("hosts").get(0).path("month").asDouble(), 1e-9); assertEquals(2, s.path("hosts").get(0).path("jobs_month").asInt());
        assertEquals("cheap", s.path("entries").get(0).path("name").asText()); assertEquals(0.5, s.path("entries").get(0).path("cap").asDouble(), 1e-9);
        assertFalse(s.path("entries").get(1).has("cap"));
        String report = Spend.report();
        assertTrue(report.contains("openrouter.ai") && report.contains("$0.40") && report.contains("$5.00") && report.contains("cap $0.50 a month"), report);
        assertNull(Spend.capReached(cheap), "$0.40 of $0.50: the next job may start");
        Spend.record("run", home, cheap, new long[]{100_000, 0, 0, 0}, 0.1);
        String why = Spend.capReached(SavedModels.get("cheap"));
        assertNotNull(why); assertTrue(why.contains("$0.50 of $0.50") && why.contains("codezaiku model set cheap --cap"), why);
        assertNull(Spend.capReached(dear), "no cap, no refusal");
        assertNull(Spend.capReached(new SavedModels.Entry("", "http://h", "m", null, "", 1, "")), "an unnamed choice has no ledger of its own");
        assertEquals("$0.13", Spend.dollars(0.129)); assertEquals("1,000 prompt (400 from the cache) + 20 completion tokens, $0.13", Spend.usageLine(new long[]{1000, 400, 0, 20}, 0.129, true));
        assertEquals("1,000 prompt + 20 completion tokens", Spend.usageLine(new long[]{1000, 0, 0, 20}, 0, false));
    }
}
