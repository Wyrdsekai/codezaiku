#!/usr/bin/env bash
# Platform acceptance for CodeZaiku: does an installed build actually work on THIS machine?
#
# Written because macOS was documented as supported for a year without anyone running it, and the
# first real run found five defects — a build that could not start, a test oracle that reported every
# suite as failed, an absent runner read as a failure, a doubled security report, and install advice
# pointing at the wrong shell's rc file. Reasoning about a platform is not the same as running on it.
#
# Deliberately portable to bash 3.2, which is what macOS still ships: no associative arrays, no
# mapfile, no ${var^^}. Runs identically on Linux and macOS.
#
#   scripts/verify-platform.sh [drive-url]        (default: $CODEZAIKU_DRIVE or http://127.0.0.1:8200)
#
# Exit 0 = every check passed. Checks needing a model are skipped, loudly, when no drive answers.

set -u

DRIVE="${1:-${CODEZAIKU_DRIVE:-http://127.0.0.1:8200}}"
CP="${CODEZAIKU_BIN:-codezaiku}"
# RESOLVE CP TO AN ABSOLUTE PATH. This script cd's into throwaway repos, so a RELATIVE CODEZAIKU_BIN
# (./bin/codezaiku — the obvious thing to pass) stops resolving after the first cd. Every later
# invocation then fails into `2>/dev/null` and writes an EMPTY file, so the checks do not error, they
# report FAILURES. Measured: macOS read 5 passed / 6 failed / 3 skipped that way, and 15 passed / 2
# failed with an absolute path — six false defects from one unresolved path. A harness that turns its
# own misconfiguration into a platform verdict is the worst kind of broken instrument.
case "$CP" in
    /*) ;;
    */*) CP="$(cd "$(dirname "$CP")" 2>/dev/null && pwd)/$(basename "$CP")" ;;
    *)  command -v "$CP" >/dev/null 2>&1 || { echo "verify-platform: '$CP' not on PATH" >&2; exit 2; } ;;
esac
if [ ! -x "$CP" ] && [ "${CP#/}" != "$CP" ]; then
    echo "verify-platform: '$CP' is not executable" >&2; exit 2
fi
WORK="$(mktemp -d "${TMPDIR:-/tmp}/cp-verify.XXXXXX")"
PASS=0; FAIL=0; SKIP=0

command -v "$CP" >/dev/null 2>&1 || { echo "no '$CP' on PATH — install first"; exit 2; }

cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

ok()   { PASS=$((PASS+1)); printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf '  \033[31mFAIL\033[0m  %s\n' "$1"; [ -n "${2:-}" ] && printf '        %s\n' "$2"; }
skip() { SKIP=$((SKIP+1)); printf '  \033[33mSKIP\033[0m  %s\n' "$1"; }
head_() { printf '\n== %s\n' "$1"; }

# A run that failed because no model answered is NOT a platform failure. Detect it and SKIP loudly —
# blaming the host for a missing drive is exactly the misattribution this script exists to prevent.
drive_absent() {
    printf '%s' "$1" | grep -qiE 'no model server is answering|contextWindow\(\) failed'
}

# Find a JSON parser. python3 is absent from Git Bash on Windows even when Windows has python, so
# the JSON-shape checks SKIP loudly there instead of failing — a missing parser is a gap in this
# script, not a defect in the thing under test, and reporting it as FAIL sent me chasing five
# phantom failures on the first Windows run.
PY_BIN=""
for c in python3 python py; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import json' >/dev/null 2>&1; then PY_BIN="$c"; break; fi
done
have_py() { [ -n "$PY_BIN" ]; }

