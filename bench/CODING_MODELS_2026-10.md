# Which models for CodeZaiku, measured (October 2026)

The record behind the hosted table in `docs/public/MODELS.md` and the tiers in `ModelTiers`, for the next measurement.

## The instrument

- **The project**: the email-intel service, a FastAPI app of about thirty files with twelve baseline tests and a boot check
  (`uvicorn` answers `/`). Every run starts from a fresh copy with the task's fixture written into it.
- **The harness**: a frozen CodeZaiku build per round (`7e0cf93a` for battery 1 round 1, `19b788a6` with the scoped faked-core
  check for round 2 and the hard set), one scratch home per arm, project environment and research-first off, a 60-turn cap, the
  model's own `pip install` into one shared virtualenv on PATH.
- **The grade**: a held-out grader per task that the model never sees, plus the twelve baseline tests and the boot check, all
  three green = pass. Every grader was validated both ways: the broken fixture fails it, a hand-written reference passes it.
  "Done" is a separate column: whether the model called `task_done` before the cap.
- **K**: 3 per task on the maintenance battery, 5 on the hard set. The noise floor measured earlier on this project is three
  points of eighteen at K=3; a one-run difference is nothing.
- **Costs** at the providers' list prices from the tokens the servers reported: OpenRouter's public model list as read on
  16 October 2026, Anthropic's pricing page on 17 October, OpenAI's through OpenRouter. The batteries ran with two prices typed
  from memory, DeepSeek's five times too high and Sonnet's half again too high (3/15/0.30 against the page's 2/10/0.10); the
  tables are recomputed from the ledger's tokens at the list, with (prompt − cached) × in + cached × cache + completion × out,
  which reproduces every recorded cost exactly at the price it was recorded with. Prices move; every public figure says its date.

## Battery 1: maintenance, six tasks, K=3

bugfix (a threading key bug), security (an open redirect and a secret in a log line), feature (an endpoint with a filter),
coverage (tests for an untested module), incident (a crash on a malformed mailbox), migration (a schema change with data).

| arm | passes of 18 | done | minutes a run | cost a run |
|---|---|---|---|---|
| Claude Sonnet 5.5 | 18 | 18 | 2.9 | $0.47 |
| Kimi K3 (OpenRouter) | 18 | 18 | 3.9 | $0.20 |
| GPT-6.1 Sol (OpenAI) | 18 | 16 | 4.2 | $0.32 |
| GLM-5.3 (OpenRouter) | 17 | 14 | 2.4 | $0.03 |
| DeepSeek V4.1 Flash (OpenRouter) | 17 | 10 | 4.6 | $0.01 |
| local Qwen3.8 27B, 4-bit, one 24 GB card | 16 | 17 | 6.4 | free |
| Hy4 preview (OpenRouter, 17 runs) | 15 | 4 | 21.6 | $0.35 |
| Qwen3-Coder-Next (OpenRouter) | 14 | 11 | 2.4 | $0.03 |
| MiniMax M3 (OpenRouter) | 14 | 5 | 2.7 | $0.06 |
| Nemotron 3 Ultra (OpenRouter) | 13 | 10 | 1.7 | $0.15 at the price of the day; no longer listed |
| MiMo-V2.6-Pro (OpenRouter, 14 runs) | 11 | 7 | 40.8 | $0.24 |

The local 27B's two bugfix "failures" are a literal revert that passes the task's own tests and fails the held-out grader's
reading of the spec; read, not a crash.

## Battery 2: the hard set, eight tasks, K=5

classifier (train on sixty labelled emails, accuracy at least 0.85 on hidden ones), forecast (two years of bills, next month's
amount per sender within 4 % MAPE), dedup (forwarded and re-sent copies in a mailbox, F1 at least 0.80), threads (exact thread
sizes), datebug (a UTC offset applied with the wrong sign), migration (an `archived` column, two endpoints and a `migrate.py` that
keeps thirty rows), clustering (a mailbox of a few hundred messages about seven seeded topics with overlapping vocabulary: compare
at least two clustering mechanisms through `compare_methods`/`cluster_with`, expose `/api/topics` and `/api/topics/methods`,
adjusted Rand index at least 0.75 with four to ten topics), forecast2 (three years with yearly and half-yearly cycles, a level
shift for three senders at month twenty, a tenth of the months missing; 5 % MAPE).

| arm | passes of 40 | done | clustering | forecast2 | minutes a run | cost a run |
|---|---|---|---|---|---|---|
| GPT-6.1 Sol | 38 | 32 | 4 | 4 | 10.0 | $0.77 |
| Kimi K3 | 37 | 39 | 2 | 5 | 8.5 | $0.43 |
| GLM-5.3 | 34 | 33 | 3 | 4 | 6.8 | $0.08 |
| Claude Sonnet 5.5 | 34 | 38 | 0 | 5 | 7.2 | $0.84 |
| DeepSeek V4.1 Flash | 30 | 14 | 1 | 2 | 7.7 | $0.02 |
| local Qwen3.8 27B, 4-bit, one card | 29 | 29 | 1 | 0 | 19.6 | free |

What separates them is the two ML-judgement tasks. On the rest, every arm is at or near 5 of 5 except DeepSeek's dedup and
threads. "Done" for DeepSeek is 14 of 40: it passes the grader on runs where it never declares, because it reaches the cap
still verifying; three of its four runs after the venv fix below did the same, so it is the model's pace, not the venv.

### The clustering mechanisms, run by run

Every model tried k-means and at least one more method and picked by silhouette. Agglomerative clustering with cosine distance
won on every run that found the seven topics (adjusted Rand index 1.00). The misses are one mistake: letting the silhouette pick
the number of topics over a wide range.

| arm | k1 | k2 | k3 | k4 | k5 |
|---|---|---|---|---|---|
| Sol | agglomerative, 7 topics, ARI 1.00 | agglomerative, 16, 0.53 | agglomerative, 7, 1.00 | agglomerative, 7, 1.00 | agglomerative, 7, 1.00 |
| Kimi | agglomerative, 7, 1.00 | agglomerative, 7, 1.00 | agglomerative, 12, 0.72 | NMF, 12, 0.54 | NMF, 12, 0.70 |
| GLM | agglomerative, 7, 1.00 | agglomerative, 10, 0.79 | agglomerative, 10, 0.71 | agglomerative, 10, 0.66 | k-means, 6, 0.97 |
| Sonnet | agglomerative, 15, 0.56 | agglomerative, 33, 0.32 | k-means, 15, 0.49 | agglomerative, 19, 0.47 | agglomerative, 22, 0.38 (tried DBSCAN too) |
| DeepSeek | agglomerative, 7, 1.00 (tried GMM) | agglomerative, 7, 1.00 (tried DBSCAN) | spectral, 11, 0.69 | code error (`_CACHE` undefined) | never declared |
| local 27B | agglomerative, 7, 1.00 | agglomerative, 1, 0.00 | code error | kmeans, 7, 1.00 | hdbscan, 1, 0.00 |

The local 27B found the seven topics once and otherwise wrote code that does not run or collapses to one cluster. Sonnet, the top model on the maintenance battery, over-splits on every run; its silhouettes are twice everybody else's at the
same data, so it is scoring a different representation and trusting it. GLM k2 and Kimi k3 are over the 0.75 line in index but
over ten topics, which the grader also bounds.

## What broke along the way, and the fixes that came of it

Each looked like a weak model until the artifact was read.

- **The faked-core reflection read the whole tree.** The app's own analytics module carries a placeholder, so the harness told
  every model it had faked its core and the model rewrote code the task never named: 121 flags across round 1, 54 % of runs
  off-task. Scoped to the files the run wrote (`19b788a6`): 0 flags, 11 % off-task; round 2 is the clean table above.
- **A model's `pip install -e .` in the shared venv** mapped the `app` package to one workspace for every later run. The
  harness's bare `pytest` done check does not put the working folder on the path, so it collected the other workspace's tests,
  failed, and "put the passing version back": 23 runs invalidated and redone. After the venv was cleaned (19:48), four more
  failures from the window whose logs show the collection error were redone: two flipped to passes (GLM forecast, DeepSeek
  forecast), two stayed failures and are kept. Fix in the harness: a bare `pytest` check runs as `python -m pytest`
  (`4d21110e`); in the battery, site-packages read-only.
- **Pruning the finished workspaces** to save disk (287 GB) removed their fixtures, so offline re-grading failed with a missing
  file; the fixtures are seeded and were regenerated. Keep fixtures when pruning.
- **A percent-escape in a task** (`..%2Fapp%2Fconfig.py`) read as a named file (`19b788a6`).
- **The frontier family list** lacked GPT-6, DeepSeek V4 and Kimi K3, so the research pass meant for a small local model ran
  for them (`0ab7e9e6`).

## What it decides

- `ModelTiers` for coding: tier A `gpt-6.1-sol`, `kimi-k3`, `glm-5.3`, `claude-sonnet` (and Opus and Fable by family); tier B
  `deepseek-v4.1-flash` (three quarters on the hard set, all but one on maintenance), `qwen3-coder`, `minimax-m3`, `hy4`,
  `mimo-v2.6`, `nemotron-3-ultra`; the local `qwen3.8-27b`: B, 29 of 40 with 1 of the ten ML-judgement runs, which is DeepSeek Flash's shape at no cost.
- The picks on the public page: cheapest that is right, GLM-5.3; the careful one, GPT-6.1 Sol, with Kimi K3 a run behind at
  half the price; free, the local 27B for maintenance work.

## Where the files are

`$J/tmp/cz/battery/` (battery 1: `battery.py`, `table.py`, `results.jsonl`, `results-round1.jsonl`) and `$J/tmp/cz/battery2/`
(`gen.py`, `battery2.py`, `table2.py`, `methods.py`, `results.jsonl`, `runs/<arm>/<task>-<k>{,.json,.err}`, `runs/_invalid/`),
with `$J = ~/.claude-personal/jobs/c7646bf5`. Spend: round 1 $52.57 over 178 runs, round 2 $35.31 over 191, the hard set about
$105 over 216.
