package org.codezaiku.shape;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One resilient project-tree walk for the whole shape layer. PRUNES ignored dirs (never descends into
 * {@code target/}, {@code .git/}, {@code node_modules/}, …) and TOLERATES files vanishing mid-walk — a
 * live language server's background {@code cargo check} constantly creates and deletes temp files under
 * {@code target/}, which crashed naive {@code Files.walk} with {@code NoSuchFileException} (seen twice:
 * ProjectShape, then ProjectFacts). Every project-root walk in the harness goes through here.
 */
public final class TreeWalk {
    private TreeWalk() {
    }

    /**
     * A cap on what one walk collects. A project has thousands of entries; a HOME directory has millions
     * (`codezaiku chat` started in a home with a 210 GB .cache walked it all to guess the project language and
     * sat on the banner for minutes, 2026-09-18). Past the cap the walk stops and returns what it has: for the
     * shape and the language vote that is plenty, and for anything else a tree that big is not a project.
     */
    public static final int MAX_ENTRIES = 50_000;

    /** Hidden directories (.cache, .local, .mozilla, .git…) and snap hold no source of the project's; never descended. */
    static boolean skip(Path dir, Path base, Set<String> ignoreDirs) {
        if (dir.equals(base)) return false;
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
        return ignoreDirs.contains(name) || name.startsWith(".") || name.equals("snap");
    }

    /** All entries under {@code base} (dirs and files, excluding {@code base}), ignored subtrees pruned, at most {@link #MAX_ENTRIES}. */
    public static List<Path> entries(Path base, Set<String> ignoreDirs) { return entries(base, ignoreDirs, MAX_ENTRIES); }

    /** As above, stopping after {@code max} entries. */
    public static List<Path> entries(Path base, Set<String> ignoreDirs, int max) {
        List<Path> out = new ArrayList<>();
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                    if (skip(dir, base, ignoreDirs)) return FileVisitResult.SKIP_SUBTREE;
                    if (!dir.equals(base)) out.add(dir);
                    return out.size() >= max ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    out.add(f);
                    return out.size() >= max ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE; // vanished mid-walk (target/ churn) — skip it
                }
            });
        } catch (IOException ignored) {
            // whole-walk failure → return whatever was collected
        }
        return out;
    }

    /** Regular files under {@code base}, ignored subtrees pruned. */
    public static List<Path> files(Path base, Set<String> ignoreDirs) {
        return entries(base, ignoreDirs).stream().filter(Files::isRegularFile).toList();
    }

    /** Regular files under {@code base}, stopping once the walk has seen {@code max} entries. */
    public static List<Path> files(Path base, Set<String> ignoreDirs, int max) {
        return entries(base, ignoreDirs, max).stream().filter(Files::isRegularFile).toList();
    }
}
