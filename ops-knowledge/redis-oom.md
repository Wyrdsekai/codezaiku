match: redis
signature: oom command not allowed, used_memory, maxmemory
push: rescue
status: candidate
# Redis out-of-memory (maxmemory) — fix procedure
1. "OOM command not allowed when used memory > 'maxmemory'" means Redis hit its memory cap and, under a
   non-eviction policy, refuses writes.
2. Confirm: `redis-cli INFO memory` (used_memory vs maxmemory) and `redis-cli CONFIG GET maxmemory-policy`.
3. Decide the right lever:
   - cache workload → set an eviction policy: `redis-cli CONFIG SET maxmemory-policy allkeys-lru`.
   - genuinely need the data → raise the cap: `redis-cli CONFIG SET maxmemory <bytes>` (and persist).
   - a few huge keys → `redis-cli --bigkeys` finds them; expire/trim them.
4. Recheck writes succeed and used_memory drops below the cap, then conclude (submit) immediately.
