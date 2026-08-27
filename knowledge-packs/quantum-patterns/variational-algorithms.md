# Variational Quantum Algorithms

## When to use
- Solving optimization problems (combinatorial, molecular energy, scheduling) on NISQ hardware
- Circuit depth must be kept short due to hardware noise and limited coherence time
- Hybrid classical-quantum approach: quantum circuit evaluates cost function, classical optimizer tunes parameters
- Exploring quantum advantage for specific problem instances before fault-tolerant hardware is available

## Pattern

### VQE (Variational Quantum Eigensolver)
- Goal: find the ground state energy of a Hamiltonian H (molecular simulation, materials science)
- Circuit prepares a parameterized trial state |psi(theta)>; measure <psi(theta)|H|psi(theta)>
- Hamiltonian decomposition: express H as a sum of Pauli strings; measure each Pauli term's expectation value separately
- Grouping: commuting Pauli terms can be measured simultaneously, reducing total measurement circuits
- Classical optimizer adjusts theta to minimize the measured energy
- Variational principle guarantees: measured energy >= true ground state energy (provides an upper bound)

### QAOA (Quantum Approximate Optimization Algorithm)
- Goal: approximately solve combinatorial optimization problems (MaxCut, graph coloring, scheduling)
- Circuit alternates: cost unitary exp(-i * gamma * C) and mixer unitary exp(-i * beta * B), repeated p times
- Cost operator C encodes the objective function; mixer B drives exploration (typically sum of Pauli-X)
- Depth parameter p: more layers = better approximation but deeper circuit; p=1-3 typical for NISQ
- Parameters (gamma, beta) optimized classically; landscape analysis can inform starting points
- Problem encoding: map binary decision variables to qubits; constraints become penalty terms in C

### Ansatz Design
- **Hardware-efficient ansatz**: layers of single-qubit rotations + entangling gates matched to hardware topology; low depth but can have barren plateaus
- **Chemistry-inspired ansatz**: UCCSD (Unitary Coupled Cluster); preserves particle number symmetry; high gate count but physically motivated
- **Problem-specific ansatz**: structure the circuit to respect problem symmetries and conserved quantities
- **ADAPT-VQE**: grow the ansatz one operator at a time based on gradient magnitude; avoids over-parameterization
- Depth budget: ansatz depth x error-per-gate < 1 for meaningful results; shorter is better on NISQ hardware
- Expressibility vs trainability: highly expressive ansatze can represent more states but may have vanishing gradients

### Parameter Optimization
- **Gradient-based**: parameter shift rule computes exact gradients on quantum hardware (2 circuit evaluations per parameter per gradient component)
- **Gradient-free**: COBYLA, Nelder-Mead, SPSA; fewer circuit evaluations per iteration but slower convergence
- **SPSA (Simultaneous Perturbation Stochastic Approximation)**: approximates gradient with only 2 circuit evaluations regardless of parameter count; good for noisy objectives
- Noise-aware optimizers: use methods robust to shot noise and hardware noise (SPSA, ImFil, Bayesian optimization)
- Local minima: variational landscapes are non-convex; use multiple random initializations or informed warm-starts
- Convergence criteria: energy change below threshold for N consecutive iterations, or iteration budget exhausted

### Shot Budget and Measurement
- Each expectation value estimate requires many measurement shots (1000-10000 typical)
- Shot allocation: allocate more shots to Pauli terms with larger coefficients (variance-weighted)
- Measurement grouping: simultaneously measure commuting Pauli terms to reduce total circuits
- Classical shadow tomography: efficient protocol for estimating many observables from fewer measurements
- Total cost: (number of Pauli groups) x (shots per group) x (optimizer iterations) = total circuit executions

## Gotchas / Anti-patterns
- **Barren plateaus**: gradients vanish exponentially with qubit count for deep random circuits; use shallow, structured ansatze
- **Too many parameters**: over-parameterized ansatz is hard to optimize and prone to barren plateaus
- **Ignoring shot noise**: using noiseless simulation to develop then deploying on hardware with 1000 shots; the optimizer sees a very noisy landscape
- **Fixed shot count**: using the same shots for early (rough) and late (fine) optimization; ramp shots up as optimization converges
- **No reference comparison**: always compare VQE/QAOA results against classical solutions for validation during development
- **Encoding overhead**: mapping constraints as penalties can require many additional qubits and high penalty weights

## References
- Peruzzo et al., "A variational eigenvalue solver on a photonic quantum processor" (2014) — original VQE
- Farhi et al., "A Quantum Approximate Optimization Algorithm" (2014) — original QAOA
- Cerezo et al., "Variational quantum algorithms" (2021) — comprehensive review
- McClean et al., "Barren plateaus in quantum neural network training landscapes" (2018)
- Qiskit / Pennylane / Cirq documentation — variational algorithm implementations
