"""Minimal dependent service. /ready reports the dep's state in the shape StackLocalizer needs.

The contract that matters: a DOWN dependency's error text must be in the dep's own VALUE
("down: <err>"), not a sibling field. A fixture that puts it elsewhere stalls the ladder at
confident=false — and the right response to that is to fix the FIXTURE, not widen the parser.
"""
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer

import redis

HOST = os.environ.get("REDIS_HOST", "cache")
PASS = os.environ.get("REDIS_PASS", "s3cret")


def probe():
    try:
        redis.Redis(host=HOST, port=6379, password=PASS, socket_connect_timeout=3).ping()
        return "ok"
    except Exception as e:                      # noqa: BLE001 - the text IS the signal
        return "down: " + str(e).strip()


class H(BaseHTTPRequestHandler):
    def do_GET(self):
        dep = probe()
        ok = dep == "ok"
        body = json.dumps({"status": "ok" if ok else "degraded", "deps": {"cache": dep}}).encode()
        self.send_response(200 if ok else 503)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8000), H).serve_forever()
