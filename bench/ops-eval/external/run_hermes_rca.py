#!/usr/bin/env python3
"""EXTERNAL VALIDATION: run CodeZaiku's ops loop against opensre's hermes_rca fault library.

Faults: theirs. Oracle: theirs. Domain: an LLM gateway we have never seen. See README.md for the fairness
decisions (we strip the answer-leaking `failure_mode` from the alert, and we supply their category label
set — their own agent knows it; ours does not).

Each scenario becomes a container holding the evidence artifacts under /evidence. The incident is the alert
(de-leaked). Our ops loop investigates by reading those files, concludes with a root_cause_category, and is
scored against THEIR answer.yml.

Usage:
  run_hermes_rca.py --scenarios <opensre>/tests/synthetic/hermes_rca [-k K] [--only 001,002] [--repo DIR]
"""
import argparse
import copy
import json
import os
import subprocess
import sys
import tempfile

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
SCORING = os.path.join(HERE, "..", "scoring.py")


def sh(cmd, **kw):
    return subprocess.run(cmd, shell=True, text=True, capture_output=True, **kw)


def log(m):
    print(m, flush=True)


def taxonomy(scen_root):
    """THEIR root-cause label set — supplied to the model, as any classification task must supply labels."""
    cats = set()
    for d in sorted(os.listdir(scen_root)):
        a = os.path.join(scen_root, d, "answer.yml")
        if os.path.isfile(a):
            with open(a) as f:
                ans = yaml.safe_load(f) or {}
            c = ans.get("root_cause_category")
            if c:
                cats.add(str(c).strip().strip('"'))
    return sorted(cats)


def build_incident(sdir, cats):
    """The alert an operator would actually see — with the leaked ground-truth label removed."""
    with open(os.path.join(sdir, "alert.json")) as f:
        alert = json.load(f)
    alert = copy.deepcopy(alert)
    ann = alert.get("commonAnnotations", {})
    ann.pop("failure_mode", None)          # <-- the scenario's OWN answer; never present in a real alert
    evidence = [f for f in sorted(os.listdir(sdir)) if f.endswith(".json") and f != "alert.json"]
    return (
        "ALERT:\n" + json.dumps(alert, indent=2) + "\n\n"
        "The evidence artifacts for this incident are files on this box, in /evidence:\n"
        + "".join(f"  - /evidence/{e}\n" for e in evidence) +
        "\nRead the evidence and determine the ROOT CAUSE of this alert.\n"
        "Report root_cause_category as EXACTLY ONE of these values:\n"
        + "".join(f"  - {c}\n" for c in cats) +
        "\nIf the evidence shows the system is actually healthy and no failure occurred, "
        "report root_cause_category \"healthy\"."
    )


