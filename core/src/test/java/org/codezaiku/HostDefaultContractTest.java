package org.codezaiku;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The precedence a HOST relies on, exercised through the real CLI rather than the resolver alone.
 *
 * An environment variable cannot say whether it means "the operator chose this" or "I am filling in
 * a blank", so a host that sends its own built-in default as CODEZAIKU_DRIVE silently redirects a
 * machine that WAS configured — invisibly, because the config file still reads correctly and
 * `doctor` reports healthy while the run goes elsewhere. `<KEY>_DEFAULT` is the channel for offering
 * rather than imposing, and another product now depends on that meaning holding.
 */
class HostDefaultContractTest {

    @TempDir Path tmp;

    private static Path cli() {
        return Path.of("build/install/codezaiku/bin/codezaiku");
    }

    private String configList(Path cfg, Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cli().toAbsolutePath().toString(), "config", "list");
        pb.environment().put("CODEZAIKU_CONFIG", cfg.toString());
        // A stale value from the developer's own shell would decide the outcome instead of the
        // test. Both prefixes have to go: the resolver falls back to the pre-rename CODEZAIKU_*
        // spelling, so clearing only the new one leaves a wider hole than existed before.
        for (String p : new String[]{"CODEZAIKU_", "CODEZAIKU_"}) {
            for (String k : new String[]{"DRIVE", "MODEL", "DRIVE_DEFAULT", "MODEL_DEFAULT"}) {
                pb.environment().remove(p + k);
            }
        }
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }

    private String driveLine(String out) {
        return out.lines().filter(l -> l.strip().startsWith("drive ")).findFirst().orElse("");
    }

    @Test
    void aConfiguredMachineKeepsItsOwnSettingsWhenAHostOffersADefault() throws Exception {
        assumeTrue(Files.isExecutable(cli()), "needs :core:installDist");
        Path cfg = tmp.resolve("cfg");
        Files.writeString(cfg, "CODEZAIKU_DRIVE=https://configured.example.com\n");

        String line = driveLine(configList(cfg,
                Map.of("CODEZAIKU_DRIVE_DEFAULT", "http://localhost:8200")));

        assertTrue(line.contains("https://configured.example.com"),
                "a host default must not redirect a configured machine — got: " + line);
        assertTrue(line.contains("[config file]"), "and the listing must say where it came from: " + line);
    }

    @Test
    void anUnconfiguredMachineTakesTheHostsDefault() throws Exception {
        assumeTrue(Files.isExecutable(cli()), "needs :core:installDist");
        Path cfg = tmp.resolve("empty");
        Files.writeString(cfg, "");

        String line = driveLine(configList(cfg,
                Map.of("CODEZAIKU_DRIVE_DEFAULT", "http://localhost:8200")));

        assertTrue(line.contains("http://localhost:8200"), "the blank should have been filled: " + line);
        assertTrue(line.contains("[host default]"), "and named as the host's, not the machine's: " + line);
    }

    @Test
    void anExplicitHostSettingStillWins() throws Exception {
        assumeTrue(Files.isExecutable(cli()), "needs :core:installDist");
        Path cfg = tmp.resolve("cfg2");
        Files.writeString(cfg, "CODEZAIKU_DRIVE=https://configured.example.com\n");

        String line = driveLine(configList(cfg, Map.of(
                "CODEZAIKU_DRIVE", "https://host-chose.example.com",
                "CODEZAIKU_DRIVE_DEFAULT", "http://localhost:8200")));

        assertTrue(line.contains("https://host-chose.example.com"),
                "a host that means to impose must still be able to: " + line);
        assertTrue(line.contains("[environment]"), line);
    }

    @Test
    void presentButBlankCountsAsUnsetAtEveryLevel() throws Exception {
        assumeTrue(Files.isExecutable(cli()), "needs :core:installDist");
        Path cfg = tmp.resolve("cfg3");
        Files.writeString(cfg, "CODEZAIKU_DRIVE=https://configured.example.com\n");

        String line = driveLine(configList(cfg, Map.of(
                "CODEZAIKU_DRIVE", "", "CODEZAIKU_DRIVE_DEFAULT", "")));

        assertTrue(line.contains("https://configured.example.com"),
                "an empty variable must not blank out a real setting: " + line);
    }
}
