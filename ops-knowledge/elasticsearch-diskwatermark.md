match: elasticsearch, elastic, opensearch
signature: flood stage disk watermark, read-only-allow-delete, disk watermark
push: immediate
status: validated
# Elasticsearch flood-stage disk watermark — fix procedure
1. A node past the flood-stage watermark makes ES set every index `read_only_allow_delete` and writes fail
   (log: "flood stage disk watermark ... exceeded ... marked read-only").
2. The fix is to FREE DISK — clearing the block on a still-full node does NOT work (ES re-applies it within
   ~30s). Find the space: `GET _cat/allocation?v` and `GET _cat/indices?v&s=store.size:desc`.
3. Delete EXPENDABLE data — the oldest time-based index, `curl -XDELETE <es-url>/<oldest-index>` (or remove
   a stray large file / grow the volume) — until the node drops below the high watermark. Keep live indices.
4. ES then auto-clears the block (>=7.4); else `PUT _all/_settings {"index.blocks.read_only_allow_delete":
   null}`. Confirm a write returns 2xx, then conclude.
