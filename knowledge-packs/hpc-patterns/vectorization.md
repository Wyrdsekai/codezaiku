# Vectorization Patterns

## When to use
- Tight numerical loops processing arrays of data (stencils, linear algebra, signal processing)
- Data-parallel operations where the same instruction applies to multiple elements
- Performance-critical kernels where single-core throughput matters
- Exploiting modern CPU SIMD units (SSE, AVX2, AVX-512, ARM NEON/SVE)

## Pattern

### SIMD Fundamentals
- Single Instruction, Multiple Data: one instruction operates on a vector of elements simultaneously
- SSE: 128-bit registers (4 floats, 2 doubles)
- AVX2: 256-bit registers (8 floats, 4 doubles)
- AVX-512: 512-bit registers (16 floats, 8 doubles)
- ARM NEON: 128-bit, SVE: scalable width (128-2048 bits)
- Theoretical throughput multiplier: vector width / scalar width (e.g., 8x for AVX2 float)

### Auto-Vectorization Hints
- Write simple loops: single entry/exit, no function calls, no complex control flow
- Use `restrict` (C) or `__restrict` (C++) to promise no pointer aliasing
- Align data to vector width boundaries: `alignas(32)` for AVX2, `alignas(64)` for AVX-512
- Trip count: compiler needs to know or estimate iteration count — use compile-time constants when possible
- Compiler flags: `-O2 -march=native` (GCC/Clang), `/O2 /arch:AVX2` (MSVC)
- Check vectorization reports: `-fopt-info-vec` (GCC), `-Rpass=loop-vectorize` (Clang)

### Data Layout for SIMD
- **Array of Structures (AoS)**: `struct { float x, y, z; } particles[N]` — poor for SIMD (strides)
- **Structure of Arrays (SoA)**: `struct { float x[N], y[N], z[N]; }` — ideal for SIMD (contiguous)
- **AoSoA** (Array of Structure of Arrays): `struct { float x[8], y[8], z[8]; } groups[N/8]` — hybrid, cache-friendly and SIMD-friendly
- SoA enables full vector loads: `_mm256_load_ps(&positions_x[i])` loads 8 consecutive x values
- Padding: pad arrays to vector width multiples to avoid remainder loop handling

### Explicit Intrinsics
- Direct mapping to hardware instructions: `_mm256_add_ps`, `_mm256_mul_ps`, `_mm256_fmadd_ps`
- Maximum control but non-portable — different intrinsics per ISA
- Use for critical inner loops where auto-vectorization fails or produces suboptimal code
- Wrapper libraries (Highway, xsimd, Vc) provide portable SIMD across architectures
- FMA (fused multiply-add): `a*b+c` in one instruction — better precision and performance

### Masking and Predication
- AVX-512 mask registers: selectively operate on subset of vector lanes
- ARM SVE: predicate registers for all operations — natural for variable-length vectors
- Use masks for: boundary handling (last iteration), conditional operations, gather/scatter
- Without masking: remainder loop handles leftover elements — scalar, slower

### Reduction Patterns
- Sum, min, max across vector elements — horizontal operations
- Horizontal operations are slower than vertical — reduce across vectors first, horizontal last
- Tree reduction: add pairs of vectors, then pairs of results, then horizontal sum of final vector
- For large arrays: accumulate into multiple vector accumulators to hide latency, reduce at the end

## Gotchas / Anti-patterns
- Unaligned memory access — works but slower on most architectures (especially AVX-512)
- AoS data layout — forces gather/scatter instructions, negating SIMD benefit
- Assuming auto-vectorization happened without checking compiler output
- Mixing SIMD widths in the same function without proper transitions (AVX/SSE penalty on older CPUs)
- Branch-heavy code inside SIMD loops — branches are scalar, break vectorization
- Ignoring denormalized floats — SIMD denormal handling can severely impact throughput
- Writing intrinsics for one ISA without fallback — crashes on older hardware

## References
- Intel Intrinsics Guide: https://www.intel.com/content/www/us/en/docs/intrinsics-guide/
- Google Highway (portable SIMD): https://github.com/google/highway
- "Hacker's Delight" (Henry Warren) — bit manipulation and SIMD tricks
- Agner Fog's Optimization Manuals: https://www.agner.org/optimize/
- ARM NEON Intrinsics Reference: https://developer.arm.com/architectures/instruction-sets/intrinsics/
