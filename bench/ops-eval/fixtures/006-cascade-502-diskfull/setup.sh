#!/usr/bin/env bash
# TRUE ROOT CAUSE (two hops down): /var/lib/appdata is FULL → the app can't write its startup lock → the
# app exits → nginx has no upstream → nginx returns 502. The 502 and "app is down" are SYMPTOMS; a fix that
# only restarts the app will just crash again. The real fix is to free the disk.
# TRAP: the two intermediate symptoms (502 at the edge, app not running) look like the answer.
set -u

mkdir -p /var/lib/appdata /var/log/app

# 1) Fill the data tmpfs so the app's startup write fails.
dd if=/dev/zero of=/var/lib/appdata/filler bs=1M count=128 >/dev/null 2>&1 || true

# 2) Start nginx (it comes up fine; its upstream is what's broken).
nginx >/dev/null 2>&1 || true

# 3) Start the app — it will fail to write the lock and exit, logging the fatal ENOSPC.
python3 -u /opt/app/app.py 2>> /var/log/app/app.log &
sleep 1

# 4) Drive one request through nginx so the 502 + upstream-connect error land in nginx's log.
curl -s -o /dev/null http://127.0.0.1:80/ || true
sleep 0.3

echo "fault injected: appdata full → app exits on startup → :8080 down → nginx 502 at :80"
