#!/bin/bash
# Reproducible acceptance test for cp-refstack: end-to-end RAG + localization signal.
B=${REFSTACK_URL:-http://localhost:28080}
fail(){ echo "SMOKE FAIL: $1"; exit 1; }
# 1) end-to-end RAG through the full engine chain
curl -s -m15 -XPOST $B/ingest -H content-type:application/json \
  -d '{"id":"smoke-1","text":"The capital of Testland is Faultville."}' >/dev/null || fail "ingest"
sleep 12
ans=$(curl -s -m60 -XPOST $B/query -H content-type:application/json \
  -d '{"q":"What is the capital of Testland?"}')
echo "$ans" | grep -qi faultville && echo "PASS  end-to-end RAG ($(echo "$ans"|grep -o '"sources":\[[^]]*\]'))" \
  || fail "end-to-end query did not return the canary: $ans"
# 2) localization signal: a fault in one engine is NAMED by the app, others stay ok, query breaks
docker stop refstack-qdrant-1 >/dev/null; sleep 4
h=$(curl -s -m10 $B/health)
echo "$h" | grep -q '"qdrant":"down' || { docker start refstack-qdrant-1 >/dev/null; fail "health did not name qdrant: $h"; }
echo "$h" | grep -q '"status":"degraded"' && echo "PASS  localization signal (app names qdrant:down, others ok)"
docker start refstack-qdrant-1 >/dev/null; sleep 6
echo "SMOKE PASS"
