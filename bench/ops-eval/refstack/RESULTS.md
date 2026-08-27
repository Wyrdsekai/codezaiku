# cp-refstack results

## P1 — localization (2026-07-21)

Battery: stop each of the 8 engines (hard-down) + one DEGRADED fault (opensearch write-block: serves reads,
rejects writes). Harness `localize.py`: sense (docker health + app `/health` per-dep + logs + `depends_on`
topology) → the MODEL names the root; a deterministic deepest-red-green-deps baseline is the yardstick.

| iteration | 30B model | deterministic baseline | key change |
|---|---|---|---|
| v1 | 6/9 | 8/9 | first cut |
| v2 | 8/9 | 8/9 | + root-vs-symptom grounding (model stopped naming `app` over the root engine) |
| **v3** | **9/9** | **9/9** | + **write-probe readiness `/health`** (a degraded write-failure is now detected & named) |

**30B: localization SOLVED (9/9).** Two things got it there:
1. **Grounding** the model that `app`/`nginx` are symptom tiers — if a backing engine is failing, the ROOT is
   that engine (fixed the v1 misses where it named `app` for opensearch/localstack).
2. A **write-probe readiness `/health`** — the app tests WRITES on the stateful engines, so a degraded
   write-failure (opensearch write-block, and by design disk-full/read-only) that still serves reads is
   detected and named. Without it, health-only sensing is blind to write-degradation (v1: both missed it).

**9B (shipping tier): 9/9 — localization SOLVED on both tiers.** (Was misdiagnosed as a broken server: the
`drive.gguf` 9B is now a REASONING model — it fills `reasoning_content` and leaves `content` empty until it
finishes, so low `max_tokens` returned empty. Fix: `chat_template_kwargs:{enable_thinking:false}` (no-think,
also much faster) + a robust output parser.) Both tiers get all 9 incl. the degraded write-block.

Robustness fixes made along the way (all real bugs on a coupled stack): app self-heals resources (LocalStack
is ephemeral → lost bucket/queue on restart → worker re-runs `ensure()` every 15s); neo4j probe got a connect
timeout (a down neo4j hung `/health`); nginx caches the app upstream IP across an app rebuild → 502 until
`nginx -s reload` (bringup should reload nginx after rebuilding app); robust model-output parser (small models
skip the strict `ROOT:` format).

Note on the degraded case: a write-probe readiness check makes common degraded write-failures *detectable*, so
localization is easy once sensed. The genuinely-hard frontier is faults that pass write-probes (perf
degradation, partial/intermittent failures) — where the model must reason from logs; that's future work.

Next: (a) restart the 9B server + get its number; (b) wire localize→card→remediate→verify (the rest of P1/P2);
(c) harder degraded faults for the log-reasoning frontier.

## P1/P2 — full remediation loop, first end-to-end (2026-07-21, 30B)

`remediate.py`: for an injected fault → sense+LOCALIZE (reuses localize.py) → match a CARD (ops-knowledge,
gated to the localized service) → REMEDIATE via a ReAct loop SCOPED to the root container (blast-radius = one
service, the guardrail) → VERIFY the service oracle + the app end-to-end recover.

First fault (`redis-auth`: `CONFIG SET requirepass` on redis): **detected=True, localized=redis (OK),
remediated (the model ran `docker restart refstack-redis-1` to clear the in-memory requirepass — the card's
own "just restart the server" option), service_ok=True, e2e_ok=True → FIXED.** The full autonomous-SRE loop
works end-to-end on the coupled stack.

Two honest notes: (1) the card `redis-auth-server` did NOT match — its signature `noauth authentication
required` vs the app's redis-py error `Authentication required` (a string-match gap; the evidence/signature
alignment needs a fix, or broaden the signature under the `match: redis` gate). (2) The 30B self-solves
redis-auth (restart) without the card — so the card lift is a 9B story (as with the chaos single-engine
faults). Next: more fault classes (opensearch flood-stage, qdrant, s3) where localization + the card matter,
and the 9B tier once its server is healthy.

