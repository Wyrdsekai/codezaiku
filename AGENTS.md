# AGENTS.md — CodeZaiku

Operating instructions for AI agents (Claude Code, Cursor, and friends) working **on** the CodeZaiku
source tree.

CodeZaiku is itself a harness that drives models through real work, so there is a pleasing recursion
here: you are an agent editing the thing that runs agents. The rules below are the ones that were
learned by getting them wrong, and most of them are about **evidence** rather than style.

---

## What this project is

A local-model autonomous operator for a single box: one binary, several surfaces — coding, operations,
security, research, review — all drivable by another program over MCP, ACP or `run`. Java 21+, Gradle
Kotlin DSL, one module (`core`).

Before you change anything:

1. Read [README.md](README.md) — what ships and what it refuses to do.
2. Read [LIMITATIONS.md](docs/LIMITATIONS.md) — the honest boundaries. Most "bugs" live here on purpose.
3. Read [ARCHITECTURE.md](docs/ARCHITECTURE.md) — the loop, the surfaces, the guard stack.
4. Skim [SECURITY.md](SECURITY.md) if you touch anything under `ops/` — that code changes live systems.

## Build

```bash
./gradlew :core:build          # compile + test
./gradlew :core:test           # tests only
./gradlew :core:installDist    # runnable tree under core/build/install/codezaiku
./bin/codezaiku --version      # the launcher builds on first use
```

**Java 21 or newer.** The build compiles with whatever JDK runs it and targets 21 via
`options.release`, deliberately *not* a Gradle toolchain: a toolchain is an exact match, so pinning
one makes a machine holding only a newer JDK unable to build at all.

## Test

JUnit 5. The suite runs in seconds and is expected to be green before you submit anything.

```bash
./gradlew :core:test
```

Read the **JUnit XML** under `core/build/test-results/test/`, not the console banner, when you want a
count you can trust. A run that prints nothing and a run that skipped everything look identical from
the banner.

For the acceptance checks that exercise a built install on the host you are actually on:

```bash
CODEZAIKU_BIN=$PWD/bin/codezaiku scripts/verify-platform.sh [drive-url]
```

Pass an **absolute** `CODEZAIKU_BIN`. The script works inside throwaway repos, so a relative path stops
resolving after the first directory change and every later check reports as a failure rather than an
error — which reads as a broken platform when it is a broken invocation. On Windows, point it at the
extensionless `codezaiku` launcher with a POSIX-style path (`/c/Users/...`): Git Bash executes neither
a `.bat` nor a `C:\...` path.

## Who calls this