jget() { "$PY_BIN" -c 'import json,sys
d=json.load(sys.stdin)
for k in sys.argv[1:]:
    d = d.get(k) if isinstance(d, dict) else None
print("" if d is None else d)' "$@" 2>/dev/null; }

# Sets REPO and cds there. Deliberately NOT `d="$(newrepo x)"` — command substitution runs in a
# SUBSHELL, so the cd would not affect the caller and every file the checks write would land in the
# invoking directory instead. That happened, and scattered test files across the source tree.
newrepo() {
    REPO="$WORK/$1"
    mkdir -p "$REPO"
    cd "$REPO" || exit 1
    git init -q . 2>/dev/null
    git config user.email v@example.com; git config user.name verify
    echo "seed" > seed.txt; git add -A >/dev/null 2>&1; git commit -qm base >/dev/null 2>&1
}

printf 'CodeZaiku platform verification\n  os     %s %s\n  java   %s\n  drive  %s\n' \
    "$(uname -s)" "$(uname -m)" \
    "$(java -version 2>&1 | head -1 | sed 's/.*version //;s/ .*//')" "$DRIVE"

# ── 1. things that need no model ─────────────────────────────────────────────
head_ "basics"

v="$($CP --version 2>&1)"; rc=$?
[ $rc -eq 0 ] && echo "$v" | grep -q codezaiku && ok "--version (health check): $v" \
    || bad "--version" "rc=$rc out=$v"

$CP doctor >"$WORK/doctor.txt" 2>&1
grep -q "java" "$WORK/doctor.txt" && ok "doctor runs and reports java" || bad "doctor" "$(head -3 "$WORK/doctor.txt")"

# A check that cannot run must SAY so rather than be omitted — degrading loudly is the contract.
if grep -qE '^\s+(ok|FAIL|--)' "$WORK/doctor.txt"; then
    ok "doctor names unavailable components rather than hiding them"
else
    bad "doctor status markers missing"
fi

# ── 2. the test oracle (no model needed) ─────────────────────────────────────
head_ "test oracle"

newrepo oracle-green
printf 'def double(n):\n    return n * 2\n' > mod.py
printf 'from mod import double\n\ndef test_a():\n    assert double(3) == 6\n\ndef test_b():\n    assert double(0) == 0\n' > test_mod.py
# Ask whether PYTEST runs, never whether a python exists. `[ -x .venv/bin/python ]` was the old
# test, and a venv is created successfully on a box with no pip at all — so on a distribution
# shipping python3 without pip (measured: Ubuntu under WSL2, python 3.14, no pip module) the guard
# said "pytest available", the case ran, and a MISSING TEST RUNNER was reported as a product failure.
# A probe that cannot tell "absent" from "broken" turns an environment gap into a false defect.
# Use the resolved $PY_BIN, not a literal `python3`: this script already knows python3 is absent from
# Git Bash on Windows even when Windows has python, and the guard hardcoding it meant the oracle case
# skipped on Windows for a reason that had nothing to do with pytest. A venv also puts its interpreter
# in Scripts/ on Windows and bin/ everywhere else.
VENV_PY=""
for cand in .venv/bin/python .venv/Scripts/python.exe .venv/Scripts/python; do
    [ -x "$cand" ] && { VENV_PY="$cand"; break; }
done
if have_py && "$PY_BIN" -m pytest --version >/dev/null 2>&1; then
    HAVE_PYTEST=1
elif [ -n "$VENV_PY" ] && "$VENV_PY" -m pytest --version >/dev/null 2>&1; then
    HAVE_PYTEST=1
elif have_py; then
    "$PY_BIN" -m venv .venv >/dev/null 2>&1
    VENV_PY=""
    for cand in .venv/bin/python .venv/Scripts/python.exe .venv/Scripts/python; do
        [ -x "$cand" ] && { VENV_PY="$cand"; break; }
    done
    [ -n "$VENV_PY" ] && "$VENV_PY" -m pip install -q pytest >/dev/null 2>&1
    if [ -n "$VENV_PY" ] && "$VENV_PY" -m pytest --version >/dev/null 2>&1; then
        HAVE_PYTEST=1
    else
        HAVE_PYTEST=0
    fi
else
    HAVE_PYTEST=0
fi

if [ "$HAVE_PYTEST" = "1" ]; then
    # a green suite must report success WITH counts — under-reporting green is the failure that matters.
    # The budget matches the product default (CODEZAIKU_RUN_MAX_TURNS=40) rather than a tighter fixture
    # number: at 8 this case failed 1-2 runs in 5 on EVERY platform, because the 9B sometimes starts
    # authoring extra tests instead of running the two that exist, and burns the budget doing it. A
    # fixture that runs the model at a fifth of the shipped budget is measuring the fixture.
    out="$($CP run --text "Run the test suite and report results." --output-format json \
           --no-session -q --max-turns 40 --drive "$DRIVE" 2>/dev/null)"
    st="$(printf '%s' "$out" | jget status)"
    tp="$(printf '%s' "$out" | jget testsPassed)"
    tr="$(printf '%s' "$out" | jget testsRan)"
    tf="$(printf '%s' "$out" | jget testsFailed)"
    # The claim under test is the HARNESS's: a green suite is reported as green, WITH counts.
    # Asserting testsPassed == 2 exactly tested something else — that the model left the suite alone —
    # and the 9B sometimes authors extra tests, pushing the count to 3 or 5 while the suite is still
    # entirely green. That is a model behaviour, not a harness defect, and this case should not fail on
    # it. `>= 2 with nothing failing` is the property the comment above has always described.
    if [ "$st" = "success" ] && [ "$tr" = "True" ] && [ "${tp:-0}" -ge 2 ] 2>/dev/null \
       && { [ "$tf" = "0" ] || [ -z "$tf" ]; }; then
        ok "green suite -> success, testsPassed=$tp, testsFailed=${tf:-0}, testsRan=true"
    elif [ -z "$st" ] || drive_absent "$out"; then
        skip "green-suite oracle (no drive at $DRIVE)"
    else
        bad "green suite" "status=$st testsPassed=$tp testsFailed=$tf testsRan=$tr"
    fi
else
    skip "oracle checks (no pytest and could not create a venv)"
fi

# ── 3. the coding surface ────────────────────────────────────────────────────
head_ "coding surface"

newrepo coding
out="$($CP run --text "Create mul.py containing a function mul(a, b) that returns a * b." \
       --output-format json --no-session -q --max-turns 12 --task-id verify-1 --drive "$DRIVE" 2>/dev/null)"
if [ -z "$out" ] || drive_absent "$out"; then
    skip "run verb (no drive at $DRIVE)"
else
    if have_py; then
        printf '%s' "$out" | "$PY_BIN" -c 'import json,sys; json.load(sys.stdin)' 2>/dev/null \
            && ok "run: stdout is exactly one JSON document" \
            || bad "run: stdout not a single JSON document" "$(printf '%s' "$out" | head -c 200)"
        [ "$(printf '%s' "$out" | jget taskId)" = "verify-1" ] \
            && ok "run: taskId echoed" || bad "run: taskId not echoed"
    else
        skip "run: JSON-shape checks (no python on PATH)"
    fi
    if [ -f mul.py ]; then
        ok "run: the file was actually written"
        printf '%s' "$out" | grep -q 'mul.py' \
            && ok "run: files[] reports it" || bad "run: files[] missing mul.py"
        printf '%s' "$out" | grep -q 'seed.txt' \
            && bad "run: files[] wrongly includes the pre-existing seed.txt" \
            || ok "run: files[] excludes pre-existing committed files"
    else
        bad "run: no file produced (model may be too small)" "$(printf '%s' "$out" | jget summary)"
    fi
fi

# ── 3b. setup keeps a model that can chat ────────────────────────────────────
# 0.3.8: setup used to offer the first model a server lists, and servers list alphabetically, so a box with an
# embedding model saved "embed" on Enter. Run the real wizard against the real drive, Enter to everything, in a
# settings file of its own, and read what it saved.
head_ "setup"
SETUP_CFG="$WORK/setup-home/config"; mkdir -p "$WORK/setup-home"; printf 'drive = %s\n' "$DRIVE" > "$SETUP_CFG"
setup_out="$(printf '\n\n\n\n\n\n' | CODEZAIKU_CONFIG="$SETUP_CFG" $CP setup --no-programs --no-library 2>/dev/null)"
if printf '%s' "$setup_out" | grep -q 'Found a model server'; then
    saved_model="$(grep -E '^model *=' "$SETUP_CFG" | head -1 | sed 's/^model *= *//')"
    printf '%s' "$setup_out" | grep -q 'it answered' && ok "setup: the model it saved answered ($saved_model)" \
        || bad "setup: the saved model did not answer" "$(printf '%s' "$setup_out" | grep -E 'Asking|no:|Saved' | head -3)"
    case "$saved_model" in
        *embed*|*rerank*|"") bad "setup: saved '$saved_model' as the model" ;;
        *) ok "setup: Enter did not save an embedding model" ;;
    esac
