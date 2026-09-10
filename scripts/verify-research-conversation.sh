#!/usr/bin/env bash
# The research→decide→implement arc, as one CHAINED conversation against a real drive — the
# battery that defines "done" for the it-works-like-a-collaborator ask (2026-08-30). Shape:
#   1. a research question that demands SOURCES (the reply must carry URLs)
#   2. a judgment turn over what came back (prose, no new files)
#   3. a decision, stated by the person
#   4. "implement what we decided" — the code must MATCH the decision, not the alternative
#   5. the suite the implementation claims must actually pass
# Graded from artifacts. Needs: a drive, and a search backend (Brave key or SearXNG up).
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DRIVE="${1:-${CODEZAIKU_DRIVE:-}}"
CZ="${2:-$ROOT/bin/codezaiku}"
case "$CZ" in /*) ;; *) CZ="$ROOT/$CZ" ;; esac
[ -n "$DRIVE" ] || { echo "usage: verify-research-conversation.sh <drive-url>" >&2; exit 2; }
curl -s -m 10 "$DRIVE/v1/models" >/dev/null 2>&1 \
    || { echo "no drive answering at $DRIVE" >&2; exit 2; }

if command -v timeout >/dev/null 2>&1; then
    t_out() { timeout "$@"; }
else
    t_out() { local _s=$1; shift; perl -e 'alarm shift; exec @ARGV' "$_s" "$@"; }
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/cz-rconv.XXXXXX")"
export CODEZAIKU_CHAT_DIR="$WORK/store"
PROJ="$WORK/proj"; mkdir -p "$PROJ"
pass=0; fail=0
ok()  { printf '  \033[32mPASS\033[0m  %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; [ -n "${2:-}" ] && printf '        %s\n' "$2"; fail=$((fail+1)); }

SID=""
say() {
    printf '%s\n/quit\n' "$1" \
        | ( cd "$PROJ" && t_out "${CONV_TURN_TIMEOUT:-900}" "$CZ" chat . --drive "$DRIVE" \
              --mode yolo ${SID:+--from "$SID"} ) >"$WORK/turn.txt" 2>&1
    cat "$WORK/turn.txt" >> "$WORK/all.txt"
    sid_file=$(sed -n 's/^saved  *//p' "$WORK/turn.txt" | tail -1)
    [ -n "$sid_file" ] && SID=$(basename "$sid_file" .md)
}

echo "== 1. research with sources"
say "Which Java library should we use for JSON parsing in a small CLI: Jackson or Gson? Research the current state of both — maintenance, performance, footprint — and give me a comparison. Cite the URLs of sources you actually read."
grep -qiE 'jackson' "$WORK/turn.txt" && grep -qiE 'gson' "$WORK/turn.txt" \
    && ok "compared both candidates" || bad "compared both candidates"
grep -qE 'https?://[a-zA-Z0-9./_-]+' "$WORK/turn.txt" \
    && ok "the reply carries source URLs" || bad "the reply carries source URLs" "$(tail -4 "$WORK/turn.txt" | head -2)"
[ -z "$(find "$PROJ" -name '*.java' -o -name 'pom.xml' -o -name '*.gradle' 2>/dev/null)" ] \
    && ok "research turn wrote no code" || bad "research turn wrote no code" "$(ls "$PROJ")"

echo "== 1b. language-directed research (the JA-query check) and citations"
say "Also find me one or two JAPANESE-language articles or papers about JSON parsing performance - search in Japanese, and give the URLs."
LOGF=$(ls "$CODEZAIKU_CHAT_DIR"/*/logs/*.log 2>/dev/null | tail -1)
if [ -n "$LOGF" ] && grep -aoP '(?<=query":")[^"]*' "$LOGF" 2>/dev/null | grep -qP '[\x{3040}-\x{30ff}\x{4e00}-\x{9fff}]'; then
    ok "issued queries in Japanese"
else
    bad "issued queries in Japanese" "all queries were Latin-script (run log: $LOGF)"
fi
# BOTH sides (the operator, 2026-09-01: ): the session must have searched in English too — bilingual
# research means both scripts appear across the session's queries, not a wholesale switch.
if [ -n "$LOGF" ] && grep -aoP '(?<=query":")[^"]*' "$LOGF" 2>/dev/null | grep -qP '^[\x00-\x7f]+$'; then
    ok "issued queries in English as well (bilingual, not switched)"
else
    bad "issued queries in English as well" "no ASCII-only query found (run log: $LOGF)"
fi
grep -qE 'https?://' "$WORK/turn.txt" && ok "sources cited (citation bounce holds)"     || bad "sources cited" "$(tail -3 "$WORK/turn.txt" | head -2)"

echo "== 2. judgment over the findings"
say "We care most about minimal dependencies and a small jar. In one short paragraph: which one, and why?"
grep -qiE 'gson|jackson' "$WORK/turn.txt" \
    && ok "gave a recommendation" || bad "gave a recommendation"

echo "== 3. the decision"
say "Decided: we use Gson, latest stable, and the project is Maven. Note that."
ok "decision turn accepted"   # the check is whether it STICKS, next turn

echo "== 4. implement what we decided"
say "Now set up the project we discussed: a minimal Maven pom.xml and one class org.demo.Config with a static fromJson(String) method that parses {\"name\":...,\"port\":...} into a Config, plus a JUnit test. Everything per our decisions."
[ -f "$PROJ/pom.xml" ] && ok "pom.xml exists" || bad "pom.xml exists" "$(ls "$PROJ")"
grep -qi 'gson' "$PROJ/pom.xml" 2>/dev/null \
    && ok "the DECISION landed in the build (gson, not jackson)" \
    || bad "the DECISION landed in the build" "$(grep -io 'jackson\|gson' "$PROJ/pom.xml" 2>/dev/null | sort -u | tr '\n' ' ')"
grep -qi 'jackson' "$PROJ/pom.xml" 2>/dev/null \
    && bad "the rejected alternative stayed out" "pom.xml mentions jackson" \
    || ok "the rejected alternative stayed out"
[ -n "$(find "$PROJ/src" -name 'Config.java' 2>/dev/null)" ] \
    && ok "Config.java exists" || bad "Config.java exists"

echo "== 5. does it actually work"
if command -v mvn >/dev/null 2>&1 && [ -f "$PROJ/pom.xml" ]; then
    ( cd "$PROJ" && t_out 300 mvn -q test ) >"$WORK/mvn.txt" 2>&1 \
        && ok "mvn test passes" || bad "mvn test passes" "$(tail -3 "$WORK/mvn.txt" | head -2)"
else
    bad "mvn test passes" "no mvn or no pom"
fi

echo
echo "  $pass passed, $fail failed"
echo "  artifacts: $WORK  (kept — the artifact is the evidence)"
[ "$fail" -eq 0 ]
