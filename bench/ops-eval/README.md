# CodeZaiku ops diagnosis eval

The scored **fault-library-with-red-herrings** for CodeZaiku's ops mode (PLAN_CODEZAIKU_OPS.md §7, the
opensre `tests/synthetic/` shape). It measures the one thing no telemetry-only ops tool measures: **did the
diagnosis find the TRUE root cause AND resist a planted distractor** — RCA quality, not task completion.

## What a scenario is

Each `fixtures/<NNN-name>/` is a single-box fault:

- `Dockerfile` — a HEALTHY baseline image (the box before it broke). No fault baked in.
- `setup.sh` — injects the fault at runtime into a running container, and plants a **red herring** (a
  distractor signal — high CPU, a scary-but-historical log line — that a shallow diagnosis blames instead
  of the real cause). Removed from the container after it runs so the model can't read the answer.
- `scenario.yml` — the incident text handed to the ops loop (what the operator would say), plus a record
  of the adversarial signals.
- `answer.yml` — the ground-truth **oracle**: `root_cause_category` (+ aliases), `required_keywords`,
  `required_keyword_groups`, **`forbidden_keywords`** (the distractor the RCA must NOT blame),
  `forbidden_category`, `required_evidence_sources`, `max_investigation_loops`.

## How a run works

`run_scenario.py <fixture> -k K --precheck on|off` for each rep: build the image → start a fresh
container → inject the fault → run `FamiliarMain ops docker://<cid>` (the lean diagnosis loop) → capture
the structured `InvestigationResult` JSON → score it with the pure `scoring.py` oracle. Reports N/K.

- `--precheck off` measures the model's **raw** diagnosis; `--precheck on` adds the deterministic
  pre-narrow catalog. The off-vs-on delta is the precheck's real lift.
- `scoring.py` is import-free and gates: category match, required keywords/groups, **not blaming a
  forbidden distractor** (dismissing it is fine), evidence sources, and the trajectory budget.

## Honesty rules (learned the hard way here)

1. **Verify every fault is REAL before trusting a number.** Scenario 001's first "broken" nginx config
   was actually valid (nginx parsed the missing-semicolon lines as one multi-arg directive), so `nginx -t`
   passed and the model was *right* — the eval was measuring a non-fault. Always confirm the fault
   reproduces (`nginx -t` fails / the port is held / the dependency is down) independently.
2. **Never leak the answer.** The injection script is deleted post-inject; nudges must not name the answer
   category as a menu; prechecks must be high-precision (a false `disk_full` actively misled the 9B).
3. **Read the produced RCA, not just the gate.** A conclusion can carry the right category slug while its
   text blames the herring — the oracle fails that.
4. **K≥3, multiple fault types.** One scenario is noise; the verdict is across the fault library.

## Current scenarios
- `001-nginx-badconfig-cpu-herring` — nginx won't start (invalid directive); herring = high CPU.
- `002-port-conflict-log-herring` — app can't bind :8080 (port held); herring = historical traceback.
- `003-dependency-down-cpu-herring` — app up but 500s (DB :5432 down); herring = high CPU.
