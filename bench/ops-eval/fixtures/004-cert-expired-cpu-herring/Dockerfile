# Single-box scenario: nginx serves HTTPS with an EXPIRED TLS certificate. HTTP is fine; TLS clients get
# a certificate-expired error. Healthy baseline; the expired cert is minted at runtime by setup.sh.
FROM debian:bookworm-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      nginx openssl faketime procps iproute2 curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /var/www/html /etc/nginx/ssl \
 && printf '<h1>hello from the box</h1>\n' > /var/www/html/index.html \
 && printf 'server {\n    listen 80 default_server;\n    root /var/www/html;\n    index index.html;\n}\n' \
      > /etc/nginx/sites-available/default

CMD ["sleep", "infinity"]