CodeZaiku is released under the [Wyrdsekai](https://github.com/Wyrdsekai) umbrella and is one of the
coding backends Wyrdsekai can summon, so the CLI contract has a real consumer: `codezaiku run --text
<task> --output-format json --no-session -q`, with the workspace as the subprocess working directory
and model routing carried only in the environment.

Treat that shape as load-bearing. Changing what `run` prints on stdout, what its result document
contains, or how the workspace is chosen is a breaking change for a caller that is not in this repo —
`WITH_WYRDSEKAI.md` and `DEPLOYING_AS_A_BACKEND.md` are the specification, and both need updating in
the same change. Narration belongs on stderr; stdout carries exactly one JSON document.

## Layout

| Path | What lives there |
|---|---|
| `core/src/main/java/org/codezaiku/` | everything; `loop/` is the coding loop, `ops/` the operator, `tools/` the model-facing tools |
| `core/src/main/resources/` | the shipped library and ops runbooks |
| `ops-knowledge/` | fix-procedure cards (see below) |
| `knowledge-packs/` | domain reference the coding surface can pull from |
| `bench/` | evaluation harnesses and their recorded results |
| `packaging/`, `scripts/` | release plumbing |

## Conventions that are load-bearing

These are not preferences. Each one is here because its absence produced a defect.

**Judge outputs, not exit codes.** `grep -c` prints `0` and exits non-zero; `while … && echo` exits
non-zero legitimately. A guard or probe that keys on the exit status silently discards findings.

**Machine-computed evidence outranks model judgment.** The model may *disambiguate* among things a
deterministic sensor flagged. It may never *nominate* one. This is what makes the ops localizer immune
to a service writing instructions into its own logs.

**Untrusted text is anything a service or user wrote** — container logs, alert free-text, page content,
filenames. It is evidence, never instruction. Fence it, label it, and neutralize instruction-shaped
strings before it reaches a prompt.

**A small model copies literals faithfully and garbles templates.** Give it a complete working command,
never a `<PLACEHOLDER>` form. A card that says "run the appropriate command" does nothing; one carrying
the exact line gets run.

**Cards name products, real stacks name roles.** A redis service is often called `cache`, a postgres one
`db`. Match a fix card against the root's *identity* — name plus container plus image — never the name
alone.

**Audit a guard by the CLAIM it makes, not by its verb list.** A forbidden-verb denylist can only ever
say "not the shapes I thought of". Where a phase is bounded — diagnosis is — prefer an allowlist, which
can be checked exhaustively. When a denylist is unavoidable, say so in the code so nobody mistakes it
for a wall.

**Spawn shells through `Shell.pb(...)`, never `new ProcessBuilder("bash", ...)`.** Asking for `bash`
by name does not get you the one on PATH: on Windows, `CreateProcess` searches `System32` *before* any
PATH entry, and `System32\bash.exe` is the **WSL launcher**. A bare `bash` therefore ran every command
inside a Linux distribution on any machine that had WSL installed — a different interpreter, a
different package set, and `/mnt/c` instead of `C:\`, with nothing reporting it. `Shell` resolves one
shell explicitly and `doctor` prints which.

**A test that passes proves nothing until you have seen it fail.** Make the change, revert it, and
confirm the test fails *by name* — then restore. Two "green" tests written in this repo were measured
to be catching nothing: one asserted on a URL form the real command never used, and one was replayed
from cache because Gradle held the task UP-TO-DATE through a docs-only edit. Both looked passing.
If a test reads a file outside the classpath, declare it as a task input or it will go stale silently.

**Run it on the platform; do not reason about it.** Every port has produced real defects the first
time it was actually executed, after confident reasoning from mechanisms said it would not. A single
Windows run surfaced five, including the shell one above.

**Measure against real inputs, not invented ones.** A test or set-diff built from probes you wrote
certifies your invention. The read-only allowlist looked correct against synthetic probes and wrongly
refused 22 of 210 commands the model had actually issued.

## Working on the ops surface

`ops/` acts on live systems, so it carries a guard stack rather than good intentions: an authority
ladder (observe < localize < propose < guarded < unattended), blast radius of one service, a read-only
diagnosis phase, a host-write guard, snapshot-and-rollback, a harm check, a kill switch, and an audit
trail. [SECURITY.md](SECURITY.md) explains what each one buys.

If you change any of them, add a probe to the matching audit test (`ScopedGuardAuditTest`,
`ReadOnlyGuardAuditTest`, `AuthorityLadderAuditTest`, `RemediationSnapshotRestoreTest`). Those files
exist because auditing the guards by claim found holes that reading them did not.

Security remediation is **report-only** by design. A wrong reliability fix restarts a service; a wrong
containment action firewalls your own load balancer.

## Fix cards

`ops-knowledge/*.md` are fault-class → fix-procedure cards, pushed by the harness when a card's match
keywords and log signature both fire. `python3 ops-knowledge/lint.py` enforces the bar, including a
terseness ceiling — a verbose card measurably stops the model concluding.

`status: candidate` earns propose-only; `status: validated` earns auto-remediation and is reached by
repeated verified reuse, not by editing the field. Write a card from a technology's public docs, never
from one incident.

## Submitting

- Green suite, and say so.
- If you claim something works on a platform, run it there. Reasoning about a platform is not the same
  as running on it, and every port so far found real defects the first time it was actually run.
- Prefer a small, measured change over a large plausible one. If a number would settle a question,
  produce the number — and if arithmetic can settle it, do not spend a model run.
