# Intermediate Representation Design

## When to use
- Building a compiler that targets multiple backends (native, WASM, bytecode)
- Implementing optimization passes that should be target-independent
- Designing a bytecode interpreter with ahead-of-time optimization
- Analyzing program properties (data flow, aliasing, escape analysis)

## Pattern

### SSA Form (Static Single Assignment)
- Each variable is assigned exactly once; subsequent assignments create new versioned names (`x1`, `x2`, `x3`)
- Phi functions (`x3 = phi(x1, x2)`) merge values at control-flow join points
- SSA makes def-use chains explicit: every use points to exactly one definition
- Enables clean implementations of constant propagation, dead code elimination, and common subexpression elimination
- Construction: insert phi functions at dominance frontiers, then rename variables

### Basic Blocks and Control Flow Graphs
- A **basic block** is a maximal sequence of instructions with one entry (top) and one exit (bottom)
- The **CFG** is a directed graph where nodes are basic blocks and edges are branches
- Entry block has no predecessors; exit block(s) have no successors
- Identify basic blocks: the first instruction, any branch target, and the instruction after a branch each start a new block

### IR Instruction Design
- Keep the instruction set small and orthogonal — complex operations decompose into simpler ones
- Three-address form: `result = op(arg1, arg2)` — easy to analyze and rewrite
- Distinguish side-effecting instructions (store, call, I/O) from pure computations
- Memory operations: `load(address)`, `store(address, value)` — make memory access explicit
- Type every value: IR types (i32, i64, f64, ptr, void) are simpler than source-language types

### Dominance and Data Flow
- **Dominator tree**: block A dominates block B if every path from entry to B goes through A
- Dominance is the foundation for SSA construction and loop detection
- **Data flow analysis**: propagate facts forward (reaching definitions, available expressions) or backward (liveness, very busy expressions) through the CFG
- Fixed-point iteration: repeat until no fact changes; use a worklist for efficiency

### Optimization Passes
- **Constant folding**: evaluate compile-time-known expressions (`3 + 4` -> `7`)
- **Dead code elimination**: remove instructions whose results are never used
- **Common subexpression elimination**: reuse previously computed values
- **Inlining**: replace call with callee body (controlled by heuristics on size and call frequency)
- **Loop-invariant code motion**: hoist computations that do not change across loop iterations
- **Strength reduction**: replace expensive operations with cheaper equivalents (`x * 2` -> `x << 1`)
- Apply passes in a pipeline; some passes enable others (inlining enables constant propagation)

### Multiple IR Levels
- **High-level IR (HIR)**: close to source, preserves loops, structured control flow, source types
- **Mid-level IR (MIR)**: SSA form, lowered types, explicit memory operations, optimization target
- **Low-level IR (LIR)**: close to machine, register-like virtual registers, target-specific operations
- Each lowering step simplifies the representation and discards information not needed downstream
- Separate IRs allow different analyses at different abstraction levels

### Serialization
- Binary format for fast loading (e.g., LLVM bitcode, JVM class files)
- Text format for debugging and testing (e.g., LLVM `.ll`, WebAssembly `.wat`)
- Both formats should round-trip faithfully

## Gotchas / Anti-patterns
- **Skipping SSA**: optimizing a non-SSA IR is possible but every analysis becomes harder and buggier
- **Overly complex instruction set**: adding a special IR instruction for every source construct — keep IR orthogonal
- **Forgetting side effects**: reordering or eliminating instructions that have memory side effects corrupts semantics
- **Pass ordering sensitivity**: the output depends heavily on which passes run in which order — document and test the pipeline
- **Unbounded inlining**: inlining everything causes code size explosion and slower compilation
- **Losing debug info**: each lowering step should propagate source locations so debuggers can map back to source

## References
- Appel, A. "Modern Compiler Implementation in Java/ML/C", Cambridge University Press
- Cooper & Torczon, "Engineering a Compiler", 2nd edition (SSA, data flow, optimization)
- LLVM Language Reference: https://llvm.org/docs/LangRef.html
- Cranelift IR reference: https://github.com/bytecodealliance/wasmtime/tree/main/cranelift
- "SSA is Functional Programming" (Appel, 1998): https://www.cs.princeton.edu/~appel/papers/ssafun.pdf
