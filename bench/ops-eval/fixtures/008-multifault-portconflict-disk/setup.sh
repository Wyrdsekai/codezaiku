#!/usr/bin/env bash
# TWO real faults, ONE causal:
#  - CAUSAL: a rogue process already holds :8080, so the real app cannot bind and exits → the app is down.
#  - NON-CAUSAL (real): /var/lib/data is ~92% full. Genuinely a problem to watch, but NOT why the app is
#    down (the app doesn't touch that disk on startup). Blaming the disk for the outage is a misattribution.
set -u
mkdir -p /var/log/app /var/lib/data

# 1) Rogue squats on :8080.
python3 -c "import socket,time,os
s=socket.socket(); s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
s.bind(('0.0.0.0',8080)); s.listen(16)
open('/tmp/rogue.pid','w').write(str(os.getpid()))
time.sleep(10**9)" &
sleep 0.5

# 2) The real app tries to start, fails to bind :8080, logs the fatal error, exits.
python3 -u /opt/app/app.py 2>> /var/log/app/app.log &
sleep 0.5

# 3) NON-CAUSAL secondary: fill /var/lib/data to ~92% (a bounded tmpfs via run_args).
dd if=/dev/zero of=/var/lib/data/filler bs=1M count=59 >/dev/null 2>&1 || true

echo "fault injected: rogue holds :8080 (causal); /var/lib/data ~92% full (real but non-causal)"
