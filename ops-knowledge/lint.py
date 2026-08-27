#!/usr/bin/env python3
"""Pre-push lint for ops knowledge cards — the FOUR-AXIS bar, mechanized.

Every time "the library didn't help", the cause was a card below bar on one of four axes (wrong info /
wrong scope / verbose / never pushed) — and a below-bar card doesn't just underperform, it VOIDS the
measurement built on it (a 1258-char correct card dropped the model's submit rate 43%->0%). So the bar is
enforced, not remembered. OpsKnowledge.java applies the same rules at load time (defense in depth: a card
that dodges the hook still cannot reach a prompt). This lint and the Java loader parse headers IDENTICALLY
so their verdicts agree.

Card format:
  match: kw[, kw...]            (required, first line)   — stack keywords that trigger the card
  signature: kw[, kw...]        (optional)               — log strings that evidence the fault class
  push: immediate | rescue      (optional, default rescue)
  status: validated | candidate (optional, default candidate)  — PROVENANCE, never rendered to the model
  platform: kubernetes|docker|systemd|macos[, ...]  (optional, default: runs anywhere)
                                — where the PROCEDURE can run. Distinct from match: (which fault) and
                                  status: (whether an A/B confirmed it). The validated tier was earned on
                                  AIOpsLab = Kubernetes, and 5 of 7 validated cards carried kubectl-only
                                  steps while matching on product keywords, so they fired on docker stacks
                                  and handed the model a procedure with no kubectl to run it.
  # <one heading>               (body starts here)
  ...procedure... conclude...

Mechanical axes (checked here, on the BODY only — headers are stripped first):
  PUSHED : first line `match:` with >=1 keyword — a card without a live trigger is silently inert.
  TERSE  : body <= 900 chars and <= 14 lines — the measured-harmful card was 1258 chars.
  STOP   : body must contain "conclude" — else the model investigates forever (submit 43%->0%).
  SCOPED : exactly one `# ` heading — one fault-domain per card.
Plus two safety checks: push/status values valid, and a 'candidate' card must be push:rescue (an
unvalidated immediate push can DESTROY fault classes the model already solves — measured 13/20->0/20).

The fourth axis, CORRECT, is not lintable: only a controlled measurement validates content (ORPO 0.27->1.0);
that is exactly what 'status: validated' records and 'candidate' withholds.
Exit 0 = all cards pass; exit 1 = violations printed.
"""
import glob, os, sys

MAX_CHARS, MAX_LINES = 900, 14

def parse(text):
    """-> (match_ok, push, status, body). Mirrors OpsKnowledge.cards() header handling exactly."""
    lines = text.split("\n")
    match_ok = bool(lines) and lines[0].lower().startswith("match:") and any(k.strip() for k in lines[0][6:].split(","))
    push, status = "rescue", "candidate"
    i = 1
    while i < len(lines):
        low = lines[i].lower()
        if low.startswith("signature:"):
            i += 1
        elif low.startswith("push:"):
            push = lines[i][5:].strip().lower(); i += 1
        elif low.startswith("platform:"):
            for pf in (k.strip().lower() for k in lines[i][9:].split(",")):
                if pf and pf not in ("kubernetes", "docker", "systemd", "macos"):
                    v.append(f"PLATFORM: '{pf}' — must be one of kubernetes, docker, systemd, macos "
                             "(a typo would silently widen the card to every target)")
            i += 1
        elif low.startswith("status:"):
            status = lines[i][7:].strip().lower(); i += 1
        else:
            break
    return match_ok, push, status, "\n".join(lines[i:]).strip()

def lint(path):
    v = []
    match_ok, push, status, body = parse(open(path, encoding="utf-8").read())
    if not match_ok:
        v.append("PUSHED: first line must be 'match: kw[, kw...]' with >=1 keyword (else the card is inert)")
    if push not in ("immediate", "rescue"):
        v.append(f"PUSH-POLICY: '{push}' — must be 'immediate' or 'rescue' (a typo silently becomes rescue)")
    if status not in ("validated", "candidate"):
        v.append(f"STATUS: '{status}' — must be 'validated' or 'candidate'")
    if status == "candidate" and push == "immediate":
        v.append("SAFETY: a 'candidate' (unvalidated) card must be push:rescue — an unproven immediate push "
                 "can DESTROY fault classes the model already solves (13/20->0/20); prove it, then promote")
    if len(body) > MAX_CHARS:
        v.append(f"TERSE: body {len(body)} chars > {MAX_CHARS} (verbose cards kill convergence: 43%->0% submit)")
    if len(body.splitlines()) > MAX_LINES:
        v.append(f"TERSE: {len(body.splitlines())} lines > {MAX_LINES}")
    if "conclude" not in body.lower():
        v.append("STOP: body must contain 'conclude' — a card that opens an investigation must close it")
    if sum(1 for l in body.splitlines() if l.startswith("# ")) != 1:
        v.append("SCOPED: exactly one '# ' heading — one fault-domain per card")
    return v, status

if __name__ == "__main__":
    root = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
    bad = 0; nval = 0; ncand = 0
    cards = sorted(glob.glob(os.path.join(root, "*.md")))
    for p in cards:
        vs, status = lint(p)
        if vs:
            bad += 1
            print(f"REJECT {os.path.basename(p)}")
            for x in vs: print(f"  - {x}")
        else:
            nval += status == "validated"; ncand += status == "candidate"
            print(f"ok     [{status:9s}] {os.path.basename(p)}")
    print(f"\n{len(cards)-bad}/{len(cards)} cards pass  ({nval} validated, {ncand} candidate).")
    print("(CORRECTNESS is not lintable — only a controlled A/B promotes candidate -> validated.)")
    sys.exit(1 if bad else 0)
