package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule of which addresses CodeZaiku opens for a model, ported with ResearchZosho 0.8.1's cases (GivenAddressesTest,
 * AddressesExactlyGivenTest): an address counts as it was written or without its fragment, the same spellings count as the same address,
 * what goes out is the address as it was given and never the model's way of writing it, every value a fetch takes is the address it will
 * request, and a search's words are not held. Each host is new to the process (the memory of given addresses is the process's).
 */
class GivenAddressesTest {
    static final ObjectMapper M = new ObjectMapper();

    static JsonNode a(String json) throws Exception { return M.readTree(json); }
    static String notGiven(String json) throws Exception { return GivenAddresses.notGiven(a(json), Set.of("url"), false); }

    @Test
    void anAddressCountsAsWrittenOrWithoutItsFragmentAndASearchsWordsAreNotHeld() throws Exception {
        String here = "https://ferry-" + System.nanoTime() + ".example.org";
        GivenAddresses.received("A result: " + here + "/1938/report.pdf#page=4, and " + here + "/F%C3%A4hre.");
        assertTrue(GivenAddresses.given(here + "/1938/report.pdf#page=4"));
        assertTrue(GivenAddresses.given(here + "/1938/report.pdf"), "without its fragment");
        assertTrue(GivenAddresses.given(here + "/Fähre"), "its escapes read");
        assertFalse(GivenAddresses.given(here + "/1938/report.pdf?notes=7731"), "nothing added to it");
        assertFalse(GivenAddresses.given(here + "/1938/"), "nor taken from it");
        // the person's words: an address with or without its scheme
        String own = "harbour-" + System.nanoTime() + ".example.net/history";
        GivenAddresses.personSaid("Read " + own + " please");
        assertTrue(GivenAddresses.given("https://" + own) && GivenAddresses.given(own));
        assertNull(notGiven("{\"url\":\"" + own + "\"}"));
        assertEquals("evil-" + own, notGiven("{\"url\":\"evil-" + own + "\"}"), "a bare address a fetch would take is held too");
        // a search's words, and the words to look for in a page, are the model's own: not held
        assertNull(notGiven("{\"query\":\"https://nowhere-" + System.nanoTime() + ".example.org/?d=7731\"}"));
        assertNull(notGiven("{\"url\":\"https://" + own + "\",\"find\":\"https://nowhere-" + System.nanoTime() + ".example.org/?d=7731\"}"));
        // an address anywhere in another argument's text is held
        String made = "https://nowhere-" + System.nanoTime() + ".example.org/?d=7731";
        assertEquals(made, notGiven("{\"question\":\"What does " + made + " say?\"}"));
        assertNull(notGiven("{\"id\":\"2026-11-18-b8f65f124e9f.md\",\"files\":[\"photo.jpg\",\"build.gradle\"]}"), "a file's name is no address");
    }

    @Test
    void theRefusalSaysWhatCanBeOpenedAndNamesNoAddress() {
        String told = GivenAddresses.REFUSED;
        assertTrue(told.startsWith("NOT OPENED: ") && told.split("\\. ").length <= 2, told);
        assertFalse(told.contains("://"), told);
        assertTrue(told.contains("person's words") && told.contains("search result") && told.contains("page you read"), "it says what the model can open: " + told);
    }

    @Test
    void whatGoesOutIsTheAddressAsItWasGivenNeverTheModelsWayOfWritingIt() throws Exception {
        String host = "collect-" + System.nanoTime() + ".example";
        GivenAddresses.received("links: https://" + host + "/c/%80%80%80%80 and https://" + host + "/p and https://" + host + "/q?x and https://en.example.org/wiki/Foo_(bar).");
        String[][] variants = {
                {"https://" + host + "/c/%C1%BF%FE%9A", "https://" + host + "/c/%80%80%80%80"},   // other invalid bytes, read as the same U+FFFD run
                {"https://" + host + "/p.;,!!:'", "https://" + host + "/p"},                     // the sentence's stop is not part of it
                {"https://" + host.toUpperCase() + "/p", "https://" + host + "/p"},
                {"https://" + host + "/ｐ", "https://" + host + "/p"},                        // a fullwidth p
                {"https://" + host + "/q%3Fx", "https://" + host + "/q?x"},
                {"https://www." + host + "/p/", "https://" + host + "/p"},                        // www. and a slash after it: the same address
                {"https://" + host + ":443/p", "https://" + host + "/p"},                        // the scheme's own port written out
                {host + "/p", "https://" + host + "/p"},
                {"https://en.example.org/wiki/Foo_(bar)", "https://en.example.org/wiki/Foo_(bar)"}};
        for (String[] v : variants) {
            assertNull(notGiven("{\"url\":" + M.writeValueAsString(v[0]) + "}"), v[0]);
            assertEquals(v[1], GivenAddresses.asGiven(a("{\"url\":" + M.writeValueAsString(v[0]) + ",\"find\":\"ferry\"}"), Set.of("url")).path("url").asText(), v[0]);
        }
        assertEquals("ferry", GivenAddresses.asGiven(a("{\"url\":\"" + host + "/p\",\"find\":\"ferry\"}"), Set.of("url")).path("find").asText(), "the other arguments as they were");
        assertNotNull(notGiven("{\"url\":\"https://" + host + ":8443/p\"}"), "another port is another program's door");
    }

