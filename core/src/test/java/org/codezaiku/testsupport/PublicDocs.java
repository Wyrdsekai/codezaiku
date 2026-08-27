package org.codezaiku.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the public documentation lives — which differs between the two trees this suite runs in.
 *
 * <p>In the private repository every public page sits flat under {@code docs/public/}. The export
 * promotes the landing-page docs (README, CONTRIBUTING, SECURITY, …) to the repository ROOT and puts
 * the rest in {@code docs/}. A test that hardcodes one layout passes here and fails in the tree
 * people actually clone — measured: the exported repository's own suite failed two tests, and
 * CONTRIBUTING.md tells a newcomer to run exactly that command as their first step.
 */
public final class PublicDocs {

    private PublicDocs() { }

    /**
     * The public documentation FILES, in whichever layout this tree uses.
     *
     * <p>Files rather than directories, because the exported layout has no single directory holding
     * them: the promoted pages sit at the repository root beside everything else. Returning the root
     * as a "docs directory" swept in the whole tree — a knowledge card using {@code <repo>} as a
     * Kubernetes image placeholder was read as an unresolved doc placeholder and failed the suite.
     */
    public static List<Path> files() {
        List<Path> out = new ArrayList<>();
        Path flat = Path.of("../docs/public");
        if (Files.isDirectory(flat)) {
            addMarkdown(out, flat);              // private tree: everything in one place
        } else {
            addMarkdown(out, Path.of(".."));     // exported tree: promoted pages, top level only
            addMarkdown(out, Path.of("../docs"));
        }
        return out;
    }

    /** Top-level {@code .md} only — never a recursive walk, for the reason above. */
    private static void addMarkdown(List<Path> out, Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (var s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
             .filter(f -> f.getFileName().toString().endsWith(".md"))
             .forEach(out::add);
        } catch (Exception ignored) {
            // an unreadable directory contributes no files
        }
    }

    /** A named public page, wherever it lives. Empty when this tree does not carry it. */
    public static java.util.Optional<Path> page(String name) {
        for (Path p : files()) {
            if (p.getFileName().toString().equals(name)) return java.util.Optional.of(p);
        }
        return java.util.Optional.empty();
    }
}
