# research-eval — measuring the research capability against an EXTERNAL oracle

Self-authored research fixtures are worthless here: I would be grading my own idea of a good answer.
This directory runs CodeZaiku's `research` verb against **SimpleQA** (OpenAI, 4,326 short fact-seeking
questions, one unambiguous gold answer each, public CSV — no API key), which is blind to us.

## The A/B

```
python3 simpleqa_ab.py --n 30 --turns 24 --pace 8 --out ~/.codezaiku/bench/simpleqa
python3 fisher.py ~/.codezaiku/bench/simpleqa/results.jsonl
```

Arms differ ONLY in `CODEZAIKU_GAPREFLECT`:

| arm | behaviour |
|-----|-----------|
| `off` | search → read → answer (the "browsing tools alone" tier: BrowseComp measured 1.9% for this shape) |
| `on`  | … → **reflect on what is still unsourced** → aim the next query at that gap (local-deep-researcher's loop) |

Same questions, same order, same turn budget. Each run gets a **fresh research memory pool**
(`CODEZAIKU_RESEARCH_POOL`) — a shared pool would carry arm A's harvested findings into arm B and
manufacture a lift that isn't there.

Grading is arm-blind: every question carries the same instruction to end with an `ANSWER:` line, and the
gold answer must appear in it (normalized, bidirectional containment — the SealQA adapter's string
fallback). If no tagged line is produced, the prose body is graded instead and the row is marked `body`
so a generous grade can be told from a tight one. **Read `answers/` — the containment grade is a proxy.**

## Measurement integrity (both of these already bit us)

- **A rate-limited search backend looks exactly like a hard question.** SearXNG's free upstreams
  (Brave / DuckDuckGo / Google CSE / Startpage) suspend under sustained benchmark load, and every engine
  down returns an *empty result list*. Two full A/B runs were voided this way: 19 of 20 runs did zero
  fetches and the model answered from memory or blocked out. The runner now aborts loudly when three
  consecutive runs search but never fetch, and `--pace` puts a gap between runs.
- **Don't let the model echo your template.** An `ANSWER: <the short answer…>` placeholder gets copied
  verbatim by a 9B; the grader now rejects instruction-shaped answer lines.

`searxng-settings.yml` is the working instance config — install to `/etc/searxng/settings.yml` in the
`codezaiku-searxng` container (`docker cp`, then `docker restart`; the bind mount is root-owned).
`bing` is enabled explicitly: it is NOT on by default in this build and it was the only upstream that
stayed healthy under load, so without it a general query can return nothing at all.

## The fan-out flip (2026-08-30) — the "levers exhausted" verdict falls

`research <q> fan` (decompose → parallel workers, fresh context each → critic owns the stop →
synthesis seeded with findings) vs the single loop, SAME drive (the 27B), same 13-task judge-free
WideSearch slice, artifacts at `~/.codezaiku/bench/widesearch-flip-2026-08-29/{broad,fan2}`:

| arm | mean item-F1 | produced a table |
|---|---|---|
| broad (single loop, 40 turns) | 0.171 | 3/13 |
| fan (3600s/task ceiling) | **0.314** | 6/13 |
| fan, completed tasks only | **0.68** (6 tasks) | 6/6 |

Verified by reading answers, not the score: ws_en_013 — the 109-row three-column table that the
July campaign measured as UNEMITTABLE (the model had the data and could not produce the table) —
came out at **0.94 (106/109 rows)** under fan, vs 0.00 broad. ws_en_063: 0.00 → 0.78. Where broad
already worked (ws_en_028, 0.575), fan matched it (0.62).

**Every fan zero is a 3600s wall-clock timeout, not a quality failure**: the timed-out logs show
100+ drive calls mid-flight with the critic having ordered a second round — the biggest tasks
(40-66 gold rows) simply need more serial throughput than one llama.cpp gives 4 "parallel"
workers. The architecture lever is real; the remaining ceiling is designed-for hardware
(a truly parallel drive — frontier API or multi-slot serving — or a bigger time budget).

The 2026-07 verdict "harness levers exhausted" was measured under one sequential loop — correct
then, superseded now: the lever that flips WideSearch is the one a single loop cannot pull.

## The full ladder (2026-08-30, same 13 tasks, same instrument)

| cell | mean F1 | tables |
|---|---|---|
| 27B, single loop | 0.171 | 3/13 |
| 27B, fan (3600s ceiling) | 0.314 | 6/13 |
| **fable-5, fan** | **0.728** | **13/13** |

Architecture and drive stack cleanly: fan roughly doubles the local drive, and the frontier
drive through the SAME fan harness doubles it again — with true parallelism erasing the timeout
failure mode entirely (~130s/task vs 42min). Raw + answers: `widesearch-flip-2026-08-29/fan-fable`.

## Backend A/B (Brave vs SearXNG) — INCONCLUSIVE, instrument-bound (2026-08-30)

Three SearXNG-forced broad tasks all died at the 1200s ceiling — but the artifact shows them
WORKING at cutoff (24 searches returning results, 16 fetches, turn 25/40), while the Brave-broad
tasks "finished" in 30-70s largely by giving up without tables. Unequal clocks, incomparable
outcomes; the assumed mechanism (degraded upstreams) does not appear in the logs. A proper A/B
needs equal generous ceilings at the FAN level (the shipping mode): queued, not run. What today
supports: Brave's result QUALITY is visibly better at rank 1-3 (measured at first probe); no
F1 claim either way yet.


## The search controller on WideSearch (2026-09-02) — NULL result, said plainly

The steerer (near-repeat/host-overlap nudges naming one axis to move on) + the stop rule
(exhausted search brings the deadline turn forward), measured on the SAME 13-task fan slice,
same 27B, same 3600s ceiling as the 0.314 baseline (`~/.codezaiku/bench/widesearch-steer-2026-09-02`,
stopped at 11/13 — the last two could not change the verdict):

| | baseline (same 11 tasks) | with controller |
|---|---|---|
| mean item-F1 | 0.286 | 0.284 |
| wins / losses (>0.05) | — | 3 / 3 |
| timeouts at 3600s | 5 | 5 |

The steerer fired 100 times across the slice; **the stop rule fired zero times** — every run reports
`0 after saturation`, because breadth tasks keep reaching new hosts and never saturate. So this
measured the nudges alone, and on WideSearch they do not move item-F1 (ws_en_065 0.61→1.00 and
ws_en_018 0.63→0.45 are the noise floor, not a story). The stop rule's habitat is DEPTH questions;
WideSearch cannot exercise it. Next: the SimpleQA A/B with `--knob steer`.

## The search controller on SimpleQA (2026-09-02) — 27B: equal accuracy, leaner runs

`simpleqa_ab.py --knob steer` (arms differ ONLY by CODEZAIKU_SEARCH_STEER), n=30, 24 turns, the 27B,
artifacts `~/.codezaiku/bench/simpleqa-steer-2026-09-02/`:

| arm | correct | concluded | searches/run | fetches/run | secs/run |
|---|---|---|---|---|---|
| off | 28/30 | 30/30 | 2.77 | 3.50 | 123 |
| on  | 28/30 | 30/30 | 2.37 | 2.93 | 103 |

Fisher p = 1.0; **zero discordant pairs** — the two arms got the same 28 right and the same 2 wrong
(both misses are answer-line format: `May 2, 2000` vs gold `2 May 2000`, `29 megapixels` vs `29MP`).
The stop rule fired ZERO times in 60 runs: the 27B answers in 2-3 searches and concludes on its own;
saturation needs six zero-gain queries in a row and these runs never issue six. What is left is the
steerer's nudges: ~15% fewer searches, ~16% fewer fetches, ~16% less wall time at equal accuracy —
a throughput effect, one sample per question, worth believing only if it repeats. The literature's
77-94% wasted-episode figure does not describe this model at this budget. Next: the same A/B on a
9B (the wyrdsekai persona fine-tune, thinking off) — the weak-drive population where over-search
should actually occur.

## The search controller on SimpleQA (2026-09-02) — 9B: null again

Same A/B on `wyrdsekai-3.5-9b-v5` (a wyrdsekai persona fine-tune of Qwen3.5-9B, thinking OFF),
the model box, :8212, artifacts `~/.codezaiku/bench/simpleqa-steer-9b-2026-09-02/`:

| arm | correct | searches/run | fetches/run | secs/run |
|---|---|---|---|---|
| off | 27/30 | 1.90 | 5.23 | 25 |
| on  | 26/30 | 2.23 | 4.37 | 24 |

Fisher p = 1.0; five discordant pairs split 2/3 — noise. The stop rule fired zero times in 60 runs
here too. The surprise is the 9B itself: 90% on this slice at ~25s a run, against 9/30 for a 9B in
July — the loop (deadline turn, give-up rule, citation discipline) moved a 9B from flailing to
disciplined without any controller. **Verdict across both drives: the over-search population the
literature measures (77-94% wasted episodes) is not our population; the loop had already removed
it. The controller stays as cheap hygiene and is NOT claimed.** A positive flip would need a
budget or a task shape where saturation actually occurs — none of WideSearch, SimpleQA-27B or
SimpleQA-9B produced one.
