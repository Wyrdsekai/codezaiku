# External validation — faults and oracle we did NOT author

Every scenario in `bench/ops-eval/fixtures` and `compose-scenarios` is one WE wrote. That is the classic
author-on-both-sides trap (the same one that sank the M1–M9 synthetic-bug arc). This directory runs
CodeZaiku's ops loop against **someone else's fault library, scored by someone else's oracle.**

## Source: opensre `tests/synthetic/hermes_rca` (25 scenarios)

From the cloned OSS harness at `~/dev/deeplearning/sre/opensre`. Each scenario ships an `alert.json`
(the incident), several evidence artifacts (`hermes_session_log.json`, `hermes_provider_traffic.json`,
`hermes_config.json`, `hermes_runtime_state.json`), and an `answer.yml` oracle with
`root_cause_category` / `required_keywords` / `forbidden_categories` / `required_evidence_sources`.

**Domain: an LLM gateway ("Hermes") we have never seen** — provider outages, dropped headers, SSE line
overflow, KV-cache format drift, agent hangs, memory-backend failures, missing governance controls. 19
distinct root-cause categories, including a `000-healthy` case.

## What this validates (and what it does NOT)

- **DOES validate:** the diagnostic REASONING core — read evidence, distinguish cause from symptom, resist
  the obvious-but-wrong label, and (000-healthy) refuse to invent a fault — and that it **generalizes to a
  domain we never designed for**, judged by an oracle we did not write.
- **Does NOT validate:** the shell/infra investigation surface. These are RCA-from-artifacts tasks (the
  evidence is files), not live-box faults. It is a genuine external check on the reasoning, not on the
  box-investigation tooling.

## Fairness decisions (stated up front)

1. **The alert LEAKS the answer — so we strip it.** `alert.json.commonAnnotations.failure_mode` carries the
   scenario's own ground-truth label (e.g. `provider_empty_response`). A real alert never contains the
   answer; it is test bookkeeping that leaked into the fixture. We remove that ONE field and keep everything
   an operator would really see (title, summary, description, service, session/provider/model ids).
2. **We supply the label set.** `root_cause_category` must match THEIR taxonomy (`upstream_service_outage`,
   `agent_hang`, …). Their own agent knows these categories; ours has never seen them. Judging us on
   guessing their vocabulary would test naming, not diagnosis — so the 19 valid categories are provided,
   as any classification task must provide its labels.
3. **Prechecks OFF.** Our prechecks detect INFRA faults (systemd/disk/ports/compose) — irrelevant here, and
   `recentChanges` would flag the freshly-copied evidence files as "recently modified" (noise, and a hint).
4. **Scored by THEIR `answer.yml`**, with our `scoring.py` applying the same gates (it accepts their
   `forbidden_categories` spelling). No thresholds were tuned for this suite.
