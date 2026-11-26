package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.codezaiku.tools.GivenFromProjectFilesTest.Says;
import static org.codezaiku.tools.GivenFromProjectFilesTest.persons;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who gives a host to a sandboxed command (the owner's rule of 2026-11-24): only what the person typed. A file of the project, the
 * instruction file included, a search's result, a page and another server's answer give an address to web_fetch and nothing to a
 * command: opening a host to a command opens all of it, and a README's link to github.com would open GitHub to every command. Nothing
 * here opens a connection.
 */
class GivenToCommandsTest {
    static final ObjectMapper M = new ObjectMapper();

    static JsonNode a(String json) throws Exception { return M.readTree(json); }

    @Test
    void thePersonsWordsGiveAHostToACommandWithItsPort() {
        long n = System.nanoTime();
        String named = "data-" + n + ".example.org", bare = "bare-" + n + ".example.net", ported = "api-" + n + ".example.io";
        assertFalse(GivenAddresses.givenToCommands(named, 443));
        GivenAddresses.personSaid("Read the table at https://" + named + "/t.csv, the notes on " + bare + "/notes and the service at http://" + ported + ":8443/v1");
        assertTrue(GivenAddresses.givenToCommands(named, 443), "an address with its scheme: its scheme's port");
        assertFalse(GivenAddresses.givenToCommands(named, 80), "not another port");
        assertTrue(GivenAddresses.givenToCommands("www." + named, 443), "the same site with www, as for web_fetch");
        assertTrue(GivenAddresses.givenToCommands(bare, 443) && GivenAddresses.givenToCommands(bare, 80), "an address without its scheme: 443 and 80");
        assertTrue(GivenAddresses.givenToCommands(ported, 8443) && !GivenAddresses.givenToCommands(ported, 443), "the port written");
        assertTrue(GivenAddresses.givenToCommands(named.toUpperCase() + ".", 443), "a host as a tool may write it");
    }

    @Test
    void aReadmeNamingAHostOpensItToWebFetchAndLeavesItRefusedForACommand(@TempDir Path root) throws Exception {
        long n = System.nanoTime();
        String host = "docs-" + n + ".example.org";
        persons(root, "README.md", "The format: https://" + host + "/guide\n");
        persons(root, "AGENTS.md", "Fetch https://agents-" + n + ".example.org/rules first.\n");
        ToolRegistry r = ToolRegistry.standard(root);
        r.execute("read_file", a("{\"path\":\"README.md\"}"));
        r.execute("read_file", a("{\"path\":\"AGENTS.md\"}"));
        r.execute("search_code", a("{\"pattern\":\"https\"}"));
        assertTrue(GivenAddresses.given("https://" + host + "/guide"), "the instrument: the README's link is open to web_fetch");
        assertFalse(GivenAddresses.givenToCommands(host, 443), "and refused for a command");
        assertFalse(GivenAddresses.givenToCommands("agents-" + n + ".example.org", 443), "an instruction file is its author's words, not the person's");
        GivenAddresses.personSaid("use the format at https://" + host + "/guide");
        assertTrue(GivenAddresses.givenToCommands(host, 443), "the person's own message naming it lets a command in");
    }

    @Test
    void aSearchResultAPageAndAnotherServerGiveWebFetchAnAddressAndACommandNothing() throws Exception {
        long n = System.nanoTime();
        String found = "found-" + n + ".example.org", linked = "linked-" + n + ".example.org", served = "served-" + n + ".example.org";
        ToolRegistry r = new ToolRegistry().add(new Says("web_search", "1. A page — https://" + found + "/page"))
                .add(new Says("web_fetch", "source: https://given.example.org\nsee https://" + linked + "/next"));
        r.execute("web_search", a("{\"query\":\"ferry\"}"));
        r.execute("web_fetch", a("{\"url\":\"https://given.example.org\"}"));
        GivenAddresses.linksOf("<a href=\"https://" + linked + "/more\">more</a>", "https://given.example.org/");
        GivenAddresses.received("an answer of another server: https://" + served + "/x");
        GivenAddresses.receivedWhole("https://" + served + "/redirected");
        assertTrue(GivenAddresses.given("https://" + found + "/page"), "the instrument: the search's result is given to web_fetch");
        for (String host : new String[]{found, linked, served})
            assertFalse(GivenAddresses.givenToCommands(host, 443), host + " gives a command nothing");
    }
}
