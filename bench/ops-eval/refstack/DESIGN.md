# CodeZaiku Autonomous SRE — cp-refstack design & roadmap

> Status: DESIGN (agreed in discussion, 2026-07-21). Not yet implemented.
> Supersedes the ad-hoc single-engine chaos scenarios as the *integration* target for the ops work.

## 1. Objective

Make CodeZaiku's OpsLoop able to **autonomously SRE / maintain a sophisticated, production-shaped
multi-service AI stack** — sense → localize → remediate → verify — and *earn* unattended operation
fault-class by fault-class. Reference for the shape: the `askmyu` local-dev stack (~30 services: relational,
cache, queue, vector, graph, full-text, object store, LLM serving, reverse proxy, app tier). We build a
**representative** of that shape (not askmyu itself), because askmyu needs the app source + ~20 API keys.

## 2. Why this substrate (the research gap)

The chaos rig validated ops-knowledge cards **one engine at a time** on synthetic single-container
substrates. Two findings shaped this design:
- The 30B **self-solves** most single-engine faults; the card lift is a **9B (shipping-tier)** story, and
  only for faults whose naive fix is a shortcut the oracle rejects or is non-obvious to the tier.
- The genuinely **untested hard part is multi-service LOCALIZATION + cascade diagnosis** — in a chaos
  scenario there is one container and one fault, so the model never has to find *which* service is the
  root. A real SRE target adds exactly that.

cp-refstack exists to exercise localization + the loop + the library together, and to be the measured
substrate on which fault-classes climb the autonomy ladder.

## 3. The substrate: cp-refstack (HYBRID)

**Hybrid** = a purpose-built app that *actually couples* the engines (so a fault anywhere manifests
end-to-end — real cascades), while we keep **objective per-service oracles** (so scoring stays clean). This
is the measurement rig extended to multi-service, not a fuzzy demo.

**The app (synthetic, we write it) = a BASIC simple RAG app.** Minimal ingest→query flow, no product
features — just enough of a real RAG path that a fault in any engine breaks a real query end-to-end. It
touches every engine, so no engine is dead weight:
- upload doc → **LocalStack S3** (storage) → **LocalStack SQS** (async ingest queue) →
- parse/embed → **Qdrant** (vectors) + **OpenSearch** (keyword) → **Neo4j** (entity graph) →
- metadata → **Postgres**; **Redis** cache; **Ollama** for embeddings/LLM answers; **Nginx** edge.

**Service topology (single-box, docker-compose, ${CP_HOST}):**

