# Single-box scenario: an app cannot write because its data filesystem is full. The data dir is a bounded
# tmpfs (mounted via run_args) so the fault reproduces WITHOUT touching the host disk; the symptom and the
# diagnosis (a filesystem at 100%, writes fail with ENOSPC) are identical to a real full disk.
FROM debian:bookworm-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      python3 procps iproute2 curl coreutils ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# The app: on each request it appends a record to /var/lib/appdata/records.log and returns 200; if the
# write fails (no space) it logs the error and returns 500.
RUN mkdir -p /opt/app && printf '%s\n' \
  'import http.server, socketserver, sys' \
  'PORT = 8080; DATA = "/var/lib/appdata/records.log"' \
  'class H(http.server.BaseHTTPRequestHandler):' \
  '    def do_GET(self):' \
  '        try:' \
  '            with open(DATA, "a") as fh: fh.write("record\\n")' \
  '            self.send_response(200); self.end_headers(); self.wfile.write(b"ok\\n")' \
  '        except OSError as e:' \
  '            sys.stderr.write("ERROR: failed to write %s: %s\\n" % (DATA, e))' \
  '            self.send_response(500); self.end_headers(); self.wfile.write(b"write error\\n")' \
  '    def log_message(self, *a): pass' \
  'with socketserver.TCPServer(("", PORT), H) as httpd:' \
  '    httpd.serve_forever()' \
  > /opt/app/app.py

CMD ["sleep", "infinity"]