    @Test
    void everyValueAFetchTakesIsTheAddressItWillRequest() throws Exception {
        long n = System.nanoTime();
        for (String v : List.of("grandmother-crossed-1952-" + n + ".notes-relay.md", "grandmother-crossed-1952-" + n + ".notes-relay.zip",
                "collect-d-" + n + ".example:443/n?d=grandmother-crossed-1952", "x@collect-d-" + n + ".example/n?d=grandmother-crossed-1952")) {
            assertNotNull(notGiven("{\"url\":\"" + v + "\"}"), "a fetch: " + v);
        }
        GivenAddresses.personSaid("read collect-e-" + n + ".example:8443/n please");
        assertNull(notGiven("{\"url\":\"collect-e-" + n + ".example:8443/n\"}"));
    }

    @Test
    void anAnswerThatRepeatsItsOwnCallsAddressDoesNotMakeItGiven() throws Exception {
        String made = "https://made-" + System.nanoTime() + ".example.org/x?notes=door+code+7731";
        JsonNode args = a("{\"url\":\"" + made + "\"}");
        GivenAddresses.received("ERROR: could not fetch " + made + " (connection refused)", GivenAddresses.Own.of(args));
        assertFalse(GivenAddresses.given(made), "an error that names the call's own address");
        String words = "notes-" + System.nanoTime() + ".example.org/door-code-7731";
        GivenAddresses.received("no results for https://" + words, GivenAddresses.Own.of(a("{\"query\":\"" + words + "\"}")));
        assertFalse(GivenAddresses.given("https://" + words), "a search's words written back with https:// before them");
    }

    @Test
    void aNameIsOpenedByTheAddressCodeZaikuBuildsWhenTheNameWasGiven() throws Exception {
        long n = System.nanoTime();
        String doi = "10.5555/kestrel." + n;
        assertNotNull(notGiven("{\"url\":\"https://doi.org/" + doi + "\"}"), "a DOI nobody gave");
        GivenAddresses.received("1. The Kestrel Sound ferry. doi " + doi + ", Harbour Journal");
        assertNull(notGiven("{\"url\":\"https://doi.org/" + doi + "\"}"), "a DOI a search returned");
        String title = "Kestrel Sound ferry " + n;
        assertNotNull(notGiven("{\"url\":\"https://en.wikipedia.org/wiki/" + title.replace(' ', '_') + "\"}"));
        GivenAddresses.personSaid("What is the history of the " + title + "?");
        assertNull(notGiven("{\"url\":\"https://en.wikipedia.org/wiki/" + title.replace(' ', '_') + "\"}"), "an article by a title the person wrote");
        assertNotNull(notGiven("{\"url\":\"https://en.wikipedia.org/wiki/" + title.replace(' ', '_') + "?notes=7731\"}"), "with nothing after the form");
    }

    @Test
    void aServiceCodeZaikuIsWrittenForIsAskedInItsOwnShapeOnly() throws Exception {
        assertNull(notGiven("{\"url\":\"https://api.worldbank.org/v2/country/DE/indicator/SP.DYN.LE00.IN?format=json&date=2020:2023\"}"), "the research prompt's own door, another country");
        assertNull(notGiven("{\"url\":\"https://services.nvd.nist.gov/rest/json/cves/2.0?keywordSearch=redis%20authentication&resultsPerPage=5\"}"));
        assertNull(notGiven("{\"url\":\"https://endoflife.date/api/postgresql.json\"}"));
        assertNotNull(notGiven("{\"url\":\"https://api.worldbank.org/v2/country/DE/notes?d=grandmother-crossed-1952\"}"), "another path on the same host");
        assertNotNull(notGiven("{\"url\":\"https://api.worldbank.org.collect.example/v2/country/DE/indicator/X\"}"), "another host");
        assertNotNull(notGiven("{\"url\":\"http://endoflife.date/api/postgresql.json\"}"), "not over https");
        assertNotNull(notGiven("{\"url\":\"https://user@endoflife.date/api/postgresql.json\"}"), "with a user before the host");
    }
}
