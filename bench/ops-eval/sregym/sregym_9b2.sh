#!/bin/bash
# 9B CONFIRM v2: valkey_auth OFF vs ON-immediate, K=5 interleaved, 9B (:8200).
# Post-crash hardening: flagd limits raised in local chart (no more OOM crashloop), ONE cluster only,
# PRE-FLIGHT GUARD before every run (MemAvailable>64G, disk<85%, no leftover astronomy ns), panic
# sysctls + watchdog on host. Aborts loudly instead of stressing a sick box.
echo $$ > "${CP_WORK:-/opt/codezaiku}"/sregym-9b.pid
exec >> "${CP_WORK:-/opt/codezaiku}"/sregym-9b.log 2>&1
export PATH="${CP_TOOLS_BIN:-/usr/local/bin}:$HOME/.local/bin:$HOME/.cargo/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/go/bin
export AGENT_API_BASE=http://localhost:8200 AGENT_API_KEY=dummy CP_MAX_STEPS=30
cd "${CP_WORK:-/opt/codezaiku}"/SREGym
guard() {
  memG=$(awk '/MemAvailable/{printf "%d", $2/1048576}' /proc/meminfo)
  dsk=$(df --output=pcent / | tail -1 | tr -dc 0-9)
  if [ "$memG" -lt 64 ] || [ "$dsk" -gt 85 ]; then
    echo "!!! GUARD ABORT: MemAvailable=${memG}G disk=${dsk}% — refusing to launch"; exit 1
  fi
  kubectl --context kind-sregym delete ns astronomy-shop --wait=false >/dev/null 2>&1
  for i in $(seq 1 30); do kubectl --context kind-sregym get ns astronomy-shop >/dev/null 2>&1 || break; sleep 4; done
  echo "    [guard ok: mem=${memG}G disk=${dsk}%]"
}
result() {
  f=$(ls -t results/*/codezaiku_ALL_results.csv 2>/dev/null | head -1)
  python3 -c "import csv;r=list(csv.DictReader(open('$f')));print(r[-1].get('Mitigation.success','?') if r else 'NOROW')" 2>/dev/null || echo PARSE-ERR
}
for k in 1 2 3 4 5; do
  for arm in off on; do
    if [ $arm = on ]; then export CP_KNOWLEDGE="${CP_WORK:-/opt/codezaiku}"/ops-knowledge CP_PUSH_MODE=immediate; else export CP_KNOWLEDGE="" CP_PUSH_MODE=card; fi
    echo "@@@ [$(date +%T)] arm=$arm k=$k"
    guard
    timeout 1100 uv run main.py --agent codezaiku --model openai/local --problem valkey_auth_disruption --agent-timeout 650 2>&1 \
      | grep -aE "KNOWLEDGE (PUSHED|HELD)|Mitigation Result|Mitigation Succeed|Mitigation Failed|flagd.*(OOM|CrashLoop)|Benchmark complete" | sed "s/^/    /"
    echo "    RESULT arm=$arm k=$k -> $(result)"
  done
done
echo "@@@ 9B DONE $(date +%s)"
rm -f "${CP_WORK:-/opt/codezaiku}"/sregym-9b.pid
