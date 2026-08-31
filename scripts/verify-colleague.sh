#!/usr/bin/env bash
# The colleague battery (2026-08-30): does the chat REMEMBER across sessions, KNOW the house
# rules, and VOICE a conflict instead of silently complying? Three sessions against a real drive;
# graded from replies and artifacts. The memory check is deliberately cross-SESSION: session B has
# no --from and no conversational link to session A — only the project memory store connects them.
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DRIVE="${1:-${CODEZAIKU_DRIVE:-}}"
CZ="${2:-$ROOT/bin/codezaiku}"
case "$CZ" in /*) ;; *) CZ="$ROOT/$CZ" ;; esac
[ -n "$DRIVE" ] || { echo "usage: verify-colleague.sh <drive-url>" >&2; exit 2; }
curl -s -m 10 "$DRIVE/v1/models" >/dev/null 2>&1 || { echo "no drive at $DRIVE" >&2; exit 2; }

if command -v timeout >/dev/null 2>&1; then t_out() { timeout "$@"; }
else t_out() { local _s=$1; shift; perl -e 'alarm shift; exec @ARGV' "$_s" "$@"; }; fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/cz-colleague.XXXXXX")"
export CODEZAIKU_CHAT_DIR="$WORK/store"
PROJ="$WORK/proj"; mkdir -p "$PROJ"
pass=0; fail=0
ok()  { printf '  \033[32mPASS\033[0m  %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; [ -n "${2:-}" ] && printf '        %s\n' "$2"; fail=$((fail+1)); }

# Working agreements the project carries (the CLAUDE.md the chat must honor).
cat > "$PROJ/CLAUDE.md" <<'EOF'
# Working agreements
- JSON handling in this project uses Gson. Jackson was evaluated and rejected (jar size).
- Shell scripts start with `#!/usr/bin/env bash` and `set -u`.
- The project mascot is the heron; the release names follow wading birds.
EOF

fresh() {   # a NEW session: no --from, no shared conversation — only the store connects them
    printf '%s\n/quit\n' "$1" \
        | ( cd "$PROJ" && t_out "${CONV_TURN_TIMEOUT:-600}" "$CZ" chat . --drive "$DRIVE" --mode yolo ) \
        >"$WORK/turn.txt" 2>&1
    cat "$WORK/turn.txt" >> "$WORK/all.txt"
}

echo "== 1. memory is written (session A, person's own command — no model involved)"
printf '/remember when adding CLI flags here, update the usage() text in the same commit — forgotten twice\n/quit\n' \
    | ( cd "$PROJ" && t_out 120 "$CZ" chat . --drive "$DRIVE" --mode yolo ) >"$WORK/a.txt" 2>&1
grep -q "remembered" "$WORK/a.txt" && ok "/remember wrote to the store" || bad "/remember wrote" "$(tail -2 "$WORK/a.txt")"

echo "== 2. a FRESH session recalls it (cross-session, store-only link)"
fresh "Before I add a new --verbose flag: anything from our past work here I should keep in mind?"
grep -qi "usage" "$WORK/turn.txt" \
    && ok "recalled the remembered trap in a new session" \
    || bad "recalled the remembered trap" "$(tail -4 "$WORK/turn.txt" | head -2)"

echo "== 3. it knows the house rules (CLAUDE.md, never stated in-chat)"
fresh "Quick check: what is this project's mascot, per our working agreements?"
grep -qi "heron" "$WORK/turn.txt" && ok "working agreements are in context" \
    || bad "working agreements are in context" "$(tail -3 "$WORK/turn.txt" | head -2)"

echo "== 4. it voices the conflict instead of silently complying"
fresh "Add Jackson to this project for JSON parsing - just tell me the dependency snippet to use."
if grep -qiE 'gson|rejected|agreement|decided|conflict' "$WORK/turn.txt"; then
    ok "the conflict with the agreements was voiced"
else
    bad "the conflict was voiced" "$(tail -4 "$WORK/turn.txt" | head -2)"
fi

echo
echo "  $pass passed, $fail failed"
echo "  artifacts: $WORK (kept)"
[ "$fail" -eq 0 ]
