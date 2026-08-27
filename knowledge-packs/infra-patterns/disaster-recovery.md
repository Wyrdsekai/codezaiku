# Disaster Recovery Patterns

## When to use
- Any system where data loss or extended downtime has material business impact
- Regulated industries requiring documented recovery capabilities
- Multi-region or multi-cloud deployments needing coordinated failover
- After an incident reveals recovery gaps — build the plan before you need it

## Pattern

### RTO and RPO
- **RPO** (Recovery Point Objective): maximum acceptable data loss, measured in time
- **RTO** (Recovery Time Objective): maximum acceptable downtime from incident to recovery
- Define both per-service — not everything needs the same tier
- RPO drives backup frequency; RTO drives failover architecture and automation

### Backup Strategies
- **Full backups**: complete copy, simplest restore, largest storage
- **Incremental**: only changes since last backup, fast to take, slower to restore (chain dependency)
- **Snapshot**: point-in-time block-level copy, near-instant creation
- 3-2-1 rule: 3 copies, 2 different media, 1 offsite/different region
- Test restores on a schedule — an untested backup is not a backup

### Failover Patterns
- **Active-passive**: standby environment receives replicated data, promoted on failure
- **Active-active**: both regions serve traffic, either can absorb the other's load
- **Pilot light**: minimal standby (database replicas) scaled up on failover
- **Warm standby**: scaled-down copy of production, faster promotion than pilot light
- Failover must be automated or single-command — manual runbooks under stress fail

### Database Recovery
- Continuous replication (synchronous for zero RPO, asynchronous for lower latency)
- Point-in-time recovery using WAL/binlog shipping to a specific timestamp
- Cross-region read replicas that can be promoted to primary
- Test promotion regularly — replica lag, sequence gaps, and missing extensions surprise you

### Chaos Engineering
- Proactively inject failures to discover weaknesses before real incidents
- Start small: kill a pod, add latency to a network call, fill a disk
- Game days: scheduled exercises where the team practices failover procedures
- Steady-state hypothesis: define what "working" looks like, then verify it holds under failure
- Blast radius controls: start in staging, then non-critical prod, then critical paths

### Incident Communication
- Pre-draft status page templates for common failure modes
- Separate communication channel from affected infrastructure
- Defined roles: incident commander, communications lead, technical lead
- Post-incident review within 48 hours, blameless, focused on systemic improvements

## Gotchas / Anti-patterns
- Backups stored in the same region/account as production — regional outage loses both
- Assuming failover works because it was configured — test it or it doesn't exist
- Manual failover runbooks with 47 steps — under pressure, steps get skipped
- RPO of zero with asynchronous replication — physically impossible, you will lose data
- DR plans that reference specific people instead of roles — people leave, get sick, sleep
- Testing DR annually instead of continuously — infrastructure changes invalidate old plans
- Forgetting that DNS propagation takes time — TTL must be pre-lowered before failover

## References
- AWS Disaster Recovery Strategies: https://docs.aws.amazon.com/whitepapers/latest/disaster-recovery-workloads-on-aws/
- Google SRE Book (Managing Incidents): https://sre.google/sre-book/managing-incidents/
- Chaos Engineering Principles: https://principlesofchaos.org/
- Netflix Chaos Monkey: https://netflix.github.io/chaosmonkey/
- PagerDuty Incident Response: https://response.pagerduty.com/
