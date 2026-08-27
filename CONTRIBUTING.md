# Contributing

Contributions welcome. The unusual part of this project is not the code style — it is the evidence
standard, so most of this document is about that.

## Getting set up

```bash
bin/codezaiku doctor     # tells you what is missing and how to fix it
./gradlew :core:test     # unit tests
bin/codezaiku smoke      # confirms the model server answers and can call a tool
```

You need a JDK 21+ and an OpenAI-compatible model server. No weights are bundled.

## Code style

- **Imports, never inline fully-qualified names.** `SecurityScan`, not `org.codezaiku.ops.SecurityScan`
  at the call site. `tools/deFqcn.py` fixes a file that has drifted.
- **Comments say *why*, not *what*.** The convention here is that a non-obvious decision carries the
  measurement or the failure that produced it — "measured: the 9B copies literals faithfully and garbles
  `<PLACEHOLDER>` templates" is worth more to the next reader than a restatement of the code.
- Match the surrounding file. There is no formatter to fight.

## The evidence standard

This is the part that matters.

**A change that claims an improvement needs a controlled measurement.** Not a demo, not a plausible
argument, not one run that looked better.

- **Baseline first.** Measure the current behaviour before changing it, on the same inputs.
- **K≥5, and more than one scenario class.** *K* is how many times you repeat the measurement on
  identical inputs. It matters because a local model is not deterministic: the published noise floor on
  one suite is 8, 5, 7 correct out of 30 across three runs of the *same* configuration, so K=1 and K=3
  cannot separate a real effect from that spread. Thirty repetitions of a single fixture is also a tight
  bound on something that may not generalize — prefer several classes at K=5.
- **Change one thing.** If the prompt and the tool both changed, the result attributes to neither.
- **Read the outputs.** Every serious error in this project's history was found by reading artifacts,
  not by reading scores. A fraction cannot tell you the grader was scoring the run log instead of the
  answer, and ours was, for a while, producing a confident p=0.004 result that was pure artifact.
- **Report the negative result.** Levers that did not work are documented in LIMITATIONS.md alongside
  the ones that did. A PR that says "this did not help, here is the measurement" is a good PR.

**Before believing any number, ask what would look identical if the instrument were broken — then check
that specific thing by hand.** The dominant failure mode in this repo has never been a weak model; it
has been a broken measurement. Fixtures where the honest answer scored as a miss, benign control arms
contaminated by the previous arm, graders that failed the ideal outcome, probes gated on exit code that
silently discarded every finding.

**Scoring is separable from running.** Save raw outputs; re-grade offline. Never re-run a model to fix a
grader bug.

## Changes that touch safety

The operator can modify live systems. If your change affects the authority ladder, blast radius,
rollback, the harm check, or what reaches a model prompt:

- Say in the PR **what new thing the agent can now do**, and what stops it doing that thing wrongly.
- New capabilities land at `propose` and earn a higher rung with evidence.
- Anything that puts external text in front of a model must treat that text as untrusted. Container
  logs, alert fields, fetched pages and filenames are all attacker-writable. See ARCHITECTURE.md §6.
- Security remediation stays report-only.

## Adding knowledge cards

Cards are pushed into the loop when a trigger matches. Two rules earned by measurement:

1. **A card must be registered to a trigger.** Thirteen cards once existed as files that nothing ever
   pushed — an unwired knowledge base is indistinguishable from an empty one.
2. **A card earns its place only if the model fails without it.** Run the task with no card first. If
   recon or the base model already solves it, the card adds nothing and should not be written. The
   measured delta is the card's value.

## Pull requests

Include what you measured, on what, how many times, and what the baseline was. If it is a bug fix or a
docs change, say so and skip the ceremony — the evidence standard is for behaviour claims.

## Releasing

Releases are cut by the maintainers, so there is nothing here you need to run. What is worth knowing
as a contributor is that every published artifact is checksummed and Sigstore-attested, and that the
release is verified on each platform the docs claim support for before it goes out —
[Verifying what you downloaded](README.md#verifying-what-you-downloaded) shows how to check an
artifact yourself.