else
    skip "setup against a drive (no drive at $DRIVE)"
fi

# ── 4. cancellation leaves nothing behind ────────────────────────────────────
head_ "cancellation"

newrepo cancel
$CP run --text "Create x.py then run the shell command: sleep 240" --output-format json \
   --no-session -q --drive "$DRIVE" >"$WORK/cancel.json" 2>/dev/null &
runpid=$!
i=0
while [ $i -lt 40 ]; do [ -f x.py ] && break; sleep 3; i=$((i+1)); done
kids=""
for c in $(pgrep -P "$runpid" 2>/dev/null); do kids="$kids $c"; done
kill -TERM "$runpid" 2>/dev/null
wait "$runpid" 2>/dev/null; crc=$?
sleep 3
if [ -s "$WORK/cancel.json" ] && ! drive_absent "$(cat "$WORK/cancel.json")"; then
    if have_py; then
        "$PY_BIN" -c 'import json,sys; json.load(open(sys.argv[1]))' "$WORK/cancel.json" 2>/dev/null \
            && ok "SIGTERM still emits a valid JSON document (exit $crc)" \
            || bad "SIGTERM produced invalid JSON"
    else
        grep -q '"status"' "$WORK/cancel.json" && ok "SIGTERM emitted a result document (exit $crc)" \
            || bad "SIGTERM produced no result"
    fi
    grep -q interrupted "$WORK/cancel.json" && ok "SIGTERM result is marked interrupted" \
        || bad "SIGTERM result not marked interrupted"
