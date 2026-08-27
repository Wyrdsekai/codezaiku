match: envoy, istio-proxy
signature: no healthy upstream, upstream connect error or disconnect/reset before headers, reset reason: connection failure
push: rescue
status: candidate
# Envoy 503 no healthy upstream — fix procedure
1. A 503 with "no healthy upstream" (or "upstream connect error or disconnect/reset before headers") and flag UH/UF means Envoy has zero healthy hosts in the target cluster — the upstream is down or failing health checks.
2. Confirm via admin API: `curl -s localhost:15000/clusters | grep <cluster>` and look for hosts with health_flags/failed_active_health_check.
3. Verify the upstream listens on the configured host:port (curl/nc it directly); a port mismatch between the service and the Envoy cluster is the usual cause.
4. Restore the upstream or fix the cluster's port/endpoints, then recheck `localhost:15000/clusters` shows healthy hosts and the route returns 200.
5. When hosts are healthy and the route returns 200, conclude (submit) immediately.