| Role | Service | Oracle (engine's own API) | Card status |
|---|---|---|---|
| Edge | Nginx | proxied request → 200 + real backend body | nginx-upstream-502 (candidate) |
| App | ingest+query app (synthetic) | end-to-end: upload→query returns the canary answer | (the coupling signal) |
| Relational | Postgres | `SELECT 1` + a canary row | TODO card |
| Cache | Redis | PING + GET canary | redis-auth-server (validated) |
| Queue | RabbitMQ | AMQP publish-with-confirms + canary msg | rabbitmq-resource-alarm (validated) |
| Vector | **Qdrant** | upsert + search canary + disk freed | qdrant-disk-full (candidate) |
| Full-text | OpenSearch | write 2xx + health + canary + disk freed | elasticsearch-diskwatermark (validated, cross-engine) |
| Graph | Neo4j | cypher canary query | TODO card |
| Object/AWS | **LocalStack** (S3+SQS) | GET object 200 + real content / queue drains | s3-delete-marker (candidate) + SQS TODO |
| LLM | Ollama | /api/generate returns tokens | TODO card |

**Decisions made:** vector DB = **Qdrant** (lighter than Milvus, card already built). LLM = **Ollama**
(local, no GPU-farm, single-box fit). LocalStack **community 3.x** carries the AWS path (S3 + SQS) — enforces
versioning/queues, not IAM policy (known limitation). Full engine breadth is *monitored*; a realistic subset
runs *hot* to fit ~16GB (the solo-dev tier per CLAUDE.md).

## 4. The autonomy model — EARN IT per fault-class

There is **no global "autonomous" switch.** Autonomy is earned per fault-class, using the same do-and-verify
discipline that validates the cards — now applied to the *action*, not just the knowledge. The system is
always **"unattended on the classes that earned it, escalate-to-human on everything else."**

**The authority ladder (a fault-class climbs it):**
- **R0 Observe** — watch health + logs, no action.
- **R1 Localize** — name the *root* unhealthy service (not a downstream symptom). Scored on localization accuracy.
- **R2 Propose** — diagnose + present the fix (the card); a human approves. **Default for any un-validated class.**
  Surfacing for now = **logging** (no Slack/webhook yet).
- **R3 Guarded auto-remediate** — dry-run → apply → verify → rollback-on-fail, blast-radius = one service.
- **R4 Unattended** — no human in the loop.

**The R4 gate** — a class reaches unattended only when, on cp-refstack across K≥5 (shipping tier, ideally both):
1. **Detector fires reliably** (sensor validated, low false-positive),
2. **Localization correct** every time (never acts on a cascade symptom),
3. **Card validated** (a controlled flip on the objective oracle),
4. **Remediation proven safe** — **zero harm** in the A/B (never made anything worse), stays in the guardrail,
5. **Recovery stable** — the recovered-oracle re-checks after a settle (no transient-pass).

Clear all five → unattended for that class. Miss any → stays at R2. "Getting to unattended" = run the
measured loop and let classes graduate — candidate→validated / rescue→immediate, extended to the whole loop.

### Localization (R1) — the new capability, and its division of labor (DECIDED)

Localization is the genuinely new thing (chaos scenarios have one container / one fault → the model never
has to find *which* service is the root). In a cascade, MANY services go red at once — Qdrant fills its disk
→ the app 500s → nginx 502s → the SQS consumer backs up — so a naive "which service is unhealthy?" health
scan conflates root and symptom and may act on the wrong service (restart nginx when Qdrant is the cause).

**Approach = dependency-graph-aware, but the harness SENSES + GROUNDS and the MODEL REASONS** (per the
no-planner / harness-doesn't-do-the-model's-job philosophy — [[reference-harness-consensus-no-planner]],
[[feedback-no-action-interceptor-gates]]). Concretely:
- harness **senses**: each service's health + recent logs + its per-service oracle result;
- harness **grounds**: the dependency topology (the map — free from compose `depends_on`) AND the
  **error-origin** signal (the app log usually names its failing dependency: "connection refused to
  qdrant:6333", "No space left on device");
- model **reasons** the root: "app is red but depends on Qdrant; Qdrant is red and its own deps are green;
  the app error names Qdrant → root = Qdrant." The root is the deepest red node whose dependencies are all
  green.

The harness must NOT hard-code the RCA algorithm — that would make localization plumbing, not a measured
capability, and we specifically want to measure whether the model can localize (the new hard thing). The
single richest signal is the **app-tier error naming the dependency**, so the sensor always surfaces the
app's errors alongside the topology, not just per-engine health. **R1 metric = localization accuracy** (did
it name the true root?), baselined against a deterministic deepest-red-green-deps oracle to see where the
model wins or fails.

## 5. Guardrails (hold at EVERY rung, non-negotiable)

- **Blast radius = one service**; no cross-service destructive ops.
- **Snapshot-before-destructive** — volume-tar export/import (the askmyu pattern) is the rollback primitive;
  a destructive fix verifies a backup/canary first (the chaos-oracle canary discipline).
- **Dry-run / preview** for anything mutating; **rollback** on failed verify.
- **Confidence gate** — act unattended only on a *single strong* localization + a validated class; ambiguous
  or novel → drop to R2 (propose + log).
- **Kill-switch + audit log** of every autonomous action.

## 6. Measurement (the new metrics beyond card pass/fail)

Per fault-class, an A/B on cp-refstack: inject → record {detected?, localized-correctly?, remediated?,
recovered-stably?, **harm?**}. New loop metrics: **localization accuracy**, **remediation success**, **MTTR**,
and the load-bearing **harm rate** (did an auto-action degrade something that was fine?). Unattended is earned
when harm-rate ≈ 0 on the class. Baseline = the stack with CodeZaiku OFF (fault persists / a flailing human).

## 7. Roadmap

- **P0 — Substrate. [DONE 2026-07-21]** cp-refstack stands on ${CP_HOST} (`"${CP_WORK:-/opt/codezaiku}"/refstack`, compose in
  `bench/ops-eval/refstack/`): 9 engines + LocalStack + the synthetic RAG app + nginx; end-to-end RAG works
  (ingest canary → query returns it); fault-sensitive + the app `/health` emits the per-dep localization
  signal (stop qdrant → names `qdrant: down`, others ok, query 500s). NEXT: P1 whole-stack OpsLoop localizer.
- **P1 — Whole-stack OpsLoop (localization). [DONE 2026-07-22, in the PRODUCT]** `StackLocalizer` senses all
  services → deterministically names the root → matches a card (root-scoped) → remediates → verifies. 9B 15/15,
  0 harm. See RESULTS.md "THE ACTUAL PRODUCT".
- **P2 — Autonomy loop. [DONE 2026-07-22/23]** `watch` (continuous, idle-on-green / fix-on-degraded) → act at
  the class's earned rung (the full **R0–R4 authority-ladder runtime**, `OpsAuthority`: kill-switch, audit,
  confidence gate, trial lane) → root-scoped verify → R3 rollback → harm-check. **Ladder-climbing is automated**
  (`OpsPromote`: N verified reuses → candidate→validated). Multi-fault fix-until-green; ssh/remote scope; HTTP
  `serve` + systemd daemon; alert webhook + `/audit` dashboard. **Card gaps are largely OBVIATED by
  recon-for-unknown** (`OpsLearn`): a localized-but-no-card fault is investigated + a fix derived + (on success)
  memoized as a learned card. MEASURED: recon subsumes cards for TRACTABLE faults (rabbitmq-permissions 5/5
  card-OFF); cards remain essential only for the HARD tail (postgres-readonly 0/5 card-OFF). So "fill card gaps"
  → "author a card only where recon-OFF fails."
