# Using CodeZaiku with Wyrdsekai

CodeZaiku is released under the [Wyrdsekai](https://github.com/Wyrdsekai) umbrella, and Wyrdsekai can
summon it as a coding backend: you ask the companion for something, it hands the work to CodeZaiku,
and CodeZaiku returns a result document describing what it changed.

You do not need Wyrdsekai to use CodeZaiku, and CodeZaiku does not depend on it. This page is only
for wiring the two together. If you are integrating some *other* host, read
[DEPLOYING_AS_A_BACKEND.md](DEPLOYING_AS_A_BACKEND.md) instead — it documents the same contract
without assuming a particular caller.

## What Wyrdsekai runs

CodeZaiku is a CLI backend, invoked as a subprocess alongside the other coding agents Wyrdsekai
supports:

```bash
codezaiku run --text "<the task>" --output-format json --no-session -q
```

Three things about that invocation are worth knowing, because they are where integrations usually go
wrong:

- **The workspace is the subprocess working directory.** There is no `--workspace` flag. Wyrdsekai
  sets the CWD; CodeZaiku treats it as the project.
- **Pass the task as `--text @<file>`, not as a literal, on Windows.** There the launcher is
  `codezaiku.bat`, so arguments cross cmd.exe and a command line over 8,191 characters is refused —
  a host preamble is usually larger than that alone, and the dispatch dies with `The command line is
  too long` before CodeZaiku starts, so no result document explains it. `--text -` reads stdin
  instead. Both work identically on Linux and macOS, so a host can use one form everywhere. Keep the
  task file outside the workspace: anything inside it is a file the model can read.
- **Model routing travels only in the environment** — `CODEZAIKU_DRIVE` (an OpenAI-compatible
  endpoint, as a base URL), `CODEZAIKU_MODEL`, and `CODEZAIKU_API_KEY` if the endpoint is a hosted one.
  Environment beats any config file on the box, so the host stays in control of which model runs, and
  of the credential, whatever a machine happens to have configured.
- **Tell it when the task is one file.** Adding `--mode artifact` to the invocation says the
  deliverable is a file rather than a change to a codebase: the model writes it, reads it back and
  stops, instead of inventing a test suite to satisfy the usual done-gate. Measured on a one-file
  request: 23 turns and 4324 files in `files[]` by default, against 6 turns and 1 file with the flag.
- **The exit code agrees with `status`.** `0` finished, `2` ran out of turns with real work in
  `files[]`, `1` failed. A run that produced what was asked and then exhausted its budget reports `2`
  and `incomplete`, not a failure.
- **One task, one JSON document on stdout.** Narration goes to stderr; `-q` silences it. The document
  carries `status`, the files touched, and the test-oracle counts. Its fields are specified in
  [DEPLOYING_AS_A_BACKEND.md](DEPLOYING_AS_A_BACKEND.md#the-result-document).

## Wiring it up

```bash
WYRDSEKAI_CODING_CODEZAIKU_ENABLED=true
CODEZAIKU_DRIVE=http://localhost:8200      # your model server
CODEZAIKU_MODEL=<model the server expects> # llama.cpp ignores this; most others do not
```

Wyrdsekai finds `codezaiku` on PATH. Install it however you like — see
[README.md](../README.md#installing) — then confirm the two sides agree before enabling the backend:

```bash
codezaiku --version   # the health check a host should probe
codezaiku doctor      # names anything missing, and the command that fixes it
```

`doctor` is worth running on the machine that will actually spawn the subprocess, not just on your
own shell. It reports the model server, the shell commands will run through, and whether a
vulnerability scanner is available.

## Which model CodeZaiku uses

**Not the companion's.** The coding backend carries its own endpoint and model, configured in
Wyrdsekai alongside the rest of that backend's settings.

Which of the two ways it reaches CodeZaiku depends on whether you set them:

- **You configured them on the Wyrdsekai side** — Wyrdsekai is authoritative. It sends
  `CODEZAIKU_DRIVE` and `CODEZAIKU_MODEL`, and those beat anything on the machine, which is what you
  want when the host owns the model choice.
- **You did not** — Wyrdsekai offers its fallback as `CODEZAIKU_DRIVE_DEFAULT` and
  `CODEZAIKU_MODEL_DEFAULT`, which lose to this machine's own `~/.codezaiku/config`. So a CodeZaiku
  that was installed and configured directly keeps working the way you set it up, and one that was
  never configured gets a sensible default instead of nothing.

The rule is the same one in [CONFIGURATION.md](CONFIGURATION.md): an environment override beats the
config file, which beats a host-supplied default. `codezaiku config list` names the level each
setting came from, so you can see what a summoned run will use rather than guess:

```
drive   http://localhost:8200             [host default]
drive   https://api.example.com           [config file]
drive   https://forced.example.com        [environment]
```

**Credentials are separate.** Wyrdsekai routes the endpoint and the model, not a key. To reach a
hosted provider, `CODEZAIKU_API_KEY` must be present in the environment Wyrdsekai itself runs in so
the subprocess inherits it — see [MODELS.md](MODELS.md#using-a-hosted-endpoint).

## What to expect

**It is a small-model harness.** The reference tier is a 9B, and the honest scorecard is in
[LIMITATIONS.md](LIMITATIONS.md) — coding is capability-bound, and a task that a frontier agent would
complete may not complete here. Read that before deciding which tasks to route this way.

**Egress is gated by Wyrdsekai, not by CodeZaiku.** Wyrdsekai's coding backends run behind an egress
gate that is on by default. CodeZaiku does not sandbox itself: it runs commands on the host, bounded
by timeouts, path scoping and its own guard stack. Those are different guarantees, and the gate is
the one restricting what a task can reach on the network.

**Git writes are off unless you ask for them.** By default CodeZaiku edits the working tree and does
not commit. See *Controlling git writes* in
[DEPLOYING_AS_A_BACKEND.md](DEPLOYING_AS_A_BACKEND.md#controlling-git-writes).

**Cancellation differs by surface.** A killed subprocess still emits its result document on Linux and
macOS. On Windows it does not — there is no SIGTERM to deliver — so if you need cancellation there,
drive CodeZaiku over ACP (`codezaiku acp`), where cancelling is a protocol message rather than a
signal. [PLATFORMS.md](PLATFORMS.md) has the detail.

## The other two surfaces

The CLI subprocess is the simplest integration, not the only one. `codezaiku mcp` exposes every
surface — coding, ops, review, research, security — as MCP tools, and `codezaiku acp` speaks Agent
Client Protocol v1 with streaming and mid-run cancellation. Both are described in
[DEPLOYING_AS_A_BACKEND.md](DEPLOYING_AS_A_BACKEND.md).
