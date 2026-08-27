# library-seed/ — Status after 2026-05-19 architectural reset

**Read this before adding or deleting entries.**

This directory previously held an ad-hoc collection of 305 markdown entries auto-bridged from `knowledge-packs/templates/` and the other `knowledge-packs/<pack>/` directories. That bridging was the wrong shape: it duplicated content that belongs in templates (still in `templates/`) or in the Lucene `docs` collection (already populated on the host), and it conflated three distinct concepts that should stay separate.

See `SPEC_CODEZAIKU_LIBRARY.md` at the repo root for the canonical library architecture going forward.

## What's left here (8 entries) and what each is for

These are the entries that survive the 2026-05-19 cleanup because they are **Pi-style pre-injection content** — small, project-scoped, load-bearing guidelines that anchor the loop runner's behavior. They are NOT the framework-knowledge library (per `SPEC_CODEZAIKU_LIBRARY.md` that library lives in Ocean / Lucene and is consulted via MCP tools).

| File | Purpose |
|---|---|
| `task-shape/repair-mode-protocol.md` | The encounter→retrieve→implement→observe→refine loop description. Architectural anchor. |
| `task-shape/forbidden-patterns.md` | Hard rules (no version downgrades, no `javax.*` in Spring Boot 3.x, etc.). Anti-pattern enforcement. |
| `framework/java-package-directory-rule.md` | Load-bearing rule per the 2026-05-19 ablation: Java package declarations must match directory paths. Dedup heuristic for MOVE-vs-DELETE. |
| `framework/java-spring-boot-3.5-project-shape.md` | Standard Spring Boot layout. |
| `framework/java-spring-boot-3.5-conventions.md` | Jakarta EE imports, constructor injection, JPA, Gradle DSL. |
| `framework/python-import-rule.md` | Python's module-path / filesystem-path mapping. Typo-vs-misplaced heuristic. |
| `framework/python-fastapi-project-shape.md` | Standard FastAPI layout, Pydantic-vs-SQLAlchemy distinction. |
| `framework/python-fastapi-conventions.md` | Async-by-default, Pydantic patterns, APIRouter idioms. |

## What does NOT belong here (per the new spec)

- **Framework reference content** (javadoc, API surfaces, language specs) — those go into the Lucene `codezaiku_library` collection, ingested from DevDocs.io, consulted via `library_search` MCP tool. Not here.
- **Templates** — those stay in `knowledge-packs/templates/` (they were never library content; the bridging was the mistake).
- **Pattern knowledge-packs** (When-to-Use / Pattern / Pitfalls) — those stay in their original `knowledge-packs/<pack>/` directories and get indexed into the Lucene `docs` collection by `DocIndexer`. Not here.

## How this directory will be used in the future architecture

These 8 entries are the **CLAUDE.md / AGENTS.md-equivalent** content that the loop runner injects as pre-task system-prompt context. They orient the model on:

1. The task-shape (what mode are we in — Create/Maintain/Repair, what's the loop)
2. The forbidden patterns (don't do X)
3. A few load-bearing framework rules that aren't reliably in the model's training (the package-directory rule, the import rule)

When the model needs **actual framework knowledge** beyond these anchors, it calls `library_search` / `library_lookup` MCP tools (Phase L1 implementation pending) and gets DevDocs-style references from the Lucene library collection.

## Don't restore the deleted 305 entries

If you find yourself thinking "but we had auto-generated entries for every framework template, that was useful" — read `SPEC_CODEZAIKU_LIBRARY.md` and the project memory `project_library_diagnosis_2026_05_19.md`. The auto-bridged entries duplicated content that already lives elsewhere (templates stay templates; knowledge-packs are indexed into the `docs` Lucene collection). Re-creating them creates the same divergence the 2026-05-19 reset corrected.

The right way to grow framework knowledge is via the Phase L1 ingestion pipeline (DevDocs.io → Lucene `codezaiku_library` collection → MCP tool retrieval), not via more markdown files in this directory.
