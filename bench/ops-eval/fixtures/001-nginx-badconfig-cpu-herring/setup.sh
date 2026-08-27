#!/usr/bin/env bash
# Inject the fault into a running container of this scenario's image. Idempotent.
#
# TRUE ROOT CAUSE: an invalid nginx config (a directive typo) → nginx fails to (re)start → the site is down.
# RED HERRING: a CPU-pegging process + a "high load" note, to lure a shallow diagnosis into blaming
# resource exhaustion instead of the config.
set -u

# 1) Corrupt the nginx site config with a REAL error: a valid directive placed in the wrong context
#    (worker_connections belongs in the events{} block, not a server{} block) — a realistic copy-paste
#    mistake that makes `nginx -t` fail hard: "worker_connections directive is not allowed here".
cat > /etc/nginx/sites-available/default <<'EOF'
server {
    listen 80 default_server;
    root /var/www/html;
    index index.html;
    worker_connections 1024;
}
EOF

# 2) Make sure nginx is DOWN (it can't start with the broken config anyway; kill any stray master).
nginx -s stop >/dev/null 2>&1 || true
pkill -x nginx >/dev/null 2>&1 || true

# 3) RED HERRING: peg a CPU core with a busy loop so `top`/`ps` show high CPU.
( while :; do :; done ) >/dev/null 2>&1 &
echo "$!" > /tmp/herring.pid

# 4) A plausible-but-irrelevant scary log line (the herring's paper trail).
mkdir -p /var/log
echo "$(date) WARN high CPU utilization detected on worker" >> /var/log/app.log

echo "fault injected: nginx config broken; nginx down; CPU herring pid=$(cat /tmp/herring.pid)"
