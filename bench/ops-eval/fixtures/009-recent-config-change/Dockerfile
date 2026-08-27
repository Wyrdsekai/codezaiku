# "What changed?" scenario. The app reads its DB port from /etc/shopapp/app.conf. The DATABASE IS HEALTHY
# and listening on 5432 — but a recent edit repointed the app at 5433, so it 500s. The naive diagnosis
# ("the app can't reach the DB → the database is down") is WRONG; the DB is up. The root cause is the recent
# config change. Tests change-correlation AND resistance to the obvious-but-wrong conclusion.
FROM debian:bookworm-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      python3 procps iproute2 curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# The "database": a plain TCP listener on 5432 that stays healthy the whole time.
RUN mkdir -p /opt/db && printf '%s\n' \
  'import socket, time' \
  's = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)' \
  's.bind(("127.0.0.1", 5432)); s.listen(16)' \
  'time.sleep(10**9)' \
  > /opt/db/db.py

# The app: reads db_port from /etc/shopapp/app.conf on every request, connects, 200 if reachable else 500
# (logging the exact host:port it tried — the evidence trail).
RUN mkdir -p /opt/app /etc/shopapp && printf '%s\n' \
  'import http.server, socketserver, socket, sys' \
  'CONF = "/etc/shopapp/app.conf"' \
  'def cfg():' \
  '    d = {}' \
  '    for ln in open(CONF).read().splitlines():' \
  '        s = ln.strip()' \
  '        if s and not s.startswith("#") and "=" in s:' \
  '            k, _, v = s.partition("="); d[k.strip()] = v.strip()' \
  '    return d' \
  'class H(http.server.BaseHTTPRequestHandler):' \
  '    def do_GET(self):' \
  '        c = cfg(); host = c.get("db_host", "127.0.0.1"); port = int(c.get("db_port", "5432"))' \
  '        try:' \
  '            s = socket.socket(); s.settimeout(1.5); s.connect((host, port)); s.close()' \
  '            self.send_response(200); self.end_headers(); self.wfile.write(b"ok\\n")' \
  '        except Exception as e:' \
  '            sys.stderr.write("ERROR: cannot connect to database %s:%d (from %s): %s\\n" % (host, port, CONF, e))' \
  '            self.send_response(500); self.end_headers(); self.wfile.write(b"db error\\n")' \
  '    def log_message(self, *a): pass' \
  'with socketserver.TCPServer(("", 8080), H) as httpd:' \
  '    httpd.serve_forever()' \
  > /opt/app/app.py

# The GOOD config (correct port). Then AGE the whole filesystem's config so the box looks like it has been
# running for a month — so the ONE edit setup.sh makes is the only thing that changed recently (a clean,
# realistic "what changed?" signal rather than a flood of image-build timestamps).
RUN printf 'db_host=127.0.0.1\ndb_port=5432\nworkers=4\n' > /etc/shopapp/app.conf \
 && find /etc /opt -type f -exec touch -d '30 days ago' {} + 2>/dev/null || true

CMD ["sleep", "infinity"]
