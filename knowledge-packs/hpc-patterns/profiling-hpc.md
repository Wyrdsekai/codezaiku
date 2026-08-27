# HPC Profiling Patterns

## When to use
- Performance of parallel code is below expected theoretical throughput
- Need to identify whether code is compute-bound, memory-bound, or communication-bound
- Scaling efficiency drops as node count increases
- Optimizing hot loops before investing in algorithmic changes

## Pattern

### Performance Counters
- Hardware counters: instructions retired, cache misses (L1/L2/L3), branch mispredictions, FLOPS
- Access via PAPI, perf_event (Linux), or vendor tools (Intel VTune, AMD uProf)
- Key ratios: instructions per cycle (IPC), cache miss rate, FLOP rate vs peak
- Low IPC + high cache misses = memory-bound; high IPC near peak = compute-bound
- Sample-based profiling: periodic interrupt records program counter — low overhead, statistical accuracy

### Roofline Model
- Plot attainable performance (FLOP/s) as a function of operational intensity (FLOP/byte)
- Roof = min(peak_compute, peak_bandwidth * operational_intensity)
- Memory-bound: below the bandwidth roof — optimize data access, improve locality
- Compute-bound: below the compute roof — vectorize, reduce instruction count
- Plot your kernel on the roofline to see which ceiling limits it and by how much
- Intel Advisor and NVIDIA Nsight Compute generate roofline plots automatically

### Bottleneck Identification
- **Compute-bound**: high CPU utilization, high IPC, near peak FLOP/s — need algorithmic improvement or better vectorization
- **Memory-bound**: low IPC, high cache miss rate, stalled on loads — improve data layout, prefetching, blocking
- **Communication-bound**: processes idle waiting for MPI calls — reduce message count, overlap with compute
- **I/O-bound**: processes blocked on file operations — use async I/O, parallel filesystem tuning
- **Load imbalance**: some processes finish early, wait at barriers — redistribute work

### Communication Profiling
- MPI profiling (PMPI interface): intercept MPI calls to measure time, count, and volume
- Tools: Intel Trace Analyzer, Scalasca, TAU, Score-P
- Identify: time spent in MPI_Wait (communication), MPI_Barrier (synchronization), collective operations
- Communication pattern: who sends to whom, how much — visualize as communication matrix
- Overlap metric: fraction of communication time overlapped with computation

### Memory Access Profiling
- Cache simulation: Valgrind/Cachegrind counts cache hits/misses per source line
- Hardware counters for L1/L2/L3 miss rates — identify data structures with poor locality
- NUMA profiling: detect remote memory access on NUMA systems (numactl, likwid)
- Memory bandwidth measurement: STREAM benchmark for peak, compare with application usage
- Prefetch analysis: is the hardware prefetcher effective? Manual prefetch hints if not

### Scaling Profiling
- Profile at multiple process/thread counts to find scaling bottlenecks
- Strong scaling: plot speedup vs processor count — deviation from linear shows overhead
- Break down time into: compute, communication, synchronization, I/O per process count
- Communication overhead typically grows with log(P) for collectives, P for all-to-all
- Identify the critical path: longest chain of dependent operations across all processes

## Gotchas / Anti-patterns
- Profiling with compiler optimizations disabled — results don't represent real performance
- Using wall-clock time only — hides where time is spent (compute vs communication vs idle)
- Over-instrumenting: too many measurement points alter timing behavior (probe effect)
- Optimizing cold code that represents <1% of runtime — profile first, optimize hot spots
- Comparing FLOP/s without considering operational intensity — a kernel at 10 GFLOP/s may be at its roofline
- Profiling a single MPI rank and assuming all ranks behave identically — load imbalance is invisible
- Not accounting for turbo boost, thermal throttling, and frequency scaling in measurements

## References
- Intel VTune Profiler: https://www.intel.com/content/www/us/en/developer/tools/oneapi/vtune-profiler.html
- LIKWID Performance Tools: https://github.com/RRZE-HPC/likwid
- Score-P Instrumentation: https://www.vi-hps.org/projects/score-p/
- Roofline Model (Williams, Waterman, Patterson): https://crd.lbl.gov/divisions/amcr/computer-science-amcr/par/research/roofline/
- TAU Performance System: https://www.cs.uoregon.edu/research/tau/
