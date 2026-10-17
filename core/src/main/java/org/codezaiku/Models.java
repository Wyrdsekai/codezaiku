package org.codezaiku;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.codezaiku.drive.DriveClient;
import org.codezaiku.drive.Auto;
import org.codezaiku.drive.SavedModels;
import org.codezaiku.drive.Spend;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
/**
 * Model endpoints: find them, name them, switch between them.
 *
 * CodeZaiku does not run an inference server of its own: llama.cpp does that, and every bug in a server of
 * ours would be ours to fix. What it owns is knowing which endpoints are live, keeping several under names,
 * switching the drive without editing a URL by hand — and, since 0.3.3, `model serve`: setting llama.cpp up
 * on this machine's card behind a proxy that starts it on demand and stops it when idle (ModelServer), the
 * measured model for the card, so nothing has to be up all the time.
 */
public final class Models {

    private static final ObjectMapper J = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    /** Local endpoints worth probing, with the server they usually mean. */
    private static final String[][] WELL_KNOWN = {
        {"http://localhost:11434", "Ollama"},
        {"http://localhost:8200",  "llama.cpp (CodeZaiku default port)"},
        {"http://localhost:8080",  "llama.cpp (its own default port)"},
        {"http://localhost:1234",  "LM Studio"},
        {"http://localhost:8000",  "vLLM"},
        {"http://localhost:5000",  "text-generation-webui"},
    };

    record Endpoint(String url, String server, List<String> models) { }

    static int command(String[] a) {
        String sub = a.length >= 1 ? a[0] : "list";
        try {
            return switch (sub) {
                case "detect" -> detect();
                case "list" -> list();
                case "add" -> add(a);
                case "set" -> set(a);
                case "use" -> use(a);
                case "remove" -> remove(a);
                case "key" -> key(a);
                case "test" -> test(a);
                case "auto" -> auto(a);
                case "private" -> priv(a);
                case "serve" -> ModelServer.command(a, 1, System.out);
                default -> {
                    System.err.println("""
                        usage:
                          codezaiku model serve install|status|stop|uninstall
                                                               the model on this machine, on demand: it comes up when asked and goes away
                                                               after 20 idle minutes (install [--file <gguf>] [--gpu <index>] [--idle-minutes N] [--share])
                          codezaiku model detect               find model servers running locally
                          codezaiku model list                 the saved models, their prices and caps, and which one is in use
                          codezaiku model add <name> <address> [<model id>] [--prices in/out[/cache]] [--effort e] [--cap dollars] [--note text]
                                                               save a model under a name: an address (or a host word: anthropic, openai,
                                                               openrouter, gemini, nebius, huggingface, deepseek), the id the server
                                                               selects by, dollars per million tokens, a monthly cap
                          codezaiku model set <name> [--address a] [--model id] [--prices p] [--effort e] [--cap c] [--tier A|B|C] [--note n]
                          codezaiku model auto [--kind code|review|research|chat] [--tier A|B|C] [--project <dir>] [--wait <minutes>]
                                                               what best judgement would pick for such a job, and why
                          codezaiku model private <project dir> on|off
                                                               mark a project private: only local models may read it
                          codezaiku model use <name|url>       make it the drive for every job (the entry called default)
                          codezaiku model remove <name>
                          codezaiku model key <host|name> (<key> | --file <path> | --remove)
                                                               keep a key for one host; it goes to that host and nowhere else
                          codezaiku model test <name>          ask the model for one word, and say what it cost

                        A job picks one with --model <name> (code, review, research, chat, run), /model <name> in the chat,
                        the model argument of the MCP tools, or the model option of an ACP session. `codezaiku spend` adds up
                        what the jobs cost.

                        CodeZaiku does not run or download models — it talks to a server you run.
                        See docs/MODELS.md for what it needs from one.
                        """);
                    yield 2;
                }
            };
        } catch (Exception e) {
            System.err.println("model: " + e.getMessage());
            return 1;
        }
    }

    // ── detect ──────────────────────────────────────────────────────────────

