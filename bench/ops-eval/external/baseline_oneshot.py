#!/usr/bin/env python3
"""CONTROL for the external hermes_rca result: the SAME 9B, the SAME scenarios, the SAME information —
but with NO ops loop.

There is no published score on this suite (opensre's README: "No benchmark results yet"), so the only
meaningful yardstick is what the harness itself contributes. This baseline hands the model everything in ONE
shot — the de-leaked alert, the FULL text of every evidence artifact, and the 19-category label set — and
asks for a root_cause_category. No tools, no investigation, no nudges, no gates.

If the agentic loop is worth anything, it must beat this. Note the baseline is if anything ADVANTAGED: it is
handed all the evidence for free, whereas the loop must decide what to read.

Usage: baseline_oneshot.py --scenarios <hermes_rca> [--base-url URL]
"""
import argparse
import copy
import json
import os
import re
import urllib.request

import yaml


def log(m):
    print(m, flush=True)


def taxonomy(root):
    cats = set()
    for d in sorted(os.listdir(root)):
        a = os.path.join(root, d, "answer.yml")
        if os.path.isfile(a):
            c = (yaml.safe_load(open(a)) or {}).get("root_cause_category")
            if c:
                cats.add(str(c).strip().strip('"'))
    return sorted(cats)


def build_prompt(sdir, cats):
    alert = copy.deepcopy(json.load(open(os.path.join(sdir, "alert.json"))))
    alert.get("commonAnnotations", {}).pop("failure_mode", None)   # same de-leak as the loop run
    parts = ["ALERT:\n" + json.dumps(alert, indent=2), "\nEVIDENCE:"]
    for f in sorted(os.listdir(sdir)):
        if f.endswith(".json") and f != "alert.json":
            parts.append(f"\n--- {f} ---\n" + open(os.path.join(sdir, f)).read())
    parts.append(
        "\n\nDetermine the ROOT CAUSE of this alert from the evidence above.\n"
        "Answer with EXACTLY ONE of these categories:\n"
        + "".join(f"  - {c}\n" for c in cats)
        + "\nRespond with only this line:\nROOT_CAUSE_CATEGORY: <category>"
    )
    return "\n".join(parts)


def ask(base_url, prompt):
    body = json.dumps({
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": 1200, "temperature": 0.7, "stream": False,
    }).encode()
    req = urllib.request.Request(base_url.rstrip("/") + "/v1/chat/completions",
                                 data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=180) as r:
        d = json.load(r)
    m = d["choices"][0]["message"]
    return (m.get("content") or "") + "\n" + (m.get("reasoning_content") or "")


def parse_cat(text, cats):
    m = re.search(r"ROOT_CAUSE_CATEGORY:\s*([a-zA-Z_\"']+)", text)
    if m:
        c = m.group(1).strip().strip('"\'').lower()
        for k in cats:
            if k.lower() == c:
                return k
    # fall back: last category name mentioned anywhere in the answer
    hits = [(text.lower().rfind(k.lower()), k) for k in cats if k.lower() in text.lower()]
    return max(hits)[1] if hits else "?"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenarios", required=True)
    ap.add_argument("--base-url", default="http://localhost:8200")
    args = ap.parse_args()

    root = os.path.abspath(args.scenarios)
    cats = taxonomy(root)
    scen = [d for d in sorted(os.listdir(root))
            if os.path.isdir(os.path.join(root, d)) and d[0].isdigit()
            and os.path.isfile(os.path.join(root, d, "alert.json"))]

    ok = 0
    for d in scen:
        sdir = os.path.join(root, d)
        exp = str((yaml.safe_load(open(os.path.join(sdir, "answer.yml"))) or {})
                  .get("root_cause_category", "")).strip().strip('"')
        try:
            got = parse_cat(ask(args.base_url, build_prompt(sdir, cats)), cats)
        except Exception as e:
            got = f"ERROR({e})"
        hit = (got.lower() == exp.lower())
        ok += hit
        log(f"  {'OK  ' if hit else 'MISS'}  {d}\n        expected={exp}  got={got}")

    log("\n" + "=" * 70)
    log(f"BASELINE (same 9B, all evidence handed over, NO ops loop) → {ok}/{len(scen)} "
        f"({100*ok//max(len(scen),1)}%) category accuracy")
    log("=" * 70)


if __name__ == "__main__":
    main()
