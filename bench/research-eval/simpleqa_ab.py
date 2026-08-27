#!/usr/bin/env python3
"""
SimpleQA A/B for CodeZaiku's research capability — EXTERNAL oracle, no self-authored fixtures.

SimpleQA (OpenAI, 4,326 short fact-seeking questions with a single unambiguous gold answer) is
downloaded from its public blob; we sample deterministically and run the SAME question set through
both arms of the gap-reflection loop:

    arm OFF : CODEZAIKU_GAPREFLECT=off   (search -> read -> answer; the "browsing tools" tier)
    arm ON  : gap reflection enabled     (... -> reflect on what is still unsourced -> query that gap)

Grading is objective and arm-blind: each question carries the SAME appended instruction to end with
`ANSWER: <short answer>`, and the gold answer must appear in that line (normalized). Every raw answer
is written to disk so the grade can be checked by reading, which is the real measure.

Usage:
    python3 simpleqa_ab.py --n 20 --turns 24 --out ~/.codezaiku/bench/simpleqa
"""
import argparse, csv, json, os, pathlib, random, re, subprocess, sys, time, urllib.request

CSV_URL = "https://openaipublic.blob.core.windows.net/simple-evals/simple_qa_test_set.csv"
ANSWER_RE = re.compile(r"ANSWER:\s*(.+?)\s*$", re.MULTILINE | re.IGNORECASE)
# No angle-bracket placeholder here: the 9B copies the template literally when one is present
# (observed: `ANSWER: <the short answer, just the fact itself ...>`), which destroys the tagged answer.
ANSWER_TAIL = ("\n\nFinally, on its own last line, write the word ANSWER, a colon, and then just the "
               "short fact itself — no sentence, no explanation, no placeholder.")
# A line that echoed the instruction rather than answering it is NOT an answer.
PLACEHOLDER = re.compile(r"[<>]|short answer|the fact itself|no explanation|placeholder", re.IGNORECASE)


def dataset(cache: pathlib.Path):
    if not cache.exists():
        cache.parent.mkdir(parents=True, exist_ok=True)
        print(f"downloading SimpleQA -> {cache}")
        urllib.request.urlretrieve(CSV_URL, cache)
    with cache.open(newline="", encoding="utf-8") as fh:
        return [r for r in csv.DictReader(fh)]


