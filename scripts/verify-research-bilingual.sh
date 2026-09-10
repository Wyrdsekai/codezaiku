#!/usr/bin/env bash
# The bilingual research check (the operator, 2026-09-01: "make sure the test is for it to check both
# english and japanese research"). Caught live: the librarian e2e about JAPANESE models ran five
# English-only queries, because the plain research() prompt lacked the language steering the chat
# register and fan decompose carry. This script verifies the FIX on the research verb itself:
# a question that NAMES Japanese sources (without ordering "search in Japanese") must produce
# BOTH Japanese-script and English queries. Graded from the run log's actual query strings —
# never from the answer's claims about what it searched.
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DRIVE="${1:-${CODEZAIKU_DRIVE:-}}"
CZ="${2:-$ROOT/bin/codezaiku}"
case "$CZ" in /*) ;; *) CZ="$ROOT/$CZ" ;; esac
[ -n "$DRIVE" ] || { echo "usage: verify-research-bilingual.sh <drive-url> [codezaiku]" >&2; exit 2; }
curl -s -m 10 "$DRIVE/v1/models" >/dev/null 2>&1 || { echo "no drive at $DRIVE" >&2; exit 2; }
[ -n "${CODEZAIKU_SEARXNG:-}${CODEZAIKU_BRAVE_KEY:-}" ] \
    || { echo "no search backend (set CODEZAIKU_SEARXNG or CODEZAIKU_BRAVE_KEY)" >&2; exit 2; }

if command -v timeout >/dev/null 2>&1; then t_out() { timeout "$@"; }
else t_out() { local _s=$1; shift; perl -e 'alarm shift; exec @ARGV' "$_s" "$@"; }; fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/cz-bilingual.XXXXXX")"
pass=0; fail=0
ok()  { printf '  \033[32mPASS\033[0m  %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; [ -n "${2:-}" ] && printf '        %s\n' "$2"; fail=$((fail+1)); }

# The question REQUIRES both literatures — compare what JA-language and EN-language sources say.
# (First cut asked only for Japanese sources and the model reasonably went 8/8 JA — the mirror
# of the all-English failure. A fair bilingual check needs a question where either-only misses.)
Q="Compare what Japanese-language sources and English-language sources each say about handling keigo (敬語) when subtitling Japanese films into English. Cite sources from both languages."

echo "== bilingual research run (depth, 14 turns) — this takes a few minutes"
# 1800s: deadline turns on 80k-char contexts run long on a 27B — that is throughput, not quality.
t_out 1800 "$CZ" research "$Q" depth "$DRIVE" 14 >"$WORK/run.log" 2>&1
rc=$?
[ "$rc" -eq 0 ] && ok "run completed (rc=0)" || bad "run completed" "rc=$rc; tail: $(tail -2 "$WORK/run.log")"

# The queries actually issued, straight from the tool-call log lines.
# JSON-escaped quotes inside a query ("keigo honorifics" ...) must not truncate the capture.
grep -aoP 'web_search\(\{"query":"(?:[^"\\]|\\.)*' "$WORK/run.log" | sed 's/.*query":"//; s/\\"/"/g' > "$WORK/queries.txt"
QN=$(wc -l < "$WORK/queries.txt")
[ "$QN" -gt 0 ] && ok "issued $QN search queries" || bad "issued search queries" "none found in log"

if grep -qP '[\x{3040}-\x{30ff}\x{4e00}-\x{9fff}]' "$WORK/queries.txt"; then
    ok "at least one query in JAPANESE script"
else
    bad "at least one query in JAPANESE script" "all queries: $(tr '\n' ' | ' < "$WORK/queries.txt" | head -c 200)"
fi
if grep -qP '^[\x00-\x7f]+$' "$WORK/queries.txt"; then
    ok "at least one query in ENGLISH (pure ASCII)"
else
    bad "at least one query in ENGLISH" "queries: $(tr '\n' ' | ' < "$WORK/queries.txt" | head -c 200)"
fi

grep -qE 'https?://' "$WORK/run.log" && ok "answer carries source URLs" \
    || bad "answer carries source URLs" "$(tail -3 "$WORK/run.log")"

echo
echo "  $pass passed, $fail failed"
echo "  artifacts: $WORK (kept — queries.txt holds every query verbatim)"
[ "$fail" -eq 0 ]
