package org.codezaiku;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Where CodeZaiku finds the data it ships with.
 *
 * The knowledge library (framework cards, validated ops fix cards) lives in directories beside the
 * code, not inside the jar — they are edited, linted and contributed to as content. That means an
 * INSTALLED copy has to find them without the user knowing they exist. Before this, the ops fix cards
 * were simply off unless `CODEZAIKU_OPS_KNOWLEDGE` happened to be set, so an install shipped 76
 * validated cards that nothing ever loaded.
 *
 * Resolution order, first hit wins:
 *   1. the explicit environment variable (always wins — an operator override is never second-guessed)
 *   2. $CODEZAIKU_HOME/<dir>
 *   3. beside the installed distribution (../<dir> relative to the jar's lib/ directory)
 *   4. ./<dir> in the working directory (the repo checkout case)
 */
public final class Install {

    /** Directory of ops fix cards, or null when none is present. */
    public static String opsKnowledgeDir() {
        return resolve("CODEZAIKU_OPS_KNOWLEDGE", "ops-knowledge");
    }

    /** Directory of evergreen knowledge packs, or null when none is present. */
    public static String knowledgePacksDir() {
        return resolve("CODEZAIKU_KNOWLEDGE_PACKS", "knowledge-packs");
    }

    static String resolve(String envVar, String dirName) {
        String explicit = System.getenv(envVar);
        if (explicit != null && !explicit.isBlank()) return explicit;
        for (Path c : candidates(dirName)) {
            if (Files.isDirectory(c)) return c.toString();
        }
        return null;
    }

    private static List<Path> candidates(String dirName) {
        List<Path> out = new ArrayList<>();
        String home = Config.get("CODEZAIKU_HOME");
        if (home != null && !home.isBlank()) out.add(Paths.get(home, dirName));
        Path installRoot = installRoot();
        if (installRoot != null) {
            out.add(installRoot.resolve(dirName));
            out.add(installRoot.resolve("share").resolve(dirName));
        }
        out.add(Paths.get(dirName));
        return out;
    }

    /** The distribution root — the parent of the lib/ directory holding our jar. Null when running
     *  from classes rather than a packaged install (dev via gradle), where the cwd candidate covers us. */
    private static Path installRoot() {
        try {
            Path self = Paths.get(Install.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            if (!self.toString().endsWith(".jar")) return null;
            Path lib = self.getParent();                       // <root>/lib
            return lib == null ? null : lib.getParent();       // <root>
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    private Install() { }
}
