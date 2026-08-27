package org.codezaiku.library;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves which Library framework packs are relevant to a project, from its language + manifests.
 *
 * <p>The index's {@code framework} field holds DevDocs pack slugs ({@code godot}, {@code fastapi},
 * {@code spring-boot}, {@code rust}, …). A project's detected <em>language</em> ({@code gdscript},
 * {@code python}, {@code java}, …) is NOT always the pack slug — gdscript→godot, and a Python project
 * may pull from {@code fastapi}/{@code django}/{@code numpy}/{@code python}. So we map language to its
 * base pack(s) and add framework packs detected from manifest markers. The push query filters to this
 * set, so a rust task never retrieves pytorch and a Godot task actually reaches the godot pack.
 */
public final class LibraryScope {

    private LibraryScope() {
    }

    /** language → base pack slug(s) in the index. */
    private static final Map<String, List<String>> LANG_BASE = Map.of(
            "gdscript", List.of("godot"),
            "rust", List.of("rust"),
            "go", List.of("go"),
            "java", List.of("java"),
            "python", List.of("python"),
            "javascript", List.of("javascript", "node"),
            "typescript", List.of("typescript", "javascript", "node"));

    /** manifest substring (lowercased) → framework pack slug to add. */
    private static final Map<String, String> MARKERS = new LinkedHashMap<>();

    static {
        MARKERS.put("fastapi", "fastapi");
        MARKERS.put("django", "django");
        MARKERS.put("flask", "flask");
        MARKERS.put("org.springframework.boot", "spring-boot");
        MARKERS.put("spring-boot", "spring-boot");
        MARKERS.put("express", "express");
        MARKERS.put("react", "react");
        MARKERS.put("vite", "vite");
        MARKERS.put("tailwind", "tailwindcss");
        MARKERS.put("numpy", "numpy");
        MARKERS.put("pandas", "pandas");
        MARKERS.put("torch", "pytorch");
        MARKERS.put("scikit-learn", "scikit-learn");
        MARKERS.put("sklearn", "scikit-learn");
        MARKERS.put("redis", "redis");
        MARKERS.put("postgres", "postgresql");
        MARKERS.put("psycopg", "postgresql");
        MARKERS.put("typescript", "typescript");
    }

    private static final List<String> MANIFESTS = List.of(
            "Cargo.toml", "requirements.txt", "pyproject.toml", "package.json",
            "pom.xml", "build.gradle", "build.gradle.kts", "go.mod");

    /** Relevant framework pack slugs for this project (base language pack + manifest-detected frameworks). */
    public static List<String> frameworks(Path root, String language) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (language != null && !language.isBlank()) {
            out.addAll(LANG_BASE.getOrDefault(language, List.of(language)));
        }
        if (Files.isRegularFile(root.resolve("project.godot"))) {
            out.add("godot");
        }
        // Rust: each Cargo.toml dependency is a candidate framework pack (ratatui, sysinfo, crossterm…).
        Path cargo = root.resolve("Cargo.toml");
        if (Files.isRegularFile(cargo)) {
            out.addAll(cargoDeps(cargo));
        }
        String manifest = readManifests(root).toLowerCase();
        for (Map.Entry<String, String> e : MARKERS.entrySet()) {
            if (manifest.contains(e.getKey())) out.add(e.getValue());
        }
        // Domain expansion: conceptual patterns are tagged by DOMAIN (gamedev, cli, web…), not the
        // framework slug. Add the domains a project's stack implies so those patterns surface too.
        for (String fw : new ArrayList<>(out)) {
            List<String> domains = DOMAIN_EXPANSION.get(fw);
            if (domains != null) out.addAll(domains);
        }
        return new ArrayList<>(out);
    }

    /** framework/lang slug → domain tokens used by the conceptual pattern packs. */
    private static final Map<String, List<String>> DOMAIN_EXPANSION = Map.ofEntries(
            Map.entry("godot", List.of("gamedev", "game")),
            Map.entry("gdscript", List.of("gamedev", "game")),
            Map.entry("ratatui", List.of("cli", "tui", "terminal")),
            Map.entry("crossterm", List.of("cli", "tui", "terminal")),
            Map.entry("clap", List.of("cli", "tool")),
            Map.entry("fastapi", List.of("web", "api")),
            Map.entry("express", List.of("web", "api")),
            Map.entry("flask", List.of("web", "api")),
            Map.entry("django", List.of("web", "api")),
            Map.entry("spring-boot", List.of("web", "api", "enterprise")),
            Map.entry("axum", List.of("web", "api")),
            Map.entry("actix", List.of("web", "api")),
            Map.entry("numpy", List.of("ml", "data")),
            Map.entry("pandas", List.of("ml", "data")),
            Map.entry("pytorch", List.of("ml")),
            Map.entry("scikit-learn", List.of("ml")),
            Map.entry("react", List.of("web", "frontend")),
            Map.entry("vite", List.of("web", "frontend")));

    private static final Pattern CARGO_DEP = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_-]+)\\s*=");

    /** Crate names from Cargo.toml [dependencies]/[dev-dependencies] sections. */
    private static List<String> cargoDeps(Path cargoToml) {
        List<String> deps = new ArrayList<>();
        try {
            boolean inDeps = false;
            for (String line : Files.readAllLines(cargoToml)) {
                String t = line.strip();
                if (t.startsWith("[")) {
                    inDeps = t.contains("dependencies");
                    continue;
                }
                if (inDeps) {
                    Matcher m = CARGO_DEP.matcher(line);
                    if (m.find()) deps.add(m.group(1).toLowerCase());
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
        return deps;
    }

    private static String readManifests(Path root) {
        StringBuilder sb = new StringBuilder();
        for (String n : MANIFESTS) {
            Path p = root.resolve(n);
            if (Files.isRegularFile(p)) {
                try {
                    sb.append(Files.readString(p)).append('\n');
                } catch (Exception ignored) {
                    // skip unreadable manifest
                }
            }
        }
        return sb.toString();
    }
}
