# CodeZaiku Architecture

> This describes what is **built**. Where a design was tried and removed, it says so and why — those
> removals are among the more useful things here, because most of them were removed after a measurement
> showed they did nothing.

CodeZaiku is a harness for driving **small local models** (a 9B is the reference tier) through work that
normally assumes a frontier model: writing and maintaining code, running ML pipelines, operating a
service stack, and researching questions on the open web.

The organising question of the whole codebase is **"what does the harness contribute, and what does the
model contribute?"** Nearly every design decision below exists because a controlled experiment answered
that question one way or the other.

---

## 1. The spine: one growing conversation

Every capability is the same loop.

```
   goal ──▶ ┌──────────────────────────────────────────┐
            │  system prompt + growing message history │
            │                                          │
            │   model ──▶ tool call ──▶ observation ───┼──┐
            │     ▲                                    │  │
            │     └────────────────────────────────────┼──┘
            └──────────────────────────────────────────┘
                              │
                    task_done / conclude
```

- **No planner.** No plan-then-execute phase, no task decomposition tower.
- **No in-loop build/test gate.** The harness does not reject the model's actions.
- **Model decides done**, and the harness verifies afterwards rather than blocking during.
- **Faithful structured compaction** keeps the conversation inside the context window.

This is deliberate and was arrived at by removal. Earlier versions had a phase tower and in-loop gates;
they were measured and deleted. The reference small-model coding harnesses (goose, opencode, openhands,
smallcode, little-coder) converged on the same shape independently.

The one place the loop constrains the model is the **deadline turn**: on the final iteration, only the
finishing tool is offered. Telling a small model its budget is nearly gone does not work — measured, it
reads the warning and issues another command. Offering it nothing else moved research conclusion rate
from 33% to 100%.

**Code:** `loop/FamiliarLoop.java` (coding), `ops/OpsLoop.java` (operations).

---

## 2. Surfaces

All of them are the loop above with a different tool surface, goal shape, and verifier.

| Surface | Entry | Tools | Verified by |
|---|---|---|---|
| **Coding** | `code`, `loop`, `decompose` | read/write/edit/shell, LSP symbol replace | project's own tests + boot check |
| **Operations** | `fix`, `watch`, `investigate`, `serve` | scoped shell | the stack's own health endpoint |
| **Operations (whole machine)** | `triage`, `watch-machine` | scoped shell, per stack | each stack's own health endpoint |
| **Security** | `secure` | read-only shell | deterministic sensors + syscall detector |
| **Research** | `research` | web search/fetch, memory recall | the answer must cite fetched sources |
| **Review** | `review` | read-only shell + read | (report only) |

### Being driven by another program

Three ways in, chosen by what the caller already speaks — not three implementations:

| Surface | Entry | Shape |
|---|---|---|
| **CLI subprocess** | `run --text … --output-format json` | one task, one JSON document on stdout, CWD is the workspace |
| **ACP** | `acp` | Agent Client Protocol v1, JSON-RPC over stdio, streams tool activity, cancellable mid-turn |
| **MCP** | `mcp` | stdio JSON-RPC exposing every surface above as tools |

**All three answer with the same result document**, built by one class (`run/ResultDocument`). The CLI
writes it to stdout; ACP returns it in the prompt response's `_meta`. That is deliberate: a host that
integrates over one surface and later moves to another parses the same shape, and the two cannot drift
because there is only one builder. Two rules live there rather than in the callers —

- `status` is `success` only when a real test oracle **ran and passed**. A model's own "all tests
  pass" is a claim, and promoting a claim to a verified status would launder it onto someone's board.
- `gitRef` is never written, because the harness authors no commit.

**stdout belongs to the protocol on every one of them.** The loop narrates to stdout, so each surface
captures the real handle at startup and redirects `System.out` to stderr. A narration line in the
middle of a JSON-RPC stream is indistinguishable from a crashed backend.

---

## 3. ACI — the tool surface is a measured lever

"Agent-Computer Interface" work turned out to matter more than model choice for some failures.

- **Forgiving edit matching** with automatic LSP symbol-span fallback. Edit hard-miss went from ~30% to
  **4–9%** — which then made a planned fine-tune unnecessary, because the failures it targeted had
  already been engineered away.
- **Repetition guards** on every fixation-prone tool. A small model re-runs the identical command or
  re-fetches the same URL indefinitely; the guard refuses and tells it to move on.
