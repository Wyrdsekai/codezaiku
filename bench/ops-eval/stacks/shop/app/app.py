#!/usr/bin/env python3
"""A tiny role-based service for the compose eval stack. It exposes /health, which reports 200 only when
all of its declared dependencies are reachable, else 503 and logs WHICH dependency failed (the evidence a
diagnosis follows). Dependencies are declared via env — no external libraries, just raw TCP + HTTP GET.

  ROLE        label used in logs
  PORT        the port to serve /health on
  CHECK_TCP   comma-separated host:port that must accept a TCP connection (e.g. postgres:5432,redis:6379)
  CHECK_HTTP  comma-separated URLs that must return HTTP 200 (e.g. http://backend:8000/health)
"""
import http.server
import os
import socket
import sys
import urllib.request

ROLE = os.environ.get("ROLE", "app")
PORT = int(os.environ.get("PORT", "8000"))
CHECK_TCP = [x for x in os.environ.get("CHECK_TCP", "").split(",") if x]
CHECK_HTTP = [x for x in os.environ.get("CHECK_HTTP", "").split(",") if x]


def _fails():
    """Return a list of human-readable failures for the declared dependencies (empty = all healthy)."""
    fails = []
    for hp in CHECK_TCP:
        host, _, port = hp.partition(":")
        try:
            s = socket.create_connection((host, int(port)), timeout=1.5)
            s.close()
        except Exception as e:
            fails.append("cannot reach %s (%s)" % (hp, e))
    for url in CHECK_HTTP:
        try:
            with urllib.request.urlopen(url, timeout=2) as r:
                if r.status != 200:
                    fails.append("%s returned HTTP %s" % (url, r.status))
        except Exception as e:
            fails.append("%s failed (%s)" % (url, e))
    return fails


class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        fails = _fails()
        if fails:
            msg = "%s unhealthy: %s" % (ROLE, "; ".join(fails))
            sys.stderr.write("HEALTH FAIL: " + msg + "\n")
            self.send_response(503)
            self.end_headers()
            self.wfile.write(msg.encode())
        else:
            self.send_response(200)
            self.end_headers()
            self.wfile.write(("%s ok\n" % ROLE).encode())

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    sys.stderr.write("%s starting on :%d (tcp=%s http=%s)\n" % (ROLE, PORT, CHECK_TCP, CHECK_HTTP))
    http.server.HTTPServer(("", PORT), H).serve_forever()
