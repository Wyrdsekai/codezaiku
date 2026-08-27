package org.codezaiku.library;

import org.codezaiku.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.codezaiku.exec.Shell;

/**
 * Stage-3 acquirer (scaffold): for each UNGROUNDED concern the coverage check found, FETCH authoritative
 * material (the real installed dep source — read-the-source, never invent), DISTILL it into a pushable
 * idiom via the {@link Distiller}, VALIDATE, and CACHE it under the library cache so it's grounded for this
 * project and every future one. See SPEC_LIBRARY_PROVISIONING.md.
 *
 * <p>SAFE BY DEFAULT: with no distiller endpoint configured ({@link Distiller.None}) it DRY-RUNS — it logs
 * what it would acquire and the source it would read, and writes NOTHING. It only produces idioms once a
 * coder endpoint (the 27B) is wired via CODEZAIKU_DISTILLER_URL. So it ships inert and goes live on deploy.
 */
public final class Acquirer {

    private static final Logger log = LoggerFactory.getLogger(Acquirer.class);

    /** Where acquired idioms are cached (loaded back by {@link Library}). */
    public static Path cacheDir() {
        return Config.home().resolve("library-cache");
    }

    private Acquirer() {}

    /** Acquire grounding for each ungrounded concern. {@code gaps} = concern header → its spec-section body. */
    public static void acquire(Path projectRoot, String stack, List<String[]> gaps, Distiller distiller) {
        if (gaps == null || gaps.isEmpty()) return;
        boolean live = distiller.available();
        for (String[] gap : gaps) {
            String concern = gap[0];
            String section = gap.length > 1 ? gap[1] : "";
            List<String> deps = depCandidates(section);
            if (!live) {
                log.info("acquire[dry-run]: concern '{}' UNGROUNDED — would read {} + distill an idiom (no "
                        + "CODEZAIKU_DISTILLER_URL set, so nothing written)", concern, deps.isEmpty() ? "the stack docs" : deps);
                continue;
            }
            try {
                String source = deps.isEmpty() ? null : deps.get(0);
                String material = source == null ? "" : readDepSource(source, stack);
                if (material.isBlank()) { log.info("acquire: no source found for '{}' (deps={}) → skip", concern, deps); continue; }
                String idiom = distiller.distill(new Distiller.Request(concern, section, stack, source, material));
                if (idiom == null || !validate(idiom, material)) { log.info("acquire: distill/validate failed for '{}' → skip", concern); continue; }
                Path out = cache(concern, deps, idiom);
                log.info("acquire: cached idiom for '{}' from {} → {}", concern, source, out);
            } catch (Exception e) {
                log.info("acquire: '{}' failed ({}) → skip", concern, e.toString());
            }
        }
    }

    // ---- fetch -----------------------------------------------------------------------------------

    /** Dependency/module names named in a spec section. Matches REAL import forms only (`import X`,
     *  `from X import …`, `require("X")`) + backtick-quoted lib names — NOT bare "from the …" prose. */
    private static final Pattern PY_IMPORT = Pattern.compile("\\bimport\\s+([a-zA-Z_][a-zA-Z0-9_.]*)|\\bfrom\\s+([a-zA-Z_][a-zA-Z0-9_.]*)\\s+import\\b");
    private static final Pattern JS_REQUIRE = Pattern.compile("(?:require\\(\\s*|\\bfrom\\s+)[\"']([a-zA-Z_@][\\w./-]*)[\"']");
    private static final Set<String> STOP = Set.of("the", "a", "an", "and", "or", "this", "that", "these",
            "those", "your", "our", "its", "their", "each", "every", "all", "import", "from", "via", "using",
            "with", "for", "name", "type", "data", "test", "tests", "code", "file", "path", "env", "default");

    static List<String> depCandidates(String section) {
        Set<String> out = new LinkedHashSet<>();
        if (section == null) return new ArrayList<>(out);
        Matcher pm = PY_IMPORT.matcher(section);
        while (pm.find()) { String g = pm.group(1) != null ? pm.group(1) : pm.group(2); if (g != null) add(out, g.split("\\.")[0]); }
        Matcher jm = JS_REQUIRE.matcher(section);
        while (jm.find()) add(out, jm.group(1));
        // backtick-quoted lib names in prose, e.g. `mailbox`, `mailparser` (but not stopwords)
        Matcher bt = Pattern.compile("`([a-zA-Z_][\\w.-]{2,30})`").matcher(section);
        while (bt.find()) add(out, bt.group(1).split("[.(]")[0]);
        return new ArrayList<>(out);
    }

