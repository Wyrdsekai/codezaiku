# OpenRCA v1 — CodeZaiku local 9B, full benchmark

**Final: 12/335 = 3.58% strict accuracy** (their offline scorer), 335/335 answered.

Their dataset, their scorer (`main/evaluate.py`), agent jailed to `telemetry/` read-only (the answer key —
`record.csv` and `query.csv`'s `scoring_points` — is physically unreachable, audited). Drive = 9B at
`:8200` on ${CP_HOST} (one A6000, single-GPU-pinned). Persistent python session ON = parity with OpenRCA's
own reference agent (`InteractiveShellEmbed`). Baseline config otherwise: precheck OFF, runbook OFF (both
are infra affordances irrelevant to telemetry RCA), REQUIRE_ANSWER ON.

## Result

| system | n | answered | strict | partial (>0) | mean score |
|---|---|---|---|---|---|
| Telecom | 51 | 51/51 | 3/51 = 5.88% | 15.69% | 0.105 |
| Bank | 136 | 136/136 | 5/136 = 3.68% | 8.82% | 0.059 |
| Market cloudbed-1 | 70 | 70/70 | 2/70 = 2.86% | 5.71% | 0.043 |
| Market cloudbed-2 | 78 | 78/78 | 2/78 = 2.56% | 5.13% | 0.038 |
| **TOTAL** | **335** | **335/335 (100%)** | **12/335 = 3.58%** | **8.36%** | — |

## Against the published v1 leaderboard (n=335)

| model | strict |
|---|---|
| Claude 3.5 Sonnet | 11.34% |
| GPT-4o | 8.96% |
| **CodeZaiku local 9B** | **3.58%** |
| Llama 3.1 (open model) | 3.28% |
| Gemini 1.5 Pro | 2.69% |

A local 9B on a 16GB-tier box lands just above the open-model reference, below the frontier hosted models.

## What the number means

The load-bearing observation is the **100%-answered / 3.58%-correct split**. The harness reliably drives
every one of the 335 investigations to a scored conclusion; the model's *diagnoses* are mostly wrong at this
scale. The remaining gap is model capability, not plumbing — which is the honest place a 9B sits next to
frontier models. Per-system mean score falls monotonically as telemetry file size rises (Telecom 1.2 GB →
0.105, cloudbed-2 3.6 GB → 0.038): the largest traces are the hardest case for a local drive, and the
accuracy tracks that.

## Provenance — why the earlier numbers are void

An earlier attempt reported Telecom 9.80% / Bank 1.47% and a "the model can diagnose, the harness can't
finish" story. **All of it was an artifact of a crashing harness** and must not be cited. Three chained
bugs, each hidden behind the previous:

1. `OpsLoop` had no context compaction. Past the 32768-token window llama.cpp returns HTTP 400, `DriveClient`
   throws, and the JVM died mid-investigation — a missing answer that the scorer counts as a wrong one.
   **129 of Bank's 136 queries died this way.** The failure tracked telemetry size, which disguised the
   harness bug as a model ceiling.
2. Market never ran: `candidates()` built `basic_prompt_Market/cloudbed-1.py` from the slash in the dataset
   name (their file is `basic_prompt_Market.py`), threw before query 1, and `run_all4.sh`'s `grep` filter
   swallowed the trace. Cost 148 of the 335 queries silently.
3. Surviving runs ended in silence — the model explored to the turn cap and never concluded.

Fixed in commits `ac186e1b`, `9d9b8ca2`, `7ff3eb7c`, `ea00db41`, `9d014ef6`: shared `OpsContext` structured
compaction (also fixes the same latent crash in `RemediationLoop`); context overflow is authoritative
(catch the 400 → shrink → retry, never die); kickoff + checkpoint pinned across compaction so the required
answer schema survives; end-game pressure + a forced final conclusion so no run ends empty; persistent
session on. A missing answer now logs `rc` + the exception, so a crashing harness can never again be mistaken
for a stumped model.

Artifacts: `"${CP_WORK:-/opt/codezaiku}"/openrca-FINAL-335/` (preds/report/score/log × 4). Broken-harness run preserved at
`"${CP_WORK:-/opt/codezaiku}"/openrca-BROKEN-HARNESS/` for comparison.

---

# The 2×2: does CodeZaiku's ops loop earn its keep?

The sharp version of the question got asked — *"is it just the model or with codezaiku?"* — so we drove
**OpenRCA's own published reference agent** with **our 30B**, same data, their scorer. Same model on both
sides, so the model confound cancels. Telecom, n=51. Archived: `"${CP_WORK:-/opt/codezaiku}"/openrca-2x2/`.

| arm | strict | partial | mean | completed |
|---|---|---|---|---|
| our harness + 9B (dense) | 3/51 = 5.88% | 15.69% | 0.105 | 51/51 |
| our harness + 30B-a3b | 2/51 = 3.92% | 13.73% | 0.082 | 51/51 |
| **THEIR reference agent + 30B-a3b** | **3/51 = 5.88%** | **17.65%** | **0.104** | 49/51 |

## Finding: no detectable diagnostic advantage

Their much simpler agent **beat our 30B arm on strict, partial and mean** — while crashing on two queries we
survived — and **tied our best arm**. At n=51 with 2–3 correct, all of these are noise-equivalent, so the
honest claim is *no detectable difference*, not *they win*. Our elaborate loop ≈ their lean agent ≈ our 9B ≈
**~4–6%**. On real RCA at this model tier, **the harness is not the lever.**

This is the **second** control to say so. The first: a no-harness baseline beat our loop 19/25 vs 18/25 on our
own ops fixtures. That was filed as a one-off; it was a reproducible finding.

**Disproven:** that the loop's elaborations (depth nudge, relevance nudge, grounding verify, forced
conclusion, answer-fidelity nudge) improve diagnosis. They yielded 24/24 on fixtures we authored ourselves —
which predicted none of this, because those fixtures measured a far easier task (*name the fault family, from
six, on one box, with a precheck already pointing at the anomaly, for a fault we planted*).

