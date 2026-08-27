match: nginx, reverse proxy, proxy
signature: 502 bad gateway, connection refused, no live upstreams, upstream, 111: connection refused, upstream prematurely closed
push: rescue
status: candidate
# nginx 502 from an unreachable upstream — fix procedure
1. A 502 Bad Gateway means nginx could not reach a working upstream — the proxied backend host/PORT is wrong
   or the backend is down. The nginx error log names it: "connect() failed (111: Connection refused)" or
   "no live upstreams" with the upstream address it tried.
2. Read the configured target: `nginx -T` (dumps the effective config) shows the `proxy_pass` / `upstream`
   host:port. Compare it to where the backend actually listens — a wrong PORT is the classic cause.
3. Fix the target: correct the proxy_pass host:port to the real backend, then `nginx -s reload`. Reload
   validates first — a rejected reload keeps the OLD config, so fix whatever error it prints and reload again.
4. Confirm a request through nginx returns the backend's real response (HTTP 200), then conclude.
