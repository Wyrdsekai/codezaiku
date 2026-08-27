#!/usr/bin/env python3
"""Re-grade a finished A/B from the saved answer files, uniformly across arms.

The live runner grades the model's tagged ANSWER line when it emits one and the prose body otherwise —
which means a run that emitted a MALFORMED tag (a 9B quoting the instruction back: `ANSWER: " followed
by the fact, and included the`) is graded on garbage while its prose may hold the right answer. That
asymmetry is grading noise, not a research difference.

This regrades every run the SAME way — gold present in the answer body — and reports the tagged-line
number alongside it, so the headline metric is uniform and the tighter one is still visible.

    python3 regrade.py ~/.codezaiku/bench/simpleqa
"""
import pathlib, re, sys
from collections import defaultdict

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from simpleqa_ab import ANSWER_RE, PLACEHOLDER, graded  # noqa: E402

HEADER = re.compile(r"^Q: (?P<q>.*)\nGOLD: (?P<gold>.*)\nARM: (?P<arm>\S+)\s+graded=(?P<g>\S+)\s+"
                    r"\((?P<how>[^)]*)\)\s+(?P<rest>.*)$", re.MULTILINE)
# The run LOG (logback -> stderr) follows the answer in the captured output. Grading through it scores
# "the gold string appeared somewhere in this run's console output" — which mostly measures whether SEARCH
# surfaced the right page, and is biased toward whichever arm fetches more. The answer ends at the first
# log line.
LOG_LINE = re.compile(r"^\d\d:\d\d:\d\d\s+(INFO|WARN|ERROR|DEBUG|TRACE)\b", re.MULTILINE)
# A run that never concluded produced no answer, whatever text is lying around.
NO_ANSWER = ("max turns", "task_blocked:")


def answer_only(body: str) -> str:
    m = LOG_LINE.search(body)
    ans = (body[:m.start()] if m else body).strip()
    return "" if ans.startswith(NO_ANSWER) else ans


def main():
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").expanduser() / "answers"
    tally = defaultdict(lambda: {"n": 0, "body": 0, "tagged_ok": 0, "tagged_n": 0})
    rows = []
    for f in sorted(root.glob("*.txt")):
        text = f.read_text(encoding="utf-8")
        m = HEADER.search(text)
        if not m:
            print(f"skip (unparseable header): {f.name}")
            continue
        gold, arm = m.group("gold"), m.group("arm")
        body = answer_only(text[m.end():])
        ok_body = graded(body, gold) if body else False
        hits = [h for h in ANSWER_RE.findall(body) if not PLACEHOLDER.search(h)]
        t = tally[arm]
        t["n"] += 1
        t["body"] += ok_body
        if hits:
            t["tagged_n"] += 1
            t["tagged_ok"] += graded(hits[-1], gold)
        rows.append((arm, f.name, ok_body, gold))

    print("=== UNIFORM REGRADE (gold present in the answer body — same rule for every run) ===")
    for arm, t in sorted(tally.items()):
        pct = t["body"] / t["n"] if t["n"] else 0
        print(f"{arm:>3}: {t['body']}/{t['n']} = {pct:.1%}   "
              f"(of the {t['tagged_n']} runs that tagged an ANSWER line, {t['tagged_ok']} were right)")
    arms = sorted(tally)
    if len(arms) == 2:
        a, b = (tally[x] for x in arms)
        print(f"\nfeed to fisher.py --table {a['body']} {a['n']-a['body']} "
              f"{b['body']} {b['n']-b['body']}")
    print("\nfailures worth reading:")
    for arm, name, ok, gold in rows:
        if not ok:
            print(f"  {name}  gold={gold[:50]!r}")


if __name__ == "__main__":
    main()
