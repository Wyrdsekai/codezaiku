# Deploying CodeZaiku as a coding backend

For running CodeZaiku underneath another agent or orchestrator — an MCP host, an ACP client, or
anything that shells out to a subprocess. If you want CodeZaiku for yourself at a terminal, start with
[README.md](../README.md) instead; this document assumes something else is doing the driving.

Three integration surfaces, same engine underneath:

| surface | command | use it when |
|---|---|---|
| **CLI subprocess** | `codezaiku run --text …` | your host already shells out to coding backends. Simplest. |
| **ACP** | `codezaiku acp` | you want streamed tool activity and mid-run cancellation |
| **MCP** | `codezaiku mcp` | you want every surface (ops, security, research, review) as tools, not just coding |

---

## 1. Install

You need a **JDK 21+** and an **OpenAI-compatible model server**. CodeZaiku ships no weights.

```bash
git clone https://github.com/Wyrdsekai/codezaiku.git && cd codezaiku
scripts/install.sh                  # ~/.local by default; --system for /usr/local, --prefix DIR
```

The install is self-contained — launcher, jars and knowledge library travel together, and nothing is
downloaded at runtime. For a packaged rollout, `packaging/deb/build-deb.sh` produces a `.deb` that
installs to `/opt/codezaiku` with `/usr/bin/codezaiku`.

Verify the binary is reachable and answers, without touching a model or the network:

```bash
codezaiku --version                 # -> codezaiku 0.1.1, exit 0, ~50ms
```

**This is the health check.** If your host probes backends before activating them, probe this.

## 2. Point it at a model

Three settings matter, and **environment variables beat any config file on the box** — so a host
that injects them is authoritative and need not care what a machine has in `~/.codezaiku/config`:

| variable | default | meaning |
|---|---|---|
| `CODEZAIKU_DRIVE` | `http://localhost:8200` | the OpenAI-compatible endpoint, as a **base URL** — CodeZaiku appends `/v1/chat/completions` itself. The only always-required setting. |
| `CODEZAIKU_MODEL` | `local-model` | the `model` field sent with each request |
| `CODEZAIKU_API_KEY` | unset | sent as `Authorization: Bearer <key>`. Required by most hosted providers, unwanted by a local server. |

`CODEZAIKU_MODEL` matters for Ollama, LM Studio, vLLM and hosted APIs, which use it to **select** a
model; llama.cpp ignores it and serves whatever is loaded. Everything else is optional — see
[CONFIGURATION.md](CONFIGURATION.md).

**Offer, or impose — decide which you mean.** Setting `CODEZAIKU_DRIVE` makes your host
authoritative: it wins over anything the machine has configured, which is what you want when the
host owns the model choice. Setting `CODEZAIKU_DRIVE_DEFAULT` instead supplies a value only when the
machine has not been configured itself, so someone who installed and configured CodeZaiku directly
keeps their setup. Use the plain variable for a value your operator chose, and the `_DEFAULT` form
for your own fallback — otherwise a built-in default of yours silently redirects a deliberately
configured machine, and nothing reports the mismatch. The same suffix works for any setting.

Against a hosted endpoint, `codezaiku doctor` is worth running before you wire anything up: it
distinguishes a rejected credential from a URL that already carries `/v1` from a server that is
genuinely unreachable, which otherwise all look alike.

CodeZaiku speaks OpenAI-compatible HTTP and nothing else. It consumes no provider-specific credentials:
point `CODEZAIKU_DRIVE` at whatever endpoint you want driven, including a gateway that fronts a hosted
API. If your host has a notion of "provider", pass it with `--provider` and it will be echoed back in
the result for your records, but it does not change what we call.

No server yet:

```bash
docker run -d --name codezaiku-drive -p 8200:8200 \
  -v /path/to/models:/models ghcr.io/ggml-org/llama.cpp:server-cuda \
  -m /models/<your-model>.gguf --port 8200 --host 0.0.0.0 --jinja --ctx-size 32768
```

`--jinja` is required. Without it the model returns tool calls as prose and nothing works.

## 3. Verify before wiring anything up

```bash
codezaiku doctor                    # names what is missing and the fix
codezaiku smoke                     # confirms the model answers AND can call a tool
```

`smoke` is the one that matters: a model that responds but cannot emit a tool call will fail every task
in a way that looks like the harness is broken. See [MODELS.md](MODELS.md).

Then run one real task end to end before involving your orchestrator:

```bash
cd /some/scratch/repo
codezaiku run --text "Create hello.py with a function hello() returning 'hi'." \
              --output-format json --no-session -q
```

You should get a single JSON document on stdout and exit 0.

---

## 4. Surface A — CLI subprocess

