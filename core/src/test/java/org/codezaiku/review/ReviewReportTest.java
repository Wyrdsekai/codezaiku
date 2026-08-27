package org.codezaiku.review;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing is tested against the messy shapes a small model actually emits — bullets, "medium" instead
 * of "med", stray punctuation — because a parser that only accepts the ideal output would silently
 * discard most of a real review and report a confidently short findings list.
 */
class ReviewReportTest {

    private static final String DIFF = """
            --- a/app/handler.py
            +++ b/app/handler.py
            @@ -10,6 +10,7 @@ def setup():
                 config = load_config()
            +    cache = Cache(ttl=0)
                 logger.info("ready")
            @@ -40,7 +41,7 @@ def handle(request):
            +    query = build_query(user)
                 rows = db.execute(query)
            """;

    @Test void anchorsFindingsToRealLineNumbers() {
        String summary = "[high] app/handler.py `cache = Cache(ttl=0)` — ttl=0 disables caching — set a real ttl";
        List<ReviewReport.Finding> f = ReviewReport.parse(summary, DIFF);
        assertEquals(1, f.size());
        assertTrue(f.get(0).located());
        assertEquals(11, f.get(0).anchor().get().startLine());
        assertTrue(f.get(0).render().startsWith("[high] app/handler.py:11 —"), f.get(0).render());
    }

    /** The whole point: an invented quote must not be printed as if it had a location. */
    @Test void keepsButSeparatesFindingsItCannotLocate() {
        String summary = """
                [high] app/handler.py `cache = Cache(ttl=0)` — ttl=0 disables caching — set a real ttl
                [med] app/handler.py `os.system(user_input)` — command injection — use subprocess
                """;
        List<ReviewReport.Finding> f = ReviewReport.parse(summary, DIFF);
        assertEquals(2, f.size());
        assertTrue(f.get(0).located());
        assertFalse(f.get(1).located(), "code absent from the diff must not anchor");

        String out = ReviewReport.render(f);
        assertTrue(out.contains("COULD NOT LOCATE"), out);
        assertTrue(out.contains("1 located, 1 unlocated"), out);
        // The unlocated finding must carry no line number at all.
        assertFalse(out.contains("app/handler.py:0"), out);
    }

    @Test void toleratesBulletsAndVerboseSeverities() {
        String summary = """
                - [MEDIUM] app/handler.py `query = build_query(user)` — unvalidated input — validate it
                * [low] app/handler.py: "logger.info(\\"ready\\")" - noisy log - drop it
                """;
        List<ReviewReport.Finding> f = ReviewReport.parse(summary, DIFF);
        assertEquals(2, f.size(), "bulleted and quote-variant lines must still parse");
        assertEquals("med", f.get(0).severity(), "MEDIUM must normalise to med");
        assertEquals("app/handler.py", f.get(1).file(), "a trailing colon must not become part of the path");
    }

    @Test void skipsProseWithoutFailingTheReport() {
        String summary = """
                Here is my review of the changes.
                [high] app/handler.py `cache = Cache(ttl=0)` — ttl=0 disables caching — set a real ttl
                Overall the change looks reasonable.
                """;
        assertEquals(1, ReviewReport.parse(summary, DIFF).size());
    }

    @Test void ordersLocatedFindingsBySeverity() {
        String summary = """
                [low] app/handler.py `logger.info("ready")` — noisy — drop
                [high] app/handler.py `cache = Cache(ttl=0)` — ttl=0 — fix
                """;
        String out = ReviewReport.render(ReviewReport.parse(summary, DIFF));
        assertTrue(out.indexOf("[high]") < out.indexOf("[low]"), "most severe first:\n" + out);
    }

    @Test void reportsDeletedCodeAsSuch() {
        String diff = """
                --- a/m.py
                +++ b/m.py
                @@ -5,3 +5,2 @@
                 keep = 1
                -password = "hunter2"
                """;
        String summary = "[high] m.py `password = \"hunter2\"` — hardcoded secret — remove it";
        List<ReviewReport.Finding> f = ReviewReport.parse(summary, diff);
        assertTrue(f.get(0).located());
        assertEquals(DiffAnchor.Side.OLD, f.get(0).anchor().get().side());
        assertTrue(f.get(0).render().contains("DELETES"), f.get(0).render());
    }

    @Test void emptyInputIsNotAnError() {
        assertTrue(ReviewReport.parse("", DIFF).isEmpty());
        assertTrue(ReviewReport.parse(null, DIFF).isEmpty());
        assertEquals("No findings in the expected format.", ReviewReport.render(List.of()));
    }
}
