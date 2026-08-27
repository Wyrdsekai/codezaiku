# review-eval — scoring the `review` verb against real human reviews

The review surface had no oracle, which made every change to it unmeasurable: a new
prompt or matcher produces *different* output, and without ground truth there is no way
to tell that from *better*. This is the oracle.

Ground truth is written by real reviewers on real merged PRs. **We do not author it** —
that is the entire point, and the reason self-made fixtures were abandoned.

## Two steps

```bash
# 1. harvest (diff hunk, human review comment) pairs
python3 mine_reviews.py "pandas-dev/pandas,apache/kafka,rust-lang/rust" 4 > corpus.jsonl

# 2. run `review` over those PRs and score against the humans
python3 score_review.py corpus.jsonl 25
```

The corpus is **not** checked in: it is other projects' review comments, and it is cheap
to regenerate (2000 threads used under 10% of an hourly GraphQL budget).

## Sampling

PRs are drawn **stratified across repos, then shuffled** (seeded by `SCORE_SEED`). The first version
sorted by fewest-comments and took the head, which looked like "smallest first" but broke ties by
corpus order — so every scored PR came from the first two repos while kafka's 480 scorable threads
and rust's 208 were never touched. Any number off that sample described two Python projects, not the
corpus. **Check what your sample actually contains before reading a number off it.**

## Which recall number to read

**Per-PR mean is primary.** Comment-weighting lets one heavily-reviewed PR dominate: in a stratified
sample one PR carried 33 human comments while most carried one, so finding 2 of 33 on a big review
scored worse than missing the only comment on a small one. Same data, both weightings:

| comment-weighted | 5.0% |
| **per-PR mean** | **15.8%** |
| single-comment PRs only | 33.3% |

The last row exists because an earlier sample happened to contain ONLY single-comment PRs and scored
32.0%. On comparable PRs the result is unchanged — the apparent collapse to 5% was the denominator
changing shape, not a regression. **Never compare recall across samples without checking how many
comments each PR carries.**

Large diffs are skipped (>200k) and diffs over the review's inline budget are only partly shown, so
recall is understated by an unknown amount and the sample is biased toward smaller changes. The run
reports both.

## What the numbers mean, and do not mean

Measured on a hand-read sample of 40 human threads:

* **~77% of harvested human threads are genuine findings** (95% CI [65, 90]). The rest are
  discussion, author self-explanation, or requests about things outside the diff.
* **~20% of raw threads are bots** — excluded by `is_bot`. This is the filter that matters
  most; leaving them in flatters any tool that finds the same class of thing.
* `has_suggestion` threads are 100% findings but a **biased subset** — most come from one
  repo and most are wording edits. Scoring only on those measures phrasing, not bug-finding.

Matching is **location-only**: same file, within ±5 lines. A finding at the right line for
the wrong reason counts as a hit, so the output is an **upper bound**, not agreement. That
is deliberate — the alternative is an LLM judge, and a mis-calibrated grader has produced a
confident wrong number on this project before. Read a sample by hand to estimate the
inflation rather than assuming it away.

**Use it to compare two harness versions, not to make an absolute precision claim.** A fixed
imperfect oracle is valid for A/B because the noise hits both arms equally.

## A filter that did NOT work, kept as a warning

The first version gated on *"a later commit touched the file this comment was left on"*, as
a proxy for the comment being actionable. Hand-reading showed it **anti**-correlated: 72%
findings among what it kept, 87.5% among what it discarded — in an active PR files churn for
unrelated reasons, so it mostly measured PR activity. It is metadata now, never a gate.

## Related

The public Microsoft **CodeReviewer** dataset (150k pairs, 9 languages) is far larger, but
~48% of it is structurally misaligned — conversational, vague, or dependent on context
outside the diff (arXiv 2607.25851). Do not adopt it as ground truth unfiltered.