```
codezaiku run --text <TASK|@FILE|-> --output-format json --no-session -q \
              [--provider <p>] [--model <m>] [--task-id <id>] [--max-turns <n>]
```

- **The subprocess working directory is the workspace.** There is no workspace flag; set the child
  process's CWD. All paths in the result are relative to it.
- **stdout carries exactly one JSON document.** Narration goes to stderr, so you can log it for humans
  without a parser ever seeing it. `-q` silences it entirely.
- **Exit codes:** `0` completed, `1` did not, `2` malformed command line *or* out of turns, `143`
  SIGTERMed. The two `2`s are told apart by stdout: a run that ran emits its document, a rejected
  command line emits nothing and says why on stderr.
- **Unknown flags are ignored**, so a newer caller passing an unrecognised flag will not fail a run.

### Pass the task as a file, not as an argument

`--text` takes one of three forms, and for a real host preamble only the last two work everywhere:

| form | meaning |
|---|---|
| `--text "<task>"` | the literal task |
| `--text @<path>` | read the task from that file |
| `--text -` | read the task from stdin |

**On Windows, `@file` or stdin is mandatory for anything sizeable.** The Windows launcher is
`codezaiku.bat`, so every argument crosses cmd.exe, which refuses a command line over 8,191
characters. A host preamble is often larger than that on its own, in which case the dispatch dies
with `The command line is too long` and exit `1` before CodeZaiku starts — nothing in the result
document, because there is no process to write one. Linux and macOS have far higher limits and will
not show you this.

```bash
# portable: works identically on Linux, macOS and Windows, at any task size
codezaiku run --text @/tmp/task-4f2a.md --output-format json --no-session -q
printf '%s' "$TASK" | codezaiku run --text - --output-format json --no-session -q
```

Two details worth having:

- **Put the task file outside the workspace.** Anything inside it is a file the model can read,
  and a stray instruction file is one more thing to explain away in the diff.
- **An unreadable `@file` is fatal — exit `2`, nothing on stdout.** It does not fall back to treating
  the literal `@C:\...\task.md` as the task, which would spend a full budget and hand you a
  confident document for a task nobody asked for. A task that genuinely starts with `@` is written
  `@@`.

### The result document

```json
{
  "taskId": "your-id",
  "workspacePath": "/abs/path/to/workspace",
  "files": ["mul.py", "test_mul.py"],
  "status": "untested",
  "testsPassed": 0,
  "testsFailed": 0,
  "filesComplete": true,
  "filesSource": "ledger+git",
  "turns": 8,
  "summary": "Created mul.py with mul(a, b)…",
  "model": "local-model"
}
```

**Exit code and `status` always agree**, so a caller can branch on the code alone:

| exit | status | meaning |
|---|---|---|
| `0` | `success` / `untested` | the agent finished. The difference is only whether an oracle verified it. |
| `2` | `incomplete` | stopped on its turn budget. `files[]` still describes real work — it may have produced exactly what you asked and then kept looking for more to do. |
| `1` | `failed` | an oracle failed, or the run did. |
| `143` | — | killed; see *Cancellation*. |

Treat `2` as "ran out of room", not "went wrong". It used to be reported as `failed` with exit `1`,
which is why a caller reading only the exit code would conclude a completed task had failed.

**`status` is deliberately not flattering.** `success` requires that a real test oracle *ran and
passed*, with genuine counts. `untested` means the agent finished and nothing verified its claim —
including when the model's own summary says its tests pass, because that is a claim, not a
verification. `failed` means the oracle failed or the run did. No oracle runs on this path today, so a
completed run reports `untested`. Map it accordingly: treat `untested` as completed-but-unverified, not
as a failure.

**`gitRef` is never present.** CodeZaiku authors no commit, so there is no ref of ours to report. (Note
this is not a guarantee that no commit can happen — a task that asks the model to commit will commit
through the shell. See "Controlling git writes" below.)

### Large repositories and the context window

CodeZaiku pins a project-shape block — the file tree and per-file signatures — into every request, so
the model can place a file without re-reading the tree. That block is **budgeted against the model's
context window**, roughly a fifth of it, and says so when it has been shortened. Without a budget it
scaled with the repository instead: measured on a 926-file tree it reached ~31,900 characters, which
is more than an entire 16k-token window, and a run in a large working tree could not land a single
call whatever the task was.

Two things follow for a host:

- **`CODEZAIKU_CTX` matters here.** If your server's real window is smaller than CodeZaiku detects,
  set it — the pinned block is sized from that number, so an accurate one shrinks the block rather
  than merely lowering the ceiling it overflows.
