# Container Orchestration Patterns

## When to use
- Running stateless or stateful services that need horizontal scaling
- Workloads requiring self-healing, rolling updates, and service discovery
- Multi-service architectures needing consistent deployment and networking
- Teams adopting microservices or need environment parity (dev/staging/prod)

## Pattern

### Pod Design
- One primary container per pod; sidecars for cross-cutting concerns (logging, proxy, TLS)
- Init containers for setup tasks: schema migration, config fetch, dependency wait
- Use pod disruption budgets to guarantee minimum availability during node drains
- Set `terminationGracePeriodSeconds` long enough for in-flight requests to complete

### Resource Management
- Always set both `requests` and `limits` — requests for scheduling, limits for protection
- CPU limits can cause throttling even when the node has spare capacity — set generously or omit
- Memory limits should be set — OOM kills are preferable to node-level memory pressure
- Use Vertical Pod Autoscaler in recommend mode to discover actual resource usage before hardcoding

### Health Checks
- Liveness probe: restarts the container if it deadlocks (not for slow startup)
- Readiness probe: removes from service endpoints when unable to serve traffic
- Startup probe: gives slow-starting apps time before liveness kicks in
- Probe endpoints should be cheap — no database calls, no heavy computation

### Deployment Strategies
- Rolling update: default, set `maxSurge` and `maxUnavailable` for control
- Blue-green: run two full deployments, switch service selector
- Canary: use weighted traffic splitting (Istio, Linkerd, or ingress annotations)
- Recreate: only for workloads that cannot tolerate two versions running simultaneously

### Configuration
- ConfigMaps for non-sensitive configuration; Secrets for credentials
- Mount as volumes for files, expose as env vars for simple key-value
- Use immutable ConfigMaps/Secrets — create new ones and update the deployment reference
- External config (Vault, SSM) injected via init container or sidecar, not baked into images

### Stateful Workloads
- StatefulSets for ordered deployment and stable network identities
- Use persistent volume claims with appropriate storage class (SSD vs HDD, single vs multi-attach)
- Headless services for direct pod-to-pod communication in stateful clusters
- Backup PVCs independently — PV lifecycle is separate from pod lifecycle

## Gotchas / Anti-patterns
- Running without resource requests — pods land on overcommitted nodes and get evicted
- Liveness probes that check dependencies — cascading restarts when a database is slow
- Using `latest` tag — no rollback capability and non-deterministic deploys
- Hardcoding replica counts instead of using HPA with metrics
- Storing state in container filesystems — lost on restart
- Privileged containers without justification — breaks security boundaries
- Not setting pod anti-affinity — all replicas land on the same node

## References
- Kubernetes Patterns (Ibryam & Huss): https://k8spatterns.io/
- Production-Grade Kubernetes: https://kubernetes.io/docs/setup/best-practices/
- GKE Best Practices: https://cloud.google.com/kubernetes-engine/docs/best-practices
- Kubernetes Resource Management: https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/