### Environment-robustness fixes (all real, on a coupled churned stack)
- **9B is a reasoning model** → `enable_thinking:false` + enough tokens (or it returns empty `content`).
- **Docker embedded-DNS flakes** under stop/start churn (app can't resolve `postgres`/`rabbitmq`) → `wait_healthy`
  self-recovers by restarting the app to refresh its resolver.
- **nginx caches the app upstream IP** across an app restart → `nginx -s reload` after.
- **App self-heals resources** (LocalStack ephemeral loses bucket/queue) via periodic `ensure()`.
- **neo4j probe connect-timeout** (a down neo4j hung `/health`).

## P1/P2 — remediation across a battery, both tiers (2026-07-21)

Battery: redis-auth (CONFIG SET requirepass) + opensearch-writeblock (index.blocks.write). Full loop:
detect → localize → match CARD (gated to the localized service) → REMEDIATE (ReAct scoped to the root
container) → verify service oracle + app end-to-end.

| fault | localize (both tiers) | 30B remediate | 9B remediate (+repetition guard) |
|---|---|---|---|
| redis-auth | ✓ | FIXED (restart) | **FIXED** (rep-guard → card's restart alternative) |
| opensearch-writeblock | ✓ | FIXED (cleared block) | not-fixed (gave up turn 2, didn't `GET _settings`) |
| **total** | 9/9 | **2/2** | **1/2** (was 0/2 before the rep guard) |

Findings:
- **Card-match fixed:** redis card signature broadened (`authentication required`) → matches the app's
  redis-py error; new `opensearch-write-blocked.md` candidate. Both cards now FIRE (gated to the localized
  service). Card-fire was the gap; now closed.
- **Tier split maps onto the authority ladder:** localization (R1) is solved on both tiers (9/9); remediation
  (R3) is reliable on the 30B (2/2) but hits an execution ceiling on the 9B. redis-auth is R3-capable on both;
  opensearch-writeblock is R3 on the 30B, stays R2 (propose) on the 9B.
- **9B failure mode = fixation, not ignorance.** On redis-auth the 9B correctly diagnosed (checked
  requirepass per the card) but repeated the same wrong `CONFIG SET` (guessed the password = container name)
  **12×**. A **repetition guard** (don't re-run a repeated command; nudge toward the card's alternative)
  unstuck it → it restarted redis → FIXED. Harness robustness (repetition guard) is a real lever for the
  shipping tier, same shape as the chaos-work repetition guard.
- opensearch-writeblock on the 9B failed by a DIAGNOSIS gap: it read cluster health (yellow=replica) and
  concluded "not write-blocked" without running the card's step-2 `GET <index>/_settings`. Future: prompt/card
  emphasis on evidence-gathering before concluding; or per-class R2-only on the 9B.

Next: K≥5 per (fault-class × tier) for the R4 gate (add the harm-rate metric); broaden the battery; the
degraded-fault log-reasoning frontier.

## R4-gate scorecard — first fault-class earns UNATTENDED (2026-07-21)

The autonomy ladder's R4 gate = across K≥5, per fault-class × tier: detector reliable + localization correct
+ card fires + remediation zero-HARM + recovery stable. Harm = a service that was healthy before is broken
by the auto-action (the load-bearing metric — did the fix damage something that was fine?).

**redis-auth (CONFIG SET requirepass):**

| tier | localized | fixed | harm | R4-gate |
|---|---|---|---|---|
| 30B | 5/5 | 5/5 | 0/5 | **PASS** |
| 9B (shipping) | 5/5 | 5/5 | 0/5 | **PASS** |

**redis-auth EARNS R4 (unattended auto-remediation) on both tiers, including the shipping 9B** — the first
fault-class to graduate the full ladder. The loop detects the fault, the model localizes redis, the card
fires, the model clears the in-memory requirepass (restart), verify + stability re-check pass, and nothing
else is disturbed (harm 0/5). The 9B needed the repetition guard to get there reliably; with it, it's clean.

opensearch-writeblock is not a candidate for R4 on the 9B yet (R3 on the 30B, R2/propose on the 9B — the 9B
gives up without checking `_settings`). Next: more classes through the gate; the degraded log-reasoning
frontier; and per-class × tier as the standing scorecard for which classes run unattended vs. propose.

## R4-gate — second class through, and the "executable card" lesson (2026-07-22)

**postgres-readonly** (`ALTER DATABASE ... default_transaction_read_only=on` → writes fail; a chicken-and-egg
fix: the repair session is itself read-only, so a plain `ALTER ... off` fails and RESTART doesn't help — the
setting persists):

| tier | localized | fixed | harm | R4-gate |
|---|---|---|---|---|
| 30B | 5/5 | 5/5 | 0/5 | **PASS** |
| 9B (shipping) | 5/5 | 5/5 | 0/5 | **PASS** |

**Second fault-class to earn UNATTENDED on both tiers.** But it only worked once the card was made
**executable**: the first card said "SET session read-write, then ALTER" — the 30B ran them as *separate*
`psql -c` calls (separate sessions), so the override didn't carry, and it **failed 0/2**. Giving the exact
one-liner (`psql -c "SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE" -c "ALTER DATABASE <db> SET
default_transaction_read_only=off"`) flipped it to **5/5 on both tiers**. This is the "correctness = executable
on the actual stack" law from the AIOpsLab work: for a fix with a non-obvious gotcha, the card must give the
exact command, and then it lifts *both* tiers.

### R4-gate standing scorecard (per fault-class × tier)
| fault-class | 30B | 9B (shipping) |
|---|---|---|
| redis-auth | **R4 (unattended)** | **R4 (unattended)** |
| postgres-readonly | **R4 (unattended)** | **R4 (unattended)** |
| opensearch-writeblock | R3 (guarded) | R2 (propose) — 9B gives up without `GET _settings` |

## R4-gate — third class (ollama-model-missing) + the "symptom evidence" fix (2026-07-22)

**ollama-model-missing** (`ollama rm nomic-embed-text` → embeddings fail; an AI-stack-specific incident —
the embedding model was removed/never-pulled; reads/tags still work, only inference fails):

| tier | localized | fixed | harm | R4-gate |
|---|---|---|---|---|
| 30B | 5/5 | 5/5 | 0/5 | **PASS** |
| 9B (shipping) | 5/5 | 5/5 | 0/5 | **PASS** |

**Third class UNATTENDED on both tiers.** Three fixes made it detectable + fixable:
1. **`/health` embed write-probe** for ollama (a shallow `/api/tags` check misses a missing model — tags
   still lists, only inference fails).
2. **`embed()` surfaces Ollama's real error** ("model not found") instead of a bare `KeyError('embedding')`
   — so the app health NAMES it and the card can match.
3. **Symptom evidence in the remediation prompt (general improvement):** with only "the ollama service is
   failing", the 30B pulled the WRONG model (`qwen2.5:0.5b`). Passing the health error (which names
   `nomic-embed-text`) into the remediation incident → it pulls the RIGHT model → 5/5 both tiers. The
   remediator must see the SYMPTOM, not just the service name.

### R4-gate standing scorecard (per fault-class × tier)
| fault-class | 30B | 9B (shipping) |
|---|---|---|
| redis-auth | **R4 unattended** | **R4 unattended** |
| postgres-readonly | **R4 unattended** | **R4 unattended** |
| ollama-model-missing | **R4 unattended** | **R4 unattended** |
| opensearch-writeblock | R3 guarded | R2 propose |

Three of four fault-classes now run unattended on the shipping 9B. (neo4j Community lacks the easy read-only
fault — Enterprise-only `ALTER DATABASE ... SET ACCESS`; deferred.)

## R4-gate — opensearch-writeblock reaches R4 on BOTH tiers; full battery graduated (2026-07-22)

opensearch-writeblock (index.blocks.write) was the last class not at R4 on the 9B. It took THREE grounding
improvements — each GENERAL — to get the shipping 9B from "gives up turn 2" to 5/5:
1. **Directive/executable card** — "do NOT judge from cluster health COLOR (yellow=unassigned replica is
   unrelated); check `curl <es>/<index>/_settings`; clear with the exact PUT". (The 9B kept reading cluster
   health = yellow and wrongly concluding "not blocked".)
