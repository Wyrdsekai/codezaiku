#!/bin/bash
# chaos_ab2.sh <K> <api_base> <tag>  — interleaved OFF/ON A/B
K="$1"; APIBASE="$2"; TAG="$3"
cd "${CP_WORK:-/opt/codezaiku}"/chaos; . .venv/bin/activate 2>/dev/null
export AGENT_API_BASE="$APIBASE" CP_MAX_STEPS=22
CSV=""${CP_WORK:-/opt/codezaiku}"/chaos/results_${TAG}.csv"; rm -f "$CSV"
LOG=""${CP_WORK:-/opt/codezaiku}"/chaos/ab_${TAG}.log"; echo "=== $TAG K=$K base=$APIBASE $(date) ===" > "$LOG"
run () { # $1=arm
  local arm="$1"
  # ON = validate the card's CONTENT: deliver it when the signature matches (immediate), not the
  # candidate-safety rescue policy (which lands at turn 14, after the model already finishes ~turn 13).
  if [ "$arm" = ON ]; then export CP_KNOWLEDGE="${CP_WORK:-/opt/codezaiku}"/chaos/kb-es CP_PUSH_MODE=immediate;
  else unset CP_KNOWLEDGE; unset CP_PUSH_MODE; fi
  export CP_RUN_ID="${TAG}-${arm}-${2}" CP_RESULT_CSV="$CSV"
  echo "----- $CP_RUN_ID -----" >> "$LOG"
  python3 "${CP_WORK:-/opt/codezaiku}"/chaos/chaos_es.py 2>&1 | grep -E "chaos\] (RESULT|steady-state (BEFORE|AFTER)|KNOWLEDGE|turn [0-9]+ ACTION|DONE|NO-FAULT|WARNING)" >> "$LOG"
}
for i in $(seq 1 "$K"); do run OFF "$i"; run ON "$i"; done
echo "=== DONE $(date) ===" >> "$LOG"
echo "OFF PASS: $(grep -c 'OFF,PASS' "$CSV")  OFF FAIL: $(grep -c 'OFF,FAIL' "$CSV")" >> "$LOG"
echo "ON  PASS: $(grep -c 'ON,PASS' "$CSV")   ON  FAIL: $(grep -c 'ON,FAIL' "$CSV")" >> "$LOG"
