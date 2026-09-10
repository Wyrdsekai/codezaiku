package org.codezaiku.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The steerer is arithmetic over the run's own queries and hosts, so every rule is pinnable. */
class SearchSteerTest {

    private static List<String> urls(String... hosts) {
        return java.util.Arrays.stream(hosts).map(h -> "https://" + h + "/page").toList();
    }

    @Test
    void firstQueryIsNeverSteered() {
        SearchSteer s = new SearchSteer().focus("anything");
        assertEquals("", s.observe("japanese wav2vec2 alignment", urls("huggingface.co", "arxiv.org", "x.org")));
    }

    @Test
    void nearRepeatByTermsFiresAndNamesThePrior() {
        SearchSteer s = new SearchSteer().focus("keigo in subtitles");
        s.observe("keigo subtitle translation english honorifics", urls("a.org", "b.org", "c.org"));
        String note = s.observe("keigo honorifics subtitle translation english film", urls("d.org", "e.org", "f.org"));
        assertTrue(note.contains("STEER"), note);
        assertTrue(note.contains("overlaps an earlier one"), note);
        assertTrue(note.contains("keigo subtitle translation english honorifics"), note);
    }

    @Test
    void mostlySeenHostsFiresEvenWithFreshWording() {
        SearchSteer s = new SearchSteer();
        s.observe("alpha topic", urls("a.org", "b.org", "c.org", "d.org"));
        String note = s.observe("completely different words entirely", urls("a.org", "b.org", "c.org", "d.org", "e.org"));
        assertTrue(note.contains("already seen"), note);
    }

    @Test
    void saturationAfterThreeZeroGainQueries() {
        SearchSteer s = new SearchSteer();
        s.observe("q1 one", urls("a.org", "b.org"));
        assertFalse(s.observe("q2 two", urls("a.org")).contains("SATURATED"));
        assertFalse(s.observe("q3 three", urls("b.org")).contains("SATURATED"));
        assertTrue(s.observe("q4 four", urls("a.org", "b.org")).contains("SATURATED"));
    }

    @Test
    void exhaustedAfterTwoSaturationsAndCountsOverSearch() {
        SearchSteer s = new SearchSteer();
        s.observe("seed", urls("a.org", "b.org"));
        for (int i = 0; i < 3; i++) s.observe("q" + i + " x", urls("a.org"));   // first saturation
        assertFalse(s.exhausted(), "one saturation is a warning, not a stop");
        assertEquals(0, s.queriesAfterSaturation(), "nothing counts as over-search before the first saturation");
        for (int i = 0; i < 3; i++) s.observe("r" + i + " y", urls("b.org"));   // second saturation
        assertTrue(s.exhausted(), "two saturations = the patch is empty");
        assertEquals(3, s.queriesAfterSaturation(), "queries after the first saturation are the over-search index");
        assertEquals(7, s.queries());
    }

    @Test
    void axisPrefersLanguageWhenNamedAndUntried_thenSourceType_thenAbstraction() {
        SearchSteer s = new SearchSteer().focus("what do Japanese sources say about keigo?");
        s.observe("keigo subtitles honorifics", urls("blog.example.org", "medium.com", "note.com"));
        assertTrue(s.axis().contains("IN Japanese"), s.axis());
        // after a JA-script query the language axis is exhausted → source type
        s.observe("敬語 字幕 翻訳", urls("blog2.example.org", "qiita.com", "zenn.dev"));
        assertTrue(s.axis().contains("source TYPE"), s.axis());
        // after a scholarly host appears → abstraction
        s.observe("keigo subtitling study", urls("www.jstage.jst.go.jp", "arxiv.org", "x.org"));
        assertTrue(s.axis().contains("abstraction"), s.axis());
    }

    @Test
    void scriptsAndTermsAreDetected() {
        assertEquals("cjk", SearchSteer.script("字幕 敬語"));
        assertEquals("latin", SearchSteer.script("keigo subtitles"));
        assertEquals("hangul", SearchSteer.script("자막 존댓말"));
        assertEquals(1.0, SearchSteer.jaccard(SearchSteer.terms("keigo subtitle film"), SearchSteer.terms("film subtitle keigo")));
        assertEquals("example.org", SearchSteer.host("https://www.example.org/x?y"));
    }
}
