package org.codezaiku;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two things that are invisible until someone tries to use the release.
 *
 * Placeholders: `--repo <owner>/codezaiku` is a fine thing to write while the repository does not
 * exist yet and a terrible thing to ship — the reader copies it verbatim and gets an error that
 * looks like their fault. Two invented orgs had also reached shipped artifacts, the .deb Homepage
 * field and the SARIF informationUri in every report.
 *
 * Casing: `gh attestation verify` matches the signing identity exactly, so a mis-cased owner does
 * not merely 404 — it makes verification reject a genuine artifact.
 */
class PublicDocsRepoRefsTest {

    private static final String REPO = "Wyrdsekai/codezaiku";

    private static final Pattern PLACEHOLDER =
            Pattern.compile("<owner>|<repo>|<this repo>|<org>|\\byourorg\\b|\\byour-org\\b", Pattern.CASE_INSENSITIVE);

    /** Any reference to this project as owner/repo, however it is cased.
     *
     *  Both forms matter and only one is a url. The `--repo owner/codezaiku` argument that
     *  `gh attestation verify` takes is the case-sensitive one, and the first version of this test
     *  matched `github.com/...` urls only — so a deliberately mis-cased `--repo` argument sailed
     *  straight through the control that was supposed to prove the check worked. */
    private static final Pattern REPO_REF =
            Pattern.compile("(?:github\\.com/|--repo\\s+)([A-Za-z0-9_.-]+/codezaiku)\\b");

    private static List<Path> shippedText() throws Exception {
        List<Path> out = new ArrayList<>();
        out.addAll(org.codezaiku.testsupport.PublicDocs.files());
        for (Path root : List.of(Path.of("../.github"), Path.of("../packaging"))) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(Files::isRegularFile)
                 .filter(p -> { String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                                return n.endsWith(".md") || n.endsWith(".yml") || n.endsWith(".yaml")
                                        || n.endsWith(".sh"); })
                 .forEach(out::add);
            }
        }
        return out;
    }

    @Test
    void noUnresolvedPlaceholdersInShippedText() throws Exception {
        List<Path> files = shippedText();
        assertTrue(files.size() >= 10, "found only " + files.size() + " shipped text files — layout moved");

        for (Path f : files) {
            Matcher m = PLACEHOLDER.matcher(Files.readString(f));
            assertTrue(!m.find(),
                    f + " still ships the placeholder `" + (m.reset().find() ? m.group() : "") + "`");
        }
    }

    @Test
    void everyRepoReferenceUsesTheExactRepoAndCasing() throws Exception {
        for (Path f : shippedText()) {
            Matcher m = REPO_REF.matcher(Files.readString(f));
            while (m.find()) {
                assertTrue(m.group(1).equals(REPO),
                        f + " references `" + m.group(1) + "` — must be exactly `" + REPO
                                + "`, casing included (attestation identity is case-sensitive)");
            }
        }
    }

    @Test
    void theSarifReportPointsAtTheRealRepository() throws Exception {
        String sarif = Files.readString(Path.of("src/main/java/org/codezaiku/report/Sarif.java"));
        assertTrue(sarif.contains("https://github.com/" + REPO),
                "SARIF informationUri does not point at github.com/" + REPO
                        + " — it lands in every report the review surface emits");
    }
}
