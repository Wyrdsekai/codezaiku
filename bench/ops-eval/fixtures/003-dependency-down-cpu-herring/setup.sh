#!/usr/bin/env bash
# TRUE ROOT CAUSE: the app's database dependency (postgres on 127.0.0.1:5432) is not running, so the app
# returns 500 on every request. The app process itself is HEALTHY (up, listening). RED HERRING: a
# CPU-pegging process + a note about load, to lure a diagnosis into blaming the app/host instead of the
# missing dependency.
set -u

# 1) Start the app (it will be up and listening on :8080, but 500 because the DB is down). Run UNBUFFERED
#    (-u) so stderr — the DB-connect error — flushes to the log immediately instead of block-buffering.
mkdir -p /var/log/app
python3 -u /opt/app/app.py 2>> /var/log/app/app.log &
sleep 0.5

# 2) Wait for the app to actually be listening, THEN fire failing requests so the log reliably carries
#    the DB-connect error (the TRUE evidence). curl exits 0 on a 500 (it connected), so this both waits
#    for readiness and triggers the error logging.
for _ in $(seq 1 30); do
  curl -s -o /dev/null http://127.0.0.1:8080/ && break
  sleep 0.2
done
curl -s -o /dev/null http://127.0.0.1:8080/ || true
curl -s -o /dev/null http://127.0.0.1:8080/health || true
sleep 0.3   # let stderr flush to the log

# 3) RED HERRING: peg a CPU core.
( while :; do :; done ) >/dev/null 2>&1 &
echo "$!" > /tmp/herring.pid

echo "fault injected: app up on :8080 but DB :5432 down; CPU herring pid=$(cat /tmp/herring.pid)"
