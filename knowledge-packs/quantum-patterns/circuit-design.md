# Quantum Circuit Design

## When to use
- Constructing quantum algorithms for execution on gate-based quantum computers
- Optimizing circuit depth and gate count for noisy intermediate-scale quantum (NISQ) devices
- Decomposing high-level quantum operations into native gate sets
- Managing auxiliary qubits (ancillas) for complex multi-qubit operations

## Pattern

### Gate Decomposition
- Target the native gate set of the backend hardware: typical sets include {CNOT, Rz, SX, X} (IBM) or {CZ, single-qubit rotations} (Google)
- Decompose arbitrary single-qubit unitaries using ZYZ decomposition: U = Rz(a) Ry(b) Rz(c) with global phase
- Multi-qubit gates: decompose Toffoli (CCX) into 6 CNOT + single-qubit gates; use relative-phase Toffoli for 3 CNOTs when phase is irrelevant
- Controlled-U gates: decompose into CNOTs + single-qubit rotations using standard identities
- Use transpiler tools to map abstract circuits to hardware-native gates; but understand the decompositions to guide optimization

### Circuit Depth Optimization
- Depth = longest path through the circuit; determines execution time and accumulated error
- Parallelize independent gates: operations on non-overlapping qubits can execute simultaneously
- Trade qubits for depth: uncompute/recompute patterns use extra ancillas to reduce serial dependencies
- Gate cancellation: adjacent inverse gates cancel (H-H, CNOT-CNOT, Rz(a)-Rz(-a)); transpilers do this but manual placement helps
- Commutation rules: gates that commute can be reordered to enable further cancellation or parallelism

### Ancilla Management
- **Borrowed ancillas**: use qubits temporarily, return them to original state (uncompute after use)
- **Dirty ancillas**: qubits in unknown state; some constructions work with dirty ancillas (e.g., relative-phase Toffoli)
- **Clean ancillas**: initialized to |0>; required for most standard constructions
- Budget ancillas: NISQ devices have limited qubits; minimize ancilla usage or reuse across algorithm phases
- Uncomputation: reverse the operations that entangled the ancilla with the computation; restore to |0> for reuse

### Common Circuit Patterns
- **QFT (Quantum Fourier Transform)**: Hadamard + controlled-phase rotations; appears in Shor's, QPE, and quantum arithmetic
- **Grover diffusion operator**: Hadamard-all, X-all, multi-controlled-Z, X-all, Hadamard-all; amplifies marked states
- **State preparation**: load classical data into quantum amplitudes; methods range from exact (exponential gates) to approximate (variational)
- **Quantum arithmetic**: ripple-carry or carry-lookahead adders; Draper QFT-based addition for depth reduction
- **Repeat-until-success**: probabilistic circuits that retry until measurement indicates success; post-select on ancilla

### Qubit Mapping and Routing
- Physical qubit connectivity is limited (not all-to-all); CNOT between non-adjacent qubits requires SWAP insertions
- SWAP overhead: each SWAP = 3 CNOTs; poor mapping can double circuit depth
- Initial mapping: place frequently interacting qubits on adjacent physical qubits
- Routing algorithms: SABRE, noise-aware routing; use transpiler but verify the output depth is acceptable
- Consider hardware topology: heavy-hex (IBM), grid (Google), all-to-all (trapped ion) — algorithms may prefer specific topologies

## Gotchas / Anti-patterns
- **Ignoring native gate set**: designing circuits with arbitrary gates then relying on transpiler to fix it; results in bloated circuits
- **Excessive ancilla usage**: using many clean ancillas when dirty or borrowed ancillas would suffice
- **No uncomputation**: leaving ancillas entangled with the computation corrupts the output state
- **Depth-blind design**: focusing on gate count while ignoring depth; depth determines error accumulation on real hardware
- **All-to-all connectivity assumption**: circuits that require many long-range CNOTs perform poorly on limited-connectivity hardware
- **Manual decomposition errors**: hand-decomposing multi-qubit gates is error-prone; verify with simulation

## References
- Nielsen & Chuang, "Quantum Computation and Quantum Information" — gate decomposition theory
- Qiskit Transpiler documentation — transpilation passes and optimization levels
- Barenco et al., "Elementary gates for quantum computation" (1995) — standard decompositions
- Amy et al., "A meet-in-the-middle algorithm for fast synthesis of depth-optimal quantum circuits" — depth optimization
