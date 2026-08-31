package org.codezaiku.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The client half of MCP, against a minimal scripted stdio server. */
class McpClientTest {

    /** The minimal scripted server, written by the test itself so the suite is self-contained. */
    private static String mini(java.nio.file.Path dir) throws java.io.IOException {
        java.nio.file.Path f = dir.resolve("mini-mcp.py");
        java.nio.file.Files.writeString(f, """
                import json, sys
                for line in sys.stdin:
                    try: m = json.loads(line)
                    except Exception: continue
                    mid = m.get("id"); meth = m.get("method","")
                    def reply(result): print(json.dumps({"jsonrpc":"2.0","id":mid,"result":result}), flush=True)
                    if meth == "initialize":
                        reply({"protocolVersion":"2024-11-05","capabilities":{"tools":{}},
                               "serverInfo":{"name":"mini","version":"0"}})
                    elif meth == "tools/list":
                        reply({"tools":[{"name":"echo","description":"Echo the message back.",
                               "inputSchema":{"type":"object","properties":{"message":{"type":"string"}},
                               "required":["message"]}}]})
                    elif meth == "tools/call":
                        msg = m["params"]["arguments"].get("message","")
                        reply({"content":[{"type":"text","text":"echo: "+msg}],"isError":False})
                """);
        return f.toString();
    }

    @Test
    void initializeListCallRoundTrip(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        try (McpClient c = new McpClient("mini", List.of("python3", mini(tmp)))) {
            var tools = c.listTools();
            assertEquals(1, tools.size());
            assertEquals("echo", tools.get(0).name());
            assertTrue(tools.get(0).schema().toString().contains("message"));
            String out = c.callTool("echo",
                    new ObjectMapper().createObjectNode().put("message", "hello mcp"));
            assertEquals("echo: hello mcp", out);
        }
    }

    @Test
    void aBrokenConfigEntryIsReportedNotFatal(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        var reports = new java.util.ArrayList<String>();
        var clients = McpClient.fromConfig("bad-entry-no-equals;ok=python3 " + mini(tmp),
                reports::add);
        assertEquals(1, clients.size(), "the good entry started");
        assertEquals(1, reports.size(), "the bad entry was reported");
        clients.forEach(McpClient::close);
    }
}
