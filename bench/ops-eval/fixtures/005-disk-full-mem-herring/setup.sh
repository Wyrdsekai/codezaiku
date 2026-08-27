#!/usr/bin/env bash
# TRUE ROOT CAUSE: the app's data filesystem /var/lib/appdata is FULL, so writes fail with ENOSPC and the
# app returns 500. RED HERRING: a memory-hungry process + a note about memory, to lure a diagnosis into
# blaming RAM instead of disk.
set -u

mkdir -p /var/lib/appdata /var/log/app

# 1) Fill /var/lib/appdata (a bounded tmpfs mounted via run_args) until no space remains (dd stops at
#    ENOSPC — count exceeds the mount size on purpose).
dd if=/dev/zero of=/var/lib/appdata/filler bs=1M count=128 >/dev/null 2>&1 || true

# 2) Start the app; drive one request so the ENOSPC error is logged (the TRUE evidence).
python3 -u /opt/app/app.py 2>> /var/log/app/app.log &
for _ in $(seq 1 30); do curl -s -o /dev/null http://127.0.0.1:8080/ && break; sleep 0.2; done
curl -s -o /dev/null http://127.0.0.1:8080/ || true
sleep 0.3

# 3) RED HERRING: a process holding a few hundred MB + a memory note.
python3 -c "x = bytearray(300*1024*1024); import time; time.sleep(10**9)" >/dev/null 2>&1 &
echo "$!" > /tmp/herring.pid
echo "$(date) WARN memory usage climbing on worker" >> /var/log/app/app.log

echo "fault injected: /var/lib/appdata full; app 500s on write; mem herring pid=$(cat /tmp/herring.pid)"
