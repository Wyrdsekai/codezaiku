#!/usr/bin/env python3
"""REAL benchmark run: CodeZaiku's ops loop on OpenRCA v1 — THEIR dataset, THEIR scorer, THEIR leaderboard.

We do NOT score this ourselves. We produce a prediction CSV and hand it to their offline scorer:
    python -m main.evaluate -p preds.csv -q dataset/<SYSTEM>/query.csv -r report.csv
Strict Accuracy (score == 1.0) is the leaderboard's "correct" column.

Published v1 leaderboard (n=335): Claude 3.5 Sonnet 11.34% strict (best), GPT-4o 8.96%,
Llama 3.1 (open model) 3.28%  <-- the honest reference point for a local 9B.

## THE DATA-LEAK JAIL (load-bearing — do not weaken)
`query.csv` carries a `scoring_points` column (literally the answer key) and `record.csv` is the ground
truth, and BOTH live in the same dataset tree. A shell agent with free rein will find them. So the agent
runs in a container with ONLY `telemetry/` bind-mounted READ-ONLY at /data/telemetry. Nothing else from the
dataset enters the container. Their FAQ makes this a submission requirement.

## Output contract (their scorer is unforgiving — verified empirically)
  {"1": {"root cause occurrence datetime": "%Y-%m-%d %H:%M:%S",
         "root cause component": "<verbatim from candidate list>",
         "root cause reason":    "<verbatim from candidate list>"}}
  - key ORDER is positional (datetime -> component -> reason). Reordering scores 0.0.
  - the number of failures must match the ground truth. A wrong count scores 0.0.
  - component/reason must be VERBATIM from their candidate lists (case-sensitive).
  - timestamps are UTC+8.
"""
import argparse
import csv
import json
import os
import re
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))


def sh(cmd, **kw):
    return subprocess.run(cmd, shell=True, text=True, capture_output=True, **kw)


def log(m):
    print(m, flush=True)


def candidates(openrca_repo, system):
    """THEIR candidate lists (reasons + components), injected verbatim — exact-match cannot fire without them.

    They keep ONE prompt file per system, not per dataset: `Market/cloudbed-1` and `Market/cloudbed-2` both
    read `basic_prompt_Market.py`. Passing the dataset path straight through built `basic_prompt_Market/
    cloudbed-1.py`, which threw before a single query ran and silently cost both Market systems (148 of the
    335 queries) in the first full-benchmark attempt.
    """
    p = os.path.join(openrca_repo, "rca/baseline/rca_agent/prompt",
                     f"basic_prompt_{system.split('/')[0]}.py")
    src = open(p).read()
    m = re.search(r'cand\s*=\s*"""(.*?)"""', src, re.S)
    return m.group(1).strip() if m else ""


def build_incident(instruction, cand):
    return (
        "You are diagnosing a failure in a production system from its TELEMETRY.\n\n"
        f"TASK:\n{instruction.strip()}\n\n"
        "The telemetry is on this box under /data/telemetry/<YYYY_MM_DD>/{metric,trace,log}/ as CSV files.\n"
        "Analyse it with python3 (pandas and duckdb are installed). All timestamps in the data are UTC+8.\n\n"
        f"{cand}\n\n"
        "When you have identified the root cause, call conclude and put your FINAL ANSWER in the `answer` "
        "field as a JSON object with EXACTLY this shape and key order, one entry per failure, in "
        "chronological order:\n"
        '{"1": {"root cause occurrence datetime": "YYYY-MM-DD HH:MM:SS", '
        '"root cause component": "<one value copied verbatim from the component list above>", '
        '"root cause reason": "<one value copied verbatim from the reason list above>"}}\n'
        "Use the exact strings from the lists above. Report exactly as many failures as the task states."
    )


def extract_answer(res):
    """Prefer the structured `answer` field; fall back to any JSON object in the RCA text."""
    a = (res.get("answer") or "").strip()
    if a:
        return a
    blob = (res.get("root_cause") or "") + " " + " ".join(res.get("evidence") or [])
    m = re.search(r"\{.*\}", blob, re.S)
    return m.group(0) if m else ""


