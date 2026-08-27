package org.codezaiku.review;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The point of anchoring is that a WRONG line is worse than no line: a comment on the wrong code
 * wastes the reader's attention and teaches them to distrust the whole report. So these tests care as
 * much about refusing to answer as about answering.
 */
class DiffAnchorTest {

    /** A small but realistic diff: one file, two hunks, additions and a deletion. */
    private static final String DIFF = """
            diff --git a/app/handler.py b/app/handler.py
            index 1234567..89abcde 100644
            --- a/app/handler.py
            +++ b/app/handler.py
            @@ -10,6 +10,7 @@ def setup():
                 config = load_config()
                 db = connect(config)
            +    cache = Cache(ttl=0)
                 logger.info("ready")
                 return db

            @@ -40,7 +41,7 @@ def handle(request):
                 user = request.get("user")
            -    query = "SELECT * FROM t WHERE u = " + user
            +    query = build_query(user)
                 rows = db.execute(query)
                 return rows
            """;

    @Test void anchorsAnAddedLineToItsNewFileNumber() {
        Optional<DiffAnchor.Anchor> a = DiffAnchor.resolve(DIFF, "cache = Cache(ttl=0)");
        assertTrue(a.isPresent(), "an added line must anchor");
        assertEquals(12, a.get().startLine());
        assertEquals(DiffAnchor.Side.NEW, a.get().side());
        assertTrue(a.get().isSingleLine());
    }

    /** Context lines belong to both sides; a comment about unchanged code must still anchor. */
    @Test void anchorsAContextLine() {
        Optional<DiffAnchor.Anchor> a = DiffAnchor.resolve(DIFF, "db = connect(config)");
        assertTrue(a.isPresent());
        assertEquals(11, a.get().startLine());
    }

    /** The second hunk must be numbered from its own header, not continued from the first. */
    @Test void usesEachHunksOwnLineNumbering() {
        Optional<DiffAnchor.Anchor> a = DiffAnchor.resolve(DIFF, "query = build_query(user)");
        assertTrue(a.isPresent());
        assertEquals(42, a.get().startLine());
    }

    @Test void anchorsAMultiLineSnippet() {
        Optional<DiffAnchor.Anchor> a = DiffAnchor.resolve(DIFF, """
                query = build_query(user)
                rows = db.execute(query)""");
        assertTrue(a.isPresent());
        assertEquals(42, a.get().startLine());
        assertEquals(43, a.get().endLine());
    }

    /** Commenting on DELETED code is legitimate and must resolve against the old side. */
    @Test void fallsBackToTheOldSideForDeletedCode() {
        Optional<DiffAnchor.Anchor> a =
                DiffAnchor.resolve(DIFF, "query = \"SELECT * FROM t WHERE u = \" + user");
        assertTrue(a.isPresent(), "deleted code should anchor on the old side");
        assertEquals(DiffAnchor.Side.OLD, a.get().side());
        assertEquals(41, a.get().startLine());
    }

    /** THE IMPORTANT ONE: invented code must not be reported at a guessed line. */
    @Test void refusesToAnchorCodeThatIsNotInTheDiff() {
        assertEquals(Optional.empty(),
                DiffAnchor.resolve(DIFF, "os.system(user_input)  # never appears anywhere"));
    }

    @Test void refusesEmptyAndBlankSnippets() {
        assertEquals(Optional.empty(), DiffAnchor.resolve(DIFF, ""));
        assertEquals(Optional.empty(), DiffAnchor.resolve(DIFF, "   \n  \n"));
        assertEquals(Optional.empty(), DiffAnchor.resolve(DIFF, null));
    }

    /** Models re-type code with different indentation instead of copying it. */
    @Test void toleratesIndentationAndWhitespaceDrift() {
        Optional<DiffAnchor.Anchor> a =
                DiffAnchor.resolve(DIFF, "        cache   =    Cache(ttl=0)   ");
        assertTrue(a.isPresent(), "whitespace drift must not defeat the match");
        assertEquals(12, a.get().startLine());
    }

    /** Models paste the diff's own markers into the quoted snippet. */
    @Test void stripsPastedDiffMarkers() {
        Optional<DiffAnchor.Anchor> a = DiffAnchor.resolve(DIFF, "+    cache = Cache(ttl=0)");
        assertTrue(a.isPresent(), "a pasted '+' marker must not defeat the match");
        assertEquals(12, a.get().startLine());
    }

    /** But a leading minus that is real code (unary/negative) must not be eaten. */
    @Test void doesNotMistakeRealCodeForADiffMarker() {
        String diff = """
                --- a/m.py
                +++ b/m.py
                @@ -1,2 +1,3 @@
                 x = 1
                +offset = -1
                 y = 2
                """;
        Optional<DiffAnchor.Anchor> a = DiffAnchor.resolve(diff, "offset = -1");
        assertTrue(a.isPresent());
        assertEquals(2, a.get().startLine());
    }

    /** Whole-file review has no meaningful diff to match against. */
    @Test void resolvesAgainstWholeFileContent() {
        String file = "import os\n\ndef run(cmd):\n    os.system(cmd)\n    return 0\n";
        Optional<DiffAnchor.Anchor> a = DiffAnchor.resolveInFile(file, "os.system(cmd)");
        assertTrue(a.isPresent());
        assertEquals(4, a.get().startLine());
        assertEquals(Optional.empty(), DiffAnchor.resolveInFile(file, "eval(cmd)"));
    }