- **Lenient argument parsing** — a malformed tool call is repaired rather than wasted.
- **LSP diagnostics after every edit** (rust-analyzer, pyright, jdtls, gopls, tsserver, clangd).
- **Query-relevant page excerpts.** `web_fetch` returns the passages matching the question, not the
  first N characters. The head-of-page version silently hid the answer: one run fetched the correct
  48,000-character page eleven times and truthfully reported it could not find the fact, because the
  fact was never in the first 2,500 characters. SimpleQA 22.2% → 38.3%.

**Code:** `tools/`.

---

## 4. The library — knowledge injection, and its honest scorecard

A corpus of framework/technique **cards** indexed with Lucene, pushed into the loop when a trigger
matches (never pulled by the model).

The measured result is domain-split and both halves matter:

- **ML: cards close real gaps.** Controlled flips on the 9B — ORPO 0.27 → 1.0000 (the card was both
  orphaned and wrong: attention-only instead of all-linear LoRA targets), a recommender 0.007 → 0.0848,
  and a distillation task that hit the 250-turn cap without its card and finished at turn ~65 with it.
- **A systematic plumbing bug:** 13 cards existed as files but had no trigger, so they were never
  pushed. A knowledge base that is not wired is indistinguishable from one that is empty.
- **Coding maintenance: cards did not help** — measured neutral to negative. Error-grounding injection
  is therefore *suppressed* for maintenance projects and kept for greenfield.

**Code:** `library/`, cards in `core/src/main/resources/library/` and `knowledge-packs/`.

---

## 5. Operations: sense → localize → act → verify → roll back

The ops surface is the most complete, because it is the one where the harness (rather than the model)
carries most of the weight.

```
  discover ─▶ sense ─▶ localize ─▶ card? ──yes─▶ apply fix
   (stack)   (health   (which      │                  │
             + logs)    service)   no                 ▼
                                    ▼            verify (the stack's
                              recon: derive       OWN health endpoint)
                              a fix read-only          │
                                                 ┌─────┴─────┐
                                              pass         fail
                                                 │            │
                                            learn-back    ROLL BACK
                                            (memoize)     (restore + re-verify)
```

### The boundary is a parameter, the guards are not

The same operator runs against two boundaries, and the distinction is worth being explicit about
because it is the one people assume wrong.

| Boundary | Verbs | What it enumerates |
|---|---|---|
| One stack | `fix`, `watch`, `investigate` | the services of a single compose project, host or container |
| One machine | `triage`, `watch-machine` | every compose stack on the box, plus failed systemd units and host disk/memory |

Widening the boundary widens only what gets *looked at*. Each unhealthy stack `triage` finds is
handed to the same per-stack operator, under the same blast-radius bound and the same authority
ladder, so no individual repair may touch more because the sweep was broader. Findings that are not
a compose stack — a failed unit, a filling disk — are reported for a human rather than acted on.

`investigate` is the same pipeline capped at `localize`: it senses, localizes and explains, and the
act stage is never reached. It exists because "just tell me what is wrong" is a different request
from "fix it", and answering it should not require trusting a ceiling to hold.

Defaults differ between the two boundaries, deliberately. `fix` and `triage` report unless asked to
act; `watch-machine` defaults to `guarded`, because a daemon whose whole purpose is keeping a box
healthy that only ever writes reports is a monitoring tool wearing the wrong name. That is the one
place where reading the ceiling off the verb rather than the docs will mislead you.

**Localization is deliberately not a model judgment when it doesn't have to be.** If the application's
readiness report names exactly one dependency as down, the root is decided mechanically and no model is
consulted. The model is asked only when the evidence is genuinely ambiguous, and then it may only choose
*among services a deterministic sensor already flagged* — it can disambiguate, never nominate.

That constraint fixed two separate problems with one mechanism:
1. **Red herrings** — a model preferred a service whose logs carried chronic, harmless error noise over
   the readiness report's plain statement of fact.
2. **Remote controllability** — see §6.

**The guard stack** is what makes acting safe, and each piece exists because something went wrong:

| Guard | Exists because |
|---|---|
| **Authority ladder** (observe < localize < propose < guarded < unattended) | unproven fixes must not auto-apply |
| **Blast radius = 1** | a fix must not touch a bystander service |
| **Host-write guard** | a model edited the compose file itself and broke the whole stack |
| **R3 rollback** (snapshot → apply → verify → restore on failure) | a model appended SQL to `postgresql.conf` and bricked the database |
| **Harm check** | a "successful" fix that breaks a healthy dependency is not a success |
| **Kill switch + audit trail** | operator control, and evidence afterwards |

