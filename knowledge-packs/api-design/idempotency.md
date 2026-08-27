# Idempotency

## When to use
- POST endpoints that create resources or trigger side effects (payments, orders, emails)
- Any operation where network retries could cause duplicate processing
- Designing APIs that are safe to retry without unintended consequences

## Pattern

### Naturally idempotent methods
- `GET`, `HEAD`, `OPTIONS`: inherently safe and idempotent — no special handling needed
- `PUT`: replaces the entire resource; applying the same PUT twice produces the same result
- `DELETE`: deleting an already-deleted resource returns 404 or 204; same end state either way
- `POST`: not idempotent by default — this is where idempotency keys are needed

### Idempotency keys
- Client generates a unique key per logical operation (UUID v4 or ULID)
- Sent via header: `Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000`
- Server workflow:
  1. Receive request, check if the key exists in the idempotency store
  2. If key exists and request matches: return the stored response (same status code, same body)
  3. If key exists but request differs: return `422 Unprocessable Content` (key reuse with different payload)
  4. If key is new: process the request, store the response keyed by the idempotency key, return the response

### Storage and expiry
- Store: `{ key, request_hash, response_status, response_body, created_at }`
- Use a database or Redis with TTL (24-72 hours is typical)
- After expiry, the key can be reused (the operation window has passed)
- Hash the request body to detect mismatched reuse of the same key

### Exactly-once processing with locks
- Use a database unique constraint or distributed lock on the idempotency key
- Prevents race conditions where two concurrent retries both see "key not found"
- Flow: acquire lock -> check key -> process -> store result -> release lock
- If lock acquisition fails (another request in progress), return `409 Conflict` or wait briefly

### Retry-safe design beyond keys
- Make operations naturally idempotent where possible:
  - "Set balance to 100" is idempotent; "Add 10 to balance" is not
  - "Assign role X to user" is idempotent; "Toggle user's role" is not
- Use conditional writes: `UPDATE ... WHERE version = :expected_version`
- External systems (payment processors, email services): store the external transaction ID and check before re-triggering

### Client-side responsibilities
- Generate a new idempotency key for each distinct operation
- Reuse the same key when retrying the same operation
- Implement exponential backoff with jitter on retries
- Do not retry on `4xx` errors (except `429` and `503`) — those indicate client mistakes, not transient failures

## Gotchas / Anti-patterns
- **Server-generated idempotency keys**: defeats the purpose; the client must control the key to correlate retries
- **No expiry on stored keys**: the idempotency store grows unboundedly; always set a TTL
- **Ignoring request body mismatch**: returning a cached response even when the new request has a different payload
- **Idempotency keys on GET requests**: GET is already idempotent; adding keys is unnecessary overhead
- **Relying on network-level deduplication**: load balancers and proxies do not provide application-level idempotency

## References
- Stripe API: idempotent requests documentation
- IETF draft: The Idempotency-Key HTTP Header Field
- Amazon Pay: idempotency key best practices
- Martin Kleppmann: "Designing Data-Intensive Applications" — exactly-once semantics
