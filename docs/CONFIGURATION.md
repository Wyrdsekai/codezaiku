# Configuration

Settings live in a **config file**; environment variables override it. There are about sixty knobs;
**a handful matter** for normal use and the rest are per-surface switches or experiment flags.

```bash
codezaiku init                                    # write a starter config
codezaiku config list                             # what is set, and where it came from
codezaiku config set drive http://localhost:8200  # change a setting
codezaiku config get ops.authority
codezaiku config unset searxng
codezaiku config edit                             # open it in $EDITOR
codezaiku config path
codezaiku doctor                                  # what is missing, and the fix
```

`config set` edits in place, so comments and ordering survive. If a setting is also exported in your
environment — which overrides the file — it says so rather than letting the change look like it did
nothing.

### Where settings come from

First hit wins:

1. **the environment variable** — an explicit export always wins, so scripts and CI keep working
2. **`$CODEZAIKU_CONFIG`**, or `~/.codezaiku/config`
3. **`/etc/codezaiku/config`** — the system-wide file, for a packaged install

### File format

`key = value`, `#` comments, blank lines ignored. Keys may be written three ways and all resolve to the
same setting, so you do not have to remember which:

```ini
drive                     = http://localhost:8200
ops.authority             = propose
CODEZAIKU_OPS_ROLLBACK    = on
```

---

## The ones that matter

**Precedence, highest first:** an environment variable, then this machine's config file, then
`<KEY>_DEFAULT` from the environment, then the built-in default.

That third level exists for hosts. An environment variable carries two different intentions — "the
operator chose this" and "I am filling in a blank" — and CodeZaiku cannot tell them apart. A host
that sends its own built-in default as `CODEZAIKU_DRIVE` silently overrides a machine that *was*
configured, and the mismatch is invisible: the config file still reads correctly, so `codezaiku
doctor` reports healthy while a spawned run goes somewhere else. Sending `CODEZAIKU_DRIVE_DEFAULT`
instead offers a value rather than imposing one, and `codezaiku config list` shows which level each
setting came from.

Two details that matter if you are wiring this up:

- **A present-but-blank value counts as unset**, at every level. Exporting `CODEZAIKU_DRIVE=` does
  not blank out a configured endpoint; resolution simply falls through to the next level. So a host
  that has nothing to say should say nothing rather than send an empty string.
- **Each setting resolves on its own.** A machine that configured only the endpoint takes its own
  endpoint and the host's model, and `config list` labels them separately. Levels mix per key rather
  than applying as a block.

