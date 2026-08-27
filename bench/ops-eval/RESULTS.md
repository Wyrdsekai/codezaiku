# CodeZaiku ops-mode results

First honest measurement of the deployable **9B** on OPS DIAGNOSIS — RCA-matches-true-cause AND
resists a planted red herring — which had never been measured (all prior ops numbers were Terminal-Bench
*task completion*). Method: `bench/ops-eval` scored fault-library (see README). Drive = 9B at :8200 on
${CP_HOST}. Each fault verified to genuinely reproduce before trusting a number.

## Diagnosis — final, 8 scenarios / 6 fault families, shipping config (precheck ON + runbook PUSH)

| scenario (fault type)                         | result (K=3) |
|-----------------------------------------------|--------------|
| 001 nginx bad-config                          | 3/3          |
| 002 port_conflict                             | 3/3          |
| 003 dependency_down                           | 3/3          |
| 004 cert_expired                              | 3/3          |
| 005 disk_full                                 | 3/3          |
| 006 cascade 502←app←disk (2-hop)              | 3/3          |
| 007 cascade 502←app←cache←config (**3-hop**)  | 3/3          |
| 008 multi-fault (port conflict + disk noise)  | 3/3          |
| **total**                                     | **24/24**    |

Correct category, grounded in real evidence (`nginx -t`/`ss`/`:5432`/`openssl`/`df`/`cache.conf`), red
herrings dismissed, multiple-fault attribution correct, causal chains traced to the true root. Read from
the RCA prose, not just the gate.

### What each lever contributed (each MEASURED, off-vs-on)
- **Precheck** (accurate, deterministic pre-narrow): raw single-hop 14/15 → 15/15, and faster. A NOISY
  precheck HURTS (an early false `disk_full` from the container overlay/`/etc/hosts` drove it to 0/3 until
  fixed) — high precision is mandatory for a 9B.
- **Iterative depth nudge** (2-hop five-whys, structural): the 9B's dominant failure was stopping at the
  SYMPTOM (`service_down`) with the root evidence in hand. Up-to-2 nudges (the 2nd: "read the failed
  component's OWN log; only an external un-introspectable dependency is a root") carries it deeper.
- **Runbook PUSH — the multi-hop lever.** The honest, decisive finding: a 9B needs PUSH, not PULL.
  - As PULL (`fetch_runbook` tool + catalog listing): **INERT** — measured 0 fetches in 25 iterations; the
    listing is pure overload and REGRESSED 007/008. Kept only as the baseline.
  - As PUSH (auto-inject the matched PROCEDURAL runbook — web-502 / service-down only, never a
    specific-root runbook that could leak or mislead): **cracked the multi-hop ceiling** — 007 (3-hop)
    **1/3 → 3/3** (all reach `bad_config` via the cache's own log), 006 (2-hop) clean 3/3 and faster, 008
    (multi-fault) 3/3. Push is a strict NO-OP for non-matching incidents (001–005 don't mention 502/down →
    no injection), so it cannot regress the single-hop cases. Consistent with the project's push-not-pull
    theme (the library, the ML cards).

### Honest caveats
- **K=3 has real variance** — OFF baselines flipped run-to-run (006 OFF was 0/3 one session, 3/3 another).
  The 24/24 AUTO sweep is clean, but the precise per-lever lift magnitude is soft; the DIRECTION (push
  cracks multi-hop; pull is inert) is robust because 007's 3-hop never exceeded 1/3 without push across
  multiple sessions and is 3/3 with it.
- **007's 3rd hop needed a discoverability breadcrumb** — a crashed process leaves no `ps` trace, so the
  app must name where its dependency logs (real systems do this via journald / known log paths). Without
  it the model reasonably assumed the `:6379` port meant redis and gave up. The fix is fair, not a leak.

- Every pass has the **correct category**, is **grounded** in real command output (`nginx -t`, `ss`,
  `:5432`), and **dismisses the red herring** (CPU / historical traceback) with a correct causal
  explanation — read from the RCA prose, not just the gate.
- The one raw miss (001-off) fell for the CPU herring (`resource_exhaustion`); the deterministic precheck
  closes it. Iterations 4–25.
- **This is the LEAN ops loop.** The heavy coding decompose loop added nothing on Terminal-Bench ops
  (3–4/18, below a lean-discipline baseline of 6/18). The ops-specific lean loop — verification-first
  conclusion gate + one-shot symptom-DEPTH nudge (five-whys) + grounding + accurate prechecks — is what
  moves the 9B.

## What moved the number (each measured, not assumed)
- **Symptom-depth nudge**: the 9B's dominant raw failure was concluding "service_down" without finding
  WHY (it had `nginx -t` evidence in hand). A one-shot nudge — "a down service is a symptom; find the
  underlying cause" — pushes it to the real cause. (Nudge must NOT name candidate categories — that leaked
  the answer and was removed.)
- **Accurate prechecks lift; noisy prechecks HURT.** A false `disk_full` (container overlay / `/etc/hosts`
  bind-mount reflecting the host fs) drove precheck-ON to 0/3 before it was fixed. High precision is
  mandatory for a small model.

## Honesty corrections made along the way (see git log)
1. Scenario 001's first "broken" nginx config was actually VALID (missing semicolon parsed as a multi-arg
   `index`) → `nginx -t` passed → the model was right → the eval measured a non-fault. Replaced with a real
   error. **Always verify the fault reproduces.**
