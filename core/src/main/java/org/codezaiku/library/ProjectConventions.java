package org.codezaiku.library;

import org.codezaiku.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The project's CULTURAL compartment (SPEC_CODEZAIKU_AS_FAMILIAR §17.6): the accumulated CONVENTIONS of a
 * specific project — how THIS codebase does things — as distinct from the familiar's own DEXTERITY (its
 * general approach). Conventions grow from bondholder accept/correct events on the familiar's edits
 * (which an embedding host can surface to the user); a correction is the strong signal ("don't do X,
 * do Y here").
 *
 * <p>Stored codezaiku-side (not in the project tree), keyed by project path, so the "Project Coding DNA"
 * persists across summons and is loaded into the loop's context whenever the familiar codes that project.
 * Fed via the MCP {@code record_convention} tool; read by {@code runLoop} + the {@code show_conventions} tool.
 */
public final class ProjectConventions {
    private ProjectConventions() { }

    private static Path storeFor(Path project) {
        String key = project.toAbsolutePath().normalize().toString().replaceAll("[^A-Za-z0-9]", "_");
        if (key.length() > 120) key = key.substring(key.length() - 120);
        return Config.home().resolve("conventions").resolve(key + ".md");
    }

    /** Record a convention learned for this project. {@code kind} is typically "correct" or "accept". */
    public static synchronized void record(Path project, String convention, String kind) {
        if (project == null || convention == null || convention.isBlank()) return;
        String k = (kind == null || kind.isBlank()) ? "note" : kind.strip().toLowerCase();
        Path store = storeFor(project);
        try {
            Files.createDirectories(store.getParent());
            Files.writeString(store, "- [" + k + "] " + convention.strip().replace("\n", " ") + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { /* best-effort */ }
    }

    /** The raw accumulated conventions for a project, or "" if none. */
    public static String raw(Path project) {
        try {
            Path store = storeFor(project);
            return Files.exists(store) ? Files.readString(store, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** A prompt block for the loop — the project's conventions to follow — or "" if none accumulated yet. */
    public static String promptBlock(Path project) {
        String raw = raw(project).strip();
        if (raw.isEmpty()) return "";
        return "PROJECT CONVENTIONS — this project's accumulated norms (corrections are strong; follow them):\n"
                + raw + "\n\n";
    }
}
