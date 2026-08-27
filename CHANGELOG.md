# Changelog

Notable changes. This project follows [semantic versioning](https://semver.org/) loosely: while at
0.x, minor versions may change behaviour.

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
`gh attestation verify <asset> --repo Wyrdsekai/codezaiku`. The signature is an authenticity statement
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
