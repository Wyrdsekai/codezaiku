package org.codezaiku;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The model server on this machine, on demand: a proxy (llama-swap) that starts llama.cpp on the first request and
 * stops it after a quiet spell, so the card is free in between and nothing has to be up all the time. Every program
 * points at the proxy, never at a server directly, so moving the model to another machine is one address.
 *
 * <p>{@code install} picks the measured model for the card, fetches the model file and the proxy, writes the
 * proxy's config and run script, installs it as a user service on {@link #PORT}, and points this program's drive at
 * it. A proxy that already answers there — the other product's, or this one's from before — is used as it is.
 * Linux with an NVIDIA card and Docker in this release; elsewhere the guide's recipe is printed.
 *
 * <p>The seams ({@link #runner}, {@link #downloader}, {@link #health}) let a test drive every step without a card,
 * a network or docker.
 */
public final class ModelServer {

    private ModelServer() { }

    public static final int PORT = 8211;
    public static final String URL = "http://127.0.0.1:" + PORT;
    public static final String UNIT = "codezaiku-model";
    public static final String CONTAINER = "codezaiku-model";
    public static final String SWAP_VERSION = "255";
    public static final int DEFAULT_IDLE_MINUTES = 20;

    /** A measured model row: the card it fits, the file, and the llama.cpp flags the guide names for it. */
    public record Row(String name, int minGb, String hf, String file, int ctx, int parallel, String extra) {
        String url() { return "https://huggingface.co/" + hf + "/resolve/main/" + file; }
    }

    public static final List<Row> ROWS = List.of(
        new Row("qwen3.8-27b",  24, "unsloth/Qwen3.8-27B-GGUF",        "Qwen3.8-27B-Q4_K_M.gguf",        131072, 4, "--chat-template-kwargs '{\"reasoning_effort\":\"low\"}'"),
        new Row("gpt-oss-20b",  16, "unsloth/gpt-oss-20b-GGUF",        "gpt-oss-20b-F16.gguf",            32768, 2, "--chat-template-kwargs '{\"reasoning_effort\":\"low\"}'"),
        new Row("qwen3.5-9b",    8, "unsloth/Qwen3.5-9B-GGUF",         "Qwen3.5-9B-Q4_K_M.gguf",          16384, 1, "--chat-template-kwargs '{\"enable_thinking\":false}'"),
        new Row("gemma-4-e4b",   4, "unsloth/gemma-4-E4B-it-GGUF",     "gemma-4-E4B-it-Q4_K_M.gguf",      16384, 1, ""),
        new Row("gemma-4-e2b",   2, "unsloth/gemma-4-E2B-it-GGUF",     "gemma-4-E2B-it-Q4_K_M.gguf",      16384, 1, ""));

    /** The row for a card of {@code gb}, or null when no measured model fits. */
    public static Row rowFor(double gb) {
        for (Row r : ROWS) if (gb >= r.minGb()) return r;
        return null;
    }

    // ---- seams ----

    public interface Runner { Result run(List<String> cmd) throws Exception; }
    public record Result(int code, String out) { }
    /** Fetch {@code url} to {@code dest}, showing progress to the person; throws on failure. */
    public interface Downloader { void fetch(String url, Path dest) throws Exception; }

    public static Runner runner = cmd -> {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(p.waitFor(), out);
    };
    public static Downloader downloader = (url, dest) -> {
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        Process p = new ProcessBuilder("curl", "-fL", "--progress-bar", "-o", part.toString(), url).inheritIO().start();
        if (p.waitFor() != 0) throw new IOException("download failed: " + url);
        Files.move(part, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    };
    /** Whether a proxy answers at {@code base}: llama-swap's /health says OK; any other server's model list will do. */
    public static java.util.function.Predicate<String> health = base -> {
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(base + "/health")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) return true;
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/models")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) { return false; }
    };

    // ---- what this machine is ----

    public static Path dir() { return org.codezaiku.Config.home().resolve("model"); }
    public static Path modelsDir() { return Path.of(System.getProperty("user.home")).resolve("models"); }

    /** The card's memory in GB, rounded to the nearest, 0 when nvidia-smi does not answer. */
    public static double cardGb() {
        try {
            Result r = runner.run(List.of("nvidia-smi", "--query-gpu=memory.total", "--format=csv,noheader,nounits"));
            if (r.code() != 0) return 0;
            double best = 0;
            for (String line : r.out().split("\\R")) { String t = line.strip(); if (!t.isEmpty()) best = Math.max(best, Math.round(Double.parseDouble(t) / 1024.0)); }
            return best;
        } catch (Exception e) { return 0; }
    }

    /** Why this machine cannot serve a model on demand, or null when it can. */
    public static String unsupported() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) return "serving on demand is set up on Linux with an NVIDIA card in this release; on " + (os.contains("mac") ? "macOS" : "Windows") + " run llama.cpp yourself and set the drive to it (the guide has the lines)";
        try {
            Result d = runner.run(List.of("docker", "info", "--format", "{{.Runtimes}}"));
            if (d.code() != 0) return "docker is not on this machine (the model runs in llama.cpp's container)";
            if (!d.out().contains("nvidia")) return "docker here has no NVIDIA runtime (install nvidia-container-toolkit)";
        } catch (Exception e) { return "docker is not on this machine (the model runs in llama.cpp's container)"; }
        double gb = cardGb();
        if (gb <= 0) return "no NVIDIA card answers nvidia-smi";
        if (rowFor(gb) == null) return "the card has " + (int) gb + " GB; the smallest measured model wants 2 GB";
        return null;
    }

    /** One sentence for setup: what install would do here, or null when unsupported. */
    public static String offer() {
        if (health.test(URL)) return "A model proxy already answers at " + URL + "; use it for the drive.";
        if (unsupported() != null) return null;
        Row r = rowFor(cardGb());
        return "Serve " + r.name() + " on this machine on demand: it downloads once" + sizeNote(r) + ", starts when a run needs it, and stops after "
                + DEFAULT_IDLE_MINUTES + " idle minutes.";
    }

    static String sizeNote(Row r) {
        try {
            Result h = runner.run(List.of("curl", "-sIL", r.url()));
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)content-length:\\s*(\\d+)").matcher(h.out());
            long n = 0; while (m.find()) n = Long.parseLong(m.group(1));
            return n > 0 ? " (about " + Math.round(n / 1e9) + " GB)" : "";
        } catch (Exception e) { return ""; }
    }

    // ---- the files the install writes ----

    public record Plan(Row row, Path modelFile, Path dir, Path unit, String configYaml, String runScript, String unitText) { }

    /** Everything install would write, computed without touching the machine. */
    public static Plan plan(Row row, Path modelFile, Path dir, Path unit, String gpus, int idleMinutes, boolean share) {
        String cfg = "# CodeZaiku: the model server on demand. llama-swap listens on " + PORT + "; the server starts on the first request\n"
                + "# and stops after ttl seconds idle. Edit and `systemctl --user restart " + UNIT + "`; `codezaiku model serve status` reads it.\n"
                + "healthCheckTimeout: 600\n"
                + "startPort: 10001\n"
                + "logLevel: info\n"
                + "models:\n"
                + "  \"" + row.name() + "\":\n"
                + "    cmd: " + dir.resolve("run.sh") + " ${PORT}\n"
                + "    cmdStop: docker stop " + CONTAINER + "\n"
                + "    proxy: http://127.0.0.1:${PORT}\n"
                + "    ttl: " + (idleMinutes * 60) + "\n"
                + "    aliases: [\"local-model\", \"" + row.file() + "\", \"/m/" + row.file() + "\"]\n";
        String run = "#!/bin/sh\n"
                + "# " + row.name() + " in llama.cpp, started by llama-swap on demand; $1 is the port it assigned. The flags are the measured ones.\n"
                + "exec docker run --rm --name " + CONTAINER + " --gpus " + (gpus.equals("all") ? "all" : "'\"device=" + gpus + "\"'")
                + " -p 127.0.0.1:$1:8080 -v " + modelFile.getParent() + ":/m ghcr.io/ggml-org/llama.cpp:server-cuda \\\n"
                + "  -m /m/" + modelFile.getFileName() + " --host 0.0.0.0 --port 8080 --jinja -c " + row.ctx() + " --parallel " + row.parallel() + " -ngl 99 --flash-attn on"
                + (row.extra().isEmpty() ? "" : " \\\n  " + row.extra()) + "\n";
        String unitText = "[Unit]\nDescription=CodeZaiku: the model server on demand (llama-swap)\nAfter=network-online.target docker.service\n\n"
                + "[Service]\nExecStart=" + dir.resolve("llama-swap") + " --config " + dir.resolve("config.yaml") + " --listen " + (share ? "0.0.0.0" : "127.0.0.1") + ":" + PORT + "\n"
                + "Restart=always\nRestartSec=5\n\n[Install]\nWantedBy=default.target\n";
        return new Plan(row, modelFile, dir, unit, cfg, run, unitText);
    }

    static Path unitPath() { return Path.of(System.getProperty("user.home")).resolve(".config").resolve("systemd").resolve("user").resolve(UNIT + ".service"); }

    static String arch() {
        String a = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return a.contains("aarch64") || a.contains("arm64") ? "arm64" : "amd64";
    }

    /**
     * Install: returns the model's name, or "!reason". {@code file} names a model file already on disk (any GGUF) instead of
     * the measured row's; {@code gpus} is "all" or a device index; {@code share} listens on every interface so other
     * machines can use this card.
     */
    public static String install(Path file, String gpus, int idleMinutes, boolean share, PrintStream out) {
        try {
            if (health.test(URL)) {
                out.println("  a model proxy already answers at " + URL + "; using it");
                org.codezaiku.Config.set("CODEZAIKU_DRIVE", URL);
                return "local-model";
            }
            String why = unsupported();
            if (why != null) return "!" + why;
            Row row = rowFor(cardGb());
            Path modelFile;
            if (file != null) {
                if (!Files.isRegularFile(file)) return "!no such file: " + file;
                modelFile = file.toAbsolutePath();
                row = new Row(stem(file.getFileName().toString()), 0, "", file.getFileName().toString(), row.ctx(), row.parallel(), "");
            } else {
                Files.createDirectories(modelsDir());
                modelFile = modelsDir().resolve(row.file());
                if (!Files.isRegularFile(modelFile)) {
                    out.println("  downloading " + row.file() + sizeNote(row) + " into " + modelsDir());
                    downloader.fetch(row.url(), modelFile);
                } else out.println("  " + row.file() + " is already in " + modelsDir());
            }
            Path dir = dir();
            Files.createDirectories(dir);
            Path swap = dir.resolve("llama-swap");
            if (!Files.isExecutable(swap)) {
                String asset = "llama-swap_" + SWAP_VERSION + "_linux_" + arch() + ".tar.gz";
                out.println("  fetching the proxy, llama-swap v" + SWAP_VERSION);
                Path tgz = dir.resolve(asset);
                downloader.fetch("https://github.com/mostlygeek/llama-swap/releases/download/v" + SWAP_VERSION + "/" + asset, tgz);
                Result x = runner.run(List.of("tar", "xzf", tgz.toString(), "-C", dir.toString(), "llama-swap"));
                if (x.code() != 0) return "!could not unpack " + asset + ": " + x.out();
                Files.deleteIfExists(tgz);
                swap.toFile().setExecutable(true);
            }
            // what the drive was, so uninstall can put it back (a working address on another machine, say)
            String before = org.codezaiku.Config.get("CODEZAIKU_DRIVE"), beforeModel = org.codezaiku.Config.get("CODEZAIKU_MODEL");
            if (!Files.exists(dir.resolve("previous")))
                Files.writeString(dir.resolve("previous"), (before == null ? "" : before) + "\n" + (beforeModel == null ? "" : beforeModel) + "\n", StandardCharsets.UTF_8);
            Plan p = plan(row, modelFile, dir, unitPath(), gpus, idleMinutes, share);
            Files.writeString(dir.resolve("config.yaml"), p.configYaml(), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("run.sh"), p.runScript(), StandardCharsets.UTF_8);
            dir.resolve("run.sh").toFile().setExecutable(true);
            Files.createDirectories(p.unit().getParent());
            Files.writeString(p.unit(), p.unitText(), StandardCharsets.UTF_8);
            Result r = runner.run(List.of("systemctl", "--user", "daemon-reload"));
            if (r.code() != 0) return "!systemctl --user daemon-reload: " + r.out();
            r = runner.run(List.of("systemctl", "--user", "enable", "--now", UNIT));
            if (r.code() != 0) return "!systemctl --user enable --now " + UNIT + ": " + r.out();
            try { runner.run(List.of("loginctl", "enable-linger", System.getProperty("user.name"))); } catch (Exception ignored) { }
            for (int i = 0; i < 30 && !health.test(URL); i++) Thread.sleep(1000);
            if (!health.test(URL)) return "!the proxy did not answer at " + URL + " within 30 s: journalctl --user -u " + UNIT;
            org.codezaiku.Config.set("CODEZAIKU_DRIVE", URL);
            org.codezaiku.Config.set("CODEZAIKU_MODEL", row.name());
            out.println("  the model comes up at " + URL + " when something asks, and goes away after " + idleMinutes + " idle minutes; the drive is set to it");
            return row.name();
        } catch (Exception e) {
            return "!" + e.getMessage();
        }
    }

    static String stem(String name) { int i = name.lastIndexOf('.'); return (i > 0 ? name.substring(0, i) : name).toLowerCase(Locale.ROOT); }

    /** A few lines for `model status`. */
    public static String status() {
        StringBuilder b = new StringBuilder();
        String drive = org.codezaiku.Config.get("CODEZAIKU_DRIVE");
        b.append("  drive: ").append(drive == null || drive.isBlank() ? "(unset)" : drive).append('\n');
        Path cfg = dir().resolve("config.yaml");
        if (!Files.exists(cfg)) { b.append("  no model server of this machine's own (`codezaiku model serve install` sets one up)\n"); return b.toString(); }
        try {
            String y = Files.readString(cfg, StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]+)\":\\s*\\n\\s*cmd:").matcher(y);
            String name = m.find() ? m.group(1) : "?";
            java.util.regex.Matcher t = java.util.regex.Pattern.compile("ttl:\\s*(\\d+)").matcher(y);
            b.append("  model: ").append(name).append(t.find() ? ", stops after " + (Integer.parseInt(t.group(1)) / 60) + " idle minutes" : "").append('\n');
        } catch (IOException e) { b.append("  config unreadable: ").append(e.getMessage()).append('\n'); }
        boolean up = health.test(URL);
        b.append("  proxy at ").append(URL).append(": ").append(up ? "answers" : "does not answer (systemctl --user status " + UNIT + ")").append('\n');
        if (up) {
            try {
                HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
                String running = c.send(HttpRequest.newBuilder(URI.create(URL + "/running")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
                b.append("  loaded now: ").append(running.contains("\"model\"") ? "yes (the card is in use)" : "no (the card is free; the next request starts it)").append('\n');
            } catch (Exception ignored) { }
        }
        return b.toString();
    }

    /** Unload the model now (the proxy stays; the next request starts it again). */
    public static String stop() {
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(URL + "/unload")).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() < 300 ? "unloaded; the card is free" : "!the proxy answered " + r.statusCode();
        } catch (Exception e) { return "!no proxy answers at " + URL; }
    }

    /** Remove the service and the proxy; the model files in ~/models stay; the drive goes back to what it was before install. */
    public static String uninstall() {
        try {
            runner.run(List.of("systemctl", "--user", "disable", "--now", UNIT));
            Files.deleteIfExists(unitPath());
            runner.run(List.of("systemctl", "--user", "daemon-reload"));
            Path d = dir();
            String restored = "";
            Path prev = d.resolve("previous");
            if (Files.exists(prev)) {
                String[] lines = Files.readString(prev, StandardCharsets.UTF_8).split("\n", -1);
                String drive = lines.length > 0 ? lines[0].strip() : "", model = lines.length > 1 ? lines[1].strip() : "";
                if (URL.equals(org.codezaiku.Config.get("CODEZAIKU_DRIVE"))) {   // still pointing at the proxy being removed: put the old address back
                    org.codezaiku.Config.set("CODEZAIKU_DRIVE", drive.isEmpty() ? "http://localhost:8200" : drive);
                    org.codezaiku.Config.set("CODEZAIKU_MODEL", model.isEmpty() ? "local-model" : model);
                    restored = "; the drive is back to " + (drive.isEmpty() ? "its default" : drive);
                }
            }
            if (Files.isDirectory(d)) { try (var s = Files.walk(d)) { s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } }); } }
            return "removed the service and the proxy; the model files in " + modelsDir() + " stay" + restored;
        } catch (Exception e) { return "!" + e.getMessage(); }
    }

    /** `model …` from the command line: install [--file F] [--gpu N] [--idle-minutes N] [--share] | status | stop | uninstall. */
    public static int command(String[] a, int from, PrintStream out) {
        String op = a.length > from ? a[from] : "status";
        switch (op) {
            case "install" -> {
                Path file = null; String gpus = "all"; int idle = DEFAULT_IDLE_MINUTES; boolean share = false;
                for (int i = from + 1; i < a.length; i++) {
                    switch (a[i]) {
                        case "--file" -> file = Path.of(a[++i]);
                        case "--gpu" -> gpus = a[++i];
                        case "--idle-minutes" -> idle = Integer.parseInt(a[++i]);
                        case "--share" -> share = true;
                        default -> { out.println("usage: codezaiku model serve install [--file <gguf>] [--gpu <index>] [--idle-minutes N] [--share]"); return 2; }
                    }
                }
                String r = install(file, gpus, idle, share, out);
                if (r.startsWith("!")) { out.println("  not set up: " + r.substring(1)); return 1; }
                out.println("  serving " + r + " at " + URL);
                return 0;
            }
            case "status" -> { out.print(status()); return 0; }
            case "stop" -> { String r = stop(); out.println("  " + (r.startsWith("!") ? r.substring(1) : r)); return r.startsWith("!") ? 1 : 0; }
            case "uninstall" -> { String r = uninstall(); out.println("  " + (r.startsWith("!") ? r.substring(1) : r)); return r.startsWith("!") ? 1 : 0; }
            default -> { out.println("usage: codezaiku model serve install|status|stop|uninstall"); return 2; }
        }
    }
}
