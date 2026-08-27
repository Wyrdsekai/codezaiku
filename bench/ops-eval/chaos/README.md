# Chaos-Toolkit-style card validation (engines with no incident benchmark)

Engines like Elasticsearch, Solr, RabbitMQ and the proxies have **no public do-and-verify incident
benchmark** (see `../knowledge/SUBSTRATE.md`). SREGym / AIOpsLab cover k8s/redis/kafka/etc. but not these.
To validate ops-knowledge cards for them we build the oracle the **Chaos-Toolkit way**: a *steady-state
hypothesis* evaluated **before and after** a real injected fault. The steady-state check IS the objective
oracle — it queries the engine's own API, so no LLM judge and no answer the agent can see.

## The discipline (same as SREGym; guards the self-fixture trap)

A self-authored substrate is only legitimate if it stays **blind-by-construction**:

1. **Real fault** — not a mock. We genuinely fill the ES data volume past the flood-stage watermark; ES
   really flips indices to `read_only_allow_delete` and writes really fail.
2. **Objective oracle = the engine's own API** — `steady_state()` requires (a) a write returns 2xx, (b)
   cluster health != red, (c) a protected canary doc survived. No text grading.
3. **Agent is blind** — it sees the incident text + whatever it inspects; it never sees how the disk got
   full, and the oracle rewards *any* correct outcome (free disk by deleting old indices / removing the
   hog / growing the volume), never one specific command.
4. **The oracle enforces the real fix** — clearing the read-only block on a still-full node re-trips within
   ~30s (ES re-applies it), and a destructive "delete all data + restart" wipes the canary and FAILS. So a
   PASS means: disk freed **without losing live data** — the actual skill.

Same model both arms; `CP_KNOWLEDGE` on/off (the deferred-scan card push) is the only difference. The push
mechanism is identical to the SREGym driver: two-shot scan, match-kw gating, at-most-one card, immediate
vs rescue policy.

## The Elasticsearch flood-stage scenario

- `es_fill.py` (the fault): on a **2 GB tmpfs** data mount, index ~450 MB of real incompressible Lucene
  data across a few rolling `logs-*` indices, plus the `orders` **canary** (protected live data) and an
  empty `cp-probe` (so the oracle's write hits an existing index — a fast 429 under flood, not an
  auto-create hang). Restore ES's **default 95%** watermarks, then `fallocate` a realistically-named disk
  hog (`heapdump-1.hprof` — a stale JVM heap dump, a genuine cause of full ES data volumes) to push the
  mount to ~96%. The node is then genuinely >95% full and trips flood-stage read-only at the **real default
  threshold**. A file (not more ES data) holds the disk full, so the fault is **stable** and never spikes
  the tmpfs to 100% (which wedges the node); ES's own data would merge/reclaim and fight the fill.
- `chaos_es.py` (the harness): restart the container for a pristine node → fill (reset the fault) → assert
  steady-state FAILS → run the ReAct agent (host shell, scoped to the `cp-es` container) → poll steady-state
  → record PASS/FAIL. Card matched by `elasticsearch-diskwatermark.md`.

Fix paths that PASS (all drop below the watermark and preserve `orders`): delete an old `logs-*` index
(the card's primary remediation), remove the heap dump, or grow the volume. Paths that FAIL: FS-level
deletion of Lucene segment files without a restart (deleted-but-open files don't free space), clearing the
block only (re-trips), or wiping all data (canary gone).

## Run

```bash
# one run (OFF baseline):  reset happens inside chaos_es.py
AGENT_API_BASE=http://localhost:8201 python3 chaos_es.py
# ON arm: point CP_KNOWLEDGE at a dir of cards
CP_KNOWLEDGE="${CP_WORK:-/opt/codezaiku}"/chaos/kb-es AGENT_API_BASE=http://localhost:8201 python3 chaos_es.py
# interleaved A/B:
bash chaos_ab2.sh 5 http://localhost:8201 ab30b
```

Env: `TARGET_PCT` (fill %, default 96), `DATA_MB` (real data, default 450), `CP_ES_URL`
(default http://localhost:19200), `CP_ES_CONTAINER` (default cp-es), `CP_MAX_STEPS`, `CP_RESULT_CSV`.