    private static int detect() {
        System.out.println("scanning common local endpoints…\n");
        List<Endpoint> found = new ArrayList<>();
        for (String[] wk : WELL_KNOWN) {
            Endpoint e = probe(wk[0], wk[1]);
            if (e != null) found.add(e);
        }
        if (found.isEmpty()) {
            System.out.println("  nothing found.\n");
            System.out.println("Start one — Ollama is the least setup:");
            System.out.println("  ollama serve && ollama pull <a tool-calling model>");
            System.out.println("  codezaiku model use http://localhost:11434\n");
            System.out.println("Or llama.cpp, which our reference numbers used:");
            System.out.println("  docker run -d -p 8200:8200 -v /path/to/models:/models \\");
            System.out.println("    ghcr.io/ggml-org/llama.cpp:server-cuda \\");
            System.out.println("    -m /models/<model>.gguf --port 8200 --host 0.0.0.0 --jinja --ctx-size 32768");
            System.out.println("\n(--jinja is required, or tool calls come back as prose)");
            return 1;
        }
        String current = Config.get("CODEZAIKU_DRIVE", "");
        for (Endpoint e : found) {
            System.out.printf("  %-32s %s%s%n", e.url(), e.server(),
                    e.url().equals(current) ? "   <- current drive" : "");
            for (String m : e.models()) System.out.println("      " + m);
        }
        System.out.println("\nuse one:  codezaiku model use " + found.get(0).url());
        System.out.println("then:     codezaiku smoke        # confirms it can actually call a tool");
        return 0;
    }

    /** Ask an endpoint what it is. Handles the OpenAI shape and Ollama's native one. */
    private static Endpoint probe(String base, String server) {
        List<String> models = fetchModels(base + "/v1/models", "data", "id");
        if (models == null) models = fetchModels(base + "/api/tags", "models", "name");
        if (models == null) return null;
        return new Endpoint(base, server, models);
    }

    /** The models a drive offers, by id, or an empty list when it will not say. For a host's model picker. */
    public static List<String> offered(String base) {
        List<String> models = fetchModels(base + "/v1/models", "data", "id", 50);
        if (models == null) models = fetchModels(base + "/api/tags", "models", "name", 50);
        return models == null ? List.of() : models;
    }

    private static List<String> fetchModels(String url, String arrayField, String nameField) { return fetchModels(url, arrayField, nameField, 4); }

