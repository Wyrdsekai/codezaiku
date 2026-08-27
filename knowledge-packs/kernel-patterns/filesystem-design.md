# Filesystem Design Patterns

## When to use
- Implementing a new filesystem or understanding existing filesystem internals
- Choosing between filesystem designs for specific workloads
- Understanding the VFS abstraction layer and how filesystems plug into it
- Designing for data integrity, crash recovery, and performance

## Pattern

### VFS Layer
- Virtual Filesystem Switch: uniform interface (open/read/write/stat) regardless of underlying FS
- Key abstractions: superblock (mounted FS), inode (on-disk object), dentry (directory entry cache), file (open instance)
- Each filesystem implements `super_operations`, `inode_operations`, `file_operations`
- Dentry cache accelerates path lookups — avoids hitting disk for repeated directory traversals
- Page cache: file data cached in memory pages, read/write through page cache by default

### Block Layer
- Filesystem issues I/O in block-sized units (typically 4KB) via the block layer
- I/O schedulers merge and reorder requests for throughput (mq-deadline, BFQ, none/noop for NVMe)
- Bio structures represent block I/O requests — can span multiple pages
- Direct I/O bypasses page cache — used when application manages its own caching (databases)
- Barrier/flush operations ensure write ordering — critical for journaling

### Journaling
- Write-ahead log: metadata changes (and optionally data) written to journal before in-place update
- Journal replay on crash recovery: re-apply committed transactions, discard incomplete ones
- Modes: journal (full data+metadata), ordered (metadata journaled, data written first), writeback (metadata only)
- Journal size trade-off: larger journal allows more in-flight transactions but slower replay
- Checkpointing: periodically flush journal to main filesystem area and reclaim journal space

### Copy-on-Write (COW) Filesystems
- Never modify data in place — write new copy, then atomically update pointer
- Inherently crash-consistent — old data valid until new tree committed
- Snapshots are nearly free — just preserve the old root pointer
- Fragmentation over time as data scatters across disk — periodic defragmentation needed
- Self-healing with checksums: detect and repair corruption using redundant copies (Btrfs, ZFS)

### Allocation Strategies
- Extent-based: contiguous block ranges described by (start, length) — efficient for large files
- Bitmap allocator: one bit per block — simple, O(1) per block, but scanning for free space is O(n)
- B-tree free space tracking: scales better than bitmap for very large filesystems
- Delayed allocation: buffer writes in memory, allocate blocks at flush time — better contiguity
- Preallocation: reserve blocks for a file that will grow — reduces fragmentation for append workloads

### Integrity and Checksums
- Metadata checksums: detect silent corruption in inodes, directory entries, tree nodes
- Data checksums: detect bitrot in file data — COW filesystems store alongside data blocks
- Scrubbing: background read of all data to verify checksums before errors are user-visible
- Redundancy: metadata mirrored (Btrfs DUP), data optionally mirrored or parity-protected
- fsck/repair: offline consistency check as last resort — goal is to never need it via journaling/COW

### Performance Patterns
- Inline data: store tiny files directly in the inode — avoids block allocation overhead
- Directory indexing: hash-based or B-tree index for large directories (not linear scan)
- Compression: transparent per-file or per-extent compression (zstd, lz4) — trades CPU for I/O
- Multi-device: stripe across devices for throughput, mirror for redundancy (filesystem-level RAID)

## Gotchas / Anti-patterns
- fsync() on file but not the directory — rename is not durable until directory is synced
- Assuming write ordering without barriers — disk and controller reorder writes
- Small random writes on COW filesystem — extreme fragmentation, write amplification
- Deleting large files blocks the filesystem — extent freeing can take seconds
- Not reserving space for root — filesystem 100% full makes the system unbootable
- Hard links across subvolumes/snapshots — breaks the COW model
- Benchmarking on empty filesystem — performance characteristics change as FS fills up

## References
- Linux VFS Documentation: https://docs.kernel.org/filesystems/vfs.html
- Btrfs Design: https://btrfs.readthedocs.io/en/latest/
- ZFS on Linux: https://openzfs.github.io/openzfs-docs/
- ext4 Disk Layout: https://ext4.wiki.kernel.org/index.php/Ext4_Disk_Layout
- XFS Architecture: https://xfs.wiki.kernel.org/
