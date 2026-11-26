package org.codezaiku;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.Assumptions;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
/**
 * The model server on demand, on all three platforms: the row for the memory, the files an install writes, and the install
 * itself with every seam faked — the platform included, so a Linux host exercises the macOS and Windows paths too.
 */
class ModelServerTest {
    final ModelServer.Os realOs = ModelServer.os;
    final ModelServer.Runner realRunner = ModelServer.runner;
    final ModelServer.Downloader realDownloader = ModelServer.downloader;
    final Predicate<String> realHealth = ModelServer.health;
    final ModelServer.Detacher realDetach = ModelServer.detach;
    final Map<String, String> realSums = new HashMap<>(ModelServer.sums);
    final Function<String, ModelServer.Served> realServedAt = ModelServer.servedAt;
    Path realDrm = Gpu.SYS_DRM, realKfd = Gpu.SYS_KFD, realDev = Gpu.DEV;
    /** The GPU readers look at a stand-in tree, empty unless a case fills it, so the box that runs the suite does not leak in. */
    @BeforeEach void standInKernel() throws Exception {
        Path sys = Files.createTempDirectory("cz-sys");
        for (String d : List.of("drm", "kfd", "dev")) Files.createDirectories(sys.resolve(d));
        Gpu.SYS_DRM = sys.resolve("drm"); Gpu.SYS_KFD = sys.resolve("kfd"); Gpu.DEV = sys.resolve("dev");
    }
    @AfterEach void restoreGpu() { Gpu.SYS_DRM = realDrm; Gpu.SYS_KFD = realKfd; Gpu.DEV = realDev; }
    @AfterEach void restore() { ModelServer.sums.clear(); ModelServer.sums.putAll(realSums); ModelServer.os = realOs; ModelServer.runner = realRunner; ModelServer.downloader = realDownloader; ModelServer.health = realHealth; ModelServer.detach = realDetach; ModelServer.servedAt = realServedAt; }

