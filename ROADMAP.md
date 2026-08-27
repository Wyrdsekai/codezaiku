# Roadmap

There are no dates here. The list is ordered by what is measurably weakest, which is not always what
looks most interesting.

Read [LIMITATIONS.md](docs/LIMITATIONS.md) first — nearly every item below is here because a number in
that file is thin.

## Coding gets better when models get better

Swap the 9B for a 30B, change nothing else, and the coding failures move. That is the single most
useful thing this repo has measured, and it has a blunt consequence: the biggest improvement
available to the coding surface is not one we can ship. It arrives with better weights. The harness
already does what the reference coding agents do, so rebuilding parts of it would not move the
number — which is why you will not find "improve coding" below.

What is below is the surfaces where the harness still does the heavy lifting — mostly operations —
and the evidence we do not have yet.

## What's next

**Run it against stacks we did not build.** The ops surface has been run, unmodified, against
exactly one stack that is not ours. Everything we say about auto-discovery, localization and card
matching rests on that. Point it at unfamiliar stacks — other languages, other health-check
conventions, other container layouts — and we either earn a broader claim or find an assumption we
cannot see from here. One already bit us: fix cards name products, while real stacks name services
for their job. A service running redis is usually called `cache`. Our own stack never showed us that,
because everything in it is named after the thing it runs.

**Leave it running for days.** The longest run so far is minutes. `watch` and `watch-machine` are
daemons, so the interesting questions are the slow ones: leaked handles, audit logs that grow without
bound, a learning loop quietly collecting cards nobody checked, drift in what the model treats as
normal. "Not production-ready" is a statement about evidence we do not have, and this is how we would
get it.

**Attack more than one channel.** Log injection was tested on one channel with eight fixed payloads.
The design already treats alert text, page content and filenames as untrusted, but nobody has
attacked them, and nobody has tried adapting an attack as it fails. The fix is structural — evidence
decides what is suspicious, the model only picks between candidates — so it ought to hold. "Ought to"
is not a measurement.

**Earn a containment number.** Security stays report-only. What is unproven is the quality of what it
proposes: six attempts, six successes, from two cases run three times each. That is a smoke test, not
a rate — on that few runs, succeeding every time is still consistent with a true failure rate as high
as 63%. Getting to a number worth printing means many more runs across several kinds of incident —
*How the numbers were produced* in [LIMITATIONS.md](docs/LIMITATIONS.md) explains why.

**Score more pull requests.** Running review twice finds more than running it once, but across 25
PRs the two results overlap enough that we cannot honestly call one better. More scored PRs would
settle it. A cleverer reviewer would not.

**A conversation you can steer.** Today CodeZaiku takes a task and runs until it is done. There is no
way to talk to it — to be asked a question when the goal is ambiguous, to redirect it halfway, or to
follow up on what it just did without starting over. If you want that now, drive it from a host that
provides the chat: `codezaiku mcp` exposes every surface as a tool, and Wyrdsekai summons it as a
coding backend. Building it in would mean holding a conversation open across tasks — ACP sessions
currently carry a working directory and permission decisions, not history — and it would need
measuring rather than assuming, because deciding what to do from open-ended conversation is precisely
what a small model is worst at.

**Grow the card library.** Recon derives a fix when no card matches, and a fix that keeps working
gets promoted into a card. That loop works; there just are not many validated cards yet. They come
from running against real faults, which makes this downstream of the first item.

## How something gets on this list

The same bar we hold ourselves to:

- Turn it on, turn it off, measure both. A change is believed when it is measurably better with it
  than without, on the same fixtures, with nothing else moved.
- Read the thing that was produced. Not a pass rate, not an error count, not a grep.
- Run it more than once. A local model is not deterministic: the same setup on the same inputs
  scored 8, 5 and 7 out of 30 on three consecutive runs. One run tells you nothing, and three barely
  narrows it — five or more, across several kinds of scenario, is the bar.
- Ask what a broken measurement would look like, then check that by hand. Most of the wrong numbers
  this project has produced came from a broken instrument, not a weak model.

A patch that arrives with a measurement lands far more easily than one that arrives with an
argument. See [CONTRIBUTING.md](CONTRIBUTING.md).

## This is not a commitment

CodeZaiku is research-grade, released because the method and the measurements are useful to other
people. The list above is what we think is worth doing next, not a schedule. If something you need is
missing, tell us about the situation you are in rather than the feature you want — a situation is
something we can measure against.
