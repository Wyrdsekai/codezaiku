# Limitations and Measured Results

Everything CodeZaiku can and cannot do, with the numbers and how they were produced. This document
exists because the project's central claim is about **measurement discipline**, and a project that
claims that has to publish its weak results first.

Reference tier is a **9B local model** unless stated. A "30B" below is `qwen3-coder-30b-a3b` — an MoE
with roughly 3B active parameters, so it is *not* a clean model-size control for knowledge tasks.

**Reading the numbers.** *K* is how many times a measurement was repeated on identical inputs — "38% at
K=8" means eight independent runs of the same task, 38% of which succeeded. It matters because a local
model is not deterministic: the same configuration on the same inputs scored 8, 5 and 7 out of 30 on
three separate runs of one suite. That spread is the noise floor, and it is why single runs are reported
as smoke tests rather than results.

---

## The one finding that matters most

**The harness is sound; the model is the wall.** Established three separate times by swapping only the
model and changing nothing else:

| Domain | Control | Result |
|---|---|---|
| Coding | same harness, 9B → 30B | 30B produces correct, booting, per-concern code across Spring Boot, FastAPI, GraalJS, ratatui. Residual failures are localized code bugs, not systemic. |
| ML | 9 residual failures re-run on 30B | 6 pass ⇒ model-size-bound. **Zero were harness/method/threshold defects.** |
| Ops diagnosis | OpenRCA's own agent vs our harness | ≈ equal. Our loop adds no diagnostic value. |

If you take one thing from this repo, take the method, not the scores.

---

## Where CodeZaiku is weak

Stated first, deliberately.

**Two kinds of limit, and they are not interchangeable.** Some ceilings move when you attach a better
model and nothing else changes; those are *capability-bound*, and the section above is the evidence
that they exist. Others are properties of the harness itself and would still be there with a frontier
model behind them. Each entry below says which it is, because the remedies are completely different: a
capability-bound number is waiting on weights, a harness limit is waiting on us.

One consequence worth stating plainly, because it cuts against the usual reading: a *better* model does
not always look better to a fixture. Our own platform-acceptance suite capped a task at 8 turns, which
suited a 9B that rushes; the 30B works the same task methodically in 9–16 turns and would have failed
that case five times out of five. Where a number here is capability-bound, check the harness is not
quietly measuring the model's style before reading it as a ceiling.

**Coding is capability-bound.** *(model, not harness)*
- SWE-bench Lite (django subset): **≈1/8 (12.5%)** on the 9B — the open-small-model range.
- M1 bug-fix-from-stack-trace maintenance task: **≈38% at K=8, on the 30B.** High variance. Every run
  that reached a green state passed; the failures never got there.
- Refactoring (M7): **0/8 on both tiers.** Structural, not incidental — refactoring has no test oracle,
  so the loop has no signal to converge on.
- CodeZaiku is **not** a replacement for a frontier coding agent and does not claim to be.

**Incident diagnosis on a public benchmark is poor.** *(model, not harness)*
- OpenRCA v1, full 335 tasks, their scorer, 100% answered: **3.58% strict (12/335).** The gap is the
  model. Every earlier number we produced on this benchmark was void — it was measuring our own crash
  rate, not accuracy.

