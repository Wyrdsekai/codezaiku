#!/usr/bin/env python3
"""Diagnosis eval driver (PLAN_CODEZAIKU_OPS.md §7).

For each repetition: build the scenario image, start a fresh container, inject the fault (setup.sh),
run CodeZaiku's ops loop against `docker://<cid>`, capture the InvestigationResult JSON, and score it
with the pure oracle. Prints a PASS/FAIL table and an N/K rate — the first honest measurement of the
9B on DIAGNOSIS (RCA matches the true cause AND resists the red herring), which we've never had.

Usage:
  run_scenario.py <fixture_dir> [-k K] [--base-url URL] [--precheck on|off] [--repo DIR] [--max-iter N]

Runs on a box with Docker + the CodeZaiku repo + java/gradle + the 9B drive reachable (${CP_HOST}).
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile
import time

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))


def sh(cmd, **kw):
    return subprocess.run(cmd, shell=True, text=True, capture_output=True, **kw)


def log(msg):
    print(msg, flush=True)


def build_image(fixture_dir, image_tag):
    log(f"[build] {image_tag} …")
    r = sh(f"docker build -q -t {image_tag} {fixture_dir}")
    if r.returncode != 0:
        log(r.stdout + r.stderr)
        raise SystemExit(f"docker build failed for {fixture_dir}")


def run_once(fixture_dir, scenario, image_tag, rep, args):
    cname = f"cpops-{scenario['id']}-{rep}"
    sh(f"docker rm -f {cname}")
    # Start the container (baseline healthy image; fault injected next). run_args lets a scenario request
    # e.g. a bounded tmpfs to reproduce a disk-full fault without touching the host disk.
    run_args = scenario.get("run_args", "")
    r = sh(f"docker run -d --name {cname} {run_args} {image_tag}")
    if r.returncode != 0:
        log(r.stdout + r.stderr)
        raise SystemExit("docker run failed")
    cid = r.stdout.strip()
    try:
        # Inject the fault.
        setup = scenario.get("setup", "setup.sh")
        sh(f"docker cp {os.path.join(fixture_dir, setup)} {cid}:/tmp/setup.sh")
        inj = sh(f"docker exec {cid} bash /tmp/setup.sh")
        log(f"[inject rep{rep}] {inj.stdout.strip()}")
        # Remove the injection script — it names the fault; the model must NOT be able to read the answer key.
        sh(f"docker exec {cid} rm -f /tmp/setup.sh")

        # Write the incident text for the ops loop to read as its goal.
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as tf:
            tf.write(scenario["incident"].strip())
            incident_file = tf.name
        result_path = os.path.join(tempfile.gettempdir(), f"{cname}-result.json")
        if os.path.exists(result_path):
            os.remove(result_path)

        env = dict(os.environ)
        env["CODEZAIKU_OPS_PRECHECK"] = "on" if args.precheck == "on" else "off"
        env["CODEZAIKU_OPS_RUNBOOK"] = "auto" if args.runbook_auto else ("on" if args.runbook else "off")
        if args.remediate:
            env["CODEZAIKU_OPS_REMEDIATE"] = "on"
            rc = scenario.get("resolved_check")
            if rc:
                env["CODEZAIKU_OPS_VERIFY_CMD"] = rc
        gradle_args = (f'ops docker://{cid} @{incident_file} {args.base_url} '
                       f'{args.max_iter} {result_path}')
        cmd = f'./gradlew -q :core:run --args="{gradle_args}"'
        log(f"[run rep{rep}] precheck={args.precheck}  ({cmd})")
        t0 = time.time()
        proc = subprocess.run(cmd, shell=True, text=True, cwd=args.repo, env=env,
                              capture_output=True, timeout=args.timeout)
        dt = time.time() - t0
        # Surface the loop's own stdout tail for debugging.
        tail = "\n".join(proc.stdout.splitlines()[-8:])
        log(f"[loop rep{rep} {dt:.0f}s]\n{tail}")

        if not os.path.exists(result_path):
            log(f"[rep{rep}] NO RESULT FILE — loop did not conclude/write. stderr tail:")
            log("\n".join(proc.stderr.splitlines()[-8:]))
            return {"pass": False, "gates": {}, "diagnostics": {"error": "no result file"}}

        # Score with the pure oracle.
        answer_path = os.path.join(fixture_dir, "answer.yml")
        sc = sh(f"{sys.executable} {os.path.join(HERE, 'scoring.py')} {answer_path} {result_path}")
        try:
            verdict = json.loads(sc.stdout)
        except json.JSONDecodeError:
            log("scoring failed:\n" + sc.stdout + sc.stderr)
            verdict = {"pass": False, "gates": {}, "diagnostics": {"error": "scoring failed"}}
        with open(result_path) as f:
            verdict["_result"] = json.load(f)
        rem = verdict["_result"].get("remediation")
        if rem is not None:
            verdict["remediated"] = rem.get("harness_verified") is True
        return verdict
    finally:
        sh(f"docker rm -f {cname}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("fixture_dir")
    ap.add_argument("-k", type=int, default=3, help="repetitions (K>=3 for a real verdict)")
    ap.add_argument("--base-url", default="http://localhost:8200")
    ap.add_argument("--precheck", choices=["on", "off"], default="off",
                    help="off = measure the model's RAW diagnosis (default); on = precheck-assisted")
    ap.add_argument("--repo", default=os.environ.get("CODEZAIKU_REPO", ""${CP_WORK:-/opt/codezaiku}"/codezaiku"))
    ap.add_argument("--max-iter", type=int, default=25)
    ap.add_argument("--remediate", action="store_true",
                    help="also run the gated remediation phase and report harness-verified resolution")
    ap.add_argument("--runbook", action="store_true",
                    help="offer the runbook catalog + fetch_runbook tool (PULL)")
    ap.add_argument("--runbook-auto", action="store_true",
                    help="also PUSH the matched procedural runbook into the first turn (for a 9B)")
    ap.add_argument("--timeout", type=int, default=1200)
    args = ap.parse_args()

    fixture_dir = os.path.abspath(args.fixture_dir)
    with open(os.path.join(fixture_dir, "scenario.yml")) as f:
        scenario = yaml.safe_load(f)
    image_tag = f"cpops/{scenario['image']}"
    build_image(fixture_dir, image_tag)

    results = []
    for rep in range(1, args.k + 1):
        v = run_once(fixture_dir, scenario, image_tag, rep, args)
        results.append(v)
        log(f"[rep{rep}] {'PASS' if v['pass'] else 'FAIL'}  gates={v.get('gates')}\n")

    npass = sum(1 for v in results if v["pass"])
    nrem = sum(1 for v in results if v.get("remediated"))
    log("=" * 60)
    remtxt = f"  |  remediated {nrem}/{args.k}" if args.remediate else ""
    log(f"SCENARIO {scenario['id']}  precheck={args.precheck}  →  diagnosis {npass}/{args.k} PASS{remtxt}")
    for i, v in enumerate(results, 1):
        rc = (v.get("_result") or {}).get("root_cause_category", "?")
        rtxt = f"  remediated={v.get('remediated')}" if args.remediate else ""
        log(f"  rep{i}: {'PASS' if v['pass'] else 'FAIL'}  category={rc}{rtxt}  "
            f"diag={v.get('diagnostics')}")
    log("=" * 60)
    sys.exit(0 if npass == args.k else 1)


if __name__ == "__main__":
    main()
