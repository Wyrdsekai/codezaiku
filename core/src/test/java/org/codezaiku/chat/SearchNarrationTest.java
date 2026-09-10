package org.codezaiku.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The web-narration digest (the operator, 2026-09-01: "right now we only know searches and pages are
 * being done") — parsed from the REAL result formats both search backends emit, so a format
 * drift breaks a test instead of silently blanking the narration.
 */
class SearchNarrationTest {

    @Test
    void digestCountsResultsAndShowsTopUrls() {
        String result = """
                results for "japanese wav2vec2":
                1. First Model
                   https://huggingface.co/a/model-one
                   a description here
                2. Second Model
                   https://huggingface.co/b/model-two
                3. Third Model
                   https://huggingface.co/c/model-three
                4. Fourth Model
                   https://huggingface.co/d/model-four
                """;
        String d = ChatRepl.Narrator.searchDigest(result);
        assertTrue(d.startsWith("4 results — "), d);
        assertTrue(d.contains("model-one") && d.contains("model-three"), d);
        assertFalse(d.contains("model-four"), "default shows 3 urls: " + d);
    }

    @Test
    void degradedNoResultsAndRepeatStatesAreNamed() {
        assertEquals("search backend DEGRADED — retrying differently",
                ChatRepl.Narrator.searchDigest("SEARCH BACKEND DEGRADED — no results came back…"));
        assertEquals("0 results", ChatRepl.Narrator.searchDigest("no results for: xyz"));
        assertEquals("already searched — needs a different query",
                ChatRepl.Narrator.searchDigest("ALREADY SEARCHED: you already ran this exact query;…"));
    }

    @Test
    void fetchTitleExtractionSurvivesRealHtml() {
        assertEquals("Tokyo Vice — Wikipedia", org.codezaiku.tools.WebFetchTool.pageTitle(
                "<html><head>\n<title>\n  Tokyo Vice &amp;#8212; Wikipedia\n</title></head>"
                        .replace("&amp;#8212;", "—")));
        assertEquals("", org.codezaiku.tools.WebFetchTool.pageTitle("<html><body>no title</body>"));
        assertEquals("", org.codezaiku.tools.WebFetchTool.pageTitle(null));
    }
}
