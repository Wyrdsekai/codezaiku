package org.codezaiku;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.File;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * The model server on this machine, on demand: a proxy (llama-swap) that starts llama.cpp on the first request and
 * stops it after a quiet spell, so the card is free in between and nothing has to be up all the time. Every program
 * points at the proxy, never at a server directly, so moving the model to another machine is one address.
 *
 * <p>{@code install} picks the measured model for what this machine has, fetches the model file and the proxy, puts
 * llama.cpp behind it, installs the proxy as a service that starts with the person's session on {@link #PORT}, and
 * points this program's drive at it. A proxy that already answers there — the other product's, or this one's from
 * before — is used as it is. On Linux the server is llama.cpp's CUDA container (Docker with the NVIDIA runtime); on
 * macOS its native Metal build sized by unified memory, as a launchd agent; on Windows its Vulkan build, which runs on
 * any card, as a logon task.
 *
 * <p>The seams ({@link #os}, {@link #runner}, {@link #downloader}, {@link #health}) let a test drive every platform's
 * install on any host, without a card, a network, docker or a service manager.
 */
public final class ModelServer {

    private ModelServer() { }

    public static final int PORT = 8211;
    public static final String URL = "http://127.0.0.1:" + PORT;
    public static final String UNIT = "codezaiku-model";                  // systemd --user unit (Linux)
    public static final String LABEL = "org.codezaiku.model";             // launchd agent (macOS)
    public static final String TASK = "CodeZaikuModel";                     // scheduled task (Windows)
    public static final String CONTAINER = "codezaiku-model";
    public static final String SWAP_VERSION = "255";
    public static final String LLAMA_BUILD = "b10929";
    public static final int DEFAULT_IDLE_MINUTES = 20;

    public enum Os {
        linux, macos, windows;
        static Os detect() {
            String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            return n.contains("win") ? windows : n.contains("mac") || n.contains("darwin") ? macos : linux;
        }
    }

    /** A measured model row: the memory it fits, the file and its sha256 as recorded from Hugging Face, and the llama.cpp flags the guide names for it. */
    public record Row(String name, int minGb, String hf, String file, String sha256, int ctx, int parallel, String extra) {
        String url() { return "https://huggingface.co/" + hf + "/resolve/main/" + file; }
    }

    // The 27B was measured on Qwen3.8-27B-Q4_K_M.gguf (2026-08-15); unsloth replaced that file with the UD quantization
    // of the same model, and only the UD file resolves now (found by a reviewer, 2026-09-12). `model serve check` reads every row.
    public static final List<Row> ROWS = List.of(
        new Row("qwen3.8-27b",  24, "unsloth/Qwen3.8-27B-GGUF",    "Qwen3.8-27B-UD-Q4_K_M.gguf", "322e194ff79741c7baa497c240f677f54b201b0efab44ca8e50f122b39123482", 131072, 4, "--chat-template-kwargs '{\"reasoning_effort\":\"low\"}'"),
        new Row("gpt-oss-20b",  16, "unsloth/gpt-oss-20b-GGUF",    "gpt-oss-20b-F16.gguf",       "4e4f9cd88d6456e4f389e7262eca4a8d565211e2b22ece9ca7a8556168ff3c66",  32768, 2, "--chat-template-kwargs '{\"reasoning_effort\":\"low\"}'"),
        new Row("qwen3.5-9b",    8, "unsloth/Qwen3.5-9B-GGUF",     "Qwen3.5-9B-Q4_K_M.gguf",     "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8",  16384, 1, "--chat-template-kwargs '{\"enable_thinking\":false}'"),
        new Row("gemma-4-e4b",   4, "unsloth/gemma-4-E4B-it-GGUF", "gemma-4-E4B-it-Q4_K_M.gguf", "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87",  16384, 1, ""),
        new Row("gemma-4-e2b",   2, "unsloth/gemma-4-E2B-it-GGUF", "gemma-4-E2B-it-Q4_K_M.gguf", "740185b21d22ceb83a11c3aa62ad5842ef32c70f6096d756bbee85a1e4ec34b8",  16384, 1, ""));

    /**
     * What every download must hash to: the model files above, llama-swap's assets from its published checksums, and
     * llama.cpp's pinned build, which publishes none, hashed when the build was pinned. A download that does not match
     * is not installed — the same rule as the one-line installer and the npm launcher. A test may put its own entries here.
     */
    public static final Map<String, String> sums = new ConcurrentHashMap<>(Map.ofEntries(
        Map.entry("llama-swap_255_linux_amd64.tar.gz",   "84aa0df0cf3e302a8591e39de347f64c0c7dce1c3a948df68723a82e1fb4f1d4"),
        Map.entry("llama-swap_255_linux_arm64.tar.gz",   "98686bc626e2d3df3b340b963fd4e4f4d3dd02dcd1bf31f0c777fb09e3053288"),
        Map.entry("llama-swap_255_darwin_arm64.tar.gz",  "d11b4c733da1c64ffd1b955f64b6af92a8c66a443aeeafeef2e84d3bf1fbf531"),
        Map.entry("llama-swap_255_darwin_amd64.tar.gz",  "98383f95298919cd6a73fcb11dadc78d53754bfe5d3de519fdead112f336f707"),
        Map.entry("llama-swap_255_windows_amd64.zip",    "14b40b2e11479af9a83dec3ac2bf7f82864e62099f012b171591b871b9f88aae"),
        Map.entry("llama-b10929-bin-macos-arm64.tar.gz", "d2367a6381911313944cf6e52da44b1d62a5c239f7a79fd4ddeb4001c301544b"),
        Map.entry("llama-b10929-bin-macos-x64.tar.gz",   "09621ac7f7636a6577075aba3bf40db4e22203aa2cac87c2dff7532982f89313"),
        Map.entry("llama-b10929-bin-win-vulkan-x64.zip", "0527be2bcb79797337c4c500e6c25a62e0cdb572b14ef2db1066c94706208936")));
    static { for (Row r : ROWS) sums.put(r.file(), r.sha256()); }

    static String sha256(Path p) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(p)) { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Fetch {@code url} to {@code dest} and check it against the recorded hash; a mismatch is deleted and refused. */
    static void fetchChecked(String url, Path dest) throws Exception {
        downloader.fetch(url, dest);
        String want = sums.get(dest.getFileName().toString());
        if (want == null) return;   // a file the person named with --file has no recorded hash; the rows and the pinned builds do
        String got = sha256(dest);
        if (!want.equalsIgnoreCase(got)) { Files.deleteIfExists(dest); throw new IOException("checksum mismatch for " + dest.getFileName() + "; refusing to install\n  expected " + want + "\n  got      " + got); }
    }

    /** The row for {@code gb} of memory for the model, or null when no measured model fits. */
    public static Row rowFor(double gb) {
        for (Row r : ROWS) if (gb >= r.minGb()) return r;
        return null;
    }

    // ---- seams ----

    public interface Runner { Result run(List<String> cmd) throws Exception; }
    public record Result(int code, String out) { }
    /** Fetch {@code url} to {@code dest}, showing progress to the person; throws on failure. */
    public interface Downloader { void fetch(String url, Path dest) throws Exception; }

    public static Os os = Os.detect();
    /**
     * Start something that keeps running (the Windows proxy through its start script) without holding its output pipe:
     * a child that inherits our stdout would keep a runner's read open forever. Nothing is read; failure to spawn is the
     * only error.
     */
    public interface Detacher { void start(List<String> cmd) throws Exception; }
    public static Detacher detach = cmd -> {
        Process p = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectInput(ProcessBuilder.Redirect.from(new File(os == Os.windows ? "NUL" : "/dev/null"))).start();
        p.waitFor(10, TimeUnit.SECONDS);   // the starter itself returns at once; the proxy it launched lives on
    };
    public static Runner runner = cmd -> {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(p.waitFor(), out);
    };
    public static Downloader downloader = (url, dest) -> {
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        Process p = new ProcessBuilder(os == Os.windows ? "curl.exe" : "curl", "-fL", "--progress-bar", "-o", part.toString(), url).inheritIO().start();
        if (p.waitFor() != 0) throw new IOException("download failed: " + url);
        Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
    };
    /** Whether a proxy answers at {@code base}: llama-swap's /health says OK; any other server's model list will do. */
    public static Predicate<String> health = base -> {
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(base + "/health")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) return true;
            r = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/models")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) { return false; }
    };

    /** The ports another program's model server usually listens on: Wyrdsekai's drive, llama.cpp, vLLM, Ollama, LM Studio. */
    static final List<Integer> SHARED_PORTS = List.of(8200, 8080, 8000, 11434, 1234);

    /**
     * The models CodeZaiku uses when another program's server already serves one, best first: the rows it installs, and Qwen3.6-35B-A3B,
     * which Wyrdsekai serves as its brain and ResearchZosho installs, but which CodeZaiku does not install itself.
     */
    static final List<String> SHARED_MODELS = List.of("qwen3.8-27b", "qwen3.6-35b-a3b", "gpt-oss-20b", "qwen3.5-9b", "gemma-4-e4b", "gemma-4-e2b");

    /** What a server serves: the names its /v1/models lists, and the file llama.cpp's /props says it loaded (a server started with --alias lists only the alias). */
    public record Served(List<String> ids, String file) { }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the server at {@code base} serves; null when nothing answers there. */
    public static Function<String, Served> servedAt = base -> {
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/models")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            List<String> ids = new ArrayList<>();
            for (JsonNode m : JSON.readTree(r.body()).path("data")) if (m.hasNonNull("id")) ids.add(m.get("id").asText());
            String file = null;
            try {
                HttpResponse<String> p = c.send(HttpRequest.newBuilder(URI.create(base + "/props")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (p.statusCode() == 200) file = JSON.readTree(p.body()).path("model_path").asText(null);
            } catch (Exception ignored) { }
            return new Served(ids, file);
        } catch (Exception e) { return null; }
    };

    /** A server another program runs on this machine that serves one of {@link #SHARED_MODELS}: its address, the name to ask it for, which model it is. */
    record Shared(String base, String model, String known) { }

    /** The shared server with the best known model on the usual ports, or null. */
    static Shared shared() {
        Shared best = null;
        for (int port : SHARED_PORTS) {
            String base = "http://127.0.0.1:" + port;
            Served s = servedAt.apply(base);
            if (s == null) continue;
            List<Shared> here = new ArrayList<>();
            for (String id : s.ids()) { String k = knownModel(id); if (k != null) here.add(new Shared(base, id, k)); }
            if (here.isEmpty() && s.file() != null && s.ids().size() == 1) {
                String k = knownModel(s.file().substring(Math.max(s.file().lastIndexOf('/'), s.file().lastIndexOf('\\')) + 1));
                if (k != null) here.add(new Shared(base, s.ids().get(0), k));
            }
            for (Shared h : here) if (best == null || SHARED_MODELS.indexOf(h.known()) < SHARED_MODELS.indexOf(best.known())) best = h;
        }
        return best;
    }

    /** Which known model a served one is, by the letters and digits of its name or file ("Qwen3.6-35B-A3B-UD-Q4_K_M.gguf", "qwen3.6:35b-a3b"); null for any other. */
    static String knownModel(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String k : SHARED_MODELS) if (s.contains(k.replaceAll("[^a-z0-9]", ""))) return k;
        return null;
    }

    /** Point CodeZaiku at another program's server that serves a known model, and say what that means. */
    static String useShared(Shared s, PrintStream out) throws IOException {
        out.println("  Another program already runs a model server on this machine, at " + s.base() + ", with the model " + s.model()
                + (s.model().equals(s.known()) ? "" : " (" + s.known() + ")") + ". CodeZaiku uses that server, so nothing is downloaded"
                + " and the model is in memory once for every program that uses it.");
        out.println("  To install a model of CodeZaiku's own instead: codezaiku model serve install --own");
        Config.set("CODEZAIKU_DRIVE", s.base());
        Config.set("CODEZAIKU_MODEL", s.model());
        return s.model();
    }

    // ---- what this machine is ----

    public static Path dir() { return Config.home().resolve("model"); }
    public static Path modelsDir() { return Path.of(System.getProperty("user.home")).resolve("models"); }
    static String home() { return System.getProperty("user.home"); }

    /** The largest NVIDIA card's memory in GB, rounded; 0 when nvidia-smi does not answer. */
    static double nvidiaGb() {
        try {
            Result r = runner.run(List.of("nvidia-smi", "--query-gpu=memory.total", "--format=csv,noheader,nounits"));
            if (r.code() != 0) return 0;
            double best = 0;
            for (String line : r.out().split("\\R")) { String t = line.strip(); if (!t.isEmpty()) best = Math.max(best, Math.round(Double.parseDouble(t) / 1024.0)); }
            return best;
        } catch (Exception e) { return 0; }
    }

    /** The machine's RAM in GB, 0 when unknown. */
    static double ramGb() {
        try {
            Result r = switch (os) {
                case macos -> runner.run(List.of("sysctl", "-n", "hw.memsize"));
                case windows -> runner.run(List.of("powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory"));
                case linux -> runner.run(List.of("sh", "-c", "grep MemTotal /proc/meminfo | awk '{print $2*1024}'"));
            };
            if (r.code() != 0) return 0;
            return Long.parseLong(r.out().strip().split("\\R")[0].strip()) / 1073741824.0;
        } catch (Exception e) { return 0; }
    }

    /**
     * Memory the model may take, in GB: an NVIDIA card's on Linux; on macOS about seven tenths of unified memory, which is
     * what the system lets the GPU wire; on Windows the NVIDIA card where there is one, else half the RAM, which is what an
     * integrated card shares.
     */
    public static double budgetGb() {
        return switch (os) {
            case linux -> nvidiaGb();
            case macos -> Math.floor(ramGb() * 0.7);
            case windows -> { double n = nvidiaGb(); yield n > 0 ? n : Math.floor(ramGb() * 0.5); }
        };
    }

    /** What the server runs on here, for the offer: "the Ada" is not known, the backend is. */
    static String backend() { return switch (os) { case linux -> "CUDA in Docker"; case macos -> "Metal"; case windows -> "Vulkan"; }; }

    /** Why this machine cannot serve a model on demand, or null when it can. */
    public static String unsupported() {
        switch (os) {
            case linux -> {
                try {
                    Result d = runner.run(List.of("docker", "info", "--format", "{{.Runtimes}}"));
                    if (d.code() != 0) return "docker is not on this machine (on Linux the model runs in llama.cpp's container)";
                    if (!d.out().contains("nvidia")) return "docker here has no NVIDIA runtime (install nvidia-container-toolkit)";
                } catch (Exception e) { return "docker is not on this machine (on Linux the model runs in llama.cpp's container)"; }
                if (nvidiaGb() <= 0) return "no NVIDIA card answers nvidia-smi";
            }
            case macos -> { }
            case windows -> { if (!arch().equals("amd64")) return "Windows on arm64 has no Vulkan build of llama.cpp yet"; }
        }
        double gb = budgetGb();
        if (gb <= 0) return "could not tell how much memory the model may take";
        if (rowFor(gb) == null) return "about " + (int) gb + " GB for the model; the smallest measured model wants 2 GB";
        return null;
    }

    /** One sentence for setup: what install would do here, or null when unsupported. */
    public static String offer() {
        if (health.test(URL)) return "A model proxy already answers at " + URL + "; use it for the drive.";
        if (unsupported() != null) return null;
        Row r = rowFor(budgetGb());
        return "Serve " + r.name() + " on this machine on demand (" + backend() + "): it downloads once" + sizeNote(r) + ", starts when a run needs it, and stops after "
                + DEFAULT_IDLE_MINUTES + " idle minutes.";
    }

    static String sizeNote(Row r) {
        try {
            Result h = runner.run(List.of(os == Os.windows ? "curl.exe" : "curl", "-sIL", r.url()));
            Matcher m = Pattern.compile("(?i)content-length:\\s*(\\d+)").matcher(h.out());
            long n = 0; while (m.find()) n = Long.parseLong(m.group(1));
            return n > 0 ? " (about " + Math.round(n / 1e9) + " GB)" : "";
        } catch (Exception e) { return ""; }
    }

    static String arch() {
        String a = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return a.contains("aarch64") || a.contains("arm64") ? "arm64" : "amd64";
    }

    // ---- the files the install writes ----

    /** Everything install writes: the proxy config, the run script (Linux), and the service definition for this platform. */
    public record Plan(Row row, Path modelFile, Path dir, Path unit, String configYaml, String runScript, String unitText) { }

    static Path unitPath(Os os) {
        return switch (os) {
            case linux -> Path.of(home(), ".config", "systemd", "user", UNIT + ".service");
            case macos -> Path.of(home(), "Library", "LaunchAgents", LABEL + ".plist");
            case windows -> dir().resolve("start.ps1");
        };
    }

    /** llama.cpp's flags for a row on a bare server, with the port the proxy assigns and quoting the platform's shell reads. */
    static String serverArgs(Row row, Path modelFile, Os os) {
        String extra = os == Os.windows ? windowsQuoted(row.extra()) : row.extra();
        return "-m " + modelFile + " --host 127.0.0.1 --port ${PORT} --jinja -c " + row.ctx() + " --parallel " + row.parallel() + " -ngl 99 --flash-attn on" + (extra.isEmpty() ? "" : " " + extra);
    }

    /** {@code --chat-template-kwargs '{"k":"v"}'} in the form a Windows command line reads: double quotes, the inner ones escaped. */
    static String windowsQuoted(String extra) {
        Matcher m = Pattern.compile("^--chat-template-kwargs '(.*)'$").matcher(extra);
        if (!m.matches()) return extra;
        return "--chat-template-kwargs \"" + m.group(1).replace("\"", "\\\"") + "\"";
    }

    public static Plan plan(Os os, Row row, Path modelFile, Path dir, Path unit, String gpus, int idleMinutes, boolean share) {
        String cmd = switch (os) {
            case linux -> dir.resolve("run.sh") + " ${PORT}";
            case macos -> dir.resolve("llama").resolve("llama-server") + " " + serverArgs(row, modelFile, os);
            case windows -> dir.resolve("llama").resolve("llama-server.exe") + " " + serverArgs(row, modelFile, os);
        };
        String cfg = "# CodeZaiku: the model server on demand. llama-swap listens on " + PORT + "; the server starts on the first request\n"
                + "# and stops after ttl seconds idle. `codezaiku model serve status` reads it.\n"
                + "healthCheckTimeout: 600\n"
                + "startPort: 10001\n"
                + "logLevel: info\n"
                + "models:\n"
                + "  \"" + row.name() + "\":\n"
                + "    cmd: " + cmd + "\n"
                + (os == Os.linux ? "    cmdStop: docker stop " + CONTAINER + "\n" : "")
                + "    proxy: http://127.0.0.1:${PORT}\n"
                + "    ttl: " + (idleMinutes * 60) + "\n"
                + "    aliases: [\"local-model\", \"" + row.file() + "\", \"/m/" + row.file() + "\"]\n";
        String run = os != Os.linux ? null : "#!/bin/sh\n"
                + "# " + row.name() + " in llama.cpp, started by llama-swap on demand; $1 is the port it assigned. The flags are the measured ones.\n"
                + "exec docker run --rm --name " + CONTAINER + " --gpus " + (gpus.equals("all") ? "all" : "'\"device=" + gpus + "\"'")
                + " -p 127.0.0.1:$1:8080 -v " + modelFile.getParent() + ":/m ghcr.io/ggml-org/llama.cpp:server-cuda \\\n"
                + "  -m /m/" + modelFile.getFileName() + " --host 0.0.0.0 --port 8080 --jinja -c " + row.ctx() + " --parallel " + row.parallel() + " -ngl 99 --flash-attn on"
                + (row.extra().isEmpty() ? "" : " \\\n  " + row.extra()) + "\n";
        String listen = (share ? "0.0.0.0" : "127.0.0.1") + ":" + PORT;
        Path swap = dir.resolve(os == Os.windows ? "llama-swap.exe" : "llama-swap");
        String unitText = switch (os) {
            case linux -> "[Unit]\nDescription=CodeZaiku: the model server on demand (llama-swap)\nAfter=network-online.target docker.service\n\n"
                    + "[Service]\nExecStart=" + swap + " --config " + dir.resolve("config.yaml") + " --listen " + listen + "\n"
                    + "Restart=always\nRestartSec=5\n\n[Install]\nWantedBy=default.target\n";
            case macos -> "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                    + "<plist version=\"1.0\"><dict>\n  <key>Label</key><string>" + LABEL + "</string>\n"
                    + "  <key>ProgramArguments</key><array>\n    <string>" + swap + "</string>\n    <string>--config</string>\n    <string>" + dir.resolve("config.yaml") + "</string>\n"
                    + "    <string>--listen</string>\n    <string>" + listen + "</string>\n  </array>\n"
                    + "  <key>RunAtLoad</key><true/>\n  <key>KeepAlive</key><true/>\n"
                    + "  <key>StandardOutPath</key><string>" + dir.resolve("llama-swap.log") + "</string>\n  <key>StandardErrorPath</key><string>" + dir.resolve("llama-swap.log") + "</string>\n"
                    + "</dict></plist>\n";
            case windows -> "# CodeZaiku: start the model proxy (llama-swap) in the background; a logon task runs this, and `codezaiku model serve install` ran it once now\n"
                    + "if (-not (Get-Process llama-swap -ErrorAction SilentlyContinue)) {\n"
                    + "  Start-Process -FilePath '" + swap + "' -ArgumentList '--config','" + dir.resolve("config.yaml") + "','--listen','" + listen + "' -WindowStyle Hidden -RedirectStandardOutput '" + dir.resolve("llama-swap.log") + "' -RedirectStandardError '" + dir.resolve("llama-swap.err") + "'\n}\n";
        };
        return new Plan(row, modelFile, dir, unit, cfg, run, unitText);
    }

    /** The service, started: systemd --user, launchd, or a logon task plus a start now. "!reason" on failure. */
    static String startService(Os os, Plan p) throws Exception {
        switch (os) {
            case linux -> {
                Result r = runner.run(List.of("systemctl", "--user", "daemon-reload"));
                if (r.code() != 0) return "!systemctl --user daemon-reload: " + r.out();
                r = runner.run(List.of("systemctl", "--user", "enable", "--now", UNIT));
                if (r.code() != 0) return "!systemctl --user enable --now " + UNIT + ": " + r.out();
                try { runner.run(List.of("loginctl", "enable-linger", System.getProperty("user.name"))); } catch (Exception ignored) { }
            }
            case macos -> {
                String uid = uid();
                runner.run(List.of("launchctl", "bootout", "gui/" + uid + "/" + LABEL));   // an earlier one, if any
                Result r = runner.run(List.of("launchctl", "bootstrap", "gui/" + uid, p.unit().toString()));
                if (r.code() != 0) return "!launchctl bootstrap: " + r.out();
            }
            case windows -> {
                String tr = "powershell -NoProfile -ExecutionPolicy Bypass -File \"" + p.unit() + "\"";
                Result r = runner.run(List.of("schtasks", "/create", "/tn", TASK, "/sc", "onlogon", "/tr", tr, "/f"));
                if (r.code() != 0) return "!schtasks /create: " + r.out();
                detach.start(List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", p.unit().toString()));   // now, not at the next logon
            }
        }
        return "";
    }

    static String uid() {
        try { Result r = runner.run(List.of("id", "-u")); return r.out().strip(); } catch (Exception e) { return "501"; }
    }

    /** Unpack an archive into {@code into}: a tarball, or a zip through the same tar on Windows and macOS. */
    static String unpack(Path archive, Path into, boolean stripTop) throws Exception {
        Files.createDirectories(into);
        List<String> cmd = new ArrayList<>(List.of(os == Os.windows ? "tar.exe" : "tar", archive.toString().endsWith(".zip") ? "-xf" : "-xzf", archive.toString(), "-C", into.toString()));
        if (stripTop) cmd.add("--strip-components=1");
        Result r = runner.run(cmd);
        return r.code() == 0 ? "" : "!could not unpack " + archive.getFileName() + ": " + r.out();
    }

    /**
     * Install: returns the model's name, or "!reason". {@code file} names a model file already on disk (any GGUF) instead of
     * the measured row's; {@code gpus} is "all" or a device index (Linux); {@code share} listens on every interface so other
     * machines can use this card.
     */
    public static String install(Path file, String gpus, int idleMinutes, boolean share, PrintStream out) { return install(file, gpus, idleMinutes, share, true, out); }

    /**
     * {@code own}: install a model of CodeZaiku's own even when another program's server on this machine already serves a known one;
     * without it, and with no file named, CodeZaiku uses that server and downloads nothing.
     */
    public static String install(Path file, String gpus, int idleMinutes, boolean share, boolean own, PrintStream out) {
        try {
            if (health.test(URL)) {
                out.println("  a model proxy already answers at " + URL + "; using it");
                Config.set("CODEZAIKU_DRIVE", URL);
                return "local-model";
            }
            Shared there = own || file != null ? null : shared();
            if (there != null) return useShared(there, out);
            String why = unsupported();
            if (why != null) return "!" + why;
            Row row = rowFor(budgetGb());
            Path modelFile;
            if (file != null) {
                if (!Files.isRegularFile(file)) return "!no such file: " + file;
                modelFile = file.toAbsolutePath();
                row = new Row(stem(file.getFileName().toString()), 0, "", file.getFileName().toString(), "", row.ctx(), row.parallel(), "");
            } else {
                Files.createDirectories(modelsDir());
                modelFile = modelsDir().resolve(row.file());
                if (!Files.isRegularFile(modelFile)) {
                    out.println("  downloading " + row.file() + sizeNote(row) + " into " + modelsDir());
                    fetchChecked(row.url(), modelFile);
                } else out.println("  " + row.file() + " is already in " + modelsDir());
            }
            Path dir = dir();
            Files.createDirectories(dir);
            // the proxy
            Path swap = dir.resolve(os == Os.windows ? "llama-swap.exe" : "llama-swap");
            if (!Files.exists(swap)) {
                String asset = "llama-swap_" + SWAP_VERSION + "_" + (os == Os.macos ? "darwin" : os.name()) + "_" + arch() + (os == Os.windows ? ".zip" : ".tar.gz");
                out.println("  fetching the proxy, llama-swap v" + SWAP_VERSION);
                Path a = dir.resolve(asset);
                fetchChecked("https://github.com/mostlygeek/llama-swap/releases/download/v" + SWAP_VERSION + "/" + asset, a);
                String u = unpack(a, dir, false);
                if (!u.isEmpty()) return u;
                Files.deleteIfExists(a);
                swap.toFile().setExecutable(true);
            }
            // the server: llama.cpp's own build on macOS (Metal) and Windows (Vulkan); the container on Linux
            if (os != Os.linux) {
                Path server = dir.resolve("llama").resolve(os == Os.windows ? "llama-server.exe" : "llama-server");
                if (!Files.exists(server)) {
                    String asset = os == Os.macos ? "llama-" + LLAMA_BUILD + "-bin-macos-" + (arch().equals("arm64") ? "arm64" : "x64") + ".tar.gz"
                                                  : "llama-" + LLAMA_BUILD + "-bin-win-vulkan-x64.zip";
                    out.println("  fetching llama.cpp " + LLAMA_BUILD + " for " + backend());
                    Path a = dir.resolve(asset);
                    fetchChecked("https://github.com/ggml-org/llama.cpp/releases/download/" + LLAMA_BUILD + "/" + asset, a);
                    String u = unpack(a, dir.resolve("llama"), os == Os.macos);   // the macOS tarball has one top folder; the zip is flat
                    if (!u.isEmpty()) return u;
                    Files.deleteIfExists(a);
                    if (os == Os.macos) server.toFile().setExecutable(true);
                }
            }
            // what the drive was, so uninstall can put it back (a working address on another machine, say)
            String before = Config.get("CODEZAIKU_DRIVE"), beforeModel = Config.get("CODEZAIKU_MODEL");
            if (!Files.exists(dir.resolve("previous")))
                Files.writeString(dir.resolve("previous"), (before == null ? "" : before) + "\n" + (beforeModel == null ? "" : beforeModel) + "\n", StandardCharsets.UTF_8);
            Plan p = plan(os, row, modelFile, dir, unitPath(os), gpus, idleMinutes, share);
            Files.writeString(dir.resolve("config.yaml"), p.configYaml(), StandardCharsets.UTF_8);
            if (p.runScript() != null) { Files.writeString(dir.resolve("run.sh"), p.runScript(), StandardCharsets.UTF_8); dir.resolve("run.sh").toFile().setExecutable(true); }
            Files.createDirectories(p.unit().getParent());
            Files.writeString(p.unit(), p.unitText(), StandardCharsets.UTF_8);
            String s = startService(os, p);
            if (!s.isEmpty()) return s;
            for (int i = 0; i < 30 && !health.test(URL); i++) Thread.sleep(1000);
            if (!health.test(URL)) return "!the proxy did not answer at " + URL + " within 30 s: " + logHint();
            Config.set("CODEZAIKU_DRIVE", URL);
            Config.set("CODEZAIKU_MODEL", row.name());
            out.println("  the model comes up at " + URL + " when something asks (" + backend() + "), and goes away after " + idleMinutes + " idle minutes; the drive is set to it");
            return row.name();
        } catch (Exception e) {
            return "!" + e.getMessage();
        }
    }

    static String logHint() {
        return switch (os) { case linux -> "journalctl --user -u " + UNIT; case macos, windows -> dir().resolve("llama-swap.log").toString(); };
    }

    /** `model serve check`: does every row's file still resolve where the row says, and the pinned builds too? For the release gate. */
    public static int check(PrintStream out) {
        int bad = 0;
        List<String> urls = new ArrayList<>();
        for (Row r : ROWS) urls.add(r.url());
        for (String a : new String[]{"linux_amd64.tar.gz", "linux_arm64.tar.gz", "darwin_arm64.tar.gz", "darwin_amd64.tar.gz", "windows_amd64.zip"}) urls.add("https://github.com/mostlygeek/llama-swap/releases/download/v" + SWAP_VERSION + "/llama-swap_" + SWAP_VERSION + "_" + a);
        for (String a : new String[]{"macos-arm64.tar.gz", "macos-x64.tar.gz", "win-vulkan-x64.zip"}) urls.add("https://github.com/ggml-org/llama.cpp/releases/download/" + LLAMA_BUILD + "/llama-" + LLAMA_BUILD + "-bin-" + a);
        int limited = 0;
        for (String u : urls) {
            String status = "";
            // a host that is rate-limiting (429) says nothing about the file: wait and ask again, up to three times
            for (int attempt = 0; attempt < 3; attempt++) {
                if (attempt > 0) { try { Thread.sleep(checkBackoffMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; } }
                try {
                    Result h = runner.run(List.of(os == Os.windows ? "curl.exe" : "curl", "-sIL", "-o", os == Os.windows ? "NUL" : "/dev/null", "-w", "%{http_code}", u));
                    status = h.out().strip();
                } catch (Exception e) { status = "no curl"; }
                if (!status.equals("429")) break;
            }
            boolean ok = status.equals("200"), rateLimited = status.equals("429");
            if (rateLimited) limited++; else if (!ok) bad++;
            out.println("  " + (ok ? "ok  " : rateLimited ? "WAIT" : "GONE") + " " + status + "  " + u + (rateLimited ? "  (the host is rate-limiting this machine; not checked, not proof of absence)" : ""));
        }
        if (bad == 0 && limited == 0) out.println("  every row and pinned build resolves");
        if (bad > 0) out.println("  " + bad + " missing: re-point the row (and re-record its sha256) before a release");
        if (limited > 0) out.println("  " + limited + " not checked: the host answered 429 (rate-limited) three times; nothing is known to be missing, run the check again later");
        return bad == 0 ? 0 : 1;
    }

    /** The pause between attempts when a host answers 429; a test sets it to 0. */
    static long checkBackoffMs = 20_000;

    /** The other product's settings file, when it is not this product's own; its drive line, or null. */
    static String siblingDrive() {
        Path own = Config.userConfigPath().toAbsolutePath();
        Path sib = Path.of(home(), ".researchzosho", "config").toAbsolutePath();
        if (sib.equals(own) || !Files.isRegularFile(sib)) return null;
        try {
            for (String line : Files.readAllLines(sib, StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (t.startsWith("drive") && t.contains("=")) return t.substring(t.indexOf('=') + 1).strip();
            }
        } catch (IOException ignored) { }
        return null;
    }

    static String stem(String name) { int i = name.lastIndexOf('.'); return (i > 0 ? name.substring(0, i) : name).toLowerCase(Locale.ROOT); }

    /** Whether the service is registered, per platform. */
    static String serviceState() {
        try {
            return switch (os) {
                case linux -> runner.run(List.of("systemctl", "--user", "is-active", UNIT)).out().strip();
                case macos -> runner.run(List.of("launchctl", "print", "gui/" + uid() + "/" + LABEL)).code() == 0 ? "loaded" : "not loaded";
                case windows -> runner.run(List.of("schtasks", "/query", "/tn", TASK)).code() == 0 ? "task registered" : "no task";
            };
        } catch (Exception e) { return "unknown"; }
    }

    /** A few lines for `model serve status`. */
    public static String status() {
        StringBuilder b = new StringBuilder();
        String drive = Config.get("CODEZAIKU_DRIVE");
        b.append("  drive: ").append(drive == null || drive.isBlank() ? "(unset)" : drive).append('\n');
        Path cfg = dir().resolve("config.yaml");
        if (!Files.exists(cfg)) { b.append("  no model server of this machine's own (`codezaiku model serve install` sets one up)\n"); return b.toString(); }
        try {
            String y = Files.readString(cfg, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"([^\"]+)\":\\s*\\n\\s*cmd:").matcher(y);
            String name = m.find() ? m.group(1) : "?";
            Matcher t = Pattern.compile("ttl:\\s*(\\d+)").matcher(y);
            b.append("  model: ").append(name).append(" on ").append(backend()).append(t.find() ? ", stops after " + (Integer.parseInt(t.group(1)) / 60) + " idle minutes" : "").append('\n');
        } catch (IOException e) { b.append("  config unreadable: ").append(e.getMessage()).append('\n'); }
        boolean up = health.test(URL);
        b.append("  proxy at ").append(URL).append(": ").append(up ? "answers" : "does not answer (service " + serviceState() + "; log: " + logHint() + ")").append('\n');
        if (up) {
            try {
                HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
                String running = c.send(HttpRequest.newBuilder(URI.create(URL + "/running")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
                b.append("  loaded now: ").append(running.contains("\"model\"") ? "yes (the memory is in use)" : "no (the memory is free; the next request starts it)").append('\n');
            } catch (Exception ignored) { }
        }
        return b.toString();
    }

    /** Unload the model now (the proxy stays; the next request starts it again). */
    public static String stop() {
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(URL + "/unload")).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() < 300 ? "unloaded; the memory is free" : "!the proxy answered " + r.statusCode();
        } catch (Exception e) { return "!no proxy answers at " + URL; }
    }

    /** Remove the service and the proxy; the model files in ~/models stay; the drive goes back to what it was before install. */
    public static String uninstall() { return uninstall(false); }

    public static String uninstall(boolean force) {
        try {
            String sib = siblingDrive();
            if (!force && URL.equals(sib)) return "!ResearchZosho's settings also point at this proxy (" + Path.of(home(), ".researchzosho", "config") + "); point it elsewhere first, or pass --force";
            switch (os) {
                case linux -> { runner.run(List.of("systemctl", "--user", "disable", "--now", UNIT)); Files.deleteIfExists(unitPath(os)); runner.run(List.of("systemctl", "--user", "daemon-reload")); }
                case macos -> { runner.run(List.of("launchctl", "bootout", "gui/" + uid() + "/" + LABEL)); Files.deleteIfExists(unitPath(os)); }
                case windows -> { runner.run(List.of("schtasks", "/delete", "/tn", TASK, "/f")); runner.run(List.of("taskkill", "/im", "llama-swap.exe", "/f")); runner.run(List.of("taskkill", "/im", "llama-server.exe", "/f")); }
            }
            Path d = dir();
            String restored = "";
            Path prev = d.resolve("previous");
            if (Files.exists(prev)) {
                String[] lines = Files.readString(prev, StandardCharsets.UTF_8).split("\n", -1);
                String drive = lines.length > 0 ? lines[0].strip() : "", model = lines.length > 1 ? lines[1].strip() : "";
                if (URL.equals(Config.get("CODEZAIKU_DRIVE"))) {   // still pointing at the proxy being removed: put the old address back
                    Config.set("CODEZAIKU_DRIVE", drive.isEmpty() ? "http://localhost:8200" : drive);
                    Config.set("CODEZAIKU_MODEL", model.isEmpty() ? "local-model" : model);
                    restored = "; the drive is back to " + (drive.isEmpty() ? "its default" : drive);
                }
            }
            if (Files.isDirectory(d)) { try (var s = Files.walk(d)) { s.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } }); } }
            return "removed the service and the proxy; the model files in " + modelsDir() + " stay" + restored;
        } catch (Exception e) { return "!" + e.getMessage(); }
    }

    /** `model serve …` from the command line: install [--file F] [--gpu N] [--idle-minutes N] [--share] | status | stop | uninstall. */
    public static int command(String[] a, int from, PrintStream out) {
        String op = a.length > from ? a[from] : "status";
        switch (op) {
            case "install" -> {
                Path file = null; String gpus = "all"; int idle = DEFAULT_IDLE_MINUTES; boolean share = false, own = false;
                for (int i = from + 1; i < a.length; i++) {
                    switch (a[i]) {
                        case "--file" -> file = Path.of(a[++i]);
                        case "--gpu" -> gpus = a[++i];
                        case "--idle-minutes" -> idle = Integer.parseInt(a[++i]);
                        case "--share" -> share = true;
                        case "--own" -> own = true;
                        default -> { out.println("usage: codezaiku model serve install [--own] [--file <gguf>] [--gpu <index>] [--idle-minutes N] [--share]"); return 2; }
                    }
                }
                String r = install(file, gpus, idle, share, own, out);
                if (r.startsWith("!")) { out.println("  not set up: " + r.substring(1)); return 1; }
                out.println("  serving " + r + " at " + Config.get("CODEZAIKU_DRIVE"));
                return 0;
            }
            case "status" -> { out.print(status()); return 0; }
            case "stop" -> { String r = stop(); out.println("  " + (r.startsWith("!") ? r.substring(1) : r)); return r.startsWith("!") ? 1 : 0; }
            case "uninstall" -> { String r = uninstall(a.length > from + 1 && a[from + 1].equals("--force")); out.println("  " + (r.startsWith("!") ? r.substring(1) : r)); return r.startsWith("!") ? 1 : 0; }
            case "check" -> { return check(out); }
            default -> { out.println("usage: codezaiku model serve install|status|stop|uninstall [--force]|check"); return 2; }
        }
    }
}
