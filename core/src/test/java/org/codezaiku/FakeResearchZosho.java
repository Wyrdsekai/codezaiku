package org.codezaiku;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A stand-in for an installed researchzosho command: a shell script that says a chosen version, answers `update now`
 * and `update` with chosen words and exit codes, and writes down every command line it was given. Nothing reaches
 * the network or a real library.
 */
final class FakeResearchZosho {

    final Path dir;
    final Path launcher;

    FakeResearchZosho(Path dir, String version) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
        this.launcher = dir.resolve("researchzosho");
        Files.writeString(launcher, """
                #!/bin/sh
                D='%s'
                printf '%%s\\n' "$*" >> "$D/calls"
                if [ "$1" = "--version" ]; then cat "$D/version"; exit 0; fi
                if [ "$1" = "update" ] && [ "$2" = "now" ]; then cat "$D/now.err" >&2; cat "$D/now.out"; exit "$(cat "$D/now.code")"; fi
                if [ "$1" = "update" ]; then cat "$D/status.out"; exit "$(cat "$D/status.code")"; fi
                if [ "$1" = "setup" ]; then exit 0; fi
                exit 2
                """.formatted(dir.toAbsolutePath()), StandardCharsets.UTF_8);
        launcher.toFile().setExecutable(true);
        write("version", version == null ? "" : "researchzosho " + version + "\n");
        now("", "", 1);
        status("usage: researchzosho update [status | now [version] | auto on|off]\n", 2);
    }

    /** What `update now` prints on stdout and stderr, and its exit code. */
    FakeResearchZosho now(String out, String err, int code) throws IOException {
        write("now.out", out); write("now.err", err); write("now.code", String.valueOf(code));
        return this;
    }

    /** What `update` (the status) prints, and its exit code. */
    FakeResearchZosho status(String out, int code) throws IOException {
        write("status.out", out); write("status.code", String.valueOf(code));
        return this;
    }

    /** Every command line it was run with, in order. */
    List<String> calls() throws IOException {
        Path c = dir.resolve("calls");
        return Files.exists(c) ? Files.readAllLines(c, StandardCharsets.UTF_8) : List.of();
    }

    private void write(String name, String text) throws IOException {
        Files.writeString(dir.resolve(name), text, StandardCharsets.UTF_8);
    }
}
