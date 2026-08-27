# CodeZaiku coding harness — M1–M9 maintenance battery findings (2026-07-24)

The "differentiator" thesis: 70–90% of real dev work is *maintaining* existing code, not greenfield.
M1–M9 measure the shipping-tier (9B) coding familiar on that work, with a 30B reference and library A/Bs.
Fixtures = injected faults / tasks on the email-intel app; graders are held-out + model-blind; K=8 each.

## 9B shipping-tier capability map (task-aware default)
| Task | 9B | 30B ref | dominant failure mode |
|---|---|---|---|
| M9 API-migration (on_event→lifespan) | 8/8 | 8/8 | — |
| M3 add-feature (/api/search) | 7/8 | ~6–7/8 | — |
| M5 incident (symptom-only, thread dates) | 7/8 | — | collateral test breakage |
| M1 bug-fix (from stack trace) | 5/8 | ~8/8 | last-mile fidelity (dropped arg) |
| M4 test-coverage (mutation-graded) | 5/8 | — | writes tests that fail on correct code |
| M2 security (path-traversal + cmd-inj) | 4/8 | 6/8 | over-secures → breaks function |
| M7 refactor (behavior-preserving dedup) | 0/8 | 0/8 | no transformation (see below) |
(M6 dep-upgrade, M8 perf DEFERRED — not gradeable without contrivance.)

Read: the shipping 9B does real maintenance (5/7 task types at 50–100%); misses are FIDELITY, not
inability. 30B's clear edge is only on localization/surgical tasks (M1, M2).

## The card verdict (library A/Bs — grounding OFF vs ON)
| Task | OFF | ON | effect |
|---|---|---|---|
| M1 bug-fix | 8/8 | 3/8 | HURTS, p=0.026 (significant) |
| M2 security | 4/8 | 4/8 | neutral |
| M3 add-feature | 5/8 | 7/8 | weak, p=0.57 (NOT significant) |
| M9 migration | 8/8 | 8/8 | NO lift (even the knowledge task) |

**The library's error-grounding cards never significantly help coding maintenance** — they hurt pure
localization (bug-fix) and add nothing even where they theoretically should (migration/knowledge). The
committed task-aware gate ("cards off for maintenance") is validated; a finer "localize-vs-write" router
is unnecessary (no task class where cards significantly help). The library's ONLY coding value is the
always-on project-shape (localization). Contrast with SRE: SRE faults recur → cards generalize → library
central; coding is idiosyncratic (localization-dominated) → the model's own reading beats any card.

## M7: a structural finding, not a bug
0/8 on BOTH tiers; NOT a model-tier gap, NOT a keep-best-green revert (RESTORE fired 0×). Cause:
refactoring is the one task with NO test-oracle signal — the code is already green, so a test-driven loop
has no red→green gradient pushing restructuring. Oracle-signal-free tasks (pure refactor) are the blind
spot of a test-driven loop. A future lever would be a non-test signal (e.g. a structure/complexity delta).

## Shipped
Task-aware library gate + keep-best-green → commit 11f5f1c4 on dev.

## Regression check (2026-07-24)
The committed changes are surgically scoped and provably inert for ML/greenfield: the gate suppresses
cards only for MAINTENANCE (≥3 source files at start); ML/greenfield seeds are 0-source → classify
"greenfield → cards ON" (verified empirically for both an ML fixture and a coding skeleton). keep-best-green
skips dev-gated (ML) runs entirely and only ever ships a green state for greenfield coding. → no regression.
