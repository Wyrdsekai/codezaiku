#!/bin/bash
# Kafka poison-pill A/B (9B): 2 OFF top-ups (baseline already 0/3) + 5 ON-immediate. ON = full 66-card
# library, CP_PUSH_MODE=immediate override, two-shot scan (lag marker expected on the rescan: measured
# max-lag 18 at scan-1, threshold 20, unbounded growth).
echo $$ > "${CP_WORK:-/opt/codezaiku}"/sregym-kab.pid
exec >> "${CP_WORK:-/opt/codezaiku}"/sregym-kab.log 2>&1
export PATH="${CP_TOOLS_BIN:-/usr/local/bin}:$HOME/.local/bin:$HOME/.cargo/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/go/bin
export AGENT_API_BASE=http://localhost:8200 AGENT_API_KEY=dummy CP_MAX_STEPS=30 \
       JUDGE_API_BASE=http://localhost:8201 JUDGE_API_KEY=dummy
cd "${CP_WORK:-/opt/codezaiku}"/SREGym
CTX=kind-sregym
clean_ns() {
  for ns in astronomy-shop social-network; do kubectl --context $CTX delete ns $ns --wait=false >/dev/null 2>&1; done
  for i in $(seq 1 25); do kubectl --context $CTX get ns 2>/dev/null | grep -qE "astronomy-shop|social-network" || break; sleep 4; done
}
guard() {
  memG=$(awk '/MemAvailable/{printf "%d", $2/1048576}' /proc/meminfo)
  dsk=$(df --output=pcent / | tail -1 | tr -dc 0-9)
  if [ "$memG" -lt 64 ] || [ "$dsk" -gt 85 ]; then echo "!!! GUARD ABORT: mem=${memG}G disk=${dsk}%"; exit 1; fi
  clean_ns
  echo "    [guard ok: mem=${memG}G disk=${dsk}%]"
}
result() {
  f=$(ls -t results/*/codezaiku_ALL_results.csv 2>/dev/null | head -1)
  [ -z "$f" ] && { echo NO-RESULT; return; }
  mt=$(stat -c %Y "$f"); [ "$mt" -lt "$1" ] && { echo "NO-RESULT (no fresh CSV)"; return; }
  python3 -c "import csv;r=list(csv.DictReader(open('$f')));print(r[-1].get('Mitigation.success','?') if r else 'NOROW')" 2>/dev/null || echo PARSE-ERR
}
run_one() {
  arm=$1; k=$2
  if [ $arm = on ]; then export CP_KNOWLEDGE="${CP_WORK:-/opt/codezaiku}"/ops-knowledge CP_PUSH_MODE=immediate; else export CP_KNOWLEDGE="" CP_PUSH_MODE=card; fi
  echo "@@@ [$(date +%T)] arm=$arm k=$k"
  guard
  t0=$(date +%s)
  timeout 2000 uv run main.py --agent codezaiku --model openai/local --problem kafka_poison_pill_hol_block --agent-timeout 650 2>&1 \
    | grep -aE "\[codezaiku\]|Mitigation Result|Benchmark complete|Failed to deploy" | sed "s/^/    /"
  echo "    RESULT arm=$arm k=$k -> $(result $t0)"
}
run_one off 4
run_one on 1
run_one off 5
run_one on 2
run_one on 3
run_one on 4
run_one on 5
echo "@@@ KAB DONE $(date +%s)"
rm -f "${CP_WORK:-/opt/codezaiku}"/sregym-kab.pid
