package org.codezaiku.review;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unioning independent review passes is only worth doing if the merge is right. Too strict and the
 * reader gets the same defect three times; too loose and a second, real finding in the same file is
 * swallowed. Both directions are tested.
 */
class ReviewUnionTest {

    private static ReviewReport.Finding at(String file, int start, int end, String snippet, String sev) {
        return new ReviewReport.Finding(sev, file, snippet, "body",
                Optional.of(new DiffAnchor.Anchor(start, end, DiffAnchor.Side.NEW)));
    }

    private static ReviewReport.Finding unplaced(String file, String snippet) {
        return new ReviewReport.Finding("med", file, snippet, "body", Optional.empty());
    }

    @Test void identicalFindingsFromTwoPassesCollapseToOne() {
        var a = at("app/x.py", 10, 10, "cache = Cache(ttl=0)", "high");
        var b = at("app/x.py", 10, 10, "cache = Cache(ttl=0)", "high");
        assertEquals(1, ReviewReport.union(List.of(List.of(a), List.of(b))).size());
    }

    /** The real case: passes quote different lines of one block and anchor a line or two apart. */
    @Test void overlappingAnchorsAreTheSameFinding() {
        var a = at("app/x.py", 10, 14, "first = 1", "high");
        var b = at("app/x.py", 12, 16, "second = 2", "med");
        assertEquals(1, ReviewReport.union(List.of(List.of(a), List.of(b))).size(),
                "overlapping ranges in one file are one defect, not two");
    }

    @Test void distinctFindingsInTheSameFileBothSurvive() {
        var a = at("app/x.py", 10, 10, "one = 1", "high");
        var b = at("app/x.py", 90, 90, "two = 2", "high");
        assertEquals(2, ReviewReport.union(List.of(List.of(a), List.of(b))).size(),
                "a second real finding must not be swallowed");
    }

    @Test void sameLineInDifferentFilesIsNotADuplicate() {
        var a = at("app/x.py", 10, 10, "shared()", "high");
        var b = at("app/y.py", 10, 10, "shared()", "high");
        assertEquals(2, ReviewReport.union(List.of(List.of(a), List.of(b))).size());
    }

    /** One pass may name a bare filename and another the full path. */
    @Test void toleratesPartialPaths() {
        var a = at("pandas/core/x.py", 10, 10, "q()", "high");
        var b = at("x.py", 10, 10, "q()", "high");
        assertEquals(1, ReviewReport.union(List.of(List.of(a), List.of(b))).size());
    }

    /** Unanchored findings have no position, so they dedupe on the quoted code. */
    @Test void unanchoredDedupeOnSnippet() {
        var a = unplaced("app/x.py", "os.system(cmd)");
        var b = unplaced("app/x.py", "os.system( cmd )");        // whitespace drift
        var c = unplaced("app/x.py", "eval(other)");
        List<ReviewReport.Finding> u = ReviewReport.union(List.of(List.of(a), List.of(b, c)));
        assertEquals(2, u.size(), "same snippet collapses, a different one survives");
    }

    /** An anchored and an unanchored finding are not comparable by position — keep both. */
    @Test void anchoredAndUnanchoredAreNotMerged() {
        var a = at("app/x.py", 10, 10, "q()", "high");
        var b = unplaced("app/x.py", "q()");
        assertEquals(2, ReviewReport.union(List.of(List.of(a), List.of(b))).size());
    }

    @Test void handlesEmptyAndNullPasses() {
        assertTrue(ReviewReport.union(List.of()).isEmpty());
        var a = at("app/x.py", 1, 1, "x = 1", "low");
        assertEquals(1, ReviewReport.union(Arrays.asList(null, List.of(a), List.of())).size());
    }

    /** The whole point: a finding present in only ONE pass still reaches the report. */
    @Test void aFindingSeenByOnlyOnePassSurvives() {
        var common = at("app/x.py", 10, 10, "a = 1", "high");
        var rare = at("app/x.py", 200, 200, "rare_bug()", "high");
        List<ReviewReport.Finding> u = ReviewReport.union(List.of(
                List.of(common), List.of(common, rare), List.of(common)));
        assertEquals(2, u.size());
        assertTrue(u.stream().anyMatch(f -> f.snippet().equals("rare_bug()")),
                "the finding only one pass saw is what unioning exists to recover");
    }
}
