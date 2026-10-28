package org.codezaiku.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.codezaiku.drive.SavedModels;

import java.io.IOException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The chat in the browser, for several projects under one server. Each project has its own loop — the terminal chat's, talking through a
 * {@link WebIo} — started when its page is first opened and kept for as long as the server runs, so a conversation survives a closed tab.
 * The page polls the loop's transcript, sends what the person types, and shows a question the chat asks as buttons. Moving to another
 * project is a link; "switch here" also carries where the person was, as a note the next message in the new project includes.
 *
 * <p>Listens on the loopback by default, where whoever reaches the port is the person. On the network it asks for a token once: the
 * browser keeps it as a cookie, and every request must carry it, because the page's buttons answer the questions that let the chat write
 * and run things in the projects.
 */
public final class WebChat {
    private static final ObjectMapper M = new ObjectMapper();

    /** One project's running chat. */
    static final class Loop {
        final String id; final Path root; volatile WebIo io = new WebIo(); volatile ChatRepl repl; volatile Thread thread; volatile String died; volatile long started;
        Loop(String id, Path root) { this.id = id; this.root = root; }
        String name() { Path n = root.getFileName(); return n == null ? root.toString() : n.toString(); }
        boolean running() { Thread t = thread; return t != null && t.isAlive(); }
        String state() { if (died != null) return "ended: " + died; if (!running()) return "not started"; return io.pending() == null ? "working" : io.pending().contains("[") || io.pending().contains(" / ") ? "asking you" : "ready"; }
    }

    private final Map<String, Loop> loops = new LinkedHashMap<>();
    private final String driveUrl, model; private final SavedModels.Entry entry; private final ChatConsent.Mode mode; private final int maxTurns;
    private final Path registry;
    private HttpServer server;
    /** Folders the kept list named that are no longer there, dropped from it. */
    private final List<String> skipped = new ArrayList<>();
    public List<String> skipped() { return skipped; }
    /** The token a request must carry (as the cookie {@code cz}), or null when none is asked for. */
    private volatile String token;

    /** {@code registry}: the file the project list is kept in (null keeps it in memory only). */
    public WebChat(List<Path> projects, String driveUrl, String model, SavedModels.Entry entry, ChatConsent.Mode mode, int maxTurns, Path registry) throws IOException {
        this.driveUrl = driveUrl; this.model = model; this.entry = entry; this.mode = mode; this.maxTurns = maxTurns; this.registry = registry;
        if (registry != null && Files.isRegularFile(registry)) for (String l : Files.readAllLines(registry, StandardCharsets.UTF_8)) {
            if (l.isBlank()) continue;
            try { add(Path.of(l.strip()), false); }
            catch (IllegalArgumentException e) { skipped.add(l.strip()); }   // a folder that is gone: left off the list, said at the start
        }
        for (Path p : projects) add(p, false);
        save();
    }

    /** Ask every request for this token, given once on the sign-in page; null asks for none. */
    public WebChat token(String token) { this.token = token == null || token.isBlank() ? null : token.strip(); return this; }
    public String token() { return token; }

    /** A fresh token: letters and digits a person can type, from a secure source. */
    public static String newToken() {
        String abc = "abcdefghjkmnpqrstuvwxyz23456789"; SecureRandom r = new SecureRandom(); StringBuilder b = new StringBuilder();
        for (int i = 0; i < 20; i++) { if (i > 0 && i % 5 == 0) b.append('-'); b.append(abc.charAt(r.nextInt(abc.length()))); }
        return b.toString();
    }

    /** The token kept in {@code file}, made on first use so it stays the same from one start to the next. */
    public static String tokenKept(Path file) throws IOException {
        if (Files.isRegularFile(file)) { String t = Files.readString(file, StandardCharsets.UTF_8).strip(); if (!t.isEmpty()) return t; }
        String t = newToken();
        Files.createDirectories(file.getParent()); Files.writeString(file, t + "\n", StandardCharsets.UTF_8);
        try { Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException | IOException ignored) { }
        return t;
    }

    private boolean signedIn(HttpExchange x) {
        String t = token; if (t == null) return true;
        for (String h : x.getRequestHeaders().getOrDefault("Cookie", List.of())) for (String c : h.split(";")) { String[] kv = c.strip().split("=", 2); if (kv.length == 2 && kv[0].equals("cz") && MessageDigest.isEqual(kv[1].getBytes(StandardCharsets.UTF_8), t.getBytes(StandardCharsets.UTF_8))) return true; }
        return false;
    }

    static String loginPage(boolean wrong) {
        return head("CodeZaiku") + "<nav id=\"side\"><p class=\"brand\">CodeZaiku</p></nav><main><header><b>Sign in</b></header><div class=\"home\">"
                + "<p>This chat can change files and run commands in your projects, so it asks who you are: the token it printed when it started" + (wrong ? ". <b>That was not it.</b>" : ".") + "</p>"
                + "<form method=\"post\" action=\"/login\" style=\"display:flex;gap:.6em;max-width:30em\"><input name=\"token\" type=\"password\" placeholder=\"the token\" autofocus required style=\"flex:1;font:inherit;padding:.5em .7em;border:1px solid var(--line);border-radius:.5em;background:var(--bg);color:var(--ink)\"><button>Enter</button></form>"
                + "<p class=\"note\">The token travels once, over this network, and the browser keeps it as a cookie for a month. On a network you do not trust, reach this machine over a tunnel such as Tailscale instead.</p></div></main></body></html>";
    }
    /** The project list, as the page shows it. */
    public synchronized List<Loop> projects() { return new ArrayList<>(loops.values()); }

