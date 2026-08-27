#!/usr/bin/env python3
"""Multi-service (docker-compose) diagnosis eval.

Brings a real compose stack up GREEN, injects a fault into ONE service, then points CodeZaiku's ops loop
(LOCAL exec on the host, CODEZAIKU_OPS_COMPOSE_PROJECT set) at the stack and scores the diagnosis. Tests
the multi-service dimensions single-box scenarios can't: branch localization across a DAG, health-status
as the localizer, and the HEALTHY case (does it stay quiet when nothing is wrong?).

Usage:
  run_compose.py <scenario_dir> [-k K] [--stack DIR] [--base-url URL] [--repo DIR] [--max-iter N]

scenario_dir/scenario.yml:
  incident: <text>
  fault: "<shell, {proj} templated>"   # empty/absent = healthy scenario
  fault_timing: after|before            # after (default) = inject once the stack is green
  settle: <seconds to wait after fault for health to propagate>  (default 25)
scenario_dir/answer.yml: the scoring oracle (same format as the single-box eval).
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


def log(m):
    print(m, flush=True)


def compose(proj, stack, *args):
    return sh(f"docker compose -p {proj} -f {stack} " + " ".join(args))


def run_once(scenario_dir, scenario, stack, proj, args):
    sh(f"docker compose -p {proj} -f {stack} down -v --remove-orphans")
    fault = (scenario.get("fault") or "").strip()
    timing = scenario.get("fault_timing", "after")
    settle = int(scenario.get("settle", 25))
    try:
        if fault and timing == "before":
            # Bring up without waiting, then inject before health settles.
            compose(proj, stack, "up", "-d")
            time.sleep(3)
            sh(fault.format(proj=proj, stack=stack))
            time.sleep(settle)
        else:
            up = compose(proj, stack, "up", "-d", "--wait", "--wait-timeout", "150")
            if up.returncode != 0:
                log("[up] stack did NOT become healthy at start:\n" + up.stderr[-500:])
                # continue anyway — some scenarios are inherently unhealthy
            if fault:
                log(f"[inject] {fault.format(proj=proj, stack=stack)}")
                sh(fault.format(proj=proj, stack=stack))
                time.sleep(settle)

        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as tf:
            tf.write(scenario["incident"].strip())
            incident_file = tf.name
        result_path = os.path.join(tempfile.gettempdir(), f"{proj}-result.json")
        if os.path.exists(result_path):
            os.remove(result_path)

        env = dict(os.environ)
        env["CODEZAIKU_OPS_PRECHECK"] = "on"
        env["CODEZAIKU_OPS_RUNBOOK"] = "auto"
        env["CODEZAIKU_OPS_COMPOSE_PROJECT"] = proj
        if args.remediate:
            env["CODEZAIKU_OPS_REMEDIATE"] = "on"
            rc = scenario.get("resolved_check")
            if rc:
                env["CODEZAIKU_OPS_VERIFY_CMD"] = rc
        gradle_args = f'ops local @{incident_file} {args.base_url} {args.max_iter} {result_path}'
        cmd = f'./gradlew -q :core:run --args="{gradle_args}"'
        log(f"[run] {proj}  ({cmd})")
        t0 = time.time()
        proc = subprocess.run(cmd, shell=True, text=True, cwd=args.repo, env=env,
                              capture_output=True, timeout=args.timeout)
        log(f"[loop {time.time()-t0:.0f}s] " + "\n".join(proc.stdout.splitlines()[-6:]))

        if not os.path.exists(result_path):
            log("[no result] stderr tail:\n" + "\n".join(proc.stderr.splitlines()[-8:]))
            return {"pass": False, "diagnostics": {"error": "no result file"}}
        sc = sh(f"{sys.executable} {os.path.join(HERE, 'scoring.py')} "
                f"{os.path.join(scenario_dir, 'answer.yml')} {result_path}")
        try:
            verdict = json.loads(sc.stdout)
        except json.JSONDecodeError:
            log("scoring failed:\n" + sc.stdout + sc.stderr)
            verdict = {"pass": False, "diagnostics": {"error": "scoring failed"}}
        with open(result_path) as f:
            verdict["_result"] = json.load(f)
        rem = verdict["_result"].get("remediation")
        if rem is not None:
            verdict["remediated"] = rem.get("harness_verified") is True
        return verdict
    finally:
        sh(f"docker compose -p {proj} -f {stack} down -v --remove-orphans")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("scenario_dir")
    ap.add_argument("-k", type=int, default=3)
    ap.add_argument("--stack", default=os.path.join(HERE, "stacks/shop/docker-compose.yml"))
    ap.add_argument("--base-url", default="http://localhost:8200")
    ap.add_argument("--repo", default=os.environ.get("CODEZAIKU_REPO", ""${CP_WORK:-/opt/codezaiku}"/cp-ops"))
    ap.add_argument("--max-iter", type=int, default=40)
    ap.add_argument("--remediate", action="store_true",
                    help="also run the gated remediation phase; harness re-verifies resolved_check itself")
    ap.add_argument("--timeout", type=int, default=1200)
    args = ap.parse_args()

    scenario_dir = os.path.abspath(args.scenario_dir)
    with open(os.path.join(scenario_dir, "scenario.yml")) as f:
        scenario = yaml.safe_load(f)
    sid = scenario["id"]

    log(f"[build] stack images from {args.stack} …")
    b = sh(f"docker compose -f {args.stack} build")
    if b.returncode != 0:
        log(b.stdout[-800:] + b.stderr[-800:])
        raise SystemExit("stack build failed")

    results = []
    for rep in range(1, args.k + 1):
        proj = f"shop{rep}"  # SHORT on purpose: long container names caused the 9B to mis-copy the identifier
        v = run_once(scenario_dir, scenario, args.stack, proj, args)
        results.append(v)
        log(f"[rep{rep}] {'PASS' if v['pass'] else 'FAIL'}  "
            f"cat={(v.get('_result') or {}).get('root_cause_category','?')}  diag={v.get('diagnostics')}\n")

    npass = sum(1 for v in results if v["pass"])
    nrem = sum(1 for v in results if v.get("remediated"))
    remtxt = f"  |  remediated {nrem}/{args.k}" if args.remediate else ""
    log("=" * 60)
    log(f"COMPOSE SCENARIO {sid}  →  diagnosis {npass}/{args.k} PASS{remtxt}")
    for i, v in enumerate(results, 1):
        rc = (v.get("_result") or {}).get("root_cause_category", "?")
        rtxt = f"  remediated={v.get('remediated')}" if args.remediate else ""
        log(f"  rep{i}: {'PASS' if v['pass'] else 'FAIL'}  category={rc}{rtxt}")
    log("=" * 60)
    sys.exit(0 if npass == args.k else 1)


if __name__ == "__main__":
    main()
