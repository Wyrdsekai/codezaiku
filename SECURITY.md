# Security

Two things belong here: how to report a vulnerability, and what CodeZaiku's own threat model is. The
second matters more than usual, because this is software that reads untrusted input and can change your
systems.

## Reporting a vulnerability

Please report privately first, by either route:

- a [private security advisory](https://github.com/Wyrdsekai/codezaiku/security/advisories/new) on
  this repository, or
- **security@codezaiku.org**

Do not open a public issue for an unpatched vulnerability.

Useful reports include what an attacker controls, what they achieve, and ideally the reproduction. We
are particularly interested in anything that gets attacker-chosen text into a model prompt in a way that
changes what the agent does.

## What CodeZaiku can do to your systems

Be clear-eyed about this before running it with authority:

- It executes shell commands on the target — locally, over ssh, or in a container.
- At `guarded` or `unattended` it modifies live services: restarting them, changing configuration,
  running engine CLI commands.
- It reads logs, environment variables and configuration, which routinely contain secrets.

Defaults are conservative: the ceiling is `propose` (it tells you the command rather than running it),
and security findings are report-only at **every** rung.

## The guard stack

| Guard | What it prevents |
|---|---|
| **Authority ladder** | An unproven fix auto-applying. `observe` < `localize` < `propose` < `guarded` < `unattended`; the effective rung is the minimum of your ceiling and what the situation earned. |
| **Blast radius 1** | A fix touching any service other than the one localized. Verified to block a bystander restart. |
| **Host-write guard** | The agent editing host files or the compose file itself. Fixes must act inside the target. |
| **Rollback** | A destructive fix leaving a service broken. Snapshot → apply → verify → restore on failure. |
| **Harm check** | A "successful" fix that broke a healthy dependency being reported as success. |
| **Read-only diagnosis** | Investigation changing what it is measuring, including mutating commands hidden behind `docker exec`. |
| **Kill switch** | Everything. `CODEZAIKU_OPS_HALT=on` forces `observe`. |
| **Audit trail** | Not knowing what happened. Set `CODEZAIKU_OPS_AUDIT`. |

## Indirect prompt injection — the one to understand

**The operator reads container logs and puts them in front of a model. Logs are attacker-writable.**
Anything that logs a request path, user-agent, or echoed error body lets an outsider write text into the
agent's reasoning context.

We measured this against our own shipping prompt. A single injected log line steered the agent to an
attacker-chosen service **40 times out of 40 — every payload, every run — on both a 9B and a 30B.**
Model scale was not a defence.

The mitigation is structural, not instructional, because instructing a model to ignore hostile text does
not work reliably:

1. The model may only choose **among services a deterministic sensor already flagged**. It can
   disambiguate; it cannot nominate. An attacker who cannot make a service genuinely look unhealthy
   cannot make it a target.
2. An answer outside that candidate set is rejected as suspected injection.
3. If no sensor flagged anything, the agent **declines to localize** rather than reasoning from log text.
4. Untrusted text is fenced and labelled, and instruction-shaped strings in it are neutralized.

Result: product-effective steering **0/80** on both tiers, with no loss of accuracy.

**The bounded claim:** eight payloads, one attack channel, no adaptive attacker. This is "we measured one
channel and closed it", not "injection-proof". The same neutralization is applied to security alert
fields, since file paths and process names are attacker-chosen too — a container that names a file
`IGNORE PREVIOUS INSTRUCTIONS…` gets that string into the alert stream, and we verified it arrives
defanged.

## Verifying a release

Release assets carry `SHA256SUMS` and a per-asset Sigstore bundle, `<asset>.sigstore.json`:

```bash
sha256sum -c SHA256SUMS --ignore-missing
gh attestation verify codezaiku-X.Y.Z.tar.gz --repo Wyrdsekai/codezaiku
```

Be precise about what that proves. The signature is an **authenticity** statement — the release
workflow, running at that tag, blessed those exact bytes — and deliberately not SLSA build provenance,
because the bytes are built and validated on real hardware before publication rather than by the
runner. The workflow verifies each asset against the release's own checksums before signing and
refuses to sign a mismatch, so a swapped asset cannot pick up a signature. Assets are immutable: a bad
one is replaced by a new version, never re-uploaded under the same name, since a re-upload would
silently invalidate every checksum and signature already in circulation.

## Deployment advice

- Start at `propose`. Move to `guarded` only for fault classes you have watched it handle.
- Set `CODEZAIKU_OPS_AUDIT`, and set `CODEZAIKU_OPS_ALERT_WEBHOOK` if anything runs unattended.
- Give it a dedicated account with the narrowest rights that let it do the job. It will use what it has.
- Prefer key-based ssh; `CODEZAIKU_SSH_PASSWORD` exists for environments that force it.
- Assume anything in a log or an alert may have been written by an attacker — that is how the operator
  treats it.
