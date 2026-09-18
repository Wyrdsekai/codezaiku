package org.codezaiku.shape;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `codezaiku chat` started in a home directory walked the whole home (a 210 GB .cache among it) to guess the
 * project language and sat on its banner for minutes. The walk now skips hidden directories and stops at a cap.
 */
class TreeWalkBoundedTest {

    static void tree(Path home) throws Exception {
        Files.writeString(home.resolve("notes.py"), "print(1)\n");
        Files.writeString(home.resolve("tool.py"), "print(2)\n");
        Path cache = Files.createDirectories(home.resolve(".cache").resolve("pip").resolve("http"));
        for (int i = 0; i < 3000; i++) Files.writeString(cache.resolve("blob" + i + ".js"), "");   // js, so an unbounded vote would say javascript
        Path snap = Files.createDirectories(home.resolve("snap").resolve("firefox").resolve("common"));
        for (int i = 0; i < 500; i++) Files.writeString(snap.resolve("f" + i + ".js"), "");
        Path big = Files.createDirectories(home.resolve("data").resolve("dump"));
        for (int i = 0; i < 2000; i++) Files.writeString(big.resolve("row" + i + ".txt"), "");
    }

    @Test
    void hiddenDirectoriesAndSnapAreNeverEnteredAndTheLanguageVoteIsNotSwayedByThem(@TempDir Path home) throws Exception {
        tree(home);
        long t0 = System.nanoTime();
        var entries = TreeWalk.entries(home, Set.of());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(entries.stream().noneMatch(p -> p.toString().contains(".cache") || p.toString().contains("/snap/")), "hidden and snap skipped");
        assertEquals("python", ProjectFacts.language(home), "the two .py files decide, not the 3,500 cached .js files");
        assertTrue(ms < 5000, "walked in " + ms + " ms");
    }

    @Test
    void theWalkStopsAtTheCap(@TempDir Path home) throws Exception {
        tree(home);
        assertEquals(100, TreeWalk.entries(home, Set.of(), 100).size());
        assertTrue(TreeWalk.files(home, Set.of(), 50).size() <= 50);
        assertTrue(TreeWalk.entries(home, Set.of()).size() < TreeWalk.MAX_ENTRIES);
    }
}