else
    skip "SIGTERM reporting (no drive at $DRIVE, or the run never started)"
fi
left=""
for c in $kids; do ps -p "$c" >/dev/null 2>&1 && left="$left $c"; done
[ -z "$left" ] && ok "SIGTERM left no orphaned child processes" || bad "orphans survived:$left"

# ── 5. protocol surfaces ─────────────────────────────────────────────────────
head_ "protocol surfaces"

newrepo acp
{ printf '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}\n'
  # Send the path shape a NATIVE client on this platform would send. Under Git Bash, $REPO is an
  # MSYS path (/c/Users/...) which is not a valid path to a Windows JVM — a real ACP client (an
  # editor) sends C:\Users\... . cygpath does that conversion; the sed then escapes the backslashes,
  # which are JSON escape characters.
  acp_cwd="$REPO"
  if command -v cygpath >/dev/null 2>&1; then acp_cwd="$(cygpath -w "$REPO")"; fi
  printf '{"jsonrpc":"2.0","id":2,"method":"session/new","params":{"cwd":"%s","mcpServers":[]}}\n' \
      "$(printf '%s' "$acp_cwd" | sed 's/\\/\\\\/g')"
  # A client's own MCP server (0.3.7): session/new has to START a program the client names, with the platform's own
  # path form, and list its tools. CodeZaiku's `mcp` command is the server here, so the check needs nothing else
  # installed. On Windows a native client names the .bat launcher, not the shell script beside it.
  mcp_cmd="$(command -v "$CP")"
  if command -v cygpath >/dev/null 2>&1; then mcp_cmd="$(cygpath -w "${mcp_cmd%.bat}.bat")"; fi
  printf '{"jsonrpc":"2.0","id":3,"method":"session/new","params":{"cwd":"%s","mcpServers":[{"name":"self","command":"%s","args":["mcp"],"env":[{"name":"CZ_PLATFORM_CHECK","value":"1"}]}]}}\n' \
      "$(printf '%s' "$acp_cwd" | sed 's/\\/\\\\/g')" "$(printf '%s' "$mcp_cmd" | sed 's/\\/\\\\/g')"
  printf '{"jsonrpc":"2.0","id":99,"method":"totally/unknown","params":{}}\n'
} | $CP acp >"$WORK/acp.txt" 2>/dev/null

