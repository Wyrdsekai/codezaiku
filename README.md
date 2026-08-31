<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://codezaiku.org/img/emblem-dark-512.png">
  <img alt="" src="https://codezaiku.org/img/emblem-512.png" width="190">
</picture>

# CodeZaiku

A harness for driving **small local models** through real work: writing and maintaining code,
operating a service stack, reviewing diffs, checking security posture, and researching questions
against live sources.

Most agents like this assume a frontier model behind someone else's API. CodeZaiku is built the
other way round — a 9B on hardware you own — and the harness does the work that makes that viable:
deterministic evidence instead of model guesses, a guard stack around anything that acts, and
verification with rollback on every change.

It runs against any OpenAI-compatible server and ships no weights. The reference tier is a 9B.

→ **[ARCHITECTURE.md](docs/ARCHITECTURE.md)** — how it works
→ **[LIMITATIONS.md](docs/LIMITATIONS.md)** — the measured numbers

---

## Quick start

You need a **JDK 21+** and an **OpenAI-compatible model server**. CodeZaiku ships no weights.

Linux is the supported platform. macOS runs the coding and research surfaces (and can operate remote
Linux hosts over ssh); Windows works under WSL2. There is a container image. See
**[PLATFORMS.md](docs/PLATFORMS.md)**.

### Three ways to use it

**At a terminal, for yourself** — carry on below; the commands are in *What you can do with it*.

**Underneath another agent or orchestrator** — CodeZaiku is a backend as well as a tool. It speaks a
CLI-subprocess contract (`codezaiku run`), **ACP** (`codezaiku acp`) and **MCP** (`codezaiku mcp`).
The contract, the result shape and the failure modes are in
**[DEPLOYING_AS_A_BACKEND.md](docs/DEPLOYING_AS_A_BACKEND.md)**; start there rather than here.

