#!/usr/bin/env python3
"""
WideSearch probe — CodeZaiku's research verb on the judge-free slice of WideSearch.

WideSearch (ByteDance, 200 broad info-seeking tasks, gold tables + per-column eval pipeline) is the
breadth benchmark whose shape matches the research capability. 13 of the 100 EN tasks are gradeable
with NO LLM judge (exact_match / number_near columns only) — this probe runs those and scores them
with the benchmark's own per-cell rules, row-aligned on the task's declared unique_columns.

This is a CAPABILITY PROBE (K=13, fixed set), not an A/B: it answers "can the 9B do broad structured
collection at all, and where does it break" — read the per-task output, not just the F1.

    python3 widesearch_probe.py --turns 40 --out ~/.codezaiku/bench/widesearch
"""
import argparse, json, os, pathlib, re, subprocess, sys, time, urllib.request

GOLD_SNAPSHOT = None  # resolved via huggingface_hub

# The OFFICIAL WideSearch judge prompt (ported verbatim from the Harbor adapter's metric_utils.py) —
# batch per column, integer 0/1 per cell, markdown-JSON output, any failure scores the column 0.
JUDGE_PROMPT = """You are an expert in grading answers. Your task is to score the responses to a certain question. Below, you will be provided with a set of standard answers, a set of responses to be graded, and specific grading criteria.

Each answer and each response has an idx. Please score each pair of answers and responses in this set according to the following methods:
1. The scoring range is from 0 to 1. A score of 1 indicates a completely correct answer. For deduction items, please refer to the specific grading criteria section.
2. After reading the standard answers, responses to be graded, and grading criteria, please first analyze and judge them item by item according to the grading criteria.
3. The score can only be an integer of 0 or 1.
4. After the analysis and judgment, please provide the final scoring results. Each pair should have a score. Output in Markdown JSON format, as shown below:
```json
{{
    "idx_xxx": score,
    "idx_yyy": score,
    ...
}}
```

====== criterion-start ======
{criterion}
====== criterion-end ======

====== response-start ======
{response}
====== response-end ======

Now start scoring. Please make sure to analyze each item step by step before providing the final scoring results.

"""

JUDGE_URL = None  # set from --judge; without it llm_judge columns score 0 (such tasks skipped unless --all)


def judge_column(pairs, criterion):
    """pairs = [(pred, gold), ...] -> [0/1, ...]; official semantics: any failure -> all zeros."""
    if not JUDGE_URL or not pairs:
        return [0.0] * len(pairs)
    resp_dict = {f"idx_{i}": {"response": p, "target": g} for i, (p, g) in enumerate(pairs)}
    prompt = JUDGE_PROMPT.format(criterion=criterion or "", response=json.dumps(resp_dict, ensure_ascii=False))
    body = json.dumps({"model": "judge", "messages": [{"role": "user", "content": prompt}],
                       "max_tokens": 4096, "temperature": 0.0, "stream": False,
                       "chat_template_kwargs": {"enable_thinking": False}}).encode()
    try:
        req = urllib.request.Request(f"{JUDGE_URL}/v1/chat/completions", data=body,
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=300) as r:
            text = json.load(r)["choices"][0]["message"]["content"]
        m = re.search(r"```json\s*(\{.*?\})\s*```", text, re.DOTALL) or re.search(r"(\{[^{}]*\})\s*$", text, re.DOTALL)
        scores = json.loads(m.group(1))
        return [float(scores.get(f"idx_{i}", 0)) for i in range(len(pairs))]
    except Exception:
        return [0.0] * len(pairs)


def norm_str(s) -> str:
    s = "" if s is None else str(s)
    s = s.lower().strip()
    s = s.replace("\u2019", "'").replace("\u2018", "'").replace("\u201c", '"').replace("\u201d", '"')
    return re.sub(r"[^a-z0-9]+", "", s)


NUM_RE = re.compile(r"-?[\d,]+(?:\.\d+)?")


