package org.codezaiku.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Crossref and OpenAlex answers become rows with a DOI URL, a venue/year/author line, and merge one row per work. */
class ScholarSearchTest {

    static final String CROSSREF = """
        {"status":"ok","message":{"items":[
          {"DOI":"10.1177/000992287801700602","URL":"https://doi.org/10.1177/000992287801700602","title":["Ban on Lead-Containing Paint"],
           "container-title":["Clinical Pediatrics"],"issued":{"date-parts":[[1978,6]]},"type":"journal-article","author":[]},
          {"DOI":"10.1016/s0140-6736(04)16017-0","URL":"https://doi.org/10.1016/s0140-6736(04)16017-0","title":["MMR—responding to retraction"],
           "container-title":["The Lancet"],"issued":{"date-parts":[[2004]]},"type":"journal-article",
           "author":[{"given":"Richard","family":"Horton"},{"family":"Smith"},{"family":"Jones"},{"family":"Fourth"}]}
        ]}}
        """;
    static final String OPENALEX = """
        {"results":[
          {"display_name":"MMR—responding to retraction","doi":"https://doi.org/10.1016/S0140-6736(04)16017-0",
           "publication_year":2004,"primary_location":{"landing_page_url":"https://doi.org/10.1016/s0140-6736(04)16017-0","source":{"display_name":"The Lancet"}},
           "authorships":[{"author":{"display_name":"Richard Horton"}}]},
          {"display_name":"Melatonin decreases delirium in elderly patients: A randomized, placebo-controlled trial","doi":"https://doi.org/10.1002/gps.2582",
           "publication_year":2010,"primary_location":{"landing_page_url":"https://doi.org/10.1002/gps.2582","source":{"display_name":"International Journal of Geriatric Psychiatry"}},
           "authorships":[{"author":{"display_name":"Tareef Alaama"}},{"author":{"display_name":"Christopher D. Brymer"}}]}
        ]}
        """;

    @Test
    void crossrefRowsCarryTheDoiVenueYearAndAuthors() {
        List<ScholarSearch.Row> rows = ScholarSearch.parseCrossref(CROSSREF);
        assertEquals(2, rows.size());
        assertEquals("Ban on Lead-Containing Paint", rows.get(0).title());
        assertEquals("https://doi.org/10.1177/000992287801700602", rows.get(0).url());
        assertEquals("Clinical Pediatrics, 1978 (journal article)", rows.get(0).snippet());
        assertEquals("The Lancet, 2004: Horton, Smith, Jones et al. (journal article)", rows.get(1).snippet(), "three authors, then et al.");
    }

    @Test
    void openAlexRowsCarryTheLandingPageAndSource() {
        List<ScholarSearch.Row> rows = ScholarSearch.parseOpenAlex(OPENALEX);
        assertEquals(2, rows.size());
        assertEquals("10.1002/gps.2582", rows.get(1).doi(), "the DOI without the resolver prefix");
        assertEquals("International Journal of Geriatric Psychiatry, 2010: Tareef Alaama, Christopher D. Brymer", rows.get(1).snippet());
    }

    @Test
    void theSameWorkFromBothSourcesIsOneRow() {
        var a = ScholarSearch.parseCrossref(CROSSREF).get(1);
        var b = ScholarSearch.parseOpenAlex(OPENALEX).get(0);
        assertEquals(ScholarSearch.key(a), ScholarSearch.key(b), "DOIs match regardless of case: " + ScholarSearch.key(a) + " / " + ScholarSearch.key(b));
    }

    @Test
    void renderedRowsAreNumberedWithTheirUrl() {
        String out = ScholarSearch.render("mmr", " (test)", ScholarSearch.parseCrossref(CROSSREF), 8);
        assertTrue(out.contains("1. Ban on Lead-Containing Paint") && out.contains("https://doi.org/10.1177/000992287801700602"), out);
    }

    @Test
    void brokenAnswersAreEmpty() {
        assertTrue(ScholarSearch.parseCrossref("<html>").isEmpty());
        assertTrue(ScholarSearch.parseOpenAlex("{}").isEmpty());
    }
}
