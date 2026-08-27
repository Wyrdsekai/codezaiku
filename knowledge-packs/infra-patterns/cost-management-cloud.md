# Cloud Cost Management Patterns

## When to use
- Cloud spend is growing faster than the workload it supports
- Teams need accountability for their infrastructure costs
- Optimizing between performance, reliability, and cost
- Forecasting infrastructure budget for planning cycles

## Pattern

### Tagging Strategy
- Mandatory tags: team/owner, environment, service, cost-center
- Enforce tagging via policy-as-code — reject untagged resources at creation
- Consistent naming convention across all providers and accounts
- Tag inheritance: resources created by tagged parents inherit their tags
- Regularly audit for untagged resources and assign or terminate them

### Rightsizing
- Collect utilization metrics for 2-4 weeks before making sizing decisions
- CPU utilization below 20% sustained = oversized; above 80% sustained = undersized
- Memory utilization patterns differ from CPU — rightsize each dimension independently
- Rightsize before committing to reserved pricing — don't lock in the wrong size
- Automate recommendations with cloud provider tools, but review before applying

### Commitment Discounts
- **Reserved instances / Savings Plans**: 30-70% discount for 1-3 year commitment
- Commit only to your steady-state baseline — the floor of your usage
- Cover the predictable base with commitments, handle spikes with on-demand
- Review utilization of existing commitments monthly — unused commitments waste money
- Stagger commitment expiration dates to avoid cliff effects

### Spot/Preemptible Instances
- Suitable for: batch processing, CI/CD runners, stateless workers, dev environments
- Not suitable for: databases, single-instance services, anything with long graceful shutdown
- Diversify across instance types and availability zones to reduce interruption risk
- Application must handle interruption gracefully — save state, drain connections
- Typical savings: 60-90% versus on-demand pricing

### Architectural Cost Optimization
- Data transfer costs often exceed compute — keep chatty services in the same zone
- Tiered storage: hot data on SSD, warm on standard, cold on archive storage
- Cache aggressively — a cache hit is cheaper than a database query or API call
- Serverless for spiky, low-utilization workloads; containers for steady-state
- Delete unused resources: unattached volumes, old snapshots, idle load balancers

### FinOps Practice
- Visibility first: dashboards showing cost by team, service, and environment
- Anomaly detection: alert when daily spend deviates more than 20% from rolling average
- Showback or chargeback: teams see (and optionally pay for) what they use
- Monthly cost review meetings with engineering leads — make cost a first-class metric
- Unit economics: cost per request, cost per user, cost per transaction

## Gotchas / Anti-patterns
- Optimizing cost before understanding the workload — premature commitment wastes money
- Over-committing to reserved instances based on peak usage instead of baseline
- Ignoring data transfer costs — they grow silently and can dominate the bill
- "Lift and shift" without rightsizing — running on-prem-sized VMs in the cloud
- Turning off dev environments manually — automate schedules, humans forget
- Cost alerts with no response plan — the alert fires, nobody acts
- Spot instances for stateful workloads — interruption causes data loss or corruption

## References
- FinOps Foundation Principles: https://www.finops.org/framework/principles/
- AWS Cost Optimization Pillar: https://docs.aws.amazon.com/wellarchitected/latest/cost-optimization-pillar/
- Google Cloud Cost Management: https://cloud.google.com/cost-management
- Spot Instance Best Practices: https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/spot-best-practices.html
- CNCF FinOps for Kubernetes: https://www.cncf.io/blog/2021/06/29/finops-for-kubernetes/