def run_one(sdir, sid, cats, args):
    cname = f"cpops-hermes-{sid}"
    sh(f"docker rm -f {cname}")
    r = sh(f"docker run -d --name {cname} cpops/hermes-evidence")
    if r.returncode != 0:
        log(r.stdout + r.stderr)
        return {"pass": False, "diagnostics": {"error": "docker run failed"}}
    cid = r.stdout.strip()
    try:
        sh(f"docker exec {cid} mkdir -p /evidence")
        for f in os.listdir(sdir):
            if f.endswith(".json") and f != "alert.json":
                sh(f"docker cp {os.path.join(sdir, f)} {cid}:/evidence/{f}")

        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as tf:
            tf.write(build_incident(sdir, cats))
            inc = tf.name
        out = os.path.join(tempfile.gettempdir(), f"{cname}-result.json")
        if os.path.exists(out):
            os.remove(out)

        env = dict(os.environ)
        env["CODEZAIKU_OPS_PRECHECK"] = "off"   # our prechecks detect INFRA faults; irrelevant + noisy here
        env["CODEZAIKU_OPS_RUNBOOK"] = "off"    # our runbooks are infra runbooks; this domain is unseen
        cmd = f'./gradlew -q :core:run --args="ops docker://{cid} @{inc} {args.base_url} {args.max_iter} {out}"'
        proc = subprocess.run(cmd, shell=True, text=True, cwd=args.repo, env=env,
                              capture_output=True, timeout=args.timeout)
        if not os.path.exists(out):
            log(f"  [{sid}] no result. stderr: " + "\n".join(proc.stderr.splitlines()[-3:]))
            return {"pass": False, "diagnostics": {"error": "no result"}}

        sc = sh(f"{sys.executable} {SCORING} {os.path.join(sdir, 'answer.yml')} {out}")
        try:
            v = json.loads(sc.stdout)
        except json.JSONDecodeError:
            log(f"  [{sid}] scoring failed: {sc.stdout}{sc.stderr}")
            return {"pass": False, "diagnostics": {"error": "scoring failed"}}
        with open(out) as f:
            v["_result"] = json.load(f)
        return v
    finally:
        sh(f"docker rm -f {cname}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenarios", required=True, help="path to opensre tests/synthetic/hermes_rca")
    ap.add_argument("--only", default="", help="comma-separated scenario id prefixes (e.g. 000,001)")
    ap.add_argument("--base-url", default="http://localhost:8200")
    ap.add_argument("--repo", default=os.environ.get("CODEZAIKU_REPO", ""${CP_WORK:-/opt/codezaiku}"/cp-ops"))
    ap.add_argument("--max-iter", type=int, default=25)
    ap.add_argument("--timeout", type=int, default=900)
    args = ap.parse_args()

    root = os.path.abspath(args.scenarios)
    cats = taxonomy(root)
    log(f"[taxonomy] {len(cats)} root-cause categories (THEIRS): {', '.join(cats)}\n")

    b = sh(f"docker build -q -t cpops/hermes-evidence {os.path.join(HERE, 'evidence-box')}")
    if b.returncode != 0:
        log(b.stdout + b.stderr)
        raise SystemExit("evidence-box build failed")

    only = [s.strip() for s in args.only.split(",") if s.strip()]
    scen = [d for d in sorted(os.listdir(root))
            if os.path.isdir(os.path.join(root, d)) and d[0].isdigit()
            and (not only or any(d.startswith(o) for o in only))]

    results = []
    for d in scen:
        sdir = os.path.join(root, d)
        if not os.path.isfile(os.path.join(sdir, "alert.json")):
            continue
        v = run_one(sdir, d, cats, args)
        exp = str((yaml.safe_load(open(os.path.join(sdir, "answer.yml"))) or {})
                  .get("root_cause_category", "")).strip().strip('"')
        got = str((v.get("_result") or {}).get("root_cause_category", "?")).strip()
        cat_ok = (exp.lower() == got.lower())
        results.append((d, v["pass"], cat_ok, exp, got))
        log(f"  cat={'OK  ' if cat_ok else 'MISS'}  {d}\n        expected={exp}  got={got}")

    ncat = sum(1 for _, _, c, _, _ in results if c)
    nall = sum(1 for _, p, _, _, _ in results if p)
    total = len(results)
    log("\n" + "=" * 72)
    log("EXTERNAL VALIDATION — opensre hermes_rca (faults + labels NOT ours, unseen domain)")
    log(f"  ROOT-CAUSE CATEGORY ACCURACY : {ncat}/{total}  ({100*ncat//max(total,1)}%)   "
        f"<-- the headline: their 19-label taxonomy, exact match")
    log(f"  our-full-gate pass rate      : {nall}/{total}  (NOT comparable to their score — see below)")
    log("")
    log("  NOTE ON SCORING. The category number is the defensible one: their scenarios, their labels,")
    log("  exact string match. The 'full-gate' number applies OUR scoring.py (which requires EVERY")
    log("  required_keyword as an exact substring). THEIR scorer is more lenient — it matches keywords")
    log("  semantically via domain-specific alias tables (e.g. 'idle' also matches 'clientread' /")
    log("  'sessions remain open'). Replicating those alias tables faithfully is out of scope, so the")
    log("  full-gate number UNDERSTATES performance and must NOT be reported as their benchmark score.")
    log("=" * 72)
    misses = [(d, e, g) for d, _, c, e, g in results if not c]
    if misses:
        log("\n  category misses:")
        for d, e, g in misses:
            log(f"    {d}: expected {e}, got {g}")


if __name__ == "__main__":
    main()
