# Indexing Strategies

## When to use
- Choosing the right index type for a database table or storage engine
- Diagnosing slow queries that scan more data than necessary
- Designing a storage engine or embedded database
- Evaluating trade-offs between read performance and write amplification

## Pattern

### B-Tree Index
- The default index in most relational databases (PostgreSQL, MySQL, SQLite)
- Balanced tree of sorted keys; each node is typically one disk page (4-16 KB)
- Supports: point lookups, range scans, prefix searches, ordered iteration
- O(log N) lookups with a branching factor of 100-500, so even billions of rows need only 3-4 page reads
- Writes update the tree in-place with page splits when nodes overflow
- Best for: read-heavy workloads, range queries, workloads needing sorted order

### LSM-Tree (Log-Structured Merge Tree)
- Writes go to an in-memory buffer (memtable); when full, flush as a sorted immutable file (SSTable) to disk
- Background compaction merges SSTables to remove duplicates and maintain read performance
- Reads check the memtable first, then each level of SSTables (use bloom filters to skip irrelevant files)
- Write amplification from compaction, but sequential I/O makes writes faster than B-tree random I/O
- Best for: write-heavy workloads, time-series data, append-mostly patterns
- Used by: RocksDB, LevelDB, Cassandra, ScyllaDB

### Hash Index
- Maps keys to values via a hash function; O(1) average-case lookups
- No ordering: cannot support range queries, prefix searches, or sorted iteration
- In-memory hash tables are straightforward; on-disk hash indexes (Bitcask) use an append-only log with an in-memory hash map of key-to-file-offset
- Best for: exact-match lookups with no range requirements (session stores, caches)

### Bitmap Index
- One bit per row per distinct value; the bit is 1 if the row has that value
- Queries on low-cardinality columns (status, gender, boolean flags) become fast bitwise AND/OR operations
- Highly compressible (run-length encoding) when most bits are 0
- Poor for high-cardinality columns (unique IDs) — bitmaps become as large as the table
- Best for: analytical queries with many low-cardinality filters (data warehouses)

### Choosing the Right Index
- **Point lookups only, no ordering**: hash index
- **Point lookups + range scans + ordering**: B-tree
- **Write-heavy, append-mostly**: LSM-tree
- **Low-cardinality analytical filters**: bitmap index
- **Full-text search**: inverted index (separate pattern; see dedicated references)
- **Geospatial queries**: R-tree or space-filling curve index (Z-order, Hilbert)
- **High-dimensional similarity**: vector index (HNSW, IVF) — relevant for ML embeddings

### Composite (Multi-Column) Indexes
- A B-tree on (A, B, C) supports queries filtering on A, (A, B), or (A, B, C) — not B alone
- Column order matters: put the most selective or most frequently filtered column first
- Covering index: if the index contains all columns a query needs, the table lookup is avoided entirely

### Index Maintenance
- Every index adds write overhead: inserts update the table *and* every index
- Unused indexes waste space and slow writes — audit and drop them
- Rebuild or reorganize indexes periodically if page splits cause fragmentation (B-tree specific)
- Monitor index hit rates: an index that is never used by the query planner is dead weight

## Gotchas / Anti-patterns
- **Indexing every column**: multiplies write cost and storage for indexes that may never be queried
- **Wrong column order in composite index**: `(B, A)` does not help a query filtering only on `A`
- **Hash index for range queries**: hash indexes cannot satisfy `WHERE price > 100`
- **LSM-tree with no bloom filters**: read amplification explodes as you check every SSTable level
- **Bitmap index on high-cardinality column**: bitmap is larger than the raw data; no benefit
- **Ignoring write amplification**: LSM compaction or B-tree page splits can dominate I/O on write-heavy workloads

## References
- Kleppmann, M. "Designing Data-Intensive Applications", Chapter 3 (O'Reilly, 2017)
- PostgreSQL index types: https://www.postgresql.org/docs/current/indexes-types.html
- RocksDB wiki (LSM internals): https://github.com/facebook/rocksdb/wiki
- SQLite B-tree internals: https://www.sqlite.org/btreemodule.html
- Use The Index, Luke (SQL indexing guide): https://use-the-index-luke.com/