    private static void add(Set<String> out, String dep) {
        String d = dep == null ? "" : dep.strip();
        if (d.length() >= 3 && !STOP.contains(d.toLowerCase())) out.add(d);
    }

    /** Read the REAL installed source for a dep — the read-the-source primitive (Python stdlib/site-packages,
     *  node module main). Bounded; "" if not resolvable. (Live path only; the 27B distills from this.) */
    static String readDepSource(String dep, String stack) {
        // Include the API SIGNATURE surface (class/def lines) up front so the distiller sees the REAL method
        // list (e.g. mailbox.Message has no get_count) — the whole point of read-the-source grounding — then
        // a chunk of source. Reading only head-from-start misses classes defined later in a big module.
        String cmd;
        if ("python".equals(stack)) {
            cmd = "f=$(python3 -c \"import " + dep + " as _m; print(getattr(_m,'__file__','') or '')\" 2>/dev/null); "
                + "[ -n \"$f\" ] && { echo '=== API (class/def signatures) ==='; "
                + "grep -nE '^(class |def |    def |  def )' \"$f\" 2>/dev/null | head -200; "
                + "echo '=== source (head) ==='; head -c 24000 \"$f\" 2>/dev/null; }";
        } else if ("javascript".equals(stack)) {
            cmd = "m=$(node -e 'process.stdout.write(require.resolve(\"" + dep + "\"))' 2>/dev/null); "
                + "[ -n \"$m\" ] && { echo '=== exports/signatures ==='; "
                + "grep -nE 'exports\\.|module.exports|function |class ' \"$m\" 2>/dev/null | head -120; "
                + "echo '=== source (head) ==='; head -c 24000 \"$m\" 2>/dev/null; }";
        } else {
            return "";
        }
        return sh(cmd);
    }

    // ---- validate --------------------------------------------------------------------------------

    /** A distilled idiom is acceptable only if it is non-trivial AND doesn't cite an API absent from the
     *  fetched material (a hallucinated method is worse than no idiom). Conservative: backtick-quoted
     *  call-like tokens in the idiom must appear in the source material. */
    static boolean validate(String idiom, String material) {
        if (idiom == null || idiom.strip().length() < 40) return false;
        String mat = material == null ? "" : material;
        // Catch BOTH bare calls (`foo(`) AND method calls (`.foo(`) — a hallucinated method on an object
        // (mailbox.Message.get_count) is exactly what must be rejected, and it appears as `.get_count(`.
        Matcher m = Pattern.compile("[.`]\\s*([a-zA-Z_][a-zA-Z0-9_]{3,})\\s*\\(").matcher(idiom);
        int present = 0, absent = 0;
        while (m.find()) {
            String sym = m.group(1);
            if (BUILTINS.contains(sym)) continue;                 // language builtins won't be in a dep's source
            if (mat.contains(sym)) present++; else absent++;
        }
        // Backstop only (the grounded fetch is the primary defense; we can't prove a single absent symbol is
        // fake — it may be inherited from a module we didn't fetch). Reject the CLEAR ungrounded cases: it
        // cited dep methods but NONE exist in the real source, or it's overwhelmingly absent.
        if (present == 0 && absent >= 1) return false;
        if (absent >= 5 && absent > present * 2) return false;
        return true;
    }

    private static final Set<String> BUILTINS = Set.of(
            "print", "len", "list", "dict", "str", "int", "float", "bool", "set", "tuple", "range",
            "open", "sorted", "enumerate", "zip", "map", "filter", "type", "isinstance", "getattr",
            "console", "require", "JSON", "Object", "Array", "String", "Number", "parseInt", "split", "join");

    // ---- cache -----------------------------------------------------------------------------------

    private static Path cache(String concern, List<String> deps, String idiom) throws Exception {
        Files.createDirectories(cacheDir());
        String slug = concern.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isBlank()) slug = "concern";
        Path out = cacheDir().resolve("acq-" + slug + ".md");
        // a triggers header so Library can concern-key the cached idiom on push
        String triggers = String.join(", ", deps.isEmpty() ? List.of(slug.replace('-', ' ')) : deps);
        String doc = "<!-- acquired; triggers: " + triggers + " -->\n" + idiom.strip() + "\n";
        Files.writeString(out, doc, StandardCharsets.UTF_8);
        return out;
    }

    // ---- shell -----------------------------------------------------------------------------------

    private static String sh(String command) {
        try {
            Process p = Shell.pb(command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(30, TimeUnit.SECONDS);
            return out;
        } catch (Exception e) {
            return "";
        }
    }
}
