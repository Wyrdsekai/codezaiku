package org.codezaiku.shape;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The project-shape block is rebuilt every turn and compaction never touches it, so on a large
 * repository it consumed the window before the task was read: measured at ~31,900 characters on a
 * 926-file tree, against a 16k-token window. A host reported that the loop could not land a single
 * call in its real working tree, whatever the task was.
 *
 * The entry cap is not a bound the window can rely on — it counts entries, while a deep tree pays
 * for path length too. This budgets the block in characters instead.
 */
class ProjectShapeBudgetTest {

    @TempDir Path repo;

    private static final int BUDGET_AT_16K = 16384 / 5 * 3;   // what the loop allows at n_ctx=16384

    private void bigTree() throws Exception {
        for (int i = 0; i < 300; i++) {
            Path d = repo.resolve("src/main/java/org/example/deeply/nested/package" + i);
            Files.createDirectories(d);
            Files.writeString(d.resolve("SomeClassWithALongName" + i + ".java"),
                    "package org.example;\npublic class SomeClassWithALongName" + i + " {\n"
                            + "  public void doSomething() { }\n}\n");
        }
    }

    @Test
    void anUnbudgetedRenderIsTooBigForASmallWindow() throws Exception {
        bigTree();
        String full = ProjectShape.render(repo);
        assertTrue(full.length() > BUDGET_AT_16K,
                "if this tree no longer overflows a 16k window the test has stopped exercising the "
                        + "bug; got " + full.length() + " chars");
    }

    @Test
    void aBudgetedRenderFitsAndSaysItWasShortened() throws Exception {
        bigTree();
        String fitted = ProjectShape.render(repo, BUDGET_AT_16K);
        assertTrue(fitted.length() <= BUDGET_AT_16K,
                "the block must fit the budget it was given: " + fitted.length() + " > " + BUDGET_AT_16K);
        assertTrue(fitted.contains("shortened to fit"),
                "a truncated block that looks whole is worse than a short one — say so");
    }

    @Test
    void whatSurvivesIsWhatOrientsTheModel() throws Exception {
        bigTree();
        String fitted = ProjectShape.render(repo, BUDGET_AT_16K);
        assertTrue(fitted.contains("PROJECT SHAPE") || fitted.contains("PROJECT FILES"),
                "the header and build facts are what place a file at all; they must survive the trim");
    }

    @Test
    void aProjectThatAlreadyFitsIsUntouched() throws Exception {
        Files.writeString(repo.resolve("main.py"), "def f():\n    return 1\n");
        assertEquals(ProjectShape.render(repo), ProjectShape.render(repo, BUDGET_AT_16K),
                "a block that already fits must pass through unchanged");
    }

    @Test
    void trimmingNeverCutsMidPath() {
        String block = "PROJECT FILES:\nsrc/a/very/long/path/File.java  (1K)\nsrc/b/another/Path.java  (2K)\n";
        String fitted = ProjectShape.fitTo(block, 60);
        String body = fitted.substring(0, fitted.indexOf("...["));
        assertFalse(body.contains("File.jav\n") || body.endsWith("File.jav"),
                "a half-written path would be read as a real filename: " + fitted);
    }
}
