#!/usr/bin/env python3
"""Re-score a finished WideSearch run from its saved answer files with the CURRENT scorer.

The run's model work is on disk (answers/<id>.txt); scoring is separable from running. Use after any
scorer improvement (header alignment, key containment) to re-grade without re-running the model.

    python3 widesearch_rescore.py ~/.codezaiku/bench/widesearch-full --judge http://${CP_HOST}:8201
"""
import argparse, io, contextlib, json, pathlib, statistics, sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import widesearch_probe as wp


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("rundir")
    ap.add_argument("--judge", default=None)
    a = ap.parse_args()
    wp.JUDGE_URL = a.judge.rstrip("/") if a.judge else None

    with contextlib.redirect_stdout(io.StringIO()):
        tasks = {t["id"]: t for t in wp.judge_free_tasks(include_judged=True)}
    rundir = pathlib.Path(a.rundir).expanduser()
    old = {json.loads(l)["id"]: json.loads(l) for l in (rundir / "results.jsonl").open()}

    out = rundir / "results_rescored.jsonl"
    rows = []
    with out.open("w") as fh:
        for tid, o in old.items():
            t = tasks.get(tid)
            f = rundir / "answers" / f"{tid}.txt"
            if not t or not f.exists():
                continue
            body = f.read_text().split("ANSWER:", 1)
            body = body[1] if len(body) > 1 else ""
            pred = wp.parse_table(body)
            s = wp.score(pred, t["gold_rows"], t["eval"])
            rows.append({"id": tid, **s, "old_f1": o["f1"]})
            fh.write(json.dumps(rows[-1]) + "\n")
            if abs(s["f1"] - o["f1"]) > 0.05:
                print(f"{tid}: {o['f1']:.2f} -> {s['f1']:.2f}")

    f1 = [r["f1"] for r in rows]
    print(f"\nRESCORED mean item-F1 {statistics.mean(f1):.3f} (was "
          f"{statistics.mean(r['old_f1'] for r in rows):.3f}) over {len(rows)} | "
          f"zeros {sum(1 for x in f1 if x == 0)} (was {sum(1 for r in rows if r['old_f1'] == 0)})")


if __name__ == "__main__":
    main()
