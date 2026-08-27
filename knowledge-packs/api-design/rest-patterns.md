# REST API Patterns

## When to use
- Designing resource-oriented HTTP APIs for CRUD and beyond
- Establishing consistent conventions across multiple API endpoints
- Building APIs consumed by web frontends, mobile apps, and third-party integrations

## Pattern

### Resource naming
- Nouns, not verbs: `/orders`, `/users/{id}/addresses` — not `/getOrders`
- Plural collection names: `/products` (collection), `/products/{id}` (item)
- Nest for clear ownership: `/users/{userId}/orders` — but limit to 2 levels deep
- Use kebab-case for multi-word paths: `/order-items`, not `/orderItems`

### HTTP methods
- `GET`: retrieve (safe, idempotent, cacheable)
- `POST`: create a new resource or trigger a process (not idempotent by default)
- `PUT`: full replacement of a resource (idempotent)
- `PATCH`: partial update (idempotent when applied correctly; use JSON Merge Patch or JSON Patch)
- `DELETE`: remove a resource (idempotent — second call returns 404 or 204)

### Status codes
- `200 OK`: successful GET, PUT, PATCH
- `201 Created`: successful POST that creates a resource; include `Location` header
- `204 No Content`: successful DELETE or update with no response body
- `400 Bad Request`: malformed syntax, invalid parameters
- `401 Unauthorized`: missing or invalid authentication
- `403 Forbidden`: authenticated but insufficient permissions
- `404 Not Found`: resource does not exist
- `409 Conflict`: state conflict (e.g., duplicate creation, optimistic concurrency violation)
- `422 Unprocessable Content`: well-formed request but semantic validation failure
- `429 Too Many Requests`: rate limit exceeded; include `Retry-After` header
- `500 Internal Server Error`: unhandled server failure

### Pagination
- Default to cursor-based for large or frequently changing datasets
- Offset-based (`?offset=20&limit=10`) acceptable for small, stable datasets
- Always include pagination metadata: `next`, `previous` links or cursors, total count if cheap

### Filtering, sorting, searching
- Filter via query parameters: `?status=active&created_after=2026-01-01`
- Sort: `?sort=created_at` (ascending), `?sort=-created_at` (descending)
- Full-text search: `?q=search+term` on a dedicated search endpoint or as a filter param

### HATEOAS (optional but powerful)
- Include links in responses: `{ "self": "/orders/42", "cancel": "/orders/42/cancel" }`
- Clients discover available actions from the response, not hardcoded URL templates
- Reduces client coupling to URL structure

## Gotchas / Anti-patterns
- **Verbs in URLs**: `/api/getUser/123` — use `GET /api/users/123`
- **200 for everything**: returning `200` with `{ "error": "not found" }` — use proper status codes
- **Deep nesting**: `/users/{id}/orders/{id}/items/{id}/reviews` — flatten after 2 levels
- **Ignoring `Accept` / `Content-Type`**: always validate and respect content negotiation headers
- **Exposing internal IDs**: auto-increment database IDs leak information; prefer UUIDs or opaque identifiers

## References
- RFC 9110: HTTP Semantics
- RFC 9457: Problem Details for HTTP APIs
- Zalando RESTful API Guidelines
- Microsoft REST API Guidelines
- JSON:API specification
