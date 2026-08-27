package org.codezaiku.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Path confinement is a SECURITY boundary, not a model-convenience guard: everything under it —
 * blast-radius, the host-write guard — assumes a resolved path cannot leave the project root.
 *
 * <p>The escape these tests pin down was live and verified: {@code normalize()} is a pure string
 * operation that never touches the filesystem, so a symlink stored INSIDE the project but pointing
 * outside it satisfied {@code startsWith(root)} and the write followed the link out of the tree.
 */
class PathScopeConfinementTest {

    /** A symlinked FILE inside the project must not read or write through to an outside target. */
    @Test void symlinkedFileDoesNotEscape(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("proj"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "SECRET");
        Files.createSymbolicLink(root.resolve("link.txt"), outside.resolve("secret.txt"));

        assertThrows(IllegalArgumentException.class,
                () -> new PathScope(root).resolve("link.txt"),
                "a symlink to a file outside the root must be refused");
    }

    /** The directory form: `link/` is in-project, its target is not. */
    @Test void symlinkedDirectoryDoesNotEscape(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("proj"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "SECRET");
        Files.createSymbolicLink(root.resolve("link"), outside);

        assertThrows(IllegalArgumentException.class,
                () -> new PathScope(root).resolve("link/secret.txt"),
                "a path THROUGH a symlinked directory must be refused");
    }

    /**
     * The same escape written as an ABSOLUTE path. This took a separate code path — an absolute
     * path that lexically startsWith(root) was returned before any canonicalisation happened.
     */
    @Test void absolutePathThroughSymlinkDoesNotEscape(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("proj"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "SECRET");
        Files.createSymbolicLink(root.resolve("link"), outside);

        assertThrows(IllegalArgumentException.class,
                () -> new PathScope(root).resolve(root.resolve("link/secret.txt").toString()),
                "an absolute path through an in-project symlink must be refused");
    }

    /** A symlink whose target stays INSIDE the root is legitimate — and must resolve to the real path. */
    @Test void inProjectSymlinkIsAllowedAndCanonicalised(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("proj"));
        Files.createDirectories(root.resolve("real"));
        Path target = Files.writeString(root.resolve("real/file.txt"), "hello");
        Files.createSymbolicLink(root.resolve("alias.txt"), target);

        Path resolved = new PathScope(root).resolve("alias.txt");
        assertEquals(target.toRealPath(), resolved,
                "an in-root symlink must resolve to its real in-root target, not the alias");
    }

    /** Writes create files that do not exist yet — confinement must not depend on the file existing. */
    @Test void newFileInProjectIsAllowed(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("proj"));
        Path p = new PathScope(root).resolve("app/brand/new.py");
        assertTrue(p.startsWith(root.toRealPath()), "a new nested file must resolve inside the root: " + p);
        assertTrue(p.toString().endsWith("app/brand/new.py"), "the tail must be preserved: " + p);
    }

    /** Ordinary in-project paths keep working. */
    @Test void ordinaryRelativePathIsAllowed(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("proj"));
        Files.createDirectories(root.resolve("app"));
        Files.writeString(root.resolve("app/main.py"), "x");
        assertEquals(root.toRealPath().resolve("app/main.py"), new PathScope(root).resolve("app/main.py"));
    }

    /** The pre-existing lexical guard must survive the change. */
    @Test void dotDotStillCannotEscape(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("proj"));
        assertThrows(IllegalArgumentException.class,
                () -> new PathScope(root).resolve("../outside/secret.txt"));
    }
}