    /** A project by its path; the one already listed when it is. */
    public synchronized Loop add(Path root, boolean persist) throws IOException {
        Path r = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(r)) throw new IllegalArgumentException("not a folder: " + r);
        for (Loop l : loops.values()) if (l.root.equals(r)) return l;
        String base = r.getFileName() == null ? "root" : r.getFileName().toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        String id = base.isEmpty() ? "project" : base; int n = 2; while (loops.containsKey(id)) id = base + "-" + n++;
        Loop l = new Loop(id, r); loops.put(id, l);
        if (persist) save();
        return l;
    }

    /** A project taken off the list; its chat, if running, ends with the server. */
    public synchronized boolean remove(String id) throws IOException {
        Loop l = loops.remove(id);
        if (l == null) return false;
        Thread t = l.thread; if (t != null && t.isAlive()) t.interrupt();
        save();
        return true;
    }

    private void save() throws IOException {
        if (registry == null) return;
        Files.createDirectories(registry.getParent());
        StringBuilder b = new StringBuilder(); for (Loop l : loops.values()) b.append(l.root).append('\n');
        Files.writeString(registry, b.toString(), StandardCharsets.UTF_8);
    }

    /** The loop of a project, started on first use. */
    synchronized Loop loop(String id) {
        Loop l = loops.get(id);
        if (l == null) return null;
        if (!l.running() && l.died == null) {
            l.started = System.nanoTime();   // a page compares it: a chat started again begins a new transcript
            List<String[]> earlier = ChatSession.list(l.root);
            if (!earlier.isEmpty()) {   // the page opens on the project's last conversation, and the chat continues it
                String[] e = earlier.get(0);
                l.io.history("notify", "Earlier conversation, continued: " + e[1] + " (" + e[2] + (e[2].equals("1") ? " turn" : " turns") + ")");
                for (String[] row : rows(ChatSession.storeDir(l.root).resolve(e[0] + ".log.jsonl"))) l.io.history(row[0].equals("user") ? "me" : row[0].equals("error") ? "notify" : "out", row[1]);
                l.io.say("/resume");
            }
            ChatRepl repl = new ChatRepl(l.root, driveUrl, model, mode, maxTurns, null).entry(entry).io(l.io);
            l.repl = repl;
            Thread t = new Thread(() -> {
                try { repl.run(); l.died = "the chat ended"; }
                catch (Throwable e) { l.died = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()); l.io.notifyLine("! the chat ended: " + l.died); }
            }, "codezaiku-web-" + id);
            t.setDaemon(true); l.thread = t; t.start();
        }
        return l;
    }

    /** The said and the answered of a conversation's transcript, in order: role and text, the tool rows left out. */
    static List<String[]> rows(Path transcript) {
        List<String[]> out = new ArrayList<>();
        if (!Files.isRegularFile(transcript)) return out;
        try {
            for (String line : Files.readAllLines(transcript, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonNode n; try { n = M.readTree(line); } catch (IOException e) { continue; }
                String role = n.path("role").asText(""), text = n.path("text").asText("");
                if (!text.isBlank() && (role.equals("user") || role.equals("agent") || role.equals("plan") || role.equals("error"))) out.add(new String[]{role, text});
            }
        } catch (IOException ignored) { }
        return out;
    }

    public HttpServer start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "codezaiku-web-http"); t.setDaemon(true); return t; }));
        server.start();
        return server;
    }
    public int port() { return server.getAddress().getPort(); }
    public void stop() { if (server != null) server.stop(0); }

    private void handle(HttpExchange x) throws IOException {
        try {
            String path = x.getRequestURI().getPath(), method = x.getRequestMethod();
            Map<String, String> q = query(x.getRequestURI().getRawQuery());
            Map<String, String> form = "POST".equals(method) ? query(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)) : Map.of();
            if (path.equals("/login")) {   // the token, once; the browser keeps it
                if ("POST".equals(method)) {
                    String given = form.getOrDefault("token", "").strip();
                    if (token != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) { x.getResponseHeaders().add("Set-Cookie", "cz=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000"); redirect(x, "/"); }
                    else send(x, 403, "text/html; charset=utf-8", loginPage(true));
                } else send(x, 200, "text/html; charset=utf-8", loginPage(false));
                return;
            }
            if (!path.equals("/health") && !signedIn(x)) {
                if (path.startsWith("/chat/") && !path.equals("/chat")) send(x, 401, "application/json", "{\"error\":\"sign in first\"}");
                else redirect(x, "/login");
                return;
            }
            switch (path) {
                case "/health" -> send(x, 200, "application/json", "{\"ok\":true}");
                case "/favicon.ico" -> { x.sendResponseHeaders(204, -1); x.close(); }
                case "/" -> send(x, 200, "text/html; charset=utf-8", home());
                case "/projects" -> { ArrayNode a = M.createArrayNode(); for (Loop l : projects()) { ObjectNode o = a.addObject(); o.put("id", l.id); o.put("name", l.name()); o.put("state", l.state()); o.put("dot", dotClass(l.state())); } send(x, 200, "application/json", a.toString()); }
                case "/projects/remove" -> { remove(form.getOrDefault("project", "")); redirect(x, "/"); }
                case "/projects/add" -> { try { Loop l = add(Path.of(form.getOrDefault("path", "").strip()), true); redirect(x, "/chat?project=" + enc(l.id)); } catch (IllegalArgumentException e) { send(x, 400, "text/plain; charset=utf-8", e.getMessage()); } }
                case "/chat" -> { Loop l = loop(q.getOrDefault("project", "")); if (l == null) redirect(x, "/"); else send(x, 200, "text/html; charset=utf-8", chatPage(l)); }
                case "/chat/log" -> { Loop l = loops.get(q.getOrDefault("project", "")); if (l == null) { send(x, 404, "application/json", "{\"error\":\"no such project\"}"); break; } long since = 0; try { since = Long.parseLong(q.getOrDefault("since", "0")); } catch (NumberFormatException ignored) { } send(x, 200, "application/json", log(l, since)); }
                case "/chat/say" -> { Loop l = loop(form.getOrDefault("project", "")); if (l == null) { send(x, 404, "text/plain", "no such project"); break; } l.io.say(form.getOrDefault("say", "")); send(x, 200, "application/json", "{\"ok\":true}"); }
                case "/chat/restart" -> {   // a chat that ended (its model gone, an error) starts again on the same transcript
                    Loop l = loops.get(form.getOrDefault("project", ""));
                    if (l != null && !l.running()) { l.died = null; l.thread = null; loop(l.id); }
                    redirect(x, l == null ? "/" : "/chat?project=" + enc(l.id));
                }
                case "/chat/stop" -> { Loop l = loops.get(form.getOrDefault("project", "")); send(x, 200, "application/json", "{\"stopped\":" + (l != null && l.io.stop()) + "}"); }
                case "/chat/switch" -> {   // to another project, carrying where the person was
                    Loop from = loops.get(form.getOrDefault("from", "")), to = loop(form.getOrDefault("to", ""));
                    if (to == null) { redirect(x, "/"); break; }
                    if (from != null && from != to) { String was = from.repl == null ? "" : from.repl.lastExchange(700); to.io.say("/carry " + ("Switched here from the project " + from.name() + " (" + from.root + ")" + (was.isBlank() ? ", where nothing had been said yet." : ", where the last exchange was: " + was.replace("\n", " ").strip()))); }
                    redirect(x, "/chat?project=" + enc(to.id));
                }
                default -> send(x, 404, "text/plain", "nothing here");
            }
        } catch (RuntimeException e) { send(x, 500, "text/plain; charset=utf-8", "codezaiku web: " + e); }
    }

    private String log(Loop l, long since) {
        WebIo.Snapshot s = l.io.snapshot(since);
        ObjectNode o = M.createObjectNode();
        ArrayNode a = o.putArray("entries"); for (WebIo.Entry e : s.entries()) { ObjectNode n = a.addObject(); n.put("seq", e.seq()); n.put("kind", e.kind()); n.put("text", e.text()); }
        o.put("partial", s.partial()); o.put("thinking", s.thinking()); if (s.pending() != null) o.put("pending", s.pending()); o.put("status", s.status()); o.put("last", s.last()); o.put("state", l.state()); o.put("loop", Long.toString(l.started));
        return o.toString();
    }

    // ---------- pages ----------
    /** ResearchZosho's colours and type, so the two feel like one family; dark by default, light by the toggle, remembered by the browser. */
    static final String CSS = "<style>:root{--bg:#12161d;--ink:#f3ede1;--k:#9aa3b2;--accent:#e0654f;--line:#2a3140;--card:#1b2130;--me:#243049;--ask:#3a3420}"
            + ":root[data-theme=light]{--bg:#f7f3ea;--ink:#1e1b16;--k:#6b6459;--accent:#c3402f;--line:#e2dccd;--card:#fffdf7;--me:#e9eef9;--ask:#fff3c4}"
            + "*{box-sizing:border-box}html,body{height:100%}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 Georgia,'Noto Serif',serif;display:grid;grid-template-columns:15em 1fr;grid-template-rows:100vh}"
            + "a{color:var(--accent)}button{font:inherit;padding:.35em .8em;border:1px solid var(--line);border-radius:.4em;background:var(--card);color:var(--ink);cursor:pointer}button:hover{border-color:var(--k)}button:disabled{opacity:.5;cursor:default}"
            + "#side{overflow:auto;border-right:1px solid var(--line);padding:1em .9em;background:var(--card)}#side .brand{font-weight:bold;font-size:1.15em;margin:0 0 1em}#side .brand a{color:var(--ink);text-decoration:none}"
            + ".nav-group{margin:0 0 1.1em}.nav-group b{display:block;font-size:.78em;color:var(--k);text-transform:uppercase;letter-spacing:.06em;margin:0 0 .3em}"
            + ".nav-group a,.nav-group .row{display:flex;align-items:center;gap:.5em;padding:.15em 0;color:var(--ink);text-decoration:none}.nav-group a:hover{text-decoration:underline}.nav-group a.here{font-weight:bold}"
            + ".dot{display:inline-block;width:.6em;height:.6em;border-radius:50%;background:var(--k);flex:none}.dot.ready{background:#5bb46a}.dot.working{background:#e0b14f}.dot.asking{background:var(--accent)}.dot.ended{background:#777}"
            + ".nav-group form{margin:0}.nav-group form.add input{width:100%;font:inherit;font-size:.9em;padding:.3em .5em;border:1px solid var(--line);border-radius:.4em;background:var(--bg);color:var(--ink)}"
            + ".nav-group .small{font-size:.85em;color:var(--k)}.nav-group button.lnk{border:0;background:none;padding:.15em 0;color:var(--ink);text-align:left;width:100%;display:flex;gap:.5em;align-items:center}.nav-group button.lnk:hover{text-decoration:underline}"
            + "main{display:flex;flex-direction:column;min-height:0;height:100vh}main>header{display:flex;gap:1em;align-items:baseline;flex-wrap:wrap;padding:.7em 1.2em;border-bottom:1px solid var(--line);background:var(--card)}main>header b{font-size:1.1em}main>header .k{color:var(--k);font-size:.9em}"
            + "#log{flex:1;min-height:0;overflow:auto;padding:1em 1.2em;display:flex;flex-direction:column;gap:.5em}"
            + ".say{max-width:52em;padding:.55em .9em;border-radius:.7em;white-space:pre-wrap;word-break:break-word;border:1px solid var(--line);background:var(--card)}"
            + ".say.me{align-self:flex-end;background:var(--me);border-color:transparent}.say.me b,.say.chat b{display:block;font-size:.78em;color:var(--k);text-transform:uppercase;letter-spacing:.06em;margin-bottom:.2em;font-weight:normal}"
            + ".say.chat{align-self:flex-start}.say.chat p{margin:.3em 0}.say.chat code{font-family:ui-monospace,monospace;font-size:.9em;background:var(--bg);padding:0 .25em;border-radius:.25em}.say.chat pre{font-family:ui-monospace,monospace;font-size:.88em;background:var(--bg);padding:.6em .8em;border-radius:.4em;overflow:auto;margin:.4em 0}"
            + ".say.chat ul{margin:.3em 0 .3em 1.2em;padding:0}.say.chat h3{margin:.5em 0 .2em;font-size:1em}"
            + "details.steps{align-self:flex-start;max-width:52em;color:var(--k);font-size:.85em;font-family:ui-monospace,monospace;border-left:2px solid var(--line);padding-left:.8em}details.steps summary{cursor:pointer;font-family:Georgia,serif;color:var(--k)}details.steps div{white-space:pre-wrap;word-break:break-word}"
            + ".say.think{align-self:flex-start;color:var(--k);font-style:italic;font-size:.9em;border:0;background:transparent;padding:.2em .9em}.say.notify{align-self:flex-start;color:var(--k);font-size:.9em;border:0;background:transparent;padding:.2em .9em}.say.notify:before{content:'\\25C6  '}"
            + ".say.banner{align-self:stretch;max-width:none;color:var(--k);font-size:.85em;font-family:ui-monospace,monospace;border:0;background:transparent;padding:0 .9em}"
            + "#ask{margin:0 1.2em;background:var(--ask);border:1px solid var(--line);border-radius:.7em;padding:.8em 1em;white-space:pre-wrap;font-family:ui-monospace,monospace;font-size:.92em}#ask .b{margin-top:.6em;font-family:Georgia,serif}#ask .b button{margin:.15em .3em .15em 0}"
            + "#status{color:var(--k);font-size:.85em;padding:.2em 1.2em;min-height:1.6em}#news{position:absolute;left:50%;transform:translateX(-50%);bottom:7.5em;background:var(--accent);color:#fff;border:0;border-radius:1em;padding:.3em 1em;box-shadow:0 2px 8px rgba(0,0,0,.3)}main{position:relative}"
            + "#composer{border-top:1px solid var(--line);background:var(--card);padding:.7em 1.2em;display:flex;gap:.6em;align-items:flex-end}#composer textarea{flex:1;font:inherit;padding:.5em .7em;border:1px solid var(--line);border-radius:.5em;background:var(--bg);color:var(--ink);resize:none;min-height:2.6em;max-height:12em}"
            + ".home{padding:1.2em;overflow:auto}.home table{border-collapse:collapse;width:100%;max-width:60em}.home td,.home th{text-align:left;padding:.45em .6em;border-bottom:1px solid var(--line);vertical-align:top}.k{color:var(--k)}.note{color:var(--k);font-size:.9em}.home h2{font-weight:normal;margin:.2em 0 .6em}"
            + ".say{max-width:min(52em,100%)}main{min-width:0}#side #more{display:none}"
            + "@media(max-width:860px){body{grid-template-columns:1fr;grid-template-rows:auto minmax(0,1fr)}#side{border-right:0;border-bottom:1px solid var(--line);padding:.5em .8em;max-height:50vh;display:flex;flex-wrap:wrap;gap:.2em .8em;align-items:center}"
            + "#side .brand{margin:0;font-size:1em}#side #more{display:inline-block;margin-left:auto;padding:.2em .6em}#side .nav-group.projects{flex:1 1 100%;margin:0;display:flex;gap:.9em;overflow-x:auto;white-space:nowrap;padding:.2em 0}#side .nav-group.projects b,#side .nav-group.projects form{display:none}"
            + "#side .more{display:none;flex:1 1 100%}#side.open .more{display:block}#side .more .nav-group{display:inline-block;vertical-align:top;margin:0 1.2em .4em 0}"
            + "main{height:auto;min-height:0}#composer{padding:.5em .7em}#composer button{padding:.35em .6em}#log{padding:.7em .7em}#ask{margin:0 .7em}}</style>";

    /** The theme the browser remembers, as ResearchZosho does it, before the page paints. */
    static final String THEME_JS = "<script>(function(){try{var t=localStorage.getItem('cz.theme');if(t==='light')document.documentElement.setAttribute('data-theme','light')}catch(e){}})();</script>";

    static String dotClass(String state) { return state.startsWith("asking") ? "asking" : state.startsWith("working") ? "working" : state.startsWith("ready") ? "ready" : state.startsWith("ended") ? "ended" : ""; }

    /** The side menu: the projects with their state, each a link; the chat's own controls; the switch that carries the place. */
    String side(Loop here) {
        StringBuilder b = new StringBuilder("<nav id=\"side\"><p class=\"brand\"><a href=\"/\">CodeZaiku</a></p>");
        b.append("<button type=\"button\" id=\"more\" title=\"the rest of the menu\">menu</button><div class=\"nav-group projects\"><b>Projects</b>");
        for (Loop l : projects()) b.append("<a href=\"/chat?project=").append(enc(l.id)).append("\"").append(l == here ? " class=\"here\"" : "").append(" title=\"").append(esc(l.root.toString())).append("\"><span class=\"dot ").append(dotClass(l.state())).append("\" data-project=\"").append(esc(l.id)).append("\"></span><span>").append(esc(l.name())).append("</span></a>");
        b.append("<form method=\"post\" action=\"/projects/add\" class=\"add\"><input name=\"path\" placeholder=\"add a project by its folder\" required></form></div><div class=\"more\">");
        b.append("<div class=\"nav-group\"><b>Add a project</b><form method=\"post\" action=\"/projects/add\" class=\"add\"><input name=\"path\" placeholder=\"by its folder\" required></form></div>");
        if (here != null) {
            b.append("<div class=\"nav-group\"><b>This chat</b>");
            b.append("<div class=\"row small\">mode: <span id=\"mode\">\u2026</span></div>");
            for (String[] m : new String[][]{{"ask", "ask before each change"}, {"auto-edit", "edit without asking, ask to run"}, {"yolo", "never ask"}}) b.append("<button class=\"lnk\" data-say=\"/mode ").append(m[0]).append("\" title=\"").append(m[1]).append("\"><span class=\"dot\"></span>").append(m[0]).append("</button>");
            b.append("<button class=\"lnk\" data-say=\"/undo\" title=\"take the last message back, with its changes\"><span class=\"dot\"></span>undo the last message</button>");
            b.append("<button class=\"lnk\" data-say=\"/sessions\" title=\"earlier conversations in this project\"><span class=\"dot\"></span>earlier sessions</button>");
            b.append("<button class=\"lnk\" data-say=\"/model\" title=\"which model answers\"><span class=\"dot\"></span>the model</button>");
            b.append("<button class=\"lnk\" data-say=\"/help\"><span class=\"dot\"></span>everything else</button></div>");
            List<Loop> others = new ArrayList<>(); for (Loop o : projects()) if (o != here) others.add(o);
            if (!others.isEmpty()) {
                b.append("<div class=\"nav-group\"><b>Switch, carrying your place</b>");
                for (Loop o : others) b.append("<form method=\"post\" action=\"/chat/switch\"><input type=\"hidden\" name=\"from\" value=\"").append(esc(here.id)).append("\"><input type=\"hidden\" name=\"to\" value=\"").append(esc(o.id)).append("\"><button class=\"lnk\" title=\"go to ").append(esc(o.name())).append(", and tell it where you were here\"><span class=\"dot ").append(dotClass(o.state())).append("\"></span>").append(esc(o.name())).append("</button></form>");
                b.append("</div>");
            }
        }
        b.append("<div class=\"nav-group\"><b>Look</b><button class=\"lnk\" id=\"theme\"><span class=\"dot\"></span><span id=\"theme-label\">light</span></button></div>");
        return b.append("</div></nav>").toString();
    }

    static String head(String title) { return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>" + esc(title) + "</title>" + THEME_JS + CSS + "</head><body>"; }

    String home() {
        StringBuilder b = new StringBuilder(head("CodeZaiku")).append(side(null));
        b.append("<main><header><b>Your projects</b><span class=\"k\">each with its own conversation, kept while this server runs</span></header><div class=\"home\">");
        List<Loop> all = projects();
        if (all.isEmpty()) b.append("<p>No project yet. Add one by its folder, in the menu.</p>");
        else {
            b.append("<table><tr><th>Project</th><th>Folder</th><th>State</th><th>Where it got to</th><th></th></tr>");
            for (Loop l : all) {
                String was = l.repl == null ? "" : l.repl.lastExchange(240);
                b.append("<tr><td><a href=\"/chat?project=").append(enc(l.id)).append("\">").append(esc(l.name())).append("</a></td><td class=\"k\">").append(esc(l.root.toString())).append("</td><td><span class=\"dot ").append(dotClass(l.state())).append("\"></span> ").append(esc(l.state())).append("</td><td class=\"note\">").append(esc(was.replace("\n", " "))).append("</td>")
                 .append("<td><form method=\"post\" action=\"/projects/remove\" onsubmit=\"return confirm('Take ").append(esc(l.name()).replace("'", "\\'")).append(" off the list? Its conversations stay on disk.')\"><input type=\"hidden\" name=\"project\" value=\"").append(esc(l.id)).append("\"><button class=\"lnk\" title=\"take it off the list; its conversations stay on disk\">remove</button></form></td></tr>");
            }
            b.append("</table>");
            if (!skipped.isEmpty()) { b.append("<p class=\"note\">Left off the list, their folders being gone: "); for (String g : skipped) b.append(esc(g)).append(" "); b.append("</p>"); }
        }
        b.append("<p class=\"note\">Open a project to talk to its chat. It is the same chat as the terminal's: it asks before it writes or runs anything, and you answer on the page.").append(token == null ? " Listening on this machine only; no sign-in." : " Signed in with the token; it is kept by this browser for a month.").append("</p>");
        return b.append("</div></main>").append(SHELL_JS).append("</body></html>").toString();
    }

    String chatPage(Loop l) {
        StringBuilder b = new StringBuilder(head(l.name() + " · CodeZaiku")).append(side(l));
        b.append("<main><header><b>").append(esc(l.name())).append("</b><span class=\"k\">").append(esc(l.root.toString())).append("</span><span class=\"k\" id=\"state\"></span></header>");
        b.append("<div id=\"log\" data-project=\"").append(esc(l.id)).append("\"></div><button type=\"button\" id=\"news\" hidden>new messages \u2193</button><div id=\"ask\" hidden></div><div id=\"status\"></div>");
        b.append("<form id=\"composer\"><textarea name=\"say\" rows=\"1\" placeholder=\"Say something. Enter sends, Shift+Enter is a new line; @path attaches a file\" autofocus></textarea><button>Send</button><button type=\"button\" id=\"stop\" title=\"stop the running turn, as ctrl-C does\">Stop</button></form>");
        b.append("</main>").append(SHELL_JS).append("<script>").append(JS).append("</script></body></html>");
        return b.toString();
    }

    /** The menu's own script: the theme toggle, the buttons that send a line, the dots refreshed from the server. */
    static final String SHELL_JS = "<script>(function(){var t=document.getElementById('theme'),lb=document.getElementById('theme-label');function paint(){var light=document.documentElement.getAttribute('data-theme')==='light';if(lb)lb.textContent=light?'dark':'light'}paint();"
            + "if(t)t.addEventListener('click',function(){var light=document.documentElement.getAttribute('data-theme')==='light';if(light)document.documentElement.removeAttribute('data-theme');else document.documentElement.setAttribute('data-theme','light');try{localStorage.setItem('cz.theme',light?'dark':'light')}catch(e){}paint()});"
            + "var mb=document.getElementById('more');if(mb)mb.addEventListener('click',function(){document.getElementById('side').classList.toggle('open')});"
            + "function dots(){fetch('/projects').then(function(r){return r.json()}).then(function(ps){ps.forEach(function(p){var d=document.querySelector('.dot[data-project=\"'+p.id+'\"]');if(d)d.className='dot '+p.dot})}).catch(function(){})}setInterval(dots,5000);"
            + "})();</script>";

    /** The chat page's script: poll the transcript, place each line by its kind, show a question with its buttons, send what is typed. */
    static final String JS = "(function(){var log=document.getElementById('log'),ask=document.getElementById('ask'),st=document.getElementById('status'),state=document.getElementById('state'),form=document.getElementById('composer'),inp=form.querySelector('textarea'),proj=log.dataset.project,last=0,shownPrompt=null,partialEl=null,bubble=null,steps=null,started=false,modeEl=document.getElementById('mode'),dkey='cz.draft.'+proj,news=document.getElementById('news'),loopId=null,wantBottom=true;"
            + "window.addEventListener('error',function(ev){st.textContent='the page hit an error: '+(ev.message||ev.type)+' — reload, and tell the developer';st.style.color='var(--accent)'});"
            + "news.addEventListener('click',function(){wantBottom=true;bottom();news.hidden=true});log.addEventListener('scroll',function(){if(atBottom())news.hidden=true});"
            + "function esc(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}"
            + "function md(s){var h=esc(s);h=h.replace(/```([\\s\\S]*?)```/g,function(m,c){return '<pre>'+c.replace(/^\\n/,'')+'</pre>'});h=h.replace(/`([^`\\n]+)`/g,'<code>$1</code>');h=h.replace(/\\*\\*([^*\\n]+)\\*\\*/g,'<b>$1</b>');"
            + "h=h.replace(/^#{1,3} (.+)$/gm,'<h3>$1</h3>');h=h.replace(/^(?:[-*] .+(?:\\n|$))+/gm,function(m){return '<ul>'+m.trim().split('\\n').map(function(l){return '<li>'+l.replace(/^[-*] /,'')+'</li>'}).join('')+'</ul>'});return h.replace(/\\n{2,}/g,'</p><p>').replace(/^(?!<)/,'<p>').replace(/(?!>)$/,'</p>')}"
            + "function isStep(t){return /^\\s*(\\|\\s*\\u00b7|\\u21b3|\\u2192 \\d+ lines|\\u00b7 |planning\\u2026|done \\u00b7|\\[thinking\\])/.test(t)||/^\\s{2,}\\u2192/.test(t)}"
            + "function atBottom(){return log.scrollHeight-log.scrollTop-log.clientHeight<80}function bottom(){log.scrollTop=log.scrollHeight}"
            + "function el(cls,html){var d=document.createElement('div');d.className='say '+cls;d.innerHTML=html;log.appendChild(d);return d}"
            + "function closeBubble(){bubble=null;steps=null}"
            + "var lib=null,libOn=false,memo=null,memoOn=false;function isBanner(t){return /^(codezaiku chat \\u2014 |drive |\\/help for commands)/.test(t)}function isNews(t){return /^\\s*\\u25C6 research /.test(t)}"
            + "function out(text){if(isBanner(text)){var bn=log.querySelector('.say.banner');if(!bn)bn=el('banner','');bn.textContent+=(bn.textContent?'\\n':'')+text;return}"
            + "if(isNews(text)||(libOn&&/^\\s{4}\\S/.test(text))){if(!lib){lib=document.createElement('details');lib.className='steps news';lib.innerHTML='<summary></summary><div></div>';log.appendChild(lib);lib.n=0}if(isNews(text))lib.n++;lib.querySelector('summary').textContent=lib.n+' notice'+(lib.n>1?'s':'')+' from the library (research that landed; /research read marks it read)';var nd=lib.querySelector('div');nd.textContent+=(nd.textContent?'\\n':'')+text.trim();libOn=true;return}libOn=false;"
            + "if(/^\\s*resumed /.test(text)){closeBubble();el('notify','').textContent=text.trim();return}"
            + "if(/^\\s*\\[session so far/.test(text)||(memoOn&&/^\\s+\\S/.test(text))){if(!memo){memo=document.createElement('details');memo.className='steps memo';memo.innerHTML='<summary>what the chat remembers of it</summary><div></div>';log.appendChild(memo)}var md2=memo.querySelector('div');md2.textContent+=(md2.textContent?'\\n':'')+text.trim();memoOn=true;return}memoOn=false;"
            + "if(isStep(text)){if(!steps){steps=document.createElement('details');steps.className='steps';steps.innerHTML='<summary></summary><div></div>';log.appendChild(steps);steps.n=0}steps.n++;steps.querySelector('summary').textContent=steps.n+' step'+(steps.n>1?'s':'')+' (what it looked at and ran)';var dv=steps.querySelector('div');dv.textContent+=(dv.textContent?'\\n':'')+text.trim();bubble=null;return}"
            + "if(!text.trim())return;if(!bubble){lib=null;memo=null;bubble=el('chat','<b>CodeZaiku</b><div></div>');bubble.raw=''}bubble.raw+=(bubble.raw?'\\n':'')+text;bubble.querySelector('div').innerHTML=md(bubble.raw)}"
            + "function add(e){if(e.kind==='me'){closeBubble();lib=null;libOn=false;memoOn=false;if(/^\\/carry /.test(e.text)){el('notify','').textContent='carried here: '+e.text.replace(/^\\/carry /,'');return}if(e.text==='/resume'){el('notify','').textContent='continuing the earlier conversation';return}el('me','<b>You</b>'+esc(e.text))}else if(e.kind==='out')out(e.text);else if(e.kind==='think'){closeBubble();el('think','').textContent=e.text}else if(e.kind==='notify'){closeBubble();el('notify','').textContent=e.text}}"
            + "function buttons(p){var out=[],seen={};var m,re=/\\[([a-zA-Z])\\]([a-zA-Z]*)/g;while((m=re.exec(p))){var k=m[1].toLowerCase();if(!seen[k]){seen[k]=1;out.push([k,m[1]+m[2]])}}"
            + "if(/\\ball=stop asking\\b/.test(p)&&!seen['all']){seen['all']=1;out.push(['all','all: stop asking'])}"
            + "var words=['go','skip','stop'];for(var i=0;i<words.length;i++){var w=words[i];if(new RegExp('(^|[\\\\s/])'+w+'(\\\\b)').test(p)&&!seen[w]){seen[w]=1;out.push([w,w])}}return out}"
            + "function idle(p){return p.indexOf('[')<0&&p.indexOf(' / ')<0&&/ > ?$/.test(p)}"
            + "function showPrompt(p){if(p===shownPrompt)return;shownPrompt=p;if(p==null){ask.hidden=true;ask.innerHTML='';inp.placeholder='The chat is working\\u2026 what you type waits its turn';return}"
            + "if(idle(p)){started=true;closeBubble();ask.hidden=true;ask.innerHTML='';inp.placeholder='Say something. Enter sends, Shift+Enter is a new line; @path attaches a file';if(modeEl)modeEl.textContent=p.replace(/\\s*>\\s*$/,'').trim();return}"
            + "ask.hidden=false;ask.innerHTML='';var t=document.createElement('div');t.textContent=p.trim();ask.appendChild(t);var bs=buttons(p);if(bs.length){var row=document.createElement('div');row.className='b';bs.forEach(function(b){var e=document.createElement('button');e.textContent=b[1];e.onclick=function(){send(b[0])};row.appendChild(e)});ask.appendChild(row)}inp.placeholder='or type your answer here'}"
            + "function poll(){fetch('/chat/log?project='+encodeURIComponent(proj)+'&since='+last).then(function(r){if(r.status===401){location.href='/login';throw new Error('401')}return r.json()}).then(function(j){"
            + "if(sentAt&&Date.now()-sentAt>4000){if(last===sentSeq)said('what you sent has not shown up; the chat may be busy, or reload the page');sentAt=0}"
            + "if(j.loop&&loopId&&j.loop!==loopId){log.innerHTML='';last=0;started=false;bubble=null;steps=null;partialEl=null;shownPrompt=null;el('notify','').textContent='the chat started again';setTimeout(poll,100);loopId=j.loop;return}if(j.loop)loopId=j.loop;"
            + "var stick=wantBottom||atBottom();"
            + "var es=j.entries||[];if(es.length&&partialEl){partialEl.remove();partialEl=null}"
            + "for(var i=0;i<es.length;i++){var e=es[i];last=e.seq;if(e.kind==='prompt'){if(!idle(e.text))closeBubble();libOn=false;memoOn=false;continue}add(e)}"
            + "var p=(j.thinking?'\\u2026 '+j.thinking:'')+(j.partial||'');if(p){if(!partialEl)partialEl=el('think','');partialEl.textContent=p}else if(partialEl&&!es.length){partialEl.remove();partialEl=null}"
            + "st.textContent=j.status||'';if(state)state.textContent=j.state||'';"
            + "if(j.state&&j.state.indexOf('ended')===0){shownPrompt='ended';ask.hidden=false;ask.innerHTML='';var dd=document.createElement('div');dd.textContent=j.state;ask.appendChild(dd);var rf=document.createElement('form');rf.method='post';rf.action='/chat/restart';rf.className='b';rf.innerHTML='<input type=\\'hidden\\' name=\\'project\\'><button>Start the chat again</button>';rf.querySelector('input').value=proj;ask.appendChild(rf)}else showPrompt(j.pending==null?null:j.pending);"
            + "if((es.length||p)&&stick){bottom();wantBottom=false}else if(es.length&&!stick)news.hidden=false;setTimeout(poll,500)}).catch(function(){setTimeout(poll,2000)})}"
            + "var sentAt=0,sentSeq=0;function said(msg){st.textContent=msg;st.style.color='var(--accent)';setTimeout(function(){st.style.color=''},6000)}"
            + "function send(text){wantBottom=true;news.hidden=true;sentAt=Date.now();sentSeq=last;var fd=new URLSearchParams();fd.append('project',proj);fd.append('say',text);"
            + "fetch('/chat/say',{method:'POST',body:fd,headers:{'Content-Type':'application/x-www-form-urlencoded'}}).then(function(r){if(r.status===401){location.href='/login';return}if(!r.ok){said('the chat did not take that ('+r.status+'); reload the page and try again')}}).catch(function(){said('could not reach the server; is it still running?')})}"
            + "form.addEventListener('submit',function(ev){ev.preventDefault();var t=inp.value.trim();if(!t)return;inp.value='';inp.style.height='';try{sessionStorage.removeItem(dkey)}catch(e){}send(t)});"
            + "inp.addEventListener('keydown',function(ev){if(ev.key==='Enter'&&!ev.shiftKey){ev.preventDefault();form.requestSubmit()}});inp.addEventListener('input',function(){inp.style.height='';inp.style.height=Math.min(inp.scrollHeight,190)+'px';try{sessionStorage.setItem(dkey,inp.value)}catch(e){}});"
            + "try{var d0=sessionStorage.getItem(dkey);if(d0)inp.value=d0}catch(e){}"
            + "document.querySelectorAll('button[data-say]').forEach(function(b){b.addEventListener('click',function(){send(b.dataset.say)})});"
            + "document.getElementById('stop').addEventListener('click',function(){var fd=new URLSearchParams();fd.append('project',proj);fetch('/chat/stop',{method:'POST',body:fd,headers:{'Content-Type':'application/x-www-form-urlencoded'}})});"
            + "poll()})();";

    // ---------- http ----------
    static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String kv : raw.split("&")) { int i = kv.indexOf('='); String k = i < 0 ? kv : kv.substring(0, i), v = i < 0 ? "" : kv.substring(i + 1); m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8)); }
        return m;
    }
    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    static void send(HttpExchange x, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", type); x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = x.getResponseBody()) { os.write(bytes); }
    }
    static void redirect(HttpExchange x, String to) throws IOException { x.getResponseHeaders().set("Location", to); x.sendResponseHeaders(303, -1); x.close(); }
}
