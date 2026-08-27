package org.codezaiku.ops;

import java.nio.file.Files;
import java.nio.file.Path;

/** The box IS this machine. Commands run through {@code bash -lc}; files via the JVM filesystem. */
final class LocalExec implements Exec {

    @Override public String describe() { return "local"; }

    @Override public Result run(String command, int timeoutSec) {
        return Procs.run(null, timeoutSec, "bash", "-lc", command);
    }

    @Override public String read(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (Exception e) {
            return null;
        }
    }

    @Override public boolean isFile(String path) { return Files.isRegularFile(Path.of(path)); }

    @Override public boolean isDir(String path) { return Files.isDirectory(Path.of(path)); }

    @Override public void write(String path, String content) {
        try {
            Path p = Path.of(path);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.writeString(p, content);
        } catch (Exception e) {
            throw new RuntimeException("local write failed for " + path + ": " + e.getMessage(), e);
        }
    }
}
