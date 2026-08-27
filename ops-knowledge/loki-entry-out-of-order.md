match: loki
signature: entry out of order, entry too far behind, out_of_order
push: rescue
status: candidate
# Loki entry out of order — fix procedure
1. 400 "entry out of order" / "entry too far behind" (reason out_of_order) = a stream pushed a timestamp older than Loki's accept window, so those lines are rejected.
2. Confirm: check distributor/ingester logs or the client push 400s; identify the offending stream labels; note current `max_chunk_age` (accepts newer than newest - max_chunk_age/2).
3. Fix: make the sender push monotonically per stream (don't merge multiple sources under one label set; add a distinguishing label); if legitimately late/bursty, widen the window `limits_config.max_chunk_age: 2h` and reload; keep senders NTP-synced.
4. Recheck the push 400s stop and lines ingest, then conclude (submit) immediately.
