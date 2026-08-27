match: nginx
signature: connection refused) while connecting to upstream, no live upstreams, upstream timed out
push: rescue
status: candidate
# nginx 502/504 upstream failure — fix procedure
1. A 502/504 with "connect() failed (111: Connection refused) while connecting to upstream" or "no live
   upstreams" means nginx is healthy but its BACKEND is down/unreachable — fix the upstream, not nginx.
2. Read the error log for the upstream address nginx tried: `tail /var/log/nginx/error.log` (or the pod
   logs). That host:port is the real suspect.
3. Verify the backend directly from nginx's vantage point: is the upstream process/pod up and listening on
   that port? (`curl`/`nc` the upstream, check its own health).
4. Restore the backend (restart it / fix its listen address) OR correct the `upstream`/`proxy_pass` target
   if it points at the wrong place. Recheck the route returns 200, then conclude (submit) immediately.