def extract_number(s):
    m = NUM_RE.search("" if s is None else str(s))
    if not m:
        return None
    try:
        return float(m.group(0).replace(",", ""))
    except ValueError:
        return None


def is_nan(s) -> bool:
    return norm_str(s) in ("nan", "na", "none", "null", "notavailable", "")


def cell_match(pred, gold, spec) -> bool:
    metrics = spec.get("metric", ["exact_match"])
    if is_nan(gold):
        return is_nan(pred)
    if "number_near" in metrics:
        gp, gn = extract_number(pred), extract_number(gold)
        if gn is None:
            return norm_str(pred) == norm_str(gold)
        if gp is None:
            return False
        tol = spec.get("criterion", 0.0) or 0.0
        return abs(gp - gn) <= abs(gn) * float(tol) + 1e-9
    return norm_str(pred) == norm_str(gold)


def parse_table(answer: str):
    """Markdown table -> list of row dicts keyed by normalized header."""
    rows, header = [], None
    for line in answer.splitlines():
        line = line.strip()
        if line.count("|") < 2:
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        if all(re.fullmatch(r":?-{2,}:?", c) for c in cells if c):
            continue  # separator row
        if header is None:
            header = [norm_str(c) for c in cells]
            continue
        if len(cells) < len(header):
            cells += [""] * (len(header) - len(cells))
        rows.append(dict(zip(header, cells)))
    return rows


def align_headers(pred_rows, wanted):
    """Map the pred table's ACTUAL headers onto the eval's canonical column names by normalized
    containment ('clinicname' ⊇ 'clinic', 'couples' ⊆ 'couplemarriedattheshow'). The official eval
    aligns columns with an LLM; containment is the conservative mechanical version — without it a
    correct table under a synonymous header scores 0 (measured: 38 of 64 zeros were join failures,
    several purely from header naming)."""
    if not pred_rows:
        return pred_rows
    have = list(pred_rows[0].keys())
    mapping = {}
    for w in wanted:
        if w in have:
            continue
        best = None
        for h in have:
            if h in mapping.values():
                continue
            if (w in h or h in w) and h and w:
                if best is None or abs(len(h) - len(w)) < abs(len(best) - len(w)):
                    best = h
        if best:
            mapping[w] = best
    if not mapping:
        return pred_rows
    return [{**r, **{w: r.get(h, "") for w, h in mapping.items()}} for r in pred_rows]


def keys_equal(a, b):
    """Normalized key-tuple match with bidirectional containment per component — the mechanical stand-in
    for the official LLM key alignment ('Fresno Clinic' vs 'FPA Women's Health - Fresno' still misses;
    reorderings and prefix/suffix noise don't)."""
    if a == b:
        return True
    if len(a) != len(b):
        return False
    for x, y in zip(a, b):
        if x == y:
            continue
        if not x or not y or (x not in y and y not in x):
            return False
    return True


def score(pred_rows, gold_rows, ev):
    """WideSearch item F1: rows aligned on unique_columns; each required cell scored by its pipeline."""
    unique = [norm_str(c) for c in ev["unique_columns"]]
    required = [norm_str(c) for c in ev["required"]]
    pipeline = {norm_str(k): v for k, v in ev["eval_pipeline"].items()}
    pred_rows = align_headers(pred_rows, required)

    def key(row):
        return tuple(norm_str(row.get(c, "")) for c in unique)

    gold_by_key = {key(r): r for r in gold_rows}
    pred_by_key = {}
    for r in pred_rows:
        pred_by_key.setdefault(key(r), r)  # first wins on duplicate keys
    # containment fallback for keys that missed exactly
    for gk in list(gold_by_key):
        if gk in pred_by_key:
            continue
        for pk in pred_by_key:
            if pk not in gold_by_key and keys_equal(gk, pk):
                pred_by_key[gk] = pred_by_key[pk]
                break

    tp = 0
    total_gold = len(gold_rows) * len(required)
    total_pred = len(pred_rows) * len(required)
    matched = [(g, pred_by_key[k]) for k, g in gold_by_key.items() if k in pred_by_key]
    for col in required:
        spec = pipeline.get(col, {})
        if col in unique:          # matched by the join itself
            tp += len(matched)
            continue
        if "llm_judge" in spec.get("metric", []):
            # judge the whole column in ONE call (official batching); gold-nan cells shortcut locally
            pairs, nans = [], 0
            for g, p in matched:
                if is_nan(g.get(col)):
                    nans += is_nan(p.get(col))
                else:
                    pairs.append((str(p.get(col, "")), str(g.get(col, ""))))
            tp += nans + int(sum(judge_column(pairs, spec.get("criterion", ""))))
            continue
        for g, p in matched:
            if cell_match(p.get(col), g.get(col), spec):
                tp += 1
    prec = tp / total_pred if total_pred else 0.0
    rec = tp / total_gold if total_gold else 0.0
    f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
    return {"tp": tp, "gold_cells": total_gold, "pred_cells": total_pred,
            "precision": round(prec, 3), "recall": round(rec, 3), "f1": round(f1, 3),
            "rows_matched": sum(1 for k in gold_by_key if k in pred_by_key),
            "gold_rows": len(gold_rows), "pred_rows": len(pred_rows)}


