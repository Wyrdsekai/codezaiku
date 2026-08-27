package org.codezaiku;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every `codezaiku ...` line in the README must be a command the dispatch actually accepts —
 * verb AND arity. `codezaiku code ...` was the README's first coding example for a long time
 * while no arm matched "code", so it exited 2. Arity matters just as much: each arm guards on
 * `args.length >= N`, so an example short of N falls through to "unknown command" too.
 *
 * Static on purpose. The alternative is running them, and the README documents a daemon that
 * defaults to `guarded` — the test suite must not repair the machine it runs on.
 */
class ReadmeCommandsDispatchTest {

    private static final Path SRC = Path.of("src/main/java/org/codezaiku/FamiliarMain.java");
    /** Resolved rather than hardcoded: the export promotes README to the repository root. */
    private static Path readme() {
        return org.codezaiku.testsupport.PublicDocs.page("README.md")
                .orElseThrow(() -> new IllegalStateException("README.md not found in either layout"));
    }

    /** verb -> the smallest args.length any arm accepting that verb requires. */
    private static Map<String, Integer> dispatchArity(String src) {
        Map<String, Integer> arity = new HashMap<>();
        Matcher arm = Pattern.compile("args\\.length >= (\\d+) && \\(?((?:args\\[0\\]\\.equals\\(\"[a-z-]+\"\\)(?:\\s*\\|\\|\\s*)?)+)")
                .matcher(src);
        while (arm.find()) {
            int n = Integer.parseInt(arm.group(1));
            Matcher v = Pattern.compile("equals\\(\"([a-z-]+)\"\\)").matcher(arm.group(2));
            while (v.find()) arity.merge(v.group(1), n, Math::min);
        }
        return arity;
    }

    /** The `codezaiku ...` lines inside fenced blocks, comments stripped, quotes respected. */
    private static List<String[]> readmeCommands(String md) {
        List<String[]> out = new ArrayList<>();
        for (String raw : md.split("\n")) {
            String line = raw.strip();
            if (!line.startsWith("codezaiku ")) continue;
            line = line.replaceAll("\\s+#.*$", "").strip();
            List<String> toks = new ArrayList<>();
            Matcher t = Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(line);
            while (t.find()) toks.add(t.group(1) != null ? t.group(1) : t.group(2));
            out.add(toks.toArray(new String[0]));
        }
        return out;
    }

    @Test
    void everyReadmeExampleDispatches() throws Exception {
        Map<String, Integer> arity = dispatchArity(Files.readString(SRC));
        List<String[]> cmds = readmeCommands(Files.readString(readme()));

        assertTrue(cmds.size() >= 15, "parsed only " + cmds.size() + " README commands — layout moved");
        assertTrue(arity.containsKey("code"), "dispatch arity table looks wrong — no `code` arm found");

        for (String[] cmd : cmds) {
            String verb = cmd[1];
            // `help` and the flag forms are handled after the arm chain, not by an arm.
            if (verb.equals("help") || verb.startsWith("-")) continue;
            Integer need = arity.get(verb);
            assertTrue(need != null,
                    "README documents `codezaiku " + verb + "` but no dispatch arm accepts it");
            int got = cmd.length - 1;   // args[] excludes the program name
            assertTrue(got >= need,
                    "README example `" + String.join(" ", cmd) + "` passes " + got
                            + " arg(s) but the `" + verb + "` arm needs args.length >= " + need);
        }
    }
}
