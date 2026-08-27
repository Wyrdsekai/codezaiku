#!/usr/bin/env python3
"""Pure diagnosis oracle (PLAN_CODEZAIKU_OPS.md §7, opensre scoring.py shape).

Gates a CodeZaiku ops `InvestigationResult` (the JSON the loop writes) against a fixture's answer.yml.
No runtime imports beyond stdlib + PyYAML — this is a scorer, it must not touch the harness or the box.

A run PASSES iff every REQUIRED gate passes:
  - category_match        result category == the true category (or an alias)
  - required_keywords     every required keyword appears in the RCA text
  - required_keyword_group at least one keyword from each group appears
  - not forbidden_category result category is not a distractor category
  - not herring_blamed    if the category is WRONG, the RCA must not blame a forbidden distractor
  - trajectory_budget     iterations <= max_investigation_loops
required_evidence_sources is reported (soft) unless CP_OPS_STRICT_EVIDENCE=1.

Usage: scoring.py <answer.yml> <result.json>   → prints a JSON verdict, exits 0 on PASS, 1 on FAIL.
"""
import json
import os
import sys

import yaml


def _norm(s):
    # Coerce non-strings: YAML parses bare tokens like `5432` / `8080` as ints, so keyword lists can hold
    # numbers. str() them before normalizing.
    return ("" if s is None else str(s)).strip().lower()


def _text_blob(result):
    """All the free-text the model produced, for keyword gating."""
    parts = [result.get("root_cause", ""), result.get("root_cause_category", "")]
    for key in ("validated_claims", "evidence", "non_validated_claims"):
        v = result.get(key)
        if isinstance(v, list):
            parts.extend(str(x) for x in v)
        elif v:
            parts.append(str(v))
    return _norm(" \n ".join(parts))


_DISMISS_MARKERS = ("red herring", "herring", "not the cause", "not the root", "is a symptom",
                    "symptom", "unrelated", "distraction", "distractor", "rule out", "ruled out",
                    "not related", "not relevant", "irrelevant", "false alarm", "not responsible")


def _is_dismissed(blob, kw):
    """True if every occurrence of kw sits near a dismissal marker (within ~60 chars) — i.e. the model
    called it out to reject it, not to blame it."""
    idx = blob.find(kw)
    while idx != -1:
        lo, hi = max(0, idx - 60), min(len(blob), idx + len(kw) + 60)
        window = blob[lo:hi]
        if not any(m in window for m in _DISMISS_MARKERS):
            return False  # at least one occurrence is NOT dismissed → it's being blamed
        idx = blob.find(kw, idx + len(kw))
    return True


# "Nothing is wrong" categories. These must NEVER be accepted for a scenario that HAS a fault — a missed
# fault is a false NEGATIVE, the worst possible scoring error.
_HEALTHY_CATS = {"healthy", "no_fault", "no_issue", "nominal", "ok", "healthy_system", "no_problem",
                 "false_alarm", "all_healthy", "no_root_cause", "none"}


def _category_ok(result, answer):
    cat = _norm(result.get("root_cause_category")).replace("-", "_").replace(" ", "_")
    if not cat:
        return False
    targets = [_norm(answer.get("root_cause_category"))]
    targets += [_norm(a) for a in answer.get("category_aliases", [])]
    targets = [t.replace("-", "_").replace(" ", "_") for t in targets if t]

    # HARD GUARD (bug found the hard way): a "healthy" verdict may ONLY pass when the scenario's true answer
    # IS healthy. Naive substring matching accepted "healthy" for a fault scenario because "healthy" is a
    # substring of "unhealthy" (aliases redis_unhealthy / service_unhealthy) — i.e. a MISSED FAULT scored as
    # a PASS. Never again.
    scenario_is_healthy = _norm(answer.get("root_cause_category")) in _HEALTHY_CATS
    if cat in _HEALTHY_CATS:
        return scenario_is_healthy
    if scenario_is_healthy:
        return False   # scenario is healthy but the model named a fault → false positive

    # Otherwise: equal, or the target slug appears in the model's (more specific) slug as a whole token
    # (dependency_down ⊂ redis_dependency_down). The reverse direction (model ⊂ target) is NOT allowed — that
    # is what let a short slug match a longer unrelated one.
    cat_tokens = set(cat.split("_"))
    for t in targets:
        if cat == t:
            return True
        if t in cat and set(t.split("_")).issubset(cat_tokens):
            return True
    return False


