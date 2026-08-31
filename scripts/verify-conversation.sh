#!/usr/bin/env bash
# Can it hold a WORKING CONVERSATION? Plan, build, be corrected, extend, remember, and still work.
#
# verify-chat.sh asks whether the chat SURFACE behaves — stderr, flags, approvals, what lands on
# disk — against a scripted stub. This asks the different question: with a real model on the other
# end, does a multi-turn session actually get work done. Those need separate harnesses, because a
# stub cannot fail at understanding and a real model cannot be asserted on deterministically.
#
#   scripts/verify-conversation.sh <drive-url> [launcher]
#
# THE SHAPE IS TAKEN FROM A REAL SESSION, not invented. What a person actually does over an hour is:
# plan something, have it built, say "no, not like that", extend it, refer back to a decision made
# earlier, and expect the result to still work. Each of those is a different failure mode:
#
#   1. PLAN        can it produce an artifact worth following
#   2. IMPLEMENT   can a later turn act on that artifact
#   3. CORRECT     >>> does a correction STICK — the one that matters most, because a session where
#                  "no, do it the other way" is forgotten two turns later is unusable no matter how
#                  good any single turn was
#   4. EXTEND      can it add to its own work
#   5. RECALL      does a decision survive turns that did not mention it
#   6. WORK        do the tests pass at the end
#
# Graded on the ARTIFACT — files on disk and a pytest run — never on what the model says about
# itself. A model reporting success is not evidence, and this project has been fooled by exactly
# that before.
#
# Exit 0 = every check passed. Runs against whatever drive you give it; expect minutes, not seconds.

set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DRIVE="${1:-${CODEZAIKU_DRIVE:-}}"
CZ="${2:-$ROOT/bin/codezaiku}"
case "$CZ" in /*) ;; *) CZ="$ROOT/$CZ" ;; esac

[ -n "$DRIVE" ] || { echo "usage: verify-conversation.sh <drive-url> [launcher]" >&2; exit 2; }
curl -s -m 10 "$DRIVE/v1/models" >/dev/null 2>&1 \
    || { echo "no drive answering at $DRIVE — this battery needs a real model" >&2; exit 2; }

# macOS ships neither `timeout` nor `gtimeout` (PLATFORMS.md); perl's alarm is the documented
# fallback. Measured 2026-08-29: a bare `timeout` here made every macOS turn die instantly with
# "command not found", and the score (3/11) was about THIS script, not the platform or the model.
if command -v timeout >/dev/null 2>&1; then
    t_out() { timeout "$@"; }
else
    t_out() { local _s=$1; shift; perl -e 'alarm shift; exec @ARGV' "$_s" "$@"; }
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/cz-conv.XXXXXX")"
# NOT cleaned up on exit: the artifact is the evidence. A battery that deletes what it judged
# leaves you with a number you cannot check, which is the failure mode this project keeps hitting.
export CODEZAIKU_CHAT_DIR="$WORK/store"
PROJ="$WORK/proj"; mkdir -p "$PROJ"
( cd "$PROJ" && git init -q && git commit -q --allow-empty -m init )
PASS=0; FAIL=0
MAXTURNS="${CONV_MAX_TURNS:-25}"

ok()  { PASS=$((PASS+1)); printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf '  \033[31mFAIL\033[0m  %s\n' "$1"
        [ -n "${2:-}" ] && printf '        %s\n' "$2"; return 0; }

# One message, its own session-continuing invocation. yolo because this is a throwaway directory and
# an unattended run cannot answer prompts — the approval path is verify-chat.sh's job, not this one.
# ONE conversation, not five. Each say() RESUMES the session the previous one saved (--from),
# because that is the thing this battery exists to test — an ongoing conversation. The first
# version started a fresh session per turn, which quietly turned "recall a decision from three
# turns ago" into a luck check: the model was asked to remember a conversation it was never in
# (measured 2026-08-29 — the 35B answered by paraphrasing the system prompt, and the 27B's
# artifact shows no answer at all; file-based checks still passed because the PROJECT carries
# state even when the conversation does not).
SID=""
say() {
    printf '%s\n/quit\n' "$1" \
        | ( cd "$PROJ" && t_out "${CONV_TURN_TIMEOUT:-900}" "$CZ" chat . --drive "$DRIVE" \
              --mode yolo --max-turns "$MAXTURNS" ${SID:+--from "$SID"} ) >"$WORK/turn.txt" 2>&1
    cat "$WORK/turn.txt" >> "$WORK/all.txt"
    # The id of the session this turn saved, for the next turn to resume.
    sid_file=$(sed -n 's/^saved  *//p' "$WORK/turn.txt" | tail -1)
    [ -n "$sid_file" ] && SID=$(basename "$sid_file" .md)
}

