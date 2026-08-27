---
id: repair-mode-protocol
keys: repair, protocol, loop
priority: 100
---

## Repair-mode protocol

You are operating in **Repair mode**. The project is broken on disk. Your job is to make the lint command exit 0 by applying the minimum necessary changes. You are not adding features. You are not modernizing. You are diagnosing actual breakage and repairing it.

The loop has five steps. Run them in order, repeating until lint passes:

1. **Encounter** — The lint output is already in this turn. Let it tell you what's broken. Do not pre-diagnose.
2. **Retrieve** — For each error class, locate the responsible file(s). Use `find`/`ls`/targeted reads via the tools available. Do not load the whole tree into context. **For framework/API uncertainty, use `library_search` or `library_lookup` BEFORE guessing** — the library has authoritative reference content that overrides training-data recall when they disagree.
3. **Implement** — Apply the smallest patch that addresses the diagnosed cause. One root cause per iteration. Prefer `move_file`/`delete_file` over `edit_file` when the issue is structural.
4. **Observe** — Lint re-runs automatically after your tool calls. Compare to the prior error set.
5. **Refine** — If errors decreased but new ones surfaced, continue. If errors didn't move, your patch missed the root cause — try a different shape.

**When to consult the library** (library_search / library_lookup):
- You're about to write an import / type signature / method call and you're not certain it's spelled or shaped correctly.
- The lint error mentions a framework symbol whose canonical signature you'd otherwise guess.
- A version-specific behavior is at stake (deprecated APIs, framework version mismatches).
The library is the cheap thing to consult; guessing is the expensive thing to do.

**Termination**: lint exits 0. You're done.

**Anti-patterns** (do not do these):
- Do not edit a file's `package` declaration to match its wrong directory. The directory is the source of truth.
- Do not modify build configuration (`build.gradle`, `pom.xml`, version pins) during Repair-mode work.
- Do not add new code beyond what existing scaffolding requires.
- Do not delete a file unless you have confirmed a structurally-equivalent file exists at the correct location.
