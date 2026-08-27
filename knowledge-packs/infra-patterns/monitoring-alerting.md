# Monitoring and Alerting Patterns

## When to use
- Any production system where you need to detect and diagnose issues
- Services with SLAs or internal reliability targets
- Distributed systems where failure modes are non-obvious
- Teams transitioning from reactive firefighting to proactive observability

## Pattern

### Three Pillars
- **Metrics**: Numeric time-series data — counters, gauges, histograms. Cheap to store, fast to query
- **Logs**: Structured event records. Use JSON with consistent field names across services
- **Traces**: Request-scoped spans showing how a request flows through services. Propagate context via headers

### Metric Design (RED/USE)
- RED for request-driven services: Rate, Errors, Duration
- USE for infrastructure: Utilization, Saturation, Errors
- Histogram over summary — histograms are aggregatable across instances, summaries are not
- Label cardinality must be bounded — unbounded labels (user_id, request_id) destroy metric stores

### SLO/SLI Framework
- Define SLIs as ratios: (good events / total events) over a time window
- SLOs are targets for SLIs: "99.9% of requests complete in under 300ms over 30 days"
- Error budgets = 1 - SLO. When budget is exhausted, prioritize reliability over features
- Measure SLIs from the client perspective when possible (synthetic probes, edge metrics)

### Alert Design
- Alert on symptoms (error rate, latency) not causes (CPU, memory) — causes are for dashboards
- Every alert must have a runbook link — if you can't write one, the alert isn't actionable
- Two severity levels are enough: page (wake someone up) and ticket (fix during business hours)
- Alert on burn rate against SLO: "consuming 30-day error budget 10x faster than sustainable"

### Dashboard Design
- Top-level dashboard: business KPIs and SLO status — the "is everything okay?" view
- Service dashboard: RED metrics, dependency health, recent deployments
- Debug dashboard: detailed metrics, log links, trace links — used during incidents
- No dashboard with more than 12 panels — if you need more, split into linked dashboards

### Log Management
- Structured logging with correlation IDs that link to traces
- Log levels: ERROR (requires action), WARN (degraded but functioning), INFO (state changes), DEBUG (off in prod)
- Sample verbose logs in high-throughput paths — 1% of debug logs is better than 0%
- Centralize logs but set retention tiers: hot (7d searchable), warm (30d), cold (archive)

## Gotchas / Anti-patterns
- Alert on every metric crossing a threshold — alert fatigue makes the team ignore all alerts
- Dashboards without context (no deployment markers, no SLO lines, no annotations)
- Monitoring the monitoring system with itself — circular dependency during outages
- Unstructured log grep as the primary debugging tool — doesn't scale
- Percentile metrics calculated client-side then averaged — mathematically meaningless
- Alerts with no owner — if nobody is responsible, nobody responds
- Collecting everything "just in case" without retention policies — cost explodes

## References
- Google SRE Book (Monitoring chapter): https://sre.google/sre-book/monitoring-distributed-systems/
- OpenTelemetry Specification: https://opentelemetry.io/docs/specs/
- Brendan Gregg's USE Method: https://www.brendangregg.com/usemethod.html
- SLO Alerting (Google): https://sre.google/workbook/alerting-on-slos/
- Grafana Dashboard Best Practices: https://grafana.com/docs/grafana/latest/best-practices/