**Code review finds roughly a quarter of what human reviewers comment on.** *(both — the
harness's matching is location-only, and the finding rate moves with the model)*
- Scored against **real review comments on 25 merged PRs** across 8 repositories and 6 languages
  (Python, Java, Go, Rust, TypeScript), 3 runs each, 146 human comments: **per-PR mean recall 23.8%**,
  with the reviewer able to read the project it is reviewing and the PR sample drawn without regard to
  how many comments a PR carried.
- Report **per-PR**, not per-comment. One PR in this sample carried 33 human comments and most carried
  one, so comment-weighting lets a single heavily-reviewed change dominate — by that measure the same
  data reads 13.2%.
- Matching is **location-only** (same file, ±5 lines), so a finding on the right line for the wrong
  reason counts. This is an **upper bound on agreement**, not accuracy.
- Human comments are not a defect list. Hand-reading 13 missed ones: ~5 were preference or wording a
  defect-focused reviewer is right to skip, 2 needed context outside the diff, ~4 were real defects.
- **Output varies run to run.** Roughly a third of PRs are found in some runs and not others;
  `CODEZAIKU_REVIEW_PASSES` unions repeated passes to recover part of that.
- An earlier figure of 32% described a sample that accidentally contained only single-comment PRs from
  two Python projects. It is not comparable to the number above; the sampler was fixed.

**Nothing has run unattended for long.** *(harness — missing evidence, not a ceiling)* The longest continuous run is minutes. There is no soak
evidence. Treat "production-ready" claims as absent rather than implied.

**Two known model-adoption ceilings.** *(model, not harness)* A 9B will not adopt a novel tool no matter how it is instructed
(measured: goal instruction + advertisement + a mid-loop nudge produced zero calls), and neither tier
could enumerate obscure foreign-language entities for a broad search task.

---

## Where it is strong

**Guardrails that demonstrably prevent damage** — the best-evidenced claim here, because it has a
counterfactual. A model corrupted `postgresql.conf` and bricked a database:
- rollback **enabled** → snapshot restored, stack green
- rollback **disabled** → service dead until manual repair

Across ~105 fault-injection runs including every failing one: **0 harm to bystander services.**

**Closed-loop remediation on a six-class fault suite:** 9B **30/30** localized and fixed, 30B **30/30**,
0 harm, K=5 per class.
> **Caveat that must travel with that number:** the suite is *ours* — our stack, our injected faults, our
> oracle. It is not an external benchmark. Read it as "the pipeline works end-to-end on faults we can
> generate", not as a competitive score. The external number is the 3.58% above.

**ML pipelines.** 22 fixtures, model-blind held-out graders that boot the artifact and exercise it. A 9B
drives fine-tuning, alignment (DPO/KTO/ORPO/GRPO/PPO), distillation, quantization and deployment to
artifacts that pass. Failures cluster on last-mile fidelity, not on the technique.

**Library cards close specific ML gaps** — positive controlled flips on the 9B:
- ORPO **0.27 → 1.0000** (the card was orphaned *and* wrong: attention-only instead of all-linear LoRA)
- recommender **0.007 → 0.0848** (orphaned card, and the model was overwriting its own input data)
- distillation: 250-turn cap without its card → **PASS at turn ~65** with it

A systematic finding came out of this: **13 cards existed as files with no trigger**, so they were never
pushed. An unwired knowledge base is indistinguishable from an empty one.

The benefit is **domain-specific, and the harness treats it that way.** The same card mechanism measured
neutral to negative on coding-maintenance tasks (one A/B arm significant *against*, p=0.026), so
error-grounding injection is switched off for maintenance projects rather than applied everywhere. A
knowledge layer that helps one domain is not evidence it helps another, and this one is wired to the
domain where it was demonstrated.

**ACI edit precision.** Forgiving edit matching plus LSP symbol-span fallback took edit hard-miss from
**~30% to 4–9%** — which then made a planned fine-tune unnecessary, because reading the failures showed
none of them were edit-precision failures any more.

**Research levers**, each a controlled A/B:
- query-relevant page excerpts: SimpleQA **22.2% → 38.3%** (p=0.0425, replicated)
- deadline turn: research conclusion rate **33% → 100%** (p<0.0001)
- WideSearch (100 EN tasks, official judge): mean item-F1 **0.145**

**Injection resistance.** Container logs are attacker-writable and feed the operator's reasoning. A single
injected log line steered localization **40/40 — every payload, every run, on both tiers.** Scale is not a
defence. After a structural fix (the model may only choose among sensor-flagged services): **0/80
product-effective**, with no loss of localization accuracy.
> Bounded claim: 8 payloads, one attack channel, no adaptive attacker. This is "we measured one channel
> and closed it", not "injection-proof".

---

## Driving it as a backend — what to plan around

These are the sharp edges of the integration surfaces, all measured.

**`files[]` can be a lower bound.** The run result lists what changed, from a write ledger plus a
before/after `git status` delta. The ledger is exact for tool writes, but our shell is unscoped, so a
`sed -i` writes behind it; git catches those *when the workspace is a repo*. In a non-git workspace it
cannot, and `filesComplete: false` with `filesSource: "ledger (shell ran; no git to reconcile)"` says
so explicitly. Read those fields rather than trusting the array.

**Build artifacts appear when nothing declares them ignorable.** A run that executes pytest in a
workspace with no `.gitignore` will list `__pycache__/*.pyc`. We do not apply a filter of our own —
git's ignore rules are the project's own statement about what is noise, and our list would eventually
drop a real file.

**The CLI path does not gate commits; ACP does.** CodeZaiku's harness authors no commit and never
reports a `gitRef`. But a task that *asks* the model to commit will get a commit through the shell.
Over ACP that request is routed to `session/request_permission` so your client decides; over the CLI
subprocess there is no client to ask, and nothing is gated.

**On Windows, a killed CLI run reports nothing.** Linux and macOS emit the usual result document with
`"interrupted": true` on SIGTERM. Windows has no SIGTERM, and MSYS emulates signals only between MSYS
processes, so a native `java.exe` never receives one — measured: exit 143, zero bytes. **ACP is
unaffected**: `session/cancel` is a protocol message and behaves as it does everywhere.

**The oracle costs one extra test-suite run per task.** `status` is only `success` when the project's
own tests actually ran and passed, which means running them. Negligible on small fixtures, minutes on
a large repo.

---

## Things we deliberately do not claim

| Not claimed | Why |
|---|---|
| Production-ready / battle-tested | no soak; longest run is minutes |
| A containment success rate | 6/6 at K=3 is a smoke test; 0 failures in 3 runs is consistent with a 63% true failure rate |
| Injection-proof | one channel, 8 payloads, non-adaptive |
| Works on any stack | ran unmodified against exactly **one** second stack |
| Competitive research performance | no frontier comparison on our setup; our numbers are absolute, not ranked |
| The blackboard architecture | designed, never built, removed — see ARCHITECTURE.md |
| A code-review accuracy figure | 23.8% is location-only agreement with human comments; it is not precision, and human comments are not a complete defect list |
| That context compaction is tuned | it barely runs — measured peak 26% of a 32k window against a 70% trigger, so the tuning question is moot rather than settled |
| A read-cap figure for other task shapes | the 12k cap beat a 30k cap by ~24% of tokens at identical success, on **one** fixture class at one window size |
| That every command is covered by automated tests | the unit suite covers components (guards, rollback, redaction, path scoping, gates); end-to-end CLI coverage is `--version`, `doctor`, the test oracle, `run`, `acp`, `mcp` and `secure` via the platform script, run on Linux, macOS, WSL2 and Windows, plus the ops surface via fault-injection batteries. `review`, `research`, `triage`, `investigate`, `watch` and `serve` are exercised by hand, not by a suite |
| That multi-pass review is reliably better than single-pass | it reaches the predicted union ceiling (52% vs 48%), but at n=25 the intervals overlap the single-pass figure |

---

## How the numbers were produced

- **External oracles wherever they exist** — SimpleQA, WideSearch, SWE-bench, OpenRCA, AIOpsLab/SREGym.
  A self-authored fixture measures your idea of the answer.
- **Model-blind, held-out graders.** The grader boots the artifact and exercises it; it never reads the
  model's own account of what it did.
- **Two-sided oracles** for fault injection: the fault must be fixed *and* nothing else broken. One side
  alone is trivially gamed — `docker kill` "contains" every intrusion perfectly.
- **Baseline-first, controlled A/B, K≥5**, Fisher exact for significance.
- **Noise floor is published:** identical config, identical inputs, three runs → 8, 5, 7 correct out of
  30. A ten-point difference at K=30 on that suite is nothing. K=1 and K=3 are smoke tests.

### Results we invalidated ourselves

Included because they are the reason to trust the rest:

- A **66.7% (p=0.004)** research result that was an artifact — the grader was reading the run *log*, not
  the answer, so it scored "did the gold string appear anywhere in the console output". Corrected to no
  significant effect.
- **Every early OpenRCA number**, which measured our crash rate rather than accuracy.
- A **30/30 → "27/30"** ops score that was a reporting bug: the settle-aware re-verify passed but its
  verdict never reached the audit trail or the outcome.
- Two full A/B runs voided by a **rate-limited search backend** — the arms looked equally bad because
  neither could fetch anything.
- A security fixture where the honest answer scored as a miss (the "implant" was a copy of `sleep`), a
  benign arm contaminated by the previous arm's evidence, and a grader that **failed the ideal outcome**.
- Posture probes gated on **exit code** rather than output, silently discarding every finding — seven
  privileged containers reported as none.

The pattern is consistent enough to be the project's main lesson: **the dominant failure mode is a broken
instrument, not a weak model.** Before believing a number, ask what would look identical if the
instrument were broken, then check that by hand.


## Chat (0.2.0)

- **macOS: ctrl-C turn-cancellation is unverified when stdin is a pipe** — the signal is consumed
  without stopping the turn. Interactive terminal use is the supported path on macOS.
- **`codezaiku v1` cannot ask permission** — the wire has no mid-turn callback, so it serves
  read-only tools by design. Anything that writes or runs belongs in the terminal chat.
- **`/undo` cannot cover background work** — a `run_background` or `delegate` step mutates after
  its turn ends, and the undo report says PARTIAL for any range containing one rather than
  promising a rewind it cannot deliver.
- **Web research needs a configured backend** — with neither `CODEZAIKU_SEARXNG` nor
  `CODEZAIKU_BRAVE_KEY` set, chat has no web tools and will say so rather than invent sources.
