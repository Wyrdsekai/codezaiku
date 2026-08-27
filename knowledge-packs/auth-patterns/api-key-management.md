# API Key Management

## When to use
- Authenticating machine-to-machine or third-party integrations
- Providing a simpler alternative to OAuth for server-to-server communication
- Tracking and rate-limiting usage per integration or customer

## Pattern

### Key generation
- Generate keys using a CSPRNG: minimum 256 bits of entropy
- Use a recognizable prefix for identification and leak detection: `cp_live_` (production), `cp_test_` (sandbox)
- Format: `cp_live_a1b2c3d4e5f6...` — prefix helps security scanners detect leaked keys in logs and repos
- Generate the key once, display it once to the user, then never show it again in full

### Key storage (server-side)
- Hash the key before storing: `SHA-256(key)` is sufficient (unlike passwords, API keys have high entropy)
- Store: `{ key_hash, prefix, user_id, scopes, created_at, last_used_at, expires_at }`
- Store the prefix unhashed for identification (allows "key ending in ...x4f2" in the UI)
- Never store the plaintext key in the database

### Scoping and permissions
- Each key has explicit scopes: `read:orders`, `write:products`
- Support for resource-level scoping: "this key can only access project X"
- Environment separation: test keys cannot access production resources and vice versa
- IP allowlisting (optional): restrict key usage to specific source IPs

### Key rotation
- Support multiple active keys per integration — enables zero-downtime rotation
- Rotation workflow:
  1. Generate a new key
  2. Update the client to use the new key
  3. Verify the new key is working (check `last_used_at`)
  4. Revoke the old key
- Set maximum key age (e.g., 90 days); notify the owner before expiry
- Provide a CLI or API for programmatic rotation

### Rate limiting per key
- Apply rate limits scoped to each API key (not just per IP or per user)
- Different tiers: free keys get 100 req/min, paid keys get 10,000 req/min
- Return rate limit headers: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `Retry-After`
- Track usage metrics per key for billing and abuse detection

### Key transmission
- Send via `Authorization: Bearer cp_live_...` header — not in URL query parameters
- Query parameter keys appear in server logs, proxy logs, browser history, and referrer headers
- Always require HTTPS; reject requests over plain HTTP

### Revocation and audit
- Immediate revocation: delete or mark the key hash as revoked; check on every request
- Audit log: record key creation, usage, scope changes, and revocation
- Notify the owner when a key is used from a new IP or after a period of inactivity
- Provide a dashboard showing key activity: last used, request counts, error rates

## Gotchas / Anti-patterns
- **Storing plaintext keys**: database breach exposes all keys; always hash
- **Keys in URLs**: `?api_key=secret` leaks in logs everywhere; use the Authorization header
- **No expiration**: keys valid forever; a leaked key from 3 years ago still works
- **Single key per account**: cannot scope, cannot rotate without downtime; support multiple keys
- **No prefix**: leaked keys are indistinguishable from random strings; scanners cannot detect them

## References
- OWASP: API Security Top 10
- Stripe docs: API key management (prefix pattern, restricted keys)
- GitHub docs: fine-grained personal access tokens (scoping model)
- NIST SP 800-57: key management best practices