2. **Widened health error to NAME the resource** — the app's opensearch error was truncated at 60 chars,
   cutting `index [docs]`; the 9B then cleared the block on the WRONG index (`opensearch`, the service name).
   Widened to 150 → the symptom names `docs` → correct index.
3. **Symptom evidence in the remediation prompt** (from the ollama fix) — the model sees the actual error.

| tier | localized | fixed | harm |
|---|---|---|---|
| 30B | 5/5 | 5/5 | 0/5 |
| 9B (shipping) | 5/5 | 5/5 | 0/5 |

### R4-gate standing scorecard — FULL BATTERY UNATTENDED ON BOTH TIERS
| fault-class | 30B | 9B (shipping) |
|---|---|---|
| redis-auth | **R4** | **R4** |
| postgres-readonly | **R4** | **R4** |
| ollama-model-missing | **R4** | **R4** |
| opensearch-writeblock | **R4** | **R4** |

**4/4 fault-classes run fully unattended on both tiers — 40 R4-gate runs, 0 harm.** The general lesson: a
harder fault-class needs richer GROUNDING for the shipping tier (executable card + the symptom naming the
exact resource), and once provided, even the 9B reaches unattended. The harness levers that made this work
— executable card, repetition guard, symptom evidence, resource-naming health errors, write-probe readiness,
harm metric — are the reusable product mechanics.

