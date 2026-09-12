package org.codezaiku;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * `codezaiku setup`: the first ten minutes, as questions with defaults. Which model server (one that is
 * running, one this machine serves on demand, or a hosted API with a key), a web search backend, the
 * programs that should reach CodeZaiku over MCP, and ResearchZosho if wanted. Every step is skippable and
 * every answer lands in the settings file, so the same questions can be answered later by hand.
 *
 * <p>Probing and acting are behind {@link Probe} and {@link Acts}, so a test scripts the whole wizard
 * without a network, a model or a program to register with.
 */
public final class Setup {

    private static final ObjectMapper J = new ObjectMapper();

    /** What setup asks of the outside world. */
    public interface Probe {
        /** Model ids a server offers at {@code base}, or null when nothing answers there. */
        List<String> models(String base, String key);
        /** One short chat with {@code model}; the reply text, or a one-line reason it failed prefixed with "!". */
        String chat(String base, String model, String key);
        /** Whether a SearXNG at {@code base} answers a JSON search. */
        default boolean searxng(String base) { return false; }
        /** Whether the Brave Search API accepts {@code key}. */
        default boolean brave(String key) { return false; }
    }

    /** What setup does to the machine. */
    public interface Acts {
        /** Whether a command (claude, codex, gemini…) is on this machine. */
        default boolean have(String command) { return false; }
        /** Run a host's own registration command; "connected" or its output, "!reason" on failure. */
        default String register(String command, List<String> args) throws Exception { return "!" + command + " is not available"; }
        /** The codezaiku launcher a program should be given. */
        default String launcher() { return "codezaiku"; }
        /** One sentence on serving a model on this machine on demand, or null when it cannot (ModelServer.offer). */
        default String modelOffer() { return null; }
        /** Set the model server on demand up; the model's name, or "!reason". */
        default String modelServe(PrintStream out) { return "!not on this machine"; }
        /** Whether ResearchZosho is installed here. */
        default boolean libraryInstalled() { return true; }
        /** Install ResearchZosho and run its setup; 0 when done. */
        default int installLibrary(PrintStream out) { return 1; }
    }

    record Host(String command, String name) { }
    static final List<Host> HOSTS = List.of(new Host("claude", "Claude Code"), new Host("codex", "Codex"), new Host("gemini", "Gemini CLI"));
    /** Where a local model server usually is, in the order worth trying; the on-demand proxy first. */
    static final String[] USUAL = {ModelServer.URL, "http://localhost:8200", "http://localhost:11434", "http://localhost:8080", "http://localhost:1234"};

    private final BufferedReader in;
    private final PrintStream out;
    private final Probe probe;
    private final Acts acts;
    private final boolean yes;

    public Setup(BufferedReader in, PrintStream out, Probe probe, Acts acts, boolean yes) {
        this.in = in; this.out = out; this.probe = probe; this.acts = acts; this.yes = yes;
    }