**Underneath Wyrdsekai** — CodeZaiku is released under the
[Wyrdsekai](https://github.com/Wyrdsekai) umbrella and is one of the coding backends Wyrdsekai can
summon: you ask the companion for something, it hands the work to CodeZaiku.
**[WITH_WYRDSEKAI.md](docs/WITH_WYRDSEKAI.md)** is the short version of the page above — just the wiring,
and the two things that trip people up. Nothing here depends on it; CodeZaiku runs standalone.

### Installing

| path | needs | good for |
|---|---|---|
| **one-line install** — `curl … \| sh`, or `irm … \| iex` on Windows | a **JDK 21+** | **the recommended path.** Verifies the download against the release checksums |
| **release tarball** — unpack and run; nothing to build | a **JDK 21+** | doing it by hand, or air-gapped |
| **Docker** — `packaging/docker/Dockerfile` | docker only | trying it with nothing on the host |
| **`.deb`** — from the release, or `packaging/deb/build-deb.sh` | a JRE, pulled automatically by apt | Debian/Ubuntu, no build |
| **from source** — `scripts/install.sh` | a **JDK 21+** and network for the build | development |

**There is one binary artifact and it is platform-independent.** CodeZaiku is JVM bytecode with no
native parts, so the same tarball installs on Linux, macOS and Windows — there is no `.pkg` or `.msi`
to look for, and none is needed.

Linux is the reference platform. **macOS and native Windows are measured too**, and the installer is
verified on all three from a clean unpack. On Windows, install **from Git Bash** — the harness shells
out through `bash`, and Git for Windows supplies it. See **[PLATFORMS.md](docs/PLATFORMS.md)** for
per-platform steps and for what does and does not work on each.

**Linux and macOS:**

```bash
curl -fsSL https://codezaiku.org/install | sh
```

**Windows**, from PowerShell (you also want Git for Windows — the harness shells out through its
bash):

```powershell
irm https://codezaiku.org/install.ps1 | iex
```

Only the script comes from `codezaiku.org`; the artifact and the checksums both come from the same
GitHub release. What that URL serves is [`scripts/install-remote.sh`](scripts/install-remote.sh)
(and [`scripts/install.ps1`](scripts/install.ps1)) from this repository, verbatim — read them here
first if you would rather, and diff them against what the site serves. Both installers fetch the release tarball and **verify it against the release's own
`SHA256SUMS` before installing anything** — a mismatch refuses rather than proceeds. Only the script comes from
the URL above; the artifact and the checksums both come from the same GitHub release, so the script
cannot hand you a payload those checksums do not match. Piping a script into a shell is worth being
wary of in general: fetch it, read it, then run it if you would rather.

They install to `~/.local` (or `%LOCALAPPDATA%\Programs` on Windows). `CODEZAIKU_PREFIX` puts it
somewhere else, `CODEZAIKU_VERSION` pins a release.

**No JRE on the machine yet?** On Debian or Ubuntu, take the `.deb` from the
[latest release](https://github.com/Wyrdsekai/codezaiku/releases/latest) instead — it declares a JRE
dependency, so `apt` installs one for you:

```bash
sudo apt install ./codezaiku_0.1.1_all.deb
```

The one-liners deliberately do not install a JRE themselves: a script piped into a shell should not
be reaching for `sudo`. They check for one, and say so if it is missing.

**To upgrade, run the same command again.** The installers resolve the latest release each time and
replace the old install rather than writing over it, so nothing stale is left behind. Your config and
data in `~/.codezaiku` are untouched. The `.deb` upgrades in place with
`sudo apt install ./codezaiku_<new>_all.deb` and keeps `/var/lib/codezaiku`; if you enabled
`codezaiku.service`, the upgrade does not restart it, it tells you to when it suits you.

<details>
<summary>Other ways: tarball by hand, .deb, from source</summary>

```bash
tar xzf codezaiku-0.1.1.tar.gz          # unpacks a ready-to-run ./codezaiku
./codezaiku/bin/codezaiku --version     # works immediately
sudo mv codezaiku /opt/codezaiku
sudo ln -s /opt/codezaiku/bin/codezaiku /usr/local/bin/codezaiku
```

On Debian or Ubuntu, `sudo apt install ./codezaiku_0.1.1_all.deb` — it installs to `/opt/codezaiku`,
links `/usr/bin/codezaiku`, and lets apt pull a JRE. The systemd unit it ships is disabled; nothing
starts on its own.

</details>

From source, if you want to build it yourself or work on it:

```bash
git clone https://github.com/Wyrdsekai/codezaiku.git && cd codezaiku

# 1. install — builds and puts `codezaiku` on your PATH (~/.local by default;
#    --system for /usr/local, --prefix DIR for anywhere else)
scripts/install.sh

# 2. point it at your model server (install.sh already wrote a starter config)
codezaiku model detect        # finds Ollama / llama.cpp / LM Studio / vLLM if running
codezaiku model use http://localhost:8200

# 3. check the environment — names exactly what is missing and how to fix it
codezaiku doctor

# 4. confirm the model answers and can call a tool
codezaiku smoke
```

The install carries its own launcher, jars and knowledge library, and downloads nothing at runtime.
**It does not bundle a JVM** — `install.sh` checks for a JDK 21+ and stops if it does not find one.
No model weights are bundled either. `scripts/install.sh --uninstall` removes the program and
keeps `~/.codezaiku` — your config, learned cards, research findings and audit trail.
`--purge` removes those too, after listing what will be lost and asking you to confirm.

To work in the repo without installing, `bin/codezaiku` runs from the checkout.

If you have no server yet:

```bash
docker run -d --name codezaiku-drive -p 8200:8200 \
  -v /path/to/models:/models ghcr.io/ggml-org/llama.cpp:server-cuda \
  -m /models/<your-model>.gguf --port 8200 --host 0.0.0.0 --jinja --ctx-size 32768
```

`--jinja` is required — without it the model returns tool calls as prose and nothing works.
See **[MODELS.md](docs/MODELS.md)** for what the harness needs from a model, what we measured on,
and why `codezaiku smoke` is the check that matters.

### Debian / Ubuntu

```bash
sudo apt install ./codezaiku_0.1.1_all.deb     # build it: packaging/deb/build-deb.sh
```

Installs to `/opt/codezaiku` with `/usr/bin/codezaiku`. A systemd unit is included but **not
enabled** — CodeZaiku can modify live systems, so starting it is a deliberate act.

### Verifying what you downloaded

Every release asset ships with `SHA256SUMS` and a Sigstore bundle (`<asset>.sigstore.json`):

```bash
sha256sum -c SHA256SUMS --ignore-missing
gh attestation verify codezaiku-0.1.1.tar.gz --repo Wyrdsekai/codezaiku \
  --predicate-type https://codezaiku.org/attestation/release/v1
```

`gh attestation` needs **GitHub CLI 2.49 or newer**. An older `gh` reports `unknown command` with no hint why — check with `gh --version` before concluding the signature is bad.

The signature says the release workflow, running at that tag, blessed those exact bytes. It is an
authenticity statement, **not** build provenance — the artifacts are built and validated on real
hardware rather than in CI, because an artifact nobody ran is not one worth shipping. Release assets
are immutable; a fix ships as a new version, never as a re-upload.

---

## What you can do with it

```bash
codezaiku help                                  # all commands, grouped by intent
```

**Work on a codebase**

```bash
codezaiku code ~/myproject "add pagination to the /items endpoint"
codezaiku decompose ~/myproject "add an admin dashboard with login and audit view"
codezaiku review ~/myproject HEAD~3             # read-only review -> findings
```

`code` keeps one conversation going until the goal is met. Use it when the model can hold the
whole job at once. `decompose` splits the goal into ordered slices first, then runs the same
loop with that plan pinned as a TODO it re-injects every turn — so you have a running program
after the first slice, and it grows from there. Use it when the job is big enough that the
model would otherwise lose the thread. `loop` is an older name for `code` and still works.

**Operate a stack** — sense, localize the failing service, fix, verify, roll back on failure

```bash
codezaiku fix myproject propose                 # diagnose and propose (default, safe)
codezaiku fix myproject guarded                 # act, behind the full guard stack
codezaiku watch myproject propose 60            # continuous: fix on degraded, else report
codezaiku investigate myproject                 # diagnose only — capped at localize, never acts
```

Scope can be local (`myproject`), remote (`ssh://host/myproject`) or a container
(`docker://name`). Everything else — the health endpoint, the verify command — is auto-discovered.

**Operate a whole machine** — the same operator, with the boundary set to the box instead of
one stack

```bash
codezaiku triage local                          # enumerate the box, report what is broken
codezaiku triage local "" 20 guarded            # ...and repair the unhealthy compose stacks
codezaiku watch-machine local 300               # the same sweep on a loop, as a daemon
```

`triage` enumerates everything it can see — compose stacks, failed systemd units, host disk
and memory — runs the SRE operator against each unhealthy stack, and surfaces the systemd and
host findings for a human. Each stack is still bound by blast-radius and the authority ladder,
so widening the boundary does not widen what any single fix may touch.

Note the difference in defaults, because it is the one place where a whole-machine command is
less conservative than a per-stack one: `triage` **reports** unless you pass `guarded` or
`unattended`, while `watch-machine` defaults to **`guarded`** — it is a daemon meant to keep a
box healthy, so it acts. Pass a ceiling explicitly if you want it to only watch:
`codezaiku watch-machine local 300 "" propose`. Run it under systemd on a real box.

**Review security posture** — report only, never auto-remediates

```bash
codezaiku secure local
```

**Research a question against live sources**

```bash
codezaiku research "what changed in the Foo API in v3" depth
```

**Drive it from another agent**

```bash
codezaiku run --text "add pagination" --output-format json   # one task, one JSON result
codezaiku acp                                   # Agent Client Protocol v1 agent on stdio
codezaiku mcp                                   # MCP server on stdio, all surfaces exposed
codezaiku serve 7070                            # HTTP: POST /fix, GET /health, GET /audit
```

---

## Safety

The operator can change your systems, so the defaults are conservative and the guards are not optional.

- **Authority ladder** — `observe` < `localize` < `propose` < `guarded` < `unattended`. Default
  `propose`: it tells you the command, it does not run it. An unproven fix never auto-applies.
- **Blast radius 1** — a fix may touch the service it localized and nothing else.
- **Rollback** — snapshot before a destructive change, verify after, restore if verification fails.
  This has a counterfactual: with it on, a model that corrupted a database config was rolled back and
  the stack came back green; with it off, the same failure left the service dead.
- **Harm check** — a fix that repairs its target but breaks a healthy dependency is a failure.
- **Kill switch** — `CODEZAIKU_OPS_HALT=on` forces `observe` immediately.
- **Security findings are report-only at every rung.** A wrong reliability fix restarts a service; a
  wrong containment action firewalls your own load balancer.

Untrusted input is treated as untrusted: container logs are attacker-writable, and a measured injection
through them steered the operator **40/40 on both model tiers** before it was fixed structurally. See
ARCHITECTURE.md §6.

---

## What this is not

- **Not a frontier coding agent.** SWE-bench Lite ≈1/8 on the 9B; refactoring 0/8. It is not a
  replacement for tools built on much larger models.
- **Not production-hardened.** No long-running soak evidence yet.
- **Not a multi-agent framework.** One loop, good tools, hard measurement. An earlier blackboard/agent
  architecture was designed, partly built, and removed.

The full accounting is in [LIMITATIONS.md](docs/LIMITATIONS.md).

---

## Repository layout

| Path | What |
|---|---|
| `core/` | the harness — loop, tools, operations, library, MCP server |
| `knowledge-packs/`, `ops-knowledge/` | the knowledge library: framework cards and validated fix cards |
| `bench/` | the measurement rigs — external benchmark adapters and fault-injection harnesses |
| `docs/` | architecture, limitations, configuration |

`bench/` is shipped on purpose. The rigs are how every number in LIMITATIONS.md was produced, including
the ones that came out badly.

---

## Documentation

- [ARCHITECTURE.md](docs/ARCHITECTURE.md) — design
- [LIMITATIONS.md](docs/LIMITATIONS.md) — the measured numbers
- [MODELS.md](docs/MODELS.md) — choosing and running a model server
- [PLATFORMS.md](docs/PLATFORMS.md) — Linux, Docker, macOS, Windows
- [CONFIGURATION.md](docs/CONFIGURATION.md) — the six settings that matter, and the rest
- [DEPLOYING_AS_A_BACKEND.md](docs/DEPLOYING_AS_A_BACKEND.md) — running it under another agent or orchestrator
- [WITH_WYRDSEKAI.md](docs/WITH_WYRDSEKAI.md) — wiring it up as a Wyrdsekai coding backend
- [ROADMAP.md](ROADMAP.md) — what is next, ordered by what measurement says is holding it back
- [CONTRIBUTING.md](CONTRIBUTING.md) — including the measurement rules
- [SECURITY.md](SECURITY.md) — reporting, and the agent's own threat model

**Getting in touch.** Bugs and feature requests belong in
[issues](https://github.com/Wyrdsekai/codezaiku/issues), where other people can see them and the
answer. For anything else, **support@codezaiku.org**. Vulnerabilities go to **security@codezaiku.org**
or a private advisory — see [SECURITY.md](SECURITY.md); conduct concerns to
**conduct@codezaiku.org**.

## License

Apache 2.0 — see [LICENSE](LICENSE).
