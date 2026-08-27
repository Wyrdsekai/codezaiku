# Single-box scenario: the app is UP but returns errors because its database dependency is unreachable.
FROM debian:bookworm-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      python3 procps iproute2 curl netcat-openbsd ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# The app: healthy process on :8080, but every request tries to reach the DB at 127.0.0.1:5432 and 500s
# when it can't connect (and logs the failure). The DB is NOT started in this image — setup.sh decides.
RUN mkdir -p /opt/app && printf '%s\n' \
  'import http.server, socketserver, socket' \
  'PORT = 8080; DB = ("127.0.0.1", 5432)' \
  'class H(http.server.BaseHTTPRequestHandler):' \
  '    def do_GET(self):' \
  '        s = socket.socket(); s.settimeout(1)' \
  '        try:' \
  '            s.connect(DB); s.close()' \
  '            self.send_response(200); self.end_headers(); self.wfile.write(b"ok\\n")' \
  '        except Exception as e:' \
  '            import sys; sys.stderr.write("ERROR: could not connect to database 127.0.0.1:5432: %s\\n" % e)' \
  '            self.send_response(500); self.end_headers(); self.wfile.write(b"db error\\n")' \
  '    def log_message(self, *a): pass' \
  'with socketserver.TCPServer(("", PORT), H) as httpd:' \
  '    httpd.serve_forever()' \
  > /opt/app/app.py

CMD ["sleep", "infinity"]
