# Card-validation substrate map (2026-07-20)

The two-tier library ships **candidate** cards (docs-derived, lint-clean, `push: rescue` so they can only
help-or-noop) that a controlled A/B promotes to **validated**. Promotion needs a RUNNABLE + OBJECTIVE
(do-and-verify recovery check) + INCIDENT-shaped substrate. This maps what exists (hunt: 3 parallel agents,
all repos verified reachable).

## Tier 1 — runnable + objective do-and-verify (promote cards here)

- **SREGym** — github.com/SREGym/SREGym (MIT, active). **The unlock.** AIOpsLab-class: live `kind` cluster,
  MCP action API the agent drives with kubectl/shell, and per-problem `*_mitigation.py` oracles that inspect
  LIVE cluster state (e.g. valkey `CONFIG GET requirepass` + `PING` + deployment replicas recovered — a real
  recovery check, not answer-match). Apps: Astronomy(OTel)/Hotel/SocialNet/TrainTicket. **Validates cards
  for: mongo, redis/valkey, kafka, tidb, memcached, nginx/ingress, coredns/dns, k8s-misconfig.** NOT mysql/
  postgres/envoy first-class. Footprint: Lite(20 probs) 8 vCPU/16GB; full needs ~32-64GB + big image pulls.
  Ports almost directly from our AIOpsLab `cp_agent.py` onto `clients/demo/driver.py` (same ReAct-over-
  OpenAI shape); only new plumbing = the MCP/SSE client. **Whitelist the objective `*_mitigation` oracles;
  ignore the `llm_as_a_judge/` detection/diagnosis oracles.** Use kind path (not the SSH+root self-managed
  path). `container_isolation: false` while developing.
- **MicroRemed** — github.com/LLM4AIOps/MicroRemed. Clean Ansible do-and-verify, single-box k3s, but GENERIC
  infra faults (CPU/mem/IO/net/pod/config), not engine-specific. Good remediation-loop cross-check.

## Not usable as-is / deferred

- **DBPA** — github.com/hjhhsy120/DBPA. **NOT a do-and-verify benchmark.** The "throughput X% lower than
  normal" health check is README PROSE, not code — the scripts do no baseline capture and no pg_stat_activity
  check; there is no oracle and no agent (the paper's "agent" is an ML classifier over metric CSVs). To use it
  we'd build the whole OLTPBench baseline→inject→fix→re-measure loop ourselves on brittle classic-OLTPBench +
  pinned PG12. DEFER; only worth it later for postgres perf-tuning cards (index/vacuum/shared_buffers/locks)
  SREGym can't exercise — reusing DBPA's INJECTORS, not its evaluation.
- **Answer-match RCA benchmarks** (D-Bot/DB-GPT[postgres], Cloud-OpsBench, RCAEval, AIOps2025/agenticopseval
  [TiDB+Redis], ITBench, OpenRCA 2.0): objective but DIAGNOSIS/localization scored, not fix-verify — the axis
  the 2×2 already showed our loop adds nothing on. Validate RCA cards only, not fix-procedure cards.
- **BIRD-CRITIC** — execution-verified across PG/MySQL/SQLServer/Oracle, but SQL-QUERY-bug fixing, not ops
  incidents. Off-shape.

## No-substrate engines (validate via authored chaos scenarios, or defer)

The hunt confirmed there is **NO objective benchmark** for the search tier (Elasticsearch/Solr), memcached,
rabbitmq, or proxies (nginx/envoy/haproxy) as first-class scored incidents. Reachable only (a) app-embedded
in SREGym/AIOpsLab, or (b) hand-authored **chaos-toolkit** (steady-state hypothesis before+after) / **chaosd**
(engine-native redis/kafka faults) scenarios where WE supply the recovery oracle. Their cards stay candidate
until then.

## Plan

Stand up **SREGym first** (kind-Lite) → fork `clients/demo` into a ReAct `/v1/chat/completions` adapter with
the deferred-scan + push-policy mechanism → OFF vs card A/B, K≥5, oracle-graded via objective `*_mitigation`
only, 30B filter → 9B confirm → promote the cards it exercises (redis/valkey, kafka, tidb, memcached, coredns,
k8s-misconfig) from candidate to validated. This directly extends the mongo/k8s result to new engines on a
do-and-verify oracle we do not control.
