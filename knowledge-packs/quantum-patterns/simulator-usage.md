# Quantum Simulator Usage

## When to use
- Developing and debugging quantum algorithms before running on hardware
- Verifying circuit correctness with access to the full quantum state (not possible on real hardware)
- Benchmarking algorithm performance under different noise models
- Scaling analysis to understand algorithm behavior beyond available hardware qubit counts

## Pattern

### Statevector Simulation
- Stores the full 2^N complex amplitude vector; exact simulation of the quantum state
- Enables inspection of amplitudes, entanglement, and intermediate states at any circuit point
- Memory: 2^N complex numbers (16 bytes each for double precision); 30 qubits = 16 GB, 34 qubits = 256 GB
- Use for: algorithm correctness verification, small-scale development (up to ~30 qubits on commodity hardware)
- No sampling noise: expectation values are exact (no shot noise); useful for isolating algorithmic issues from statistical effects
- Limitation: exponential memory scaling makes it impractical beyond ~40-45 qubits even on large servers

### Density Matrix Simulation
- Stores the full 2^N x 2^N density matrix; represents mixed states and decoherence
- Enables simulation of noise channels (depolarizing, amplitude damping, dephasing) applied as quantum operations
- Memory: 2^(2N) complex numbers; practical limit ~15-17 qubits on commodity hardware
- Use for: studying noise effects, verifying error mitigation techniques, open quantum system dynamics
- Can represent classical mixtures of states (thermal states, partially measured systems) that statevector cannot

### Noise Models
- **Depolarizing noise**: with probability p, replace qubit state with maximally mixed state; models generic gate imperfection
- **Amplitude damping**: models energy relaxation (T1 decay); qubit decays from |1> to |0>
- **Phase damping (dephasing)**: models loss of phase coherence (T2 decay); off-diagonal density matrix elements decay
- **Readout error**: probability of bit-flip during measurement; modeled as classical confusion matrix
- **Device noise model**: import calibration data from real hardware to replicate its noise profile in simulation
- Build noise models incrementally: start noiseless, add depolarizing, then gate-specific noise, then device-calibrated noise

### GPU-Accelerated Simulation
- Statevector operations are matrix-vector multiplications; map naturally to GPU parallelism
- GPU memory is the limit: 24 GB GPU handles ~30 qubits (statevector); multi-GPU can extend to ~33-35
- Frameworks: cuQuantum (NVIDIA), Qiskit Aer GPU, Pennylane Lightning.GPU
- Speedup over CPU: 10-100x for circuits with many qubits and moderate depth
- Tensor network simulation: an alternative that trades time for memory; can simulate certain circuits with 50+ qubits if entanglement is limited
- Use GPU simulation for: variational algorithm optimization loops, noise model sweeps, large-scale benchmarking

### Simulation Strategy by Use Case
| Use case | Simulator type | Qubit limit |
|---|---|---|
| Algorithm correctness | Statevector | ~30 |
| Noise characterization | Density matrix | ~16 |
| Variational training | Statevector + GPU | ~30 |
| Hardware prediction | Device noise model | ~16-20 |
| Large-scale structure | Tensor network | 50+ (low entanglement) |
| Clifford circuits | Stabilizer simulator | 1000+ (Clifford-only gates) |

### Verification Workflow
1. Implement circuit and test on statevector simulator (exact, noiseless)
2. Verify expected output state, intermediate states, and expectation values
3. Add noise model; verify error mitigation techniques recover noiseless results
4. Compare simulator results with hardware results; calibrate noise model if they diverge
5. Use simulator as oracle for regression testing: any code change that alters circuit output is flagged

## Gotchas / Anti-patterns
- **Simulating too many qubits**: attempting 40-qubit statevector on a laptop; will OOM or swap thrash
- **Noiseless-only development**: algorithms that work perfectly in simulation may completely fail on noisy hardware
- **Shot noise confusion**: running statevector with 1000 shots adds artificial sampling noise; use exact expectation values for debugging, shots for realism
- **Wrong noise model**: using generic depolarizing noise when the hardware has correlated or non-Markovian noise
- **Simulation as proof of advantage**: simulating a quantum algorithm classically and claiming quantum advantage is a contradiction
- **Ignoring simulation cost**: variational optimization with 10,000 iterations x 100 circuits each is expensive even in simulation; budget compute time

## References
- Qiskit Aer documentation — statevector, density matrix, and noise simulation
- NVIDIA cuQuantum SDK — GPU-accelerated quantum simulation
- Pennylane documentation — simulator backends and device interfaces
- Pednault et al., "Breaking the 49-qubit barrier in the simulation of quantum circuits" (2017) — tensor network methods
- Stim documentation — fast Clifford/stabilizer circuit simulation
