# Offline Storage

## When to use
- App must function without network connectivity
- Data must persist across app restarts and OS-initiated process kills
- User-generated content must be saved locally before syncing to a server
- App caches server data to reduce latency and bandwidth usage

## Pattern

### SQLite for structured data
- Use SQLite for relational data, complex queries, and large datasets
- Access via a platform abstraction: Room (Android), Core Data or GRDB (iOS), Drift (Flutter)
- Define a schema with migrations versioned alongside the app
- Run migrations on app startup before any reads; never modify tables in place
- Use WAL (write-ahead logging) mode for concurrent read/write performance
- Keep the database file in the app's private data directory, not external storage

### Key-value stores
- Use for simple preferences, feature flags, tokens, and small configuration
- Platform options: SharedPreferences (Android), UserDefaults (iOS), or cross-platform libraries
- Do not store large blobs or collections in key-value stores -- use SQLite or files
- Encrypt sensitive values (tokens, credentials) at rest using the platform keychain/keystore

### File storage
- Store binary assets (images, documents, downloads) as files, with metadata pointers in SQLite
- Use the app's cache directory for re-downloadable content (OS can reclaim it)
- Use the app's documents directory for user-created content (backed up, not reclaimable)
- Generate unique filenames (UUID-based) to avoid collisions
- Clean up orphaned files when their metadata records are deleted

### Sync strategies
- **Pull-on-open**: fetch latest data when the screen opens; show cached data immediately, refresh in background
- **Push-on-change**: queue local mutations and sync to the server immediately (with retry)
- **Periodic sync**: background sync on a timer (every 15-60 minutes) using platform work schedulers
- **Event-driven sync**: server pushes a sync trigger via push notification or WebSocket
- Combine strategies: pull-on-open for reads, push-on-change for writes, periodic for catch-up

### Conflict handling
- Assign a version or modification timestamp to each record
- On sync conflict, apply a resolution policy: last-write-wins, server-wins, or prompt user
- For critical data, preserve both versions and let the user merge
- Log all conflict resolutions for debugging

### Storage limits and cleanup
- Monitor storage usage; warn the user before the app consumes excessive space
- Implement a cache eviction policy: LRU (least recently used) with a size cap
- Provide a "Clear cache" option in settings (clears re-downloadable content only)
- Never delete user-created content without explicit user confirmation

### Encryption at rest
- Encrypt the entire SQLite database using SQLCipher for sensitive applications
- Store encryption keys in the platform keychain (iOS Keychain, Android Keystore)
- For individual sensitive fields, encrypt at the field level before inserting into SQLite
- Clear decrypted data from memory when no longer needed

## Gotchas / Anti-patterns
- Storing structured data as JSON blobs in key-value stores -- unqueryable, slow, grows unbounded
- Not running SQLite migrations -- schema mismatch crashes on app update
- Storing large binary files in SQLite -- database bloat, slow queries; use the filesystem
- Putting user data in the cache directory -- OS will delete it without warning
- Not encrypting tokens and credentials at rest -- extractable from rooted/jailbroken devices
- Synchronous database queries on the UI thread -- causes jank and ANR (Application Not Responding)

## References
- SQLite Documentation: https://www.sqlite.org/docs.html
- Android Room: https://developer.android.com/training/data-storage/room
- iOS Core Data: https://developer.apple.com/documentation/coredata
- SQLCipher: https://www.zetetic.net/sqlcipher/
- Android Data Storage Guide: https://developer.android.com/training/data-storage
