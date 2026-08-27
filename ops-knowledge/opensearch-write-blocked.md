match: opensearch, elasticsearch, elastic
signature: cluster_block_exception, index.blocks.write, blocked by, forbidden/, read_only, index read-only
push: rescue
status: candidate
# OpenSearch/Elasticsearch index writes blocked — fix procedure
1. Indexing fails with `cluster_block_exception` ("blocked by: FORBIDDEN/...") while reads/searches still
   work — the index has a WRITE BLOCK set (index.blocks.write / read_only / read_only_allow_delete).
2. Do NOT judge this from cluster health COLOR — a yellow/unassigned-replica is unrelated and does NOT mean
   "not blocked". Check the INDEX SETTINGS: `curl <es-url>/<index>/_settings` and look for any
   `index.blocks.*` = true.
3. Clear the block directly: `curl -XPUT <es-url>/<index>/_settings -H 'Content-Type: application/json'
   -d '{"index.blocks.write":null,"index.blocks.read_only":null,"index.blocks.read_only_allow_delete":null}'`
   (if read_only_allow_delete is from a FULL disk, free disk first).
4. Confirm indexing a doc returns 2xx, then conclude.
