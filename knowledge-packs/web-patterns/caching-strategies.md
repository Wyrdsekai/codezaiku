# Caching Strategies

## When to use
- Reducing server load and improving response times for web applications
- Deciding cache placement (browser, CDN, application, database)
- Configuring HTTP cache headers for different content types

## Pattern

### Cache layers (ordered by proximity to user)
1. **Browser cache**: per-user, controlled by `Cache-Control` headers
2. **Service worker cache**: programmatic, enables offline — Cache API with fetch interception
3. **CDN / edge cache**: shared, geographically distributed, controlled by response headers and CDN config
4. **Application cache**: in-memory (Redis, local LRU) for computed results, sessions, rate limit counters
5. **Database query cache**: materialized views, query result cache — use sparingly, invalidation is hard

### HTTP Cache-Control patterns
- **Immutable assets** (hashed filenames: `app.a1b2c3.js`): `Cache-Control: public, max-age=31536000, immutable`
- **HTML pages**: `Cache-Control: no-cache` (always revalidate with server) or `private, max-age=0, must-revalidate`
- **API responses**: `Cache-Control: private, max-age=60` (short-lived, per-user) or `no-store` for sensitive data
- **Shared public data**: `Cache-Control: public, s-maxage=3600, stale-while-revalidate=86400`

### Stale-while-revalidate (SWR)
- Serve the stale cached response immediately while revalidating in the background
- User sees instant response; cache is updated for the next request
- Use for data that changes regularly but does not need real-time accuracy (feeds, dashboards, product listings)
- `Cache-Control: public, max-age=60, stale-while-revalidate=3600`

### Incremental Static Regeneration (ISR)
- Pre-render pages at build time; regenerate them on-demand after a time threshold
- First request after expiry triggers regeneration; users see the stale page until the new one is ready
- Use for content-heavy sites with thousands of pages (blogs, e-commerce catalogs)
- On-demand revalidation: trigger rebuild via webhook when content changes in the CMS

### Cache invalidation strategies
- **Time-based (TTL)**: simplest; acceptable staleness window
- **Event-driven purge**: CMS publish event, database change, webhook triggers a cache purge
- **Tag-based invalidation**: tag cached entries (e.g., `product:123`), purge by tag on update
- **Versioned keys**: change the cache key when data changes; old entries expire naturally

### ETag and conditional requests
- Server generates an ETag (hash of response body) and sends it with the response
- Client sends `If-None-Match: <etag>` on subsequent requests
- Server returns `304 Not Modified` if unchanged — saves bandwidth, validates freshness

## Gotchas / Anti-patterns
- **Caching authenticated responses on a shared CDN**: user A sees user B's data; use `Cache-Control: private` or `no-store`
- **Cache-busting with query strings**: some CDNs ignore query strings; use filename hashing instead
- **No cache invalidation plan**: data updates but users see stale content for hours; always define an invalidation strategy
- **Over-caching API mutations**: POST/PUT/DELETE responses should not be cached; only cache GET
- **Ignoring `Vary` header**: serving the same cached response regardless of `Accept-Language` or `Accept-Encoding`; set `Vary` correctly

## References
- MDN: HTTP caching, Cache-Control, ETag
- web.dev: HTTP cache best practices
- Vercel docs: Incremental Static Regeneration
- Cloudflare docs: cache rules and purge API