    @Test
    void anInstallUsesAServerAnotherProgramAlreadyRunsWithAKnownModelAndDownloadsNothing(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        boolean[] started = {false}; List<String> fetched = new ArrayList<>();
        try {
            fakeMachine(ModelServer.Os.linux, started, fetched);
            ModelServer.health = base -> started[0];   // nothing of ours on 8211 until an install starts it
            Map<String, ModelServer.Served> ports = new HashMap<>();
            // Wyrdsekai's brain: llama-server without --alias lists the file it loaded
            ports.put("http://127.0.0.1:8200", new ModelServer.Served(List.of("/models/Qwen3.6-35B-A3B-UD-Q4_K_M.gguf"), "/models/Qwen3.6-35B-A3B-UD-Q4_K_M.gguf"));
            ports.put("http://127.0.0.1:11434", new ModelServer.Served(List.of("llama3:8b"), null));   // a model CodeZaiku does not know
            ModelServer.servedAt = ports::get;
            var out = new ByteArrayOutputStream();
            String r = ModelServer.install(null, "all", 20, false, false, new PrintStream(out, true));
            assertEquals("/models/Qwen3.6-35B-A3B-UD-Q4_K_M.gguf", r, out.toString());
            assertEquals("http://127.0.0.1:8200", Config.get("CODEZAIKU_DRIVE"));
            assertEquals("/models/Qwen3.6-35B-A3B-UD-Q4_K_M.gguf", Config.get("CODEZAIKU_MODEL"));
            assertTrue(fetched.isEmpty() && !started[0] && !Files.exists(ModelServer.dir()), "nothing downloaded, nothing started: " + fetched);
            assertTrue(out.toString().contains("(qwen3.6-35b-a3b)") && out.toString().contains("codezaiku model serve install --own"), out.toString());
            // a Wyrdsekai drive fine-tune is not one of the known models
            assertNull(ModelServer.knownModel("wyrdsekai-3.5-9b-drive-v6-q4km.gguf"));
            assertEquals("qwen3.6-35b-a3b", ModelServer.knownModel("qwen3.6:35b-a3b-q4_K_M"));
            // --own: CodeZaiku's own install, as before
            ModelServer.install(null, "all", 20, false, true, new PrintStream(new ByteArrayOutputStream()));
            assertFalse(fetched.isEmpty(), "its own model downloaded");
            assertEquals("http://127.0.0.1:8211", Config.get("CODEZAIKU_DRIVE"));
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }

    @Test
    void theRowFollowsTheMemory() {
        assertEquals("qwen3.8-27b", ModelServer.rowFor(48).name());
        assertEquals("gpt-oss-20b", ModelServer.rowFor(16).name());
        assertEquals("qwen3.5-9b", ModelServer.rowFor(11).name());
        assertEquals("gemma-4-e4b", ModelServer.rowFor(6).name());
        assertEquals("gemma-4-e2b", ModelServer.rowFor(2).name());
        assertNull(ModelServer.rowFor(1));
    }

    @Test
    void eachPlatformsPlanStartsTheRightServerTheRightWay(@TempDir Path tmp) {
        Path model = tmp.resolve("models/gpt-oss-20b-F16.gguf"), dir = tmp.resolve("model");
        var linux = ModelServer.plan(ModelServer.Os.linux, ModelServer.rowFor(16), model, dir, tmp.resolve("unit.service"), "all", 20, false);
        assertTrue(linux.configYaml().contains("cmd: " + dir.resolve("run.sh") + " ${PORT}") && linux.configYaml().contains("cmdStop: docker stop codezaiku-model") && linux.configYaml().contains("ttl: 1200") && linux.configYaml().contains("\"local-model\""), linux.configYaml());
        assertTrue(linux.runScript().contains("--gpus all") && linux.runScript().contains("-m /m/gpt-oss-20b-F16.gguf") && linux.runScript().contains("-c 32768 --parallel 2") && linux.runScript().contains("reasoning_effort"), linux.runScript());
        assertTrue(linux.unitText().contains("ExecStart=" + dir.resolve("llama-swap")) && linux.unitText().contains("--listen 127.0.0.1:8211"), linux.unitText());
        var mac = ModelServer.plan(ModelServer.Os.macos, ModelServer.rowFor(11), tmp.resolve("models/Qwen3.5-9B-Q4_K_M.gguf"), dir, tmp.resolve("org.x.plist"), "all", 20, false);
        assertTrue(mac.configYaml().contains("cmd: " + dir.resolve("llama").resolve("llama-server") + " -m " + tmp.resolve("models/Qwen3.5-9B-Q4_K_M.gguf") + " --host 127.0.0.1 --port ${PORT} --jinja -c 16384 --parallel 1 -ngl 99 --flash-attn on --chat-template-kwargs '{\"enable_thinking\":false}'"), mac.configYaml());
        assertFalse(mac.configYaml().contains("cmdStop"), "no container to stop on macOS");
        assertNull(mac.runScript());
        assertTrue(mac.unitText().contains("<string>org.codezaiku.model</string>") && mac.unitText().contains("<string>--listen</string>\n    <string>127.0.0.1:8211</string>") && mac.unitText().contains("<key>KeepAlive</key><true/>"), mac.unitText());
        var win = ModelServer.plan(ModelServer.Os.windows, ModelServer.rowFor(16), tmp.resolve("models/gpt-oss-20b-F16.gguf"), dir, dir.resolve("start.ps1"), "all", 45, true);
        assertTrue(win.configYaml().contains("cmd: " + dir.resolve("llama").resolve("llama-server.exe") + " -m ") && win.configYaml().contains("--chat-template-kwargs \"{\\\"reasoning_effort\\\":\\\"low\\\"}\"") && win.configYaml().contains("ttl: 2700"), win.configYaml());
        assertTrue(win.unitText().contains("Start-Process -FilePath '" + dir.resolve("llama-swap.exe") + "'") && win.unitText().contains("'0.0.0.0:8211'"), win.unitText());
        var shared = ModelServer.plan(ModelServer.Os.linux, ModelServer.rowFor(48), model, dir, tmp.resolve("u"), "3", 45, true);
        assertTrue(shared.runScript().contains("--gpus '\"device=3\"'") && shared.unitText().contains("--listen 0.0.0.0:8211"), shared.runScript());
    }

    static final String SHA_X = "2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881";   // sha256 of "x", what the fake downloads hold

    /** The machine as the fakes describe it: which commands answer, what the archives unpack to. */
    private List<List<String>> fakeMachine(ModelServer.Os os, boolean[] started, List<String> fetched) {
        List<List<String>> ran = new ArrayList<>();
        ModelServer.os = os;
        ModelServer.runner = cmd -> {
            ran.add(cmd);
            String c = cmd.get(0);
            if (c.equals("docker")) return new ModelServer.Result(os == ModelServer.Os.linux ? 0 : 1, "map[nvidia:{...} runc:{...}]");
            if (c.equals("nvidia-smi")) return new ModelServer.Result(os == ModelServer.Os.linux ? 0 : 1, "NVIDIA RTX A4000, 16380\n");
            if (c.equals("sysctl")) return new ModelServer.Result(0, String.valueOf(16L * 1073741824L) + "\n");
            if (c.equals("powershell") && cmd.contains("-Command")) return new ModelServer.Result(0, String.valueOf(13L * 1073741824L) + "\n");
            if (c.startsWith("curl")) return new ModelServer.Result(0, "HTTP/2 200\ncontent-length: 13780000000\n");
            if (c.startsWith("tar")) {   // unpacks: the proxy into -C, or llama-server into -C
                Path into = Path.of(cmd.get(cmd.indexOf("-C") + 1));
                String archive = cmd.get(2);
                try {
                    Files.createDirectories(into);
                    if (archive.contains("llama-swap")) Files.writeString(into.resolve(os == ModelServer.Os.windows ? "llama-swap.exe" : "llama-swap"), "x");
                    else Files.writeString(into.resolve(os == ModelServer.Os.windows ? "llama-server.exe" : "llama-server"), "x");
                } catch (Exception e) { return new ModelServer.Result(1, e.toString()); }
                return new ModelServer.Result(0, "");
            }
            if (c.equals("id")) return new ModelServer.Result(0, "501\n");
            if (cmd.contains("enable") || cmd.contains("bootstrap")) started[0] = true;
            return new ModelServer.Result(0, "");
        };
        ModelServer.detach = cmd -> { ran.add(cmd); if (cmd.get(0).equals("powershell") && cmd.contains("-File")) started[0] = true; };
        ModelServer.downloader = (url, dest) -> { fetched.add(url); Files.createDirectories(dest.getParent()); Files.writeString(dest, "x"); ModelServer.sums.put(dest.getFileName().toString(), SHA_X); };
        ModelServer.health = base -> started[0];
        return ran;
    }

    @Test
    void onLinuxInstallWritesTheFilesStartsTheServiceAndPointsTheDriveAtTheProxy(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        boolean[] started = {false}; List<String> fetched = new ArrayList<>();
        try {
            List<List<String>> ran = fakeMachine(ModelServer.Os.linux, started, fetched);
            Config.set("CODEZAIKU_DRIVE", "http://elsewhere:8211");   // a drive the person had set, to be put back on uninstall
            assertTrue(ModelServer.offer().startsWith("Serve gpt-oss-20b on this machine on demand (CUDA in Docker): it downloads once (about 14 GB)"), ModelServer.offer());
            var out = new ByteArrayOutputStream();
            String r = ModelServer.install(null, "all", 20, false, new PrintStream(out, true));
            assertEquals("gpt-oss-20b", r, out.toString());
            assertTrue(fetched.get(0).endsWith("/gpt-oss-20b-F16.gguf") && fetched.get(1).contains("llama-swap_255_linux_"), fetched.toString());
            assertEquals(2, fetched.size(), "no llama.cpp download on Linux: the container is the server");
            Path dir = ModelServer.dir();
            assertTrue(Files.readString(dir.resolve("config.yaml")).contains("\"gpt-oss-20b\":"));
            assertTrue(Files.isExecutable(dir.resolve("run.sh")));
            assertTrue(Files.readString(home.resolve(".config/systemd/user/codezaiku-model.service")).contains("--listen 127.0.0.1:8211"));
            assertTrue(ran.stream().anyMatch(c -> c.equals(List.of("systemctl", "--user", "enable", "--now", "codezaiku-model"))), ran.toString());
            assertEquals("http://127.0.0.1:8211", Config.get("CODEZAIKU_DRIVE"));
            assertEquals("gpt-oss-20b", Config.get("CODEZAIKU_MODEL"));
            ModelServer.health = base -> false;
            String u = ModelServer.uninstall();
            assertTrue(u.contains("the drive is back to http://elsewhere:8211"), u);
            assertEquals("http://elsewhere:8211", Config.get("CODEZAIKU_DRIVE"));
            assertFalse(Files.exists(ModelServer.dir()));
            ModelServer.health = base -> true;
            assertEquals("local-model", ModelServer.install(null, "all", 20, false, new PrintStream(new ByteArrayOutputStream())));
            assertTrue(ModelServer.offer().startsWith("A model proxy already answers"));
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }

    /** What another program's installer adds to this folder's proxy: an embeddings server beside the model, and the group that keeps it loaded. */
    private static final String THEIR_ENTRIES = "  \"embed\":\n    cmd: DIR/run-embed.sh ${PORT}\n    cmdStop: docker stop other-embed\n    proxy: http://127.0.0.1:${PORT}\n    ttl: 0\n"
            + "groups:\n  \"embeddings\":\n    swap: false\n    exclusive: false\n    members: [\"embed\"]\n";

    @Test
    void anInstallSavedWithAStopCommandThatStopsNothingIsPutRightOnce(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        try {
            List<List<String>> ran = fakeMachine(ModelServer.Os.linux, new boolean[1], new ArrayList<>());
            Path dir = ModelServer.dir(); Files.createDirectories(dir);
            Path unit = home.resolve(".config/systemd/user/codezaiku-model.service");
            var mine = ModelServer.plan(ModelServer.Os.linux, ModelServer.rowFor(16), ModelServer.modelsDir().resolve("gpt-oss-20b-F16.gguf"), dir, unit, "all", 20, false);
            // the fault as found on a machine both programs set up: this program's start script, and a config another program rewrote with its own container's name
            Files.writeString(dir.resolve("run.sh"), mine.runScript());
            String theirs = THEIR_ENTRIES.replace("DIR", dir.toString());
            Files.writeString(dir.resolve("run-embed.sh"), "#!/bin/sh\nexec docker run --rm --name other-embed image\n");
            String wrong = mine.configYaml().replace("docker stop " + ModelServer.CONTAINER, "docker stop other-model") + theirs;
            Files.writeString(dir.resolve("config.yaml"), wrong);
            Files.createDirectories(unit.getParent()); Files.writeString(unit, mine.unitText());
            String said = ModelServer.repairStop();
            assertTrue(said.contains("was never stopped") && !said.startsWith("!"), said);
            assertEquals(wrong.replace("docker stop other-model", "docker stop " + ModelServer.CONTAINER), Files.readString(dir.resolve("config.yaml")), "that one line and nothing else: the other program's entries stay");
            assertTrue(ran.contains(List.of("systemctl", "--user", "restart", "codezaiku-model")), "the proxy rereads its config only on a restart: " + ran);
            assertTrue(ran.contains(List.of("docker", "stop", ModelServer.CONTAINER)), "the model the old command left running is stopped: " + ran);
            ran.clear();
            assertEquals("", ModelServer.repairStop(), "once");
            assertTrue(ran.isEmpty(), ran.toString());
            // the unit that runs this folder's proxy may be the other program's
            Files.move(unit, unit.resolveSibling("other-model.service"));
            assertEquals("other-model", ModelServer.proxyUnit());
            ModelServer.os = ModelServer.Os.macos;
            Files.writeString(dir.resolve("config.yaml"), wrong);
            assertEquals("", ModelServer.repairStop(), "only where the server is a container");
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }

    @Test
    void anInstallKeepsWhatAnotherProgramAddedToTheProxy(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        try {
            fakeMachine(ModelServer.Os.linux, new boolean[1], new ArrayList<>());
            Path dir = ModelServer.dir(); Files.createDirectories(dir);
            String theirs = THEIR_ENTRIES.replace("DIR", dir.toString());
            // a folder another program's installer wrote: its model under its container's name, and its embeddings server beside it
            Files.writeString(dir.resolve("config.yaml"), "# theirs\nmodels:\n  \"their-model\":\n    cmd: " + dir.resolve("run.sh") + " ${PORT}\n    cmdStop: docker stop other-model\n    ttl: 600\n" + theirs);
            Files.writeString(dir.resolve("run.sh"), "#!/bin/sh\nexec docker run --rm --name other-model image\n");
            Files.writeString(dir.resolve("run-embed.sh"), "#!/bin/sh\nexec docker run --rm --name other-embed image\n");
            assertEquals(theirs, ModelServer.keptEntries(Files.readString(dir.resolve("config.yaml"))), "everything after the first model");
            assertEquals("", ModelServer.keptEntries("models:\n  \"one\":\n    cmd: x\n    ttl: 1\n"), "a config with one model holds nothing more");
            // asked for a server of its own (--own), over the folder the other program wrote
            assertEquals("gpt-oss-20b", ModelServer.install(null, "all", 20, false, true, new PrintStream(new ByteArrayOutputStream(), true)));
            String y = Files.readString(dir.resolve("config.yaml"));
            assertTrue(y.contains("\"gpt-oss-20b\":") && !y.contains("their-model"), "the model is this install's: " + y);
            assertTrue(y.endsWith(theirs), "the embeddings server and its group are kept as they were: " + y);
            assertTrue(Files.readString(dir.resolve("run.sh")).contains("--name " + ModelServer.CONTAINER) && y.contains("cmdStop: docker stop " + ModelServer.CONTAINER + "\n"), "the start script and the stop command name the same container");
            assertTrue(y.contains("cmdStop: docker stop other-embed\n"), "and so do the other program's");
            assertEquals("", ModelServer.repairStop());
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }

    @Test
    void theStatusSaysTheModelIsLoadedOnlyWhenTheModelIs() {
        // the proxy's list as it stands on a machine where another program runs its embeddings server through the same proxy
        String onlyEmbed = "{\"running\":[{\"model\":\"embed\",\"state\":\"ready\",\"ttl\":0}]}";
        String both = "{\"running\":[{\"model\":\"embed\",\"state\":\"ready\"},{\"model\":\"gpt-oss-20b\",\"state\":\"ready\"}]}";
        assertEquals("no (the memory is free; the next request starts it; the proxy also runs embed, which is not this model)", ModelServer.loadedSaid(onlyEmbed, "gpt-oss-20b"));
        assertEquals("yes (the memory is in use; the proxy also runs embed, which is not this model)", ModelServer.loadedSaid(both, "gpt-oss-20b"));
        assertEquals("yes (the memory is in use)", ModelServer.loadedSaid("{\"running\":[{\"model\":\"gpt-oss-20b\"}]}", "gpt-oss-20b"));
        assertEquals("no (the memory is free; the next request starts it)", ModelServer.loadedSaid("{\"running\":[]}", "gpt-oss-20b"));
        assertEquals("no (the memory is free; the next request starts it)", ModelServer.loadedSaid(null, "gpt-oss-20b"));
    }

    @Test
    void onMacOsInstallFetchesTheMetalBuildSizesByUnifiedMemoryAndLoadsALaunchAgent(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        boolean[] started = {false}; List<String> fetched = new ArrayList<>();
        try {
            List<List<String>> ran = fakeMachine(ModelServer.Os.macos, started, fetched);
            assertEquals(11, (int) ModelServer.budgetGb(), "seven tenths of 16 GB");
            assertTrue(ModelServer.offer().startsWith("Serve qwen3.5-9b on this machine on demand (Metal)"), ModelServer.offer());
            String r = ModelServer.install(null, "all", 20, false, new PrintStream(new ByteArrayOutputStream()));
            assertEquals("qwen3.5-9b", r);
            assertTrue(fetched.stream().anyMatch(u -> u.contains("llama-swap_255_darwin_")), fetched.toString());
            assertTrue(fetched.stream().anyMatch(u -> u.contains("/releases/download/b10929/llama-b10929-bin-macos-")), fetched.toString());
            Path dir = ModelServer.dir();
            assertTrue(Files.exists(dir.resolve("llama").resolve("llama-server")));
            assertTrue(Files.readString(dir.resolve("config.yaml")).contains("llama-server -m " + home.resolve("models/Qwen3.5-9B-Q4_K_M.gguf")));
            Path plist = home.resolve("Library/LaunchAgents/org.codezaiku.model.plist");
            assertTrue(Files.readString(plist).contains("<string>org.codezaiku.model</string>"));
            assertTrue(ran.stream().anyMatch(c -> c.size() >= 4 && c.get(0).equals("launchctl") && c.get(1).equals("bootstrap") && c.get(3).equals(plist.toString())), ran.toString());
            assertEquals("http://127.0.0.1:8211", Config.get("CODEZAIKU_DRIVE"));
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }

    @Test
    void onWindowsInstallFetchesTheVulkanBuildSizesByRamWithoutAnNvidiaCardAndRegistersALogonTask(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        boolean[] started = {false}; List<String> fetched = new ArrayList<>();
        try {
            List<List<String>> ran = fakeMachine(ModelServer.Os.windows, started, fetched);
            Assumptions.assumeTrue(System.getProperty("os.arch", "").contains("64") && !System.getProperty("os.arch", "").contains("aarch"), "the Windows path is x64");
            assertEquals(6, (int) ModelServer.budgetGb(), "half of 13 GB RAM when no NVIDIA card answers");
            assertTrue(ModelServer.offer().startsWith("Serve gemma-4-e4b on this machine on demand (Vulkan)"), ModelServer.offer());
            String r = ModelServer.install(null, "all", 20, false, new PrintStream(new ByteArrayOutputStream()));
            assertEquals("gemma-4-e4b", r);
            assertTrue(fetched.stream().anyMatch(u -> u.endsWith("llama-swap_255_windows_amd64.zip")), fetched.toString());
            assertTrue(fetched.stream().anyMatch(u -> u.endsWith("llama-b10929-bin-win-vulkan-x64.zip")), fetched.toString());
            Path dir = ModelServer.dir();
            assertTrue(Files.exists(dir.resolve("llama").resolve("llama-server.exe")));
            assertTrue(Files.readString(dir.resolve("config.yaml")).contains("llama-server.exe -m "));
            assertTrue(Files.readString(dir.resolve("start.ps1")).contains("Start-Process -FilePath '" + dir.resolve("llama-swap.exe") + "'"));
            assertTrue(ran.stream().anyMatch(c -> c.get(0).equals("schtasks") && c.contains("/create") && c.contains("CodeZaikuModel") && c.contains("onlogon")), ran.toString());
            assertTrue(ran.stream().anyMatch(c -> c.get(0).equals("powershell") && c.contains("-File") && c.contains(dir.resolve("start.ps1").toString())), "started now, detached: " + ran);
            assertEquals("http://127.0.0.1:8211", Config.get("CODEZAIKU_DRIVE"));
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }

    @Test
    void withoutDockerOnLinuxItSaysWhy() {
        ModelServer.os = ModelServer.Os.linux;
        ModelServer.health = base -> false;
        ModelServer.runner = cmd -> { throw new IOException("docker: not found"); };
        assertTrue(ModelServer.unsupported().contains("docker"), ModelServer.unsupported());
        assertNull(ModelServer.offer());
        ModelServer.runner = cmd -> cmd.get(0).equals("docker") ? new ModelServer.Result(0, "map[runc:{...}]") : new ModelServer.Result(0, "NVIDIA RTX A4000, 16380");
        assertTrue(ModelServer.unsupported().contains("NVIDIA runtime"), ModelServer.unsupported());
        ModelServer.runner = cmd -> cmd.get(0).equals("docker") ? new ModelServer.Result(0, "map[runc:{...}]") : new ModelServer.Result(0, "nvidia-smi not found. This is AMD country.\n");
        assertTrue(ModelServer.unsupported().startsWith("no GPU"), "a stub nvidia-smi that exits 0 is not a card: " + ModelServer.unsupported());
    }

    /** An AMD machine as the kernel describes it: the device under drm, its compute target under kfd, the device files, and no NVIDIA runtime in docker. */
    private void amdMachine(String pciId, long vramBytes, String kfdProps, boolean kfdDevice) throws Exception {
        Path d = Gpu.SYS_DRM.resolve("card0").resolve("device");
        Files.createDirectories(d);
        Files.writeString(d.resolve("vendor"), "0x1002\n"); Files.writeString(d.resolve("device"), pciId + "\n"); Files.writeString(d.resolve("mem_info_vram_total"), vramBytes + "\n");
        if (kfdProps != null) { Files.createDirectories(Gpu.SYS_KFD.resolve("1")); Files.writeString(Gpu.SYS_KFD.resolve("1").resolve("properties"), kfdProps); }
        if (kfdDevice) Files.writeString(Gpu.DEV.resolve("kfd"), "");
        Files.createDirectories(Gpu.DEV.resolve("dri"));
        ModelServer.os = ModelServer.Os.linux;
        ModelServer.health = base -> false;
        ModelServer.runner = cmd -> switch (cmd.get(0)) {
            case "docker" -> new ModelServer.Result(0, "map[runc:{...}]");
            case "nvidia-smi" -> new ModelServer.Result(0, "nvidia-smi not found. This is AMD country.\n");
            case "sh" -> new ModelServer.Result(0, (64L << 30) + "\n");
            default -> new ModelServer.Result(0, "HTTP/2 200\ncontent-length: 13780000000\n");
        };
    }

    @Test
    void onLinuxAnInstinctCardServesThroughTheRocmContainerWithoutTheNvidiaRuntime(@TempDir Path tmp) throws Exception {
        amdMachine("0x74b5", 205822885888L, "simd_count 1216\ngfx_target_version 90402\n", true);
        assertNull(ModelServer.unsupported(), ModelServer.unsupported());
        assertEquals(192, (int) ModelServer.budgetGb(), "what the kernel says is set aside for the card");
        assertEquals("ROCm in Docker", ModelServer.backend());
        assertTrue(ModelServer.offer().startsWith("Serve qwen3.8-27b on this machine on demand (ROCm in Docker): it downloads once (about 16 GB)"), ModelServer.offer());
        var p = ModelServer.plan(ModelServer.Os.linux, ModelServer.rowFor(192), tmp.resolve("m.gguf"), tmp, tmp.resolve("u"), "all", 20, false, ModelServer.gpu().build());
        assertTrue(p.runScript().contains("exec docker run --rm --name codezaiku-model --device /dev/kfd --device /dev/dri --group-add video -p 127.0.0.1:$1:8080"), p.runScript());
        assertTrue(p.runScript().contains("ghcr.io/ggml-org/llama.cpp:server-rocm \\\n  -m /m/m.gguf --host 0.0.0.0 --port 8080 --jinja -c 131072 --parallel 4 -ngl 99 --flash-attn on"), p.runScript());
        assertFalse(p.runScript().contains("--gpus") || p.runScript().contains("VISIBLE_DEVICES"), p.runScript());
        var one = ModelServer.plan(ModelServer.Os.linux, ModelServer.rowFor(192), tmp.resolve("m.gguf"), tmp, tmp.resolve("u"), "1", 20, false, "rocm");
        assertTrue(one.runScript().contains("--group-add video -e HIP_VISIBLE_DEVICES=1 -p"), one.runScript());
        Files.delete(Gpu.DEV.resolve("kfd"));
        assertTrue(ModelServer.unsupported().startsWith("/dev/kfd is not here"), ModelServer.unsupported());
        assertNull(ModelServer.offer());
    }

    @Test
    void onLinuxARadeonOrAnApuServesThroughTheVulkanContainerWithTheRenderDevices(@TempDir Path tmp) throws Exception {
        amdMachine("0x1586", 32L << 30, null, false);   // a Ryzen AI MAX with 32 GB set aside in the BIOS; no compute topology, the PCI id names it
        assertNull(ModelServer.unsupported(), ModelServer.unsupported());
        assertEquals(32, (int) ModelServer.budgetGb());
        assertEquals("Vulkan in Docker", ModelServer.backend());
        assertTrue(ModelServer.offer().startsWith("Serve qwen3.8-27b on this machine on demand (Vulkan in Docker)"), ModelServer.offer());
        var p = ModelServer.plan(ModelServer.Os.linux, ModelServer.rowFor(32), tmp.resolve("m.gguf"), tmp, tmp.resolve("u"), "all", 20, false, ModelServer.gpu().build());
        assertTrue(p.runScript().contains("--name codezaiku-model --device /dev/dri --group-add video -p 127.0.0.1:$1:8080") && p.runScript().contains("llama.cpp:server-vulkan"), p.runScript());
        assertFalse(p.runScript().contains("/dev/kfd") || p.runScript().contains("VISIBLE_DEVICES"), "GGML_VK_VISIBLE_DEVICES stays unset for all: " + p.runScript());
        var one = ModelServer.plan(ModelServer.Os.linux, ModelServer.rowFor(32), tmp.resolve("m.gguf"), tmp, tmp.resolve("u"), "0", 20, false, "vulkan");
        assertTrue(one.runScript().contains("-e GGML_VK_VISIBLE_DEVICES=0 -p"), one.runScript());
        // an Intel GPU: the same container, sized by half the RAM
        Files.writeString(Gpu.SYS_DRM.resolve("card0").resolve("device").resolve("vendor"), "0x8086\n");
        Files.delete(Gpu.SYS_DRM.resolve("card0").resolve("device").resolve("mem_info_vram_total"));
        assertNull(ModelServer.unsupported(), ModelServer.unsupported());
        assertEquals(8, (int) ModelServer.budgetGb(), "half of 64 GB RAM, capped at the 9B's row: an iGPU shares the memory, not a card's speed");
        assertTrue(ModelServer.offer().startsWith("Serve qwen3.5-9b on this machine on demand (Vulkan in Docker)"), ModelServer.offer());
        assertEquals("Vulkan in Docker", ModelServer.backend());
        Files.walk(Gpu.DEV.resolve("dri")).forEach(x -> x.toFile().delete());
        assertTrue(ModelServer.unsupported().startsWith("/dev/dri is not here"), ModelServer.unsupported());
    }

    @Test
    void aDownloadThatDoesNotMatchItsRecordedHashIsRefusedAndDeleted(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        boolean[] started = {false}; List<String> fetched = new ArrayList<>();
        try {
            fakeMachine(ModelServer.Os.linux, started, fetched);
            ModelServer.downloader = (url, dest) -> { fetched.add(url); Files.createDirectories(dest.getParent()); Files.writeString(dest, "tampered"); };   // the recorded hash stays
            String r = ModelServer.install(null, "all", 20, false, new PrintStream(new ByteArrayOutputStream()));
            assertTrue(r.startsWith("!checksum mismatch for gpt-oss-20b-F16.gguf"), r);
            assertFalse(Files.exists(home.resolve("models/gpt-oss-20b-F16.gguf")), "nothing kept");
            assertNull(Config.get("CODEZAIKU_DRIVE"), "the drive was not touched");
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }

    @Test
    void uninstallRefusesWhileTheOtherProductStillPointsAtTheProxy(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        try {
            ModelServer.os = ModelServer.Os.linux;
            ModelServer.runner = cmd -> new ModelServer.Result(0, "");
            Files.createDirectories(home.resolve(".codezaiku"));   // this product's own state dir, so its settings are its own file
            Path sib = home.resolve(".researchzosho").resolve("config");
            Files.createDirectories(sib.getParent());
            Files.writeString(sib, "drive = http://127.0.0.1:8211\n");
            String r = ModelServer.uninstall();
            assertTrue(r.startsWith("!ResearchZosho's settings also point at this proxy"), r);
            assertFalse(ModelServer.uninstall(true).startsWith("!"), "forced");
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }

    @Test
    void theOfferSaysTheSizeOfTheFileFromItsRowAndAsksNobody() {
        boolean[] started = {false};
        List<List<String>> ran = fakeMachine(ModelServer.Os.linux, started, new ArrayList<>());
        ModelServer.Runner machine = ModelServer.runner;
        // what doctor runs when no model answers: the machine's own commands answer, and a request for the file's size gets nothing
        ModelServer.runner = cmd -> cmd.get(0).startsWith("curl") ? new ModelServer.Result(0, "") : machine.run(cmd);
        String offer = ModelServer.offer();
        assertTrue(offer.startsWith("Serve gpt-oss-20b on this machine on demand (CUDA in Docker): it downloads once (about 14 GB)"), offer);
        assertEquals(List.of(), ran.stream().filter(c -> c.get(0).startsWith("curl") || String.join(" ", c).contains("huggingface")).toList(),
                "nothing is asked of Hugging Face to say how big the file is");
    }

    /** What curl -sIL -w "\n%{http_code}" prints for a row's file on Hugging Face: its own sha256 and size on the first answer, then 200. */
    static String hfHead(String sha256, long bytes) {
        return "HTTP/2 302\nx-repo-commit: 0000\nx-linked-size: " + bytes + "\nx-linked-etag: \"" + sha256 + "\"\n\nHTTP/2 200\ncontent-length: " + bytes + "\n\n200";
    }

    /** Hugging Face as it is today for every row: the file at each revision and on main is the one pinned. */
    static ModelServer.Result huggingFaceAsPinned(String url) {
        for (ModelServer.Row r : ModelServer.ROWS) if (url.equals(r.url()) || url.equals(r.mainUrl())) return new ModelServer.Result(0, hfHead(r.sha256(), r.bytes()));
        return new ModelServer.Result(0, "200");
    }

    @Test
    void checkReadsEveryRowAndPinnedBuildAndNamesWhatIsGone() {
        ModelServer.os = ModelServer.Os.linux;
        ModelServer.runner = cmd -> { String u = cmd.get(cmd.size() - 1); return u.contains("gpt-oss") ? new ModelServer.Result(0, "404") : huggingFaceAsPinned(u); };
        var out = new ByteArrayOutputStream();
        assertEquals(1, ModelServer.check(new PrintStream(out, true)));
        String o = out.toString();
        assertTrue(o.contains("GONE 404  https://huggingface.co/unsloth/gpt-oss-20b-GGUF/resolve/a1b6de7b9f9540e2e00885ca1a96614a187c9c3a/gpt-oss-20b-F16.gguf"), o);
        assertTrue(o.contains("ok   200  https://huggingface.co/unsloth/Qwen3.8-27B-GGUF/resolve/4ca720788d1e01f1bff70c033e0d0028fd02e502/Qwen3.8-27B-UD-Q4_K_M.gguf"), o);
        assertTrue(o.contains("llama-swap_255_windows_amd64.zip") && o.contains("llama-b10929-bin-win-vulkan-x64.zip"), o);
        assertTrue(o.contains("1 missing"), o);
    }

    @Test
    void checkReadsARateLimitAsUncheckedNotGone() {
        ModelServer.os = ModelServer.Os.linux;
        ModelServer.checkBackoffMs = 0;
        var calls = new AtomicInteger();
        ModelServer.runner = cmd -> { String u = cmd.get(cmd.size() - 1); if (u.contains("gemma-4-E2B")) calls.incrementAndGet(); return u.contains("gemma-4-E2B") ? new ModelServer.Result(0, "429") : huggingFaceAsPinned(u); };
        var out = new ByteArrayOutputStream();
        assertEquals(0, ModelServer.check(new PrintStream(out, true)), "a 429 is not a missing file: the release gate does not fail on it");
        String o = out.toString();
        assertEquals(3, calls.get(), "asked three times before giving up on the host");
        assertTrue(o.contains("WAIT 429  https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/0314792d7f1f7e229411f620751375812bb9faf2/gemma-4-E2B-it-Q4_K_M.gguf"), o);
        assertTrue(o.contains("1 not checked") && !o.contains("GONE") && !o.contains(" missing:"), o);
    }

    @Test
    void everyFileTheOfferCanNameHasARevisionAHashAndASize() {
        for (ModelServer.Row r : ModelServer.ROWS) {
            assertTrue(r.revision().matches("[0-9a-f]{40}"), r.name() + ": a commit, never a branch: " + r.revision());
            assertTrue(r.sha256().matches("[0-9a-f]{64}"), r.name());
            assertTrue(r.bytes() > 1_000_000_000L, r.name() + ": " + r.bytes());
            assertTrue(r.url().equals("https://huggingface.co/" + r.hf() + "/resolve/" + r.revision() + "/" + r.file()), r.url());
            assertEquals(r.sha256(), ModelServer.sums.get(r.file()), "the download is checked against the row's sha256");
        }
        // every amount of memory the offer looks at lands on one of these rows, or on none
        for (int gb = 0; gb <= 512; gb++) { ModelServer.Row r = ModelServer.rowFor(gb); assertTrue(r == null || ModelServer.ROWS.contains(r)); }
    }

    @Test
    void checkComparesEachRowAtItsRevisionAndSaysWhenMainHasMovedOn() {
        ModelServer.os = ModelServer.Os.linux;
        ModelServer.Row nine = ModelServer.ROWS.get(2), two = ModelServer.ROWS.get(4);
        String other = "1bce504dfecd84153666928a41d8a66706f0158d3a317bc5196b72819d7dc529";
        ModelServer.runner = cmd -> {
            String u = cmd.get(cmd.size() - 1);
            if (u.equals(nine.url())) return new ModelServer.Result(0, hfHead(other, nine.bytes()));      // the file at the pinned revision is not the pinned one
            if (u.equals(two.mainUrl())) return new ModelServer.Result(0, hfHead(other, 3_200_000_000L));   // a newer file on main: said, not a failure
            return huggingFaceAsPinned(u);
        };
        var out = new ByteArrayOutputStream();
        assertEquals(1, ModelServer.check(new PrintStream(out, true)), "a pinned file that is not the one pinned stops the release");
        String o = out.toString();
        assertTrue(o.contains("DIFF 200  " + nine.url() + "  (Hugging Face gives sha256 " + other + ", " + nine.bytes() + " bytes; the row pins sha256 " + nine.sha256()), o);
        assertTrue(o.contains("note gemma-4-E2B-it-Q4_K_M.gguf on main is now sha256 " + other + " (3200000000 bytes); the row stays at 0314792d7f1f"), o);
        assertTrue(o.contains("1 changed") && !o.contains("missing"), o);

        // main moved on and nothing else: the gate passes, and says so
        ModelServer.runner = cmd -> { String u = cmd.get(cmd.size() - 1); return u.equals(two.mainUrl()) ? new ModelServer.Result(0, hfHead(other, 3_200_000_000L)) : huggingFaceAsPinned(u); };
        out.reset();
        assertEquals(0, ModelServer.check(new PrintStream(out, true)), out.toString());
        assertTrue(out.toString().contains("note gemma-4-E2B-it-Q4_K_M.gguf on main is now"), out.toString());

        // Hugging Face says nothing of the file's hash or size: not taken for a match
        ModelServer.runner = cmd -> new ModelServer.Result(0, "HTTP/2 200\n\n200");
        out.reset();
        assertEquals(1, ModelServer.check(new PrintStream(out, true)));
        assertTrue(out.toString().contains("Hugging Face gives no sha256, no size"), out.toString());
    }

    @Test
    void installFetchesTheFileAtItsRevisionAndRefusesOtherBytesBeforeTheyAreInPlace(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home"), realHf = System.getProperty("codezaiku.huggingface");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        List<String> asked = new ArrayList<>();
        HttpServer hf = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hf.createContext("/", x -> {   // Hugging Face as it was after 2026-11-11 at main: the same name, other bytes
            asked.add(x.getRequestURI().getPath());
            byte[] b = "the file re-made under the same name".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, b.length);
            try (var o = x.getResponseBody()) { o.write(b); }
        });
        hf.start();
        try {
            System.setProperty("codezaiku.huggingface", "http://127.0.0.1:" + hf.getAddress().getPort());
            fakeMachine(ModelServer.Os.linux, new boolean[]{false}, new ArrayList<>());
            // curl's part: fetch the address into the .part file and move it into place, as the real downloader does; nothing else is reached
            ModelServer.downloader = (url, dest) -> {
                if (!url.startsWith("http://127.0.0.1:")) throw new IOException("a test reaches no network: " + url);
                Path part = dest.resolveSibling(dest.getFileName() + ".part");
                HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofFile(part));
                Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
            };
            ModelServer.Row row = ModelServer.rowFor(16);
            String r = ModelServer.install(null, "all", 20, false, new PrintStream(new ByteArrayOutputStream(), true));
            assertEquals(List.of("/" + row.hf() + "/resolve/" + row.revision() + "/" + row.file()), asked, "the address names the row's revision");
            assertTrue(r.startsWith("!checksum mismatch for " + row.file() + "; refusing to install"), r);
            assertFalse(Files.exists(ModelServer.modelsDir().resolve(row.file())), "the refused file is not left in place");
            assertFalse(Files.exists(ModelServer.modelsDir().resolve(row.file() + ".part")));
            assertFalse(Files.exists(ModelServer.dir().resolve("config.yaml")), "nothing of the install was written");
        } finally {
            hf.stop(0);
            if (realHf == null) System.clearProperty("codezaiku.huggingface"); else System.setProperty("codezaiku.huggingface", realHf);
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }
}
