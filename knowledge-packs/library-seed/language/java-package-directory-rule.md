---
id: java-package-directory-rule
keys: java, package-directory-rule, package-mismatch, repair
priority: 100
---

## Java package-directory rule (load-bearing)

Java enforces an absolute rule: the `package` declaration at the top of a `.java` file **must exactly match the source file's directory path** under `src/main/java/`.

- File `src/main/java/com/example/dto/CategoryDto.java` MUST declare `package com.example.dto;`
- File `src/main/java/com/example/model/CategoryDto.java` declaring `package com.example.dto;` is a **compile error** — `javac` rejects with "class CategoryDto is public, should be declared in a file named ..." or a package-mismatch diagnostic.

This is a Java language guarantee, not a convention. `javac`, `gradle`, `maven`, IDEs all rely on it.

**Repair implication.** When a file's declared package doesn't match its directory, the question is "which is wrong — the package, or the location?" The answer is **almost always: the location is wrong**. The file should be moved (or deleted if a duplicate already exists). Editing the package declaration to match the wrong directory is the failure mode this rule prevents.

### Move vs. delete — the dedup check

For each mis-located file, before choosing `move_file`, **check whether a structurally-equivalent file already exists at the target directory**. If yes, choose `delete_file` instead of `move_file`. The duplicate at the wrong location is debris; the correct one already exists.

A file at `model/BillRepository.java` declaring `package com.example.repository;` is a duplicate if `repository/BillRepository.java` already exists. Delete the duplicate. Do not move-and-overwrite.

A file at `model/DashboardController.java` declaring `package com.example.controller;` is a *unique* misplaced file if `controller/DashboardController.java` does NOT exist. Move it.

**Heuristic in one line**: if the target path already has a file → DELETE; if not → MOVE.
