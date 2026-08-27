# Secret Management Patterns

## When to use
- Any application that consumes credentials, API keys, certificates, or tokens
- Environments with compliance requirements for secret auditability
- Multi-service architectures needing centralized credential distribution
- Teams moving away from secrets in config files, env vars, or version control

## Pattern

### Centralized Vault
- Store secrets in a dedicated secret store (HashiCorp Vault, AWS Secrets Manager, etc.)
- Applications authenticate to the vault using their workload identity, not a static token
- Vault issues short-lived, scoped credentials — database passwords, cloud tokens, TLS certs
- Audit log every secret access — who read what, when

### Secret Rotation
- Automated rotation on a schedule (30-90 days) without application restarts
- Dual-credential pattern: issue new credential, verify it works, then revoke old
- Database credential rotation: create new user, update vault, drop old user
- Application must re-read secrets periodically or watch for change notifications

### Environment Injection
- Inject secrets at runtime via sidecar, init container, or entrypoint script
- Secrets as mounted files (tmpfs) are safer than env vars — env vars leak into logs and child processes
- Never bake secrets into container images or build artifacts
- Use secret references in config, not the secret values themselves

### Sealed/Encrypted Secrets for GitOps
- Encrypt secrets client-side with the cluster's public key before committing
- Only the target cluster can decrypt — safe to store in Git alongside other manifests
- Re-seal when rotating the cluster's encryption key
- Limit who holds the sealing key — it controls access to all sealed secrets

### Secret Hierarchy
- Global secrets (shared CA certs, org-wide keys) managed by platform team
- Service secrets (database credentials, API keys) managed by service owners
- Environment overrides (staging vs prod endpoints) layered on top
- Least privilege: each service accesses only its own secrets plus global

### Zero-Trust Secret Access
- No permanent credentials where dynamic ones are possible
- Short TTLs (1h for tokens, 24h for certificates) force regular re-authentication
- Break-glass procedures for emergency access with elevated audit logging
- Revocation must be near-instant — don't rely on TTL expiry alone for compromised credentials

## Gotchas / Anti-patterns
- Secrets in environment variables logged by crash reporters or debug endpoints
- Committing `.env` files to version control (even "just for development")
- One master API key shared across all services — no audit trail, no revocation granularity
- Long-lived secrets with no rotation schedule — compromise goes undetected for months
- Encrypting secrets with a key stored next to the secrets — security theater
- Secret sprawl: copies of the same credential in multiple stores with no source of truth
- Vault unsealing keys stored on the same machine as the vault

## References
- HashiCorp Vault Patterns: https://developer.hashicorp.com/vault/docs/concepts
- Kubernetes Secrets Best Practices: https://kubernetes.io/docs/concepts/configuration/secret/
- Sealed Secrets (Bitnami): https://github.com/bitnami-labs/sealed-secrets
- OWASP Secrets Management Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html
- SPIFFE for Workload Identity: https://spiffe.io/
