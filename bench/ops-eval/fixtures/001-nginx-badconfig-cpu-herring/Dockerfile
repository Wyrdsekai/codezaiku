# A single-box scenario: a small web host running nginx. Healthy baseline; the fault is injected at
# runtime by setup.sh (so the image itself is clean and reusable across scenarios of this shape).
FROM debian:bookworm-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      nginx procps iproute2 curl openssl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# A minimal, VALID site config as the healthy baseline. setup.sh corrupts it to inject the fault.
RUN printf 'server {\n    listen 80 default_server;\n    root /var/www/html;\n    index index.html;\n}\n' \
      > /etc/nginx/sites-available/default \
 && mkdir -p /var/www/html \
 && printf '<h1>hello from the box</h1>\n' > /var/www/html/index.html

# Keep the container alive; the eval driver exec's setup.sh then points the ops loop at it.
CMD ["sleep", "infinity"]
