# Secure Defaults

## When to use
- Designing new systems, frameworks, libraries, or infrastructure configurations
- Reviewing existing systems for security posture baseline
- Establishing security requirements and architectural principles
- Building platforms where downstream developers will make security-relevant decisions

## Pattern

### Principle of Least Privilege
- Grant the minimum permissions necessary for each component to perform its function
- Default to no access: explicitly grant required permissions rather than starting with full access and restricting
- Service accounts: dedicated identity per service with only the permissions that service needs; no shared admin accounts
- Database access: application accounts get SELECT/INSERT/UPDATE on their tables only; never use DBA accounts in application code
- File system: application processes own their data directories; read-only access to configuration; no access to system directories
- Cloud IAM: use resource-specific policies, not `*:*`; review and prune unused permissions regularly
- Review access quarterly: permissions accumulate over time; audit and remove unused grants

### Defense in Depth
- Layer multiple security controls; do not rely on any single mechanism
- Network: firewall + network segmentation + TLS + application-level authentication
- Application: input validation + parameterized queries + output encoding + WAF
- Data: encryption at rest + encryption in transit + access control + audit logging
- Identity: password + MFA + session management + anomaly detection
- Each layer should function independently: if one layer is bypassed, others still protect
- Assume breach: design internal controls as if the perimeter has already been compromised

### Fail-Secure Design
- When a system fails, it should deny access rather than grant it
- Authentication failure: deny access, do not fall through to an unauthenticated path
- Authorization failure: return 403/deny, do not expose the resource with degraded permissions
- Error handling: do not expose stack traces, internal paths, or database errors to users; log internally, return generic messages
- Circuit breakers: when a dependent security service (auth, authz) is unavailable, fail closed (deny) not open (allow)
- Configuration errors: if security configuration is missing or malformed, refuse to start rather than running with defaults that are less secure
- Timeouts: treat timeout as failure; do not default to "allowed" when an authorization check times out

### Secure by Default Configuration
- **HTTPS everywhere**: redirect HTTP to HTTPS; set HSTS; no plaintext HTTP endpoints
- **Authentication required**: all endpoints require authentication unless explicitly marked public
- **CORS restrictive**: do not use `Access-Control-Allow-Origin: *` by default; allowlist specific origins
- **Cookie security**: `Secure`, `HttpOnly`, `SameSite=Lax` (or `Strict`) flags on all cookies by default
- **CSP (Content Security Policy)**: start restrictive (`default-src 'self'`); add exceptions as needed with justification
- **Password policy**: minimum 12 characters, check against breach databases (HaveIBeenPwned), no arbitrary complexity rules
- **Session management**: short idle timeout (15-30 min), absolute timeout (8-12 hours), regenerate session ID on login
- **Logging**: log authentication events, authorization failures, input validation failures, administrative actions

### Deny by Default
- Firewalls: default deny all; explicitly allow required traffic by port, protocol, and source
- API gateways: require authentication and rate limiting on all routes by default
- File uploads: reject by default; allowlist specific MIME types and extensions
- Outbound traffic: restrict egress to known-required destinations; prevents data exfiltration and C2 communication
- Feature flags: new features default to off; enable explicitly after security review

### Secure Development Lifecycle
- Threat modeling: identify threats during design, not after deployment; STRIDE or attack trees
- Security requirements: derive from threat model; track as first-class requirements alongside functional requirements
- Code review: security-focused review checklist for authentication, authorization, input validation, crypto usage
- Dependency management: automated vulnerability scanning, pinned versions, lockfiles committed
- Pre-production security testing: SAST + DAST + SCA as CI gates before deployment
- Incident response plan: documented and rehearsed before incidents occur; includes communication, containment, recovery

### Observability for Security
- Audit log every security-relevant action: who did what, when, from where, and whether it succeeded
- Centralize logs: ship to a tamper-resistant log aggregation system; retention per compliance requirements
- Alerting: automated alerts on anomalies — failed login spikes, privilege escalation, unusual data access patterns
- Immutable audit trail: logs cannot be modified or deleted by application accounts; separate log infrastructure

## Gotchas / Anti-patterns
- **Security as afterthought**: bolting security onto a finished system is more expensive and less effective than building it in
- **Fail-open defaults**: "if auth is down, let everyone through" — guarantees exploitation during outages
- **Overly permissive CORS**: `*` origin in production allows any website to make authenticated requests to your API
- **Security through obscurity**: relying on hidden URLs, non-standard ports, or obfuscated code as the primary defense
- **Checkbox compliance**: meeting the minimum compliance requirement without actually securing the system
- **Alert without response**: generating security alerts that no one monitors or acts on; detection without response is just logging

## References
- OWASP Application Security Verification Standard (ASVS) — security requirements by level
- NIST SP 800-53 — security and privacy controls catalog
- CIS Benchmarks — hardening guides for OS, cloud, databases, containers
- OWASP Proactive Controls — top 10 security techniques for developers
- Saltzer & Schroeder, "The Protection of Information in Computer Systems" (1975) — foundational security design principles
