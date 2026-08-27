# Secrets in Code

## When to use
- Preventing credentials, API keys, tokens, and private keys from being committed to version control
- Setting up pre-commit safeguards for development teams
- Remediating a secret that has already been committed
- Building CI pipelines that handle secrets safely

## Pattern

### Detection Patterns
- **High-entropy strings**: base64/hex strings longer than 20 characters in non-binary files are suspect
- **Known formats**: AWS keys (`AKIA...`), GitHub tokens (`ghp_...`, `gho_...`), Slack tokens (`xoxb-...`), JWTs, PEM headers
- **Variable names**: keys named `password`, `secret`, `api_key`, `token`, `private_key`, `credentials` assigned string literals
- **Connection strings**: database URIs with embedded passwords (`postgres://user:pass@host/db`)
- **Private keys**: `-----BEGIN RSA PRIVATE KEY-----`, `-----BEGIN EC PRIVATE KEY-----`, `-----BEGIN OPENSSH PRIVATE KEY-----`
- Regular expressions for common patterns: tools like TruffleHog and detect-secrets maintain pattern databases

### .gitignore Configuration
- Exclude files that commonly contain secrets: `.env`, `.env.local`, `*.pem`, `*.key`, `*.p12`, `*.jks`
- Exclude credential files: `credentials.json`, `service-account.json`, `*-credentials.*`
- Exclude tool configuration that may embed tokens: `.npmrc` (with authToken), `.pypirc`, `docker-config.json`
- Include template files: `.env.example` with placeholder values documenting required variables
- Use a global gitignore (`~/.gitignore_global`) for personal secrets files across all repositories

### Pre-Commit Hooks
- Install secret scanning as a pre-commit hook: runs on every commit, blocks commits containing detected secrets
- Tools: detect-secrets (Yelp), TruffleHog, git-secrets (AWS), gitleaks
- Baseline file: acknowledge known false positives; new secrets still trigger; review baseline periodically
- Speed: hooks must run fast (< 5 seconds) or developers will bypass them; scan only staged files, not entire repo
- CI backstop: run the same scan in CI in case a developer bypasses the hook (`--no-verify`)

### Secret Scanning in CI
- Run on every PR: scan the diff for newly introduced secrets
- Full repository scan on a schedule (weekly): detect secrets that bypassed other controls
- GitHub secret scanning: enable for public and private repos; auto-notifies providers of leaked tokens
- Alert and block: fail the CI pipeline if secrets are detected; require explicit override with justification
- Historical scan: when enabling scanning for the first time, scan full git history (all commits, all branches)

### Remediation
- **Revoke immediately**: rotate the exposed credential before cleaning the repository; assume it is compromised
- **Remove from history**: use `git filter-repo` (not `git filter-branch`) to purge the secret from all commits
- **Force push**: after history rewrite, force push and notify all contributors to re-clone or rebase
- **Invalidate caches**: purge CI caches, container layer caches, and artifact storage that may contain the secret
- **Post-mortem**: document how the secret was committed, what controls failed, and what changes prevent recurrence

### Secret Management
- Store secrets in dedicated secret management tools: HashiCorp Vault, AWS Secrets Manager, Azure Key Vault, SOPS
- Environment variables for runtime injection; never bake secrets into container images or build artifacts
- Least privilege: each service/environment gets only the secrets it needs; no shared "master" secret set
- Rotation: automate secret rotation on a schedule; design applications to handle credential refresh without restart
- Audit logging: track who accessed which secret and when; alert on anomalous access patterns

## Gotchas / Anti-patterns
- **Revoking without rotating**: removing the secret from the repo but leaving the credential active; it is already in git history and CI logs
- **git filter-branch**: slow, error-prone; use `git filter-repo` instead
- **Secrets in CI logs**: printing environment variables or command outputs that contain secrets; mask them in CI configuration
- **Base64 as obfuscation**: encoding a secret in base64 does not hide it; scanners and attackers decode trivially
- **Shared secrets across environments**: production secret used in development; development compromise exposes production
- **Secrets in Docker layers**: `COPY .env .` or `ARG SECRET=...` bakes secrets into image layers; use runtime injection or multi-stage builds

## References
- detect-secrets (Yelp) — https://github.com/Yelp/detect-secrets
- gitleaks — https://github.com/gitleaks/gitleaks
- TruffleHog — https://github.com/trufflesecurity/trufflehog
- git-filter-repo — https://github.com/newren/git-filter-repo
- OWASP Secrets Management Cheat Sheet — comprehensive guidance
