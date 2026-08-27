# Multi-fault: the app on :8080 cannot start because a rogue process already holds the port (the CAUSAL
# fault). SEPARATELY, the data disk is ~92% full — a real, concurrent problem, but NOT why the app is down.
# Tests causal attribution amid noise: both faults trip a precheck; only the port conflict caused the outage.
FROM debian:bookworm-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      python3 procps iproute2 curl coreutils ca-certificates \
 && rm -rf /var/lib/apt/lists/*

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
