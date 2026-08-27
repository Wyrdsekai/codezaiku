package org.codezaiku.verify;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads how many tests passed and failed out of a test runner's output.
 *
 * <p>Every parser here returns null rather than guessing. A caller that reports these numbers outward
 * needs "we could not tell" to stay distinct from "zero" — reporting zero passed for a green suite
 * understates it, and understating a green suite is the exact bug this exists to fix.
 *
 * <p>Summary lines are matched from the END of the output, because a runner prints its totals last and
 * an earlier line may be a per-file tally or a fixture's own text quoting a similar phrase.
 */
final class TestCounts {

    /** {@code {passed, failed}} or null when nothing recognisable was found. */
    static int[] parse(String out) {
        if (out == null || out.isBlank()) return null;
        for (Parser p : PARSERS) {
            int[] r = p.apply(out);
            if (r != null) return r;
        }
        return null;
    }

    private interface Parser {
        int[] apply(String out);
    }

    /** pytest: "2 passed", "1 failed, 2 passed in 0.03s", "3 passed, 1 skipped". */
    private static final Pattern PYTEST_PASSED = Pattern.compile("(\\d+) passed");
    private static final Pattern PYTEST_FAILED = Pattern.compile("(\\d+) (?:failed|error(?:s|ed)?)");

    /** unittest: "Ran 5 tests in 0.001s" then "OK" or "FAILED (failures=2, errors=1)". */
    private static final Pattern UNITTEST_RAN = Pattern.compile("Ran (\\d+) tests?");
    private static final Pattern UNITTEST_BAD = Pattern.compile("(?:failures|errors)=(\\d+)");

    /** cargo: "test result: ok. 5 passed; 0 failed". */
    private static final Pattern CARGO = Pattern.compile("test result:.*?(\\d+) passed;\\s*(\\d+) failed");

    /** jest / vitest: "Tests:       1 failed, 2 passed, 3 total". */
    private static final Pattern JEST_LINE = Pattern.compile("(?m)^\\s*Tests:\\s+(.+)$");

    /** mocha: "2 passing", "1 failing". */
    private static final Pattern MOCHA_PASS = Pattern.compile("(\\d+) passing");
    private static final Pattern MOCHA_FAIL = Pattern.compile("(\\d+) failing");

    /** go test -v: per-test "--- PASS:" / "--- FAIL:" lines. */
    private static final Pattern GO_PASS = Pattern.compile("(?m)^\\s*--- PASS:");
    private static final Pattern GO_FAIL = Pattern.compile("(?m)^\\s*--- FAIL:");

    private static final List<Parser> PARSERS = List.of(
            TestCounts::pytest, TestCounts::unittest, TestCounts::cargo,
            TestCounts::jest, TestCounts::mocha, TestCounts::goTest);

    private static int[] pytest(String out) {
        Integer p = lastInt(PYTEST_PASSED, out);
        Integer f = lastInt(PYTEST_FAILED, out);
        if (p == null && f == null) return null;
        return new int[]{p == null ? 0 : p, f == null ? 0 : f};
    }

    private static int[] unittest(String out) {
        Integer ran = lastInt(UNITTEST_RAN, out);
        if (ran == null) return null;
        int bad = 0;
        Matcher m = UNITTEST_BAD.matcher(out);
        while (m.find()) bad += Integer.parseInt(m.group(1));
        return new int[]{Math.max(0, ran - bad), bad};
    }

    private static int[] cargo(String out) {
        Matcher m = CARGO.matcher(out);
        int[] last = null;
        while (m.find()) last = new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        return last;
    }

    private static int[] jest(String out) {
        Matcher m = JEST_LINE.matcher(out);
        String line = null;
        while (m.find()) line = m.group(1);
        if (line == null) return null;
        Integer p = lastInt(Pattern.compile("(\\d+) passed"), line);
        Integer f = lastInt(Pattern.compile("(\\d+) failed"), line);
        if (p == null && f == null) return null;
        return new int[]{p == null ? 0 : p, f == null ? 0 : f};
    }

    private static int[] mocha(String out) {
        Integer p = lastInt(MOCHA_PASS, out);
        Integer f = lastInt(MOCHA_FAIL, out);
        if (p == null && f == null) return null;
        return new int[]{p == null ? 0 : p, f == null ? 0 : f};
    }

    private static int[] goTest(String out) {
        int p = count(GO_PASS, out);
        int f = count(GO_FAIL, out);
        if (p == 0 && f == 0) return null;
        return new int[]{p, f};
    }

    /**
     * JUnit XML, for gradle and maven — whose console output reports failures but not a pass total.
     * Reading the report files rather than the banner is the reliable route for those stacks.
     */
    static int[] fromJUnitXml(Path work) {
        List<Path> dirs = List.of(
                work.resolve("build/test-results/test"),
                work.resolve("target/surefire-reports"),
                work.resolve("target/failsafe-reports"));
        int tests = 0;
        int bad = 0;
        boolean found = false;
        for (Path d : dirs) {
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> s = Files.list(d)) {
                for (Path f : (Iterable<Path>) s.filter(p -> p.getFileName().toString().endsWith(".xml"))::iterator) {
                    String head = readHead(f);
                    if (head == null) continue;
                    Integer t = attr(head, "tests");
                    if (t == null) continue;
                    found = true;
                    tests += t;
                    bad += or0(attr(head, "failures")) + or0(attr(head, "errors"));
                }
            } catch (Exception ignored) {
                // an unreadable report directory just means no counts from this source
            }
        }
        return found ? new int[]{Math.max(0, tests - bad), bad} : null;
    }

    /** The attributes live on the opening <testsuite> tag; no need to read a large report in full. */
    private static String readHead(Path f) {
        try {
            byte[] b = Files.readAllBytes(f);
            int n = Math.min(b.length, 2048);
            return new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer attr(String xml, String name) {
        Matcher m = Pattern.compile(name + "=\"(\\d+)\"").matcher(xml);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private static int or0(Integer i) {
        return i == null ? 0 : i;
    }

    /** The LAST match — a runner's totals come at the end. */
    private static Integer lastInt(Pattern p, String s) {
        Matcher m = p.matcher(s);
        Integer last = null;
        while (m.find()) last = Integer.parseInt(m.group(1));
        return last;
    }

    private static int count(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private TestCounts() { }
}
