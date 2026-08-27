# Rate Limiting

## When to use
- Protecting APIs from abuse, DoS, and noisy neighbors
- Enforcing fair usage quotas across consumers
- Preventing cascading failures when downstream services are overloaded

## Pattern

### Algorithms

**Token bucket**
- A bucket fills with tokens at a steady rate; each request consumes one token
- Allows short bursts (up to the bucket capacity) while enforcing an average rate
- Most common choice for API rate limiting; simple and burst-friendly

**Sliding window log**
- Track timestamps of all requests in the window; count entries in the current window
- Precise but memory-intensive for high-throughput endpoints
- Good for strict per-second limits where accuracy matters

**Sliding window counter**
- Weighted combination of the current and previous window counts
- Approximation that uses constant memory (two counters per window)
- Good balance of accuracy and efficiency for most use cases

**Fixed window**
- Count requests in fixed time windows (e.g., per minute)
- Simple but allows 2x burst at window boundaries (end of one window + start of next)
- Acceptable for coarse-grained limits; avoid for strict enforcement

### Scope dimensions
- **Per-user / per-API-key**: prevents a single consumer from monopolizing capacity
- **Per-IP**: fallback for unauthenticated endpoints; unreliable behind NAT/proxies
- **Per-endpoint**: different limits for expensive operations (search: 10/min) vs cheap ones (healthcheck: unlimited)
- **Global**: total request capacity across all consumers; protects infrastructure

### Response headers
- `X-RateLimit-Limit`: maximum requests allowed in the window
- `X-RateLimit-Remaining`: requests remaining in the current window
- `X-RateLimit-Reset`: Unix timestamp when the window resets
- `Retry-After`: seconds (or date) to wait before retrying — required on `429` responses
- Draft standard: `RateLimit` header (IETF draft-ietf-httpapi-ratelimit-headers)

### Implementation placement
- **API gateway / reverse proxy**: simplest; centralized, no code changes (Nginx, Kong, Envoy)
- **Application middleware**: more flexible scoping (per-user, per-endpoint, per-role)
- **Distributed rate limiting**: use Redis or a similar shared store when running multiple instances
- **Client-side**: respect `Retry-After`, implement exponential backoff, never tight-loop on 429

### Graduated response
1. Soft limit: return `X-RateLimit-Remaining: 0` as a warning
2. Hard limit: return `429 Too Many Requests` with `Retry-After`
3. Escalation: progressively longer cooldowns for persistent violators
4. Block: temporary IP/key block for extreme abuse

## Gotchas / Anti-patterns
- **No rate limit on authenticated endpoints**: assuming auth = trust; compromised keys can DoS your API
- **Fixed window only**: allows 2x burst at boundaries; use sliding window or token bucket
- **Missing Retry-After header**: clients cannot implement correct backoff without it
- **Rate limiting by IP alone**: shared IPs (corporate NAT, cloud functions) penalize innocent users
- **Same limit for all endpoints**: an expensive search endpoint and a lightweight status endpoint should not share the same limit

## References
- IETF draft: RateLimit header fields for HTTP
- Stripe docs: rate limiting approach
- Cloudflare docs: rate limiting rules
- Redis documentation: rate limiting patterns with MULTI/EVAL
