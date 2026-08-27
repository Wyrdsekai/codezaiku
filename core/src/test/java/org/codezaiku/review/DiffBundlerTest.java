package org.codezaiku.review;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundles must remain VALID unified diffs. If a split produces something DiffAnchor cannot parse,
 * anchoring breaks silently — every finding in that bundle becomes unlocatable and the review looks
 * like it found nothing. So the tests check parseability and line numbers, not just the split.
 */
class DiffBundlerTest {

    private static final String TWO_FILES = """
            diff --git a/app/one.py b/app/one.py
            index 111..222 100644
            --- a/app/one.py
            +++ b/app/one.py
            @@ -10,3 +10,4 @@ def setup():
                 config = load()
            +    cache = Cache(ttl=0)
                 return config
            diff --git a/app/two.py b/app/two.py
            index 333..444 100644
            --- a/app/two.py
            +++ b/app/two.py
            @@ -900,3 +900,4 @@ def handle():
                 rows = fetch()
            +    danger = eval(user_input)
                 return rows
            """;

    @Test void splitsAtFileBoundaries() {
        var files = DiffBundler.splitByFile(TWO_FILES);
        assertEquals(2, files.size());
        assertTrue(files.containsKey("app/one.py"), files.keySet().toString());
        assertTrue(files.containsKey("app/two.py"), files.keySet().toString());
    }

    /** THE ONE THAT MATTERS: a split piece must still anchor, with the same line numbers. */
    @Test void eachBundleIsStillAValidDiffThatAnchors() {
        List<DiffBundler.Bundle> bundles = DiffBundler.bundle(TWO_FILES, 200);
        assertEquals(2, bundles.size(), "a 200-char budget must separate these files");

        Optional<DiffAnchor.Anchor> a = DiffAnchor.resolve(
                bundles.get(0).diff(), "cache = Cache(ttl=0)", "app/one.py");
        assertTrue(a.isPresent(), "the first bundle must still anchor");
        assertEquals(11, a.get().startLine(), "and keep the ORIGINAL absolute line number");

        Optional<DiffAnchor.Anchor> b = DiffAnchor.resolve(
                bundles.get(1).diff(), "danger = eval(user_input)", "app/two.py");
        assertTrue(b.isPresent());
        assertEquals(901, b.get().startLine(), "second file's numbering is independent and preserved");
    }

    @Test void packsSmallFilesTogetherUnderTheBudget() {
        List<DiffBundler.Bundle> bundles = DiffBundler.bundle(TWO_FILES, 100_000);
        assertEquals(1, bundles.size(), "both files fit, so one call not two");
        assertEquals(2, bundles.get(0).files().size());
    }

    /** Splitting a file mid-hunk would produce a diff that no longer parses — never do it. */
    @Test void aSingleOversizedFileIsKeptWholeRatherThanCut() {
        StringBuilder big = new StringBuilder("""
                diff --git a/huge.py b/huge.py
                --- a/huge.py
                +++ b/huge.py
                @@ -1,200 +1,300 @@
                """);
        for (int i = 0; i < 500; i++) big.append("+line_").append(i).append(" = ").append(i).append('\n');

        List<DiffBundler.Bundle> bundles = DiffBundler.bundle(big.toString(), 500);
        assertEquals(1, bundles.size(), "one file cannot be split further");
        assertTrue(bundles.get(0).size() > 500, "it is returned oversized rather than cut");
        assertTrue(DiffAnchor.resolve(bundles.get(0).diff(), "line_400 = 400", "huge.py").isPresent(),
                "and it still parses");
    }

    /** Diffs from `git diff` sometimes lead with `---` and carry no `diff --git` line. */
    @Test void handlesDiffsWithoutGitHeaders() {
        String plain = """
                --- a/x.py
                +++ b/x.py
                @@ -1,2 +1,3 @@
                 a = 1
                +b = 2
                --- a/y.py
                +++ b/y.py
                @@ -5,2 +5,3 @@
                 c = 3
                +d = 4
                """;
        var files = DiffBundler.splitByFile(plain);
        assertEquals(2, files.size(), "must still split: " + files.keySet());
        assertTrue(files.containsKey("x.py") && files.containsKey("y.py"), files.keySet().toString());
    }

    /** A new file's old side is /dev/null — the path must come from the +++ line. */
    @Test void namesNewFilesFromTheAddedSide() {
        String added = """
                diff --git a/new.py b/new.py
                --- /dev/null
                +++ b/new.py
                @@ -0,0 +1,2 @@
                +fresh = 1
                """;
        var files = DiffBundler.splitByFile(added);
        assertTrue(files.containsKey("new.py"), files.keySet().toString());
    }

    @Test void emptyInputYieldsNoBundles() {
        assertTrue(DiffBundler.bundle("", 1000).isEmpty());
        assertTrue(DiffBundler.bundle(null, 1000).isEmpty());
    }

    /** Every byte of the original must survive somewhere — a lost hunk is a silently missed review. */
    @Test void losesNothingFromTheOriginal() {
        List<DiffBundler.Bundle> bundles = DiffBundler.bundle(TWO_FILES, 150);
        String rejoined = String.join("", bundles.stream().map(DiffBundler.Bundle::diff).toList());
        for (String line : TWO_FILES.split("\n")) {
            if (!line.isBlank()) {
                assertTrue(rejoined.contains(line), "line lost in bundling: " + line);
            }
        }
    }

    /**
     * A real pandas PR was 676k of diff of which 599k was a single generated pixi.lock — 88% of the
     * change, consuming the whole prompt budget and pushing the actual code out.
     */
    @Test void filtersGeneratedFilesAndSaysWhich() {
        String diff = """
                diff --git a/pixi.lock b/pixi.lock
                --- a/pixi.lock
                +++ b/pixi.lock
                @@ -1,2 +1,3 @@
                 pinned: 1
                +pinned: 2
                diff --git a/app/real.py b/app/real.py
                --- a/app/real.py
                +++ b/app/real.py
                @@ -5,2 +5,3 @@
                 keep = 1
                +bug = eval(x)
                """;
        List<String> filtered = new ArrayList<>();
        List<DiffBundler.Bundle> bundles = DiffBundler.bundle(diff, 100_000, filtered);
        assertEquals(List.of("pixi.lock"), filtered, "the skip must be REPORTED, not silent");
        assertEquals(1, bundles.size());
        assertEquals(List.of("app/real.py"), bundles.get(0).files());
        assertTrue(DiffAnchor.resolve(bundles.get(0).diff(), "bug = eval(x)", "app/real.py").isPresent(),
                "the surviving file must still anchor");
    }

    /** Conservative on purpose: dropping real code from review is far worse than a few wasted tokens. */
    @Test void doesNotFilterHandWrittenFiles() {
        for (String p : List.of("app/main.py", "src/lock.py", "docs/locking.md",
                                "pkg/vendored_api.go", "web/app.js", "Makefile",
                                "requirements.txt", "pyproject.toml")) {
            assertTrue(!DiffBundler.isGenerated(p), "must NOT be filtered: " + p);
        }
    }

    @Test void filtersTheUsualGeneratedShapes() {
        for (String p : List.of("package-lock.json", "yarn.lock", "poetry.lock", "go.sum",
                                "Cargo.lock", "vendor/github.com/x/y.go", "web/node_modules/a/b.js",
                                "static/app.min.js", "api/service.pb.go", "proto/thing_pb2.py")) {
            assertTrue(DiffBundler.isGenerated(p), "should be filtered: " + p);
        }
    }
}
