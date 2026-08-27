match: prometheus
signature: write to wal, compaction failed: preallocate, opening storage failed
push: rescue
status: candidate
# Prometheus TSDB out of disk — fix procedure
1. "write to WAL: ... no space left on device" / "compaction failed: preallocate: no space left on device" = the TSDB volume is full; scrapes and compaction fail (often crashloops).
2. Confirm: `df -h <storage.tsdb.path>` (default /prometheus or /var/lib/prometheus) at 100%; `du -sh <path>/wal <path>/chunks_head`.
3. Fix: free space or grow the disk; lower retention `--storage.tsdb.retention.time=15d` or `--storage.tsdb.retention.size`; Prometheus stays wedged after a fill so restart it once space is free; if the WAL is corrupt, stop it, remove the bad `wal/` segment, then restart.
4. Recheck df has headroom and scrapes/compaction succeed in the log, then conclude (submit) immediately.
