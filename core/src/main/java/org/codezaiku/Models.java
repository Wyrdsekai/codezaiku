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

/**
 * Model endpoints: find them, name them, switch between them.
 *
 * CodeZaiku does not run an inference server of its own: llama.cpp does that, and every bug in a server of
 * ours would be ours to fix. What it owns is knowing which endpoints are live, keeping several under names,
 * switching the drive without editing a URL by hand — and, since 0.3.3, `model serve`: setting llama.cpp up
 * on this machine's card behind a proxy that starts it on demand and stops it when idle (ModelServer), the
 * measured model for the card, so nothing has to be up all the time.
 */
final class Models {

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
                case "use" -> use(a);
                case "remove" -> remove(a);
                case "serve" -> ModelServer.command(a, 1, System.out);
                default -> {
                    System.err.println("""
                        usage:
                          codezaiku model serve install|status|stop|uninstall
                                                               the model on this machine, on demand: it comes up when asked and goes away
                                                               after 20 idle minutes (install [--file <gguf>] [--gpu <index>] [--idle-minutes N] [--share])
                          codezaiku model detect               find model servers running locally
                          codezaiku model list                 saved endpoints, and which one is in use
                          codezaiku model add <name> <url>     save an endpoint under a name
                          codezaiku model use <name|url>       point the drive at it
                          codezaiku model remove <name>

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

    private static List<String> fetchModels(String url, String arrayField, String nameField) {
        try {
            HttpResponse<String> r = HTTP.send(
                    org.codezaiku.drive.DriveClient.auth(
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
                if (out.size() >= 4) break;
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    // ── named endpoints ─────────────────────────────────────────────────────

    /** Saved endpoints live in the config as `model.<name>.url`, so they travel with everything else. */
    private static Map<String, String> saved() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : Config.keysWithPrefix("CODEZAIKU_MODEL_")) {
            if (!key.endsWith("_URL")) continue;
            String name = key.substring("CODEZAIKU_MODEL_".length(), key.length() - "_URL".length())
                    .toLowerCase(Locale.ROOT).replace('_', '-');
            out.put(name, Config.get(key));
        }
        return out;
    }

    private static int list() {
        String current = Config.get("CODEZAIKU_DRIVE", "(unset)");
        System.out.println("drive: " + current + "\n");
        Map<String, String> s = saved();
        if (s.isEmpty()) {
            System.out.println("no saved endpoints.");
            System.out.println("  codezaiku model detect              find what is running");
            System.out.println("  codezaiku model add big http://localhost:8201");
            return 0;
        }
        System.out.println("saved:");
        s.forEach((name, url) -> System.out.printf("  %-14s %s%s%n", name, url,
                url.equals(current) ? "   <- in use" : ""));
        return 0;
    }

    private static int add(String[] a) throws IOException {
        if (a.length < 3) { System.err.println("usage: codezaiku model add <name> <url>"); return 2; }
        String key = "CODEZAIKU_MODEL_" + a[1].toUpperCase(Locale.ROOT).replace('-', '_') + "_URL";
        Config.set(key, a[2]);
        System.out.println("saved " + a[1] + " = " + a[2]);
        System.out.println("use it:  codezaiku model use " + a[1]);
        return 0;
    }

    private static int remove(String[] a) throws IOException {
        if (a.length < 2) { System.err.println("usage: codezaiku model remove <name>"); return 2; }
        String key = "CODEZAIKU_MODEL_" + a[1].toUpperCase(Locale.ROOT).replace('-', '_') + "_URL";
        System.out.println(Config.unset(key) ? "removed " + a[1] : "no saved endpoint named " + a[1]);
        return 0;
    }

    private static int use(String[] a) throws IOException {
        if (a.length < 2) { System.err.println("usage: codezaiku model use <name|url>"); return 2; }
        String target = a[1];
        String url = target.startsWith("http") ? target : saved().get(target);
        if (url == null) {
            System.err.println("no saved endpoint named '" + target + "' — `codezaiku model list`");
            return 1;
        }
        // Check it before committing: pointing the drive at a dead endpoint just moves the failure
        // to the next command, where it is harder to explain.
        Endpoint e = probe(url, "");
        Config.set("CODEZAIKU_DRIVE", url);
        System.out.println("drive = " + url);
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