if have_py; then
    "$PY_BIN" - "$WORK/acp.txt" <<'PYEOF' && ok "ACP: every stdout line is valid JSON (protocol not corrupted)" || bad "ACP: stdout carried non-JSON"
import json,sys
for line in open(sys.argv[1]):
    if line.strip():
        json.loads(line)
PYEOF
else
    skip "ACP: JSON validation (no python on PATH)"
fi
grep -q '"protocolVersion":1' "$WORK/acp.txt" && ok "ACP: negotiates protocol v1" || bad "ACP: no protocolVersion 1"
grep -q '"sessionId"' "$WORK/acp.txt" && ok "ACP: session/new returns a sessionId" || bad "ACP: session/new failed"
grep '"id":3' "$WORK/acp.txt" | grep -q '"sessionId"' && ok "ACP: session/new starts the client's stdio MCP server" || bad "ACP: the client's MCP server did not start: $(grep '"id":3' "$WORK/acp.txt" | cut -c1-300)"
grep '"id":2' "$WORK/acp.txt" | grep -q '"category":"model"' && ok "ACP: session/new offers the model option" || bad "ACP: no model config option"
grep -q '\-32601' "$WORK/acp.txt" && ok "ACP: unknown method -> -32601" || bad "ACP: wrong error for unknown method"

printf '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{}}}\n' \
  | $CP mcp >"$WORK/mcp.txt" 2>/dev/null
if have_py; then
    head -1 "$WORK/mcp.txt" | "$PY_BIN" -c 'import json,sys; json.loads(sys.stdin.readline())' 2>/dev/null \
        && ok "MCP: stdout is clean JSON-RPC" || bad "MCP: stdout corrupted" "$(head -c 120 "$WORK/mcp.txt")"
else
    grep -q '"jsonrpc"' "$WORK/mcp.txt" && ok "MCP: stdout looks like JSON-RPC (no python for a strict parse)" \
        || bad "MCP: no jsonrpc envelope on stdout"
fi

# ── 6. security surface (report-only) ────────────────────────────────────────
head_ "security surface"

$CP secure local >"$WORK/secure.txt" 2>/dev/null
# `grep -c` PRINTS the count and EXITS 1 when that count is zero, so `|| echo 0` appends a SECOND
# zero and n becomes "0\n0" — the failure message then reads "header appears 0\n0 times". Judge the
# output, not the exit code (CLAUDE.md), which is exactly the rule this line was breaking.
n="$(grep -c 'security review on' "$WORK/secure.txt" 2>/dev/null || true)"
n="${n:-0}"
[ "$n" = "1" ] && ok "secure: report rendered exactly once" || bad "secure: header appears $n times (duplicate report)"
if grep -q 'INTRUSION' "$WORK/secure.txt"; then
    ok "secure: reports intrusion-detection status rather than omitting it"
else
    bad "secure: no INTRUSION line — a missing sensor must announce itself"
fi

# ── summary ──────────────────────────────────────────────────────────────────
printf '\n%s\n  %d passed, %d failed, %d skipped\n' "----" "$PASS" "$FAIL" "$SKIP"
[ "$FAIL" -eq 0 ] || exit 1
