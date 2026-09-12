# Changelog

Notable changes. This project follows [semantic versioning](https://semver.org/) loosely: while at
0.x, minor versions may change behaviour.

## 0.3.4

Fixed
- The MCP server introduced itself as version 0.1 whatever the release; it now says the release's version. (Its sibling ResearchZosho 0.1.8 fixes the same line, and its drive probe, which named no model and so read every llama-swap drive as absent; CodeZaiku's probe already named the model.)
- `codezaiku model serve check` (which the release build runs) read a rate-limited host (HTTP 429) as a missing file; it now asks again, twice, twenty seconds apart, and then reports the row as not checked rather than gone.

## 0.3.3

Added
- `codezaiku setup`: the first ten minutes as questions with defaults. The model (a server it finds, one this machine serves on demand, or a hosted API with a key, then one word asked of it), a web search backend (a Brave key or a SearXNG address, checked), Claude Code, Codex and Gemini CLI connected over MCP when installed, and ResearchZosho offered. The installers name it as the next step.
- `codezaiku model serve install`: the model on this machine, on demand, on Linux, macOS and Windows. It picks the measured model for the machine's memory, downloads it once, puts llama.cpp behind a small proxy (llama-swap) as a service that starts with your session on port 8211, and sets the drive to it: the model comes up when asked and goes away after 20 idle minutes, so the memory is free in between and nothing has to be up all the time. On Linux the server is llama.cpp's CUDA container (Docker with the NVIDIA runtime); on macOS its Metal build, sized by unified memory, as a launchd agent; on Windows its Vulkan build, which runs on any card, as a logon task. Every download is checked against a recorded sha256 (the model files as Hugging Face lists them, the proxy's published checksums, the pinned llama.cpp build) and refused on a mismatch; `codezaiku model serve check` reads every row and pinned build, and the release build runs it. Uninstall refuses while the other product's settings still point at the proxy (`--force` overrides). `status`, `stop` and `uninstall` beside it; `--share` lets other machines use this card, and a proxy already on 8211, ResearchZosho's included, is used as it is. `codezaiku doctor` names it as the fix when no model server answers, and offers to run it when a person is at the keyboard.

## 0.3.2

Added
- `npx -y @wyrdsekai/codezaiku-mcp` starts the MCP server from any client that runs npm packages: the launcher finds an installed CodeZaiku, or fetches the release of the same version, checks it against the release's checksums and unpacks it under `~/.codezaiku/launcher`. CodeZaiku is listed in the MCP Registry as `io.github.Wyrdsekai/codezaiku`.
- Builds with their own Java runtime, one per platform (Linux and macOS on x64 and arm64, Windows x64): `codezaiku-<version>-<platform>.tar.gz`, nothing to install first. The install one-liners take one when the machine has no Java 21 (`CODEZAIKU_RUNTIME=1` asks for it), the npm launcher does the same, and `codezaiku update` on such an install stays on its own kind.

## 0.3.1

Fixed
- `codezaiku update now` failed with "HTTP 302" on a tarball install: a GitHub release asset is served through a redirect and the downloader did not follow it. The update check, the deb path, and `codezaiku install researchzosho` were unaffected. On 0.3.0, update with the install one-liner instead.

## 0.3.0

Added
- Chat: `/research <topic>` builds a research brief in conversation; `/research go` files the run; `/research status` and `/research read` follow it. A finished run is announced once in the next chat.
- Research memory: `codezaiku research` keeps its findings with sources in `~/.codezaiku/research`; later runs on related questions start from it. Works with nothing else installed.
- `codezaiku install researchzosho`: downloads the ResearchZosho release, verifies it, runs its setup, connects the chat (write token, research memory questions handed over). Updates an existing install in place. `/setup librarian` in chat does the same.
- `codezaiku doctor` and the chat banner report when a newer ResearchZosho is released.
- `codezaiku update [now | auto on|off]`: install the latest CodeZaiku release in place (checksum-verified; tarball installs). `CODEZAIKU_UPDATE=auto` lets a chat do it at its start. `doctor` reports a newer CodeZaiku.
- `web_search` fallback: Wikipedia plus Crossref and OpenAlex when neither Brave nor SearXNG answers. `CODEZAIKU_FALLBACK_SEARCH=off` disables it.
- `scholar_search` tool (Crossref and OpenAlex, results by DOI) in every research run.
- `web_fetch` refuses loopback, link-local, unspecified and multicast addresses and the service's own hosts (`CODEZAIKU_FETCH_PRIVATE`, `CODEZAIKU_FETCH_MAX_BYTES`).

Changed
- ResearchZosho is a separate program (https://researchzosho.org). The copy that shipped inside CodeZaiku is removed. `codezaiku librarian …` runs the installed `researchzosho`. CodeZaiku talks to it over HTTP with the published client.
- The `library_*` tools and library resources are removed from CodeZaiku's MCP server. ResearchZosho has its own MCP server.
- CI and release workflows use GitHub-owned actions only.

## 0.2.0 — the conversation release

The chat surface, orchestration, and a research capability that compounds.

- **`codezaiku chat`** — a conversation in one project: it asks before writing or running
  anything (showing the change first), remembers what you allow (`/trust`), steps back through
  its own actions (`/undo`, per-step journal), and keeps sessions you can `/resume`, `/onboard`
  from, or hand off. Streaming replies; thinking hidden unless you ask (`/thinking`).
- **Project memory** — `/remember` carries facts into every future session here; the model can
  save too, and asks first. Working agreements: the project's `FAMILIAR.md`/`CLAUDE.md` rides
  every turn. Conflicts with recorded decisions are said out loud, never silently overridden.
- **Background work and delegation** — `run_background` keeps long commands out of the
  conversation's way (`/tasks`, completion notices); `delegate` hands a self-contained task —
  coding or research — to a background sub-agent, optionally on a different model
  (`CODEZAIKU_DELEGATE_DRIVE`).
- **Research that fans out** — `codezaiku research <q> fan` decomposes a question, researches
  sub-questions in parallel with fresh contexts, lets a critic decide whether coverage is
  sufficient, then synthesizes. Measured on WideSearch: 0.171 → 0.314 (local model), 0.728
  (hosted frontier model), same harness.
- **Web search backends** — Brave Search API first when `CODEZAIKU_BRAVE_KEY` is set, SearXNG
  (`CODEZAIKU_SEARXNG`) as fallback; web tools join chat when either is configured.
- **OpenAI-compatible façade** — `codezaiku v1` serves the chat as `/v1/chat/completions`
  (read-only tools by design: that wire cannot ask permission). Point Open WebUI at it.
- **MCP client** — `CODEZAIKU_MCP_SERVERS` makes other processes' MCP tools callable from chat,
  consent-gated per call.
- **Sessions as archives** — `codezaiku sessions export|import` (backup/restore; import never
  overwrites without `--force`).
- **Hosted drives** — `CODEZAIKU_DRIVE=https://api.anthropic.com` (+ `CODEZAIKU_MODEL`,
  `CODEZAIKU_API_KEY`, `CODEZAIKU_TEMP=none`) runs any surface on a hosted model; per-response
  token usage is logged; a failing endpoint stops the run after 8 consecutive errors instead of
  retrying forever.
- **Attestation predicate moved to `codezaiku.org`** (a domain we own). 0.1.0 and 0.1.1 are
  immutable and verify only with the old `codezaiku.dev` type — see SECURITY.md.
- Platform validation: the chat conversation battery passes 11/11 on Linux and macOS against a
  live model; Windows native validated end-to-end (7/7).
- Known gap: on macOS, ctrl-C turn-cancellation is unverified when stdin is a pipe (the signal
  is consumed without stopping the turn); interactive terminal use is the supported path there.

## 0.1.1 — fixes

- **`run --text` accepts `@<file>` and `-`.** On Windows every argument crosses cmd.exe, which
  refuses a command line over 8,191 characters, so a dispatch carrying a real task never started.
  The file and stdin forms work on every platform, and match the `@file` form `code`, `fix` and
  `research` already take. An unreadable `@file` is an error, not a literal task.
- **`doctor` reports a second context-window floor.** 8k passes the required check and still cannot
  complete a run driven by another agent; 12k is the floor for `run`, MCP and ACP.
- **`install.ps1` prints one line when a prerequisite is missing**, not a PowerShell error record.
- **`bin/codezaiku` runs in the directory you called it from.** From a source checkout, `run` used
  the checkout as the workspace instead of your project. The packaged launcher was never affected.
- **The tarball contains `LICENSE` and `README.md` again.** The 0.1.0 tarball shipped without them.
- **`docker build -f packaging/docker/Dockerfile .` works from a clone.** It referenced a path that
  only exists in the private tree, so it failed for everyone else.

## 0.1.0 — first public release

The initial open-source release. CodeZaiku has been developed and measured privately; this is the
point at which it became installable by someone who is not its author.

### Platforms

Linux is the reference platform. macOS (Apple Silicon), **native Windows** and WSL2 are all measured
— Windows requires Git for Windows, whose bash the harness shells out to. `scripts/verify-platform.sh`
runs the acceptance checks on any host, and `PLATFORMS.md` carries the per-platform numbers.

### Release artifacts

A platform-independent tarball, a Debian package and a container image, each listed in `SHA256SUMS`
and signed after publication with a per-asset Sigstore bundle. Verify with
`gh attestation verify <asset> --repo Wyrdsekai/codezaiku --predicate-type https://codezaiku.dev/attestation/release/v1` (needs GitHub CLI 2.49+). The signature is an authenticity statement
about bytes built and validated on real hardware — not build provenance from a CI runner. See
[SECURITY.md](SECURITY.md).

### Surfaces

- **Coding** — `code`, `loop`, `decompose`, `review`. A single growing-conversation loop with no
  planner and no in-loop gates; the model decides it is done and the harness verifies afterwards.
- **Operations** — `fix`, `watch`, `serve`, `triage`, `investigate`. Senses a stack, localizes the
  failing service, applies a fix, verifies it against the stack's own health endpoint, and rolls back
  if verification fails.
- **Security** — `secure`. Deterministic posture checks plus runtime intrusion detections, and a
  bounded read-only investigation that proposes one containment command. Report-only at every
  authority rung.
- **Research** — `research`. Search, fetch and synthesize against live sources, with an accumulating
  findings pool.
- **Integration** — three ways for another program to drive CodeZaiku, all answering with the same
  result document: `run` (one task, one JSON document on stdout, the subprocess CWD is the
  workspace; exit code agrees with `status`, and `--mode artifact` says the deliverable is a single
  file so the run does not build tests around it), `acp` (Agent Client Protocol v1 over stdio — streams tool activity, cancellable
  mid-turn, routes git writes to the client's consent flow), and `mcp` (stdio JSON-RPC exposing every
  surface as tools). `serve` exposes HTTP.

### Safety

- Authority ladder: `observe` < `localize` < `propose` < `guarded` < `unattended`, defaulting to
  `propose`.
- Blast-radius limiting, host-write guard, snapshot-and-rollback, harm check, kill switch, JSON audit
  trail.
- Indirect prompt injection through container logs is mitigated structurally: the model may only choose
  among services a deterministic sensor already flagged, and declines to localize when nothing is
  flagged.

### Install

- `scripts/install.sh` for a user, system or custom prefix, with `--uninstall` and `--purge`.
- Debian package via `packaging/deb/build-deb.sh`. The systemd unit ships disabled.
- `codezaiku doctor` reports what is missing and the command that fixes it.
- `codezaiku init` and `codezaiku config` manage settings in `~/.codezaiku/config`; environment
  variables override the file.
- `codezaiku model detect|add|use` finds and switches model endpoints. CodeZaiku runs no inference
  server and bundles no weights.

### Known limitations

See [LIMITATIONS.md](docs/LIMITATIONS.md) — it is the honest accounting and is worth reading before
deciding whether this is useful to you. In short: coding is capability-bound on small models, incident
diagnosis on public benchmarks is weak, and there is no long-running soak evidence yet.