echo
echo "== 1. plan"
say "Plan a small URL-shortener web service in Python: POST /shorten takes a JSON url and returns a short code; GET /<code> redirects to it. In-memory storage. Write the plan to PLAN.md as numbered steps naming the files to create. Write ONLY PLAN.md — no code."
[ -f "$PROJ/PLAN.md" ] && ok "wrote PLAN.md" || bad "wrote PLAN.md" "$(ls "$PROJ")"
grep -qi 'shorten' "$PROJ/PLAN.md" 2>/dev/null && grep -qi 'redirect' "$PROJ/PLAN.md" 2>/dev/null \
    && ok "the plan covers both endpoints" || bad "the plan covers both endpoints"
# "no code yet" is an instruction a small model routinely ignores; it is worth knowing which.
# MEASURED, and it cost a false FAIL: every check here must search the TREE. The model followed its
# own plan and put the suite in tests/test_app.py, and a top-level `ls test_*.py` reported "no test
# file was ever written" about six passing tests. Fifth grader of the day to lie in the safe
# direction of my expectations.
PYFILES() { find "$PROJ" -name '*.py' -not -path '*/__pycache__/*' -not -path '*/.git/*'; }
[ -n "$(PYFILES)" ] \
    && bad "wrote ONLY the plan, as asked" "$(PYFILES | xargs -n1 basename | tr '\n' ' ')" \
    || ok "wrote ONLY the plan, as asked"

echo
echo "== 2. implement from the plan"
say "Read PLAN.md and implement it. Use ONLY the Python standard library — http.server, no third-party packages. Write the service and a test file that runs under pytest."
[ -n "$(PYFILES)" ] && ok "wrote Python files" || bad "wrote Python files" "$(ls "$PROJ")"
PYFILES | xargs grep -qi 'shorten' 2>/dev/null && ok "the code implements /shorten" \
    || bad "the code implements /shorten"

echo
echo "== 3. the correction sticks   <<< the one that matters"
# A session where "no, do it the other way" is forgotten two turns later is unusable however good
# any single turn was. This is the property the session layer exists to provide.
say "Do not use any third-party packages at all — no fastapi, no flask. Standard library only. Fix it if you have."
if PYFILES | xargs grep -qiE '^[[:space:]]*(import|from)[[:space:]]+(fastapi|flask|django|starlette)' 2>/dev/null; then
    bad "no third-party web framework after the correction" \
        "$(PYFILES | xargs grep -hiE '^[[:space:]]*(import|from)[[:space:]]+(fastapi|flask|django)' | head -2)"
else
    ok "no third-party web framework after the correction"
fi

echo
echo "== 4. extend its own work"
say "Now add a hit counter: every successful redirect increments a count for that code, and GET /stats/<code> returns it as JSON. Update the tests."
PYFILES | xargs grep -q 'stats' 2>/dev/null && ok "the extension landed" \
    || bad "the extension landed" "no /stats anywhere in the tree"

echo
echo "== 5. recall a decision from three turns ago"
say "In one sentence, and without reading any files: what did I tell you NOT to use?"
if grep -qiE 'third.?party|fastapi|flask|standard library|stdlib' "$WORK/turn.txt"; then
    ok "recalled the constraint from turn 3"
else
    bad "recalled the constraint from turn 3" "$(tail -4 "$WORK/turn.txt" | head -2)"
fi

echo
echo "== 6. does the result actually work"
# The only check that cannot be talked around. Graded by pytest, on the files as they now are.
if [ -n "$(find "$PROJ" -name 'test_*.py' -not -path '*/__pycache__/*')" ]; then
    ( cd "$PROJ" && t_out 120 python3 -m pytest -q ) >"$WORK/pytest.txt" 2>&1
    if grep -qE '[0-9]+ passed' "$WORK/pytest.txt" && ! grep -qE 'failed|error' "$WORK/pytest.txt"; then
        ok "the test suite passes: $(grep -oE '[0-9]+ passed[^,]*' "$WORK/pytest.txt" | head -1)"
    else
        bad "the test suite passes" "$(tail -3 "$WORK/pytest.txt")"
    fi
else
    bad "the test suite passes" "no test_*.py was ever written"
fi

echo
echo "== the session held together"
STATE=$(find "$CODEZAIKU_CHAT_DIR" -name '*.md' 2>/dev/null | head -1)
[ -n "$STATE" ] && ok "a session state file exists" || bad "a session state file exists"
[ -n "$STATE" ] && grep -qE 'turns: [1-9]' "$STATE" && ok "turns were recorded" || bad "turns were recorded"

echo
echo "----"
printf '  %d passed, %d failed\n' "$PASS" "$FAIL"
echo "  project:    $PROJ"
echo "  full log:   $WORK/all.txt"
echo "  (kept for reading — the artifact is the evidence, not this score)"
[ "$FAIL" -eq 0 ] || exit 1
