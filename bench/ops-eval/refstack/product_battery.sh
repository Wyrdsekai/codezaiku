#!/usr/bin/env bash
# Refstack battery over the PRODUCT path (`bin/codezaiku fix`).
#
# WHY THIS EXISTS SEPARATELY FROM remediate.py: that driver carries its OWN python card matcher and
# does not execute the Java card matching, authority ladder, ActionLedger or R3 code at all. Its R4
# CSVs are therefore NOT a baseline for product changes — comparing the two is comparing instruments.
#
# THE ORACLE TRAP THIS ENCODES (measured 2026-08-18): `fixed` (app health green after the run) read
# TRUE in 18/18 runs of redis-auth while `harness_verified` ranged 20%-100%. The redis fault is a
# RUNTIME `CONFIG SET requirepass`, which ANY container restart clears -- including R3's own rollback.
# So a FAILED run's own rollback satisfies the metric. A metric a failure can satisfy measures nothing.
#
#   * `harness_verified` is the PRIMARY column -- the closed-loop check, run BEFORE any rollback.
#   * `fixed` is reported but is only trustworthy for a fault that SURVIVES a restart.
#   * opensearch-writeblock is the clean fixture: `index.blocks.write` is cluster state and was
#     verified to survive `docker restart` (redis-auth was verified NOT to).
#
# THE STRUCTURAL CATCH: no refstack fixture is BOTH clean-oracle and reaches GUARDED on the shipping
# 9B by itself. redis-auth has the validated card (so it acts) but a contaminated oracle; opensearch
# has the clean oracle but a CANDIDATE card, and the ladder correctly caps a candidate at PROPOSE --
# measured, 5/5 runs `acted=false`. That is the ladder working, not a bug. TRIAL=on uses the
# measurement lane built for exactly this (`CODEZAIKU_OPS_TRIAL`), which auto-applies a candidate
# under the full guard stack so it can be A/B'd. Production never does this.
#
# Usage: FAULT=opensearch|redis K=8 ARM=name FILTER=off TRIAL=on ./product_battery.sh
set -uo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
OUT=${OUT:-$REPO/run/battery-$(date +%s)}
K=${K:-5}; ARM=${ARM:-baseline}; FAULT=${FAULT:-opensearch}
DRIVE=${CODEZAIKU_DRIVE:-http://localhost:8210}
mkdir -p "$OUT"

case "$FAULT" in
  redis)
    inject() { docker exec refstack-redis-1 redis-cli CONFIG SET requirepass badpass123 >/dev/null 2>&1; }
    heal()   { docker exec refstack-redis-1 redis-cli -a badpass123 CONFIG SET requirepass '' >/dev/null 2>&1
               docker exec refstack-redis-1 redis-cli CONFIG SET requirepass '' >/dev/null 2>&1; sleep 3; }
    green()  { curl -s -m8 localhost:28080/health | grep -q '"redis":"ok"'; }
    ORACLE=contaminated ;;
  opensearch)
    inject() { curl -s -m10 -XPUT localhost:29200/docs/_settings -H content-type:application/json \
                 -d '{"index.blocks.write":true}' >/dev/null 2>&1; }
    heal()   { curl -s -m10 -XPUT localhost:29200/docs/_settings -H content-type:application/json \
                 -d '{"index.blocks.write":false}' >/dev/null 2>&1; sleep 3; }
    green()  { curl -s -m8 localhost:28080/health | grep -q '"opensearch":"ok"'; }
    ORACLE=clean ;;
  *) echo "unknown FAULT=$FAULT"; exit 2 ;;
esac

cd "$REPO"
echo "# fault=$FAULT oracle=$ORACLE arm=$ARM K=$K filter=${FILTER:-off} trial=${TRIAL:-off}" | tee -a "$OUT/results.jsonl"
for k in $(seq 1 "$K"); do
  heal
  green || { echo "k=$k PRECONDITION FAIL: stack not green before inject"; continue; }
  inject; sleep 12
  green && { echo "k=$k FAULT DID NOT REPRODUCE — skipping"; heal; continue; }

  log="$OUT/${ARM}-${FAULT}-k$k.log"; start=$(date +%s)
  CODEZAIKU_DRIVE="$DRIVE" \
  CODEZAIKU_OPS_KNOWLEDGE="$REPO/ops-knowledge" \
  CODEZAIKU_OPS_CARD_APPLICABILITY="${FILTER:-off}" \
  CODEZAIKU_OPS_TRIAL="${TRIAL:-off}" \
    timeout 900 bin/codezaiku fix refstack guarded > "$log" 2>&1
  rc=$?; elapsed=$(( $(date +%s) - start ))

  sleep 8
  green && post_green=true || post_green=false
  ver=$(grep -oE 'harness_verified: (true|false|null)' "$log" | tail -1 | awk '{print $2}')
  card=$(grep -oE 'card=[a-z0-9._-]+' "$log" | head -1 | cut -d= -f2)
  rb=$(grep -c 'R3 rollback applied' "$log")
  rbfail=$(grep -c 'R3 rollback FAILED' "$log")
  printf '{"arm":"%s","fault":"%s","oracle":"%s","k":%d,"rc":%d,"sec":%d,"verified":"%s","post_green":%s,"card":"%s","rollback_ok":%d,"rollback_failed":%d}\n' \
    "$ARM" "$FAULT" "$ORACLE" "$k" "$rc" "$elapsed" "${ver:-none}" "$post_green" "${card:-none}" "$rb" "$rbfail" \
    | tee -a "$OUT/results.jsonl"
  heal
done
echo "OUT=$OUT"
