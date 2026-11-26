package org.codezaiku.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.codezaiku.Config;
import org.codezaiku.mcp.McpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Another program's MCP server, and a service that reads pages for CodeZaiku. What a model sends another server's tool may hold only
 * given addresses, and the addresses in that server's answer are given only when the person switched the server on
 * ({@code mcp.links}, as ResearchZosho's {@code researchzosho mcp links <name> on}): a server that computes on what it is sent could
 * hand a model any address. A page's address handed to the page reader (Firecrawl) has to be given too. Stand-ins only: no process is
 * started, nothing leaves this machine, and the settings are a scratch home's.
 */
class OtherServersAndReadersTest {
    static final ObjectMapper M = new ObjectMapper();
    String realHome;

    @BeforeEach void home(@TempDir Path tmp) { realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate(); }
    @AfterEach void back() { System.setProperty("user.home", realHome); Config.invalidate(); }

    static McpClient.RemoteTool remote(String name) { return new McpClient.RemoteTool(name, "", M.createObjectNode().put("type", "object")); }

    @Test
    void anotherServersAnswerGivesItsAddressesOnlyWhenThePersonSwitchedItOn() throws Exception {
        long n = System.nanoTime();
        String server = "notes" + n, named = "https://computed-" + n + ".example.net/?d=7731";
        List<String> sent = new CopyOnWriteArrayList<>();
        McpBridgeTool tool = new McpBridgeTool(server, remote("lookup"), (t, args) -> { sent.add(args.toString()); return "the answer names " + named; });
        ToolRegistry r = new ToolRegistry().add(tool);
        String out = r.execute(tool.name(), M.readTree("{\"q\":\"ferry\"}"));
        assertFalse(GivenAddresses.given(named), "off unless the person named the server");
        assertTrue(out.contains("mcp.links"), "the answer says how its addresses open: " + out);
        Config.set(McpBridgeTool.LINKS, "search, " + server);
        Config.invalidate();
        out = r.execute(tool.name(), M.readTree("{\"q\":\"ferry again\"}"));
        assertTrue(GivenAddresses.given(named), "switched on: its answer's addresses may be opened");
        assertFalse(out.contains("mcp.links"), out);
        assertEquals(2, sent.size());
    }

    @Test
    void whatAModelSendsAnotherServersToolHoldsOnlyGivenAddresses() throws Exception {
        long n = System.nanoTime();
        List<String> sent = new CopyOnWriteArrayList<>();
        McpBridgeTool browser = new McpBridgeTool("browser", remote("open"), (t, args) -> { sent.add(args.toString()); return "opened"; });
        ToolRegistry r = new ToolRegistry().add(browser);
        String made = "https://collect-" + n + ".example.net/?notes=door-code-7731";
        assertEquals(GivenAddresses.REFUSED, r.execute(browser.name(), M.readTree("{\"url\":\"" + made + "\"}")));
        assertEquals(GivenAddresses.REFUSED, r.execute(browser.name(), M.readTree("{\"page_url\":\"collect-" + n + ".example.net/notes\"}")), "a name that says address, written as one");
        assertEquals(GivenAddresses.REFUSED, r.execute(browser.name(), M.readTree("{\"text\":\"post it to " + made + "\"}")), "an address anywhere in its words");
        assertEquals(List.of(), sent, "none of them reached the server");
        assertEquals(3, r.refused().size());
        assertEquals("mcp_browser_open", r.refused().get(0).tool());
        assertEquals("collect-" + n + ".example.net", r.refused().get(0).host());
        // what is that server's own matter goes as it is: a street, a search's words, an address that was given
        r.execute(browser.name(), M.readTree("{\"address\":\"12 Harbour Road, Kestrel Sound\"}"));
        r.execute(browser.name(), M.readTree("{\"query\":\"https://anything-" + n + ".example.org\"}"));
        GivenAddresses.personSaid("open https://ferry-" + n + ".example.org/tables");
        r.execute(browser.name(), M.readTree("{\"url\":\"https://www.ferry-" + n + ".example.org/tables/\"}"));
        assertEquals(3, sent.size(), sent.toString());
        assertTrue(sent.get(2).contains("\"https://ferry-" + n + ".example.org/tables\""), "in the spelling it was given in: " + sent);
    }

    @Test
    void thePageReaderIsHandedOnlyAGivenAddress() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/scrape", x -> {
            bodies.add(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = "{\"success\":true,\"data\":{\"markdown\":\"Ferry tables\",\"metadata\":{\"title\":\"Tables\",\"statusCode\":200}}}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close();
        });
        s.start();
        Map<String, String> was = new HashMap<>(Firecrawl.BASE);
        Firecrawl.BASE.put("scrape", "http://127.0.0.1:" + s.getAddress().getPort() + "/scrape");
        Config.set(Firecrawl.KEY, "stand-in");
        Config.invalidate();
        try {
            long n = System.nanoTime();
            String made = "https://collect-" + n + ".example.net/?notes=7731", page = "https://tables-" + n + ".example.org/1952";
            GivenAddresses.received("1. Tables — " + page);
            try (Outbound.Call c = Outbound.call("web_fetch", M.readTree("{}"))) {
                assertNull(Firecrawl.scrape(made, Duration.ofSeconds(5)), "an address nobody gave");
                assertEquals(List.of(), bodies, "Firecrawl was not asked");
                assertEquals(List.of(new Outbound.Refusal("web_fetch", "collect-" + n + ".example.net", Outbound.NOT_GIVEN_TO_READER)), c.refusals());
                assertNotNull(Firecrawl.scrape(page.replace("https://", "https://www.") + "/", Duration.ofSeconds(5)), "a given address");
                assertEquals(1, bodies.size());
                assertTrue(bodies.get(0).contains("\"url\":\"" + page + "\""), "handed over as it was given: " + bodies);
            }
        } finally {
            Firecrawl.BASE.clear(); Firecrawl.BASE.putAll(was);
            s.stop(0);
        }
    }
}