def run_query(idx, instruction, cand, telem_dir, args):
    cname = f"openrca-{idx}"
    sh(f"docker rm -f {cname}")
    # THE JAIL: only telemetry/, read-only. query.csv / record.csv never enter the container.
    r = sh(f"docker run -d --name {cname} -v {telem_dir}:/data/telemetry:ro cpops/openrca-box")
    if r.returncode != 0:
        log(f"  [q{idx}] docker run failed: {r.stderr[:200]}")
        return ""
    cid = r.stdout.strip()
    try:
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as tf:
            tf.write(build_incident(instruction, cand))
            inc = tf.name
        out = os.path.join(tempfile.gettempdir(), f"{cname}.json")
        if os.path.exists(out):
            os.remove(out)
        env = dict(os.environ)
        env["CODEZAIKU_OPS_PRECHECK"] = "off"   # infra prechecks are irrelevant to telemetry RCA
        env["CODEZAIKU_OPS_RUNBOOK"] = "off"    # our runbooks are infra runbooks
        env["CODEZAIKU_OPS_REQUIRE_ANSWER"] = "on"
        # PERSISTENT SESSION ON — PARITY, not an advantage. OpenRCA's own reference agent (the one that
        # scores 11.34% with Claude 3.5 Sonnet) runs model-written python inside a persistent IPython kernel
        # (InteractiveShellEmbed). Without one, our agent re-reads a 2.5GB CSV on EVERY turn, so the run
        # measures our missing kernel rather than the model's ability to diagnose.
        #
        # It was previously OFF on the strength of an A/B that is now void: BOTH arms were crashing on
        # context overflow (OpsLoop had no compaction), and the "baseline wins" reading came from a single
        # lucky run — the baseline re-ran at 3.92%, the same as the session arm. Never trust an A/B whose
        # arms are both broken.
        env["CODEZAIKU_OPS_PYTHON_SESSION"] = "on"
        cmd = (f'./gradlew -q :core:run --args="ops docker://{cid} @{inc} '
               f'{args.base_url} {args.max_iter} {out}"')
        t0 = time.time()
        p = subprocess.run(cmd, shell=True, text=True, cwd=args.repo, env=env,
                           capture_output=True, timeout=args.timeout)
        if not os.path.exists(out):
            # A CRASHING HARNESS AND A STUMPED MODEL BOTH LOOK LIKE "no answer" in the prediction CSV, and
            # that ambiguity cost us a whole benchmark: the loop had no compaction, so it 400'd on context
            # overflow and died, and 129/136 Bank queries were scored as wrong diagnoses when in truth the
            # model was never asked. A missing answer must now say WHY, out loud.
            why = (p.stderr or "").strip().splitlines()
            why = next((l for l in why if "Exception" in l or "Error" in l), why[-1] if why else "")
            log(f"  [q{idx}] NO RESULT ({time.time()-t0:.0f}s, rc={p.returncode}) {why[:180]}")
            return ""
        ans = extract_answer(json.load(open(out)))
        log(f"  [q{idx}] ({time.time()-t0:.0f}s) {ans[:110]}")
        return ans
    except subprocess.TimeoutExpired:
        log(f"  [q{idx}] TIMEOUT")
        return ""
    finally:
        sh(f"docker rm -f {cname}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--openrca-repo", default=""${CP_WORK:-/opt/codezaiku}"/openrca")
    ap.add_argument("--data", default=""${CP_WORK:-/opt/codezaiku}"/openrca-data")
    ap.add_argument("--system", default="Telecom")
    ap.add_argument("--limit", type=int, default=0, help="0 = all queries")
    ap.add_argument("--base-url", default="http://localhost:8200")
    ap.add_argument("--repo", default=""${CP_WORK:-/opt/codezaiku}"/cp-ops")
    ap.add_argument("--max-iter", type=int, default=25)
    ap.add_argument("--timeout", type=int, default=1500)
    ap.add_argument("--out", default=""${CP_WORK:-/opt/codezaiku}"/openrca-preds.csv")
    args = ap.parse_args()

    sysdir = os.path.join(args.data, args.system)
    telem = os.path.join(sysdir, "telemetry")
    qcsv = os.path.join(sysdir, "query.csv")
    assert os.path.isdir(telem), f"no telemetry at {telem}"

    b = sh(f"docker build -q -t cpops/openrca-box {os.path.join(HERE)}")
    if b.returncode != 0:
        log(b.stdout + b.stderr)
        raise SystemExit("jail image build failed")

    cand = candidates(args.openrca_repo, args.system)
    assert cand, "could not read their candidate lists"

    with open(qcsv) as f:
        rows = list(csv.DictReader(f))
    if args.limit:
        rows = rows[: args.limit]
    log(f"[openrca] system={args.system}  queries={len(rows)}  JAIL=telemetry-only (read-only)\n")

    preds = []
    for i, row in enumerate(rows):
        preds.append(run_query(i, row["instruction"], cand, telem, args))

    # prediction CSV: exactly len(query.csv) rows, IN ORDER (their scorer aligns by row order).
    # An EMPTY cell is read back by pandas as NaN and crashes their scorer ("expected string, got float"),
    # so a failed query gets "{}" — which legitimately scores 0 rather than breaking the run.
    with open(args.out, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["prediction"])
        for p in preds:
            w.writerow([p if p.strip() else "{}"])
    answered = sum(1 for p in preds if p.strip())
    log(f"[openrca] {answered}/{len(preds)} queries produced an answer "
        f"({len(preds)-answered} scored 0 for producing none)")
    log(f"\n[openrca] wrote {len(preds)} predictions -> {args.out}")
    log(f"[openrca] now score with THEIR scorer:\n"
        f"  cd {args.openrca_repo} && python -m main.evaluate -p {args.out} -q {qcsv} -r report.csv")


if __name__ == "__main__":
    main()
