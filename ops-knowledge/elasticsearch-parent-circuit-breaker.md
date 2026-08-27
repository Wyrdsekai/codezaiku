match: elasticsearch
signature: circuit_breaking_exception, data too large, would be larger than limit
push: rescue
status: candidate
# Elasticsearch parent circuit breaker tripped — fix procedure
1. "circuit_breaking_exception ... [parent] Data too large ... would be larger than limit" = JVM heap pressure; ES rejects requests (429) to avoid OOM.
2. Confirm: `curl -s localhost:9200/_nodes/stats/breaker?pretty` (parent tripped count) and `.../jvm?pretty` (heap.used_percent high).
3. Fix: relieve heap — cut heavy aggregations / large bulk request sizes, reduce field-data and shard count; clear caches `curl -XPOST localhost:9200/_cache/clear`; raise heap in jvm.options Xms=Xmx (<=50% RAM, <32g) and restart; add a node if chronically full.
4. Recheck the breaker no longer trips and heap.used_percent drops, then conclude (submit) immediately.
