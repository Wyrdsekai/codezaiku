package org.codezaiku.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.codezaiku.exec.Shell;

/**
 * Minimal LSP (JSON-RPC over stdio) client — the type-and-scope-aware checker the field uses to kill
 * API-faking (opencode pattern: language-server diagnostics fed back after each edit). Launches ONE
 * off-the-shelf per-language server (rust-analyzer / pyright / gopls / …) pointed at the project, keeps
 * it alive for the run (it indexes the project + resolved deps once), and after an edit returns the real
 * per-file diagnostics — "no method `disks` on `System`", "no method `name` on `&(Pid, Process)`", a
 * missing lifetime — grounded in THIS project's actual dependency versions. Degrades to empty on any
 * failure (server missing/slow): an aid, never a hard dependency.
 */
public final class LspClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LspClient.class);

    private final Path root;
    private final String[] command;
    private final String languageId;
    private final ObjectMapper J = new ObjectMapper();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<String, JsonNode> responses = new ConcurrentHashMap<>();
    private final Map<String, String> diagnostics = new ConcurrentHashMap<>();   // uri -> formatted block ("" = clean)
    private final Map<String, Long> diagStamp = new ConcurrentHashMap<>();       // uri -> last-update millis
    private final Map<String, Integer> versions = new ConcurrentHashMap<>();     // uri -> document version
    private Process proc;
    private OutputStream out;
    private volatile boolean started;
    private volatile boolean dead;

    private LspClient(Path root, String[] command, String languageId) {
        this.root = root.toAbsolutePath().normalize();
        this.command = command;
        this.languageId = languageId;
    }

    /** Pick the off-the-shelf server for the project's language, or null if none/unsupported. */
    public static LspClient forProject(Path root, String language) {
        if (language == null) return null;
        String lang = language.toLowerCase(Locale.ROOT);
        String[] cmd = switch (lang) {
            case "rust" -> have("rust-analyzer") ? new String[]{"rust-analyzer"} : null;
            case "python" -> have("pyright-langserver") ? new String[]{"pyright-langserver", "--stdio"}
                    : have("pylsp") ? new String[]{"pylsp"} : null;
            case "go" -> have("gopls") ? new String[]{"gopls"} : null;
            case "typescript", "javascript", "js", "ts", "jsx", "tsx" ->
                    have("typescript-language-server") ? new String[]{"typescript-language-server", "--stdio"} : null;
            case "java" -> have("jdtls") ? new String[]{"jdtls"} : null;
            case "cpp", "c++", "c", "cxx" -> have("clangd") ? new String[]{"clangd"} : null;
            case "kotlin", "kt" -> have("kotlin-language-server") ? new String[]{"kotlin-language-server"} : null;
            default -> null;
        };
        if (cmd == null) return null;
        String langId = switch (lang) {
            case "rust" -> "rust";
            case "python" -> "python";
            case "go" -> "go";
            case "java" -> "java";
            case "cpp", "c++", "cxx" -> "cpp";
            case "c" -> "c";
            case "kotlin", "kt" -> "kotlin";
            case "typescript", "ts", "tsx" -> "typescript";
            default -> "javascript";
        };
        return new LspClient(root, cmd, langId);
    }

    /**
     * Lock in + broaden pyright's cross-module contract checks (battery47 lever). pyright's default
     * standard mode already flags `obj.wrongField` on a TYPED object — a producer/consumer desync where
     * a 9B's fit() returns one shape and the consumer reads a field it doesn't have (verified: it reports
     * reportAttributeAccessIssue as an error). Push the sibling checks to error too — optional-member,
     * index, call/argument — so MORE cross-module contract mismatches surface as the same after-edit
     * diagnostic we already feed back. Pure grounding, never a gate. Inherent limit (why Lever 2 exists):
     * a dict-KEY access (`d["wrongkey"]`) and a plain-JS object field are untypeable, so those degenerate
     * desyncs need the runtime non-degeneracy assertion, not the type checker. Notification only — pyright
     * ignores settings it doesn't recognize, so a bad shape can never wedge startup.
     */
    private void pushPyrightStrictness() {
        try {
            ObjectNode analysis = J.createObjectNode();
            ObjectNode py = analysis.putObject("python").putObject("analysis");
            py.put("typeCheckingMode", "standard");
            ObjectNode sev = py.putObject("diagnosticSeverityOverrides");
            for (String rule : new String[]{"reportAttributeAccessIssue", "reportOptionalMemberAccess",
                    "reportIndexIssue", "reportCallIssue", "reportArgumentType"}) {
                sev.put(rule, "error");
            }
            ObjectNode params = J.createObjectNode();
            params.set("settings", analysis);
            notifyServer("workspace/didChangeConfiguration", params);
        } catch (Exception e) {
            log.warn("pyright config push failed (non-fatal): {}", e.getMessage());
        }
    }

    private static boolean have(String bin) {
        try {
            Process p = Shell.pb("command -v " + bin).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Launch + initialize handshake; safe to call repeatedly. */
    public synchronized void start() {
        if (started || dead) return;
        try {
            proc = new ProcessBuilder(command).directory(root.toFile()).start();
            out = proc.getOutputStream();
            Thread reader = new Thread(() -> readLoop(proc.getInputStream()), "lsp-reader");
            reader.setDaemon(true);
            reader.start();
            // drain stderr so the server never blocks on a full pipe
            Thread errDrain = new Thread(() -> { try (var e = proc.getErrorStream()) { e.readAllBytes(); }
                catch (Exception ignored) { } }, "lsp-stderr");
            errDrain.setDaemon(true);
            errDrain.start();

            ObjectNode init = J.createObjectNode();
            init.put("processId", ProcessHandle.current().pid());
            init.put("rootUri", uri(root));
            ArrayNode wf = init.putArray("workspaceFolders");
            ObjectNode folder = wf.addObject();
            folder.put("uri", uri(root));
            folder.put("name", root.getFileName().toString());
            ObjectNode caps = init.putObject("capabilities");
            caps.putObject("textDocument").putObject("publishDiagnostics");
            String id = request("initialize", init);
            JsonNode resp = await(id, 60_000);
            if (resp == null) { dead = true; log.warn("LSP initialize timed out ({})", command[0]); return; }
            notifyServer("initialized", J.createObjectNode());
            if ("python".equals(languageId)) pushPyrightStrictness();
            started = true;
            log.info("LSP up: {} on {}", command[0], root.getFileName());
        } catch (Exception e) {
            dead = true;
            log.warn("LSP start failed ({}): {}", command[0], e.getMessage());
        }
    }

    /**
     * Open/refresh {@code file} (full {@code text}) and return its diagnostics once they settle.
     * Empty string = clean (no errors) OR unavailable — the caller treats absence of news as no-news.
     */
    public String check(Path file, String text) {
        if (dead) return "";
        if (!started) start();
        if (!started) return "";
        String fileUri = uri(file.toAbsolutePath().normalize());
        try {
            int ver = versions.merge(fileUri, 1, Integer::sum);
            diagStamp.remove(fileUri);
            if (ver == 1) didOpen(fileUri, text);
            else didChange(fileUri, ver, text);
            didSave(fileUri); // triggers rust-analyzer flycheck (cargo check) — the full type errors
            return waitForDiagnostics(fileUri);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Wait for diagnostics to SETTLE. rust-analyzer publishes an early (often empty) pass from its
     * analyzer, THEN the full type errors from flycheck (cargo check) seconds later — so we must not
     * trust an early "clean": return as soon as NON-EMPTY errors settle, but only declare "clean" after
     * a floor wait that lets flycheck run.
     */
    private String waitForDiagnostics(String uri) {
        long now = System.currentTimeMillis();
        // Only rust-analyzer needs a long floor: its real type errors arrive seconds LATER via flycheck
        // (cargo check) after an early often-empty pass, so "clean" can't be trusted early. pyright/jdtls/
        // gopls/clangd/tsserver publish synchronously on didChange, so a short settle suffices. A 12s floor
        // on EVERY clean edit (hundreds per run) was the dominant per-turn wall-time sink — make it
        // language-conditional so only rust pays it.
        boolean rust = "rust".equals(languageId);
        long deadline = now + (rust ? 60_000 : 8_000);     // cold flycheck can be slow the first time
        long cleanFloor = now + (rust ? 12_000 : 1_500);   // don't trust "clean" before flycheck has run
        long settleMs = rust ? 3_000 : 600;
        while (System.currentTimeMillis() < deadline) {
            Long stamp = diagStamp.get(uri);
            String diag = diagnostics.getOrDefault(uri, "");
            boolean settled = stamp != null && System.currentTimeMillis() - stamp >= settleMs;
            if (settled && !diag.isBlank()) return diag;                        // errors found + settled
            if (settled && diag.isBlank() && System.currentTimeMillis() >= cleanFloor) return ""; // truly clean
            sleep(200);
        }
        return diagnostics.getOrDefault(uri, "");
    }

    /** A named code symbol (function/method/class/struct/…) with its full line span, from the server. */
    public record Sym(String name, int kind, int startLine, int endLine) {
    }

    /**
     * The document's symbols (functions/types/methods) with EXACT line spans, via the standard
     * {@code textDocument/documentSymbol} request — implemented by every language server, so this is
     * language-general by construction. Lets a tool replace a whole symbol by NAME without the model
     * reproducing its old text (the dominant edit-miss). Empty list if unavailable.
     */
    public List<Sym> symbols(Path file, String text) {
        if (dead) return List.of();
        if (!started) start();
        if (!started) return List.of();
        String fileUri = uri(file.toAbsolutePath().normalize());
        try {
            int ver = versions.merge(fileUri, 1, Integer::sum);
            if (ver == 1) didOpen(fileUri, text);
            else didChange(fileUri, ver, text);
            ObjectNode params = J.createObjectNode();
            params.putObject("textDocument").put("uri", fileUri);
            String id = request("textDocument/documentSymbol", params);
            JsonNode resp = await(id, 20_000);
            if (resp == null) return List.of();
            List<Sym> out = new ArrayList<>();
            collectSymbols(resp.path("result"), out);
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** One code location, 0-based line, from a definition/references answer. */
    public record Loc(String path, int line) {
    }

    /**
     * Where the symbol at {@code line:character} (0-based) is defined, via
     * {@code textDocument/definition} — and {@link #references} via {@code textDocument/references}.
     * Both are implemented by every mainstream language server, same as documentSymbol above, so
     * they are language-general by construction. Empty list when unavailable, never an error: nav
     * is an assist, and a missing language server must not cost a turn.
     *
     * <p>Built because sourcebot made the gap visible: IDE-grade code nav was its headline feature,
     * and this client had the whole transport for it with nothing wired on top — the same
     * door-with-no-corridor shape the wyrdsekai access audit found.
     */
    public List<Loc> definition(Path file, String text, int line, int character) {
        return locate("textDocument/definition", file, text, line, character, false);
    }

    /** Everywhere the symbol at {@code line:character} is used. Includes the declaration. */
    public List<Loc> references(Path file, String text, int line, int character) {
        return locate("textDocument/references", file, text, line, character, true);
    }

    private List<Loc> locate(String method, Path file, String text, int line, int character,
                             boolean includeDecl) {
        if (dead) return List.of();
        if (!started) start();
        if (!started) return List.of();
        String fileUri = uri(file.toAbsolutePath().normalize());
        try {
            int ver = versions.merge(fileUri, 1, Integer::sum);
            if (ver == 1) didOpen(fileUri, text);
            else didChange(fileUri, ver, text);
            ObjectNode params = J.createObjectNode();
            params.putObject("textDocument").put("uri", fileUri);
            params.putObject("position").put("line", line).put("character", character);
            if (includeDecl) params.putObject("context").put("includeDeclaration", true);
            String id = request(method, params);
            JsonNode resp = await(id, 20_000);
            if (resp == null) return List.of();
            List<Loc> out = new ArrayList<>();
            JsonNode result = resp.path("result");
            // Location | Location[] | LocationLink[] — servers use all three shapes.
            if (result.isObject()) collectLoc(result, out);
            else for (JsonNode n : result) collectLoc(n, out);
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static void collectLoc(JsonNode n, List<Loc> out) {
        String u = n.hasNonNull("uri") ? n.get("uri").asText()
                : n.path("targetUri").asText(null);
        JsonNode range = n.has("range") ? n.path("range") : n.path("targetRange");
        int line = range.path("start").path("line").asInt(-1);
        if (u != null && u.startsWith("file://") && line >= 0) {
            out.add(new Loc(u.substring("file://".length()), line));
        }
    }

    // Handles both shapes: DocumentSymbol (hierarchical, .range + .children) and SymbolInformation
    // (flat, .location.range). Recurses children so nested methods are reachable.
    private static void collectSymbols(JsonNode node, List<Sym> out) {
        if (!node.isArray()) return;
        for (JsonNode s : node) {
            String name = s.path("name").asText("");
            int kind = s.path("kind").asInt(0);
            JsonNode range = s.has("range") ? s.path("range") : s.path("location").path("range");
            int sl = range.path("start").path("line").asInt(-1);
            int el = range.path("end").path("line").asInt(-1);
            if (!name.isEmpty() && sl >= 0 && el >= sl) out.add(new Sym(name, kind, sl, el));
            if (s.has("children")) collectSymbols(s.path("children"), out);
        }
    }

    // ---- JSON-RPC plumbing ----

    private synchronized String request(String method, JsonNode params) throws Exception {
        String id = Integer.toString(nextId.getAndIncrement());
        ObjectNode m = J.createObjectNode();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("method", method);
        m.set("params", params);
        send(m);
        return id;
    }

    private synchronized void notifyServer(String method, JsonNode params) throws Exception {
        ObjectNode m = J.createObjectNode();
        m.put("jsonrpc", "2.0");
        m.put("method", method);
        m.set("params", params);
        send(m);
    }

    private void send(JsonNode m) throws Exception {
        byte[] body = J.writeValueAsBytes(m);
        out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private JsonNode await(String id, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !dead) {
            JsonNode r = responses.remove(id);
            if (r != null) return r;
            sleep(50);
        }
        return null;
    }

    private void readLoop(InputStream in) {
        try {
            while (true) {
                int len = readHeaders(in);
                if (len < 0) break;
                byte[] buf = in.readNBytes(len);
                if (buf.length < len) break;
                JsonNode msg = J.readTree(buf);
                if (msg.has("id") && msg.has("method")) {
                    respondToServerRequest(msg); // server→client request: must reply or it stalls
                } else if (msg.has("id")) {
                    responses.put(msg.get("id").asText(), msg);
                } else if (msg.has("method")) {
                    if ("textDocument/publishDiagnostics".equals(msg.get("method").asText())) {
                        onDiagnostics(msg.path("params"));
                    }
                }
            }
        } catch (Exception e) {
            // server died / stream closed
        } finally {
            dead = true;
        }
    }

    private void respondToServerRequest(JsonNode msg) {
        try {
            String method = msg.get("method").asText();
            ObjectNode r = J.createObjectNode();
            r.put("jsonrpc", "2.0");
            r.set("id", msg.get("id"));
            if ("workspace/configuration".equals(method)) {
                ArrayNode items = J.createArrayNode();
                int n = msg.path("params").path("items").size();
                for (int i = 0; i < Math.max(1, n); i++) items.addNull();
                r.set("result", items);
            } else {
                r.putNull("result");
            }
            send(r);
        } catch (Exception ignored) {
        }
    }

    private void onDiagnostics(JsonNode params) {
        String uri = params.path("uri").asText();
        if (uri.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        int errs = 0;
        for (JsonNode d : params.path("diagnostics")) {
            if (d.path("severity").asInt(1) != 1) continue; // 1 = ERROR only
            int line = d.path("range").path("start").path("line").asInt() + 1;
            int col = d.path("range").path("start").path("character").asInt() + 1;
            String code = d.path("code").asText("");
            String src = d.path("source").asText("");
            sb.append("\n  ERROR [").append(line).append(':').append(col).append("] ")
                    .append(code.isBlank() ? "" : code + ": ")
                    .append(d.path("message").asText().split("\n")[0])
                    .append(src.isBlank() ? "" : "  (" + src + ")");
            if (++errs >= 25) { sb.append("\n  …(more)"); break; }
        }
        diagnostics.put(uri, errs == 0 ? "" : "LSP diagnostics (type/scope-checked against this project's "
                + "real deps — fix these, do not guess):" + sb);
        diagStamp.put(uri, System.currentTimeMillis());
        log.info("LSP diag: {} error(s) for {}", errs, uri.substring(uri.lastIndexOf('/') + 1));
    }

    private void didOpen(String uri, String text) throws Exception {
        ObjectNode td = J.createObjectNode();
        td.put("uri", uri);
        td.put("languageId", languageId);
        td.put("version", 1);
        td.put("text", text);
        ObjectNode p = J.createObjectNode();
        p.set("textDocument", td);
        notifyServer("textDocument/didOpen", p);
    }

    private void didChange(String uri, int version, String text) throws Exception {
        ObjectNode td = J.createObjectNode();
        td.put("uri", uri);
        td.put("version", version);
        ObjectNode change = J.createObjectNode();
        change.put("text", text); // full-document sync
        ObjectNode p = J.createObjectNode();
        p.set("textDocument", td);
        p.putArray("contentChanges").add(change);
        notifyServer("textDocument/didChange", p);
    }

    private void didSave(String uri) throws Exception {
        ObjectNode td = J.createObjectNode();
        td.put("uri", uri);
        ObjectNode p = J.createObjectNode();
        p.set("textDocument", td);
        notifyServer("textDocument/didSave", p);
    }

    private static int readHeaders(InputStream in) throws Exception {
        StringBuilder line = new StringBuilder();
        int len = -1;
        int c;
        int state = 0; // counts \r\n\r\n
        while ((c = in.read()) != -1) {
            line.append((char) c);
            if (c == '\n') {
                String h = line.toString().trim();
                if (h.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    len = Integer.parseInt(h.substring(h.indexOf(':') + 1).trim());
                }
                if (h.isEmpty()) return len; // blank line ends headers
                line.setLength(0);
            }
        }
        return -1;
    }

    private static String uri(Path p) {
        return p.toUri().toString();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override
    public synchronized void close() {
        dead = true;
        if (proc != null) {
            proc.descendants().forEach(ProcessHandle::destroyForcibly);
            proc.destroyForcibly();
        }
    }
}
