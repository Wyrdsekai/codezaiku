#!/usr/bin/env bash
# TRUE ROOT CAUSE: the TLS certificate nginx serves on :443 is EXPIRED (minted with validity entirely in
# the past), so HTTPS clients reject it. nginx itself loads and runs fine — expiry is a client-side check.
# RED HERRING: a CPU-pegging process + a note about load, to lure a diagnosis into blaming performance.
set -u

# 1) Mint a cert whose validity is entirely in the past (faketime — Debian's openssl 3.0 has no
#    -not_after). notBefore 2024-01-01, notAfter ~2024-01-06 → long expired.
faketime '2024-01-01 00:00:00' openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout /etc/nginx/ssl/key.pem -out /etc/nginx/ssl/cert.pem \
    -days 5 -subj '/CN=mysite.local' >/dev/null 2>&1

# 2) Add an HTTPS server block using that (expired) cert.
cat > /etc/nginx/sites-available/default <<'EOF'
server {
    listen 80 default_server;
    root /var/www/html;
    index index.html;
}
server {
    listen 443 ssl;
    server_name mysite.local;
    ssl_certificate     /etc/nginx/ssl/cert.pem;
    ssl_certificate_key /etc/nginx/ssl/key.pem;
    root /var/www/html;
    index index.html;
}
EOF

# 3) Start nginx (config is valid; the cert merely expired). Reload if already running.
nginx -s stop >/dev/null 2>&1 || true
nginx >/dev/null 2>&1 || true

# 4) RED HERRING: peg a CPU core + a plausible-but-irrelevant note.
( while :; do :; done ) >/dev/null 2>&1 &
echo "$!" > /tmp/herring.pid
mkdir -p /var/log
echo "$(date) WARN high CPU utilization detected on worker" >> /var/log/app.log

echo "fault injected: nginx serving EXPIRED cert on :443; CPU herring pid=$(cat /tmp/herring.pid)"
