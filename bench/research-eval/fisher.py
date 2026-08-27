#!/usr/bin/env python3
"""Two-sided Fisher exact test on an A/B scorecard — no scipy dependency.

    python3 fisher.py results.jsonl          # reads {"arm":..., "ok":...} rows
    python3 fisher.py --table 4 26 11 19     # a_ok a_fail b_ok b_fail
"""
import json, math, sys
from collections import defaultdict


def fisher_two_sided(a, b, c, d):
    """Table [[a,b],[c,d]]. Sums the probability of every table at least as extreme."""
    n = a + b + c + d
    r1, r2, c1 = a + b, c + d, a + c

    def p(x):
        return (math.comb(r1, x) * math.comb(r2, c1 - x)) / math.comb(n, c1)

    obs = p(a)
    lo, hi = max(0, c1 - r2), min(r1, c1)
    return min(1.0, sum(p(x) for x in range(lo, hi + 1) if p(x) <= obs * (1 + 1e-9)))


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--table":
        a, b, c, d = map(int, sys.argv[2:6])
        arms = [("A", a, b), ("B", c, d)]
    else:
        tally = defaultdict(lambda: [0, 0])
        with open(sys.argv[1], encoding="utf-8") as fh:
            for line in fh:
                if not line.strip():
                    continue
                r = json.loads(line)
                tally[r["arm"]][0 if r["ok"] else 1] += 1
        names = list(tally)
        if len(names) != 2:
            sys.exit(f"need exactly 2 arms, found {names}")
        arms = [(n, tally[n][0], tally[n][1]) for n in names]
        a, b, c, d = arms[0][1], arms[0][2], arms[1][1], arms[1][2]

    for name, ok, fail in arms:
        print(f"{name:>4}: {ok}/{ok+fail} = {ok/(ok+fail):.1%}")
    print(f"\ntwo-sided Fisher exact p = {fisher_two_sided(a, b, c, d):.4f}")
    print("(p <= 0.05 = a real difference; anything else is one sample of a noisy process)")


if __name__ == "__main__":
    main()
