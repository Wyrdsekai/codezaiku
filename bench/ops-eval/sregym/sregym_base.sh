#!/bin/bash
# BASELINE-FIRST probe (9B, OFF-arm only, K=3): does the shipping tier reliably FAIL these faults?
# Only faults the baseline fails (and can't accidentally fix by restarts) are worth a card A/B —
# the valkey_auth lesson (9B baseline 4/5 = no headroom). All objective mitigation oracles.
echo $$ > "${CP_WORK:-/opt/codezaiku}"/sregym-base.pid
exec >> "${CP_WORK:-/opt/codezaiku}"/sregym-base.log 2>&1
export PATH="${CP_TOOLS_BIN:-/usr/local/bin}:$HOME/.local/bin:$HOME/.cargo/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/go/bin
export AGENT_API_BASE=http://localhost:8200 AGENT_API_KEY=dummy CP_MAX_STEPS=30 CP_KNOWLEDGE="" \
       JUDGE_API_BASE=http://localhost:8201 JUDGE_API_KEY=dummy
cd "${CP_WORK:-/opt/codezaiku}"/SREGym
CTX=kind-sregym
PROBS="service_dns_resolution_failure_social_network stale_coredns_config_social_network kafka_poison_pill_hol_block"
unstick_ns() {
  ns=$1
  kubectl --context $CTX get ns $ns >/dev/null 2>&1 || return 0
  kubectl --context $CTX delete ns $ns --wait=false >/dev/null 2>&1
  for i in $(seq 1 20); do kubectl --context $CTX get ns $ns >/dev/null 2>&1 || return 0; sleep 4; done
  kubectl --context $CTX get pods -n $ns -o name 2>/dev/null | while read -r p; do
    kubectl --context $CTX patch "$p" -n $ns -p '{"metadata":{"finalizers":null}}' --type=merge >/dev/null 2>&1
    kubectl --context $CTX delete "$p" -n $ns --force --grace-period=0 >/dev/null 2>&1
  done
  kubectl --context $CTX get ns $ns -o json 2>/dev/null | python3 -c 'import json,sys;d=json.load(sys.stdin);d["spec"]["finalizers"]=[];print(json.dumps(d))' > /tmp/ns.json 2>/dev/null
  kubectl --context $CTX replace --raw /api/v1/namespaces/$ns/finalize -f /tmp/ns.json >/dev/null 2>&1
  for i in $(seq 1 10); do kubectl --context $CTX get ns $ns >/dev/null 2>&1 || return 0; sleep 4; done
  return 1
}
guard() {
  memG=$(awk '/MemAvailable/{printf "%d", $2/1048576}' /proc/meminfo)
  dsk=$(df --output=pcent / | tail -1 | tr -dc 0-9)
  if [ "$memG" -lt 64 ] || [ "$dsk" -gt 85 ]; then echo "!!! GUARD ABORT: mem=${memG}G disk=${dsk}%"; exit 1; fi
  for ns in astronomy-shop social-network; do
    if ! unstick_ns $ns; then echo "!!! GUARD ABORT: ns $ns STUCK"; exit 1; fi
  done
  echo "    [guard ok: mem=${memG}G disk=${dsk}%]"
}
result() {
  f=$(ls -t results/*/codezaiku_ALL_results.csv 2>/dev/null | head -1)
  [ -z "$f" ] && { echo NO-RESULT; return; }
  mt=$(stat -c %Y "$f")
  if [ "$mt" -lt "$1" ]; then echo "NO-RESULT (no fresh CSV)"; return; fi
  python3 -c "import csv;r=list(csv.DictReader(open('$f')));print(r[-1].get('Mitigation.success','?') if r else 'NOROW')" 2>/dev/null || echo PARSE-ERR
}
for p in $PROBS; do
  for k in 1 2 3; do
    echo "@@@ [$(date +%T)] prob=$p k=$k"
    guard
    t0=$(date +%s)
    timeout 2000 uv run main.py --agent codezaiku --model openai/local --problem "$p" --agent-timeout 650 2>&1 \
      | grep -aE "\[codezaiku\]|Mitigation Result|Mitigation Succeed|Mitigation Failed|Benchmark complete|Failed to deploy" | sed "s/^/    /"
    echo "    RESULT $p k=$k -> $(result $t0)"
  done
done
echo "@@@ BASE DONE $(date +%s)"
rm -f "${CP_WORK:-/opt/codezaiku}"/sregym-base.pid
