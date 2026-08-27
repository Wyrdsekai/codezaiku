# Networking Patterns

## When to use
- Designing service-to-service communication in distributed systems
- Exposing services to the internet with proper security boundaries
- Managing traffic routing, load balancing, and failover
- Implementing zero-trust networking or mutual authentication

## Pattern

### Service Mesh
- Deploy as sidecar proxies (Envoy-based) for transparent L7 traffic management
- Use mesh for: mTLS between services, retries, circuit breaking, observability — not for business logic
- Control plane manages configuration; data plane handles actual traffic
- Adopt incrementally — start with observability, add mTLS, then traffic policies

### Load Balancing
- L4 (TCP/UDP): fast, protocol-agnostic, suitable for non-HTTP workloads
- L7 (HTTP): content-aware routing, header-based routing, path-based routing
- Client-side load balancing for internal gRPC services — avoids proxy hop
- Health-check endpoints must reflect actual ability to serve, not just process liveness
- Consistent hashing for session affinity when stateless isn't possible

### DNS Patterns
- Short TTLs (30-60s) for services behind load balancers — allows fast failover
- Longer TTLs (300s+) for stable endpoints — reduces DNS query volume
- Use SRV records for service discovery when not behind a mesh
- Split-horizon DNS for internal vs external resolution of the same domain
- Always have a plan for DNS propagation delays during failover

### TLS Termination
- Terminate at the edge (load balancer/ingress) for simplicity and certificate management
- Re-encrypt to backends if compliance requires encryption in transit end-to-end
- Automate certificate rotation with ACME (Let's Encrypt) or internal CA
- Pin minimum TLS version to 1.2; prefer 1.3 for new deployments

### mTLS
- Both client and server present certificates — mutual authentication
- Use short-lived certificates (hours, not years) rotated by the mesh or SPIFFE
- SPIFFE IDs provide workload identity independent of network location
- Certificate validation must check both validity and authorization (identity != permission)

### Network Segmentation
- Microsegmentation: allow only explicitly needed traffic between services
- Default-deny network policies — services must declare their communication needs
- Separate management plane traffic from data plane traffic
- Egress filtering: services should only reach the external endpoints they need

## Gotchas / Anti-patterns
- Relying on network perimeter alone — once breached, everything is accessible
- DNS caching ignoring TTL — stale records cause outages after failover
- mTLS without certificate rotation — compromised long-lived certs are irrevocable
- Load balancer health checks that pass when the backend is overloaded
- Hairpin routing through external load balancers for internal service-to-service calls
- Not accounting for DNS resolution time in timeout budgets
- Service mesh for two services — overhead exceeds benefit at small scale

## References
- Envoy Proxy Architecture: https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/
- SPIFFE Specification: https://spiffe.io/docs/latest/spiffe-about/overview/
- Kubernetes Network Policies: https://kubernetes.io/docs/concepts/services-networking/network-policies/
- Linkerd Architecture: https://linkerd.io/2/reference/architecture/
- Cloudflare TLS Best Practices: https://developers.cloudflare.com/ssl/
