#!/usr/bin/env bash
# TRUE ROOT CAUSE (THREE hops down): the cache's config /etc/cache/cache.conf has an invalid directive →
# the cache process exits on startup → :6379 never comes up → the app can't reach the cache and exits →
# :8080 down → nginx returns 502. Every intermediate hop (502, app down, cache down) is a SYMPTOM; the
# root is the bad cache config. A fix that restarts anything without fixing the config will not hold.
set -u
mkdir -p /var/log/app /var/log/svc /etc/cache

# 1) Write a BAD cache config (a bare line that is not KEY=VALUE → an invalid directive).
cat > /etc/cache/cache.conf <<'EOF'
# cache configuration
maxmemory=64mb
ENABLE_TURBO_MODE
EOF

# 2) Start nginx (fine), then the cache (crashes on the bad config), then the app (can't reach cache, exits).
nginx >/dev/null 2>&1 || true
python3 -u /opt/svc/cache.py 2>> /var/log/svc/cache.log &
sleep 0.5
python3 -u /opt/app/app.py 2>> /var/log/app/app.log &
sleep 1

# 3) Drive one request so nginx logs the 502 + upstream-connect failure.
curl -s -o /dev/null http://127.0.0.1:80/ || true
sleep 0.3

echo "fault injected: cache bad config -> cache down :6379 -> app down :8080 -> nginx 502 :80"
