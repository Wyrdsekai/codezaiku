package org.codezaiku;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one compatibility shim left after the rename from CodePlane: the state directory.
 *
 * `~/.codezaiku`, or `~/.codeplane` if that is the directory that exists. It is a single string
 * literal deliberately saying the OLD name, sitting in a repository where every other mention was
 * swept to the new one — which makes a project-wide rename exactly the operation that destroys it.
 * That has already happened twice: once to the constants, once to the comments explaining them.
 *
 * The environment-variable fallback that used to live here has been removed. It existed for one
 * consumer, which has migrated.
 */
class LegacyNameCompatibilityTest {

    @Test
    void theCommentsExplainingTheShimsNameTheOldSpelling() throws Exception {
        String src = Files.readString(Path.of("src/main/java/org/codezaiku/Config.java"));
        int at = src.indexOf("public static Path home(");
        String doc = src.substring(Math.max(0, at - 700), at);
        assertTrue(doc.contains(".codeplane"),
                "home()'s javadoc must name the old directory it falls back to");
    }

    @Test
    void theStateDirectoryFallsBackToTheOldLocation() throws Exception {
        String src = Files.readString(Path.of("src/main/java/org/codezaiku/Config.java"));
        assertTrue(src.contains("\".codeplane\""),
                "an install predating the rename must keep its cards, caches and research memory");
    }
}
