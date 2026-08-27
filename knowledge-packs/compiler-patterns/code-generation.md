# Code Generation Patterns

## When to use
- Translating IR to machine code or a target bytecode (LLVM IR, WASM, JVM bytecode)
- Implementing a compiler backend for one or more target architectures
- Choosing between JIT and AOT compilation strategies
- Optimizing generated code for performance or code size

## Pattern

### LLVM as a Backend
- Emit LLVM IR (text `.ll` or bitcode `.bc`) from your mid-level IR
- LLVM handles: instruction selection, register allocation, scheduling, target-specific optimization
- Map your IR types to LLVM types: `i8`, `i32`, `i64`, `float`, `double`, `ptr`
- Use LLVM intrinsics for operations that do not map to simple instructions (overflow-checked arithmetic, vector ops)
- Link with `clang` or `lld` for object file generation and linking

### Instruction Selection
- **Tree matching**: represent IR as expression trees, match target instruction patterns bottom-up
- **Macro expansion**: each IR instruction maps to a fixed sequence of target instructions (simple but suboptimal)
- **DAG-based selection** (LLVM approach): convert basic blocks to SelectionDAGs, pattern-match and lower
- Cover every IR operation: if an IR op has no direct target instruction, decompose it (legalization)

### Register Allocation
- Goal: map unlimited virtual registers to a finite set of physical registers, spilling to stack when necessary
- **Graph coloring**: build an interference graph (two virtuals that are live simultaneously get an edge), color with k registers
- **Linear scan**: process live intervals in order, assign registers greedily — faster than graph coloring, good enough for JIT
- Handle register classes: integer regs, float regs, vector regs — each virtual belongs to a class
- Spill heuristics: spill the variable with the longest remaining live range or the one used least frequently

### Calling Conventions
- Define how arguments are passed (registers, then stack), return values, caller-saved vs callee-saved registers
- Follow the platform ABI (System V AMD64, ARM AAPCS, Windows x64) for interoperability with C libraries
- Implement a custom calling convention for internal (non-exported) functions if it improves performance (e.g., pass more args in registers)
- Correctly handle varargs, struct passing/returning, and alignment requirements

### Stack Frame Layout
- Prologue: save callee-saved registers, allocate stack frame (`sub rsp, N`)
- Epilogue: restore registers, deallocate frame, return
- Place spilled variables, local allocations, and outgoing arguments in the frame
- Align the stack to the ABI requirement (16 bytes on x86-64) before every call
- Generate unwind information (DWARF `.eh_frame`, Windows SEH) for debuggers and exception handling

### JIT Compilation
- Allocate executable memory (`mmap` with `PROT_EXEC`, or platform equivalent)
- Generate machine code directly into the buffer, patch relocations in-place
- Use a tiered strategy: interpret first, JIT-compile hot functions after profiling
- Deoptimization: if a speculative optimization is invalidated, fall back to interpreted or re-compiled code
- Security: mark memory writable during codegen, then switch to executable-only (W^X policy)

### WebAssembly as a Target
- Emit WASM bytecode: stack-based, strongly typed, sandboxed by design
- Map IR types to WASM types: `i32`, `i64`, `f32`, `f64`, `funcref`, `externref`
- Linear memory model: heap is a flat byte array, accessed by `i32.load` / `i32.store`
- Export functions via the WASM module interface; import host functions for I/O
- Optimize for code size (important for network transfer): use `wasm-opt` as a post-processing step

## Gotchas / Anti-patterns
- **Ignoring the ABI**: internal functions work but calling into C or being called from C crashes due to mismatched conventions
- **Forgetting stack alignment**: segfaults on `movaps` (SSE) instructions that require 16-byte alignment
- **No unwind info**: debuggers cannot produce stack traces; C++ exceptions cannot propagate through your frames
- **JIT without W^X**: writable-and-executable memory is a security vulnerability; always toggle permissions
- **Premature register allocation**: allocating registers before optimization passes limits what the optimizer can do
- **Hardcoding one architecture**: write the backend with a target abstraction even if you only support x86-64 today

## References
- LLVM Tutorial (Kaleidoscope): https://llvm.org/docs/tutorial/
- Cranelift (Rust JIT/AOT backend): https://cranelift.dev/
- QBE (small C backend, educational): https://c9x.me/compile/
- WebAssembly Specification: https://webassembly.github.io/spec/
- System V AMD64 ABI: https://gitlab.com/x86-psABIs/x86-64-ABI
- "Linear Scan Register Allocation" (Poletto & Sarkar, 1999)
