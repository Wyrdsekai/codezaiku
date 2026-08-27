## What this changes

<!-- One or two sentences. What behaviour is different afterwards? -->

## Why

<!-- The problem, not the patch. If it fixes an issue, link it. -->

## How it was verified

<!--
Required, and the part that gets read first.

A test that passes proves nothing until it can fail: if you added one, say how you confirmed it
FAILS without your change. That single check has caught more broken instruments in this project
than any review.

For anything model-facing, K=1 is noise and so is K=3 — the measured noise floor on an identical
configuration was 8, 5, 7. If you are claiming an improvement, say how many runs, on what fixtures,
and what the comparison arm was.
-->

- [ ] `./gradlew :core:test` passes
- [ ] Added or updated tests, and confirmed they fail without the change
- [ ] Docs updated if behaviour or a command changed

## Anything you are unsure about

<!-- Genuinely useful. Say what you could not verify, rather than leaving it to be discovered. -->
