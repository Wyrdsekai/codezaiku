#!/bin/bash
# One-command, idempotent bringup of cp-refstack. Run from anywhere.
set -e
cd "$(dirname "$0")"
echo "[refstack] docker compose up --build ..."
docker compose up -d --build
echo "[refstack] waiting for ollama, pulling models ..."
until docker exec refstack-ollama-1 ollama list >/dev/null 2>&1; do sleep 3; done
docker exec refstack-ollama-1 ollama pull nomic-embed-text >/dev/null 2>&1 || true
docker exec refstack-ollama-1 ollama pull qwen2.5:0.5b   >/dev/null 2>&1 || true
echo "[refstack] waiting for app health ..."
for i in $(seq 1 40); do
  s=$(curl -s -m5 http://localhost:28080/health 2>/dev/null | grep -o '"status":"[a-z]*"' | head -1)
  [ "$s" = '"status":"ok"' ] && { echo "[refstack] UP + healthy"; exit 0; }
  sleep 5
done
echo "[refstack] WARNING: not healthy after wait"; curl -s http://localhost:28080/health; exit 1
