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
