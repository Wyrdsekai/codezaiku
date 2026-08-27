# HPC I/O Patterns

## When to use
- Scientific simulations writing large datasets (GB to TB) to storage
- Parallel programs where many processes need coordinated file access
- Checkpoint/restart for long-running jobs that may be interrupted
- Post-processing workflows that read simulation output for analysis

## Pattern

### HDF5
- Hierarchical data format: groups (directories) containing datasets (N-dimensional arrays) with metadata
- Self-describing: data types, dimensions, and attributes stored alongside data
- Chunked storage: datasets divided into fixed-size chunks — enables compression and partial I/O
- Parallel HDF5 (via MPI-IO): multiple processes read/write a single file concurrently
- Compression: per-chunk transparent compression (gzip, LZ4, Blosc) — trades CPU for I/O bandwidth
- Hyperslab selection: read arbitrary rectangular subsets without loading the entire dataset

### NetCDF
- Built on HDF5 (NetCDF-4) or independent (NetCDF-3) — common in climate, ocean, and atmospheric science
- Dimension-variable model: named dimensions shared across variables
- CF Conventions: standardized variable names, units, coordinate systems for interoperability
- Parallel NetCDF (PnetCDF): independent parallel I/O library, can outperform parallel HDF5 for specific patterns
- Append-friendly: time dimension grows as simulation progresses

### Parallel I/O Strategies
- **Independent I/O**: each process writes its own file — simple, no coordination, hard to post-process
- **Collective I/O**: all processes coordinate through a single file — MPI-IO optimizes access patterns
- **Subfiling**: each process writes a local file, metadata file links them — balances simplicity and performance
- MPI-IO hints: stripe size, number of aggregators, filesystem-specific tuning
- Two-phase I/O (MPI-IO collective): processes exchange data so each writes a contiguous region

### Checkpoint/Restart
- Periodically save full simulation state to disk — resume after failure or job preemption
- Checkpoint interval: balance between checkpoint cost and lost computation on failure
- Optimal interval (Young's formula): `sqrt(2 * mean_time_between_failures * checkpoint_cost)`
- Incremental checkpoints: save only changed data — reduces I/O volume but adds complexity
- Async checkpoints: copy state to buffer, continue computing while background thread writes
- Multi-level: fast checkpoint to node-local SSD, periodic full checkpoint to parallel filesystem

### Filesystem Considerations
- Parallel filesystems (Lustre, GPFS/Spectrum Scale, BeeGFS): stripe files across OSTs for bandwidth
- Stripe count and stripe size tuning: more stripes = more bandwidth for large files, overhead for small files
- Metadata operations (open, stat, readdir) are slow — minimize file count, use a few large files
- Burst buffers: fast intermediate tier (NVMe) between compute nodes and parallel filesystem
- I/O forwarding: fewer nodes talk to filesystem — reduces metadata contention

### Data Formats and Serialization
- Binary over text: 10-100x faster I/O, smaller files, but not human-readable
- Endianness: HDF5/NetCDF handle byte order transparently — raw binary must manage it explicitly
- Schema evolution: version your checkpoint format — old checkpoints must be loadable by new code
- Compression trade-offs: lossless (reproducible) vs lossy (smaller, acceptable for visualization)
- ADIOS2: middleware abstracting I/O backends (HDF5, BP, SST streaming) — experiment without code changes

## Gotchas / Anti-patterns
- One file per process at scale (millions of files) — overwhelms filesystem metadata
- One shared file with uncoordinated independent I/O — seek storms destroy performance
- Opening/closing files frequently instead of keeping handles open — metadata overhead
- Small I/O requests (few KB) to parallel filesystem — below stripe size, wastes bandwidth
- Not setting MPI-IO hints for the target filesystem — default behavior is rarely optimal
- Checkpointing synchronously in the main loop — computation stalls during I/O
- Writing data in row-major order when the filesystem is optimized for column-major access (or vice versa)

## References
- HDF5 Documentation: https://docs.hdfgroup.org/hdf5/
- NetCDF Documentation: https://www.unidata.ucar.edu/software/netcdf/
- Parallel I/O in Practice (ALCF): https://www.alcf.anl.gov/support-center/theta/parallel-io
- ADIOS2: https://adios2.readthedocs.io/
- Lustre Tuning Guide: https://doc.lustre.org/