    public static Probe liveProbe() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
        return new Probe() {
            @Override public List<String> models(String base, String key) {
                try {
                    var b = HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/v1/models")).timeout(Duration.ofSeconds(6)).GET();
                    if (key != null && !key.isBlank()) b.header("Authorization", "Bearer " + key.strip());
                    HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() != 200) return null;
                    List<String> ids = new ArrayList<>();
                    for (JsonNode m : J.readTree(r.body()).path("data")) if (m.hasNonNull("id")) ids.add(m.get("id").asText());
                    return ids;
                } catch (Exception e) { return null; }
            }
            @Override public String chat(String base, String model, String key) {
                try {
                    String body = "{\"model\":" + J.writeValueAsString(model) + ",\"max_tokens\":64,\"messages\":[{\"role\":\"user\",\"content\":\"Reply with the single word ready.\"}]}";
                    var b = HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/v1/chat/completions")).timeout(Duration.ofSeconds(120))
                            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
                    if (key != null && !key.isBlank()) b.header("Authorization", "Bearer " + key.strip());
                    HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() != 200) return "!HTTP " + r.statusCode() + ": " + r.body().replaceAll("\\s+", " ").strip().substring(0, Math.min(160, r.body().length()));
                    JsonNode j = J.readTree(r.body());
                    String text = j.path("choices").path(0).path("message").path("content").asText("").strip();
                    return text.isEmpty() ? "(an empty reply, but it answered)" : text;
                } catch (Exception e) { return "!" + e.getMessage(); }
            }
            @Override public boolean searxng(String base) {
                try {
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/search?q=test&format=json"))
                            .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
                    return r.statusCode() == 200 && r.body().contains("\"results\"");
                } catch (Exception e) { return false; }
            }
            @Override public boolean brave(String key) {
                try {
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("https://api.search.brave.com/res/v1/web/search?q=test&count=1"))
                            .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").header("X-Subscription-Token", key.strip()).GET().build(), HttpResponse.BodyHandlers.ofString());
                    return r.statusCode() == 200;
                } catch (Exception e) { return false; }
            }
        };
    }

    public static Acts liveActs() {
        return new Acts() {
            @Override public boolean have(String command) {
                try {
                    String probe = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "where" : "command";
                    List<String> cmd = probe.equals("where") ? List.of("where", command) : List.of("sh", "-c", "command -v " + command);
                    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                    p.getInputStream().readAllBytes();
                    return p.waitFor() == 0;
                } catch (Exception e) { return false; }
            }
            @Override public String register(String command, List<String> args) throws Exception {
                List<String> cmd = new ArrayList<>(); cmd.add(command); cmd.addAll(args);
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                String o = new String(p.getInputStream().readAllBytes());
                return p.waitFor() == 0 ? "connected" : "!" + o.strip();
            }
            @Override public String launcher() {
                Path root = SelfUpdate.root();
                if (root != null) {
                    Path bin = root.resolve("bin").resolve(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "codezaiku.bat" : "codezaiku");
                    if (Files.exists(bin)) return bin.toAbsolutePath().toString();
                }
                return "codezaiku";
            }
            @Override public String modelOffer() { return ModelServer.offer(); }
            @Override public String modelServe(PrintStream out) { return ModelServer.install(null, "all", ModelServer.DEFAULT_IDLE_MINUTES, false, out); }
            @Override public boolean libraryInstalled() { return ResearchZoshoInstall.installedVersion() != null; }
            @Override public int installLibrary(PrintStream out) { return ResearchZoshoInstall.door(new String[0], out); }
        };
    }

    String ask(String question, String dflt) throws IOException {
        String shown = dflt == null || dflt.isEmpty() ? "" : " [" + dflt + "]";
        out.print(question + shown + " ");
        out.flush();
        if (yes) { out.println(dflt == null ? "" : dflt); return dflt == null ? "" : dflt; }
        String line = in.readLine();
        if (line == null) return dflt == null ? "" : dflt;
        line = line.strip();
        return line.isEmpty() ? (dflt == null ? "" : dflt) : line;
    }

    boolean yesNo(String question, boolean dflt) throws IOException {
        String a = ask(question, dflt ? "Y/n" : "y/N");
        if (a.equals("Y/n")) return true;
        if (a.equals("y/N")) return false;
        a = a.toLowerCase(Locale.ROOT);
        return a.startsWith("y") || a.equals("yes") ? true : a.startsWith("n") ? false : dflt;
    }

    static boolean local(String base) {
        String b = base.toLowerCase(Locale.ROOT);
        return b.contains("localhost") || b.contains("127.0.0.1") || b.contains("://[::1]") || b.contains("://10.") || b.contains("://192.168.") || b.contains("://172.");
    }

    /** The wizard. {@code offerPrograms}: the MCP registration step; {@code offerLibrary}: the ResearchZosho step. */
    public int run(boolean offerPrograms, boolean offerLibrary) throws Exception {
        out.println("CodeZaiku setup. Your settings go in " + Config.userConfigPath() + "; every question has a default, Enter takes it.");
        out.println();

        // 1. the model server
        out.println("1. The model. CodeZaiku drives a model that speaks the OpenAI chat API: a local server (llama.cpp, Ollama, LM Studio)");
        out.println("   or a hosted API with a key (OpenAI, DeepSeek, Gemini, OpenRouter and others).");
        String base = null, model = null, key = Config.get("CODEZAIKU_API_KEY");
        String current = Config.get("CODEZAIKU_DRIVE");
        List<String> candidates = new ArrayList<>();
        if (current != null && !current.isBlank()) candidates.add(current);
        for (String u : USUAL) if (!candidates.contains(u)) candidates.add(u);
        for (String cand : candidates) {
            List<String> ids = probe.models(cand, local(cand) ? null : key);
            if (ids == null) continue;
            String first = ids.isEmpty() ? Config.get("CODEZAIKU_MODEL", "local-model") : ids.get(0);
            out.println("   Found a model server at " + cand + (ids.isEmpty() ? "." : " offering " + String.join(", ", ids.subList(0, Math.min(5, ids.size()))) + (ids.size() > 5 ? ", …" : "") + ".")
                    + (cand.equals(current) ? " It is the one in your settings." : ""));
            if (yesNo("   Use it?", true)) {
                base = cand;
                model = ids.size() > 1 ? ask("   Which model?", first) : first;
            }
            break;
        }
        if (base == null) {
            out.println("   No model server was found on the usual local ports.");
            String offer = acts.modelOffer();
            if (offer != null && yesNo("   " + offer + " Set it up?", true)) {
                String r = acts.modelServe(out);
                if (r.startsWith("!")) out.println("   not set up: " + r.substring(1));
                else { base = ModelServer.URL; model = r; }
            }
        }
        if (base == null) {
            String typed = ask("   Where is your model server? (an address such as http://localhost:11434 or https://api.openai.com/v1, or leave blank)", "");
            if (!typed.isBlank()) {
                base = typed;
                if (!local(base) && (key == null || key.isBlank())) {
                    String k = ask("   Does it need a key? (paste it, or leave blank)", "");
                    if (!k.isBlank()) key = k;
                }
                List<String> ids = probe.models(base, key);
                model = ask("   Which model?", ids != null && !ids.isEmpty() ? ids.get(0) : Config.get("CODEZAIKU_MODEL", "local-model"));
            }
        }
        if (base != null) {
            Config.set("CODEZAIKU_DRIVE", base);
            if (model != null && !model.isBlank()) Config.set("CODEZAIKU_MODEL", model);
            if (key != null && !key.isBlank() && !local(base)) Config.set("CODEZAIKU_API_KEY", key);
            out.print("   Asking it for one word" + (base.equals(ModelServer.URL) ? " (the first request starts the model; a moment)" : "") + "… ");
            out.flush();
            String reply = probe.chat(base, model == null ? "local-model" : model, local(base) ? null : key);
            out.println(reply.startsWith("!") ? "no: " + reply.substring(1) + "\n   Saved anyway; `codezaiku doctor` re-checks it." : "it answered: \"" + reply + "\"");
        } else {
            out.println("   No model server set. `codezaiku model detect` finds one later, `codezaiku model use <address>` names it.");
        }
        out.println();

        // 2. web search
        out.println("2. Web search, for research runs. A Brave Search API key (free plan) or a SearXNG address; without either the");
        out.println("   built-in fallback (Wikipedia, Crossref, OpenAlex) answers, which is reference pages and papers, not the web.");
        String haveBrave = Config.get("CODEZAIKU_BRAVE_KEY"), haveSearx = Config.get("CODEZAIKU_SEARXNG");
        String dfltSearch = haveBrave != null && !haveBrave.isBlank() ? "(the Brave key in your settings)" : haveSearx != null && !haveSearx.isBlank() ? haveSearx : "";
        String s = ask("   Brave key, SearXNG address, or blank", dfltSearch);
        if (s.startsWith("(")) out.println("   Keeping the Brave key in your settings.");
        else if (s.startsWith("http")) {
            if (probe.searxng(s)) { Config.set("CODEZAIKU_SEARXNG", s); out.println("   SearXNG answers at " + s + "."); }
            else out.println("   Nothing answered a JSON search at " + s + " (SearXNG needs json in search.formats); not saved.");
        } else if (!s.isBlank()) {
            if (probe.brave(s)) { Config.set("CODEZAIKU_BRAVE_KEY", s); out.println("   Brave Search accepts the key."); }
            else out.println("   Brave Search did not accept that key; not saved.");
        } else out.println("   The built-in fallback it is; `codezaiku config set CODEZAIKU_BRAVE_KEY <key>` any time.");
        out.println();

        // 3. programs
        if (offerPrograms) {
            out.println("3. Programs. Claude Code, Codex and Gemini CLI can each reach CodeZaiku over MCP from every session.");
            boolean any = false;
            for (Host h : HOSTS) {
                if (!acts.have(h.command()) || !yesNo("   " + h.name() + " is installed. Connect it to CodeZaiku?", true)) continue;
                List<String> args = switch (h.command()) {
                    case "claude" -> List.of("mcp", "add", "--scope", "user", "codezaiku", "--", acts.launcher(), "mcp");
                    case "codex" -> List.of("mcp", "add", "codezaiku", "--", acts.launcher(), "mcp");
                    default -> List.of("mcp", "add", "-s", "user", "codezaiku", acts.launcher(), "mcp");
                };
                String r = acts.register(h.command(), args);
                out.println(r.startsWith("!") ? "   Could not connect " + h.name() + ": " + r.substring(1) : "   " + h.name() + " is connected: every session can use CodeZaiku's tools.");
                any = true;
            }
            out.println("   " + (any ? "Any other" : "Any") + " program that speaks MCP takes the command \"" + acts.launcher() + "\" with the argument \"mcp\".");
            out.println();
        }

        // 4. the research library
        if (offerLibrary) {
            if (acts.libraryInstalled()) out.println("4. ResearchZosho, the research library, is installed; `codezaiku doctor` says whether its service answers.");
            else if (yesNo("4. ResearchZosho, the research library, keeps what research runs find and checks it. Install it? It fetches its release and asks its own questions.", false)) {
                int rc = acts.installLibrary(out);
                out.println(rc == 0 ? "   ResearchZosho is installed." : "   ResearchZosho did not install (see above). Later: codezaiku install researchzosho");
            } else out.println("   Skipped. Later: codezaiku install researchzosho");
            out.println();
        }

        out.println("Done. Your settings are in " + Config.userConfigPath() + ".");
        out.println("  codezaiku doctor      # checks everything above, names what is missing and the fix");
        out.println("  codezaiku chat        # sit down and talk to it");
        return 0;
    }
}
