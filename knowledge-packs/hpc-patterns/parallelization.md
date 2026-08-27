# Parallelization Patterns

## When to use
- Computation that exceeds what a single core can deliver in acceptable time
- Problems with natural decomposition into independent or loosely-coupled subproblems
- Scaling from a single node to clusters of nodes
- Reducing time-to-solution for large simulations, data processing, or analysis

## Pattern

### MPI (Message Passing Interface)
- Distributed memory: each process has private memory, communicates by sending messages
- Point-to-point: `MPI_Send` / `MPI_Recv` — explicit pairwise communication
- Collectives: `MPI_Bcast`, `MPI_Reduce`, `MPI_Alltoall` — optimized multi-process operations
- Non-blocking: `MPI_Isend` / `MPI_Irecv` — overlap communication with computation
- One-sided (RMA): `MPI_Put` / `MPI_Get` — direct remote memory access without receiver participation
- Communicators: group processes into subsets for independent collective operations

### OpenMP (Shared Memory)
- Thread-based parallelism within a single node via compiler pragmas
- `#pragma omp parallel for` — parallelize a loop, distribute iterations across threads
- `schedule(static)` for balanced work, `schedule(dynamic)` for imbalanced iterations
- `reduction(+:sum)` — each thread accumulates locally, combined at the end
- `critical` / `atomic` for shared variable updates — minimize scope for performance
- Task-based: `#pragma omp task` for irregular parallelism (trees, graphs, recursive algorithms)

### Domain Decomposition
- Split the problem domain into subdomains assigned to processes/threads
- 1D decomposition: simple, but surface-to-volume ratio is high — communication overhead
- 2D or 3D decomposition: lower communication ratio for 2D/3D grids
- Ghost/halo cells: each subdomain maintains a border copy of neighboring data for stencil operations
- Load balancing: equal subdomain size if work is uniform; dynamic partitioning if non-uniform

### Task Parallelism
- Work decomposed into tasks with dependencies, not uniform loop iterations
- DAG (directed acyclic graph) of tasks — scheduler executes tasks as dependencies are satisfied
- Work-stealing: idle threads steal tasks from busy threads' queues — auto-balances load
- Frameworks: Intel TBB, OpenMP tasks, Cilk, std::execution (C++17)
- Well-suited for irregular problems: tree traversals, sparse graph algorithms, adaptive mesh refinement

### Hybrid Parallelism (MPI + OpenMP)
- MPI between nodes, OpenMP threads within each node
- Reduces MPI process count — fewer messages, better memory utilization per node
- `MPI_THREAD_FUNNELED` or `MPI_THREAD_MULTIPLE` depending on thread-safety needs
- Typical: one MPI rank per socket or per node, OpenMP threads fill the cores
- Benefit depends on problem: communication-bound problems benefit most from hybrid

### Scaling Analysis
- **Strong scaling**: fixed total problem size, increase processor count — measures speedup
- **Weak scaling**: problem size grows proportionally with processor count — measures efficiency
- Amdahl's Law: serial fraction limits speedup — 5% serial = max 20x speedup regardless of cores
- Gustafson's Law: with larger problems, parallel fraction grows — more optimistic for scaled workloads
- Communication overhead grows with process count — eventually dominates and limits scaling

## Gotchas / Anti-patterns
- Parallelizing trivially small problems — overhead exceeds computation time
- Data races in shared memory: multiple threads writing same variable without synchronization
- Load imbalance: one process finishes early, waits idle for the slowest
- Over-decomposition: too many MPI ranks relative to work — communication dominates
- Implicit serialization: all ranks writing to a single file sequentially
- Assuming linear speedup — communication, synchronization, and serial sections prevent it
- Not profiling before parallelizing — optimizing the wrong part of the code

## References
- "Using MPI" (Gropp, Lusk, Skjellum) — definitive MPI reference
- OpenMP Specification: https://www.openmp.org/specifications/
- Intel TBB Documentation: https://www.intel.com/content/www/us/en/docs/onetbb/
- "Introduction to High Performance Computing for Scientists and Engineers" (Hager & Wellein)
- MPI Forum: https://www.mpi-forum.org/
