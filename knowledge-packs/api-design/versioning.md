# API Versioning

## When to use
- Evolving an API while maintaining backward compatibility for existing consumers
- Deciding when and how to introduce breaking changes
- Planning deprecation and migration timelines

## Pattern

### Versioning strategies

**URL path versioning**: `/v1/users`, `/v2/users`
- Most explicit and visible; easy for clients to understand
- Each version is a separate set of routes
- Caching is simple — URL changes mean different cache entries
- Downside: can lead to full API duplication if not carefully layered

**Header versioning**: `Accept: application/vnd.myapi.v2+json` or custom `API-Version: 2`
- URL stays clean; version is a content negotiation concern
- Harder to test in a browser or share API links
- Works well when combined with content negotiation

**Query parameter versioning**: `/users?version=2`
- Simple to use but easy to forget; not widely recommended
- Caching can break if CDNs strip query params

### Recommended approach
- Default to URL path versioning for public APIs — clarity outweighs elegance
- Use header versioning for internal APIs where client libraries handle the header
- Version at the API level, not per-endpoint — mixed versions across endpoints confuse consumers

### What constitutes a breaking change
- Removing a field or endpoint
- Changing a field's type or meaning
- Changing a required field to optional (or vice versa)
- Altering error response structure or status codes for existing error cases
- Renaming a field (from the client's perspective, the old name is gone)

### Non-breaking (additive) changes
- Adding a new optional field to a response
- Adding a new endpoint
- Adding a new optional query parameter
- Adding a new enum value (if clients handle unknown values gracefully)

### Deprecation process
1. Announce deprecation: add `Deprecated` header, update docs, notify consumers
2. Set a sunset date: `Sunset: Sat, 01 Jan 2027 00:00:00 GMT` (RFC 8594)
3. Monitor usage of the deprecated version — do not remove while active consumers remain
4. Provide a migration guide with concrete before/after examples
5. Remove after the sunset date, returning `410 Gone`

### Internal versioning (without URL versions)
- Tolerant reader pattern: clients ignore unknown fields, handle missing optional fields
- Additive-only changes: never remove, always add
- Feature flags: enable new behavior per-client via feature flags in the request context
- Works for tightly controlled internal APIs; insufficient for public APIs

## Gotchas / Anti-patterns
- **Versioning too early**: creating `/v1` before you have any consumers; you will never need v2 for most endpoints
- **Too many active versions**: supporting v1 through v7 simultaneously; deprecate aggressively, aim for at most 2 concurrent versions
- **Breaking changes disguised as non-breaking**: changing the semantic meaning of a field without changing its name
- **No deprecation notices**: removing an endpoint without warning; always announce with a sunset date
- **Versioning individual endpoints**: `/v1/users` but `/v3/orders` — version the entire API surface together

## References
- RFC 8594: The Sunset HTTP Header Field
- Stripe API versioning: date-based version pinning (example of a well-executed approach)
- Microsoft REST API Guidelines: versioning section
- Google API Design Guide (aip.dev): compatibility and versioning
