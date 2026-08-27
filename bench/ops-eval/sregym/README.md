# SREGym adapter + runners (provenance copies)

Live copies run from ${CP_HOST}: driver at `"${CP_WORK:-/opt/codezaiku}"/SREGym/clients/codezaiku/driver.py` (registered as agent
`codezaiku`, `container_isolation: false`), runners at `"${CP_WORK:-/opt/codezaiku}"/sregym_*.sh`. These are the versions that
produced the results in ../knowledge/RESULTS_KNOWLEDGE.md (valkey/DNS promotions).

Driver = stage-aware ReAct (diagnosis submit → mitigation fix loop → submit) over DIRECT kubectl
(SREGym's MCP tool fails 3 ways over long runs; the mitigation oracle grades cluster state, so the
channel is fair and identical in both arms), with the knowledge sensor: two-shot deferred scan
(turn 1/+4), logs + state bundle (pods, warning events, coredns cm, kafka consumer-lag probe with the
documented "kafka consumer-group lag stalled" marker), match-gating (card match: keywords vs
"kubernetes k8s kubectl " + pod names/images), per-card or CP_PUSH_MODE-overridden push policy,
CP_LOG_DUMP sigprobe mode. Runner pattern: pidfile, on-box log, mem/disk/stuck-ns guards with loud
ABORT, result() that rejects any CSV older than the run start (never newest-on-disk).
