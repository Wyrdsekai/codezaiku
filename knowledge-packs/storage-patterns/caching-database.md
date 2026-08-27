# Database Caching Patterns

## When to use
- Query latency exceeds application requirements and cannot be solved by indexing alone
- Read-heavy workloads repeatedly access the same data
- Expensive aggregations or joins need to be precomputed
- Scaling read throughput without adding more database replicas

## Pattern

### Query Result Cache
- Cache the result of a query keyed by the query text (or a normalized/parameterized form) + parameter values
- The database engine itself may provide this (MySQL query cache, though deprecated; PostgreSQL does not cache query results natively)
- Application-level: compute a cache key from the query + params, store the result in Redis/Memcached
- Invalidate on any write to the underlying tables — coarse-grained but simple
- Best for: expensive read-only queries that do not change frequently

### Materialized Views
- A precomputed result set stored as a table, defined by a query over base tables
- The database maintains it (refreshed on write or on demand, depending on the engine)
- Use for: aggregation dashboards, denormalized read models, complex joins that are expensive to compute on every read
- **Eagerly refreshed**: updated on every write to the base tables (always current, adds write cost)
- **Lazily refreshed**: updated on a schedule or on demand (`REFRESH MATERIALIZED VIEW`) — stale but cheap
- PostgreSQL supports `REFRESH MATERIALIZED VIEW CONCURRENTLY` to avoid locking readers during refresh

### Application-Level Cache Strategies

#### Cache-Aside (Lazy Loading)
- Application reads from cache first; on miss, reads from database, writes result to cache, returns to caller
- The cache only contains data that has been requested — no wasted memory on unread data
- Stale data risk: if the database is updated, the cache still holds the old value until TTL expires or explicit invalidation
- Most common pattern; simple to implement

#### Write-Through
- Application writes to the cache and the database together (or the cache writes through to the database)
- Cache is always current — no stale reads
- Every write pays the cost of updating the cache, even for data that may never be read
- Combine with cache-aside for reads: write-through handles freshness, cache-aside handles population

#### Write-Behind (Write-Back)
- Application writes to the cache; the cache asynchronously flushes to the database
- Lowest write latency (the application only waits for the cache)
- Risk: data loss if the cache crashes before flushing to the database
- Use only when you can tolerate brief data loss or have a durable cache (e.g., Redis with AOF)

#### Read-Through
- The cache itself fetches from the database on a miss (the application only talks to the cache)
- Simplifies application code — the cache layer handles population
- Requires the cache to understand how to query the database (tighter coupling)

### Cache Invalidation
- **TTL (Time-To-Live)**: entries expire after a fixed duration — simple, bounded staleness
- **Event-driven invalidation**: listen to database change events (CDC, triggers, application events) and evict affected entries
- **Tag-based invalidation**: tag cache entries with the entities they depend on; invalidate all entries with a given tag when that entity changes
- **Versioned keys**: include a version number in the cache key; bump the version on write — old entries are never read, eventually evicted by LRU
- No single strategy fits all cases — combine TTL (as a safety net) with event-driven invalidation (for freshness)

### Cache Key Design
- Include all parameters that affect the result: query params, user ID (if personalized), locale, pagination offset
- Normalize the key: sort parameters, lowercase strings, canonicalize — prevent duplicate entries for the same logical query
- Prefix keys with a namespace to avoid collisions between different subsystems
- Keep keys short — long keys waste memory and network bandwidth in cache systems

### Cache Sizing and Eviction
- Size the cache to hold the working set — the subset of data accessed in a typical time window
- **LRU (Least Recently Used)**: evict the entry that has not been accessed for the longest time — good default
- **LFU (Least Frequently Used)**: evict the entry with the fewest accesses — better for stable access patterns
- **W-TinyLFU** (Caffeine, Ristretto): combines recency and frequency; state-of-the-art hit rates
- Monitor hit rate: below 80% suggests the cache is too small or the access pattern is too random to benefit

### Thundering Herd / Cache Stampede
- When a popular entry expires, many concurrent requests miss the cache and all hit the database simultaneously
- **Lock/single-flight**: only one request fetches from the database; others wait for the result
- **Stale-while-revalidate**: serve the stale entry while one request refreshes it in the background
- **Probabilistic early expiration**: each request has a small chance of refreshing the entry before it expires, spreading the load

## Gotchas / Anti-patterns
- **Caching without invalidation strategy**: data goes stale and stays stale — always define how and when entries are evicted
- **Caching everything**: caching low-frequency or unique queries wastes memory and adds complexity for no benefit
- **Cache as source of truth**: if the cache is lost, the system should recover from the database — the database is the source of truth
- **Ignoring cold start**: after a deploy or cache flush, all requests miss — warm the cache proactively for critical data
- **Double caching**: the ORM caches, the application caches, and Redis caches — three layers of stale data and memory waste
- **No monitoring**: without hit rate and latency metrics, you cannot tell if the cache is helping or hurting

## References
- Kleppmann, M. "Designing Data-Intensive Applications", Chapter 3 and Chapter 11 (O'Reilly, 2017)
- Caffeine (Java high-performance cache): https://github.com/ben-manes/caffeine
- Redis caching patterns: https://redis.io/docs/manual/patterns/
- Facebook TAO (social graph cache): https://www.usenix.org/conference/atc13/technical-sessions/presentation/bronson
- "Scaling Memcache at Facebook" (Nishtala et al., 2013)