| Variable | Default | What it does |
|---|---|---|
| `CODEZAIKU_DRIVE` | `http://localhost:8200` | The OpenAI-compatible model server. The only genuinely required setting. |
| `CODEZAIKU_MODEL` | `local-model` | The `model` field sent with each request. llama.cpp ignores it and serves whatever is loaded, but Ollama, LM Studio, vLLM and hosted APIs use it to **select** a model — set it there or requests fail. |
| `CODEZAIKU_SHELL` | Git Bash on Windows, `bash` elsewhere | The shell every command is dispatched through. Worth knowing about on Windows, where asking for `bash` does not get you the one on your PATH: `C:\Windows\System32\bash.exe` is the **WSL launcher**, and `CreateProcess` finds `System32` before any PATH entry — so a plain `bash` runs your commands inside the Linux distribution instead. CodeZaiku resolves Git Bash by absolute path to make that a choice rather than an accident. Set this to dispatch elsewhere on purpose. `codezaiku doctor` prints the shell it resolved. |
| `CODEZAIKU_SHELL_TIMEOUT_SEC` | `300` | Per-command wall-clock cap for the model's shell. Raise it when a task legitimately runs for minutes — a step making hundreds of model calls, a full-dataset pass. A command killed on this cap is told that the cap expired and that it may simply need longer, so it does not respond by wrapping itself in a shorter `timeout`. |
| `CODEZAIKU_SHELL_HEAVY_TIMEOUT_SEC` | `1200` | The same cap for commands recognised as training or generation steps. Recognition is by name (`train.py`, `finetune`, `torchrun`, …), so a long-running script called something else gets the ordinary cap — set the value above rather than relying on the guess. |
| `CODEZAIKU_API_KEY` | unset | Credential for the model endpoint, sent as `Authorization: Bearer <key>` on every request including the health probe. Needed by most hosted providers; leave unset for a local llama.cpp or Ollama, which want no header. Accepts the key bare or already prefixed with `Bearer `. `codezaiku config list` reports whether it is set, never its value. |
| `CODEZAIKU_SARIF` | unset | Path to write findings as **SARIF 2.1.0**. Off unless set. Upload it to GitHub code scanning and findings appear on the pull request; DefectDojo, the VS Code SARIF viewer and most vulnerability managers ingest the same file. Unanchored findings are written without a line region rather than at a guessed line. |
| `CODEZAIKU_REVIEW_PASSES` | `1` | How many independent passes `review` makes before merging findings. Raising it trades time for recall by unioning repeated passes: roughly a third of PRs yield findings on some runs and not others. It also multiplies false positives and costs N× the model time — hence off by default. Capped at 5. See LIMITATIONS.md for the measured figures. |
| `CODEZAIKU_SEARXNG` | `http://localhost:8888` | Search backend for the research surface. Needs `json` in its `search.formats` — it is off by default in SearXNG. |
| `CODEZAIKU_OPS_AUTHORITY` | `propose` | Highest rung the operator may act at: `observe` < `localize` < `propose` < `guarded` < `unattended`. Start at `propose`. |
| `CODEZAIKU_OPS_HALT` | unset | Set to `on` to force everything to `observe`. The kill switch. |
| `CODEZAIKU_OPS_ROLLBACK` | `on` | Snapshot before a destructive fix and restore if verification fails. Leave it on. |
| `CODEZAIKU_OPS_AUDIT` | unset | Path for the JSON-lines audit trail. Set it if anything acts on your systems. |

A working minimum:

```bash
export CODEZAIKU_DRIVE=http://localhost:8200
export CODEZAIKU_OPS_AUDIT=$HOME/.codezaiku/audit.jsonl
```

---

## Operations

| Variable | Default | Notes |
|---|---|---|
| `CODEZAIKU_OPS_APP_HEALTH` | auto-discovered | The app's per-dependency readiness endpoint. **This is the load-bearing signal** — it names the failing dependency, including degraded-but-container-healthy faults. Auto-discovery prefers `/ready` over `/health`. **It must return `{"deps": {"<name>": "ok"}}`** — any dependency whose value is not exactly `"ok"` counts as unhealthy, and the before/after difference across a fix is the harm metric. A response without an object `deps` field gives the harm metric no input at all; CodeZaiku logs a warning rather than treating it as healthy. Spring Boot's actuator shape (`components`) is **not** read — expose a small endpoint in this shape instead. |
| `CODEZAIKU_OPS_STACK_LOCALIZE` | from scope | Compose project to sense. Usually comes from the `fix <scope>` argument. |
| `CODEZAIKU_OPS_VERIFY_CMD` | derived | Command whose exit status decides whether a fix worked. Derived from the health endpoint if unset. |
| `CODEZAIKU_OPS_KNOWLEDGE` | auto | Directory of fix cards. Resolved in order: this variable, `$CODEZAIKU_HOME/ops-knowledge`, the directory beside an install, then `./ops-knowledge`. Set it only to point somewhere unusual. |
| `CODEZAIKU_KNOWLEDGE_PACKS` | auto | Same resolution, for the evergreen knowledge packs. |
| `CODEZAIKU_OPS_LEARN` | unset | Directory for cards learned from successful recon. Enables learn-back and auto-promotion. |
| `CODEZAIKU_OPS_PROMOTE_N` | `3` | Verified reuses before a learned card is promoted from candidate to validated. |
| `CODEZAIKU_OPS_RECON` | on | Derive a fix read-only when no card matches. |
| `CODEZAIKU_OPS_PRECHECK` | on | Deterministic checks before the model spends a turn. Set `off` to measure raw model diagnosis. |
| `CODEZAIKU_OPS_ALERT_WEBHOOK` | unset | POST target for harm and rollback events. |
| `CODEZAIKU_OPS_HALT_FILE` | unset | Path whose existence forces `observe`. Useful for external control. |
| `CODEZAIKU_OPS_REMEDIATE` | unset | `on` raises the default ceiling from `localize` to `guarded` for surfaces that take no explicit ceiling argument. Prefer passing the ceiling per command. |
| `CODEZAIKU_OPS_COMPOSE_PROJECT` | auto | Compose project name, when it cannot be derived from the scope. |
| `CODEZAIKU_OPS_RUNBOOK` | unset | Runbook listing mode for the operator's prompt. |
| `CODEZAIKU_OPS_CHANGES` | on | Recent-change sensing (a fault that appeared right after a deploy is usually the deploy). `off` disables it. |
| `CODEZAIKU_OPS_TURN_FEEDBACK` | unset | `on` adds per-turn probe feedback during diagnosis. |
| `CODEZAIKU_LOCALIZER_MODEL` | falls back to `CODEZAIKU_DISTILLER_MODEL` | Model name for the localization hand-off. |
| `CODEZAIKU_WIRING_MODEL` | falls back to `CODEZAIKU_DISTILLER_MODEL` | Model name for the wiring step. |

