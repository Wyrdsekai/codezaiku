#!/bin/bash
# chaos_scn_ab.sh <scenario> <K> <api_base> <kbdir> <tag>  — interleaved OFF/ON
SCN="$1"; K="$2"; APIBASE="$3"; KB="$4"; TAG="$5"
cd "${CP_WORK:-/opt/codezaiku}"/chaos; . .venv/bin/activate 2>/dev/null
export CP_SCENARIO="$SCN" AGENT_API_BASE="$APIBASE" CP_MAX_STEPS=22
CSV=""${CP_WORK:-/opt/codezaiku}"/chaos/results_${TAG}.csv"; rm -f "$CSV"; export CP_RESULT_CSV="$CSV"
LOG=""${CP_WORK:-/opt/codezaiku}"/chaos/${TAG}.log"; echo "=== $TAG scn=$SCN K=$K base=$APIBASE $(date) ===" > "$LOG"
run(){ local arm="$1" i="$2"
  if [ "$arm" = ON ]; then export CP_KNOWLEDGE="$KB" CP_PUSH_MODE=immediate; else unset CP_KNOWLEDGE; unset CP_PUSH_MODE; fi
  export CP_RUN_ID="${TAG}-${arm}-${i}"
  python3 "${CP_WORK:-/opt/codezaiku}"/chaos/chaos_core.py 2>&1 | grep -E "chaos\] (RESULT|steady-state AFTER|KNOWLEDGE|fault applied|DONE)" >> "$LOG"; }
for i in $(seq 1 "$K"); do run OFF "$i"; run ON "$i"; done
echo "OFF PASS=$(grep -c ',OFF,PASS' "$CSV")/$K  ON PASS=$(grep -c ',ON,PASS' "$CSV")/$K" >> "$LOG"
