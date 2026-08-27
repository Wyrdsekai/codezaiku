# Data Versioning

## When to use
- Any ML project where datasets change over time (new labels, corrections, additions)
- Reproducibility is required: you must recreate exact training conditions from 6 months ago
- Multiple team members work on the same dataset concurrently
- Regulatory or audit requirements demand lineage tracking
- Dataset files are too large for Git (>100MB)

## Pattern

### Content-addressable storage
- Hash every file (or chunk) and store by hash; metadata layer maps version tags to hashes
- DVC uses this model: `.dvc` pointer files in Git, actual blobs in remote storage (S3, GCS, SSH). Note: lakeFS acquired DVC in November 2025 — DVC continues as open-source but is now part of the lakeFS ecosystem
- Oxen.ai: the fastest option — built in Rust, ~40x faster than git-lfs, uses Merkle trees for content addressing and DuckDB for row-level versioning of tabular data. Git-like CLI optimized for large binary/tabular datasets

### Version granularity
- **Snapshot versioning**: tag the entire dataset at a point in time (simplest, most common)
- **Row-level versioning**: track individual record additions/deletions (useful for append-only logs)
- **Diff-based versioning**: store deltas between versions (space-efficient, harder to reconstruct)

### Recommended workflow
1. Store raw data immutably in a versioned object store
2. Keep pointer files (DVC) or manifest files (custom) in Git alongside code
3. Tag dataset versions with semantic meaning: `v2.3-added-multilingual-labels`
4. Pin training runs to exact dataset version in experiment metadata
5. Automate integrity checks: hash verification on pull/checkout

### Storage backends
- Object storage (S3, GCS, MinIO) for large-scale; SSH/NFS for on-prem
- Deduplication via content-addressable hashing saves significant space across versions
- Compression at the storage layer (zstd, lz4) reduces transfer costs

## Gotchas / Anti-patterns
- **Git LFS for datasets**: works for small datasets but scales poorly beyond a few GB; LFS locking is fragile in teams
- **Versioning only the processed data**: always version raw data; processing code changes break reproducibility if you only kept the output
- **No deletion policy**: versions accumulate indefinitely; define retention and garbage collection early
- **Mutable datasets**: never overwrite a versioned artifact in-place; always create a new version
- **Ignoring metadata**: versioning files without recording schema, label definitions, and collection parameters is incomplete

## References
- DVC documentation: https://dvc.org/doc (now part of lakeFS)
- Oxen.ai: https://oxen.ai/docs — recommended for performance-critical workflows
- lakeFS: https://lakefs.io/ — Git-like branching for data lakes (acquired DVC Nov 2025)
- "Data Management for Machine Learning" — survey of versioning strategies
- Delta Lake / Apache Iceberg: table-format versioning for structured data
