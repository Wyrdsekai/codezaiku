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

    /** A server that misbehaves the ways real ones do: pages its tools, logs a lot, asks us things, goes silent. */
    private static String awkward(java.nio.file.Path dir) throws java.io.IOException {
        java.nio.file.Path f = dir.resolve("awkward-mcp.py");
        java.nio.file.Files.writeString(f, """
                import json, sys, time
                def send(o): print(json.dumps(o), flush=True)
                for line in sys.stdin:
                    try: m = json.loads(line)
                    except Exception: continue
                    mid = m.get("id"); meth = m.get("method","")
                    if meth == "" : continue            # a reply to OUR ping: nothing to do
                    def reply(result): send({"jsonrpc":"2.0","id":mid,"result":result})
                    if meth == "initialize":
                        sys.stderr.write(("log line " * 40 + "\\n") * 600); sys.stderr.flush()   # ~220 KB: more than a pipe holds
                        reply({"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{"name":"awkward","version":"0"}})
                    elif meth == "tools/list":
                        cur = (m.get("params") or {}).get("cursor")
                        if cur is None: reply({"tools":[{"name":"one","description":"","inputSchema":{"type":"object"}}],"nextCursor":"p2"})
                        elif cur == "p2": reply({"tools":[{"name":"two","description":""}],"nextCursor":"p3"})
                        else: reply({"tools":[{"name":"three","description":""}]})
                    elif meth == "tools/call":
                        name = m["params"]["name"]; args = m["params"]["arguments"]
                        if name == "silent": time.sleep(30)
                        elif name == "structured": reply({"content":[],"structuredContent":{"rows":3,"ok":True}})
                        elif name == "args": reply({"content":[{"type":"text","text":json.dumps(args, sort_keys=True)}]})
                        elif name == "asks":
                            send({"jsonrpc":"2.0","id":"srv-1","method":"roots/list"})
                            back = json.loads(sys.stdin.readline())
                            send({"jsonrpc":"2.0","id":"srv-2","method":"sampling/createMessage","params":{}})
                            back2 = json.loads(sys.stdin.readline())
                            reply({"content":[{"type":"text","text":json.dumps([back.get("result"), back2.get("error",{}).get("code")])}]})
                        elif name == "slow":
                            for i in range(3):
                                time.sleep(1.5); send({"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":i}})
                            reply({"content":[{"type":"text","text":"done after progress"}]})
                """);
        return f.toString();
    }

    @Test
    void aChattySilentPagingAskingServerIsHandled(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        var m = new ObjectMapper();
        long t0 = System.nanoTime();
        try (McpClient c = new McpClient("awkward", List.of("python3", awkward(tmp)), 2)) {
            // 220 KB of stderr before the first reply: undrained, the server would block on its own logging and initialize would never answer
            assertTrue(c.stderrTail().contains("log line"));
            assertEquals(List.of("one", "two", "three"), c.listTools().stream().map(McpClient.RemoteTool::name).toList(), "every page");
            assertEquals("{\"ok\":true,\"rows\":3}", m.readTree(c.callTool("structured", m.createObjectNode())).toString().replace("\"rows\":3,\"ok\":true", "\"ok\":true,\"rows\":3"));
            var args = m.createObjectNode().put("query", "x"); args.putNull("limit"); args.putObject("opts").putNull("after").put("deep", true); args.putArray("list").addNull().add(1);
            assertEquals("{\"list\": [null, 1], \"opts\": {\"deep\": true}, \"query\": \"x\"}", c.callTool("args", args), "object nulls dropped at every depth, array nulls kept");
            assertEquals("[{\"roots\": []}, -32601]", c.callTool("asks", m.createObjectNode()), "a request from the server is answered: roots empty, sampling refused");
            assertEquals("done after progress", c.callTool("slow", m.createObjectNode()), "4.5 s of work against a 2 s timeout: each progress notification pushes the deadline out");
            long before = System.nanoTime();
            String out = c.callTool("silent", m.createObjectNode());
            long waited = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - before);
            assertTrue(out.startsWith("ERROR: no response from mcp server awkward within 2s"), out);
            assertTrue(waited <= 6, "the wait is a real timeout, it took " + waited + " s");
        }
    }

    @Test
    void aServerThatDiesSaysWhy(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path f = tmp.resolve("dies.py");
        java.nio.file.Files.writeString(f, "import sys\nsys.stderr.write('no API key configured\\n')\nsys.exit(3)\n");
        var e = assertThrows(java.io.IOException.class, () -> new McpClient("dies", List.of("python3", f.toString()), 5));
        assertTrue(e.getMessage().contains("did not answer initialize") && e.getMessage().contains("no API key configured"), e.getMessage());
    }
}
