#!/usr/bin/env bash
# Acceptance for `codezaiku chat`: drive the REAL launcher, script the model, check what actually
# happened — including stderr and what is left on disk.
#
# Written because four defects were found by the operator typing at it and none by the test suite. Every
# one was invisible to a unit test for the same reason: a unit test does not run the launcher, does
# not look at stderr, and does not check the filesystem afterwards.
#
#   scripts/verify-chat.sh [path-to-launcher]     (default: bin/codezaiku — the DEV one, on purpose:
#                                                  it is the one that was missing a JVM flag)
#
# No GPU and no model: scripts/stub-drive.py answers with a scripted sequence, so a conversation is
# deterministic and takes seconds. Exit 0 = everything passed.

set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CZ="${1:-$ROOT/bin/codezaiku}"
case "$CZ" in /*) ;; *) CZ="$ROOT/$CZ" ;; esac
[ -x "$CZ" ] || { echo "verify-chat: '$CZ' is not executable" >&2; exit 2; }

PORT="${CHAT_STUB_PORT:-18210}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/cz-chat.XXXXXX")"
# Point the chat store at the work dir so a test never writes to, or reads from, the real one.
export CODEZAIKU_CHAT_DIR="$WORK/store"
PASS=0; FAIL=0

ok()  { PASS=$((PASS+1)); printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf '  \033[31mFAIL\033[0m  %s\n' "$1"
        [ -n "${2:-}" ] && printf '        %s\n' "$2"; return 0; }

STUB_PID=""
cleanup() { [ -n "$STUB_PID" ] && kill "$STUB_PID" 2>/dev/null; rm -rf "$WORK"; }
trap cleanup EXIT

# --- the scripted model ------------------------------------------------------------------------
cat > "$WORK/script.json" <<'JSON'
[
  {"tool": "read_file", "args": {"path": "app.py"}},
  {"tool": "edit_file", "args": {"path": "app.py", "old_string": "return 1", "new_string": "return 2"}},
  {"tool": "shell", "args": {"command": "rm -rf build"}},
  {"tool": "task_done", "args": {"summary": "changed app.py to return 2"}}
]
JSON
python3 "$ROOT/scripts/stub-drive.py" "$PORT" "$WORK/script.json" >"$WORK/stub.log" 2>&1 &
STUB_PID=$!
for _ in $(seq 1 40); do
    curl -s -m 1 "http://127.0.0.1:$PORT/v1/models" >/dev/null 2>&1 && break
    sleep 0.25
done
curl -s -m 2 "http://127.0.0.1:$PORT/v1/models" >/dev/null 2>&1 \
    || { echo "verify-chat: stub drive never came up"; cat "$WORK/stub.log"; exit 2; }
DRIVE="http://127.0.0.1:$PORT"

# A throwaway project.
PROJ="$WORK/proj"; mkdir -p "$PROJ/sub"
printf 'def f():\n    return 1\n' > "$PROJ/app.py"
( cd "$PROJ" && git init -q && git add -A && git -c user.email=a@b -c user.name=t commit -qm init )

# chat <input> <extra args...> -> writes $WORK/out.txt and $WORK/err.txt, sets RC
chat() {
    local input="$1"; shift
    # Rewind the stub first: its script position is shared across sessions, and without this every
    # check's starting reply depends on what every earlier check consumed — phase luck, measured
    # when a change to turn counts broke five unrelated sections at once.
    curl -s -m 2 "$DRIVE/reset" >/dev/null 2>&1
    printf '%b' "$input" | ( cd "$PROJ" && "$CZ" chat . --drive "$DRIVE" "$@" ) \
        >"$WORK/out.txt" 2>"$WORK/err.txt"
    RC=$?
}

echo
echo "== stderr is quiet"
# The reported bug: bin/codezaiku lacked --enable-native-access, so four Lucene warnings landed on
# stderr AFTER the banner, mid-conversation. stderr is the first thing a caller quotes to explain a
# failure; the first thing there must be the reason.
chat '/quit\n'
if [ -s "$WORK/err.txt" ]; then
    bad "nothing on stderr for a clean run" "$(head -3 "$WORK/err.txt")"
else
    ok "nothing on stderr for a clean run"
fi
grep -qi 'restricted method\|WARNING' "$WORK/err.txt" 2>/dev/null \
    && bad "no JVM/Lucene warnings" "$(grep -i warning "$WORK/err.txt" | head -1)" \
    || ok "no JVM/Lucene warnings"

echo
echo "== argument handling"
# `--drive` with nothing after it used to mean localhost:8200 silently.
printf '/quit\n' | ( cd "$PROJ" && "$CZ" chat . --drive ) >"$WORK/out.txt" 2>"$WORK/err.txt"; RC=$?
[ "$RC" = 2 ] && grep -q 'needs a value' "$WORK/err.txt" \
    && ok "a flag with no value fails with usage (exit 2)" \
    || bad "a flag with no value fails with usage (exit 2)" "rc=$RC $(head -1 "$WORK/err.txt")"

echo
echo "== working directory"
# Running from a subdirectory is correct and surprising; it must say so.
printf '/quit\n' | ( cd "$PROJ/sub" && "$CZ" chat . --drive "$DRIVE" ) >"$WORK/out.txt" 2>&1
grep -q 'only files under this directory' "$WORK/out.txt" \
    && ok "a subdirectory says the project root is above it" \
    || bad "a subdirectory says the project root is above it" "$(head -3 "$WORK/out.txt")"

echo
echo "== approvals"
# A read must never interrupt; the edit and the shell must both ask.
chat 'change it\ny\ny\n/quit\n'
grep -q 'read_file' "$WORK/out.txt" && ! grep -B2 'read_file' "$WORK/out.txt" | grep -q '⏸' \
    && ok "a read is not interrupted" || bad "a read is not interrupted"
grep -q '⏸  edit app.py' "$WORK/out.txt" \
    && ok "an edit asks, naming the file" || bad "an edit asks, naming the file" "$(grep '⏸' "$WORK/out.txt")"
grep -q '^     - return 1' "$WORK/out.txt" && grep -q '^     + return 2' "$WORK/out.txt" \
    && ok "the diff is shown before the question" \
    || bad "the diff is shown before the question" "$(grep -A3 '⏸  edit' "$WORK/out.txt" | head -3)"
grep -q '⏸  run `rm -rf`' "$WORK/out.txt" \
    && ok "a mutating command asks, naming the command" || bad "a mutating command asks"
grep -q 'return 2' "$PROJ/app.py" \
    && ok "an approved edit is actually applied" || bad "an approved edit is actually applied"

echo
echo "== refusal"
( cd "$PROJ" && git checkout -q . )
chat 'change it\nn\n/quit\n'
grep -q 'return 1' "$PROJ/app.py" \
    && ok "a refused edit is NOT applied" || bad "a refused edit is NOT applied"

echo
echo "== empty is not consent"
# Measured live: a blank line was read as YES and an unapproved command ran.
( cd "$PROJ" && git checkout -q . )
chat 'change it\n\n\n\n/quit\n'
grep -q 'return 1' "$PROJ/app.py" \
    && ok "a blank answer does not approve" || bad "a blank answer does not approve"
grep -q 'nothing is assumed' "$WORK/out.txt" \
    && ok "a blank answer says nothing is assumed" || bad "a blank answer says nothing is assumed"

echo
echo "== you can walk away from a prompt"
( cd "$PROJ" && git checkout -q . )
chat 'change it\ns\n/quit\n'
grep -q 'the turn is abandoned' "$WORK/out.txt" \
    && ok "stop abandons the turn" || bad "stop abandons the turn"
chat 'change it\n/quit\n'
grep -q 'the turn is abandoned' "$WORK/out.txt" \
    && ok "a slash command at a prompt also abandons it" \
    || bad "a slash command at a prompt also abandons it"

echo
echo "== modes"
( cd "$PROJ" && git checkout -q . )
chat 'change it\ny\n/quit\n' --mode auto-edit
grep -q '⏸  edit' "$WORK/out.txt" \
    && bad "auto-edit does not ask before editing" || ok "auto-edit does not ask before editing"
grep -q '⏸  run' "$WORK/out.txt" \
    && ok "auto-edit still asks before running" || bad "auto-edit still asks before running"

( cd "$PROJ" && git checkout -q . )
chat 'change it\n/quit\n' --mode plan
grep -q 'return 1' "$PROJ/app.py" \
    && ok "plan changes nothing" || bad "plan changes nothing"

echo
echo "== what is left on disk"
# A turn that never completed must leave nothing — not a state file and not a transcript.
rm -rf "$CODEZAIKU_CHAT_DIR"
printf 'this will fail\n/quit\n' | ( cd "$PROJ" && "$CZ" chat . --drive http://127.0.0.1:1 ) \
    >"$WORK/out.txt" 2>&1
# The run LOG is excluded on purpose: a failed run is exactly when the log earns its keep, and a
# diagnostic that deletes itself on failure is not a diagnostic.
LEFT=$(find "$CODEZAIKU_CHAT_DIR" -type f -not -path '*/logs/*' 2>/dev/null | wc -l | tr -d ' ')
[ "$LEFT" = 0 ] && ok "a failed turn leaves no SESSION files behind (the run log stays)" \
    || bad "a failed turn leaves no SESSION files behind" "$LEFT file(s)"
grep -q 'no model server is answering' "$WORK/out.txt" \
    && ok "a dead drive is reported, not a stack trace" || bad "a dead drive is reported"

# A completed turn must leave a state file that carries what happened.
rm -rf "$CODEZAIKU_CHAT_DIR"; ( cd "$PROJ" && git checkout -q . )
chat 'change it\ny\ny\n/quit\n'
STATE=$(find "$CODEZAIKU_CHAT_DIR" -name '*.md' | head -1)
[ -n "$STATE" ] && ok "a completed turn saves a state file" || bad "a completed turn saves a state file"
if [ -n "$STATE" ]; then
    grep -q 'app.py' "$STATE" && ok "the state records the file that was touched" \
        || bad "the state records the file that was touched" "$(head -20 "$STATE")"
    grep -q 'overwritten' "$STATE" && ok "the state file says it is not yours to edit" \
        || bad "the state file says it is not yours to edit"
fi
TRANSCRIPT=$(find "$CODEZAIKU_CHAT_DIR" -name '*.log.jsonl' | head -1)
[ -n "$TRANSCRIPT" ] && python3 -c "
import json,sys
for l in open(sys.argv[1]):
    json.loads(l)
" "$TRANSCRIPT" 2>/dev/null \
    && ok "the transcript is valid JSONL" || bad "the transcript is valid JSONL"

echo
echo "== the session carries between turns"
# A SECOND stub that only ever finishes, so no approval is needed. The first attempt at this check
# scripted approvals per user turn and failed — because ONE user turn is many loop turns (the loop
# runs a self-verify reflection round after task_done), so turn 1 ate the answers budgeted for turn
# 2 and the run recorded one turn. The harness was wrong, not the code; a check whose input depends
# on how many times the model happens to act is not a check.
cat > "$WORK/script2.json" <<'JSON'
[{"tool": "task_done", "args": {"summary": "answered"}}]
JSON
python3 "$ROOT/scripts/stub-drive.py" $((PORT + 1)) "$WORK/script2.json" >>"$WORK/stub.log" 2>&1 &
STUB2_PID=$!
for _ in $(seq 1 40); do
    curl -s -m 1 "http://127.0.0.1:$((PORT + 1))/v1/models" >/dev/null 2>&1 && break
    sleep 0.25
done
rm -rf "$CODEZAIKU_CHAT_DIR"; ( cd "$PROJ" && git checkout -q . )
printf 'first question\nsecond question\n/quit\n' \
    | ( cd "$PROJ" && "$CZ" chat . --drive "http://127.0.0.1:$((PORT + 1))" ) \
    >"$WORK/out.txt" 2>"$WORK/err.txt"
kill $STUB2_PID 2>/dev/null
STATE=$(find "$CODEZAIKU_CHAT_DIR" -name '*.md' | head -1)
[ -n "$STATE" ] && grep -q 'turns: 2' "$STATE" \
    && ok "two turns land in one session" \
    || bad "two turns land in one session" "$(grep turns: "$STATE" 2>/dev/null)"
# And the session is named after the FIRST thing said, not the last.
[ -n "$STATE" ] && grep -q '^# first question' "$STATE" \
    && ok "the session is named from the first message" \
    || bad "the session is named from the first message"

echo
echo "== ctrl-C stops a turn without ending the conversation"
# Needs a SLOW stub: a scripted turn finishes in milliseconds and there is no window to interrupt.
# And it must kill the JAVA process specifically — `pgrep -f` matches this script's own command line,
# which is a trap this project has written down and which I walked into anyway.
rm -rf "$CODEZAIKU_CHAT_DIR"; ( cd "$PROJ" && git checkout -q . )
printf '[{"tool":"read_file","args":{"path":"app.py"}}]' > "$WORK/slow.json"
STUB_DELAY=2 python3 "$ROOT/scripts/stub-drive.py" $((PORT + 2)) "$WORK/slow.json" >>"$WORK/stub.log" 2>&1 &
SLOW_PID=$!
for _ in $(seq 1 40); do
    curl -s -m 1 "http://127.0.0.1:$((PORT + 2))/v1/models" >/dev/null 2>&1 && break; sleep 0.25
done
( cd "$PROJ" && printf 'go
/quit
' | "$CZ" chat . --drive "http://127.0.0.1:$((PORT + 2))"     --max-turns 500 ) >"$WORK/int.txt" 2>&1 &
sleep 12
JPID=$(ps -eo pid,comm,args | awk '$2=="java" && /FamiliarMain chat/ {print $1; exit}')
[ -n "$JPID" ] && kill -INT "$JPID" 2>/dev/null
sleep 12
kill -9 "$JPID" 2>/dev/null; kill $SLOW_PID 2>/dev/null
grep -q 'stopping' "$WORK/int.txt" \
    && ok "ctrl-C stops the turn" || bad "ctrl-C stops the turn" "$(tail -3 "$WORK/int.txt")"
grep -q 'cancelled by the host' "$WORK/int.txt" \
    && ok "the loop reports it was cancelled" || bad "the loop reports it was cancelled"
grep -q 'ask > /quit' "$WORK/int.txt" \
    && ok "the conversation stays open afterwards" \
    || bad "the conversation stays open afterwards"

echo
echo "== a conversation survives the process"
# Sessions were saved and unreachable until /resume existed — write-only memory is not memory.
rm -rf "$CODEZAIKU_CHAT_DIR"; ( cd "$PROJ" && git checkout -q . )
chat 'change it\ny\ny\n/quit\n'
printf '/sessions\n/quit\n' | ( cd "$PROJ" && "$CZ" chat . --drive "$DRIVE" ) >"$WORK/out.txt" 2>&1
grep -q 'turn(s)' "$WORK/out.txt" \
    && ok "a NEW process lists the earlier session" \
    || bad "a NEW process lists the earlier session" "$(tail -3 "$WORK/out.txt")"
printf '/resume\n/quit\n' | ( cd "$PROJ" && "$CZ" chat . --drive "$DRIVE" ) >"$WORK/out.txt" 2>&1
grep -q 'resumed ' "$WORK/out.txt" \
    && ok "a NEW process resumes it" || bad "a NEW process resumes it"
grep -q 'session so far' "$WORK/out.txt" \
    && ok "and the decisions come back with it" \
    || bad "and the decisions come back with it" "$(tail -4 "$WORK/out.txt")"
printf '/resume 1999-01-01-nope\n/quit\n' | ( cd "$PROJ" && "$CZ" chat . --drive "$DRIVE" ) >"$WORK/out.txt" 2>&1
grep -q 'no session starting with' "$WORK/out.txt" \
    && ok "an unknown session is reported, not invented" \
    || bad "an unknown session is reported, not invented"

echo
echo "== trust survives the session; onboarding seeds a new one"
# Its own stub, with its own script. The main stub's sequence repeats through the loop's
# self-verify round, so the NUMBER of approval prompts per turn is not deterministic — and a
# scripted answer that lands on an unexpected prompt is read as a slash command and abandons the
# turn. A shell-then-done script asks exactly once, which is what these checks need.
rm -rf "$CODEZAIKU_CHAT_DIR"; ( cd "$PROJ" && git checkout -q . )
printf '[{"tool":"shell","args":{"command":"rm -rf build"}},{"tool":"task_done","args":{"summary":"done"}}]' > "$WORK/trust.json"
python3 "$ROOT/scripts/stub-drive.py" $((PORT + 3)) "$WORK/trust.json" >>"$WORK/stub.log" 2>&1 &
TRUST_PID=$!
for _ in $(seq 1 40); do
    curl -s -m 1 "http://127.0.0.1:$((PORT + 3))/v1/models" >/dev/null 2>&1 && break; sleep 0.25
done
TDRIVE="http://127.0.0.1:$((PORT + 3))"
# session 1: approve with 'a' (always), promote it, hand off
printf 'trust me\na\n/trust project\n/handoff start with the tests\n/quit\n' \
    | ( cd "$PROJ" && "$CZ" chat . --drive "$TDRIVE" ) >"$WORK/out.txt" 2>&1
grep -q 'answer(s) now standing for project' "$WORK/out.txt" \
    && ok "/trust project promotes the session's answers" \
    || bad "/trust project promotes the session's answers" "$(grep -i trust "$WORK/out.txt" | head -2)"
grep -q 'wrote .*handoff.md' "$WORK/out.txt" \
    && ok "/handoff writes the summary" || bad "/handoff writes the summary"
HAND=$(find "$CODEZAIKU_CHAT_DIR" -name '*.handoff.md' | head -1)
[ -n "$HAND" ] && grep -q 'start with the tests' "$HAND" \
    && ok "the handoff carries the human note" || bad "the handoff carries the human note"
# session 2, NEW process: the trusted command runs without a prompt
printf 'again please\n/quit\n' | ( cd "$PROJ" && "$CZ" chat . --drive "$TDRIVE" ) >"$WORK/out.txt" 2>&1
grep -q '⏸' "$WORK/out.txt" \
    && bad "a NEW session inherits the trusted answer (no prompt at all)" "$(grep '⏸' "$WORK/out.txt")" \
    || ok "a NEW session inherits the trusted answer (no prompt at all)"
# and the consent lives in the STORE, never the repo — a checkout must not arrive pre-trusted
[ -f "$PROJ/.codezaiku/consent" ] \
    && bad "consent is NOT in the repository" ".codezaiku/consent exists in the checkout" \
    || ok "consent is NOT in the repository"
# session 3: onboard from session 1
printf '/onboard trust-me\n/state\n/quit\n' \
    | ( cd "$PROJ" && "$CZ" chat . --drive "$TDRIVE" ) >"$WORK/out.txt" 2>&1
grep -q 'new session, seeded from' "$WORK/out.txt" \
    && ok "/onboard starts a new session that knows the old one" \
    || bad "/onboard starts a new session that knows the old one" "$(grep -A1 onboard "$WORK/out.txt" | head -3)"
grep -q 'onboarded from' "$WORK/out.txt" \
    && ok "the seed names its source session" || bad "the seed names its source session"
kill $TRUST_PID 2>/dev/null

echo
echo "== the working-turn toolbox: @file, /undo, /diff, /test, /model, streaming"
rm -rf "$CODEZAIKU_CHAT_DIR"; ( cd "$PROJ" && git checkout -q . && git clean -qfd )

# @file: the attachment is confirmed, and a missing file is said out loud rather than guessed at
chat 'look at @app.py and tell me\ny\ny\n/quit\n'
grep -q '· attached app.py' "$WORK/out.txt" \
    && ok "@file attaches a real file" || bad "@file attaches a real file"
chat 'look at @nope.py\n/quit\n'
grep -q '@nope.py not found' "$WORK/out.txt" \
    && ok "a missing @file is reported, not invented" || bad "a missing @file is reported, not invented"
chat 'look at @../../etc/passwd\n/quit\n'
grep -q 'not found — sent as plain text' "$WORK/out.txt" \
    && ok "@file cannot escape the project" || bad "@file cannot escape the project" "$(grep attach "$WORK/out.txt")"

# /undo: an approved edit is applied, then rolled back, and the session is told
( cd "$PROJ" && git checkout -q . )
# Answers are 'a' (always), not 'y': the self-verify round re-runs the stub script and asks again,
# and a 'y' budget that guesses the prompt count hands later commands to a prompt — the trap this
# battery has now hit three times. A standing answer covers every round.
# The stub turn is TWO mutating steps — the edit, then `rm -rf build` — and undo is now
# per-STEP: /undo 1 takes back only the shell and leaves the edit standing (that is the
# correction the operator asked for, and the first battery run proved it by failing). Both back:
chat 'change it\na\na\n/undo 2\n/state\n/quit\n'
grep -q 'rewound ' "$WORK/out.txt" \
    && ok "/undo restores the files" || bad "/undo restores the files" "$(grep -i undo "$WORK/out.txt" | head -2)"
grep -q 'return 1' "$PROJ/app.py" \
    && ok "the undone edit is really gone from disk" || bad "the undone edit is really gone from disk"
grep -q 'the user undid' "$WORK/out.txt" \
    && ok "the session is told about the undo" || bad "the session is told about the undo"

# /diff and /test
( cd "$PROJ" && git checkout -q . )
chat 'change it\ny\ny\n/diff\n/quit\n'
grep -qE 'app.py' "$WORK/out.txt" && ok "/diff names the changed file" || bad "/diff names the changed file"
( cd "$PROJ" && git checkout -q . )
chat '/test\n/quit\n'
grep -qE 'no test suite ran|passed' "$WORK/out.txt" \
    && ok "/test reports a verdict" || bad "/test reports a verdict"

# /model: shows the current drive; refuses a dead endpoint; switches to a live one
chat '/model\n/quit\n'
grep -q "drive $DRIVE" "$WORK/out.txt" && ok "/model shows the current drive" || bad "/model shows the current drive"
chat '/model http://127.0.0.1:1\n/quit\n'
grep -q 'not switching' "$WORK/out.txt" \
    && ok "/model refuses a dead endpoint" || bad "/model refuses a dead endpoint"
# Switch TO the main stub, starting FROM a dead drive — the auxiliary stubs are already killed,
# and probing a dead server here would test the wrong thing.
printf '/model %s\n/quit\n' "$DRIVE" \
    | ( cd "$PROJ" && "$CZ" chat . --drive "http://127.0.0.1:1" ) >"$WORK/out.txt" 2>&1
grep -q "drive -> $DRIVE" "$WORK/out.txt" \
    && ok "/model switches after a live probe" || bad "/model switches after a live probe" "$(grep -iE 'model|drive' "$WORK/out.txt" | head -2)"

# streaming: prose arrives via SSE when the config says so — and reassembly means the turn still works
printf '[{"content":"streamed words arriving one at a time","think":"pondering the imponderable quietly"},{"tool":"task_done","args":{"summary":"done streaming"}}]' > "$WORK/streamscript.json"
python3 "$ROOT/scripts/stub-drive.py" $((PORT + 4)) "$WORK/streamscript.json" >>"$WORK/stub.log" 2>&1 &
SSE_PID=$!
for _ in $(seq 1 40); do curl -s -m 1 "http://127.0.0.1:$((PORT + 4))/v1/models" >/dev/null 2>&1 && break; sleep 0.25; done
printf 'talk to me\n/quit\n' \
    | ( cd "$PROJ" && CODEZAIKU_STREAM=on "$CZ" chat . --drive "http://127.0.0.1:$((PORT + 4))" ) \
    >"$WORK/out.txt" 2>&1
grep -q 'streamed words arriving' "$WORK/out.txt" \
    && ok "streamed prose reaches the screen (CODEZAIKU_STREAM=on)" \
    || bad "streamed prose reaches the screen" "$(tail -4 "$WORK/out.txt")"
grep -q 'done streaming' "$WORK/out.txt" \
    && ok "a streamed TOOL CALL still works (deltas reassembled)" \
    || bad "a streamed tool call still works" "$(tail -3 "$WORK/out.txt")"
# Thinking is HIDDEN by default — the operator on seeing it: "why is it still talking to itself".
grep -q 'pondering the imponderable' "$WORK/out.txt" \
    && bad "thinking is hidden by default" \
    || ok "thinking is hidden by default"
# CODEZAIKU_STREAM=all shows it, labelled so it cannot be read as the model addressing the person.
printf 'talk to me\n/quit\n' \
    | ( cd "$PROJ" && CODEZAIKU_STREAM=all "$CZ" chat . --drive "http://127.0.0.1:$((PORT + 4))" ) \
    >"$WORK/out.txt" 2>&1
grep -q 'pondering the imponderable' "$WORK/out.txt" \
    && ok "CODEZAIKU_STREAM=all shows the thinking" \
    || bad "CODEZAIKU_STREAM=all shows the thinking" "$(tail -3 "$WORK/out.txt")"
grep -q '\[thinking\]' "$WORK/out.txt" \
    && ok "shown thinking is labelled as thinking" \
    || bad "shown thinking is labelled as thinking"
# /thinking on turns it on mid-session even when the config left it off
printf '/thinking on\ntalk to me\n/quit\n' \
    | ( cd "$PROJ" && CODEZAIKU_STREAM=on "$CZ" chat . --drive "http://127.0.0.1:$((PORT + 4))" ) \
    >"$WORK/out.txt" 2>&1
grep -q 'pondering the imponderable' "$WORK/out.txt" \
    && ok "/thinking on shows it mid-session" \
    || bad "/thinking on shows it mid-session" "$(tail -3 "$WORK/out.txt")"
kill $SSE_PID 2>/dev/null

echo
echo "== a conversation turn ends when the model says done"
# The self-verify reflection is injected as a USER message, so in a chat the model attributes it to
# the person — measured live: it apologised to the operator for a "verification protocol" they never
# sent, then manufactured a gradle init nobody asked for. Chat mode turns it off; this pins that.
( cd "$PROJ" && git checkout -q . )
printf '[{"tool":"task_done","args":{"summary":"four"}}]' > "$WORK/done.json"
python3 "$ROOT/scripts/stub-drive.py" $((PORT + 5)) "$WORK/done.json" >>"$WORK/stub.log" 2>&1 &
DONE_PID=$!
for _ in $(seq 1 40); do curl -s -m 1 "http://127.0.0.1:$((PORT + 5))/v1/models" >/dev/null 2>&1 && break; sleep 0.25; done
printf 'just answer: what is 2+2?\n/quit\n' \
    | ( cd "$PROJ" && CODEZAIKU_CHAT_LOG=info "$CZ" chat . --drive "http://127.0.0.1:$((PORT + 5))" ) >"$WORK/out.txt" 2>&1
kill $DONE_PID 2>/dev/null
grep -q 'self-verify reflection' "$WORK/out.txt" \
    && bad "no self-verify reflection in a chat turn" \
    || ok "no self-verify reflection in a chat turn"
grep -qE 'task_done at turn 1' "$WORK/out.txt" \
    && ok "one task_done ends the turn" \
    || bad "one task_done ends the turn" "$(grep -oE 'task_done at turn [0-9]+' "$WORK/out.txt" | head -1)"

echo
echo "== /help covers every command that exists"
# Grepped from the SOURCE, so a new command that skips /help fails here rather than shipping
# undocumented — the usage() rule ("an undocumented command is one nobody can use"), applied to chat.
chat '/help\n/quit\n'
MISSING=""
for cmd in $(grep -oE 'case "/[a-z]+"' "$ROOT/core/src/main/java/org/codezaiku/chat/ChatRepl.java" | grep -oE '/[a-z]+' | sort -u); do
    grep -q -- "$cmd" "$WORK/out.txt" || MISSING="$MISSING $cmd"
done
[ -z "$MISSING" ] && ok "every command appears in /help" \
    || bad "every command appears in /help" "missing:$MISSING"
grep -q '@path' "$WORK/out.txt" && grep -q 'all stop asking' "$WORK/out.txt" \
    && ok "/help covers @file and the prompt answers" \
    || bad "/help covers @file and the prompt answers"

echo
echo "== no turn cap by default — the person is the cap"
# The old default of 8 died on the first real work-shaped ask, mid-fix. A 12-step turn now runs to
# its own conclusion; --max-turns still exists for unattended runs, and 0 means no cap.
LONG='['
for i in 1 2 3 4 5 6 7 8 9 10 11; do LONG="$LONG{\"tool\":\"read_file\",\"args\":{\"path\":\"app.py\"}},"; done
LONG="$LONG{\"tool\":\"task_done\",\"args\":{\"summary\":\"finished after twelve steps\"}}]"
printf '%s' "$LONG" > "$WORK/long.json"
python3 "$ROOT/scripts/stub-drive.py" $((PORT + 6)) "$WORK/long.json" >>"$WORK/stub.log" 2>&1 &
LONG_PID=$!
for _ in $(seq 1 40); do curl -s -m 1 "http://127.0.0.1:$((PORT + 6))/v1/models" >/dev/null 2>&1 && break; sleep 0.25; done
printf 'go\n/quit\n' | ( cd "$PROJ" && "$CZ" chat . --drive "http://127.0.0.1:$((PORT + 6))" ) \
    >"$WORK/out.txt" 2>&1
kill $LONG_PID 2>/dev/null
grep -q 'finished after twelve steps' "$WORK/out.txt" \
    && ok "a 12-step turn completes on the default (old cap was 8)" \
    || bad "a 12-step turn completes on the default" "$(tail -3 "$WORK/out.txt")"

echo
echo "== the budget you grant is the rewind you keep"
# --max-turns 3, then /undo 999: the report must land at the cap's depth, and an explicit
# CODEZAIKU_UNDO_DEPTH must beat the inference (env is real here; a unit test cannot set it).
curl -s -m 2 "$DRIVE/reset" >/dev/null 2>&1
printf 'change it\na\na\n/undo 999\n/quit\n' \
    | ( cd "$PROJ" && git checkout -q . && CODEZAIKU_UNDO_DEPTH=1 "$CZ" chat . --drive "$DRIVE" --max-turns 30 ) \
    >"$WORK/out.txt" 2>&1
grep -q 'rewound ' "$WORK/out.txt" \
    && ok "an explicit CODEZAIKU_UNDO_DEPTH is honoured over the cap" \
    || bad "an explicit CODEZAIKU_UNDO_DEPTH is honoured over the cap" "$(grep -i undo "$WORK/out.txt" | head -2)"

echo
echo "== /undo works where git never has — the gap a real project exposed"
# t_youtubesubdownloader was not a git repository, so the git-based checkpoint silently did not
# exist exactly where an agent was editing real files for the first time. The journal replaces it:
# pre-images captured at the tool layer, no git anywhere. This project has NO .git on purpose.
NOGIT="$WORK/nogit"; mkdir -p "$NOGIT"
printf 'def f():\n    return 1\n' > "$NOGIT/app.py"
curl -s -m 2 "$DRIVE/reset" >/dev/null 2>&1
printf 'change it\na\na\n/undo 2\n/state\n/quit\n' \
    | ( cd "$NOGIT" && "$CZ" chat . --drive "$DRIVE" ) >"$WORK/out.txt" 2>"$WORK/err.txt"
grep -q 'rewound ' "$WORK/out.txt" \
    && ok "/undo rewinds without git" || bad "/undo rewinds without git" "$(grep -i undo "$WORK/out.txt" | head -2)"
grep -q 'return 1' "$NOGIT/app.py" \
    && ok "the edit is really gone from the non-git project" \
    || bad "the edit is really gone from the non-git project" "$(cat "$NOGIT/app.py")"
grep -q 'the user undid' "$WORK/out.txt" \
    && ok "the session is told about the undo" || bad "the session is told about the undo"
grep -qE 'rewound .*(edit|write|run)' "$WORK/out.txt" \
    && ok "the undo report names the steps it took back" \
    || bad "the undo report names the steps it took back" "$(grep -i rewound "$WORK/out.txt" | head -1)"

echo
echo "== 'all' at a prompt stops the asking for the session"
( cd "$PROJ" && git checkout -q . )
chat 'change it\nall\n/mode\n/quit\n'
N=$(grep -c '⏸' "$WORK/out.txt" || true)
[ "$N" = 1 ] && ok "one prompt, answered 'all', then none" \
    || bad "one prompt, answered 'all', then none" "$N prompt(s)"
grep -q '\* yolo' "$WORK/out.txt" \
    && ok "the session is now in yolo, and /mode says so" \
    || bad "the session is now in yolo, and /mode says so"
grep -q 'return 2' "$PROJ/app.py" \
    && ok "the edit went through without further questions" \
    || bad "the edit went through without further questions"

echo
echo "== /note pins a fact without spending a model turn"
rm -rf "$CODEZAIKU_CHAT_DIR"; ( cd "$PROJ" && git checkout -q . )
chat '/note the deploy target is staging-eu-3\n/note\n/state\n/quit\n'
grep -q 'noted — it rides' "$WORK/out.txt" \
    && ok "/note pins without a model turn" || bad "/note pins without a model turn"
grep -q 'staging-eu-3' "$WORK/out.txt" \
    && ok "the note is in the state the model will see" \
    || bad "the note is in the state the model will see"
printf '/note\n/quit\n' | ( cd "$PROJ" && "$CZ" chat . --drive "$DRIVE" ) >"$WORK/out.txt" 2>&1
grep -q '(no notes)' "$WORK/out.txt" || grep -q 'staging' "$WORK/out.txt" \
    && ok "notes behave across a fresh process" || bad "notes behave across a fresh process"

echo
echo "== quiet screen, complete file"
# Logs go to stderr, right for MCP — but a terminal shows both streams, so every INFO line landed
# in the middle of the conversation. The threshold sits on the CONSOLE APPENDER, never the root:
# the first cut set root to WARN and silenced the diagnostic file along with the screen.
( cd "$PROJ" && git checkout -q . )
chat 'change it\na\na\n/sessionid\n/quit\n'
# Logs live on STDERR, and the chat helper captures the streams separately — so BOTH checks here
# read err.txt. The first version grepped out.txt, where log lines can never appear: the quiet
# check passed vacuously and the loud check failed unfailably. A check that reads the wrong stream
# is worse than no check, because it certifies the thing it never looked at.
grep -qE 'INFO +(FamiliarLoop|DriveClient)' "$WORK/err.txt" \
    && bad "no INFO log lines in a chat conversation" "$(grep -m1 'INFO' "$WORK/err.txt")" \
    || ok "no INFO log lines in a chat conversation"
grep -q 'session: 2' "$WORK/out.txt" \
    && ok "/sessionid names the session" || bad "/sessionid names the session"
grep -q 'run log: ' "$WORK/out.txt" \
    && ok "/sessionid names the run log" || bad "/sessionid names the run log"
RUNLOG=$(grep -oE 'run log: .*' "$WORK/out.txt" | head -1 | cut -d' ' -f3)
[ -n "$RUNLOG" ] && grep -q 'task_done' "$RUNLOG" \
    && ok "the file got everything the screen did not" \
    || bad "the file got everything the screen did not" "$RUNLOG"
[ -n "$RUNLOG" ] && grep -q 'chat session 2' "$RUNLOG" \
    && ok "the session id is IN the log, so the two can be joined" \
    || bad "the session id is IN the log, so the two can be joined"
# /logging info mid-session brings the screen back
chat '/logging info\nchange it\na\na\n/quit\n'
grep -qE 'INFO +(FamiliarLoop|DriveClient)' "$WORK/err.txt" \
    && ok "/logging info pipes the log to the screen" \
    || bad "/logging info pipes the log to the screen"
chat '/logging nonsense\n/quit\n'
grep -q 'not a level' "$WORK/out.txt" \
    && ok "/logging rejects a level it does not know" || bad "/logging rejects a level it does not know"

echo
echo "----"
printf '  %d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