def judge_free_tasks(include_judged=False):
    import pandas as pd
    from datasets import load_dataset
    from huggingface_hub import snapshot_download
    snap = pathlib.Path(snapshot_download(repo_id="ByteDance-Seed/WideSearch", repo_type="dataset"))
    gold_dir = snap / "widesearch_gold"
    ds = load_dataset("ByteDance-Seed/WideSearch", split="full")
    out = []
    for r in ds:
        if r["language"] != "en":
            continue
        ev = r["evaluation"]
        ev = json.loads(ev) if isinstance(ev, str) else ev
        metrics = {m for c in ev["eval_pipeline"].values() for m in c.get("metric", [])}
        if "llm_judge" in metrics and not include_judged:
            continue
        f = gold_dir / f"{r['instance_id']}.csv"
        if not f.exists():
            continue
        gold = pd.read_csv(f)
        gold_rows = [{norm_str(c): ("" if pd.isna(v) else str(v)) for c, v in row.items()}
                     for row in gold.to_dict(orient="records")]
        cells = gold.shape[0] * gold.shape[1]
        out.append({"id": r["instance_id"], "query": r["query"], "eval": ev,
                    "gold_rows": gold_rows, "cells": cells})
    out.sort(key=lambda t: t["cells"])
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--turns", type=int, default=40)
    ap.add_argument("--drive", default="http://localhost:8200")
    ap.add_argument("--timeout", type=int, default=1200)
    ap.add_argument("--limit", type=int, default=13)
    ap.add_argument("--cp", default=None)
    ap.add_argument("--all", action="store_true",
                    help="include llm_judge-metric tasks (needs --judge); default = judge-free slice only")
    ap.add_argument("--judge", default=None,
                    help="OpenAI-compatible base URL for the llm_judge columns (e.g. http://${CP_HOST}:8201)")
    ap.add_argument("--out", default=os.path.expanduser("~/.codezaiku/bench/widesearch"))
    ap.add_argument("--mode", default="broad", help="research mode: broad | depth | fan")
    a = ap.parse_args()
    global JUDGE_URL
    JUDGE_URL = a.judge.rstrip("/") if a.judge else None
    if a.all and not JUDGE_URL:
        sys.exit("--all needs --judge (llm_judge columns would silently score 0 otherwise)")

    out = pathlib.Path(a.out).expanduser()
    (out / "answers").mkdir(parents=True, exist_ok=True)
    tasks = judge_free_tasks(include_judged=a.all)[: a.limit]
    # RESUME: skip tasks already scored in results.jsonl — an infra outage mid-probe (search backend
    # bounced under us, drive container removed under us: both happened) shouldn't force redoing the
    # tasks that ran against healthy infra. Delete the row from results.jsonl to force a rerun.
    done = set()
    rf = out / "results.jsonl"
    if rf.exists():
        done = {json.loads(l)["id"] for l in rf.open() if l.strip()}
        tasks = [t for t in tasks if t["id"] not in done]
    print(f"{len(tasks)} judge-free EN tasks (smallest first)"
          + (f" — resuming, {len(done)} already scored" if done else ""))
    # SUBSTRATE PRECHECK: refuse to start while the search backend can't serve — a probe against dead
    # search measures nothing (it already burned 13 tasks at 1s each once).
    import urllib.request as _rq
    sx = os.environ.get("CODEZAIKU_SEARXNG", "http://localhost:8888")
    try:
        # 45s: the instance's own outgoing.max_request_timeout is 15s, and a cold meta-search fans out
        # to every engine — a healthy backend measured 10.03s and lost a 10s precheck by 26ms.
        with _rq.urlopen(f"{sx}/search?q=probe&format=json", timeout=45) as r:
            json.loads(r.read())
    except Exception as e:
        sys.exit(f"ABORT before start: search backend {sx} not serving JSON ({e}). Fix it, then rerun.")
    cp = a.cp or subprocess.run(["./gradlew", ":core:printCp", "-q", "--no-daemon", "--console=plain"],
                                capture_output=True, text=True).stdout.strip().splitlines()[-1]
    work = out / "work"
    work.mkdir(exist_ok=True)

    results = []
    for t in tasks:
        qf = work / f"{t['id']}.txt"
        qf.write_text(t["query"], encoding="utf-8")
        env = dict(os.environ)
        env["CODEZAIKU_RESEARCH_POOL"] = str(work / f"pool-{t['id']}.jsonl")
        pathlib.Path(env["CODEZAIKU_RESEARCH_POOL"]).unlink(missing_ok=True)
        t0 = time.time()
        p = subprocess.run(["timeout", "-k", "30", str(a.timeout), "java", "-cp", cp,
                            "org.codezaiku.FamiliarMain", "research", "@" + str(qf), a.mode,
                            a.drive, str(a.turns)],
                           cwd=work, env=env, capture_output=True, text=True)
        body = p.stdout.split("=== RESEARCH ===", 1)[1] if "=== RESEARCH ===" in p.stdout else ""
        cut = re.search(r"^\d\d:\d\d:\d\d\s+(INFO|WARN|ERROR)\b", body, re.MULTILINE)
        body = (body[:cut.start()] if cut else body).strip()
        pred_rows = parse_table(body)
        s = score(pred_rows, t["gold_rows"], t["eval"])
        secs = round(time.time() - t0, 1)
        # Full run log per task — a zero-score is diagnosable from disk, not by re-deriving from code.
        (out / "answers" / f"{t['id']}.log").write_text(p.stdout + "\n--- STDERR ---\n" + p.stderr,
                                                        encoding="utf-8")
        (out / "answers" / f"{t['id']}.txt").write_text(
            f"TASK: {t['id']} ({t['cells']} gold cells)\nSCORE: {json.dumps(s)}\nSECS: {secs}\n\n"
            f"QUERY:\n{t['query']}\n\nANSWER:\n{body}\n", encoding="utf-8")
        results.append({"id": t["id"], **s, "secs": secs, "rc": p.returncode})
        print(f"[{t['id']}] f1={s['f1']:.2f} rows {s['rows_matched']}/{s['gold_rows']} "
              f"(pred {s['pred_rows']}) cells {s['tp']}/{s['gold_cells']}  {secs}s", flush=True)
        (out / "results.jsonl").open("a", encoding="utf-8").write(json.dumps(results[-1]) + "\n")

    if results:
        mean_f1 = sum(r["f1"] for r in results) / len(results)
        tables = sum(1 for r in results if r["pred_rows"] > 0)
        print(f"\n=== PROBE: mean item-F1 {mean_f1:.3f} over {len(results)} tasks | "
              f"{tables}/{len(results)} produced a table ===")
        print(f"READ {out/'answers'} before any verdict — the F1 is a proxy.")


if __name__ == "__main__":
    main()
