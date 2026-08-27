match: qdrant, vector, vector database, vector search
signature: no space left on device, wal buffer size exceeds available disk space, service internal error, disk full, enospc
push: rescue
status: candidate
# Qdrant out of disk — upserts failing — fix procedure
1. Upserts return HTTP 500 "Service internal error: No space left on device" (a WAL/segment write) while
   reads and searches still work — the storage volume is full. The collection may still report "green".
2. Qdrant's data and WAL live under the storage dir (default `/qdrant/storage`). Check it: `df` that path,
   and `du -sh` its contents to see what fills it — a stray large file, or unbounded snapshots/segments.
3. FREE DISK: delete the stray file or old snapshots (or grow the volume) until the storage volume has room.
   Do NOT delete or recreate the collection — that discards the indexed vectors; only writes are blocked,
   the data is intact.
4. Confirm an upsert succeeds again (a search still returns existing points), then conclude.
