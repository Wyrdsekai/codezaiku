---
id: forbidden-patterns
keys: repair, forbidden-patterns, do-not
priority: 95
---

## Forbidden patterns (hard rules)

- **No version downgrades or substitutions** in `build.gradle` / `pom.xml`. Pinned versions are authoritative.
- **No `javax.*` imports** in Spring Boot 3.x code. Jakarta EE only.
- **No field injection** (`@Autowired` on fields). Constructor injection only.
- **No new files in `model/` that aren't `@Entity`**. The `model/` package is for entities only.
- **No editing a file's `package` declaration to match a wrong directory**. Move or delete the file instead. (See `java-package-directory-rule` for the dedup heuristic.)
- **No silent file deletion**. Before deleting, confirm an equivalent exists at the correct location, and state explicitly which file you are deleting and why.
- **No adding features beyond what existing scaffolding requires** during Repair-mode work.
