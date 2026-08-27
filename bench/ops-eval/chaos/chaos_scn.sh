#!/bin/bash
# chaos_scn.sh <scenario> <arm OFF|ON> <K> <api_base> <kbdir> <tag>
SCN="$1"; ARM="$2"; K="$3"; APIBASE="$4"; KB="$5"; TAG="$6"
cd "${CP_WORK:-/opt/codezaiku}"/chaos; . .venv/bin/activate 2>/dev/null
export CP_SCENARIO="$SCN" AGENT_API_BASE="$APIBASE" CP_MAX_STEPS=22
[ "$ARM" = ON ] && export CP_KNOWLEDGE="$KB" CP_PUSH_MODE=immediate || { unset CP_KNOWLEDGE; unset CP_PUSH_MODE; }
CSV=""${CP_WORK:-/opt/codezaiku}"/chaos/results_${TAG}.csv"; export CP_RESULT_CSV="$CSV"
LOG=""${CP_WORK:-/opt/codezaiku}"/chaos/${TAG}.log"; echo "=== $TAG scn=$SCN arm=$ARM K=$K base=$APIBASE $(date) ===" >> "$LOG"
for i in $(seq 1 "$K"); do
  export CP_RUN_ID="${TAG}-${ARM}-${i}"
  python3 "${CP_WORK:-/opt/codezaiku}"/chaos/chaos_core.py 2>&1 | grep -E "chaos\] (RESULT|steady-state AFTER|KNOWLEDGE|fault applied|DONE)" >> "$LOG"
done
echo "$ARM PASS=$(grep -c ",$ARM,PASS" "$CSV" 2>/dev/null)/$K" >> "$LOG"
