#!/bin/bash
# DNS-family A/B (9B): both coredns-config faults, OFF vs ON-immediate, K=5 interleaved.
# ON arm = the FULL 65-card library (real-product test: the right card must win among 65 via
# match-gating + signature), CP_PUSH_MODE=immediate override (card ships rescue+candidate per safety
# lint), CP_SIG_SCAN_TURN=1 (SREGym pre-runs traffic; the 9B early-quits so turn-5 is too late).
echo $$ > "${CP_WORK:-/opt/codezaiku}"/sregym-dnsab.pid
exec >> "${CP_WORK:-/opt/codezaiku}"/sregym-dnsab.log 2>&1
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
for k in 1 2 3 4 5; do
  for p in service_dns_resolution_failure_social_network stale_coredns_config_social_network; do
    for arm in off on; do
      if [ $arm = on ]; then export CP_KNOWLEDGE="${CP_WORK:-/opt/codezaiku}"/ops-knowledge CP_PUSH_MODE=immediate CP_SIG_SCAN_TURN=1; else export CP_KNOWLEDGE="" CP_PUSH_MODE=card CP_SIG_SCAN_TURN=5; fi
      echo "@@@ [$(date +%T)] prob=$p arm=$arm k=$k"
      guard
      t0=$(date +%s)
      timeout 2000 uv run main.py --agent codezaiku --model openai/local --problem "$p" --agent-timeout 650 2>&1 \
        | grep -aE "\[codezaiku\]|Mitigation Result|Benchmark complete|Failed to deploy" | sed "s/^/    /"
      echo "    RESULT $p $arm k=$k -> $(result $t0)"
    done
  done
done
echo "@@@ DNSAB DONE $(date +%s)"
rm -f "${CP_WORK:-/opt/codezaiku}"/sregym-dnsab.pid
