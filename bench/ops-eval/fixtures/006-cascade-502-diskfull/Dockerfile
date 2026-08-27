# Multi-hop cascade: nginx (:80) proxies to an upstream app (:8080). The app crashes on startup because it
# can't write to its data dir (disk full) → :8080 is down → nginx returns 502. The 502 and the down app are
# SYMPTOMS; the root cause is the full filesystem. Data dir is a bounded tmpfs (run_args) — no host disk.
FROM debian:bookworm-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      nginx python3 procps iproute2 curl coreutils ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# nginx reverse-proxies / → 127.0.0.1:8080 (the app).
RUN printf 'server {\n    listen 80 default_server;\n    location / {\n        proxy_pass http://127.0.0.1:8080;\n    }\n}\n' \
      > /etc/nginx/sites-available/default

# The app: on startup it MUST write a lock file to its data dir; if that write fails (no space) it logs the
# error and EXITS (so :8080 never comes up). Otherwise it serves 200 on :8080.
RUN mkdir -p /opt/app && printf '%s\n' \
  'import http.server, socketserver, sys' \
  'PORT = 8080; LOCK = "/var/lib/appdata/startup.lock"' \
  'try:' \
  '    with open(LOCK, "w") as fh: fh.write("up\\n")' \
  'except OSError as e:' \
  '    sys.stderr.write("FATAL: cannot write %s on startup: %s\\n" % (LOCK, e)); sys.exit(1)' \
  'class H(http.server.BaseHTTPRequestHandler):' \
  '    def do_GET(self):' \
  '        self.send_response(200); self.end_headers(); self.wfile.write(b"app ok\\n")' \
  '    def log_message(self, *a): pass' \
  'with socketserver.TCPServer(("", PORT), H) as httpd:' \
  '    httpd.serve_forever()' \
  > /opt/app/app.py

CMD ["sleep", "infinity"]
