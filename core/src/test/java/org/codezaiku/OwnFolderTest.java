package org.codezaiku;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Each program in a folder of its own (ResearchZosho 0.8.3): CodeZaiku's uninstall removes only the files its install made, its status
 * says what of another program its folder holds and leaves it, it says when a ResearchZosho from before 0.8.3 still keeps its files in
 * CodeZaiku's folder, and it never writes into ResearchZosho's. Every home is a test's own; systemctl and Docker are stand-ins.
 */
class OwnFolderTest {
    @TempDir Path home;
    final String realHome = System.getProperty("user.home");
    final ModelServer.Os realOs = ModelServer.os;
    final ModelServer.Runner realRunner = ModelServer.runner;
    final Predicate<String> realHealth = ModelServer.health;
    final Function<String, String> realLoaded = ModelServer.loaded;
    final List<List<String>> ran = new ArrayList<>();

    @BeforeEach void own() {
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        ModelServer.os = ModelServer.Os.linux;
        ModelServer.runner = cmd -> { ran.add(cmd); return new ModelServer.Result(0, ""); };
        ModelServer.health = base -> false;
        ModelServer.loaded = base -> "";
    }

    @AfterEach void back() {
        ModelServer.os = realOs; ModelServer.runner = realRunner; ModelServer.health = realHealth; ModelServer.loaded = realLoaded;
        System.setProperty("user.home", realHome);
        Config.invalidate();
    }

    Path cz() { return home.resolve(".codezaiku"); }
    Path rz() { return home.resolve(".researchzosho"); }
    static void put(Path f, String t) throws IOException { Files.createDirectories(f.getParent()); Files.writeString(f, t); }

    static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> out = new TreeMap<>();
        if (!Files.exists(root)) return out;
        try (Stream<Path> w = Files.walk(root)) {
            for (Path p : w.toList()) out.put(root.relativize(p).toString(), Files.isDirectory(p) ? "dir" : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
        }
        return out;
    }

    /** CodeZaiku's install, with ResearchZosho's embeddings entry and start script added to its folder, as a shared home had them. */
    void czInstallWithTheirs() throws IOException {
        Path m = cz().resolve("model");
        put(m.resolve("run.sh"), "#!/bin/sh\nexec docker run --rm --name codezaiku-model image\n");
        put(m.resolve("plan.properties"), "name=q\n");
        put(m.resolve("previous"), "\n\n");
        put(m.resolve("llama-swap"), "binary");
        put(m.resolve("LICENSE.md"), "license");
        put(m.resolve("config.yaml"), "models:\n  \"q\":\n    cmd: " + m.resolve("run.sh") + " ${PORT}\n  \"embed\":\n    cmd: " + m.resolve("run-embed.sh") + " ${PORT}\n");
        put(m.resolve("run-embed.sh"), "#!/bin/sh\nexec docker run --rm --name researchzosho-model-embed -p 127.0.0.1:$1:80 image\n");
    }

    @Test
    void uninstallRemovesOnlyItsOwnFilesAndLeavesTheFolderWhenAnotherProgramsFileIsInIt() throws Exception {
        czInstallWithTheirs();
        String r = ModelServer.uninstall(true);
        assertTrue(r.contains("the folder " + cz().resolve("model") + " stays, since it holds what another program made (run-embed.sh)"), r);
        try (Stream<Path> in = Files.list(cz().resolve("model"))) { assertEquals(List.of("run-embed.sh"), in.map(p -> p.getFileName().toString()).toList()); }
    }

    @Test
    void uninstallRemovesTheFolderWhenOnlyItsOwnFilesWereInIt() throws Exception {
        Path m = cz().resolve("model");
        put(m.resolve("run.sh"), "x"); put(m.resolve("config.yaml"), "models:\n"); put(m.resolve("llama").resolve("llama-server"), "x");
        put(m.resolve("llama-swap_" + ModelServer.SWAP_VERSION + "_linux_amd64.tar.gz.part"), "x");
        String r = ModelServer.uninstall(true);
        assertFalse(r.contains("stays, since"), r);
        assertFalse(Files.exists(m));
    }

    @Test
    void statusSaysWhatOfAnotherProgramItsFolderHoldsAndThatItLeavesIt() throws Exception {
        czInstallWithTheirs();
        String s = ModelServer.status();
        assertTrue(s.contains("  another program's, in this folder, left as it is: run-embed.sh; its entry embed in config.yaml\n"), s);
    }

    @Test
    void itSaysWhenAResearchZoshoFromBefore083StillKeepsItsFilesInItsFolder() throws Exception {
        put(cz().resolve("config"), "drive = http://127.0.0.1:8211/v1\n");
        assertEquals("", ResearchZoshoInstall.livesInOurFolderSaid(), "CodeZaiku alone");
        put(cz().resolve("researchzosho.pid"), "4242\n");
        String said = ResearchZoshoInstall.livesInOurFolderSaid();
        assertTrue(said.startsWith("ResearchZosho keeps its files in CodeZaiku's folder (" + cz() + "), as its versions before 0.8.3 did. From 0.8.3 it moves them"), said);
        assertTrue(ModelServer.status().contains(said), "model serve status says it");
        Files.createDirectories(rz());
        assertEquals("", ResearchZoshoInstall.livesInOurFolderSaid(), "it has its own folder: whatever is left in CodeZaiku's is not read by it");
    }

    @Test
    void uninstallRefusesWhileResearchZoshosOwnSettingsPointAtTheProxyWithV1OrNot() throws Exception {
        czInstallWithTheirs();
        put(rz().resolve("config"), "# ResearchZosho's settings\ndrive = http://127.0.0.1:8211/v1\n");
        Map<String, String> before = snapshot(rz());
        String r = ModelServer.uninstall(false);
        assertTrue(r.startsWith("!ResearchZosho's settings also point at this proxy (" + rz().resolve("config") + ")"), r);
        assertTrue(Files.exists(cz().resolve("model").resolve("run.sh")), "nothing removed");
        assertEquals(before, snapshot(rz()), "its settings read, never written");
        // a ResearchZosho from before 0.8.3 in CodeZaiku's folder reads the shared file: the same refusal
        Files.walk(rz()).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        put(cz().resolve("researchzosho.pid"), "4242\n");
        put(cz().resolve("config"), "drive = http://127.0.0.1:8200/v1\nlibrarian.drive = http://127.0.0.1:8211/v1\n");
        Config.invalidate();
        assertTrue(ModelServer.uninstall(false).startsWith("!ResearchZosho's settings also point at this proxy"), "librarian.drive is the address it reads there");
    }

    @Test
    void neitherStatusNorUninstallNorDoctorsLineWritesIntoResearchZoshosFolder() throws Exception {
        czInstallWithTheirs();
        put(rz().resolve("config"), "drive = http://127.0.0.1:8200/v1\n");
        put(rz().resolve("logs").resolve("librarian-serve.log"), "x\n");
        Map<String, String> before = snapshot(rz());
        ModelServer.status();
        ResearchZoshoInstall.livesInOurFolderSaid();
        ModelServer.uninstall(false);
        assertEquals(before, snapshot(rz()));
    }
}
