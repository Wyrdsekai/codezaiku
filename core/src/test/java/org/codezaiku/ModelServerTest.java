package org.codezaiku;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The model server on demand, on all three platforms: the row for the memory, the files an install writes, and the install
 * itself with every seam faked — the platform included, so a Linux host exercises the macOS and Windows paths too.
 */
class ModelServerTest {
    final ModelServer.Os realOs = ModelServer.os;
    final ModelServer.Runner realRunner = ModelServer.runner;
    final ModelServer.Downloader realDownloader = ModelServer.downloader;
    final java.util.function.Predicate<String> realHealth = ModelServer.health;
    final ModelServer.Detacher realDetach = ModelServer.detach;
    final java.util.Map<String, String> realSums = new java.util.HashMap<>(ModelServer.sums);
    @AfterEach void restore() { ModelServer.sums.clear(); ModelServer.sums.putAll(realSums); ModelServer.os = realOs; ModelServer.runner = realRunner; ModelServer.downloader = realDownloader; ModelServer.health = realHealth; ModelServer.detach = realDetach; }

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
            if (c.equals("nvidia-smi")) return new ModelServer.Result(os == ModelServer.Os.linux ? 0 : 1, "16380\n");
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
        org.codezaiku.Config.invalidate();
        boolean[] started = {false}; List<String> fetched = new ArrayList<>();
        try {
            List<List<String>> ran = fakeMachine(ModelServer.Os.linux, started, fetched);
            org.codezaiku.Config.set("CODEZAIKU_DRIVE", "http://elsewhere:8211");   // a drive the person had set, to be put back on uninstall
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
            assertEquals("http://127.0.0.1:8211", org.codezaiku.Config.get("CODEZAIKU_DRIVE"));
            assertEquals("gpt-oss-20b", org.codezaiku.Config.get("CODEZAIKU_MODEL"));
            ModelServer.health = base -> false;
            String u = ModelServer.uninstall();
            assertTrue(u.contains("the drive is back to http://elsewhere:8211"), u);
            assertEquals("http://elsewhere:8211", org.codezaiku.Config.get("CODEZAIKU_DRIVE"));
            assertFalse(Files.exists(ModelServer.dir()));
            ModelServer.health = base -> true;
            assertEquals("local-model", ModelServer.install(null, "all", 20, false, new PrintStream(new ByteArrayOutputStream())));
            assertTrue(ModelServer.offer().startsWith("A model proxy already answers"));
        } finally {
            System.setProperty("user.home", realHome);
            org.codezaiku.Config.invalidate();
        }
    }

    @Test
    void onMacOsInstallFetchesTheMetalBuildSizesByUnifiedMemoryAndLoadsALaunchAgent(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        org.codezaiku.Config.invalidate();
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
            assertEquals("http://127.0.0.1:8211", org.codezaiku.Config.get("CODEZAIKU_DRIVE"));
        } finally {
            System.setProperty("user.home", realHome);
            org.codezaiku.Config.invalidate();
        }
    }

    @Test
    void onWindowsInstallFetchesTheVulkanBuildSizesByRamWithoutAnNvidiaCardAndRegistersALogonTask(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        org.codezaiku.Config.invalidate();
        boolean[] started = {false}; List<String> fetched = new ArrayList<>();
        try {
            List<List<String>> ran = fakeMachine(ModelServer.Os.windows, started, fetched);
            org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.arch", "").contains("64") && !System.getProperty("os.arch", "").contains("aarch"), "the Windows path is x64");
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
            assertEquals("http://127.0.0.1:8211", org.codezaiku.Config.get("CODEZAIKU_DRIVE"));
        } finally {
            System.setProperty("user.home", realHome);
            org.codezaiku.Config.invalidate();
        }
    }

    @Test
    void withoutDockerOnLinuxItSaysWhy() {
        ModelServer.os = ModelServer.Os.linux;
        ModelServer.health = base -> false;
        ModelServer.runner = cmd -> { throw new java.io.IOException("docker: not found"); };
        assertTrue(ModelServer.unsupported().contains("docker"), ModelServer.unsupported());
        assertNull(ModelServer.offer());
        ModelServer.runner = cmd -> cmd.get(0).equals("docker") ? new ModelServer.Result(0, "map[runc:{...}]") : new ModelServer.Result(0, "16380");
        assertTrue(ModelServer.unsupported().contains("NVIDIA runtime"), ModelServer.unsupported());
    }

    @Test
    void aDownloadThatDoesNotMatchItsRecordedHashIsRefusedAndDeleted(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        org.codezaiku.Config.invalidate();
        boolean[] started = {false}; List<String> fetched = new ArrayList<>();
        try {
            fakeMachine(ModelServer.Os.linux, started, fetched);
            ModelServer.downloader = (url, dest) -> { fetched.add(url); Files.createDirectories(dest.getParent()); Files.writeString(dest, "tampered"); };   // the recorded hash stays
            String r = ModelServer.install(null, "all", 20, false, new PrintStream(new ByteArrayOutputStream()));
            assertTrue(r.startsWith("!checksum mismatch for gpt-oss-20b-F16.gguf"), r);
            assertFalse(Files.exists(home.resolve("models/gpt-oss-20b-F16.gguf")), "nothing kept");
            assertNull(org.codezaiku.Config.get("CODEZAIKU_DRIVE"), "the drive was not touched");
        } finally {
            System.setProperty("user.home", realHome);
            org.codezaiku.Config.invalidate();
        }
    }

    @Test
    void uninstallRefusesWhileTheOtherProductStillPointsAtTheProxy(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        org.codezaiku.Config.invalidate();
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
            org.codezaiku.Config.invalidate();
        }
    }

    @Test
    void checkReadsEveryRowAndPinnedBuildAndNamesWhatIsGone() {
        ModelServer.os = ModelServer.Os.linux;
        ModelServer.runner = cmd -> new ModelServer.Result(0, cmd.get(cmd.size() - 1).contains("gpt-oss") ? "404" : "200");
        var out = new ByteArrayOutputStream();
        assertEquals(1, ModelServer.check(new PrintStream(out, true)));
        String o = out.toString();
        assertTrue(o.contains("GONE 404  https://huggingface.co/unsloth/gpt-oss-20b-GGUF/resolve/main/gpt-oss-20b-F16.gguf"), o);
        assertTrue(o.contains("ok   200  https://huggingface.co/unsloth/Qwen3.8-27B-GGUF/resolve/main/Qwen3.8-27B-UD-Q4_K_M.gguf"), o);
        assertTrue(o.contains("llama-swap_255_windows_amd64.zip") && o.contains("llama-b10929-bin-win-vulkan-x64.zip"), o);
        assertTrue(o.contains("1 missing"), o);
    }

    @Test
    void checkReadsARateLimitAsUncheckedNotGone() {
        ModelServer.os = ModelServer.Os.linux;
        ModelServer.checkBackoffMs = 0;
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        ModelServer.runner = cmd -> { String u = cmd.get(cmd.size() - 1); if (u.contains("gemma-4-E2B")) calls.incrementAndGet(); return new ModelServer.Result(0, u.contains("gemma-4-E2B") ? "429" : "200"); };
        var out = new ByteArrayOutputStream();
        assertEquals(0, ModelServer.check(new PrintStream(out, true)), "a 429 is not a missing file: the release gate does not fail on it");
        String o = out.toString();
        assertEquals(3, calls.get(), "asked three times before giving up on the host");
        assertTrue(o.contains("WAIT 429  https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf"), o);
        assertTrue(o.contains("1 not checked") && !o.contains("GONE") && !o.contains(" missing:"), o);
    }
}
