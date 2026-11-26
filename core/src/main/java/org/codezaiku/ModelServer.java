package org.codezaiku;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.codezaiku.drive.DriveClient;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import java.io.File;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
/**
 * The model server on this machine, on demand: a proxy (llama-swap) that starts llama.cpp on the first request and
 * stops it after a quiet spell, so the card is free in between and nothing has to be up all the time. Every program
 * points at the proxy, never at a server directly, so moving the model to another machine is one address.
 *
 * <p>{@code install} picks the measured model for what this machine has, fetches the model file and the proxy, puts
 * llama.cpp behind it, installs the proxy as a service that starts with the person's session on {@link #PORT}, and
 * points this program's drive at it. A proxy that already answers there — the other product's, or this one's from
 * before — is used as it is. On Linux the server is llama.cpp's container for the GPU the kernel names ({@link Gpu}):
 * CUDA on an NVIDIA card (Docker with the NVIDIA runtime), ROCm on an AMD Instinct card, Vulkan on a Radeon, an AMD APU
 * or an Intel GPU (Docker with the kernel's render devices passed through); on macOS its native Metal build sized by
 * unified memory, as a launchd agent; on Windows its Vulkan build, which runs on any card, as a logon task.
 *
 * <p>On Linux the container is set up with no way out to the network ({@link Shut}), proven before the install ends, or set up
 * the earlier way when that does not hold on the machine, and said.
 *
 * <p>The seams ({@link #os}, {@link #runner}, {@link #downloader}, {@link #health}, {@link #asker}) let a test drive every
 * platform's install on any host, without a card, a network, docker or a service manager.
 */
public final class ModelServer {

    private ModelServer() { }

    /**
     * A second model server on one machine, for a trial of the real install beside the machine's own: its port
     * ({@code -Dcodezaiku.model.port}) and the first part of every name it makes on Linux, the container, the service and the
     * network ({@code -Dcodezaiku.model.name}). Unset, they are what they always were.
     */
    static final String NAMED = System.getProperty("codezaiku.model.name", "codezaiku");
    public static final int PORT = Integer.getInteger("codezaiku.model.port", 8211);
    public static final String URL = "http://127.0.0.1:" + PORT;
    public static final String UNIT = NAMED + "-model";                      // systemd --user unit (Linux)
    public static final String LABEL = "org.codezaiku.model";             // launchd agent (macOS)
    public static final String TASK = "CodeZaikuModel";                     // scheduled task (Windows)
    public static final String CONTAINER = NAMED + "-model";
    /** The Docker network the model's container is on when it has no way out ({@link Shut}). ResearchZosho's is researchzosho-model-shut. */
    public static final String NETWORK = NAMED + "-model-shut";
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

    /**
     * A measured model row: the memory it fits; the file at a revision of its repository (a commit), its sha256 and its size in bytes,
     * as Hugging Face gives them; and the llama.cpp flags the guide names for it. The download names the revision, so a file
     * re-uploaded under the same name changes nothing for an install. The size is what doctor and setup say the download is; until
     * 0.3.20 they asked Hugging Face for it each time no model answered. {@code model serve check}, which the release build runs,
     * compares the file at the revision with the row and says when the file on main has moved on.
     */
    public record Row(String name, int minGb, String hf, String revision, String file, String sha256, long bytes, int ctx, int parallel, String extra) {
        /** The file at the row's revision: what install downloads. */
        String url() { return hfBase() + "/" + hf + "/resolve/" + revision + "/" + file; }
        /** The file as it is on main now, which the check compares with the row to say when a newer one is out. */
        String mainUrl() { return hfBase() + "/" + hf + "/resolve/main/" + file; }
    }

    /** Hugging Face, where the rows' files are. A test points it at a stand-in on the loopback. */
    static String hfBase() { return System.getProperty("codezaiku.huggingface", "https://huggingface.co"); }

