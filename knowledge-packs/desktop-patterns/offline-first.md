# Offline-First

## When to use
- Application must function without a network connection (field work, travel, unreliable connectivity)
- Data is created or modified locally and synced to a remote server when online
- Multiple users or devices may edit the same data concurrently
- Users expect zero-latency reads and writes regardless of network state

## Pattern

### Local storage as source of truth
- Read and write to local storage first; treat the network as an optimization, not a dependency
- Use a local database (SQLite, LevelDB, or an embedded key-value store) rather than raw files
- All UI reads come from local storage -- never block the UI on a network request
- Queue mutations locally; sync in the background when connectivity is available

### Connectivity detection
- Monitor network state actively: online, offline, metered, degraded
- On desktop: check with OS APIs (`NWPathMonitor` on macOS, `NetworkInformation` on Windows, `NetworkManager` on Linux)
- Do not rely solely on "can I reach the internet" pings -- check the actual sync endpoint
- Debounce state transitions to avoid rapid online/offline flapping

### Sync on reconnect
- Maintain a local queue of unsynced mutations with timestamps and operation type
- On reconnect, replay the queue in order against the remote server
- Use exponential backoff for retry on transient failures
- Show sync status to the user: "3 changes pending sync", "Last synced: 5 minutes ago"
- Support manual "Sync now" trigger for impatient users

### Conflict resolution strategies
- **Last-write-wins (LWW)**: simplest; use synchronized timestamps or logical clocks; acceptable for low-contention data
- **Field-level merge**: merge non-conflicting field changes automatically; flag true conflicts for user resolution
- **CRDT-based**: use conflict-free replicated data types for automatic convergence (counters, sets, registers)
- **Manual resolution**: present both versions to the user with a diff; let them choose or merge manually
- Always preserve both versions until the conflict is resolved -- never silently discard data

### Conflict resolution UI
- Show a clear visual indicator on conflicted items (warning badge, color highlight)
- Present a side-by-side diff with clear labels: "Your version" vs "Remote version"
- Provide actions: "Keep mine", "Keep theirs", "Merge manually"
- Log conflict resolutions for audit trail

### Data versioning
- Assign a version or vector clock to each record
- Store change history (event log or append-only changelog) alongside current state
- Support "undo" by replaying the log minus the undone operation
- Periodically compact the log to bound storage growth

### Cache management
- Cache frequently accessed remote resources (images, documents, reference data) locally
- Set cache size limits; evict least-recently-used entries when the limit is reached
- Serve stale cache data immediately; refresh in the background ("stale-while-revalidate")
- Distinguish between "cached and fresh", "cached but stale", and "not cached"

## Gotchas / Anti-patterns
- Treating offline as an error state instead of a normal operating mode
- Blocking the UI until the network request completes -- defeats the purpose of offline-first
- Last-write-wins without user awareness -- silently losing the other user's changes
- Unbounded local storage growth -- eventually fills the disk without compaction
- Not showing sync status -- users do not know if their changes have been synced
- Assuming clocks are synchronized -- use logical clocks or server-assigned timestamps for ordering

## References
- Local-First Software: https://www.inkandswitch.com/local-first/
- CRDTs: https://crdt.tech/
- SQLite as an Application File Format: https://www.sqlite.org/appfileformat.html
- Automerge (CRDT library): https://automerge.org/
- Yjs (CRDT framework): https://yjs.dev/
