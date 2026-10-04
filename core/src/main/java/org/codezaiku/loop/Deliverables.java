package org.codezaiku.loop;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The files a goal names that the run has to write, and which of them exist.
 *
 * <p>A goal says what to hand over by naming files: "write measure.py", "put the numbers in RESULTS.md". A long run loses sight of
 * the ones it has not reached yet — on 2026-09-30 two 100-turn runs ended with the tool half-built and neither RESULTS.md nor
 * requirements.txt written, and in July a run finished its evaluator and never produced the verdicts file the goal asked for. So the
 * names are read from the goal once, the ones already on disk when the run starts are set aside (those are inputs, or files to
 * edit), and the rest are checked on disk each turn. The check is the harness's; the model is shown the result.
 */
final class Deliverables {

    static final int MAX = 12;

    /**
     * How a named file comes to exist. A program and a document are written; an output is what a program produces when it runs,
     * and writing one by hand would be inventing results — so the turns that offer only the write tool are never for outputs.
     */
    enum Kind { PROGRAM, DOCUMENT, OUTPUT }

    private static final Set<String> DOCUMENT_EXT = Set.of("md", "txt", "rst");
    private static final Set<String> OUTPUT_EXT = Set.of("json", "jsonl", "csv", "tsv", "xml", "parquet", "png", "jpg", "jpeg", "svg", "pdf",
            "lock", "ipynb");

    static Kind kind(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 || dot < name.lastIndexOf('/') ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return DOCUMENT_EXT.contains(ext) ? Kind.DOCUMENT : OUTPUT_EXT.contains(ext) ? Kind.OUTPUT : Kind.PROGRAM;
    }
    private static final int MAX_VISITED = 50_000;