## R4-gate — fifth class (rabbitmq-disk-alarm), five distinct mechanisms (2026-07-22)

**rabbitmq-disk-alarm** (`set_disk_free_limit` to an absurd 50TB → disk_free_alarm → publishers blocked). The
app's rabbitmq probe was upgraded to an **AMQP publish-with-confirms** (a bare connection succeeds under an
alarm; only a confirmed publish blocks). The fault is a MISCONFIGURED limit (disk is fine), so the fix is to
lower the limit — which the disk-full validated card discourages; so a new `rabbitmq-disk-alarm.md` candidate
teaches **diagnose-then-fix**: `rabbitmqctl status` → compare `disk_free` vs `disk_free_limit` → if the limit
is set too high (misconfig) lower it, else free disk.

| tier | localized | fixed | harm | R4-gate |
|---|---|---|---|---|
| 30B | 5/5 | 5/5 | 0/5 | **PASS** |
| 9B (shipping) | 5/5 | 5/5 | 0/5 | **PASS** |

### R4-gate standing scorecard — FIVE classes, five mechanisms, all UNATTENDED both tiers
| fault-class | mechanism | 30B | 9B |
|---|---|---|---|
| redis-auth | auth / requirepass | R4 | R4 |
| postgres-readonly | read-only config | R4 | R4 |
| ollama-model-missing | missing dependency | R4 | R4 |
| opensearch-writeblock | index write-block | R4 | R4 |
| rabbitmq-disk-alarm | resource alarm / flow control | R4 | R4 |

**5/5 classes unattended on both tiers — 50 R4-gate runs, 0 harm.** Five distinct failure mechanisms across
five engines, on the shipping 9B, on a real coupled AI stack.

---

# THE ACTUAL PRODUCT — port into org.codezaiku.ops + full autonomous-SRE operator (2026-07-22 → 07-23)

Everything above was measured with the Python drivers (`localize.py`/`remediate.py`) — a PROTOTYPE. The call was:
"so wait — we haven't actually been testing against the real product? what's the point then?" Correct. So the
whole loop was ported into the Java product (`org.codezaiku.ops`, `FamiliarMain`) and re-measured, then built
out into a complete operator. What transferred as-is: the cards (data → library), the substrate (the
certification rig), the levers as design. What was rebuilt in the product below.

## The port + the big bug (gap #1)
- `StackLocalizer` — senses the whole stack (compose health + app write-probe /health per-dep + per-service
  logs) → model names the ROOT. `DriveClient.classify` (temp-0, no-think) makes localization deterministic.
