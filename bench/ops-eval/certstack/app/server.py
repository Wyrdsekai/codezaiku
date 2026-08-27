"""Client that VERIFIES the TLS endpoint, so an expired cert surfaces as a dependency failure.

The error text it records is the generic one — "certificate has expired" — which is exactly why this
fixture exists: that string is in `host-tls-cert-expiry`'s signature, and that card's procedure is
`systemctl`-based, so on a docker-only macOS target the harness offers a fix that cannot run.
"""
import json
import os
import ssl
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

URL = os.environ.get("TLS_URL", "https://tls:8443/")
CA = os.environ.get("TLS_CA", "/certs/server.crt")


def probe():
    try:
        ctx = ssl.create_default_context(cafile=CA)
        urllib.request.urlopen(URL, timeout=4, context=ctx).read()
        return "ok"
    except Exception as e:                       # noqa: BLE001 - the text IS the signal
        return "down: " + str(e).strip()


class H(BaseHTTPRequestHandler):
    def do_GET(self):
        dep = probe()
        ok = dep == "ok"
        body = json.dumps({"status": "ok" if ok else "degraded", "deps": {"tls": dep}}).encode()
        self.send_response(200 if ok else 503)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *a): pass


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8000), H).serve_forever()