- **A request that cannot fit stops immediately**, with `status: "failed"`, exit `1`, and a summary
  naming the overflow and the server's own token counts. It is not retried: the prompt is the
  problem, so resending it fails identically. Budget exhaustion is still `incomplete` and exit `2` —
  the two are different situations and a caller should treat them differently.

**A request is bounded before it is sent, not only compacted at a threshold.** Compaction fires at
70% of the window and the pinned block is budgeted, but a single large observation — a mid-run file
read — lands after both. If the assembled request still would not fit, the oldest observations are
trimmed until it does, and each trimmed body says so, because a model acting on a silently truncated
file believing it whole is worse than the overflow.

**Long commands need a declared budget.** The model's shell has a per-command cap
(`CODEZAIKU_SHELL_TIMEOUT_SEC`, 300s; 1200s for commands recognised as training or generation).
Recognition is by name, so a script that legitimately runs for minutes but is not called
`train.py` gets the short cap — set the value rather than relying on the guess. A command killed on
the cap is told the cap expired and that it may need longer, specifically so it does not respond by
re-running itself under a *shorter* `timeout`.

Worth knowing when reading a trace: if the command itself calls the same model server CodeZaiku is
driving, the two compete for it, and a command that finishes in three minutes standalone can exceed
the cap under a run. The harness can be the reason its own child was slow.

Token budgets here assume roughly **three characters per token**, not four. Paths and code tokenize
far denser than prose, and that is exactly what fills the prompt in a large repository — one host
measured 78,637 request characters arriving as 23,461 tokens.

### One-file tasks: `--mode artifact`

CodeZaiku's default posture suits a codebase: on `task_done` the harness re-runs the project's tests
and sends the model back while they are red. For a task whose entire deliverable is one file, that
pressure has nowhere to go, so the model satisfies it by **inventing** a test suite — and then a
build system to run it.

Measured on a request for one JavaScript file, same model, same task:

| | turns | `files[]` |
|---|---|---|
| default | 23 | 4324 (it ran `npm install`) |
| `--mode artifact` | 6 | 1 |

Pass `--mode artifact` when you know the deliverable is a file rather than a change to a project. It
tells the model to write the file, read it back, and stop, and it turns off the gates that would
otherwise keep it going. Everything else — the result document, `files[]`, cancellation — is
unchanged. Expect `untested` rather than `success`, because nothing verified the file; that is the
honest status for work no oracle checked.

### `files[]`

Workspace-relative paths the run created or modified, from two sources unioned: a write ledger that is
exact for tool-mediated writes, and a `git status` **delta** taken before and after the run.

The delta is before/after rather than a single end-of-run status on purpose: a plain status reports
every dirty file in the workspace, including a developer's uncommitted work from before the run — which
a syncing host would then attribute to the agent.

- `filesComplete: true` means the list is authoritative.
- `filesSource` names the mechanisms used. `ledger (shell ran; no git to reconcile)` means the run used
  the shell in a non-git workspace, so the list is a **lower bound**.
- **Dependencies a tool installed are held back**, and `filesExcluded` counts them when any were.
  There is otherwise no filter list here, for a good reason — git's ignore rules are the project's own
  statement about what is noise, and a list of ours would eventually drop a file someone wanted. This
  exception is narrower than that and answers the same objection: it only ever holds back a path that
  is **untracked** *and* sits under a dependency or build directory (`node_modules`, `.venv`,
  `__pycache__`, `target`, `dist`, `vendor`, and similar). A file the project **tracks** is never held
  back, however it is named — committing a vendored dependency is the project saying it matters. A
  file the project **ignores** never reaches the list at all. So this fires only where the project has
  said nothing and a package manager filled the directory in.
- Without it, a request for one JavaScript file returned **4,324 paths, 4,320 of them `node_modules`**,
  because the model ran `npm install` in a workspace with no `.gitignore`. A host syncing on that list
  would copy a dependency tree. Other build artifacts in an unconfigured workspace still appear —
  the exception covers installs, not everything a run leaves behind.

### Cancellation

Send `SIGTERM`. CodeZaiku kills its descendant processes, emits the same JSON document with
`"interrupted": true` and whatever files were already written, and exits `143`. A killed run still
tells you what it changed, because those files are on disk either way.

---

## 5. Surface B — ACP

```bash
codezaiku acp
```