- Blast-radius scope (`OpsShellTool.scopeTo`): a remediation docker verb may not touch a bystander.
- Harm check (before/after healthy-dep diff), localized fast-path (skip the conclude-loop that leaked runs).
- **gap #1 (the big bug): card match was gated to the WHOLE STACK, not the localized root** — on a single-box
  stack every service is a running container, so a shared signature keyword ("read-only") pushed the WRONG
  card (postgres got a vector card, never its ALTER fix). Root-scoping the match fixed postgres AND rabbitmq
  at once. Plus `extractFixCommands` (hand the model the card's exact grounded command) — postgres 1/3 → 3/3.

## Result: 9B = 15/15 clean, 30B redis/opensearch/postgres 3/3
Full K=3 on the real product, 9B shipping tier: all 5 classes localize + fix, **0 harm, 15/15**. (Across K=3
the honest range is ~13–15/15; the shortfall was environmental docker-DNS mislocalization, since hardened.)

## Safety guards (all proven)
- **R3 rollback** (`RemediationSnapshot`): snapshot-before / rollback-on-failed-verify. Crash-consistent
  (`docker pause` during the volume tar — a live tar left postgres an invalid WAL checkpoint). Covers volume
  config (restore), container-FS config (force-recreate), and a LATENT-config catch (a config-file edit that
  passed verify is restart-validated). Proven: a fix that corrupted postgresql.conf is auto-recovered.
- **Host-write guard** (`OpsSafety` extension): a scoped fix may not edit host files / the compose file (the
  30B had corrupted the compose file itself); `SVC_WRITE` keeps the read-only recon truly read-only.
- **Harm metric** (bystanders) + root-scoped verify (each fix judged on its own root, not the whole stack —
  the load-bearing fix for multi-fault).

## Authority ladder (R0–R4 runtime) — OpsAuthority
Rungs OBSERVE<LOCALIZE<PROPOSE<GUARDED<UNATTENDED; effective = min(ceiling, earned), forced to OBSERVE by a
kill-switch. Earned: validated-card → UNATTENDED, candidate-card / recon-derived → PROPOSE, low-confidence →
PROPOSE. JSON audit trail; a TRIAL lane auto-applies candidates for measurement. Proven on redis: PROPOSE (no
mutation) / GUARDED (fix+verify+0-harm) / kill-switch (OBSERVE) all correct.

## Recon-for-unknown + the card-vs-recon finding
When localized but NO card matches, the loop RECONs (read-only diagnosis) and DERIVES a fix → PROPOSE / trial.
Proven on a genuinely unknown fault (redis maxmemory OOM). **KEY FINDING (A/B, both K=5): recon SUBSUMES cards
for TRACTABLE faults** — rabbitmq-permissions recon 5/5 (card neutral) vs postgres-readonly recon 0/5 (card
essential). ⇒ recon = default free coverage; cards earn their place only on the HARD TAIL. The card-worth-
authoring test = run recon-OFF; recon-fixes ⇒ no card needed; recon-fails ⇒ author one, the OFF→ON delta is
its value.

## The learning arc: learn-back → auto-promote
- **Learn-back** (`OpsLearn`): a VERIFIED recon fix becomes a candidate card (the exact command, templatized,
  keyed by the error). Proven: redis-OOM RECON → learned card → same fault next time hits the fast card-path.
- **Auto-promote** (`OpsPromote`): a candidate card that VERIFIES N times (default 3) flips candidate→validated
  in its own file (may then auto-remediate, not just propose). Proven: candidate → "PROMOTED after 2 reuses".

## Triggering — fix | serve | watch, local or ssh
- `FamiliarMain fix <scope> <ceiling>` — one-shot; `OpsDiscovery` auto-discovers the app-health endpoint +
  derives a verify from the compose project (no per-stack env). **Multi-round (fix-until-green): one trigger
  clears ALL current faults** — proven on redis-auth + opensearch-writeblock (round1 opensearch, round2 redis,
  green). Scope may be `ssh://[user@]host/<project>` — the whole pipeline runs over ssh, drive stays central
  (proven: `fix ssh://localhost/refstack guarded` → PONG).
- `serve [port]` — HTTP dispatch (POST /fix → OpsOutcome JSON; GET /health; GET /audit dashboard). Alerts
  (harm/rollback) POST to CODEZAIKU_OPS_ALERT_WEBHOOK.
- `watch <scope> <ceiling> <interval>` — proactive loop (idle on green, fix on degraded).
- **systemd daemon** (`deploy/codezaiku-ops.service`) running `serve` on :7070 — live on ${CP_HOST}.

## Off-substrate generality (proven)
`fix shop guarded` on a genuinely different stack (`offstack-shop/`: web+cache, differently-named service) with
NO refstack env → auto-discovered its /health, the redis card did NOT match (service named `cache`) → recon
derived + applied the fix → verified, 0 harm. Same product code, different stack.

## Product state
`FamiliarMain fix|serve|watch <scope> <ceiling>` on ANY compose stack, local or ssh:
discover → sense → localize → validated-card? apply : recon-derive → act at the earned rung (kill-switch,
confidence, audit) → root-scoped verify → R3 rollback → harm-check → learn-back → auto-promote → loop-until-green.
Card-first / recon-fallback / learn-back / auto-promote; safe by construction; 6 fault classes; 0 harm.
Commits d68ceaa2 → 7f4dd87f on dev. NEXT frontiers: degraded / log-reasoning (up-but-misbehaving faults that
pass write-probes), P3 proactive maintenance (act before an outage).
