match: traefik
signature: '502 Bad Gateway' caused by, connect: connection refused, dial tcp
push: rescue
status: candidate
platform: docker, systemd
# Traefik 502 Bad Gateway backend down — fix procedure
1. A 502 logged as "'502 Bad Gateway' caused by: dial tcp <ip>:<port>: connect: connection refused" means Traefik resolved the service but the backend refused the connection — the target is down or on the wrong port.
2. Read the exact dial target: `journalctl -u traefik | grep "Bad Gateway"` (or the container logs); note the ip:port Traefik tried.
3. Verify the backend is up on that ip:port: `curl -sv http://<ip>:<port>/` or `nc -vz <ip> <port>`. For Docker, use the container's INTERNAL port, not the published one.
4. Restore the backend or correct the service port (`traefik.http.services.<svc>.loadbalancer.server.port` label / service definition), then let Traefik re-poll.
5. Recheck the route returns 200 and the 502 stops; when healthy, conclude (submit) immediately.