    private static final String EXT = "py|pyi|ipynb|js|mjs|cjs|ts|tsx|jsx|java|kt|kts|rs|go|rb|php|cs|swift|scala|c|h|cc|cpp|hpp|sh|bash|ps1|sql"
            + "|md|txt|rst|json|jsonl|yaml|yml|toml|ini|cfg|conf|csv|tsv|xml|html|htm|css|scss|gradle|lock|parquet|png|jpg|jpeg|svg|pdf|proto";
    // A name is taken only when it stands alone: not inside a URL or a longer path (a "/", ":" or "." before it), and not a pattern
    // or placeholder ("<id>.json", "*.py", "{name}.md", "$OUT.csv").
    private static final Pattern NAMED = Pattern.compile(
            "(?<![\\w./:@<{$*\\\\-])((?:\\.{1,2}/)?(?:[\\w-]+/)*[\\w-]+(?:\\.[\\w-]+)*\\.(?:" + EXT + "))(?![\\w/>}*(-])(?!\\.[\\w])");
    private static final Pattern PLAIN_NAMED = Pattern.compile("(?<![\\w./-])(Dockerfile|Makefile)(?![\\w/-])(?!\\.\\w)");
    // Products that are spelled like a file.
    private static final Set<String> NOT_FILES = Set.of("node.js", "next.js", "vue.js", "react.js", "d3.js", "three.js", "express.js",
            "nuxt.js", "angular.js", "ember.js", "backbone.js", "chart.js", "p5.js", "alpine.js", "moment.js", "discord.js", "nest.js",
            "solid.js", "svelte.js", "deno.js", "bun.js", "asp.net");
    private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "target", "build", "dist", ".venv", "venv", "__pycache__",
            ".cp-checkpoints", ".gradle", ".idea", ".mypy_cache", ".pytest_cache", ".tox");

    private final Path root;
    private final List<String> names;
    private List<String> missing = List.of();

    private Deliverables(Path root, List<String> names) {
        this.root = root;
        this.names = names;
    }

    /** Nothing to track. */
    static Deliverables none() { return new Deliverables(null, List.of()); }

    /** The files the goal names that are not on disk now, at the start of the run. */
    static Deliverables of(String goal, Path root) {
        List<String> named = named(goal);
        if (named.isEmpty() || root == null) return none();
        Set<String> there = present(root, named);
        List<String> toWrite = new ArrayList<>();
        for (String n : named) if (!there.contains(n)) toWrite.add(n);
        Deliverables d = new Deliverables(root, List.copyOf(toWrite));
        d.missing = d.names;
        return d;
    }

    /** Every file name in the goal, in the order it names them, each once. */
    static List<String> named(String goal) {
        if (goal == null || goal.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Set<String> seenLower = new HashSet<>();
        Matcher m = NAMED.matcher(goal);
        while (m.find() && out.size() < MAX) {
            String name = m.group(1);
            if (name.startsWith("./")) name = name.substring(2);
            String lower = name.toLowerCase(Locale.ROOT);
            if (NOT_FILES.contains(lower)) continue;
            // "Next.js", "Vue.js": a capitalised one-word .js name is a product, not a file
            if (lower.endsWith(".js") && !name.contains("/") && Character.isUpperCase(name.charAt(0))) continue;
            if (seenLower.add(lower)) out.add(name);
        }
        Matcher p = PLAIN_NAMED.matcher(goal);
        while (p.find() && out.size() < MAX) {
            if (seenLower.add(p.group(1).toLowerCase(Locale.ROOT))) out.add(p.group(1));
        }
        return List.copyOf(out);
    }

    boolean isEmpty() { return names.isEmpty(); }

    List<String> names() { return names; }

    /** Look at the disk again; call once a turn. */
    void refresh() {
        if (names.isEmpty()) return;
        Set<String> there = present(root, names);
        List<String> gone = new ArrayList<>();
        for (String n : names) if (!there.contains(n)) gone.add(n);
        missing = List.copyOf(gone);
    }

    /** The named files that were not there at the last {@link #refresh()}. */
    List<String> missing() { return missing; }

    /** The named files of these kinds that were not there at the last {@link #refresh()}. */
    List<String> missing(Kind first, Kind... more) {
        List<String> out = new ArrayList<>();
        for (String n : missing) {
            Kind k = kind(n);
            boolean wanted = k == first;
            for (Kind m : more) wanted |= k == m;
            if (wanted) out.add(n);
        }
        return out;
    }

    /** The names, each in backticks, joined for a sentence. */
    static String list(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) sb.append(sb.length() == 0 ? "" : ", ").append('`').append(n).append('`');
        return sb.toString();
    }

    /** The block shown to the model every turn; empty when the goal names nothing to write. */
    String pinned() {
        if (names.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\nFILES THE GOAL NAMES (the harness checked the disk at the start of this turn). "
                + "The work is complete when each one exists with its real content:");
        for (String n : names) sb.append("\n - ").append(n).append(missing.contains(n) ? " — not written yet" : " — written");
        return sb.append('\n').toString();
    }

    /** Which of the names exist as a non-empty file: at that path under the root, or (a bare name) anywhere in the project. */
    private static Set<String> present(Path root, List<String> names) {
        Set<String> found = new HashSet<>();
        Set<String> bare = new HashSet<>();
        for (String n : names) {
            if (nonEmptyFile(root.resolve(n))) found.add(n);
            else if (!n.contains("/")) bare.add(n.toLowerCase(Locale.ROOT));
        }
        if (bare.isEmpty()) return found;
        Set<String> seen = new HashSet<>();
        int[] visited = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path name = dir.getFileName();
                    if (!dir.equals(root) && name != null && SKIP_DIRS.contains(name.toString())) return FileVisitResult.SKIP_SUBTREE;
                    return ++visited[0] > MAX_VISITED ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (++visited[0] > MAX_VISITED) return FileVisitResult.TERMINATE;
                    String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (attrs.isRegularFile() && attrs.size() > 0 && bare.contains(lower)) seen.add(lower);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException e) { return FileVisitResult.CONTINUE; }
            });
        } catch (IOException e) {
            // an unreadable tree: report what was found
        }
        for (String n : names) if (seen.contains(n.toLowerCase(Locale.ROOT))) found.add(n);
        return found;
    }

    private static boolean nonEmptyFile(Path p) {
        try {
            return Files.isRegularFile(p) && Files.size(p) > 0;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