## Security

| Variable | Default | Notes |
|---|---|---|
| `CODEZAIKU_OPS_SECURITY` | on | Posture and intrusion reporting on the `watch` loop. |
| `CODEZAIKU_OPS_FALCO_ALERTS` | `/var/log/falco/alerts.json` | Falco JSON alert stream. Without it, intrusion detection reports *"nothing is watching"* — never "no intrusion". |
| `CODEZAIKU_OPS_FALCO_MIN_PRIORITY` | `WARNING` | Severity floor for reported detections, applied to whichever sensor is active. |
| `CODEZAIKU_OPS_WAZUH_ALERTS` | `/var/ossec/logs/alerts/alerts.json` | Wazuh's JSON alert stream — the fallback detector where Falco cannot run, macOS above all. Falco is preferred when both are present because it sees syscalls. **Wazuh is not an equivalent**: on macOS it analyses the unified log, file integrity and polled command output, so it observes what was *logged*, not what the kernel saw. `secure` names the active sensor and its reach rather than reporting a bare "none detected". Verified against a live wazuh-manager 4.9.2. |
| `CODEZAIKU_OPS_TLS_ENDPOINTS` | unset | Comma-separated `host:port` list for certificate-expiry checks. Opt-in on purpose: a proactive scan must never surprise-probe a host you did not name. |

## Proactive maintenance

| Variable | Default |
|---|---|
| `CODEZAIKU_OPS_DISK_WARN_PCT` | `85` |
| `CODEZAIKU_OPS_MEM_WARN_PCT` | `90` |
| `CODEZAIKU_OPS_CERT_WARN_DAYS` | `14` |
| `CODEZAIKU_OPS_HORIZON_HOURS` | `48` — how far ahead a trend must project to be reported |

## Research

| Variable | Default | Notes |
|---|---|---|
| `CODEZAIKU_RESEARCH_POOL` | `~/.codezaiku/research/findings.jsonl` | Accumulated findings. **Use a separate pool per experiment arm** — a shared pool carries one arm's findings into another and invalidates the comparison. |
| `CODEZAIKU_GAPREFLECT` | on | Gap-reflection loop. Measured *unproven* (see LIMITATIONS.md); left on because it shows no harm. |

## Driving CodeZaiku from another program