def normalize(s: str) -> str:
    s = s.lower().replace("&", "and")
    s = re.sub(r"\b(the|a|an)\b", " ", s)
    s = re.sub(r"[^a-z0-9 ]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def graded(answer_line: str, gold: str) -> bool:
    """Gold must be present in the model's own ANSWER line. Bidirectional containment on the
    normalized forms (the SealQA adapter's string fallback) — tight because the line is short."""
    a, g = normalize(answer_line), normalize(gold)
    if not a or not g:
        return False
    return g in a or a in g


def run_one(q: str, arm: str, cp: str, drive: str, turns: int, workdir: pathlib.Path, timeout: int):
    qf = workdir / "question.txt"
    qf.write_text(q + ANSWER_TAIL, encoding="utf-8")
    env = dict(os.environ)
    if arm == "off":
        env["CODEZAIKU_GAPREFLECT"] = "off"
    else:
        env.pop("CODEZAIKU_GAPREFLECT", None)
    # POOL ISOLATION: a shared research memory pool would carry arm A's harvested findings into arm B
    # (and question i's into question i+1) — every run gets a fresh, empty pool so the arms measure the
    # gap-reflection loop alone.
    env["CODEZAIKU_RESEARCH_POOL"] = str(workdir / f"pool-{arm}.jsonl")
    pathlib.Path(env["CODEZAIKU_RESEARCH_POOL"]).unlink(missing_ok=True)
    cmd = ["timeout", "-k", "30", str(timeout), "java", "-cp", cp,
           "org.codezaiku.FamiliarMain", "research", "@" + str(qf), "depth", drive, str(turns)]
    t0 = time.time()
    p = subprocess.run(cmd, cwd=workdir, env=env, capture_output=True, text=True)
    out = p.stdout + "\n" + p.stderr
    # GRADE THE ANSWER, NOT THE RUN LOG. logback writes to stderr, so a naive stdout+stderr split puts
    # every search snippet and fetched page excerpt into the "answer" — grading that scores "the gold
    # string appeared somewhere in the console output", which mostly measures whether SEARCH found the
    # right page and is biased toward whichever arm fetches more. (It scored a run PASS that ended in
    # "max turns reached without task_done" and never answered at all.) Take stdout only, cut at the
    # first log line, and treat a run that never concluded as having produced no answer.
    body = p.stdout.split("=== RESEARCH ===", 1)[1] if "=== RESEARCH ===" in p.stdout else ""
    cut = re.search(r"^\d\d:\d\d:\d\d\s+(INFO|WARN|ERROR|DEBUG|TRACE)\b", body, re.MULTILINE)
    body = (body[:cut.start()] if cut else body).strip()
    if body.startswith(("max turns", "task_blocked:")):
        body = ""
    hits = [h for h in ANSWER_RE.findall(body) if not PLACEHOLDER.search(h)]
    gaps = out.count("gap reflection")
    fetches = out.count("web_fetch({")
    return {"rc": p.returncode, "secs": round(time.time() - t0, 1), "answer_line": hits[-1] if hits else "",
            "body": body.strip(), "gaps": gaps, "fetches": fetches, "raw": out,
            "concluded": "task_done at turn" in out,
            "degraded": out.count("SEARCH BACKEND DEGRADED"),
            "searches": out.count("web_search({")}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=20)
    ap.add_argument("--turns", type=int, default=24)
    ap.add_argument("--seed", type=int, default=1234)
    ap.add_argument("--drive", default="http://localhost:8200")
    ap.add_argument("--timeout", type=int, default=900, help="hard per-run wall clock (seconds)")
    ap.add_argument("--arms", default="off,on")
    ap.add_argument("--pace", type=float, default=8.0,
                    help="seconds between runs — back-to-back runs rate-limit the free search upstreams")
    ap.add_argument("--cp", default=None, help="java classpath (default: ./gradlew :core:printCp)")
    ap.add_argument("--out", default=os.path.expanduser("~/.codezaiku/bench/simpleqa"))
    a = ap.parse_args()

    out = pathlib.Path(a.out).expanduser()
    (out / "answers").mkdir(parents=True, exist_ok=True)
    rows = dataset(out / "simple_qa_test_set.csv")
    rng = random.Random(a.seed)
    sample = rng.sample(rows, a.n)
    cp = a.cp or subprocess.run(["./gradlew", ":core:printCp", "-q", "--no-daemon", "--console=plain"],
                                capture_output=True, text=True).stdout.strip().splitlines()[-1]
    work = out / "work"
    work.mkdir(exist_ok=True)

    results = []
    pace = a.pace
    for arm in a.arms.split(","):
        for i, row in enumerate(sample):
            q, gold = row["problem"], row["answer"]
            r = run_one(q, arm, cp, a.drive, a.turns, work, a.timeout)
            # Prefer the model's own ANSWER line; fall back to the prose body when it didn't emit one
            # (the answer is still there, just not tagged). `how` records which, so a generous
            # body-containment grade can be told apart from a tight one when reading the results.
            if r["answer_line"]:
                ok, how = graded(r["answer_line"], gold), "answer-line"
            else:
                ok, how = graded(r["body"], gold), "body"
            (out / "answers" / f"{arm}-{i:03d}.txt").write_text(
                f"Q: {q}\nGOLD: {gold}\nARM: {arm}  graded={ok} ({how})  gaps={r['gaps']} "
                f"fetches={r['fetches']} rc={r['rc']} concluded={r['concluded']} secs={r['secs']}\n\n"
                f"{r['body']}\n", encoding="utf-8")
            results.append({"arm": arm, "i": i, "gold": gold, "answer_line": r["answer_line"],
                            "ok": ok, "how": how, "gaps": r["gaps"], "fetches": r["fetches"],
                            "rc": r["rc"], "concluded": r["concluded"], "secs": r["secs"],
                            "degraded": r["degraded"], "searches": r["searches"]})
            print(f"[{arm} {i+1}/{a.n}] {'PASS' if ok else 'fail'} ({how})  gaps={r['gaps']} "
                  f"search={r['searches']} fetch={r['fetches']} deg={r['degraded']} {r['secs']}s  "
                  f"gold={gold[:40]!r} got={r['answer_line'][:40]!r}", flush=True)
            # MEASUREMENT INTEGRITY: a rate-limited search backend makes both arms look equally bad and the
            # whole A/B measures nothing (it already happened once). Key this on the tool's OWN degradation
            # signal, NOT on "searched but never fetched" — those are different things, and conflating them
            # aborted a valid run: three runs searched 20/7/8 times and fetched nothing while the backend was
            # healthy (verified by hand afterwards, 9-10 results for the very queries that "failed"). That is
            # SEARCH FIXATION by the model, a finding in its own right, not a dead substrate.
            dead = [x for x in results[-3:] if x["degraded"] > 0 and x["fetches"] == 0]
            if len(dead) == 3 and len(results) >= 3:
                sys.exit("\nABORTED — 3 consecutive runs hit SEARCH BACKEND DEGRADED and fetched nothing: the "
                         "upstream engines are rate-limited. Results so far are NOT a valid A/B. Let them cool "
                         "down, verify with a manual query, then re-run.")
            if r["searches"] >= 5 and r["fetches"] == 0 and r["degraded"] == 0:
                print(f"    note: {r['searches']} searches, 0 fetches, backend healthy — search fixation",
                      flush=True)
            time.sleep(pace)
            (out / "results.jsonl").open("a", encoding="utf-8").write(json.dumps(results[-1]) + "\n")

    print("\n=== SCORECARD ===")
    for arm in a.arms.split(","):
        rs = [r for r in results if r["arm"] == arm]
        if not rs:
            continue
        n_ok = sum(r["ok"] for r in rs)
        tight = sum(1 for r in rs if r["ok"] and r["how"] == "answer-line")
        concluded = sum(1 for r in rs if r["concluded"])
        print(f"{arm:>3}: {n_ok}/{len(rs)} correct ({tight} on a tagged ANSWER line) | "
              f"{concluded}/{len(rs)} concluded | mean {sum(r['secs'] for r in rs)/len(rs):.0f}s | "
              f"mean fetches {sum(r['fetches'] for r in rs)/len(rs):.1f}")
    print(f"\nanswers written to {out/'answers'} — READ them; the containment grade is a proxy.")


if __name__ == "__main__":
    main()
