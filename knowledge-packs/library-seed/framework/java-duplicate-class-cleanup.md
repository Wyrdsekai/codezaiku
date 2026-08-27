---
id: java-duplicate-class-cleanup
keys: java, spring-boot, duplicate, package, layout, cleanup, jpa, repository, dto, entity
priority: 88
---

## Resolving duplicate classes across packages (Spring Boot layering repair)

A canonical Spring Boot project has each domain type in exactly ONE package per the layered
shape: entities in `model/`, repositories in `repository/`, DTOs in `dto/`, controllers in
`controller/`, services in `service/`. When a maintenance fixture has the same simple name in
MULTIPLE packages — e.g. `model/EmailRepository.java` AND `repository/EmailRepository.java` —
that is a layering violation that breaks build at the second compilation pass and that the
test suite cannot run against.

### Diagnosis

```bash
find src/main/java -name "*.java" -exec basename {} \; | sort | uniq -c | sort -rn | head
# Any count > 1 = duplicate class. Open both and identify which package is correct
# per the spring-boot project-shape pack:
#   - @Repository / extends JpaRepository   → repository/
#   - @Entity                                → model/
#   - DTOs (record-based, no persistence)   → dto/
#   - @Controller / @RestController          → controller/
#   - @Service                               → service/
```

### Repair action

The duplicate in the WRONG package must be **deleted**, not edited. Editing both copies
keeps the violation. Use `delete_file` on the wrong-package copy.

Heuristic for "which is wrong":
1. If the class is annotated `@Repository` or extends `JpaRepository<>`, the right package is
   `repository/`. Delete copies in `model/`, `dto/`, anywhere else.
2. If it's `@Entity`, the right package is `model/`. Delete copies in `dto/`, `repository/`.
3. If it's a DTO (no `@Entity`, no `extends JpaRepository`, often a record), the right
   package is `dto/`. Delete copies in `model/`.
4. If a `@Controller` / `@RestController` exists in `model/`, that file declares
   `package controller` but lives at `model/` — the FILE is in the wrong directory. Move it
   to `controller/` (delete + create with corrected path) OR delete it if a correctly-placed
   copy already exists.

### Why this matters for compilation

Two files with the same simple name in different packages are NOT duplicate-class errors at
the Java compilation level (they have different FQNs). But they ARE conflicting Spring beans
if both have `@Component`-family annotations — Spring will throw `BeanDefinitionOverrideException`
or silently inject the wrong bean. And if the test suite imports `com.foo.repository.EmailRepository`,
the `model/EmailRepository.java` copy is dead code OR shadowing the correct symbol.

### The model/Controller.java trap specifically

A file at `src/main/java/com/example/model/SomeController.java` whose declaration line says
`package com.example.controller;` will:
- compile successfully (Java doesn't enforce filename-package consistency in javac, only in
  some IDEs),
- fail at Spring scan time because the file's *real* package on disk is `model`, but the
  declaration claims `controller`, and `@SpringBootApplication(scanBasePackages = ...)`
  reads the on-disk package,
- cause hardcoded `assert 404 == 200` failures on every endpoint the controller serves.

Fix: delete the file and recreate it at `src/main/java/com/example/controller/SomeController.java`
with the same content.

### When in doubt: spec target file list is authoritative

If the spec or task brief lists "expected files" or "expected paths", a duplicate present at
a path NOT in the spec list is the one to delete. Don't edit it; delete it.
