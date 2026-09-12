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

/** The model server on demand: the row for a card, the files an install writes, and the install itself with every seam faked. */
class ModelServerTest {
    final ModelServer.Runner realRunner = ModelServer.runner;
    final ModelServer.Downloader realDownloader = ModelServer.downloader;
    final java.util.function.Predicate<String> realHealth = ModelServer.health;
    @AfterEach void restore() { ModelServer.runner = realRunner; ModelServer.downloader = realDownloader; ModelServer.health = realHealth; }

    @Test
    void theRowFollowsTheCard() {
        assertEquals("qwen3.8-27b", ModelServer.rowFor(48).name());
        assertEquals("gpt-oss-20b", ModelServer.rowFor(16).name());
        assertEquals("qwen3.5-9b", ModelServer.rowFor(12).name());
        assertEquals("gemma-4-e2b", ModelServer.rowFor(2).name());
        assertNull(ModelServer.rowFor(1));
    }

    @Test
    void thePlanNamesTheModelTheFlagsTheIdleTimeAndTheAliasesProgramsSend(@TempDir Path tmp) {
        var p = ModelServer.plan(ModelServer.rowFor(16), tmp.resolve("models/gpt-oss-20b-F16.gguf"), tmp.resolve("model"), tmp.resolve("unit.service"), "all", 20, false);
        assertTrue(p.configYaml().contains("\"gpt-oss-20b\":") && p.configYaml().contains("ttl: 1200") && p.configYaml().contains("\"local-model\""), p.configYaml());
        assertTrue(p.configYaml().contains("cmdStop: docker stop codezaiku-model"), p.configYaml());
        assertTrue(p.runScript().contains("--gpus all") && p.runScript().contains("-m /m/gpt-oss-20b-F16.gguf") && p.runScript().contains("-c 32768 --parallel 2") && p.runScript().contains("reasoning_effort"), p.runScript());
        assertTrue(p.unitText().contains("--listen 127.0.0.1:8211"), p.unitText());
        var shared = ModelServer.plan(ModelServer.rowFor(48), tmp.resolve("m.gguf"), tmp.resolve("model"), tmp.resolve("u"), "3", 45, true);
        assertTrue(shared.runScript().contains("--gpus '\"device=3\"'") && shared.configYaml().contains("ttl: 2700") && shared.unitText().contains("--listen 0.0.0.0:8211"), shared.runScript());
    }

    @Test
    void installWritesTheFilesStartsTheServiceAndPointsTheDriveAtTheProxy(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        org.codezaiku.Config.invalidate();
        List<List<String>> ran = new ArrayList<>(); List<String> fetched = new ArrayList<>();
        boolean[] started = {false};   // the proxy answers only once the service has been started
        try {
            ModelServer.runner = cmd -> {
                ran.add(cmd);
                if (cmd.get(0).equals("docker")) return new ModelServer.Result(0, "map[nvidia:{...} runc:{...}]");
                if (cmd.get(0).equals("nvidia-smi")) return new ModelServer.Result(0, "16380\n");
                if (cmd.get(0).equals("curl")) return new ModelServer.Result(0, "HTTP/2 200\ncontent-length: 13780000000\n");
                if (cmd.get(0).equals("tar")) { Files.writeString(Path.of(cmd.get(4)).resolve("llama-swap"), "#!/bin/sh\n"); return new ModelServer.Result(0, ""); }
                if (cmd.contains("enable")) started[0] = true;
                return new ModelServer.Result(0, "");
            };
            ModelServer.downloader = (url, dest) -> { fetched.add(url); Files.createDirectories(dest.getParent()); Files.writeString(dest, "x"); };
            ModelServer.health = base -> started[0];
            org.codezaiku.Config.set("CODEZAIKU_DRIVE", "http://elsewhere:8211");   // a drive the person had set, to be put back on uninstall
            assertTrue(ModelServer.offer().startsWith("Serve gpt-oss-20b on this machine on demand: it downloads once (about 14 GB)"), ModelServer.offer());
            var out = new ByteArrayOutputStream();
            String r = ModelServer.install(null, "all", 20, false, new PrintStream(out, true));
            assertEquals("gpt-oss-20b", r, out.toString());
            assertTrue(fetched.get(0).endsWith("/gpt-oss-20b-F16.gguf") && fetched.get(1).contains("llama-swap_255_linux_"), fetched.toString());
            assertTrue(Files.exists(home.resolve("models/gpt-oss-20b-F16.gguf")));
            Path dir = ModelServer.dir();
            assertTrue(Files.readString(dir.resolve("config.yaml")).contains("\"gpt-oss-20b\":"));
            assertTrue(Files.isExecutable(dir.resolve("run.sh")));
            assertTrue(Files.readString(home.resolve(".config/systemd/user/codezaiku-model.service")).contains("--listen 127.0.0.1:8211"));
            assertTrue(ran.stream().anyMatch(c -> c.equals(List.of("systemctl", "--user", "enable", "--now", "codezaiku-model"))), ran.toString());
            assertEquals("http://127.0.0.1:8211", org.codezaiku.Config.get("CODEZAIKU_DRIVE"));
            assertEquals("gpt-oss-20b", org.codezaiku.Config.get("CODEZAIKU_MODEL"));
            // uninstall puts the drive back to what it was before the install
            ModelServer.health = base -> false;
            String u = ModelServer.uninstall();
            assertTrue(u.contains("the drive is back to http://elsewhere:8211"), u);
            assertEquals("http://elsewhere:8211", org.codezaiku.Config.get("CODEZAIKU_DRIVE"));
            assertFalse(Files.exists(ModelServer.dir()));
            // a second install finds the proxy and only points the drive at it
            ModelServer.health = base -> true;
            assertEquals("local-model", ModelServer.install(null, "all", 20, false, new PrintStream(new ByteArrayOutputStream())));
            assertTrue(ModelServer.offer().startsWith("A model proxy already answers"));
        } finally {
            System.setProperty("user.home", realHome);
            org.codezaiku.Config.invalidate();
        }
    }

    @Test
    void withoutDockerOrACardItSaysWhy() {
        ModelServer.health = base -> false;
        ModelServer.runner = cmd -> { throw new java.io.IOException("docker: not found"); };
        String why = ModelServer.unsupported();
        assertTrue(why == null || why.contains("docker") || why.contains("Linux"), why);
        ModelServer.runner = cmd -> cmd.get(0).equals("docker") ? new ModelServer.Result(0, "map[runc:{...}]") : new ModelServer.Result(0, "16380");
        why = ModelServer.unsupported();
        assertTrue(why == null || why.contains("NVIDIA runtime") || why.contains("Linux"), why);
    }
}