    /** A repeated snippet resolves to its FIRST occurrence — defined, not arbitrary. */
    @Test void repeatedSnippetTakesTheFirstOccurrence() {
        String file = "a = 1\nb = 2\na = 1\n";
        Optional<DiffAnchor.Anchor> a = DiffAnchor.resolveInFile(file, "a = 1");
        assertTrue(a.isPresent());
        assertEquals(1, a.get().startLine());
    }

    // ── failures observed in real runs (the ~48% that did not anchor) ─────────

    /**
     * THE DOMINANT REAL FAILURE. The model quotes two statements and silently drops the comment
     * lines between them, so the quoted block is real but not CONTIGUOUS. Observed verbatim in a
     * captured run as {@code "Path abs = Path.of(r).normalize();\n if (abs.startsWith(root))..."}
     * against code carrying two comment lines in the gap. Demanding a consecutive run made this
     * unanchorable even though every quoted line is present.
     */
    @Test void anchorsWhenTheModelElidesLinesInBetween() {
        String diff = """
                --- a/PathScope.java
                +++ b/PathScope.java
                @@ -56,8 +56,9 @@ public Path resolve(String relative) {
                             Path abs = Path.of(r).normalize();
                             // Lexically in-root is NOT enough — a symlink still starts with root
                             // as a string. confine() canonicalises before deciding.
                             if (abs.startsWith(root)) return confine(abs, relative);
                +            log.debug("confined");
                """;
        Optional<DiffAnchor.Anchor> a = DiffAnchor.resolve(diff,
                "Path abs = Path.of(r).normalize();\nif (abs.startsWith(root)) return confine(abs, relative);");
        assertTrue(a.isPresent(), "a real but non-contiguous quote must still anchor");
        assertEquals(56, a.get().startLine(), "the block starts at the first quoted line present");
        assertEquals(59, a.get().endLine(), "and spans to the last, so it reads as the block");
        assertEquals(DiffAnchor.Side.NEW, a.get().side(), "must anchor on the new side, not slip to old");
    }

    /** Anchoring falls back to the most DISTINCTIVE line, never to a generic one. */
    @Test void fallbackPicksTheDistinctiveLineNotABrace() {
        String diff = """
                --- a/A.java
                +++ b/A.java
                @@ -10,5 +10,6 @@
                     if (x) {
                +        doSomethingVeryDistinctive(alpha, beta);
                     }
                """;
        Optional<DiffAnchor.Anchor> a = DiffAnchor.resolve(diff,
                "}\ndoSomethingVeryDistinctive(alpha, beta);\n}");
        assertTrue(a.isPresent());
        assertEquals(11, a.get().startLine(), "must land on the distinctive line, not a brace");
    }

    /** A prose description is not a quote — it must stay unanchored rather than match something. */
    @Test void refusesAProseDescriptionInsteadOfCode() {
        String diff = """
                --- a/A.java
                +++ b/A.java
                @@ -1,3 +1,2 @@
                 keep = 1
                -deleted = 2
                """;
        assertEquals(Optional.empty(), DiffAnchor.resolve(diff, "deleted file"));
    }

    /** Relaxing must not invent an anchor for code that genuinely is not there. */
    @Test void relaxationDoesNotAnchorAbsentCode() {
        String diff = """
                --- a/A.java
                +++ b/A.java
                @@ -1,3 +1,4 @@
                 int a = 1;
                +int b = 2;
                 int c = 3;
                """;
        assertEquals(Optional.empty(),
                DiffAnchor.resolve(diff, "totallyUnrelatedCall(x);\nanotherMissingLine(y);"));
    }

    /**
     * A multi-file diff must not anchor a finding to a line from the WRONG FILE. Found in a real
     * scoring run: two findings came back as {@code array.py:3644} and {@code string_arrow.py:3644} —
     * the identical line number in different files, because resolve() searched every file's hunks and
     * returned the first match, which was then paired with whatever file the model named. That is a
     * confidently-wrong location, the failure this class exists to prevent.
     */
    @Test void doesNotAnchorToALineFromAnotherFile() {
        String diff = """
                --- a/alpha.py
                +++ b/alpha.py
                @@ -10,3 +10,4 @@
                 keep = 1
                +shared_helper(x)
                 tail = 2
                --- a/beta.py
                +++ b/beta.py
                @@ -900,3 +900,4 @@
                 other = 1
                +shared_helper(x)
                 more = 2
                """;
        Optional<DiffAnchor.Anchor> inBeta = DiffAnchor.resolve(diff, "shared_helper(x)", "beta.py");
        assertTrue(inBeta.isPresent());
        assertEquals(901, inBeta.get().startLine(), "must use beta.py's numbering, not alpha.py's");

        Optional<DiffAnchor.Anchor> inAlpha = DiffAnchor.resolve(diff, "shared_helper(x)", "alpha.py");
        assertEquals(11, inAlpha.get().startLine(), "and alpha.py's when that is the file named");
    }

    /** A file the diff does not touch cannot anchor, however familiar the code looks. */
    @Test void refusesWhenTheNamedFileIsNotInTheDiff() {
        String diff = """
                --- a/alpha.py
                +++ b/alpha.py
                @@ -10,3 +10,4 @@
                 keep = 1
                +shared_helper(x)
                """;
        assertEquals(Optional.empty(), DiffAnchor.resolve(diff, "shared_helper(x)", "unrelated.py"));
    }
}
