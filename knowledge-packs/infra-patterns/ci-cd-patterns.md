# CI/CD Pipeline Patterns

## When to use
- Any project that needs repeatable, auditable builds and deployments
- Teams shipping to multiple environments (dev, staging, prod)
- Projects requiring automated quality gates before code reaches production
- Regulatory or compliance contexts that mandate deployment traceability

## Pattern

### Pipeline Structure
- Stages should be: lint → build → unit test → integration test → security scan → package → deploy
- Each stage produces artifacts consumed by the next — never rebuild between stages
- Fail fast: cheapest checks first (lint, compile) before expensive ones (integration, E2E)
- Pipeline-as-code in the repository root — the pipeline definition ships with the code it builds

### Artifact Management
- Build once, deploy everywhere — the same artifact goes through all environments
- Tag artifacts with Git SHA, not branch name — branch names are mutable
- Artifact repositories should have retention policies — don't store every build forever
- Sign artifacts at build time; verify signatures at deploy time

### Deployment Strategies
- Rolling deployments for stateless services with backward-compatible changes
- Blue-green when you need instant rollback and can afford double the resources
- Canary when changes are risky and you want to limit blast radius to a percentage of traffic
- Feature flags decouple deployment from release — deploy dark, enable gradually

### Rollback
- Every deployment must have a tested rollback path before going to production
- Rollback should be a forward action (deploy previous artifact) not "undo"
- Database migrations must be backward-compatible — the previous code version must work with the new schema
- Keep N previous artifacts available for rapid rollback without rebuilding

### Branch Strategy
- Trunk-based development: short-lived feature branches, merge to main frequently
- Release branches only when supporting multiple live versions simultaneously
- Environment branches (deploy-to-staging) are an anti-pattern — use promotion pipelines
- Merge queues prevent broken main by testing the merge result before landing

### Security in Pipelines
- Secrets injected at runtime by the CI system, never in pipeline definition files
- Pin action/plugin versions by SHA, not tag — prevents supply chain attacks
- Run SAST/DAST as non-blocking initially, then promote to blocking as baselines improve
- Audit pipeline permissions — CI service accounts should have minimal scope

## Gotchas / Anti-patterns
- Snowflake pipelines per service with no shared library — maintenance nightmare at scale
- Manual steps in the middle of automated pipelines — defeats the purpose
- Testing against shared staging environments — flaky tests from concurrent changes
- Building different artifacts for different environments (config baked into build)
- Long-running pipelines with no caching — rebuild everything from scratch each time
- Auto-deploying to production without any gate (approval, canary, smoke test)
- Retry-on-failure loops in pipelines — masks real problems

## References
- Continuous Delivery (Humble & Farley): https://continuousdelivery.com/
- DORA Metrics: https://dora.dev/
- Trunk-Based Development: https://trunkbaseddevelopment.com/
- SLSA Supply Chain Framework: https://slsa.dev/
- GitOps Principles: https://opengitops.dev/
