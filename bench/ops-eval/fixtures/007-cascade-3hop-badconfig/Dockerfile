# 3-hop cascade: nginx (:80) -> app (:8080) -> cache (:6379). The cache process crashes on startup because
# its config has an invalid directive → :6379 never comes up → the app can't reach the cache and exits →
# :8080 down → nginx returns 502. Root cause is the cache's BAD CONFIG, three hops below the 502.
FROM debian:bookworm-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      nginx python3 procps iproute2 curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN printf 'server {\n    listen 80 default_server;\n    location / {\n        proxy_pass http://127.0.0.1:8080;\n    }\n}\n' \
      > /etc/nginx/sites-available/default

# cache: reads /etc/cache/cache.conf; every non-empty non-comment line must be KEY=VALUE, else it treats it
# as an invalid directive, logs a FATAL, and exits (never binds :6379). Otherwise it listens on :6379.
RUN mkdir -p /opt/svc /etc/cache && printf '%s\n' \
  'import socket, sys, os' \
  'CONF = "/etc/cache/cache.conf"' \
  'for ln in open(CONF).read().splitlines():' \
  '    s = ln.strip()' \
  '    if not s or s.startswith("#"): continue' \
  '    if "=" not in s:' \
  '        sys.stderr.write("FATAL: invalid config directive in %s: %r\\n" % (CONF, s)); sys.exit(1)' \
  's = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)' \
  's.bind(("127.0.0.1", 6379)); s.listen(16)' \
  'import time; time.sleep(10**9)' \
  > /opt/svc/cache.py

# app: on startup connects to the cache at 127.0.0.1:6379; if it cannot, logs a FATAL and exits. Otherwise
# it serves 200 on :8080.
RUN mkdir -p /opt/app && printf '%s\n' \
  'import http.server, socketserver, socket, sys' \
  'PORT = 8080; CACHE = ("127.0.0.1", 6379)' \
  'try:' \
  '    c = socket.socket(); c.settimeout(1); c.connect(CACHE); c.close()' \
  'except Exception as e:' \
  '    sys.stderr.write("FATAL: cannot reach cache service at 127.0.0.1:6379 on startup: %s (cache is /opt/svc/cache.py; its log is /var/log/svc/cache.log)\\n" % e); sys.exit(1)' \
  'class H(http.server.BaseHTTPRequestHandler):' \
  '    def do_GET(self):' \
  '        self.send_response(200); self.end_headers(); self.wfile.write(b"app ok\\n")' \
  '    def log_message(self, *a): pass' \
  'with socketserver.TCPServer(("", PORT), H) as httpd:' \
  '    httpd.serve_forever()' \
  > /opt/app/app.py

CMD ["sleep", "infinity"]
