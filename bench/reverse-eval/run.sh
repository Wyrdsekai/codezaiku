#!/usr/bin/env bash
# The reverse-eval loop: a repo, backwards and forwards again.
#
#   bench/reverse-eval/run.sh <repo-dir> [drive-url]
#
# 1. `codezaiku reverse` turns the real repo into the one prompt that would have vibe-coded it.
# 2. A FRESH directory + `codezaiku run --text @prompt` builds that prompt from scratch.
# 3. The result is graded from ARTIFACTS: the run's own JSON verdict, and a comparison report
#    against the real repo (language, file counts, tree overlap).
#
# WHAT THIS IS AND IS NOT. It is a self-grading greenfield eval in the old G1 shape with no
# hand-authored fixtures: any real repo becomes a test case, and the grade cannot be gamed by the
# harness because the reference is a project that already exists. It is NOT a similarity contest —
# a regeneration is a different program that answers the same prompt, so file-identity is neither
# expected nor scored. The verdict that matters is the run's own: did it finish, do its tests pass.
# The comparison report exists for a HUMAN reading the artifacts, and the artifacts are kept.
#
# One honest caveat, stated where it cannot be missed: the reverse prompt is only as good as the
# repo's own self-description. Measured on first use — a repo with no README reversed into a prompt
# about directory names instead of intent, and its regeneration will faithfully answer that worse
# question. The instrument shows the input's quality; it does not launder it.
set -u
prev=""

#   run.sh <repo-dir> [drive-url] [--prompt FILE]
#
# --prompt FILE skips the reverse step and regenerates from FILE instead. That turns one script
# into a two-arm experiment: arm A regenerates from OUR synthesized prompt, arm B from a
# ground-truth prompt the author actually wrote (the operator kept YTPROMPT.md for exactly this repo).
# Same loop, same grading — the delta between the two reports IS the reverse step's quality,
# measured instead of guessed.
REPO="${1:?usage: run.sh <repo-dir> [drive-url] [--prompt FILE]}"
DRIVE="${2:-${CODEZAIKU_DRIVE:-http://127.0.0.1:8200}}"
PROMPT_FILE=""
for i in "$@"; do
    case "$prev" in --prompt) PROMPT_FILE="$i";; esac
    prev="$i"
done
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CZ="${CODEZAIKU_BIN:-$ROOT/bin/codezaiku}"
REPO="$(cd "$REPO" && pwd)"

curl -s -m 10 "$DRIVE/v1/models" >/dev/null 2>&1 \
    || { echo "no drive at $DRIVE" >&2; exit 2; }

OUT="$(mktemp -d "${TMPDIR:-/tmp}/cz-reverse-eval.XXXXXX")"
WORK="$OUT/regen"; mkdir -p "$WORK"
( cd "$WORK" && git init -q && git commit -q --allow-empty -m start )

if [ -n "$PROMPT_FILE" ]; then
    echo "== 1. prompt supplied (reverse step skipped): $PROMPT_FILE"
    cp "$PROMPT_FILE" "$OUT/prompt.txt" || exit 2
    head -3 "$OUT/prompt.txt" | sed 's/^/    /'
else
    echo "== 1. reverse: $REPO"
    "$CZ" reverse "$REPO" --drive "$DRIVE" > "$OUT/prompt.txt" 2>"$OUT/reverse.err"
    [ -s "$OUT/prompt.txt" ] || { echo "reverse produced nothing:"; cat "$OUT/reverse.err"; exit 1; }
    sed 's/^/    /' "$OUT/prompt.txt"
fi

echo
echo "== 2. regenerate from the prompt (fresh directory, real loop)"
( cd "$WORK" && CODEZAIKU_DRIVE="$DRIVE" "$CZ" run --text "@$OUT/prompt.txt" \
    --output-format json --task-id reverse-eval ) > "$OUT/run.json" 2>"$OUT/run.err"
RC=$?

echo
echo "== 3. the verdict, from artifacts"
python3 - "$OUT/run.json" "$REPO" "$WORK" "$OUT/REPORT.md" <<'PY'
import json, sys, collections, pathlib

run_json, repo, work, report = sys.argv[1:5]
d = json.load(open(run_json))

def tree(root):
    root = pathlib.Path(root)
    exts, files = collections.Counter(), []
    for p in root.rglob("*"):
        rel = p.relative_to(root)
        s = str(rel)
        if any(part in (".git", "node_modules", "build", "__pycache__", ".codezaiku")
               for part in rel.parts):
            continue
        if p.is_file():
            files.append(s)
            exts[p.suffix or "(none)"] += 1
    return exts, files

r_exts, r_files = tree(repo)
g_exts, g_files = tree(work)

lines = []
lines.append("# Reverse-eval report\n")
lines.append(f"- real repo: `{repo}` — {len(r_files)} files, top exts "
             f"{dict(r_exts.most_common(4))}")
lines.append(f"- regenerated: `{work}` — {len(g_files)} files, top exts "
             f"{dict(g_exts.most_common(4))}")
lines.append(f"- run verdict: status={d.get('status')} turns={d.get('turns')} "
             f"testsPassed={d.get('testsPassed')} testsFailed={d.get('testsFailed')}")
lines.append(f"- summary: {(d.get('summary') or '')[:300]}")
# Language agreement is the one comparison that is fair to score: a Java project that reversed
# into a Python one means the PROMPT lost the stack, which is a reverse-step defect. Judged on
# CODE extensions only — the first stub run declared ".md" the top language of a two-file repo
# and reported a mismatch about documentation.
CODE = {".py", ".java", ".ts", ".js", ".go", ".rs", ".rb", ".kt", ".c", ".cc", ".cpp", ".cs",
        ".sh", ".php", ".swift", ".scala", ".gradle", ".kts"}
def top_code(exts):
    for ext, _ in exts.most_common():
        if ext in CODE:
            return ext
    return "?"
top_r = top_code(r_exts)
top_g = top_code(g_exts)
lines.append(f"- language agreement: real={top_r} regen={top_g} "
             f"{'OK' if top_r == top_g else 'MISMATCH — the prompt lost the stack'}")
open(report, "w").write("\n".join(lines) + "\n")
print("\n".join("  " + l for l in lines))

ok = d.get("status") == "success"
sys.exit(0 if ok else 1)
PY
VERDICT=$?

echo
echo "----"
echo "  artifacts kept in $OUT  (prompt.txt · regen/ · run.json · REPORT.md)"
echo "  (read them — the report is for a human; the exit code only carries the run's own verdict)"
exit $VERDICT
