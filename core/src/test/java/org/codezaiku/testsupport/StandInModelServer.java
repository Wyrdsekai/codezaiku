package org.codezaiku.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A model server on an ephemeral loopback port, for the tests of what a job asks before it starts: a llama.cpp server with slots, or
 * a llama-swap proxy in front of one, that finishes any job on its first turn. Every request's path is kept, so a test can count them
 * and prove what was never asked.
 */
public final class StandInModelServer implements AutoCloseable {

    private static final ObjectMapper J = new ObjectMapper();

    private final HttpServer server;
    private final String model;
    private final List<String> paths = new ArrayList<>();
    private volatile int slots = 2, working = 0;
    private volatile boolean proxy = false, showsSlots = true;
    private volatile String loaded = null, loadedState = "ready";

    private StandInModelServer(String model) throws IOException {
        this.model = model;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
        server.start();
    }

    /** A llama.cpp server serving {@code model}, two slots, both free. */
    public static StandInModelServer serving(String model) throws IOException { return new StandInModelServer(model); }

    /** An address where nothing answers: a port a server just gave back. */
    public static String nothingAt() throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = s.getAddress().getPort();
        s.stop(0);
        return "http://127.0.0.1:" + port;
    }

    /** {@code working} of {@code slots} calls in use. */
    public StandInModelServer slots(int working, int slots) { this.working = working; this.slots = slots; return this; }

    /** A server started without its slots page (llama.cpp's --no-slots): /slots answers 501. */
    public StandInModelServer noSlots() { this.showsSlots = false; return this; }

    /** A llama-swap proxy in front of the server, with {@code loadedModel} loaded in the given state, or nothing loaded when null. */
    public StandInModelServer proxy(String loadedModel, String state) { this.proxy = true; this.loaded = loadedModel; this.loadedState = state; return this; }

    public String address() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    /** Every path asked so far, in order. */
    public List<String> paths() { synchronized (paths) { return List.copyOf(paths); } }

    public int requests() { synchronized (paths) { return paths.size(); } }

    @Override public void close() { server.stop(0); }

    private void answer(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath();
        synchronized (paths) { paths.add(path); }
        ex.getRequestBody().readAllBytes();
        int code = 200;
        String body;
        String upstream = "/upstream/" + model.replace(" ", "%20") + "/";
        if (path.equals("/v1/models")) body = "{\"object\":\"list\",\"data\":[{\"id\":" + J.writeValueAsString(model) + "}]}";
        else if (path.equals("/running") && proxy) {
            ObjectNode o = J.createObjectNode();
            ArrayNode r = o.putArray("running");
            if (loaded != null) r.addObject().put("model", loaded).put("state", loadedState);
            body = o.toString();
        } else if (path.equals("/slots") && !proxy) {
            if (showsSlots) body = slotsJson(); else { code = 501; body = "{\"error\":{\"message\":\"This server does not support slots endpoint.\"}}"; }
        } else if (proxy && path.equals(upstream + "slots")) body = slotsJson();
        else if (path.equals("/v1/chat/completions") || (proxy && path.equals(upstream + "v1/chat/completions"))) {
            ObjectNode m = J.createObjectNode().put("role", "assistant");
            ObjectNode c = m.putArray("tool_calls").addObject(); c.put("id", "c1"); c.put("type", "function");
            c.putObject("function").put("name", "task_done").put("arguments", "{\"summary\":\"nothing to do\"}");
            ObjectNode out = J.createObjectNode();
            out.putArray("choices").addObject().set("message", m);
            out.putObject("usage").put("prompt_tokens", 1000).put("completion_tokens", 50).put("total_tokens", 1050);
            body = out.toString();
        } else { code = 404; body = "{\"error\":\"not found\"}"; }
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private String slotsJson() {
        ArrayNode a = J.createArrayNode();
        for (int i = 0; i < slots; i++) a.addObject().put("id", i).put("n_ctx", 8192).put("is_processing", i < working);
        return a.toString();
    }
}
