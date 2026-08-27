package org.codezaiku.gate;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bundled gates ship in the public release, so their names are published whether or not anyone
 * thinks about it. One was named after a personal side project for months; it was renamed to say
 * what it verifies (`godot-gdscript`) rather than which project it came from.
 *
 * The name/filename check is the load-bearing half: GateLoader resolves `/gates/<name>.json`, so a
 * rename that misses the `name` field leaves a gate that only resolves under its old, gone name.
 */
class BundledGatesTest {

    private static final Path GATES = Path.of("src/main/resources/gates");

    private static List<Path> gates() throws Exception {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(GATES)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(out::add);
        }
        return out;
    }

    @Test
    void everyGateDeclaresTheNameItIsResolvedBy() throws Exception {
        List<Path> gates = gates();
        assertTrue(gates.size() >= 4, "found only " + gates.size() + " bundled gates — layout moved");

        for (Path g : gates) {
            String file = g.getFileName().toString().replace(".json", "");
            String body = Files.readString(g);
            assertTrue(body.contains("\"name\": \"" + file + "\""),
                    g + " declares a name that is not `" + file + "` — GateLoader resolves gates by "
                            + "filename, so the two must agree");
        }
    }

    @Test
    void everyBundledGateActuallyLoads() throws Exception {
        for (Path g : gates()) {
            String name = g.getFileName().toString().replace(".json", "");
            try (InputStream in = GateLoader.class.getResourceAsStream("/gates/" + name + ".json")) {
                assertNotNull(in, "gate `" + name + "` is on disk but not on the classpath");
            }
            assertNotNull(GateLoader.gate(name), "gate `" + name + "` failed to load");
        }
    }
}
