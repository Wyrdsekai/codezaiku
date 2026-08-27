package org.codezaiku;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The usage text is the authoritative list of what CodeZaiku accepts — CLAUDE.md says so and the
 * README is written from it. Nothing kept it honest: `code|loop` was advertised for a long time
 * while the dispatch only ever matched "loop", so the first command in the README's coding section
 * exited 2 with "unknown command 'code'".
 *
 * This reads the source rather than driving main(), because every dispatch arm ends in System.exit
 * and the drive would have to be reachable. A divergence is a text-vs-text mismatch, so text is the
 * right thing to compare.
 */
class UsageMatchesDispatchTest {

    private static final Path SRC = Path.of("src/main/java/org/codezaiku/FamiliarMain.java");

    /** Verbs the help advertises: the leading token(s) of each indented command line, `a|b` split. */
    private static Set<String> advertised(String usage) {
        Set<String> verbs = new LinkedHashSet<>();
        for (String line : usage.split("\n")) {
            // Two lines sit at command indent without being commands: the `codezaiku <command>`
            // synopsis under USAGE, and the `ceiling = observe | ...` legend. Legends are `x = ...`.
            if (line.matches("^ {14}[a-z|-]+ =.*")) continue;
            Matcher m = Pattern.compile("^ {14}([a-z][a-z|-]*)(?:\\s|$)").matcher(line);
            if (m.find()) for (String v : m.group(1).split("\\|")) if (!v.equals("codezaiku")) verbs.add(v);
        }
        return verbs;
    }

    @Test
    void everyVerbTheHelpAdvertisesIsActuallyDispatched() throws Exception {
        String src = Files.readString(SRC);

        int open = src.indexOf("private static void usage()");
        assertTrue(open > 0, "usage() not found — this test tracks its source location");
        int close = src.indexOf("\"\"\".formatted", open);
        assertTrue(close > open, "usage() text block terminator not found");
        String usage = src.substring(open, close);

        Set<String> verbs = advertised(usage);
        assertTrue(verbs.size() >= 10, "parsed too few verbs (" + verbs + ") — the usage layout moved");

        String dispatch = src.substring(0, open);
        for (String verb : verbs) {
            assertTrue(dispatch.contains("args[0].equals(\"" + verb + "\")"),
                    "usage advertises `" + verb + "` but no dispatch arm accepts it");
        }
    }
}