The rollback has counterfactual evidence: with it enabled the bricked database recovered and the stack
came back green; with it disabled the same failure left the service dead until manual repair.

**Code:** `ops/`.

---

## 6. Untrusted input is a first-class concern

The operator reads container logs and feeds them to a model. Logs are **attacker-writable** — anything
that logs a request path, user-agent, or echoed error body lets an outsider put text into the agent's
reasoning context.

Measured on the shipping prompt: a single injected log line steered the model to an attacker-chosen
service **40/40 — every payload, every run — on both the 9B and the 30B.** Scale is not a defence.

The fix is structural rather than instructional:

1. **Candidate constraint** (§5) — the model may only choose among sensor-flagged services, so injected
   text cannot nominate a target. An answer outside the candidate set is rejected as suspected injection.
2. **No candidate ⇒ no localization.** An agent that acts on log text alone is remote-controllable.
3. **Untrusted-data fencing** — log content is labelled as evidence, never instruction.
4. **Neutralization** of instruction-shaped strings in any attacker-influenced free text.

Result: product-effective steering 100% → **0%**, on both tiers, with no loss of localization accuracy.
The same neutralization is applied to security-alert fields, since file paths and process names are
attacker-chosen too.

---

## 7. Learning: recon → card → promotion

When no card matches, the operator investigates read-only and derives a fix. If that fix verifies, it is
memoized as a **candidate** card keyed on the application's real error text. A candidate that verifies
repeatedly is promoted to **validated** and may thereafter auto-apply.

The measured economics decide what deserves a hand-written card at all: recon solves *tractable* faults
for free (a permissions fault: 5/5 with no card), while the hard tail genuinely needs one (a read-only
database fault: 0/5 by recon, 3/3 with the card). **The test for whether a card is worth writing is
whether recon fails without it.**

**Code:** `ops/OpsLearn.java`, `ops/OpsPromote.java`, `ops/OpsKnowledge.java`.

---

## 8. The measurement apparatus is part of the product

`bench/` is not an afterthought; the discipline it encodes is the reason the rest can be trusted.

- **External oracles wherever possible** — SimpleQA, WideSearch, SWE-bench, OpenRCA, AIOpsLab/SREGym —
  because a self-authored fixture measures your idea of the answer.
- **Model-blind, held-out graders** — the agent never sees the grader, and the grader boots the artifact
  and exercises it rather than reading the model's own claims.
- **Two-sided oracles.** A fault-injection rig checks *both* that the fault is fixed *and* that nothing
  else broke. One side alone is trivially gamed: `docker kill` "contains" every intrusion perfectly.
- **Baseline-first, controlled A/B, K≥5** — *K* being how many times a measurement is repeated on
  identical inputs, which matters because a local model is not deterministic. The measured noise
  floor on one suite was 8/5/7 correct out of 30 on identical inputs — a ten-point difference there
  is nothing.

See [LIMITATIONS.md](LIMITATIONS.md) for what the numbers are and are not.

---

## 9. What CodeZaiku is not

Setting expectations, because the shape of this system is unusual enough that people reasonably assume
otherwise.

- **Not a multi-agent framework.** There is no agent tower, no blackboard, no specialist agents, no
  tracker, no orchestrator dispatching workers. One loop, one growing conversation, good tools.
- **No separate planning phase.** The loop does not plan-then-execute as distinct stages; `decompose`
  pins an ordered TODO into the same loop rather than running a planner over it.
- **No harness gate on model actions.** The harness never intercepts an action and rejects it by rule.
  Where a choice needs constraining, it is constrained by *evidence* — the model may disambiguate among
  things a deterministic sensor already flagged, never nominate one itself. §6 covers why.
- **Not sandboxed by containers.** Runs execute on the host, bounded by timeouts, path scoping and the
  guard stack rather than by isolation.
- **No UI.** The interfaces are the CLI, MCP (stdio JSON-RPC), ACP and HTTP (`serve`). Anything richer
  belongs in a client built against those, which is why none ships here.

Each of these is a deliberate choice rather than an unfinished edge, and the reasoning behind them is
the same throughout: simpler mechanisms that could be measured beat elaborate ones that could not.

If you are looking for the "many cooperating agents behind a dashboard" architecture, this is not that,
on purpose. It is one loop, good tools, and hard measurement.