| Variable | Default | What it does |
|---|---|---|
| `CODEZAIKU_RUN_MAX_TURNS` | `40` | Turn budget for `codezaiku run`. CodeZaiku bounds a run by turns, not wallclock — if your host enforces a time limit, hard-kill it and read the result document it emits on the way out. |
| `CODEZAIKU_ACP_PERMISSION_TIMEOUT` | `600` | Seconds `codezaiku acp` waits for a `session/request_permission` answer. Generous because the far side may be asking a human. **An unanswered request refuses** — a gate that proceeds because nobody replied has not gated anything. |

See **[DEPLOYING_AS_A_BACKEND.md](DEPLOYING_AS_A_BACKEND.md)** for the full contract.

## Coding

| Variable | Default | Notes |
|---|---|---|
| `CODEZAIKU_SELFVERIFY` | on | Ask the model to run its own deliverable end-to-end before finishing. |
| `CODEZAIKU_BOOTGATE` | on | Harness-owned end-to-end boot of an assembled HTTP app. |
| `CODEZAIKU_PROJECT_MEMORY` | on | Per-project conventions loaded into context. |
| `CODEZAIKU_DISTILLER_URL` | unset | Larger model for one-shot localization hand-offs. Optional. |
| `CODEZAIKU_DISTILLER_MODEL` | `default` | The `model` field sent to that endpoint. |
| `CODEZAIKU_READ_CAP` | `12000` | Chars a single `read_file` may return. Above it the tool returns the file's first lines for structure and asks the model to `grep -n` then read a range. **Measured, not guessed**: against a 30k cap this was ~24% cheaper in tokens at identical task success, because a large file enters the conversation once and is then re-sent on every later turn. See LIMITATIONS.md. |
| `CODEZAIKU_FAMILIAR_MEMORY_DIR` | `~/.codezaiku/familiar-memory` | Where per-project conventions are kept. |
| `CODEZAIKU_OCEAN_DIR` | auto | Location of the library/embedding index. |

## Remote and container targets

| Variable | Notes |
|---|---|
| `CODEZAIKU_SSH_PASSWORD` | Only if key-based auth is unavailable. Prefer keys. |
| `CODEZAIKU_EXEC_CONTAINER` / `CODEZAIKU_EXEC_WORKDIR` | Pin execution to a container and working directory. |

---

## Experiment flags

These exist to *measure* the system, not to run it, and several intentionally make it worse. They are
documented so the benchmark scripts in `bench/` are readable, not because you should set them:
`CODEZAIKU_LIB_OFF`, `CODEZAIKU_DEV_ANSWERS`, `CODEZAIKU_DEV_BASELINE`, `CODEZAIKU_EDIT_TELEMETRY`,
`CODEZAIKU_OPS_TELEMETRY`, `CODEZAIKU_OPS_TRIAL`, `CODEZAIKU_OPS_RECON_JAIL`,
`CODEZAIKU_OPS_PYTHON_SESSION`, `CODEZAIKU_FORCE_GROUNDING`, `CODEZAIKU_COMPACT_MASK_FIRST`.

`CODEZAIKU_COMPACT_MASK_FIRST` is off for a measured reason rather than a cautious one: it reclaims
context by masking old tool results before paying for a summary, and on read-heavy coding runs
compaction **never fired at all** — context peaked at 26% of a 32k window against a 70% trigger. Tool
output is already bounded when it is captured, so there is nothing for it to do. It stays in because
a long maintenance run might yet cross the threshold, and then it would.

A few internal knobs are deliberately absent from this page because setting them by hand is a
mistake rather than a choice — toolchain discovery (`CODEZAIKU_FIXTURE_JAVA_HOME`,
`CODEZAIKU_MISE_SHIMS`) resolves itself and `codezaiku doctor` reports what it found.

---

## Precedence

An explicitly set variable always wins over auto-discovery. Where CodeZaiku can work something out from
the environment — the app health endpoint, the verify command, the compose project — it does, and says
so in its output. That is why `fix <scope> <ceiling>` needs no per-stack configuration at all.
