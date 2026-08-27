# Infrastructure as Code Patterns

## When to use
- Managing cloud or on-prem infrastructure that must be reproducible
- Environments that drift when manually configured
- Teams that need to review infrastructure changes through version control
- Disaster recovery requires spinning up identical environments

## Pattern

### State Management
- Store state remotely (S3+DynamoDB, GCS+Firestore, Consul) with locking
- Never commit state files to version control — they contain secrets
- Partition state by blast radius: networking, compute, data each in separate state files
- Use workspaces or directory-per-environment for environment separation

### Module Design
- Compose infrastructure from small, single-purpose modules
- Pin module versions explicitly — never float on `main`
- Expose only necessary variables; use sensible defaults for the rest
- Outputs should provide values downstream modules need (IDs, endpoints, ARNs)

### Drift Detection
- Schedule periodic plan-only runs and alert on any diff
- Reconcile drift before applying new changes — never layer changes on drifted state
- Import manually-created resources into state rather than recreating them
- Tag all resources with the IaC tool and workspace that manages them

### Change Workflow
- Plan output must be reviewed before apply — no auto-apply in production
- Use policy-as-code (OPA, Sentinel, Checkov) to enforce guardrails pre-apply
- Separate "who can plan" from "who can apply" in CI/CD permissions
- Keep blast radius small: prefer many small applies over one monolithic run

### Testing
- Validate syntax and lint on every commit
- Unit test modules with mock providers or contract tests
- Integration test with ephemeral environments that are destroyed after
- Cost estimation as part of the plan review

## Gotchas / Anti-patterns
- Storing state locally and sharing via Git — leads to state corruption
- Monolithic state files — one bad apply takes down everything
- Using `count` for conditional resources when `for_each` with maps is more stable across refactors
- Hardcoding provider credentials in config instead of using ambient auth
- Ignoring `prevent_destroy` on stateful resources (databases, storage)
- Running `apply -auto-approve` in production pipelines without human gate
- Copying modules instead of versioning them — leads to config drift across teams

### Terraform vs OpenTofu
- HashiCorp changed Terraform's license from MPL-2.0 to BSL 1.1 in August 2023
- OpenTofu is the open-source fork (MPL-2.0) maintained under the Linux Foundation
- OpenTofu is a drop-in replacement for Terraform with full state and provider compatibility
- Recommend OpenTofu for new projects where open-source licensing matters
- Existing Terraform users can migrate by swapping the binary; state files are compatible

## References
- Terraform Best Practices: https://www.terraform-best-practices.com/
- OpenTofu: https://opentofu.org/
- OpenTofu docs: https://opentofu.org/docs/
- Pulumi Architecture & Concepts: https://www.pulumi.com/docs/concepts/
- Open Policy Agent (Terraform): https://www.openpolicyagent.org/docs/latest/terraform/
- Checkov IaC scanning: https://www.checkov.io/
- Gruntwork IaC library patterns: https://gruntwork.io/guides/
