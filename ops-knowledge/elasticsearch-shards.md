match: elasticsearch, elastic, opensearch
signature: unassigned_shards, cluster health status red, status red, allocation_explain
push: rescue
status: candidate
# Elasticsearch red cluster / unassigned shards — fix procedure
1. RED cluster health means a PRIMARY shard is unassigned — some data is unreadable. `GET _cluster/health`
   and `GET _cat/shards?v | grep UNASSIGNED` show which.
2. Ask ES why, don't guess: `GET _cluster/allocation/explain` names the exact reason (node left, disk
   watermark, allocation filtering, max_retries exceeded after repeated failures).
3. Fix by the stated reason: node gone → bring it back or the replica promotes; watermark → free disk;
   "max_retries" → `POST _cluster/reroute?retry_failed=true`.
4. Recheck health returns to yellow/green (all primaries assigned), then conclude (submit) immediately.