## What survives: robustness — which bought zero accuracy

Their `executor.py` caps **each** observation at 16384 tokens but never caps total history and never compacts.
Over `max_step=25` that reached a **544,795-token** request and died. (That would exceed Claude's 200k too —
it's the *interaction*: our 30B dumps whole dataframes; their agent assumes a model disciplined enough not to.
At their design point — Claude/GPT-4o, 128–200k — it works and scores 11.34%.) Their runner also has no
per-query isolation, so one 400 killed all 51; we restored isolation via their own `--start_idx/--end_idx`
(their `eval_file` is read-back-and-append) with **zero edits to their code** — steelmanning their arm.

Ours ran **51/51, zero crashes**, on the model that kills theirs. But robustness converted to **no accuracy**:
51/51 → 2 correct, vs their 49/51 → 3 correct. The "completion bottleneck" narrative is dead in both
directions.

## Untested, and still ours

Closed-loop remediation with harness re-verification. Their agent is **diagnosis-only**, so nothing here
touches that claim — the one part of the ops thesis left standing, and the part no external benchmark checks.

## Caveats stated plainly

- **Arm A is confounded.** `qwen3-coder-30b-a3b` is MoE with ~3B *active* params, a different family, and
  coding-specialised — against a dense 9B that is not a size-up. It is a *different-model* control, not a size
  control. No dense larger model exists on the box, so a clean size control needs a download. **Arm B is
  unconfounded** (same model both sides) and is the load-bearing result.
- **Integrity audited, not assumed.** Their IPython kernel is unsandboxed and *could* read the answer key
  (ours physically cannot) — an asymmetry favouring them. `audit_armB.sh`: **0 of 64** trajectory notebooks
  touched `query.csv` / `record.csv` / `scoring_points`. Their arm is clean.
- Their harness was **unmodified**; the model was redirected purely through their hardcoded
  `_MODEL_KEY="gemini3pro"` config lookup, and run on their published defaults (25 steps × 5 turns).