An [Agent Client Protocol](https://agentclientprotocol.com) **v1** agent speaking JSON-RPC 2.0 over
stdio, framed as **newline-delimited JSON** (one object per line, flushed) — not `Content-Length`
headers.

**Implemented:** `initialize`, `session/new`, `session/prompt`, `session/cancel`, `session/close`,
`session/delete`, `authenticate` (a no-op; credentials come from the environment).

**Streaming.** During a prompt we send `session/update` notifications — `tool_call` and
`tool_call_update` per tool, mapped to the ACP taxonomy (`read` / `edit` / `execute` / `search` /
`fetch`) with `locations` for file tools, plus an `agent_message_chunk` carrying the final summary.

**Cancellation** is cooperative: `session/cancel` is honoured at the next turn boundary rather than by
interrupting mid-write, and child processes are killed so a long build actually stops. The pending
prompt then answers with `stopReason: "cancelled"` — a stop reason, not an error.

**Same result shape.** The prompt response carries the §4 document at `_meta.codezaiku`, built by the
same code that writes the CLI document, so the two cannot drift and you parse one shape.

> **Read `_meta.codezaiku`.** Through the 0.x line the same document is also emitted under
> `_meta.codeplane`, the pre-rename spelling, for hosts that integrated before the rename and have
> not changed their reader. Do not write new code against it; it goes away at 1.0.

**We never call `fs/*` or `terminal/*`.** CodeZaiku has its own confined file tools and shell, so a
client that declines those capabilities loses nothing.

**`session/request_permission`** is sent before any shell command that writes git state, so your
client decides whether it runs. See §7 — nothing else is gated, and with no client (the CLI path)
nothing is gated at all.

---

## 6. Surface C — MCP

```bash
codezaiku mcp
```

A stdio MCP server (JSON-RPC 2.0) exposing every surface — coding, ops, security, research, review — as
tools. Use this when you want more than coding. Configure it in your host as a stdio MCP service with
command `codezaiku` and args `["mcp"]`.

**In Claude Code, register it per project, not per user.** The coding familiar belongs to a project;
sessions elsewhere should not carry its tools. From inside the project:

```bash
claude mcp add --scope project codezaiku -- codezaiku mcp
```

which writes a `.mcp.json` next to the code that you can commit:

```json
{ "mcpServers": { "codezaiku": { "command": "codezaiku", "args": ["mcp"] } } }
```

The ops tools (`fix`, `serve`, `watch`, `investigate`) obey the authority ladder and default to
`propose`: a session can diagnose and suggest, and nothing is restarted unless you raised the
authority yourself (`codezaiku config set ops.authority guarded`).

The research library, ResearchZosho, is a separate program with its own MCP server, and that one
does belong at user scope — every session should be able to ask the shelves. Its setup registers it
(`codezaiku install researchzosho`, or `researchzosho setup`); see researchzosho.org.

---

## 7. Operational notes

### Controlling git writes

CodeZaiku authors no commit. The **model** can run `git commit` through the shell if a task asks it to,
which is correct for standalone use — a user who says "commit this" should get a commit.

**On ACP, this is decided by your client.** Shell commands that write git state route to
`session/request_permission` before running, with `allow_once` / `allow_always` / `reject_once` /
`reject_always`. Deny and the model is told the operator refused and to leave the change in the working
tree — the run continues and you get the work without the commit. `allow_always` and `reject_always`
are remembered for the session, keyed by the specific command, so allowing `git commit` does not also
permit `git push`. Read-only git is never gated (the file ledger depends on `git status`), and an
unanswered request refuses rather than proceeding — a gate nobody answered has not gated anything.

**On the CLI subprocess there is no client to ask, so nothing is gated.** A task that asks for a commit
gets one. Your control there is which tasks you route.

### Concurrency

Each `run` invocation and each ACP session is independent; nothing is shared between them except the
model server. `--model` is threaded per invocation rather than written to config, so concurrent runs
against different models do not interfere. Size concurrency against your model server's capacity, not
CodeZaiku's.

### Timeouts

CodeZaiku bounds a run by turns (`--max-turns`, default 40), not by wallclock. If your host enforces a
wallclock limit, hard-kill with SIGTERM — that path is handled and still reports.

### What to log

`stderr` carries the narration (task mode, conventions, library size, per-turn progress). Capture it
per run: when a task goes wrong it is where the reason is, and it costs nothing to keep because the
machine-readable result is on a separate channel.

---

## 8. Troubleshooting

| symptom | cause |
|---|---|
| every task fails immediately, `contextWindow() failed` | `CODEZAIKU_DRIVE` is wrong or the server is down. `codezaiku doctor`. |
| model answers but no work happens | server started without `--jinja`; tool calls come back as prose. `codezaiku smoke`. |
| stdout will not parse | narration reached stdout — pass `--output-format json`, which redirects it |
| `files[]` looks short | check `filesSource`; `no git to reconcile` means shell writes were not captured |
| `status` is always `untested` | expected — see §4. No test oracle runs on this path. |
| requests fail on Ollama / vLLM / a hosted API | `CODEZAIKU_MODEL` must name a model those servers can select |
