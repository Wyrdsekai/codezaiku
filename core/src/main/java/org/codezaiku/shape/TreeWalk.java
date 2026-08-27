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

    /** All entries under {@code base} (dirs and files, excluding {@code base}), ignored subtrees pruned. */
    public static List<Path> entries(Path base, Set<String> ignoreDirs) {
        List<Path> out = new ArrayList<>();
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                    if (!dir.equals(base) && ignoreDirs.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(base)) out.add(dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    out.add(f);
                    return FileVisitResult.CONTINUE;
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
}