    // The 27B was measured on Qwen3.8-27B-Q4_K_M.gguf (2026-08-15); unsloth replaced that file with the UD quantization
    // of the same model, and only the UD file resolves now (found by a reviewer, 2026-09-12). `model serve check` reads every row.
    // On 2026-11-11 (commit acb953b1) unsloth re-made the UD file under the same name; the download, addressed at main, then
    // fetched 16.5 GB that did not match the row and refused it. Each row now names the commit its file is taken at: for the 27B
    // the August revision that holds the file every 27B number was measured on, for the other four the commit that last changed
    // their file. Revisions, hashes and sizes as Hugging Face gave them on 2026-11-26.
    public static final List<Row> ROWS = List.of(
        new Row("qwen3.8-27b",  24, "unsloth/Qwen3.8-27B-GGUF",    "4ca720788d1e01f1bff70c033e0d0028fd02e502", "Qwen3.8-27B-UD-Q4_K_M.gguf", "322e194ff79741c7baa497c240f677f54b201b0efab44ca8e50f122b39123482", 16_464_440_224L, 131072, 4, "--chat-template-kwargs '{\"reasoning_effort\":\"low\"}'"),
        new Row("gpt-oss-20b",  16, "unsloth/gpt-oss-20b-GGUF",    "a1b6de7b9f9540e2e00885ca1a96614a187c9c3a", "gpt-oss-20b-F16.gguf",       "4e4f9cd88d6456e4f389e7262eca4a8d565211e2b22ece9ca7a8556168ff3c66", 13_792_639_168L,  32768, 2, "--chat-template-kwargs '{\"reasoning_effort\":\"low\"}'"),
        new Row("qwen3.5-9b",    8, "unsloth/Qwen3.5-9B-GGUF",     "99a1b2185534379e6e8b5ec869da25d3e7b3f73c", "Qwen3.5-9B-Q4_K_M.gguf",     "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8",  5_680_522_464L,  16384, 1, "--chat-template-kwargs '{\"enable_thinking\":false}'"),
        new Row("gemma-4-e4b",   4, "unsloth/gemma-4-E4B-it-GGUF", "bfc15c382204943c3a8fff0c750b94ae2364d7a3", "gemma-4-E4B-it-Q4_K_M.gguf", "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87",  4_977_171_584L,  16384, 1, ""),
        new Row("gemma-4-e2b",   2, "unsloth/gemma-4-E2B-it-GGUF", "0314792d7f1f7e229411f620751375812bb9faf2", "gemma-4-E2B-it-Q4_K_M.gguf", "740185b21d22ceb83a11c3aa62ad5842ef32c70f6096d756bbee85a1e4ec34b8",  3_106_738_272L,  16384, 1, ""));

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
    /** How long one command may take before it is killed: a docker CLI whose daemon is not running can wait forever. */
    static final int COMMAND_SECONDS = 120;
    static final Runner REAL_RUNNER = cmd -> {
        Path out = Files.createTempFile("codezaiku-cmd", ".out");
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(out.toFile()).start();
            if (!p.waitFor(COMMAND_SECONDS, TimeUnit.SECONDS)) { p.destroyForcibly(); return new Result(-1, cmd.get(0) + " gave no answer in " + COMMAND_SECONDS + " s and was stopped"); }
            return new Result(p.exitValue(), Files.readString(out, StandardCharsets.UTF_8));
        } finally { Files.deleteIfExists(out); }
    };
    public static Runner runner = REAL_RUNNER;
    /**
     * A command that takes as long as it takes and shows the person its own progress (fetching an image of several gigabytes). On the
     * real machine it writes to the terminal; where a test stands in for the machine, the test's runner answers for it.
     */
    static Result runShown(List<String> cmd) throws Exception {
        if (runner != REAL_RUNNER) return runner.run(cmd);
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        return new Result(p.waitFor(), "");
    }
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

    /** The largest NVIDIA card's memory in GB, rounded; 0 when nvidia-smi does not name a card with a memory size (a stub that prints a sentence and exits 0 is not a card). */
    static double nvidiaGb() { Gpu g = Gpu.nvidia(runner); return g == null ? 0 : g.gb(); }

    /** The GPU this Linux machine has, as the kernel tells it. */
    static Gpu gpu() { return Gpu.detect(runner); }

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
     * Memory the model may take, in GB: on Linux an NVIDIA card's, or what the BIOS set aside for an AMD GPU (a discrete
     * card's own memory, an APU's carve-out), or for an Intel GPU half the RAM it shares, up to {@link #INTEL_GB}; on macOS about
     * seven tenths of unified memory, which is what the system lets the GPU wire; on Windows the NVIDIA card where there
     * is one, else half the RAM, which is what an integrated card shares.
     */
    /**
     * The most an Intel GPU is given: it shares the system's memory but not a card's speed (a 4B ran at 9 tokens a second on an
     * Arrow Lake chip against 7 on its CPU, 2026-10-20), so the pick stops at the 9B's row rather than the 27B that half the RAM would buy.
     */
    static final double INTEL_GB = 8;

    public static double budgetGb() {
        return switch (os) {
            case linux -> { Gpu g = gpu(); yield g.kind() == Gpu.Kind.intel ? Math.min(INTEL_GB, Math.floor(ramGb() * 0.5)) : g.gb(); }
            case macos -> Math.floor(ramGb() * 0.7);
            case windows -> { double n = nvidiaGb(); yield n > 0 ? n : Math.floor(ramGb() * 0.5); }
        };
    }

    /** What the server runs on here, for the offer: "the Ada" is not known, the backend is. */
    static String backend() {
        return switch (os) {
            case linux -> switch (gpu().build()) { case "rocm" -> "ROCm in Docker"; case "vulkan" -> "Vulkan in Docker"; default -> "CUDA in Docker"; };
            case macos -> "Metal";
            case windows -> "Vulkan";
        };
    }

    /** Why this machine cannot serve a model on demand, or null when it can. */
    public static String unsupported() {
        switch (os) {
            case linux -> {
                String runtimes;
                try {
                    Result d = runner.run(List.of("docker", "info", "--format", "{{.Runtimes}}"));
                    if (d.code() != 0) return "docker is not on this machine (on Linux the model runs in llama.cpp's container)";
                    runtimes = d.out();
                } catch (Exception e) { return "docker is not on this machine (on Linux the model runs in llama.cpp's container)"; }
                Gpu g = gpu();
                switch (g.kind()) {
                    case nvidia -> { if (!runtimes.contains("nvidia")) return "docker here has no NVIDIA runtime (install nvidia-container-toolkit)"; }
                    case amd, intel -> { String m = g.missingDevices(); if (m != null) return m; }
                    case none -> { return "no GPU: nvidia-smi names no NVIDIA card, and the kernel lists no AMD or Intel one under /sys/class/drm"; }
                }
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

    /** " (about 14 GB)", from the row's own size: nothing is asked of anyone. "" for a file the person named. */
    static String sizeNote(Row r) {
        return r.bytes() > 0 ? " (about " + Math.round(r.bytes() / 1e9) + " GB)" : "";
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

    public static Plan plan(Os os, Row row, Path modelFile, Path dir, Path unit, String gpus, int idleMinutes, boolean share) { return plan(os, row, modelFile, dir, unit, gpus, idleMinutes, share, "cuda"); }

    /**
     * {@code build} is the llama.cpp container the Linux run script starts ({@link Gpu#build()}): cuda with the NVIDIA runtime's
     * {@code --gpus}; rocm with the compute interface and the render devices passed through; vulkan with the render devices.
     * {@code gpus} is "all" or one device's index: {@code --gpus} for CUDA, the backend's visible-devices variable for the others
     * (left unset for "all": a visible-devices list that is empty means no device, and the CPU serves at a tenth of the speed).
     */
    public static Plan plan(Os os, Row row, Path modelFile, Path dir, Path unit, String gpus, int idleMinutes, boolean share, String build) {
        return plan(os, row, modelFile, dir, unit, gpus, idleMinutes, share, build, null);
    }

    /**
     * {@code shut}: on Linux, the network with no way out that the container is put on, at its fixed address there, where the proxy
     * reaches it ({@link Shut}); null writes the lines earlier versions wrote, a port of this machine's own published for it.
     */
    public static Plan plan(Os os, Row row, Path modelFile, Path dir, Path unit, String gpus, int idleMinutes, boolean share, String build, Shut shut) {
        String cmd = switch (os) {
            case linux -> dir.resolve("run.sh") + " ${PORT}";
            case macos -> dir.resolve("llama").resolve("llama-server") + " " + serverArgs(row, modelFile, os);
            case windows -> dir.resolve("llama").resolve("llama-server.exe") + " " + serverArgs(row, modelFile, os);
        };
        String cfg = "# CodeZaiku: the model server on demand. llama-swap listens on " + PORT + "; the server starts on the first request\n"
                + "# and stops after ttl seconds idle. `codezaiku model serve status` reads it.\n"
                + "healthCheckTimeout: 600\n"
                + "startPort: " + (PORT == 8211 ? 10001 : PORT + 1) + "\n"
                + "logLevel: info\n"
                + "models:\n"
                + "  \"" + row.name() + "\":\n"
                + "    cmd: " + cmd + "\n"
                + (os == Os.linux ? "    cmdStop: docker stop " + CONTAINER + "\n" : "")
                + "    proxy: http://127.0.0.1:${PORT}\n"
                + "    ttl: " + (idleMinutes * 60) + "\n"
                + "    aliases: [\"local-model\", \"" + row.file() + "\", \"/m/" + row.file() + "\"]\n";
        String image = imageOf(build);
        String devices = switch (build) {
            case "rocm" -> "--device /dev/kfd --device /dev/dri --group-add video" + (gpus.equals("all") ? "" : " -e HIP_VISIBLE_DEVICES=" + gpus);
            case "vulkan" -> "--device /dev/dri --group-add video" + (gpus.equals("all") ? "" : " -e GGML_VK_VISIBLE_DEVICES=" + gpus);
            default -> "--gpus " + (gpus.equals("all") ? "all" : "'\"device=" + gpus + "\"'");
        };
        String run = os != Os.linux ? null : "#!/bin/sh\n"
                + "# " + row.name() + " in llama.cpp, started by llama-swap on demand; $1 is the port it assigned. The flags are the measured ones.\n"
                + "exec docker run --rm --name " + CONTAINER + " " + devices
                + " -p 127.0.0.1:$1:8080 -v " + modelFile.getParent() + ":/m ghcr.io/ggml-org/llama.cpp:" + image + " \\\n"
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
        Plan p = new Plan(row, modelFile, dir, unit, cfg, run, unitText);
        if (os != Os.linux || shut == null) return p;
        // written the earlier way above, then turned: one text decides what "no way out" changes, for a new install and for one that changes over
        Map<String, String> turned = toShut(dir, filesOf(p, ""), shut);
        return new Plan(row, modelFile, dir, unit, turned.get("config.yaml"), turned.get("run.sh"), unitText);
    }

    // ---- no way out ----

    /**
     * The setting that chooses whether the model server `model serve install` sets up on Linux may reach the network: unset or
     * closed, it is set up with no way out; open, the way earlier versions set it up, for a model server that has to reach a
     * network. ResearchZosho has the same setting under the same name; each program reads its own settings file.
     */
    public static final String MODEL_NETWORK = "CODEZAIKU_MODEL_NETWORK";

    static boolean openAsked() {
        String v = Config.get(MODEL_NETWORK);
        return v != null && List.of("open", "on", "yes", "true").contains(v.strip().toLowerCase(Locale.ROOT));
    }

    /** The setting's name as a person types it. */
    static String settingName() { return MODEL_NETWORK.replaceFirst("^CODEZAIKU_", "").toLowerCase(Locale.ROOT); }

    static String imageOf(String build) { return switch (build) { case "rocm" -> "server-rocm"; case "vulkan" -> "server-vulkan"; default -> "server-cuda"; }; }

    /**
     * The model server with no way out, as ResearchZosho 0.8.1 sets up its own. On Linux the model is a container, and a container
     * on a Docker network made with {@code --internal} has no route to anything but the machine it runs on: no gateway, no name
     * service for outside names, and Docker drops what is sent across that network's edge. The machine itself still reaches the
     * container at the container's own address, so the proxy (llama-swap, a process of the person's) passes requests to that
     * address in place of a published port, and the address every program uses, the proxy's, does not change. The container's
     * address has to be known before it starts, so the network has a range of its own and the container a fixed address in it:
     * the range's second, as in ResearchZosho, whose embeddings server takes the third. The container also loses the right to
     * write raw packets ({@code --cap-drop NET_RAW}), which is the one way a program could address a packet past its routes.
     *
     * <p>What this gives: nothing a model file, a chat template or a fault in the model server does can send anything off the
     * machine. What it does not: the container still reaches the machine itself, so a program on the machine that listens on every
     * address and passes things on (a proxy, a name server) would be a way out.
     */
    public record Shut(String subnet, String modelAt) {
        /** The range's second address; the first is the machine's own on that network. Null when the range is not one. */
        static Shut of(String subnet) {
            long[] r = range(subnet);
            return r == null || r[1] - r[0] < 7 ? null : new Shut(subnet, dotted(r[0] + 2));
        }

        /**
         * What the start script says and does before its {@code docker run}: the arrangement in words, and the network made again when
         * it is gone. A {@code docker network prune} removes a network no container is on, which is this one whenever the model is
         * not loaded.
         */
        String said(String at) {
            return "# No way out: it runs on a Docker network that has no route to anything but this machine (" + NETWORK + ", " + subnet + "),\n"
                 + "# may not write raw packets, and answers this machine at " + at + " ($1 is not used). When the network was removed it is made again here.\n"
                 + "docker network inspect " + NETWORK + " >/dev/null 2>&1 || docker network create --internal --subnet " + subnet + " " + NETWORK + " >/dev/null 2>&1\n";
        }

        String flags(String at) { return " --network " + NETWORK + " --ip " + at + " --cap-drop NET_RAW"; }
    }

    /**
     * The ranges tried for the network, in this order: sixteen addresses each, private, and away from the ones Docker hands out by
     * itself (172.17 to 172.31, 192.168), home routers, and the usual virtual machines and tunnels. The first that no route of the
     * machine overlaps and that Docker accepts is taken. None of them is one of ResearchZosho's (172.16.211, 10.213.71, 172.16.93,
     * 10.157.29, 192.168.251 and 172.31.251, each /28): each program's start script makes its network again with the range it
     * recorded, and with lists that share a range one could find it taken by the other's after a prune.
     */
    static final List<String> RANGES = List.of("172.16.221.0/28", "10.213.81.0/28", "172.16.103.0/28", "10.157.39.0/28", "192.168.241.0/28", "172.31.241.0/28");

    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?:/(\\d{1,2}))?(?![\\d.])");

    /** An IPv4 range ("172.16.221.0/28", or one address) as its first and last address; null when it is not one. */
    static long[] range(String cidr) {
        Matcher m = IPV4.matcher(cidr == null ? "" : cidr.strip());
        if (!m.matches()) return null;
        long a = 0;
        for (int i = 1; i <= 4; i++) { int part = Integer.parseInt(m.group(i)); if (part > 255) return null; a = a << 8 | part; }
        int bits = m.group(5) == null ? 32 : Integer.parseInt(m.group(5));
        if (bits > 32) return null;
        long size = 1L << (32 - bits), first = a & ~(size - 1);
        return new long[]{first, first + size - 1};
    }

    static String dotted(long a) { return (a >> 24 & 255) + "." + (a >> 16 & 255) + "." + (a >> 8 & 255) + "." + (a & 255); }

    /**
     * Every range and address the machine's routes name ({@code ip -4 route show table all}: the networks it is on, its own
     * addresses, what a tunnel or a virtual machine routes, another program's Docker networks, every table). The default route
     * names none.
     */
    static List<long[]> routed(String routes) {
        List<long[]> used = new ArrayList<>();
        Matcher m = IPV4.matcher(routes);
        while (m.find()) {
            long[] r = range(m.group());
            if (r != null && !"0".equals(m.group(5))) used.add(r);
        }
        return used;
    }

    static boolean overlaps(long[] r, List<long[]> used) {
        for (long[] u : used) if (r[0] <= u[1] && u[0] <= r[1]) return true;
        return false;
    }

    /** The network as made, or why it was not: one of the two is null. */
    record Made(Shut shut, String why) { }

    /**
     * The network with no way out, made or found. One of this program's own that is already there is used as it is. Else the
     * machine's routes are read, and the first of {@link #RANGES} that none of them overlaps is given to Docker, which refuses a range
     * one of its own networks holds; the next is tried then. Nothing is made when no range is free. Once the network is there the
     * machine has to have a route to it: with rootless Docker, or Docker inside a virtual machine, it has none, a container's address
     * is out of the machine's reach, and the model would be waited for in vain. The network is taken away again then.
     */
    static Made makeNetwork() {
        try {
            Result have = runner.run(List.of("docker", "network", "inspect", NETWORK, "--format", "{{.Internal}} {{range .IPAM.Config}}{{.Subnet}}{{end}}"));
            if (have.code() == 0) {
                Matcher m = Pattern.compile("(?m)^(true|false) (\\S+)\\s*$").matcher(have.out().strip());
                Shut there = m.find() && m.group(1).equals("true") ? Shut.of(m.group(2)) : null;
                return there != null ? routedTo(there) : new Made(null, "a Docker network named " + NETWORK + " is already there and is not one CodeZaiku made");
            }
            Result routes = runner.run(List.of("ip", "-4", "route", "show", "table", "all"));
            if (routes.code() != 0 || routes.out().isBlank()) return new Made(null, "the routes of this machine could not be read, so no free address range could be chosen for its network");
            List<long[]> used = routed(routes.out());
            String refused = "";
            for (String candidate : RANGES) {
                if (overlaps(range(candidate), used)) continue;
                Result made = runner.run(List.of("docker", "network", "create", "--internal", "--subnet", candidate, NETWORK));
                if (made.code() == 0) return routedTo(Shut.of(candidate));
                refused = firstLine(made.out());
            }
            return new Made(null, refused.isEmpty() ? "no address range was free for its network" : "Docker did not make its network (" + refused + ")");
        } catch (Exception e) { return new Made(null, "Docker did not make its network (" + e.getMessage() + ")"); }
    }

    /** The network, when the machine has a route to its range; else it is removed and the reason given. */
    private static Made routedTo(Shut shut) throws Exception {
        Result routes = runner.run(List.of("ip", "-4", "route", "show", "table", "all"));
        if (routes.code() == 0 && Pattern.compile("(?m)^" + Pattern.quote(shut.subnet()) + "\\s").matcher(routes.out()).find()) return new Made(shut, null);
        runner.run(List.of("docker", "network", "rm", NETWORK));
        return new Made(null, "this machine has no route to its own Docker networks (rootless Docker, or Docker in a virtual machine), so it could not reach a container there");
    }

    static String firstLine(String text) {
        String t = text == null ? "" : text.strip();
        int nl = t.indexOf('\n');
        t = nl < 0 ? t : t.substring(0, nl).strip();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    /** The network removed by its name and the container stopped by its own: what a step back, a change to the open way and an uninstall leave behind otherwise. */
    static void removeNetwork() {
        try {
            runner.run(List.of("docker", "stop", CONTAINER));
            for (int i = 0; i < 5; i++) {   // a container that is still going away holds the network for a moment
                Result r = runner.run(List.of("docker", "network", "rm", NETWORK));
                if (r.code() == 0 || !r.out().contains("active endpoints")) break;
                Thread.sleep(1000);
            }
        } catch (Exception ignored) { }
    }

    /** What the proof asks of the model server through the proxy. The real one asks over HTTP; a test gives its own. */
    public interface Asker {
        /** One short completion from {@code model} at {@code base}, waited for up to {@code seconds}: "" when the model wrote something, else "!" and why. */
        String complete(String base, String model, int seconds);
        /** Everything the proxy at {@code base} holds loaded is let go, so that what is asked next has the card to itself. */
        void rest(String base);
    }

    static final Asker REAL_ASKER = new Asker() {
        @Override public String complete(String base, String model, int seconds) {
            try {
                var body = JSON.createObjectNode();
                body.put("model", model); body.put("max_tokens", 64);
                body.putArray("messages").addObject().put("role", "user").put("content", "Reply with the single word: ready");
                String to = base + "/v1/chat/completions";
                String payload = DriveClient.routed(to, body.toString(), model);   // the proxy on this machine: nothing is added; every sender of a completion goes through it
                HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();
                HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(to)).timeout(Duration.ofSeconds(seconds))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) return "!it returned HTTP " + r.statusCode() + ": " + errorSaid(r.body());
                JsonNode j = JSON.readTree(r.body()), message = j.path("choices").path(0).path("message");
                boolean wrote = !message.path("content").asText("").isBlank() || !message.path("reasoning_content").asText("").isBlank() || j.path("usage").path("completion_tokens").asInt(0) > 0;
                return wrote ? "" : "!its reply held no words: " + firstLine(r.body());
            } catch (Exception e) { return "!" + (e instanceof HttpTimeoutException ? "no answer in " + seconds + " seconds" : e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
        }
        @Override public void rest(String base) {
            try { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().send(HttpRequest.newBuilder(URI.create(base + "/unload")).timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.discarding()); }
            catch (Exception ignored) { }
        }
    };
    public static Asker asker = REAL_ASKER;

    /** A server's error in its own words: the message of an OpenAI-style error body, else the body's first line. */
    static String errorSaid(String body) {
        try { String m = JSON.readTree(body).path("error").path("message").asText(""); if (!m.isBlank()) return firstLine(m); } catch (Exception ignored) { }
        return firstLine(body);
    }

    /** How long the first answer is waited for: the proxy itself gives a model ten minutes to load. */
    static final int PROOF_SECONDS = 660;
    /** The outside address of the proof: the site the model's files come from. It is asked by its name once, from an ordinary container, and by the number that gave from inside. */
    static final String OUTSIDE = "https://huggingface.co/";

    /**
     * What the proof found: {@code failed} null when the arrangement holds, else why it does not, as words that follow "because";
     * {@code names}: Docker looked an outside name up for the container; {@code silent}: the model gave no answer through it.
     */
    record Proof(String failed, boolean names, boolean silent) {
        static Proof holds(boolean names) { return new Proof(null, names, false); }
        static Proof fails(String why, boolean silent) { return new Proof(why, false, silent); }
    }

    /** One request from inside the container to the outside address by its number: null when it could not connect, else why the arrangement does not hold. */
    private static String reaches(String container, String number) throws Exception {
        Result in = runner.run(List.of("docker", "exec", container, "curl", "-sS", "-o", "/dev/null", "-m", "8", "-k", "-A", "codezaiku.org", "https://" + number + "/"));
        if (in.code() == 7 || in.code() == 28) return null;   // curl's own word: it could not connect, or nothing came back in time
        if (in.code() < 0 || in.code() >= 125) return "the check could not be run inside the container " + container + " (" + firstLine(in.out()) + ")";
        return "an outside address answered from inside the container " + container;
    }

    /**
     * The arrangement, proven by what it does. First the control: a container on an ordinary network asks the outside address, which
     * has to answer there, or a silence from inside would mean nothing. Then whatever the proxy holds is let go, the model writes a
     * completion through the proxy, and the same outside request, by the number the control reached, is made inside its container
     * and has to fail to connect. Last, whether Docker looks an outside name up for the container.
     */
    static Proof prove(String model, String build) throws Exception {
        if (runner != REAL_RUNNER && asker == REAL_ASKER) throw new IllegalStateException("a test that stands in for the machine has to stand in for the model's answers too (ModelServer.asker)");
        String image = "ghcr.io/ggml-org/llama.cpp:" + imageOf(build);
        Result control = runner.run(List.of("docker", "run", "--rm", "--entrypoint", "curl", image, "-4", "-sS", "-o", "/dev/null", "-m", "15", "-A", "codezaiku.org", "-w", "%{remote_ip}", OUTSIDE));
        Matcher at = IPV4.matcher(control.out());
        String number = null;
        while (at.find()) if (at.group(5) == null) number = at.group();
        if (control.code() != 0 || number == null)
            return Proof.fails("no container on this machine reached an outside address just now, so it could not be tried (" + firstLine(control.out()) + ")", false);
        asker.rest(URL);   // whatever the proxy still holds from before is let go: the model is asked with the card to itself
        String a = asker.complete(URL, model, PROOF_SECONDS);
        if (a.startsWith("!")) return Proof.fails("the model did not answer through it (" + a.substring(1) + ")", true);
        String out = reaches(CONTAINER, number);
        if (out != null) return Proof.fails(out, false);
        Result name = runner.run(List.of("docker", "exec", CONTAINER, "getent", "hosts", "huggingface.co"));
        return Proof.holds(name.code() == 0 && name.out().contains("huggingface.co"));
    }

    /** The files that decide which way an install is set up, by their names in the model folder: the config, with {@code kept} after its model, and the start script. */
    static Map<String, String> filesOf(Plan p, String kept) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("config.yaml", p.configYaml() + kept);
        if (p.runScript() != null) files.put("run.sh", p.runScript());
        return files;
    }

    private static final String PROXY_OPEN = "    proxy: http://127.0.0.1:${PORT}\n";
    private static final String PUBLISHED = " -p 127.0.0.1:$1:8080 ";

    /** Where the block of a model ends in a config: before the next model's name, or the groups, or the end. */
    private static int blockEnd(String cfg, int from) {
        Matcher next = Pattern.compile("(?m)^(?:  \"[^\"\\n]+\":|\\S.*)$").matcher(cfg);
        return next.find(from) ? next.start() : cfg.length();
    }

    /**
     * The files of an install set up the earlier way, turned into the ones with no way out. The start script loses its published port
     * and gains the network, its fixed address there and the right it gives up, with the lines that say so and make the network again
     * when it is gone; the config stops handing the script a port and passes the model's requests to the address. Everything else in
     * them stays as it was written, by whichever version or by the person, and so does whatever another program added to the
     * config (an embeddings server beside the model is that program's). Null when a file is not of the shape this program writes.
     */
    static Map<String, String> toShut(Path dir, Map<String, String> files, Shut shut) {
        String cfg = files.get("config.yaml"), script = files.get("run.sh"), cmd = "    cmd: " + dir.resolve("run.sh");
        if (cfg == null || script == null) return null;
        int at = cfg.indexOf(cmd + " ${PORT}\n");
        if (at < 0) return null;
        Matcher exec = Pattern.compile("(?m)^exec docker run ").matcher(script);
        int end = blockEnd(cfg, at + cmd.length());
        String block = cfg.substring(at, end);
        if (script.split(Pattern.quote(PUBLISHED), -1).length != 2 || !exec.find() || block.split(Pattern.quote(PROXY_OPEN), -1).length != 2) return null;
        Map<String, String> out = new LinkedHashMap<>();
        out.put("config.yaml", cfg.substring(0, at) + block.replace(cmd + " ${PORT}\n", cmd + "\n").replace(PROXY_OPEN, "    proxy: http://" + shut.modelAt() + ":8080\n") + cfg.substring(end));
        out.put("run.sh", script.substring(0, exec.start()) + shut.said(shut.modelAt() + ":8080") + script.substring(exec.start()).replace(PUBLISHED, shut.flags(shut.modelAt()) + " "));
        return out;
    }

    /** The other way: the files of an install with no way out, turned into the earlier way's. Null when a file is not of the shape {@link #toShut} leaves. */
    static Map<String, String> toOpen(Path dir, Map<String, String> files) {
        String cfg = files.get("config.yaml"), script = files.get("run.sh"), cmd = "    cmd: " + dir.resolve("run.sh");
        if (cfg == null || script == null) return null;
        int at = cfg.indexOf(cmd + "\n");
        if (at < 0) return null;
        Matcher flags = Pattern.compile(" --network " + Pattern.quote(NETWORK) + " --ip (\\S+) --cap-drop NET_RAW ").matcher(script);
        if (!flags.find()) return null;
        String proxy = "    proxy: http://" + flags.group(1) + ":8080\n";
        int end = blockEnd(cfg, at + cmd.length());
        String block = cfg.substring(at, end);
        if (!block.contains(proxy)) return null;
        Map<String, String> out = new LinkedHashMap<>();
        out.put("config.yaml", cfg.substring(0, at) + block.replace(cmd + "\n", cmd + " ${PORT}\n").replace(proxy, PROXY_OPEN) + cfg.substring(end));
        out.put("run.sh", script.replaceFirst("(?m)^# No way out: .*\\n# may not write raw packets.*\\n", "").replaceFirst("(?m)^docker network inspect \\S+ >/dev/null 2>&1 \\|\\| docker network create .*\\n", "")
                .replace(flags.group(), PUBLISHED));
        return out;
    }

    static void writeFiles(Path dir, Map<String, String> files) throws IOException {
        for (var f : files.entrySet()) {
            Files.writeString(dir.resolve(f.getKey()), f.getValue(), StandardCharsets.UTF_8);
            if (f.getKey().endsWith(".sh")) dir.resolve(f.getKey()).toFile().setExecutable(true);
        }
    }

    /** How long one of the seconds {@link #answersWithin} waits lasts, in milliseconds: a second, except in a test of a proxy that never answers. */
    static volatile long SECOND_MS = 1000;

    static boolean answersWithin(int seconds) throws InterruptedException {
        for (int i = 0; i < seconds && !health.test(URL); i++) Thread.sleep(SECOND_MS);
        return health.test(URL);
    }

    static final String STEPPED_BACK = "The model server could not be set up with no way out to the network on this machine, because %s. It is set up the way earlier versions did, so it can reach the network. To try again: codezaiku model serve install";

    /**
     * It proves itself or steps back. The install is set up with no way out and its proxy answers: the proof is run, and when it holds
     * the person is told. When it does not, the files of the earlier way ({@code earlier}: what this version writes for it, or what
     * the install had before `model serve install` was given again) are put back, the proxy is started again to read them, the
     * container and the network of the attempt are taken away, and the model is asked again that way, so that the person reads
     * whether it answers.
     */
    static Proof proveOrStepBack(String model, String build, Path dir, Map<String, String> earlier, PrintStream out) throws Exception {
        out.println("  Asking the model for a first answer, to see that it works with no way out to the network. The model is loaded for this, which can take a few minutes with a large one.");
        Proof proof = prove(model, build);
        if (proof.failed() == null) {
            asker.rest(URL);   // the card is free again, as an install has always left it; the first request loads the model
            out.println("  The model server has no way out to the network: its container is on a Docker network with no route to anything but this machine. Tried just now: the model answered through it, and an outside address that another container reached could not be reached from inside.");
            if (proof.names()) out.println("  " + NAMES_SAID);
            return proof;
        }
        writeFiles(dir, earlier);
        restartProxy();
        removeNetwork();
        out.println("  " + String.format(STEPPED_BACK, proof.failed()));
        // asked again the earlier way, so the person reads whether it answers: when it does not either, the arrangement was not the cause
        boolean up = answersWithin(30);
        String again = up ? asker.complete(URL, model, PROOF_SECONDS) : "!the proxy did not answer at " + URL;
        out.println("  " + (again.startsWith("!") ? "Set up the earlier way, the model did not answer either (" + again.substring(1) + "), so the cause lies elsewhere. Its log: " + logHint()
                                                  : "Set up the earlier way, the model answered."));
        if (up) asker.rest(URL);
        return proof;
    }

    static final String NAMES_SAID = "Docker on this machine still looks names up for the model server, which a program could use to send a little information out. Docker 29 does not.";

    /** The proxy itself did not come back with the new files: the earlier ones are put back and it is started again. */
    static Proof stepBackUnanswered(Path dir, Map<String, String> earlier, PrintStream out) throws Exception {
        String why = "the proxy did not answer at " + URL;
        writeFiles(dir, earlier);
        restartProxy();
        removeNetwork();
        out.println("  " + String.format(STEPPED_BACK, why));
        return Proof.fails(why, false);
    }

    /** The proxy started again, so it reads its config. Only a Linux install changes its way out. */
    static void restartProxy() throws Exception {
        if (os == Os.linux) runner.run(List.of("systemctl", "--user", "restart", proxyUnit()));
    }

    /**
     * The lines of the plan that say an install is set up with no way out: its network's range, and, once the proof held, the day it
     * did ({@code proven}) and whether Docker looked a name up for the container. Without the day the files are written and nothing
     * is claimed yet. The keys are ResearchZosho's: where the two programs share this folder, it reads them.
     */
    static String shutProperties(Shut shut, boolean proven, boolean names) {
        return "network=shut\nsubnet=" + shut.subnet() + "\n" + (proven ? "network_tried=" + LocalDate.now() + "\n" : "") + (proven && names ? "network_names=looked-up\n" : "");
    }

    /** The lines of the plan that say an install can reach the network, and why: "asked" (the setting), or "unproven" with the reason the attempt gave. */
    static String openProperties(String why, String reason) {
        return "network=open\nnetwork_why=" + why + "\n" + (reason == null || reason.isBlank() ? "" : "network_reason=" + reason.replace("\n", " ") + "\n") + "network_tried=" + LocalDate.now() + "\n";
    }

    /** The network an install's plan says it is on, when it is set up with no way out; null otherwise. */
    static Shut shutOf(Map<String, String> m) { return "shut".equals(m.get("network")) ? Shut.of(m.get("subnet")) : null; }

    /** The plan's lines with the ones about the way out replaced. */
    static void rewriteNet(Path dir, String netLines) throws IOException {
        Path props = dir.resolve("plan.properties");
        String kept = Files.exists(props) ? Files.readString(props, StandardCharsets.UTF_8).replaceAll("(?m)^(network|subnet|network_why|network_reason|network_tried|network_names)=.*\\n?", "") : "";
        Files.writeString(props, kept + (kept.isEmpty() || kept.endsWith("\n") ? "" : "\n") + netLines, StandardCharsets.UTF_8);
    }

    /** llama.cpp's image, fetched before the proof when it is not on the machine: a model that waits for an image of several gigabytes would look like one that does not answer. "" when it is there; else what Docker said. */
    static String fetchImages(String build, PrintStream out) throws Exception {
        String image = "ghcr.io/ggml-org/llama.cpp:" + imageOf(build);
        if (runner.run(List.of("docker", "image", "inspect", "--format", "{{.Id}}", image)).code() == 0) return "";
        out.println("  Fetching " + image + ", the program in a container that Docker runs here. It is several gigabytes.");
        Result r = runShown(List.of("docker", "pull", image));
        return r.code() == 0 ? "" : image + (r.out().isBlank() ? "" : ": " + firstLine(r.out()));
    }

    /** The build of llama.cpp an install's start script runs, read from the script. */
    static String buildOf(Path dir) throws IOException {
        String run = Files.readString(dir.resolve("run.sh"), StandardCharsets.UTF_8);
        return run.contains(":server-rocm") ? "rocm" : run.contains(":server-vulkan") ? "vulkan" : "cuda";
    }

    /** The name of the first model a proxy config serves, or null. */
    static String modelOf(String yaml) {
        Matcher m = Pattern.compile("(?m)^  \"([^\"]+)\":\\s*\\n\\s*cmd:").matcher(yaml == null ? "" : yaml);
        return m.find() ? m.group(1) : null;
    }

    /** Whether the model server in this folder is this program's: its start script starts this program's container, and this program's service runs it. */
    static boolean ours(Path dir) {
        return CONTAINER.equals(containerOf(dir.resolve("run.sh"), null)) && Files.isRegularFile(unitPath(Os.linux));
    }

    /**
     * `model serve install` given again where this program's own model server already answers (Linux): the install is moved to the way
     * that is wanted now. From the earlier way to no way out, proven, or stepped back to exactly the files it had; from no way out to
     * the earlier way when the setting asks for it. Only the lines that decide the way out change ({@link #toShut}, {@link #toOpen}):
     * the model, its flags, anything the person wrote into the files and anything another program added stay. "" when there is
     * nothing to change: not Linux, not an install of this program's (another program's start script or service is left as it is),
     * or already set up as wanted. Else the model's name.
     */
    static String changeOver(PrintStream out) throws Exception {
        if (os != Os.linux) return "";
        Path dir = dir(), props = dir.resolve("plan.properties");
        if (!Files.isRegularFile(dir.resolve("run.sh")) || !Files.isRegularFile(dir.resolve("config.yaml")) || !ours(dir)) return "";
        Map<String, String> earlier = new LinkedHashMap<>();
        for (String f : List.of("config.yaml", "run.sh")) earlier.put(f, Files.readString(dir.resolve(f), StandardCharsets.UTF_8));
        String name = modelOf(earlier.get("config.yaml"));
        if (name == null) return "";
        Map<String, String> m = readPlan(dir);
        boolean isShut = shutOf(m) != null, wantOpen = openAsked(), proven = m.containsKey("network_tried");
        if (!isShut && wantOpen || isShut && !wantOpen && proven) return "";   // already set up the way that is wanted
        // an install from before the plan file: its plan, read back from its files, is written first, whole, as ResearchZosho reads it where the two share this folder
        if (!Files.exists(props)) Files.writeString(props, planText(m), StandardCharsets.UTF_8);
        String build = buildOf(dir);
        if (isShut && !wantOpen) {   // set up with no way out by an install that did not get to the end of its proof: proven now, or stepped back
            Map<String, String> back = toOpen(dir, earlier);
            if (back == null || !answersWithin(5)) return "";
            Proof proof = proveOrStepBack(name, build, dir, back, out);
            rewriteNet(dir, proof.failed() == null ? shutProperties(shutOf(m), true, proof.names()) : openProperties("unproven", proof.failed()));
            return name;
        }
        if (wantOpen) {   // from no way out to the earlier way, on the person's word
            Map<String, String> open = toOpen(dir, earlier);
            if (open == null) { out.println("  The setting " + settingName() + " is open, but the model server's files in " + dir + " are not as CodeZaiku wrote them, so they are left as they are. codezaiku model serve uninstall, then codezaiku model serve install, sets it up afresh."); return name; }
            writeFiles(dir, open);
            rewriteNet(dir, openProperties("asked", ""));
            restartProxy();
            removeNetwork();
            out.println("  The setting " + settingName() + " is open, so the model server is set up able to reach the network, as earlier versions did.");
            return name;
        }
        String images = fetchImages(build, out);
        Made made = images.isEmpty() ? makeNetwork() : new Made(null, "a program it runs could not be fetched (" + images + ")");
        Map<String, String> turned = null;
        if (made.shut() != null) {
            turned = toShut(dir, earlier, made.shut());
            if (turned == null) { removeNetwork(); made = new Made(null, "its files in " + dir + " are not as CodeZaiku wrote them"); }
        }
        if (made.shut() == null) {
            rewriteNet(dir, openProperties("unproven", made.why()));
            out.println("  The model server could not be set up with no way out to the network on this machine, because " + made.why() + ". It stays as it was, able to reach the network. To try again: codezaiku model serve install");
            return name;
        }
        out.println("  Setting the model server of this machine up again with no way out to the network. A model that is loaded now is unloaded for it.");
        writeFiles(dir, turned);
        rewriteNet(dir, shutProperties(made.shut(), false, false));
        restartProxy();
        Proof proof = answersWithin(30) ? proveOrStepBack(name, build, dir, earlier, out) : stepBackUnanswered(dir, earlier, out);
        rewriteNet(dir, proof.failed() == null ? shutProperties(made.shut(), true, proof.names()) : openProperties("unproven", proof.failed()));
        return name;
    }

    /**
     * What `model serve status` says of the way out, in a sentence or two: none, and since when; or that the model server can reach
     * the network, and why: the setting, an install by an earlier version, an attempt that did not hold; with the command that changes
     * it. Another program's install is described from its start script and left as it is. Where the server is a plain program
     * (macOS, Windows) it says that.
     */
    static String wayOutSaid() {
        if (os != Os.linux) return "On this system the model server is an ordinary program, not in a container, so nothing keeps it from the network.";
        try {
            Path dir = dir();
            String again = "codezaiku model serve install sets it up with no way out.";
            String run = Files.isRegularFile(dir.resolve("run.sh")) ? Files.readString(dir.resolve("run.sh"), StandardCharsets.UTF_8) : "";
            if (!ours(dir)) {
                Matcher net = Pattern.compile(" --network (\\S+) --ip \\S+ ").matcher(run);
                if (net.find() && !run.contains(" -p ")) return "Another program set the model server up, and CodeZaiku leaves it as it is. By its start script the model is on a Docker network of its own (" + net.group(1) + "), with no port of this machine published.";
                return "The model server can reach the network: another program set it up, and CodeZaiku leaves it as it is.";
            }
            Map<String, String> m = Files.exists(dir.resolve("plan.properties")) ? readPlan(dir) : Map.of();
            Shut shut = shutOf(m);
            if (shut != null) {
                Result net = runner.run(List.of("docker", "network", "inspect", NETWORK, "--format", "{{.Internal}}"));
                if (!run.contains("--network " + NETWORK + " ") || (net.code() == 0 && !net.out().strip().equals("true")))
                    return "The model server can reach the network: it was set up with no way out, and its start script or its Docker network (" + NETWORK + ") is no longer as CodeZaiku made it. " + again;
                if (!m.containsKey("network_tried")) return "The model server is set up with no way out to the network, but setting it up stopped before that was tried. codezaiku model serve install tries it.";
                String said = "The model server has no way out to the network: its container is on a Docker network with no route to anything but this machine (" + NETWORK + "). Tried on " + m.get("network_tried") + ": the model answered through it, and an outside address could not be reached from inside.";
                if ("looked-up".equals(m.get("network_names"))) said += " " + NAMES_SAID;
                if (openAsked()) said += " The setting " + settingName() + " is open now; codezaiku model serve install applies it.";
                return said + beside(dir);
            }
            if (openAsked()) return "The model server can reach the network, because the setting " + settingName() + " is open. With it closed or unset, codezaiku model serve install sets it up with no way out.";
            String why = m.getOrDefault("network_why", "");
            if (why.equals("unproven")) return "The model server can reach the network: on " + m.getOrDefault("network_tried", "?") + " it could not be set up with no way out, because " + m.getOrDefault("network_reason", "?") + ". codezaiku model serve install tries again.";
            if (why.equals("asked")) return "The model server can reach the network: the setting " + settingName() + " was open when it was set up. " + again;
            return "The model server can reach the network: an earlier version of CodeZaiku set it up. " + again;
        } catch (Exception e) { return "Whether the model server can reach the network could not be read (" + e.getMessage() + ")."; }
    }

    /**
     * What else the proxy starts, beside this program's model with no way out, that can reach the network: an entry another program
     * added (ResearchZosho's embeddings server, where the two share this folder), whose start script publishes a port. "" when none.
     */
    static String beside(Path dir) throws IOException {
        String cfg = Files.readString(dir.resolve("config.yaml"), StandardCharsets.UTF_8);
        Matcher e = Pattern.compile("(?m)^  \"([^\"]+)\":\\s*\\n\\s*cmd:\\s*(\\S+\\.sh)\\b").matcher(cfg);
        List<String> open = new ArrayList<>();
        while (e.find()) {
            Path script = Path.of(e.group(2));
            if (script.equals(dir.resolve("run.sh")) || !Files.isRegularFile(script)) continue;
            if (Files.readString(script, StandardCharsets.UTF_8).contains(" -p ")) open.add(e.group(1));
        }
        return open.isEmpty() ? "" : " The proxy also starts " + String.join(", ", open) + ", which another program added; it can reach the network, and CodeZaiku leaves it as it is.";
    }

    /** What `model serve install` was told, as ResearchZosho writes it: where the two programs share this folder, it reads this file. */
    static String planProperties(Row row, Path modelFile, String gpus, int idleMinutes, boolean share) {
        return "# CodeZaiku: what `model serve install` was told, and which way the model server is set up\n"
                + "name=" + row.name() + "\nfile=" + modelFile.toAbsolutePath() + "\nctx=" + row.ctx() + "\nparallel=" + row.parallel() + "\nextra=" + row.extra().replace("\n", " ")
                + "\ngpus=" + gpus + "\nidle_minutes=" + idleMinutes + "\nshare=" + share + "\n";
    }

    /** A plan read back from an install's files, written out with the same keys as {@link #planProperties}. */
    static String planText(Map<String, String> m) {
        StringBuilder b = new StringBuilder("# CodeZaiku: what `model serve install` was told, and which way the model server is set up\n");
        for (String k : List.of("name", "file", "ctx", "parallel", "extra", "gpus", "idle_minutes", "share")) if (m.containsKey(k)) b.append(k).append('=').append(m.get(k)).append('\n');
        return b.toString();
    }

    /** An install's plan: its plan file, or, for an install from before it had one, read back from the files it wrote (as ResearchZosho reads it). */
    static Map<String, String> readPlan(Path dir) throws IOException {
        Map<String, String> m = new HashMap<>();
        Path props = dir.resolve("plan.properties");
        if (Files.exists(props)) {
            for (String line : Files.readAllLines(props, StandardCharsets.UTF_8)) { int eq = line.indexOf('='); if (eq > 0 && !line.startsWith("#")) m.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip()); }
            return m;
        }
        String y = Files.readString(dir.resolve("config.yaml"), StandardCharsets.UTF_8);
        String name = modelOf(y);
        if (name == null) return m;
        m.put("name", name);
        Matcher t = Pattern.compile("ttl:\\s*(\\d+)").matcher(y);
        m.put("idle_minutes", t.find() ? String.valueOf(Integer.parseInt(t.group(1)) / 60) : String.valueOf(DEFAULT_IDLE_MINUTES));
        String server = y;   // where -m, -c, --parallel and --gpus live: run.sh on Linux, the cmd line elsewhere
        Path run = dir.resolve("run.sh");
        if (Files.exists(run)) server = Files.readString(run, StandardCharsets.UTF_8);
        Matcher f = Pattern.compile("-m\\s+(\\S+)").matcher(server);
        if (f.find()) {
            String file = f.group(1);
            Matcher mount = Pattern.compile("-v\\s+(\\S+):/m\\b").matcher(server);
            m.put("file", !file.startsWith("/m/") ? file : (mount.find() ? Path.of(mount.group(1)) : modelsDir()).resolve(file.substring(3)).toString());
        }
        Matcher c = Pattern.compile("-c\\s+(\\d+)").matcher(server); if (c.find()) m.put("ctx", c.group(1));
        Matcher pl = Pattern.compile("--parallel\\s+(\\d+)").matcher(server); if (pl.find()) m.put("parallel", pl.group(1));
        Matcher g = Pattern.compile("--gpus\\s+(?:'\"device=([^\"]+)\"'|(all))|VISIBLE_DEVICES=(\\S+)").matcher(server);   // CUDA's --gpus, or the device variable the ROCm and Vulkan scripts set
        m.put("gpus", g.find() ? (g.group(1) != null ? g.group(1) : g.group(3) != null ? g.group(3) : "all") : "all");
        Matcher x = Pattern.compile("--chat-template-kwargs\\s+(\\S+)").matcher(server); m.put("extra", x.find() ? "--chat-template-kwargs " + x.group(1) : "");
        boolean share = false;
        try { share = Files.readString(unitPath(os), StandardCharsets.UTF_8).contains("0.0.0.0:" + PORT); } catch (IOException ignored) { }
        m.put("share", String.valueOf(share));
        return m;
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
                // this program's own install on Linux, set up the other way than the one wanted now: it changes over, proven or stepped back
                String over = changeOver(out);
                if (over.isEmpty()) out.println("  a model proxy already answers at " + URL + "; using it");
                Config.set("CODEZAIKU_DRIVE", URL);
                return over.isEmpty() ? "local-model" : over;
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
                row = new Row(stem(file.getFileName().toString()), 0, "", "", file.getFileName().toString(), "", 0, row.ctx(), row.parallel(), "");
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
            String build = os == Os.linux ? gpu().build() : "";
            // the way out: on Linux the container gets none, unless the setting asks for the earlier way or something stands in the way of the proof
            Shut shut = null;
            String openWhy = null, reason = null;
            if (os == Os.linux) {
                if (openAsked()) openWhy = "asked";
                else {
                    String images = fetchImages(build, out);
                    Made made = images.isEmpty() ? makeNetwork() : new Made(null, "a program it runs could not be fetched (" + images + ")");
                    shut = made.shut();
                    if (shut == null) { openWhy = "unproven"; reason = made.why(); }
                }
            }
            Plan earlier = plan(os, row, modelFile, dir, unitPath(os), gpus, idleMinutes, share, build, null);
            Plan p = shut == null ? earlier : plan(os, row, modelFile, dir, unitPath(os), gpus, idleMinutes, share, build, shut);
            if (p.runScript() != null) { Files.writeString(dir.resolve("run.sh"), p.runScript(), StandardCharsets.UTF_8); dir.resolve("run.sh").toFile().setExecutable(true); }
            // a config already here may carry what another program added to this proxy (an embeddings server beside the model): kept, with
            // every stop command naming the container its own start script starts
            Path cfg = dir.resolve("config.yaml");
            String kept = Files.isRegularFile(cfg) ? keptEntries(Files.readString(cfg, StandardCharsets.UTF_8)) : "";
            Map<String, String> earlierFiles = new LinkedHashMap<>();   // what a step back writes: the earlier way's files, to the letter
            for (var f : filesOf(earlier, kept).entrySet()) earlierFiles.put(f.getKey(), f.getKey().equals("config.yaml") ? stopsWhatStarts(f.getValue()) : f.getValue());
            Files.writeString(cfg, stopsWhatStarts(p.configYaml() + kept), StandardCharsets.UTF_8);
            if (os == Os.linux)
                Files.writeString(dir.resolve("plan.properties"), planProperties(row, modelFile, gpus, idleMinutes, share) + (shut != null ? shutProperties(shut, false, false) : openProperties(openWhy, reason)), StandardCharsets.UTF_8);
            Files.createDirectories(p.unit().getParent());
            Files.writeString(p.unit(), p.unitText(), StandardCharsets.UTF_8);
            String s = startService(os, p);
            if (!s.isEmpty()) return s;
            if (!answersWithin(30)) {
                if (shut == null) return "!the proxy did not answer at " + URL + " within 30 s: " + logHint();
                Proof unanswered = stepBackUnanswered(dir, earlierFiles, out);   // the earlier way's files; when the proxy stays silent with them too, the fault is not the arrangement's
                rewriteNet(dir, openProperties("unproven", unanswered.failed()));
                if (!answersWithin(30)) return "!the proxy did not answer at " + URL + " within 30 s: " + logHint();
                shut = null;
            }
            if (os == Os.linux && openWhy != null)
                out.println("  " + (openWhy.equals("asked") ? "The setting " + settingName() + " is open, so the model server is set up able to reach the network, as earlier versions did."
                        : String.format(STEPPED_BACK, reason)));
            if (shut != null) {
                Proof proof = proveOrStepBack(row.name(), build, dir, earlierFiles, out);
                rewriteNet(dir, proof.failed() == null ? shutProperties(shut, true, proof.names()) : openProperties("unproven", proof.failed()));
            }
            Config.set("CODEZAIKU_DRIVE", URL);
            Config.set("CODEZAIKU_MODEL", row.name());
            out.println("  the model comes up at " + URL + " when something asks (" + backend() + "), and goes away after " + idleMinutes + " idle minutes; the drive is set to it");
            return row.name();
        } catch (Exception e) {
            return "!" + e.getMessage();
        }
    }

    static String logHint() {
        return switch (os) { case linux -> "journalctl --user -u " + proxyUnit(); case macos, windows -> dir().resolve("llama-swap.log").toString(); };
    }

    /**
     * `model serve check`, the release gate: is every row's file at its revision the one the row pins (Hugging Face's own sha256 and
     * size for it, its x-linked-etag and x-linked-size), and do the pinned builds still resolve? A row whose file at main has moved on
     * is said in a line and does not fail the gate: that is how a newer quantization is learned of, and it is measured before a row is
     * pinned to it.
     */
    public static int check(PrintStream out) {
        int bad = 0, changed = 0, limited = 0;
        for (Row r : ROWS) {
            String at = headWithRetries(r.url());
            String status = lastLine(at);
            if (status.equals("429")) { limited++; out.println("  WAIT 429  " + r.url() + "  (the host is rate-limiting this machine; not checked, not proof of absence)"); continue; }
            if (!status.equals("200")) { bad++; out.println("  GONE " + status + "  " + r.url()); continue; }
            String sha = linked(at, "etag"), size = linked(at, "size");
            if (r.sha256().equalsIgnoreCase(sha) && String.valueOf(r.bytes()).equals(size)) out.println("  ok   200  " + r.url());
            else {
                changed++;
                out.println("  DIFF 200  " + r.url() + "  (Hugging Face gives " + (sha.isEmpty() ? "no sha256" : "sha256 " + sha) + ", "
                        + (size.isEmpty() ? "no size" : size + " bytes") + "; the row pins sha256 " + r.sha256() + ", " + r.bytes() + " bytes)");
            }
            String main = headWithRetries(r.mainUrl());
            String now = linked(main, "etag");
            if (lastLine(main).equals("200") && !now.isEmpty() && !now.equalsIgnoreCase(r.sha256()))
                out.println("  note " + r.file() + " on main is now sha256 " + now + " (" + linked(main, "size") + " bytes); the row stays at "
                        + r.revision().substring(0, Math.min(12, r.revision().length())) + ": measure the new file before a row is pinned to it");
        }
        List<String> urls = new ArrayList<>();
        for (String a : new String[]{"linux_amd64.tar.gz", "linux_arm64.tar.gz", "darwin_arm64.tar.gz", "darwin_amd64.tar.gz", "windows_amd64.zip"}) urls.add("https://github.com/mostlygeek/llama-swap/releases/download/v" + SWAP_VERSION + "/llama-swap_" + SWAP_VERSION + "_" + a);
        for (String a : new String[]{"macos-arm64.tar.gz", "macos-x64.tar.gz", "win-vulkan-x64.zip"}) urls.add("https://github.com/ggml-org/llama.cpp/releases/download/" + LLAMA_BUILD + "/llama-" + LLAMA_BUILD + "-bin-" + a);
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
        if (bad == 0 && changed == 0 && limited == 0) out.println("  every row's file is the one pinned, and every pinned build resolves");
        if (bad > 0) out.println("  " + bad + " missing: re-point the row (and re-record its revision, sha256 and size) before a release");
        if (changed > 0) out.println("  " + changed + " changed: the file at the row's revision is not the one the row pins; find out why before a release");
        if (limited > 0) out.println("  " + limited + " not checked: the host answered 429 (rate-limited) three times; nothing is known to be missing, run the check again later");
        return bad == 0 && changed == 0 ? 0 : 1;
    }

    /** The headers of a HEAD of {@code url} and of each redirect after it, then the last status on a line of its own; asked again on a 429. */
    static String headWithRetries(String url) {
        String got = "";
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) { try { Thread.sleep(checkBackoffMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; } }
            try { got = runner.run(List.of(os == Os.windows ? "curl.exe" : "curl", "-sIL", "-w", "\n%{http_code}", url)).out(); }
            catch (Exception e) { got = "no curl"; }
            if (!lastLine(got).equals("429")) break;
        }
        return got;
    }

    static String lastLine(String s) {
        String[] lines = s.strip().split("\\R");
        return lines[lines.length - 1].strip();
    }

    /** Hugging Face's x-linked-etag (the file's sha256) or x-linked-size, from the headers; "" when not given. */
    static String linked(String headers, String which) {
        Matcher m = Pattern.compile("(?im)^x-linked-" + which + ":\\s*\"?([0-9a-f]+)\"?\\s*$").matcher(headers);
        return m.find() ? m.group(1) : "";
    }

    /** The pause between attempts when a host answers 429; a test sets it to 0. */
    static long checkBackoffMs = 20_000;

    /**
     * The model server ResearchZosho uses, read from its own settings file (read only; never written), as an address with no {@code /v1};
     * where a ResearchZosho from before 0.8.3 still keeps its files in this program's folder, from the line it reads in the shared file
     * ({@code librarian.drive}, else {@code drive}). Null when neither says.
     */
    static String siblingDrive() {
        Path own = Config.userConfigPath().toAbsolutePath();
        Path sib = Path.of(home(), ".researchzosho", "config").toAbsolutePath();
        String d = null;
        if (!sib.equals(own) && Files.isRegularFile(sib)) {
            try {
                for (String line : Files.readAllLines(sib, StandardCharsets.UTF_8)) {
                    String t = line.strip();
                    if ((t.startsWith("drive ") || t.startsWith("drive=")) && t.contains("=")) d = t.substring(t.indexOf('=') + 1).strip();
                }
            } catch (IOException ignored) { }
        } else if (ResearchZoshoInstall.livesInOurFolder()) {
            d = Config.get("CODEZAIKU_LIBRARIAN_DRIVE");
            if (d == null || d.isBlank()) d = Config.get("CODEZAIKU_DRIVE");
        }
        return d == null || d.isBlank() ? null : d.strip().replaceAll("/+$", "").replaceAll("/v1$", "");
    }

    static String stem(String name) { int i = name.lastIndexOf('.'); return (i > 0 ? name.substring(0, i) : name).toLowerCase(Locale.ROOT); }

    /** The container a start script of this folder starts ({@code docker run … --name <it>}); {@code otherwise} when the script is not there or names none. */
    static String containerOf(Path script, String otherwise) {
        try {
            if (Files.isRegularFile(script)) {
                Matcher m = Pattern.compile("--name\\s+([A-Za-z0-9][A-Za-z0-9_.-]*)").matcher(Files.readString(script, StandardCharsets.UTF_8));
                if (m.find()) return m.group(1);
            }
        } catch (IOException ignored) { }
        return otherwise;
    }

    /**
     * The config with every stop command naming the container that its own start script starts. The two can disagree where this folder
     * is one that two programs' installers write (a home they share): one wrote the start script under its container's name, the other
     * rewrote the config with its own. The proxy then stopped a container that was never there, so a model that went idle, or was
     * unloaded, stayed up and kept its memory.
     */
    static String stopsWhatStarts(String yaml) {
        Matcher m = Pattern.compile("(?m)^(\\s*cmd:\\s*(\\S+\\.sh)\\b[^\\n]*\\n\\s*cmdStop:\\s*docker stop\\s+)(\\S+)").matcher(yaml);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + containerOf(Path.of(m.group(2)), m.group(3))));
        m.appendTail(out);
        return out.toString();
    }

    /**
     * What a config on disk holds after its first model: the entries another program added to this folder's proxy (an embeddings server
     * beside the model, and the group that keeps it from being swapped out). An install writes the first model anew and keeps these.
     */
    static String keptEntries(String yaml) {
        Matcher first = Pattern.compile("(?m)^  \"[^\"\\n]+\":[ \\t]*$").matcher(yaml);
        if (!first.find()) return "";
        Matcher next = Pattern.compile("(?m)^(  \"[^\"\\n]+\":[ \\t]*|\\S[^\\n]*)$").matcher(yaml);
        return next.find(first.end()) ? yaml.substring(next.start()) : "";
    }

    /**
     * The systemd unit that runs this folder's proxy: this program's own, or the one another program's installer made for the same folder.
     * A restart sent to a unit that is not there does nothing and says nothing, and the proxy rereads its config only on a restart.
     */
    static String proxyUnit() {
        Path own = unitPath(Os.linux);
        if (Files.isRegularFile(own) || own.getParent() == null || !Files.isDirectory(own.getParent())) return UNIT;
        String swap = dir().resolve("llama-swap").toString();
        try (Stream<Path> units = Files.list(own.getParent())) {
            for (Path u : units.filter(x -> x.getFileName().toString().endsWith(".service")).sorted().toList())
                if (Files.readString(u, StandardCharsets.UTF_8).contains(swap)) return u.getFileName().toString().replaceFirst("\\.service$", "");
        } catch (IOException | RuntimeException ignored) { }
        return UNIT;
    }

    /**
     * Puts right an install saved with a stop command that names a container its start script does not start (see {@link #stopsWhatStarts}):
     * the command is corrected, the proxy is restarted to read it, and a model the old command left running is stopped, so the next
     * request starts it afresh under its name. What it did, in a sentence; "" when there was nothing to put right; "!" and why when it could not.
     */
    public static String repairStop() {
        if (os != Os.linux) return "";
        try {
            Path cfg = dir().resolve("config.yaml");
            if (!Files.isRegularFile(cfg)) return "";
            String y = Files.readString(cfg, StandardCharsets.UTF_8), right = stopsWhatStarts(y);
            if (right.equals(y)) return "";
            Files.writeString(cfg, right, StandardCharsets.UTF_8);
            runner.run(List.of("systemctl", "--user", "restart", proxyUnit()));
            String left = containerOf(dir().resolve("run.sh"), null);
            if (left != null) runner.run(List.of("docker", "stop", left));
            return "the model server's stop command named a container that its start script does not start, so a model that went idle was never stopped and kept its memory. The command is corrected and the proxy restarted; the model starts again on the next request";
        } catch (Exception e) { return "!" + e.getMessage(); }
    }

    /** Whether the service is registered, per platform. */
    static String serviceState() {
        try {
            return switch (os) {
                case linux -> runner.run(List.of("systemctl", "--user", "is-active", proxyUnit())).out().strip();
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
        if (!Files.exists(cfg)) {
            b.append("  no model server of this machine's own (`codezaiku model serve install` sets one up)\n");
            String rz = ResearchZoshoInstall.livesInOurFolderSaid();
            if (!rz.isEmpty()) b.append("  ").append(rz).append('\n');
            return b.toString();
        }
        String name = "?";
        try {
            String y = Files.readString(cfg, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"([^\"]+)\":\\s*\\n\\s*cmd:").matcher(y);
            name = m.find() ? m.group(1) : "?";
            Matcher t = Pattern.compile("ttl:\\s*(\\d+)").matcher(y);
            b.append("  model: ").append(name).append(" on ").append(backend()).append(t.find() ? ", stops after " + (Integer.parseInt(t.group(1)) / 60) + " idle minutes" : "").append('\n');
        } catch (IOException e) { b.append("  config unreadable: ").append(e.getMessage()).append('\n'); }
        b.append("  network: ").append(wayOutSaid()).append('\n');
        boolean up = health.test(URL);
        b.append("  proxy at ").append(URL).append(": ").append(up ? "answers" : "does not answer (service " + serviceState() + "; log: " + logHint() + ")").append('\n');
        if (up) {
            String running = loaded.apply(URL);
            if (!running.isEmpty()) b.append("  loaded now: ").append(loadedSaid(running, name)).append('\n');
        }
        String others = othersSaid(dir());
        if (!others.isEmpty()) b.append("  ").append(others).append('\n');
        String rz = ResearchZoshoInstall.livesInOurFolderSaid();
        if (!rz.isEmpty()) b.append("  ").append(rz).append('\n');
        return b.toString();
    }

    /** What the proxy at {@code base} has loaded now: llama-swap's /running, as it answers; "" when it does not. A seam: a test reads no proxy of the machine. */
    public static Function<String, String> loaded = base -> {
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            return c.send(HttpRequest.newBuilder(URI.create(base + "/running")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) { return ""; }
    };

    /** The files an install of this program makes in its folder, by name: what an uninstall removes, and nothing else. */
    static final List<String> OWN_FILES = List.of("config.yaml", "run.sh", "plan.properties", "previous", "llama-swap", "llama-swap.exe",
            "LICENSE.md", "README.md", "start.ps1", "llama-swap.log", "llama-swap.err");

    /** Whether a name in the folder is one this program made: one of {@link #OWN_FILES}, a download of its own (an archive, a part), or its llama.cpp folder. */
    static boolean ownName(String name) {
        return OWN_FILES.contains(name) || name.equals("llama") || name.endsWith(".part") || name.startsWith("llama-swap_" + SWAP_VERSION + "_") || name.startsWith("llama-" + LLAMA_BUILD + "-bin-");
    }

    /** The names in the folder this program did not make, sorted; empty when there are none or no folder. */
    static List<String> othersIn(Path d) {
        if (!Files.isDirectory(d)) return List.of();
        try (Stream<Path> in = Files.list(d)) { return in.map(p -> p.getFileName().toString()).filter(n -> !ownName(n)).sorted().toList(); }
        catch (IOException e) { return List.of(); }
    }

    /** What of another program the model folder holds, and that CodeZaiku leaves it as it is; "" when nothing. */
    static String othersSaid(Path d) {
        List<String> others = othersIn(d);
        List<String> entries = new ArrayList<>();
        try {
            if (Files.isRegularFile(d.resolve("config.yaml"))) {
                Matcher e = Pattern.compile("(?m)^  \"([^\"]+)\":\\s*\\n\\s*cmd:\\s*(\\S+)").matcher(Files.readString(d.resolve("config.yaml"), StandardCharsets.UTF_8));
                while (e.find()) { Path script = Path.of(e.group(2)); if (script.getParent() != null && script.getParent().equals(d) && !ownName(script.getFileName().toString())) entries.add(e.group(1)); }
            }
        } catch (IOException | RuntimeException ignored) { }
        if (others.isEmpty() && entries.isEmpty()) return "";
        return "another program's, in this folder, left as it is: " + String.join(", ", others)
                + (entries.isEmpty() ? "" : (others.isEmpty() ? "" : "; ") + "its entr" + (entries.size() == 1 ? "y " : "ies ") + String.join(", ", entries) + " in config.yaml");
    }

    /**
     * What this program made in its folder removed, and the folder when nothing is left in it. A file another program wrote there stays,
     * and so does the folder then. "" when the folder is gone; else the words that say what stays.
     */
    static String removeOwn(Path d) throws IOException {
        if (!Files.isDirectory(d)) return "";
        try (Stream<Path> in = Files.list(d)) {
            for (Path p : in.sorted().toList()) {
                String name = p.getFileName().toString();
                if (!ownName(name)) continue;
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) { try (var s = Files.walk(p)) { s.sorted(Comparator.reverseOrder()).forEach(q -> { try { Files.deleteIfExists(q); } catch (IOException ignored) { } }); } }
                else Files.deleteIfExists(p);
            }
        }
        List<String> stays = othersIn(d);
        if (stays.isEmpty()) { Files.deleteIfExists(d); return ""; }
        return "; the folder " + d + " stays, since it holds what another program made (" + String.join(", ", stays) + ")";
    }

    /**
     * Whether the model is loaded, read from the proxy's list of what runs. The list can hold more than this program's model: another
     * program may run its own entry through the same proxy (an embeddings server that stays loaded), and that one running is not the
     * model's memory in use. What else runs is said beside the answer.
     */
    static String loadedSaid(String running, String name) {
        List<String> up = new ArrayList<>();
        Matcher m = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"").matcher(running == null ? "" : running);
        while (m.find()) if (!up.contains(m.group(1))) up.add(m.group(1));
        boolean loaded = up.remove(name);
        String beside = up.isEmpty() ? "" : "; the proxy also runs " + String.join(", ", up) + ", which is not this model";
        return (loaded ? "yes (the memory is in use" : "no (the memory is free; the next request starts it") + beside + ")";
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
            if (!force && URL.equals(sib)) return "!ResearchZosho's settings also point at this proxy (" + (ResearchZoshoInstall.livesInOurFolder() ? Config.userConfigPath() : Path.of(home(), ".researchzosho", "config")) + "); point it elsewhere first, or pass --force";
            switch (os) {
                case linux -> {
                    boolean shut = false;
                    try { shut = Files.exists(dir().resolve("plan.properties")) && shutOf(readPlan(dir())) != null; } catch (IOException | RuntimeException ignored) { }
                    runner.run(List.of("systemctl", "--user", "disable", "--now", UNIT)); Files.deleteIfExists(unitPath(os)); runner.run(List.of("systemctl", "--user", "daemon-reload"));
                    if (shut) removeNetwork();   // the network with no way out goes with the install that made it
                }
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
            return "removed the service and the proxy; the model files in " + modelsDir() + " stay" + restored + removeOwn(d);
        } catch (Exception e) { return "!" + e.getMessage(); }
    }

    /** `model serve …` from the command line: install [--file F] [--gpu N] [--idle-minutes N] [--share] | status | stop | uninstall. */
    public static int command(String[] a, int from, PrintStream out) {
        String op = a.length > from ? a[from] : "status";
        // an install saved with a stop command that stops nothing is put right before anything else is done with it
        String fixed = op.equals("uninstall") ? "" : repairStop();
        if (!fixed.isEmpty()) out.println(fixed.startsWith("!") ? "  not repaired: " + fixed.substring(1) : "  repaired: " + fixed + ".");
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
