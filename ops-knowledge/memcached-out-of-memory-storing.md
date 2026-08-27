match: memcached
signature: out of memory storing object, server_error out of memory, evictions
push: rescue
status: candidate
# memcached out of memory storing object — fix procedure
1. "SERVER_ERROR out of memory storing object" = a slab class is full and eviction couldn't free a chunk fast enough, so the set fails.
2. Confirm: `echo stats | nc -q1 localhost 11211 | egrep 'limit_maxbytes|bytes |evictions|curr_items'`; `echo 'stats slabs' | nc -q1 localhost 11211` to spot the saturated slab class.
3. Fix: raise capacity — restart with a larger `-m` (edit /etc/memcached.conf -m MB); ensure sane TTLs so keys expire; for oversized values grow item size `-I`/growth `-f` or shard the load; confirm no runaway huge keys.
4. Recheck sets succeed and evictions stop climbing, then conclude (submit) immediately.