2. Answer leak: the model `cat`'d the injection script; and the depth-nudge listed the answer category.
   Both closed.
3. Scoring bug: bare YAML tokens (`5432`, `8080`) parse as ints → scorer crashed → 12 correct diagnoses
   mislabeled "scoring failed". Fixed.
4. Herring detection by keyword proximity false-flagged correct diagnoses that *explain* the herring as an
   effect. The category is the authoritative "resisted?" signal → hard-fail on forbidden keywords only when
   the category is ALSO wrong.

## Multi-service (docker-compose) — `run_compose.py` + `stacks/shop` (K=3)

A real 7-service DAG (nginx → frontend → backend → {postgres, redis, rabbitmq}; worker → {redis, rabbitmq})
with healthchecks + `depends_on: service_healthy` — the shape of a real small-team stack (modeled on a
production compose file, not copied). The ops loop runs with LOCAL exec on the host and
`CODEZAIKU_OPS_COMPOSE_PROJECT` set. Turn budget 40 (a 7-service stack is a far bigger search space than one
box — 25 turns exhausted before concluding).

| scenario                                   | result (repeated runs) | what it tests |
|--------------------------------------------|------------------------|---------------|
| c001-healthy (NO fault, stack all green)    | **2–3/3 `healthy`**    | FALSE-POSITIVE discipline — can it say "nothing is wrong"? |
| c002-branch-redis-down (1 of backend's 3 deps down) | **1–3/3 (HIGH VARIANCE — not established)** | BRANCH localization across a DAG — name *redis*, not postgres/rabbitmq, not just "backend unhealthy" |

> **CORRECTION (measurement honesty).** An earlier version of this file reported c002 as **3/3**. That was a
> single lucky K=3 run presented as established. Repeated runs give 3/3, 1/3, 2/3, 1/3 — **compose branch
> diagnosis is HIGH-VARIANCE and NOT established.** Single-box, by contrast, is solid across many runs
> (9/9 on a 001/006/009 regression sample). Two identified contaminants, both real: (a) the eval host has a
> genuine `/data1` at 92% that the precheck flags, and reps sometimes build a false causal chain from it
> ("redis exited *because* /data1 hit 92%" — while noting redis's mount is on a different filesystem);
> (b) reps frequently exhaust the turn budget on a 7-service search space and never conclude. K=3 is
> inadequate here; a firm compose number needs K≥5 on a clean host.

**The compose-health precheck is the lever.** It enumerates the stack roster, flags the unhealthy services,
and pulls each one's LAST healthcheck output — which literally names the failing dependency ("backend
unhealthy: cannot reach redis:6379"). That collapses a 7-service (or 29-service) search to one service in one
step. It is the on-prem analog of `aws ecs describe-services` (running vs desired) / `gcloud run services
list` (Ready) — see the cloud-ops note; the architecture generalizes, only this precheck is per-platform.

### False-positive discipline (the #1 gap) — fixed, with an honest caveat
Asked "is anything wrong?" on a HEALTHY stack, the 9B originally asserted a benign BACKGROUND condition as
"the true root cause" (the host's disk at 90%) — over-diagnosis, the dangerous failure mode for a tool you
point at your own server. Fix: a one-shot **relevance/reproduction nudge** — *"does your cause actually
REPRODUCE the reported symptom? a disk at 90% (not full) does not cause request errors; if the symptom isn't
reproducing, conclude healthy."* Now **3/3 `healthy`**, and real faults REAFFIRM rather than flip (005/006/008
regression = 9/9). Notably the host still has a real `/data1` at 92% that the precheck DOES flag — and the
model now resists it and still concludes healthy, which is genuine discipline, not an empty box.
**CAVEAT (do not overclaim):** between the 0/3 and the 3/3, TWO things changed — the nudge was added AND the
host's `/` dropped from 90%→59%. The nudge's isolated contribution is therefore NOT cleanly measured; what IS
established is that the system now behaves correctly on a healthy stack while a real background anomaly is
flagged, and that the nudge costs nothing on real faults.

### The false-NEGATIVE hole (found, closed)
The relevance nudge fixed over-diagnosis — and then over-corrected: a rep concluded **"healthy" while redis
was DOWN**. Worse, the ORACLE scored it a PASS, because alias matching did naive substring matching and
`"healthy"` is a substring of `"unhealthy"` (`redis_unhealthy`) — **a missed fault scoring as a pass, the
worst possible oracle error.** Both fixed:
- **scoring**: a healthy verdict passes ONLY when the scenario's true answer is healthy (hard guard).
- **loop**: a structural false-negative guard — a "healthy" verdict is rejected while the DETERMINISTIC
  prechecks carry a `[critical]` finding. Not one-shot: reporting the box fine while a service is down is the
  most dangerous output this tool can produce, strictly worse than over-diagnosing.
Verified: after the guard, NO rep concluded healthy on the faulted stack, and the healthy scenario can still
freely conclude healthy (its prechecks are clean).

### Prompt style: POSITIVE directives only
Every model-facing prompt was rewritten from prohibitions to actions ("Do NOT invent a fault" → "When your
checks show the system is serving correctly, conclude healthy"). Negatives make a model attend to the very
thing they forbid AND add instruction volume — already measured harmful on this 9B (more system-prompt rules:
4/18 → 3/18 on Terminal-Bench). Verified no regression: single-box 9/9 after the rewrite.
Related trap, same cause: an ACTION hint leaked into the PRECHECK (a block also shown to the READ-ONLY
diagnostician, where mutation is blocked) and diagnosis fell 3/3 → 1/3. Keep shared blocks to neutral facts.

### Honest notes from this round
- Two ORACLE bugs (not model failures) initially scored a correct 3/3 as 0/3: the trajectory cap
  (`max_investigation_loops: 25`) wasn't raised with the turn budget, and `redis_dependency_failure` — a true
  synonym — was missing from the aliases. Both fixed. **Always read the failed GATE before believing a number.**
- At 25 turns one rep hallucinated a causal chain from the irrelevant `/data1` 92% ("redis exited *because*
  disk hit 92%") while even noting redis's mount was on a different filesystem. At 40 turns all 3 were correct.
  Misattribution under background noise remains the residual weakness.

## The "what changed?" axis (scenario 009) — and an honest negative on its lift

Most real incidents follow a recent change, and correlating the outage with it is the fastest RCA path (the
one a human reaches for first). Built `Precheck.recentChanges` — PUSHED, not offered (same lesson as the
runbooks): files modified in the last 24h with timestamps, recent `dpkg` install/upgrade, just-started
containers. Deliberately high-precision (system churn filtered, list capped) — a noisy precheck misleads a 9B.

**Scenario 009** is the trap: the app 500s and its log says *"cannot connect to database 127.0.0.1:5433"*, so
"the database is down" is the obvious call — **and it is WRONG**. The database is healthy and listening on
5432; a config edit minutes ago set `db_port=5433`. (The image ages `/etc` to 30 days, so the one edit is the
ONLY recent change — a clean signal, not a flood of image-build timestamps.)

| condition                          | result |
|------------------------------------|--------|
| changes-precheck OFF               | 3/3    |
| changes-precheck ON                | 3/3    |

**Two honest readings:**
- **The scenario is a strong result: the 9B resisted the "database is down" misdiagnosis 6/6** — it verified
  the DB was actually up on 5432 and blamed the config, not the dependency. That is exactly the
  obvious-but-wrong conclusion we most need it to refuse.
- **The change-signal A/B shows NO lift — and this test cannot prove it useless.** The app's own error names
  its config file (`(from /etc/shopapp/app.conf)`), so the trail is not cold: the model reaches the config
  without needing the change signal. To actually measure change-correlation's value we need a scenario where
  the changed file is NOT discoverable from the error (a cold trail). **Recorded as unmeasured, not as zero.**

## Multi-service REMEDIATION — and the strongest evidence yet for the closed loop

Remediating the compose stack (diagnose redis is down → restart it → harness re-verifies the edge serves).
The failure modes are more interesting than the score:

- ✅ A correct rep: `docker start <redis>` → verified `.State.Status=running` + backend `.State.Health=healthy`
  → harness's independent `curl` of the edge confirmed 200.
- 🚨 **A rep HALLUCINATED both the fix and its proof.** It ran `docker restart shop-c002-branch-redis-down-1-redis-1`
  while working on rep **2** (whose container is `-2-`) — a container that DOES NOT EXIST — then reported
  `redis-cli ping → PONG` *from that nonexistent container* and asserted resolved (`model_claims_resolved=True`).
  **The harness's independent resolved_check caught it** (`harness_verified=False`).

**This is the whole thesis in one data point: the model can fabricate a fix AND fabricate the verification of
that fix. Only an independent, harness-run check on the true end-state exposes it.** Every ops tool that
trusts the agent's own "I fixed it" would have reported success here.

Contributing cause (our own eval hygiene, now fixed): the generated project names were absurdly long
(`shop-c002-branch-redis-down-2-redis-1`), making exact identifier-copying a real ACI hazard. Real compose
projects are named `myapp`. Shortened to `shop<rep>` so the eval measures remediation, not string-copying.

## EXTERNAL VALIDATION — faults and labels we did NOT author

Everything above is measured on faults **I wrote**. That is the author-on-both-sides trap (the same one that
sank the M1–M9 synthetic-bug arc), and it means those numbers could be measuring my own imagination. So:
run the loop against **opensre's `hermes_rca` library** — 25 scenarios, 19 root-cause categories, a
`000-healthy` case, in an **LLM-gateway domain the harness has never seen** (provider outages, dropped
headers, SSE overflow, KV-cache drift, agent hangs, memory-backend failures, missing governance controls).
Faults: theirs. Label taxonomy: theirs. Evidence artifacts: theirs.

### Result: **ROOT-CAUSE CATEGORY ACCURACY = 18/25 (72%)**

A 19-way classification in an unseen domain (random ≈ 5%), exact-match against their ground-truth labels —
**including `000-healthy` → `healthy`, so the false-positive discipline holds on an EXTERNAL healthy case.**

### Scoring honesty (this matters — the first number I got was wrong)
My first run reported **1/25**, which was an artifact of MY scorer, not their result: in **18 of 25 the
category was exactly right and still scored FAIL**, because our `scoring.py` requires EVERY
`required_keyword` as an exact substring. **Their** scorer matches keywords *semantically* through
domain-specific alias tables (in their RDS suite, `idle` also matches `clientread` / `sessions remain open`).
Replicating those alias tables faithfully is out of scope, so **the full-gate number understates performance
and must never be quoted as their benchmark score.** The category number is the defensible one.

### The failure pattern (7 misses)
`configuration_error` is used as a CATCH-ALL — it accounts for 5 of the 7 misses:

| scenario | expected | got |
|---|---|---|
| 041-approval-lock-missing | missing_approval_gate | configuration_error |
| 042-audit-trail-missing | missing_audit_trail | configuration_error |
| 043-rbac-gateway-missing | missing_rbac | configuration_error |
| 020-multi-agent-orchestration-missing | orchestration_missing | configuration_error |
| 012-cron-hang-post-output | delivery_hang | configuration_error |
| 001-codex-empty-response | upstream_service_outage | configuration_error |
| 004-bedrock-imds-override | configuration_error | missing_credential_isolation |

The `missing_*` family (a governance control is absent) collapses into "configuration_error" — defensible in
plain English, but their taxonomy separates them. Notably it got `040-missing_determinism_control` and
`044-missing_credential_isolation` RIGHT, so the confusion is inconsistent, not a blanket failure.

### What this does and does NOT establish
- **DOES:** the diagnostic REASONING core generalizes to a domain it was never designed for, judged by an
  oracle we did not write — and it refuses to invent a fault on an external healthy case.
- **Does NOT:** validate the shell/infra investigation surface. These are RCA-from-artifacts tasks (the
  evidence is files), not live-box faults.
- **Fairness decisions, stated up front** (see `external/README.md`): the alert's
  `commonAnnotations.failure_mode` leaks the scenario's own ground-truth label (never present in a real
  alert) and is stripped; their 19-category label set is supplied to the model, exactly as any
  classification task must supply its labels (their agent knows the taxonomy; ours never saw it); prechecks
  off (they detect infra faults and `recentChanges` would flag the freshly-copied evidence as a hint).

## THE REAL BENCHMARK — OpenRCA v1 (their dataset, their scorer, their leaderboard)

Everything else in this file is measured on faults **we** wrote, or on a task **we** shaped. This is the one
number produced by submitting to someone else's benchmark: we emit a prediction CSV and **their** offline
scorer (`main/evaluate.py`) grades it. Runner: `external/openrca/run_openrca.py`.

**Correction on the target.** OpenRCA **2.0** (the widely-quoted 20.7% EM / ~29% best) is **NOT RELEASED** —
no code, no data; the paper says "upon acceptance". It is unreproducible by anyone. The runnable benchmark
is **v1** (microsoft/OpenRCA, ICLR'25), whose bar is far harsher.

### Result — Telecom (n=51), THEIR scorer

| metric | local 9B (our ops loop) |
|---|---|
| **Strict** (the leaderboard metric) | **9.80%**  (5/51) |
| Partial | 10.78% |
| by difficulty | easy 12.50% · middle 5.56% · hard 11.11% |

Their published leaderboard (**n=335, all four systems**): Claude 3.5 Sonnet **11.34%** (best) · GPT-4o
**8.96%** · Gemini 1.5 Pro 2.69% · **Llama 3.1 (the open-model entry) 3.28%**.

> **NOT LEADERBOARD-COMPARABLE — do not quote it as one.** Our run covers **Telecom only (51 of 335)**;
> the leaderboard is the full set (Bank 136, Market 148, Telecom 51), and Telecom's difficulty mix may differ
> from the average. Claiming "we beat GPT-4o" from this would be the benchmark-gaming bias. A comparable
> number requires running all four systems.

### The finding that matters more than the score

```
produced an answer :  9/51
emitted NOTHING    : 42/51   <- auto-zero
among the 9 that ANSWERED: 5/9 = 55.6% strict, mean score 0.611
```

**82% of queries scored zero for never FINISHING, not for being wrong.** When the agent completes the task
it is strictly correct **55.6%** of the time. The binding constraint is **task completion, not diagnostic
ability** — the agent spends its entire turn budget running pandas over the telemetry and never reaches an
answer (confirmed by reading the trajectory: every turn is `python3 -c ...`, conclusion only on the final
forced turn). That is a concrete engineering target, and it is the kind of thing only a real benchmark
surfaces.

### Integrity measures (the part that makes the number real)
- **DATA-LEAK JAIL, audited.** `record.csv` (ground truth) and `query.csv`'s `scoring_points` column (the
  literal answer key) sit inside the dataset tree a shell agent browses. The agent runs in a container with
  ONLY `telemetry/` bind-mounted **read-only**; verified against a live container that both files are
  physically unreachable. Their FAQ makes this a submission requirement.
- **We do not score.** Their `main/evaluate.py` produces the number.
- **Their output contract honoured**: positional key order (datetime→component→reason), exact failure count,
  candidate strings verbatim/case-sensitive, UTC+8.

### Terminal-Bench — deliberately NOT run
TB 2.0's 89 tasks contain **zero incident-diagnosis tasks**: `system-administration` = *build CompCert /
configure a git server / run Windows 3.11 in qemu / install nginx*; `debugging` = *fix a C++ memory bug /
fix LaTeX / recover a truncated sqlite* (software debugging, i.e. edit source). Pointing an ops
**diagnostician** at constructive tasks and reporting the number would be a category error that looks like a
result and measures nothing. TB legitimately measures the CODING harness (the old 3/18–6/18 baselines),
which this arc did not change.

## Caveats (honest scope)
- 5 fault families × K=3 = 15 samples/condition. Solid; not yet covered: OOM (can't be reproduced
  discoverably in an unprivileged container — the OOM-killer logs to the HOST dmesg), multi-service /
  cascading faults.
- Faults are single-cause and relatively clean. Real incidents can be multi-cause / noisier; the current
  prechecks target exactly these families.
- Remediation resolved-checks must assert the true end-state (003 taught this) — 004/005's checks verify a
  real property (cert validity via `checkend`, `df` usage) so they can't be gamed the way 003's could.

## Remediation (closed loop, K=3, precheck ON)

The gated remediation phase applies a fix then the HARNESS re-runs an objective `resolved_check` itself
(`harness_verified`) — the model's own claim is never trusted.

| scenario            | genuinely fixable on-box? | harness-verified resolved | the actual fix the 9B applied |
|---------------------|---------------------------|---------------------------|-------------------------------|
| 001 nginx bad-config| yes                       | **3/3 REAL**              | commented out the invalid `worker_connections` directive → `nginx -t` ok → started nginx → curl 200 |
| 002 port_conflict   | yes                       | **3/3 REAL**              | `kill` the rogue holder → started the real app → curl 200 |
| 004 cert_expired    | yes                       | **3/3 REAL**              | `openssl req -x509 -days 365` new cert → `nginx -t && nginx -s reload` → cert `checkend` valid |
| 005 disk_full       | yes                       | **3/3 REAL**              | `rm /var/lib/appdata/filler` (+ truncate the log) → `df` back under 90% |
| 003 dependency_down | NO (no DB installed)      | 3/3 but **DOES NOT COUNT**| a bare listener on :5432 satisfied the app's TCP-only check — not a real DB fix; `applied_steps` empty |

**Headline: closed-loop remediation is independently harness-verified on all FOUR genuinely-fixable faults
(001, 002, 004, 005) — 12/12 reps: the 9B diagnoses, applies a correct minimal fix, and the harness itself
confirms the incident is resolved.** This is the author↔operate + closed-loop verification the SRE
landscape says nobody does, demonstrated on a LOCAL 9B.

**The cascade (006) surfaced a second, striking result: remediation (2/3) beat diagnosis (1/3 → 2/3).**
Because the resolved-check (the edge must serve 200) cannot be satisfied without actually freeing the
disk, the model is FORCED to discover and fix the true root even on a rep where its stated diagnosis
stopped at the symptom. The closed loop partly compensates for diagnosis depth — the verification IS the
safety net.

**003 is an honest negative + a design lesson.** There is no database on the box, so the incident is
unfixable in-place (correct real-world answer: escalate / install the dependency). The model instead
started a stub listener that satisfied the app's `connect()`-only health check, and my `resolved_check`
(curl 200) was too weak to catch it. **Lesson: a closed-loop check must assert the TRUE end-state, not a
proxy.** 003 is treated as diagnosis-only; its remediation "pass" is discounted.