    private static List<String> fetchModels(String url, String arrayField, String nameField, int limit) {
        try {
            HttpResponse<String> r = HTTP.send(
                    DriveClient.auth(
                            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3))).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            JsonNode root = J.readTree(r.body());
            JsonNode arr = root.path(arrayField);
            if (!arr.isArray()) arr = root.path("models");   // llama.cpp answers /v1/models with {models:[…]}
            List<String> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String name = n.path(nameField).asText("");
                if (name.isBlank()) name = n.path("name").asText("");
                if (name.isBlank()) name = n.path("id").asText("");
                if (!name.isBlank()) out.add(name);
                if (out.size() >= limit) break;
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    // ── named endpoints ─────────────────────────────────────────────────────

    private static int list() {
        SavedModels.Entry dflt = SavedModels.defaultEntry();
        System.out.println("drive: " + dflt.drive() + "   model: " + dflt.model() + (SavedModels.hasKey(dflt) ? "" : "   (no key kept for this host)") + "\n");
        Map<String, SavedModels.Entry> s = SavedModels.all();
        if (s.size() <= 1) {
            System.out.println("no saved models besides the default.");
            System.out.println("  codezaiku model detect              find what is running");
            System.out.println("  codezaiku model add big http://localhost:8201");
            System.out.println("  codezaiku model add deepseek openrouter deepseek/deepseek-v4.1-flash --prices 0.024/0.72/0.005 --cap 20");
            return 0;
        }
        System.out.println("saved:");
        for (SavedModels.Entry e : s.values()) {
            if (e.name().equals(SavedModels.DEFAULT)) continue;
            boolean inUse = Config.driveBase(e.drive()).equalsIgnoreCase(Config.driveBase(dflt.drive())) && e.model().equals(dflt.model());
            System.out.println("  " + e.line() + (SavedModels.hasKey(e) ? "" : "   [no key kept for " + e.host() + ": codezaiku model key " + e.name() + " --file <path>]") + (inUse ? "   <- in use" : ""));
        }
        System.out.println("\nA job picks one with --model <name>; `codezaiku model use <name>` makes it the default.");
        return 0;
    }

    /** The value after a flag, or null; the flag and its value are taken out of {@code rest}. */
    private static String opt(List<String> rest, String flag) {
        int i = rest.indexOf(flag);
        if (i < 0) return null;
        if (i + 1 >= rest.size()) throw new IllegalArgumentException(flag + " needs a value");
        String v = rest.get(i + 1);
        rest.remove(i + 1); rest.remove(i);
        return v;
    }

    private static int add(String[] a) throws IOException {
        List<String> rest = new ArrayList<>(Arrays.asList(a).subList(1, a.length));
        String prices = opt(rest, "--prices"), effort = opt(rest, "--effort"), cap = opt(rest, "--cap"), note = opt(rest, "--note");
        if (rest.size() < 2) { System.err.println("usage: codezaiku model add <name> <address|host word> [<model id>] [--prices in/out[/cache]] [--effort e] [--cap dollars] [--note text]"); return 2; }
        SavedModels.Entry e = SavedModels.put(rest.get(0), rest.get(1), rest.size() >= 3 ? rest.get(2) : null, prices, effort, cap, note);
        System.out.println("saved " + e.line());
        if (!SavedModels.hasKey(e)) System.out.println("no key is kept for " + e.host() + " yet:  codezaiku model key " + e.name() + " --file <path to the key>");
        System.out.println("use it for one job:  codezaiku code <project> \"…\" --model " + e.name() + "      for every job:  codezaiku model use " + e.name());
        return 0;
    }

    private static int set(String[] a) throws IOException {
        List<String> rest = new ArrayList<>(Arrays.asList(a).subList(1, a.length));
        String address = opt(rest, "--address"), id = opt(rest, "--model"), prices = opt(rest, "--prices"), effort = opt(rest, "--effort"), cap = opt(rest, "--cap"), note = opt(rest, "--note"), tier = opt(rest, "--tier");
        if (rest.size() != 1) { System.err.println("usage: codezaiku model set <name> [--address a] [--model id] [--prices p] [--effort e] [--cap dollars] [--tier A|B|C] [--note text]"); return 2; }
        if (address == null && id == null && prices == null && effort == null && cap == null && note == null && tier == null) { System.err.println("nothing to change: give --address, --model, --prices, --effort, --cap, --tier or --note"); return 2; }
        if (!rest.get(0).equalsIgnoreCase(SavedModels.DEFAULT) && SavedModels.get(rest.get(0)) == null) { System.err.println("no saved model named '" + rest.get(0) + "' — `codezaiku model list`"); return 1; }
        System.out.println("now " + SavedModels.put(rest.get(0), address, id, prices, effort, cap, note, tier).line());
        return 0;
    }

    private static int remove(String[] a) throws IOException {
        if (a.length < 2) { System.err.println("usage: codezaiku model remove <name>"); return 2; }
        System.out.println(SavedModels.remove(a[1]) ? "removed " + a[1] : "no saved model named " + a[1]);
        return 0;
    }

    /** The host a word names: a saved model's host, a host word's, or the host of an address or a bare host name. */
    static String hostFor(String word) {
        SavedModels.Entry e = SavedModels.nameOk(word).isEmpty() ? SavedModels.get(word) : null;
        if (e != null && !word.equalsIgnoreCase(SavedModels.DEFAULT)) return e.host();
        String address = SavedModels.hostAddress(word);
        String h = DriveClient.hostOf(address.contains("://") ? address : "https://" + address);
        return h == null || h.isBlank() ? null : h;
    }

    private static int key(String[] a) throws IOException {
        List<String> rest = new ArrayList<>(Arrays.asList(a).subList(1, a.length));
        String file = opt(rest, "--file");
        boolean drop = rest.remove("--remove");
        if (rest.isEmpty() || (!drop && file == null && rest.size() < 2)) { System.err.println("usage: codezaiku model key <host|saved name|host word> (<key> | --file <path> | --remove)"); return 2; }
        String host = hostFor(rest.get(0));
        if (host == null) { System.err.println("'" + rest.get(0) + "' names no host: give a host name (openrouter.ai), a host word (openrouter) or a saved model's name"); return 1; }
        String setting = DriveClient.hostKeySetting(host);
        if (drop) { System.out.println(Config.unset(setting) ? "the key for " + host + " is removed" : "no key was kept for " + host); return 0; }
        String key = file != null ? firstLine(Path.of(file)) : rest.get(1);
        if (key == null || key.isBlank()) { System.err.println("the key is empty"); return 1; }
        Config.set(setting, key.strip());
        System.out.println("kept the key for " + host + " in " + Config.userConfigPath() + " (as " + setting.toLowerCase(Locale.ROOT).replace("codezaiku_", "").replace('_', '.') + "); it goes to " + host + " and nowhere else.");
        return 0;
    }

    private static String firstLine(Path p) throws IOException {
        for (String l : Files.readAllLines(p)) if (!l.isBlank() && !l.strip().startsWith("#")) return l.strip();
        return null;
    }

    private static int test(String[] a) {
        if (a.length < 2) { System.err.println("usage: codezaiku model test <name>"); return 2; }
        SavedModels.Entry e = SavedModels.get(a[1]);
        if (e == null) { System.err.println("no saved model named '" + a[1] + "' — `codezaiku model list`"); return 1; }
        System.out.println("asking " + e.describe() + " for one word…");
        long[] usage = new long[4];
        DriveClient.countInto(usage);
        try {
            DriveClient d = new DriveClient(e.drive(), e.model()).effort(e.effort());
            var msgs = d.json().createArrayNode();
            msgs.addObject().put("role", "user").put("content", "Answer with the one word OK.");
            String said = d.chat(msgs, d.json().createArrayNode(), 64, "none").path("content").asText("").strip();
            System.out.println("it answered: " + (said.length() > 80 ? said.substring(0, 80) + "…" : said));
            System.out.println("that call: " + Spend.usageLine(usage, e.cost(usage), e.prices() != null) + (e.prices() == null ? "   (no prices saved: codezaiku model set " + e.name() + " --prices in/out)" : ""));
            return 0;
        } catch (RuntimeException ex) {
            Throwable t = ex; while (t.getCause() != null && t.getMessage() != null && t.getMessage().startsWith("chat() failed")) t = t.getCause();
            System.out.println("it did not answer: " + t.getMessage());
            return 1;
        } finally { DriveClient.stopCounting(); }
    }

    /** What best judgement would pick for a job of a kind, and why. */
    private static int auto(String[] a) {
        List<String> rest = new ArrayList<>(Arrays.asList(a).subList(1, a.length));
        String kind = opt(rest, "--kind"), tier = opt(rest, "--tier"), project = opt(rest, "--project"), wait = opt(rest, "--wait");
        kind = kind == null ? "code" : kind; tier = tier == null ? (kind.equals("research") ? "A" : "B") : tier.toUpperCase(Locale.ROOT);
        Auto.Job job = Auto.Job.of(kind, tier, true, project == null ? Path.of(System.getProperty("user.dir")) : Path.of(project));
        if (wait != null) job = job.waiting(Double.parseDouble(wait));
        if (!Auto.isDefault()) System.out.println(Config.isOn("CODEZAIKU_AUTO", true) ? "auto is not in charge yet: only the default model is saved (codezaiku model add … saves a second one)." : "auto is off (codezaiku config set auto on turns it on).");
        System.out.println("candidates:");
        for (SavedModels.Entry e : SavedModels.all().values()) {
            Auto.Candidate c = Auto.candidate(e, job);
            System.out.println("  " + e.name() + ": tier " + c.tier() + ", about " + String.format(Locale.ROOT, "%.0f", c.minutes()) + " min, " + (c.free() ? "free" : "about " + Spend.dollars(c.dollars())) + (c.reachable() ? "" : " — not now: " + c.whyNot()));
        }
        System.out.println(Auto.choose(job).why());
        return 0;
    }

    /** A project marked private may be read by local models only. */
    private static int priv(String[] a) throws IOException {
        if (a.length < 3 || !(a[2].equals("on") || a[2].equals("off"))) { System.err.println("usage: codezaiku model private <project dir> on|off"); return 2; }
        Path marker = Path.of(a[1]).toAbsolutePath().normalize().resolve(".codezaiku").resolve("private");
        if (a[2].equals("on")) { Files.createDirectories(marker.getParent()); Files.writeString(marker, "This project is private: best judgement sends its jobs to local models only.\n"); System.out.println("private: " + marker.getParent().getParent() + " — only local models may read it"); }
        else { System.out.println(Files.deleteIfExists(marker) ? "no longer private: " + marker.getParent().getParent() : "it was not marked private"); }
        return 0;
    }

    private static int use(String[] a) throws IOException {
        if (a.length < 2) { System.err.println("usage: codezaiku model use <name|url>"); return 2; }
        String target = a[1];
        SavedModels.Entry saved = target.startsWith("http") || !SavedModels.nameOk(target).isEmpty() ? null : SavedModels.get(target);
        String url = target.startsWith("http") ? target : saved == null ? null : saved.drive();
        if (url == null) {
            System.err.println("no saved model named '" + target + "' — `codezaiku model list`");
            return 1;
        }
        // Check it before committing: pointing the drive at a dead endpoint just moves the failure
        // to the next command, where it is harder to explain.
        Endpoint e = probe(url, "");
        Config.set("CODEZAIKU_DRIVE", url);
        if (saved != null && !saved.name().equals(SavedModels.DEFAULT)) Config.set("CODEZAIKU_MODEL", saved.model());
        System.out.println("drive = " + url + (saved != null && !saved.name().equals(SavedModels.DEFAULT) ? "   model = " + saved.model() : ""));
        if (e == null) {
            System.out.println("NOTE: nothing is answering there right now. Saved anyway — start the "
                    + "server, then `codezaiku doctor`.");
        } else {
            if (!e.models().isEmpty()) System.out.println("serving: " + String.join(", ", e.models()));
            System.out.println("check it:  codezaiku smoke");
        }
        return 0;
    }

    private Models() { }
}
