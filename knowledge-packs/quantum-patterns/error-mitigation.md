# Quantum Error Mitigation

## When to use
- Running quantum circuits on noisy hardware where full error correction is not available
- Extracting more accurate expectation values from noisy measurement results
- NISQ-era algorithms (VQE, QAOA) where circuit depth exceeds coherence limits
- Benchmarking to separate algorithmic signal from hardware noise

## Pattern

### Zero-Noise Extrapolation (ZNE)
- Run the same circuit at multiple noise levels; extrapolate to the zero-noise limit
- Noise amplification methods:
  - **Unitary folding**: insert gate-inverse-gate pairs (G G-dag G) to increase effective noise without changing ideal operation
  - **Pulse stretching**: on hardware with pulse-level access, stretch gate pulses to increase decoherence
- Extrapolation models: linear, polynomial, exponential fit to noise-level vs expectation-value curve
- Minimum 3 noise levels for polynomial extrapolation; more levels improve fit confidence
- Trade-off: requires 3-5x more circuit executions; each at the same shot count as the original

### Probabilistic Error Cancellation (PEC)
- Represent the ideal (noiseless) operation as a linear combination of noisy implementable operations
- Requires a noise model: characterize each gate's noise channel via process tomography or gate set tomography
- Sample from the quasi-probability distribution of noisy circuits; combine results with signed weights
- Overhead: sampling cost scales exponentially with circuit depth and noise strength (gamma factor)
- Best for: short circuits where noise is well-characterized; diminishing returns for deep circuits

### Readout Error Mitigation
- Measurement errors: qubit in |0> read as 1 (and vice versa) due to readout noise
- **Calibration matrix**: prepare each computational basis state, measure, build confusion matrix M
- **Correction**: apply M-inverse (or pseudo-inverse) to the measured probability distribution
- For N qubits, full calibration matrix is 2^N x 2^N — only practical for small systems
- **Tensor product mitigation**: assume independent readout errors per qubit; calibrate N 2x2 matrices instead of one 2^N matrix
- **M3 (Matrix-free Measurement Mitigation)**: scalable approach using iterative methods instead of explicit matrix inversion

### Symmetry Verification
- If the ideal circuit preserves a known symmetry (e.g., particle number, parity), post-select on results that satisfy it
- Discard measurement outcomes that violate the symmetry; they are noise-induced
- Can be combined with other mitigation methods for compounding improvement
- Cost: discarding outcomes reduces effective shot count; severe noise means most shots are discarded

### Twirling
- **Pauli twirling**: randomize over Pauli gates before and after a noisy gate to convert coherent errors into stochastic (depolarizing) noise
- Stochastic noise is easier to mitigate and model than coherent noise
- **Randomized compiling**: apply random Pauli frame changes at each layer; average over randomizations
- Overhead: multiple randomized circuit instances needed; 30-100 random instances is typical
- Particularly effective for suppressing crosstalk and systematic calibration errors

### Combining Techniques
- Layer techniques: readout mitigation (always applicable) + ZNE or PEC (gate error) + symmetry verification (post-selection)
- Each technique addresses a different noise source; combining improves overall accuracy
- Monitor the mitigation overhead budget: total shots = base_shots x ZNE_factor x twirling_instances x ...
- Diminishing returns: if the circuit is too noisy, no amount of mitigation recovers the signal; verify with noiseless simulation first

## Gotchas / Anti-patterns
- **Mitigating without characterizing noise**: PEC requires accurate noise model; wrong model gives wrong corrections
- **ZNE with wrong extrapolation model**: linear extrapolation when noise is exponential gives incorrect zero-noise estimate
- **Full calibration matrix for many qubits**: 2^N scaling makes it impractical beyond ~10 qubits; use tensor product or M3
- **Symmetry post-selection on highly noisy circuits**: discards nearly all data, leaving statistically insignificant sample
- **Ignoring shot budget**: mitigation techniques multiply the required shots; budget for total cost before committing
- **Assuming mitigation fixes everything**: mitigation improves estimates but has limits; it is not a substitute for error correction

## References
- Temme et al., "Error mitigation for short-depth quantum circuits" (2017) — PEC and ZNE foundations
- Kandala et al., "Error mitigation extends the computational reach of a noisy quantum processor" (2019)
- Qiskit Runtime Sampler/Estimator documentation — built-in mitigation options
- Mitiq documentation (Unitary Fund) — open-source error mitigation library
- Nation et al., "Scalable mitigation of measurement errors on quantum computers" (2021) — M3 method
