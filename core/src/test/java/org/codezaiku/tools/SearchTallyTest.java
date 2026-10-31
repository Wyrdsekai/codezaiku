package org.codezaiku.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The month's tally of searches and pages by service, in a file of its own. */
class SearchTallyTest {
    static void at(Path p) throws Exception { Field f = SearchTally.class.getDeclaredField("override"); f.setAccessible(true); f.set(null, p); }

    @AfterEach void back() throws Exception { at(null); }

    @Test void countsCallsAndCreditsByServiceAndSaysSoPlainly(@TempDir Path tmp) throws Exception {
        at(tmp.resolve("tally.json"));
        assertTrue(SearchTally.summary().startsWith("Nothing counted this month yet"), SearchTally.summary());
        SearchTally.count("Serper", 1); SearchTally.count("Serper", 1); SearchTally.count("Brave", 0); SearchTally.count("Firecrawl", 1);
        assertEquals(2, SearchTally.thisMonth().get("Serper")[0]); assertEquals(2, SearchTally.thisMonth().get("Serper")[1]);
        assertEquals(1, SearchTally.thisMonth().get("Brave")[0]); assertEquals(0, SearchTally.thisMonth().get("Brave")[1]);
        String s = SearchTally.summary();
        assertTrue(s.startsWith("This month, " + SearchTally.month() + ": 1 search through Brave; 1 page through Firecrawl, 1 credit; 2 searches through Serper, 2 credits. The tally is in "), s);
    }
}
