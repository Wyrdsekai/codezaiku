#!/bin/bash
# SIGPROBE: one OFF run per confirmed target with CP_LOG_DUMP — captures exactly what the deferred
# turn-5 scan sees under each live fault, to ground the cards' signature strings (generic, docs-level;
# the dump only confirms the trigger fires — never card content from the oracle).
echo $$ > "${CP_WORK:-/opt/codezaiku}"/sregym-sig.pid
exec >> "${CP_WORK:-/opt/codezaiku}"/sregym-sig.log 2>&1
export PATH="${CP_TOOLS_BIN:-/usr/local/bin}:$HOME/.local/bin:$HOME/.cargo/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/go/bin
export AGENT_API_BASE=http://localhost:8200 AGENT_API_KEY=dummy CP_MAX_STEPS=30 CP_KNOWLEDGE="" \
       JUDGE_API_BASE=http://localhost:8201 JUDGE_API_KEY=dummy
cd "${CP_WORK:-/opt/codezaiku}"/SREGym
CTX=kind-sregym
clean_ns() {
  for ns in astronomy-shop social-network; do
    kubectl --context $CTX delete ns $ns --wait=false >/dev/null 2>&1
  done
  for i in $(seq 1 25); do
    kubectl --context $CTX get ns 2>/dev/null | grep -qE "astronomy-shop|social-network" || break; sleep 4
  done
}
for p in service_dns_resolution_failure_social_network stale_coredns_config_social_network kafka_poison_pill_hol_block; do
  echo "@@@ [$(date +%T)] sigprobe $p"
  clean_ns
  export CP_LOG_DUMP="${CP_WORK:-/opt/codezaiku}"/sregym-sigprobe-$p.txt
  timeout 2000 uv run main.py --agent codezaiku --model openai/local --problem "$p" --agent-timeout 650 2>&1 \
    | grep -aE "LOG_DUMP|Mitigation Result|Benchmark complete" | sed "s/^/    /"
done
clean_ns
echo "@@@ SIG DONE $(date +%s)"
rm -f "${CP_WORK:-/opt/codezaiku}"/sregym-sig.pid
