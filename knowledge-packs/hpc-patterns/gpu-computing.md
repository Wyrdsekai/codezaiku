# GPU Computing Patterns

## When to use
- Massively data-parallel problems: same operation on millions of elements
- Workloads with high arithmetic intensity that can saturate GPU compute units
- Linear algebra, FFT, stencil computations, particle simulations, deep learning
- When CPU parallelism is insufficient and GPU hardware is available

## Pattern

### Kernel Design
- A kernel is a function executed by thousands of threads simultaneously on the GPU
- Thread hierarchy: threads → warps/wavefronts (32/64 threads) → thread blocks → grid
- Each thread computes one or a few output elements — fine-grained parallelism
- Thread block size: typically 128 or 256 threads — must be a multiple of warp size
- Grid size: enough blocks to cover the problem domain — `ceil(N / block_size)` blocks

### Memory Hierarchy
- **Registers**: fastest, private per thread — use for local variables
- **Shared memory**: on-chip, shared within a block — explicit cache for cooperative algorithms
- **L1/L2 cache**: automatic, transparent — benefits from coalesced access patterns
- **Global memory (VRAM)**: large, high latency (~400 cycles) — coalesced access is critical
- **Constant memory**: cached, broadcast to all threads reading the same address — use for parameters
- Memory coalescing: adjacent threads access adjacent memory addresses — one transaction instead of 32

### Occupancy
- Occupancy = active warps / maximum warps per SM (streaming multiprocessor)
- Higher occupancy hides memory latency through warp switching
- Limited by: registers per thread, shared memory per block, threads per block
- Use occupancy calculator to find optimal block size for your kernel's resource usage
- Sometimes lower occupancy with more registers per thread outperforms higher occupancy — profile to decide

### Synchronization and Atomics
- `__syncthreads()` (CUDA) / `barrier()` (HIP): synchronize all threads in a block
- No global synchronization within a kernel — use multiple kernel launches
- Atomics (`atomicAdd`, `atomicCAS`): thread-safe global memory updates — slow if highly contended
- Warp-level primitives (`__shfl`, `__ballot`): fast communication within a warp without shared memory
- Cooperative groups (CUDA): flexible synchronization scopes beyond block level

### Reduction Pattern
- Sum/min/max of an array: each thread loads one element, cooperatively reduce within block
- Shared memory reduction: iteratively halve active threads, combine pairs
- Warp shuffle reduction for final 32 elements — avoids shared memory bank conflicts
- Launch a second kernel (or atomic) to reduce block results to a single value
- Use multiple accumulators per thread before the tree reduction — hides memory latency

### Data Transfer
- Host-to-device transfer is a bottleneck — PCIe bandwidth is 10-50x lower than GPU memory bandwidth
- Overlap: use CUDA streams or HIP streams to overlap transfer with compute
- Pinned (page-locked) host memory: required for async transfers, higher transfer bandwidth
- Unified memory: automatic migration between host and device — convenient but can be slower than explicit
- Minimize transfers: keep data on GPU across kernel launches, avoid round-tripping through CPU

### Multi-GPU
- Data parallelism: split problem across GPUs, each processes a subset
- Peer-to-peer: GPUs directly access each other's memory (NVLink) — faster than going through host
- NCCL (NVIDIA) / RCCL (AMD): optimized collective communication library for multi-GPU
- GPU-aware MPI: MPI operations directly on GPU buffers without copying to host
- Load balancing: assign work proportional to GPU capability if GPUs are heterogeneous

## Gotchas / Anti-patterns
- Launching kernels with too few threads — GPU is underutilized, most SMs idle
- Non-coalesced memory access — 32x bandwidth reduction in worst case
- Excessive host-device transfers — PCIe becomes the bottleneck, GPU sits idle
- Shared memory bank conflicts — 32-way conflict serializes to 32 sequential accesses
- Thread divergence within a warp — both branches execute, one masked off (wasted work)
- Small kernels with high launch overhead — batch work into larger kernels
- Assuming GPU is always faster — small problems with transfer overhead are faster on CPU

## References
- CUDA Programming Guide: https://docs.nvidia.com/cuda/cuda-c-programming-guide/
- HIP Programming Guide (AMD): https://rocm.docs.amd.com/projects/HIP/
- "Programming Massively Parallel Processors" (Kirk & Hwu) — standard GPU computing textbook
- NVIDIA Nsight Compute: https://developer.nvidia.com/nsight-compute
- CUDA Samples: https://github.com/NVIDIA/cuda-samples
