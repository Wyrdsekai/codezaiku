# Single-box scenario: the app's port is already held by a rogue process, so the app can't bind.
FROM debian:bookworm-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      python3 procps iproute2 curl netcat-openbsd ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# A tiny "app" that binds :8080 and serves a page — the service the operator cares about.
RUN mkdir -p /opt/app && printf '%s\n' \
  'import http.server, socketserver' \
  'PORT = 8080' \
  'class H(http.server.BaseHTTPRequestHandler):' \
  '    def do_GET(self):' \
  '        self.send_response(200); self.end_headers(); self.wfile.write(b"app ok\\n")' \
  '    def log_message(self, *a): pass' \
  'with socketserver.TCPServer(("", PORT), H) as httpd:' \
  '    httpd.serve_forever()' \
  > /opt/app/app.py

CMD ["sleep", "infinity"]