def score(answer, result):
    blob = _text_blob(result)
    gates = {}

    gates["category_match"] = _category_ok(result, answer)

    req = [_norm(k) for k in answer.get("required_keywords", [])]
    missing_kw = [k for k in req if k and k not in blob]
    gates["required_keywords"] = not missing_kw

    groups = answer.get("required_keyword_groups", []) or []
    unmet_groups = []
    for g in groups:
        if not any(_norm(k) in blob for k in g):
            unmet_groups.append(g)
    gates["required_keyword_group"] = not unmet_groups

    cat = _norm(result.get("root_cause_category"))
    # accept both spellings: ours ("forbidden_category") and opensre's ("forbidden_categories"), so an
    # EXTERNAL answer.yml can be scored by the same gates without editing their fixture.
    forbidden_cats = [_norm(c) for c in
                      (answer.get("forbidden_category") or answer.get("forbidden_categories") or [])]
    forbidden_category_hit = any(fc and (fc == cat or fc in cat) for fc in forbidden_cats)
    gates["not_forbidden_category"] = not forbidden_category_hit

    # Herring: only a hard fail when the category is WRONG and the RCA blames a distractor. A correct
    # diagnosis that merely *mentions* the herring to DISMISS it ("high CPU is a red herring") must not be
    # penalized — so a forbidden keyword only counts as "blamed" when it appears WITHOUT a nearby dismissal
    # marker.
    # Resisting the herring is judged PRIMARILY by the category: if the model concluded the correct
    # category (and not a forbidden one), it resisted — regardless of what context its prose mentions.
    # A correct diagnosis routinely NAMES the distractor to explain it as an effect ("the traceback was
    # caused by the bind failure"), which keyword scans can't reliably tell from blame. So the forbidden-
    # keyword text scan only HARD-fails when the category is ALSO wrong (fell for it AND named it); when
    # the category is right it is reported, not gated.
    forbidden_kw = [_norm(k) for k in answer.get("forbidden_keywords", [])]
    blamed = [k for k in forbidden_kw if k and k in blob and not _is_dismissed(blob, k)]
    herring_blamed = (not gates["category_match"]) and (bool(blamed) or forbidden_category_hit)
    gates["not_herring_blamed"] = not herring_blamed

    max_loops = answer.get("max_investigation_loops")
    iters = result.get("iterations")
    if max_loops is None or iters is None:
        gates["trajectory_budget"] = True
    else:
        gates["trajectory_budget"] = iters <= max_loops

    # Evidence sources — soft by default.
    ev_sources = [_norm(e) for e in answer.get("required_evidence_sources", [])]
    ev_hit = [e for e in ev_sources if e and e in blob]
    evidence_ok = (not ev_sources) or (len(ev_hit) == len(ev_sources))
    strict_ev = os.environ.get("CP_OPS_STRICT_EVIDENCE") == "1"
    if strict_ev:
        gates["required_evidence_sources"] = evidence_ok

    concluded = bool(result.get("concluded"))
    gates["concluded"] = concluded

    passed = all(gates.values())
    return {
        "pass": passed,
        "gates": gates,
        "diagnostics": {
            "result_category": result.get("root_cause_category"),
            "missing_keywords": missing_kw,
            "unmet_groups": unmet_groups,
            "forbidden_blamed": blamed,
            "herring_blamed": herring_blamed,
            "evidence_hit": ev_hit,
            "evidence_ok": evidence_ok,
            "iterations": iters,
        },
    }


def main():
    if len(sys.argv) != 3:
        print("usage: scoring.py <answer.yml> <result.json>", file=sys.stderr)
        sys.exit(2)
    with open(sys.argv[1]) as f:
        answer = yaml.safe_load(f)
    with open(sys.argv[2]) as f:
        result = json.load(f)
    verdict = score(answer, result)
    print(json.dumps(verdict, indent=2))
    sys.exit(0 if verdict["pass"] else 1)


if __name__ == "__main__":
    main()
