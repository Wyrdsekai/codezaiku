import http.server, json, os, redis
R = os.environ.get("CACHE_HOST", "cache")
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        try:
            redis.Redis(host=R, socket_timeout=3).set("_probe", "1")
            body = {"status": "ok", "deps": {"cache": "ok"}}
        except Exception as e:
            body = {"status": "degraded", "deps": {"cache": "down: " + str(e)[:120]}}
        b = json.dumps(body).encode()
        self.send_response(200); self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def log_message(self, *a): pass
http.server.HTTPServer(("0.0.0.0", 8080), H).serve_forever()
