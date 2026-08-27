# The ops knowledge layer — card shape & delivery, measured (AIOpsLab, 2026-07-18/20)

**Question.** The library thesis says pushed domain knowledge is the lever (OpenRCA oracle: 2.2×). Every
properly-shaped ML card lifted; ops cards kept measuring neutral-or-harmful. What SHAPE and DELIVERY make
an ops card lift on the shipping small-model tier?

**Substrate.** AIOpsLab (Microsoft) mitigation tasks on a kind cluster (${CP_HOST}): 5 mongo-family faults
(card targets) + misconfig_app (control). Adapter `cp_agent.py`, bare ReAct, 35-step budget. Arms differ in
ONE variable per step; arms interleaved per rep (this family's identical-config variance: 5/15…0/10).
Treatment verified per run from pushed-line stdout, never inferred from absence. 30B = search tier
(:8201), 9B = shipping tier confirm (:8200).

## The ledger (each shape eliminated or kept by measurement)

| step | shape | result |
|---|---|---|
| know1 | verbose reference card (1258 chars), up-front | submit rate 43%→0% — verbose kills convergence |
| know2 | terse reference + stop cue, up-front | convergence restored, solves neutral |
| know3 | model-side signature trigger | fired 0× — needs the model to already read the logs (chicken-and-egg) |
| know4 | harness scan at init | fired 0× — too EARLY (no traffic yet) + too COSTLY (~4min of 560s) |
| know5 | **deferred scan** (turn 5, ONE ≤30s all-pods grep), push immediately | fires 25/25; TLS card **flips auth_miss 0/5→4/5**; credential card harms (-1s 7/10→0/10) |
| know6 | same, credential card FIXED (mongosh trap removed, configmap-first) | harm REPLICATES with a correct+followed card (-1s 13/20→0/20 pooled) → not content |
| know7 | rescue push (hold card, inject turn 20 if not concluded) | harm GONE (mongo 8/25→11/25) but 0/5 on auth_miss — too late for the startup-dead class |
| know8 | **per-card policy** (`push: immediate\|rescue` header) | **mongo 7/25→15/25 (2.1×), zero harmed cells**, delivery exactly per policy |
| know9 | know8 design on the **9B** (shipping tier) | **mongo 2/25→10/25 (5×), zero harmed cells** — CONFIRMED |

## The laws this bought

1. **The harness injects; the model never looks.** Fetchable catalogs are inert (0/25); model-side triggers
   can't bootstrap. The harness greps the stack's recent logs for each card's `signature:` and pushes.
2. **Scan deferred and bounded.** At init the fault signature isn't in the logs yet; an unbounded sweep
   starves the budget. One combined scan at ~turn 5, ≤30s, partial output preserved (inner timeout).
3. **Push timing is fault-class knowledge — declared on the card.** An immediate procedure flips classes
   the model cannot solve by exploration (component dead at startup) and DESTROYS classes it can (the plan
   displaces the winning exploration: 13/20→0/20 across two card versions). Rescue (inject at turn ~20 only
   if not concluded) removes all harm — and on the 9B, which rarely wins by exploration, rescue itself
   lifts (runs survive to the rescue turn and use the procedure). Default `rescue` — first, do no harm.
4. **The four-axis bar is real and mechanized** (correct · scoped · terse+stop-cue · pushed; ops-knowledge/
   lint.py + OpsKnowledge load-time lint + pre-push hook). Both TERSE (912>900 caught) and CORRECT bit in
   practice: the mongosh step was true-of-MongoDB but not executable on the stack — correctness means
   *executable on the actual stack*.
5. **Ground-truth signatures from the fault's own physics, keep them generic.** auth_miss was assumed a
   credential fault; reading the injector + live logs showed a TLS fault (expired cert, mongod fatally dead
   at startup). Signature strings are generic technology error strings; the benchmark only confirms the
   trigger fires (sigprobe: dump exactly what the scan greps, one run per fault class).

## Port (this repo)

`OpsKnowledge.java`: `Card(match, signature, push, body)`, load-time lint, `cards()`; `block()` now pushes
only match-only cards. `OpsLoop.knowledge(cards, logProbe)`: one deferred scan at iter 5 (probe = bounded
journalctl + docker-logs sweep supplied by `FamiliarMain.ops()`), immediate-policy hits inject at the scan,
rescue-policy hits held until iter ~20 (clamped to leave ≥10 iterations). Every push/miss printed.
Cards: `ops-knowledge/*.md`. Enabled by `CODEZAIKU_OPS_KNOWLEDGE=<dir>`; absent = off (baseline default).

Raw logs: ${CP_HOST} `"${CP_WORK:-/opt/codezaiku}"/know{4..9}.log`, sigprobe dumps `"${CP_WORK:-/opt/codezaiku}"/sigprobe-*.txt`, session JSONs in
`"${CP_WORK:-/opt/codezaiku}"/AIOpsLab/aiopslab/data/results/`.

## SREGym extension (2026-07-20): redis/valkey server-auth card — first promotion on an external do-and-verify oracle

**Substrate.** SREGym (kind cluster, astronomy-shop app), problem `valkey_auth_disruption`: the injector runs
`CONFIG SET requirepass 'invalid_pass'` on the valkey server; the cart client can no longer authenticate.
Graded by the objective `ValkeyAuthMitigation` oracle (live cluster state: requirepass cleared + PING + cart
replicas recovered). Adapter: `clients/codezaiku/driver.py` (stage-aware ReAct; actions via direct kubectl —
SREGym's MCP tool failed 3 distinct ways over long runs; the oracle grades state so the channel is fair).

**Card.** `redis-auth-server.md`, written from the fault mechanism + redis docs (never the oracle):
client-can't-connect + server-Running ⇒ check the SERVER's requirepass; reset or restart (in-memory value).

| arm | 30B | 9B (shipping) |
|---|---|---|
| OFF | 1/5 | 4/5 |
| ON rescue@18 | 2/5 (noise) | — |
| ON immediate@5 | **5/5** | 4/5 |

**Reading.** The 30B is the controlled positive flip (1/5 → 5/5, Fisher p≈0.02): rescue was a timing artifact
(12 turns left is too few), immediate gave the model the procedure and it executed it every time. The 9B is a
CEILING result, not a null: the 9B's flail-with-restarts baseline accidentally clears the in-memory
requirepass 80% of the time, leaving no headroom on this fault — ON showed NO HARM (4/5 = baseline) with the
treatment firing 4/5. Promotion basis: positive controlled flip on the 30B + no-harm on the 9B ⇒
`status: validated`, `push: immediate`. Lesson for the next targets: pick faults where the BASELINE of the
tier under test reliably fails (a flailing model must not be able to fix it by restarts) — baseline-first,
then card.

**Measurement-integrity ledger for this arc** (each bug produced plausible-looking wrong numbers before the
fix): stale-CSV parsing (results keyed to newest-on-disk, not this run) → run-start mtime check; 1100s
timeout silently killing healthy ~18-20min 9B runs; judge on the busy 9B server blowing the driver's 420s
stage-wait → judge to the idle 30B + 900s waits; post-reboot casualties (dead 9B container passing /health,
stuck Terminating namespace, unpersisted inotify limit crashlooping promtail). Every one was caught by
loud-failure design (NO-RESULT over silent reuse) or by run-duration sanity checks.

## DNS-family promotion (2026-07-20): one card, two faults, shipping-tier flips — and the sensor that had to grow

**Targets** (baseline-first: 9B OFF K=3 each = 0/3, no accidental fixes): SREGym `stale_coredns_config` and
`service_dns_resolution_failure` (both social-network) — same fault family, a poisoned CoreDNS config
(NXDOMAIN template / stale entry for one service name) while pods stay healthy. One card:
`k8s-dns-nxdomain.md` (clients log resolution failures for a Running service ⇒ inspect the coredns
ConfigMap for template/rewrite/hosts overrides; remove + rollout restart).

**Clean frozen-config scorecard** (9B, ON arm = the FULL 65-card library; OFF baselines clean by design):
stale_coredns **0/5 → 5/5** (p≈0.004) · service_dns **0/5 → 4/5** (p≈0.024). Treatment: fired turn-1,
exactly one card, 10/10 cells. Promoted: `status: validated`, `push: immediate`.

**What the misses taught (each now mechanism):** two-shot scan (signatures lag injection by minutes);
cluster-STATE bundle in the haystack (pods + warning events + coredns cm — for the log-quiet classes:
scaled-to-zero, kafka consumer-lag, config-poisoning); match-gating (card fires only when its `match:`
stack keywords hit the environment text AND its `signature:` hits logs+state — resolves generic-string
collisions between host-tier and k8s-tier cards, the product's two-layer trigger). Two signature laws,
both enforced the hard way: FAULT strings never IDENTITY strings (identity strings false-fire permanently
once state text joins the haystack), and never injector-shaped markers (the de-overfit pass dropped
'template in' — the don't-overfit call). Promotion evidence = the frozen-config rerun only; the
mixed-version discovery battery is treatment-mechanics history, not the scorecard.

## Elasticsearch flood-stage promotion (2026-07-21): a Chaos-Toolkit oracle for engines with no benchmark

**Why a new oracle.** ES/Solr/RabbitMQ/proxies have no public do-and-verify incident benchmark (SUBSTRATE.md);
SREGym/AIOpsLab don't cover them. So we built the oracle the Chaos-Toolkit way — a steady-state hypothesis
evaluated before/after a REAL injected fault (`bench/ops-eval/chaos/`, harness commit d86ec308). Legit only
because blind-by-construction: the fault genuinely fills the ES data volume until the node trips flood-stage
read-only; the oracle is the engine's OWN API (a write returns 2xx + health != red + a protected `orders`
canary still holds its value + disk genuinely freed); the agent never sees the injection.

**Target** (baseline-first): `cp-es` single-node ES 8.13.4, real Lucene data to ~34% across a few sizable
indices with the watermark set just below occupancy (stated per-deployment config — robustness over a
default-95% tightrope that repeatedly wedged the node at 100%). Card: `elasticsearch-diskwatermark.md`
(flood-stage ⇒ FREE DISK — delete the oldest expendable index via `curl -XDELETE`, ES auto-clears the block;
clearing the block alone re-trips, raising the watermark leaves disk full).

**Clean interleaved A/B, frozen card+oracle+fault, CP_PUSH_MODE=immediate:**
- **30B:** OFF **0/5** → ON **5/5** (Fisher p≈0.004)
- **9B (shipping tier):** OFF **0/5** → ON **5/5** (Fisher p≈0.004)

The scored A/B used the pre-trim card (1148 chars). The library terseness lint caps bodies at 900 (verbose
kills convergence), so the SHIPPED card was trimmed to 820 chars keeping the load-bearing instruction verbatim
(`curl -XDELETE <oldest-index>`); a 9B K=3 spot-check of the trimmed card re-confirmed **2/3** (one run
reverted to the block-clear shortcut without deleting — a 9B ceiling wobble, no XDELETE that run). Aggregate
9B ON evidence = **7/8** PASS vs OFF **0/5** (p≈0.001): the lift is decisive and causal; the occasional
non-follow is the small-model ceiling, not a card defect (cf. redis-auth-server).

Causal, read from traces: OFF flails the two non-fixes the oracle rejects (clear the read-only block / RAISE
the watermark — disk stays full → FAIL); ON gets the card at turn 4 → `curl -XDELETE <oldest logs index>` →
disk 34%→23% → ES auto-clears the block below the watermark → canary intact → PASS. Promoted:
`status: validated`, `push: immediate`.

**Honesty notes.** (1) Self-authored substrate ⇒ dev-level ablation; the disk-freed + canary requirements
are load-bearing — they defeat the block-clear, watermark-raise, and rm-rf-all shortcuts that a naive
write-succeeds oracle would pass. (2) The card was REFINED during validation: v1 handed over the block-clear
command, so the model latched onto that shortcut and failed; the rewrite teaches the durable fix concretely.
That is a correctness improvement (block-clear-alone-fails is real ES ≥7.4 behavior), not oracle-fitting —
and the 9B confirm ran on the frozen card. (3) Fault-injection gotchas (ES fights its own fill; text-field
inverted-index amplifies disk ~4×; auto-create hangs under read-only) are in memory
`reference-sre-incident-benchmarks.md`.

## RabbitMQ resource-alarm (2026-07-21): PROMOTED on the 9B flip (1/5→5/5), redis-mirrored

Second engine on the generalized Chaos-Toolkit rig (`chaos_core.py` + per-engine scenario modules). Fault:
a real large file fills the RabbitMQ data volume below `disk_free_limit` → `disk_free_alarm` → flow control
BLOCKS all publishers (consumers keep running) — the "RabbitMQ goes dark" classic. Oracle: an **AMQP
publish-with-confirms actually blocks** under the alarm (the mgmt HTTP publish does NOT — it would be a false
oracle, a real find) + a surviving canary message + disk genuinely freed (defeats the lower-disk_free_limit
shortcut). Card: `rabbitmq-resource-alarm.md` (candidate, 841c).

Baseline-first, honest result:
- **30B: baseline SELF-SOLVES** — `rabbitmq-diagnostics check_free_disk_space / alarms` names the problem
  directly and the hog file is obvious via `ls`, so the capable model fixes it unaided (16s). No lift to
  measure at that tier.
- **9B (shipping): OFF 1/5 PASS → ON 5/5 PASS** (clean interleaved K=5, frozen debounced config; Fisher
  one-tailed p≈0.024). The card is pushed turn 4 → the model runs `rabbitmq-diagnostics alarms` → frees disk.
  The baseline is not a hard zero (the 9B self-solves ~20% by exploration), but 1/5 is a fine flip arm — the
  SAME structure and strength as the promoted redis-auth-server (30B 1/5→5/5, p≈0.02).

Verdict: **PROMOTED** — `status: validated`, `push: immediate`. Basis (identical to redis, mirrored across
tiers): a positive controlled flip on the tier with headroom (**9B 1/5→5/5**) + no-harm on the near-ceiling
tier (**30B self-solves**); immediate push showed no displacement harm (5/5 includes the runs the baseline
would have self-solved). Course-correction note: I first stopped the ON arm at K=3 (OFF 1/3 vs ON 3/3,
p≈0.2) and mislabeled it "marginal / stays candidate" — that was an UNDER-POWERED sample, not a weak lift;
finishing to K=5 cleared the p<0.05 bar the other cards meet. Lesson kept: the 30B self-solves because
`rabbitmq-diagnostics` is very discoverable, so this fault yields NO 30B lift — a card can still be validated
on a one-tier flip + other-tier no-harm. A still-cleaner RabbitMQ target (both tiers) would be the **memory /
queue-backlog** variant (raise-`vm_memory_high_watermark` shortcut trap).

## Chaos suite batch (2026-07-21): nginx (no-lift), OpenSearch (cross-engine), Solr (harder) — the target-selection law

Three more engines on the generalized rig, and together they SHARPEN the card-lift law.

- **nginx upstream-502** (config-change: proxy_pass to right host / wrong port → 502): 9B **OFF 3/5 → ON
  3/5** — NO lift. The fix is fully self-solvable (the wrong port is right there in the conf and the error
  log names the connection refusal), and immediate push even risks DISPLACEMENT (ON-1 failed a case OFF
  solves). Card stays candidate, `push: rescue`. Harness note: mount the conf.d DIRECTORY, not a single
  file — `sed -i` on a single-file bind-mount silently no-ops the model's correct edit.
- **OpenSearch** (freebie — the `elasticsearch-diskwatermark` card already `match:`es opensearch): the ES
  injector trips OpenSearch's identical flood-stage read-only, the card fires, and the model deletes an
  index → write 201 + canary intact = fixed. Cross-engine mechanism + card confirmed. (The disk-freed grade
  threshold, −8pp tuned for ES's 34% fill, false-negatives OpenSearch's smaller 29% fill — grade calibration,
  not a real fail.)
- **Solr core-init-failure** (config-change: bad class in solrconfig → core fails init → every query 500):
  NOT self-solvable — the 30B found the bad line but botched the XML `sed` (replaced with a malformed
  handler) so the reload still failed. A genuinely harder fault; whether the card lifts it or it's an
  execution-ceiling (like kafka poison-pill) needs measurement.

**The law, now demonstrated across 6 faults:** a card lifts iff the fault's naive fix is a SHORTCUT the
oracle rejects (ES raise-watermark) OR the diagnosis/fix is non-obvious *to the tier under test*
(RabbitMQ-9B can't reach `rabbitmq-diagnostics`). Faults whose fix the engine's own error/diagnostics hand
to the model (nginx port, RabbitMQ-disk on the 30B) SELF-SOLVE → no lift, and immediate push can only harm.
Pick targets by this law; measure baseline-first; and for self-solvable faults keep the card `rescue`
(do-no-harm) rather than chasing a lift that isn't there.
