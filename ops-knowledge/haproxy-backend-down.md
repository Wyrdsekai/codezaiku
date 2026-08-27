match: haproxy
signature: has no server available, is DOWN, reason:, Layer4 connection problem
push: rescue
status: candidate
platform: systemd
# HAProxy backend has no server available — fix procedure
1. "backend X has no server available!" with "Server X/Y is DOWN, reason: Layer4 connection problem" = every backend server failed its health check; the pool is empty so HAProxy returns 503.
2. Confirm which are down: `echo "show servers state" | socat stdio /run/haproxy/admin.sock` (or the stats page), noting DOWN backends and their addr:port.
3. Verify that addr:port is listening: `nc -vz <addr> <port>` or `curl -sv http://<addr>:<port>/`.
4. Restore the backend (start the service, fix the bound port/firewall); if the check itself is wrong, correct `option httpchk`/`server ... check` then `systemctl reload haproxy`.
5. Recheck the server flips UP in `show servers state` and the route returns 200; when a server is UP, conclude (submit) immediately.