- **P3 — Maintenance beyond incidents. [PARTIAL → the remaining ops frontier]** `watch` is the reactive half.
  Still to build: PROACTIVE threshold checks (act before an outage: disk trending full, cert expiring, capacity)
  + a run_dev-style hygiene surface (snapshot/restore, upgrades).
- **The DEGRADED / LOG-REASONING frontier [the named hard one, unbuilt].** Faults where a service is UP (passes
  the write-probes / docker health) but MISBEHAVING — latency, partial/intermittent failures, wrong-but-not-down
  — where the root is visible only in LOGS/STATE, not binary health. All current detection keys off "down"; this
  is the "up but wrong" class.

## 8. Reuse (what already exists)

- **Chaos rig** (`bench/ops-eval/chaos/`): `chaos_core.py` + per-engine scenario modules + injectors for
  ES/OpenSearch, RabbitMQ, Qdrant, LocalStack-S3, (Milvus, Solr, nginx). These become cp-refstack's fault
  injectors + per-service oracles.
- **Validated cards** (8): redis-auth-server, elasticsearch-diskwatermark (cross-engine → OpenSearch),
  rabbitmq-resource-alarm, k8s-dns-nxdomain, mongo-*, k8s-workloads. Candidates for qdrant, s3, nginx, solr.
- **Ported sensor** in the product (`OpsLoop`/`OpsKnowledge`/`FamiliarMain`): two-shot scan, match-gating,
  state-bundle probe, cap-to-one card, immediate/rescue policy.
- **Target-selection law**: validate on faults where the naive fix is a shortcut the oracle rejects or is
  non-obvious to the tier; self-solvable faults yield no lift.

## 9. Open questions

RESOLVED:
- **Synthetic app** = a BASIC simple RAG app (minimal ingest→query, no product features; couples all engines). §3.
- **Localization** = dependency-graph-aware; harness senses + grounds (health + logs + topology + error-origin),
  model reasons the root; harness does NOT hard-code RCA. R1 metric = localization accuracy vs a deterministic
  deepest-red-green-deps baseline. §4 → "Localization (R1)".

STILL OPEN:
- Which engines run *hot* by default within the 16GB budget vs. monitored-only.
- The DEGRADED / log-reasoning frontier (up-but-misbehaving faults) — richer sensing than binary health +
  a degraded-aware verify.
- P3 proactive maintenance (threshold checks + hygiene surface).

RESOLVED SINCE (see RESULTS.md "THE ACTUAL PRODUCT"):
- Synthetic-app framework → FastAPI, coupling all engines (P0). [done]
- Card design for the roster → recon-for-unknown handles the tractable tail without cards; author a card only
  where the recon-OFF A/B fails (postgres-readonly). Neo4j/Postgres/Ollama fault classes exercised. [reframed]
