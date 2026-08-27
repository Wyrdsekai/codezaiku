# Quantum Machine Learning

## When to use
- Exploring quantum-enhanced feature spaces for classification or regression tasks
- Data has structure that may benefit from quantum kernel evaluation (high-dimensional, periodic, group-structured)
- Investigating hybrid quantum-classical models for specific ML tasks
- Research and prototyping of quantum ML algorithms before fault-tolerant hardware

## Pattern

### Quantum Kernel Methods
- Encode classical data into quantum states using a feature map circuit: x -> |phi(x)>
- Kernel value: K(x_i, x_j) = |<phi(x_i)|phi(x_j)>|^2 — overlap of quantum feature states
- Feed the quantum kernel matrix into classical SVM, kernel PCA, or other kernel-based methods
- Advantage: quantum feature maps can access exponentially large Hilbert space that is hard to compute classically
- Feature map design: ZZFeatureMap, PauliFeatureMap, or custom circuits; must match data structure
- Kernel training: optimize feature map parameters to maximize kernel-target alignment (separability of classes)

### Variational Classifiers
- Parameterized quantum circuit as the model: encode input, apply trainable layers, measure output
- Architecture: data encoding layer -> variational layers (rotations + entanglement) -> measurement
- Output: expectation value of observable mapped to class label (threshold for binary, argmax for multi-class)
- Training: minimize classical loss function (cross-entropy, MSE) by optimizing circuit parameters
- Hybrid: quantum circuit computes the forward pass; classical optimizer handles the backward pass (parameter shift rule for gradients)

### Data Encoding Strategies
- **Basis encoding**: encode N-bit binary string into N-qubit computational basis state; limited to binary data
- **Amplitude encoding**: encode 2^N amplitudes into N qubits; exponential compression but requires complex state preparation
- **Angle encoding**: encode each feature as a rotation angle on a dedicated qubit; one qubit per feature, simple circuits
- **Re-uploading**: repeat encoding layers throughout the circuit (data re-uploaded at each layer); increases expressiveness
- Encoding choice significantly affects model capability; angle encoding + re-uploading is common for NISQ

### Quantum Neural Networks (QNN)
- Parameterized quantum circuits treated analogously to classical neural network layers
- No direct analog of backpropagation: use parameter shift rule, finite differences, or adjoint differentiation (simulator)
- Expressibility: controlled by circuit depth, entanglement structure, and gate parameterization
- Trainability: subject to barren plateaus for deep random circuits; use structured initialization and local cost functions
- Hybrid architectures: classical pre/post-processing layers + quantum circuit in the middle; leverage classical layers for dimensionality reduction

### Practical Workflow
1. Classical baseline: always establish classical model performance first; quantum must beat or match it to be worthwhile
2. Feature selection: reduce input dimensionality classically before encoding (PCA, feature importance); fewer features = fewer qubits
3. Simulator development: design and train on noiseless or noise-model simulators
4. Hardware validation: run trained model on hardware with error mitigation; compare to simulator results
5. Scaling analysis: check if the quantum advantage claim holds as problem size increases

## Gotchas / Anti-patterns
- **No classical baseline**: claiming quantum advantage without comparing to a well-tuned classical model
- **Barren plateaus**: deep variational circuits on many qubits have vanishing gradients; restrict depth and use local observables
- **Overfitting small data**: quantum models with many parameters on small datasets overfit just like classical models
- **Encoding overhead ignored**: amplitude encoding requires exponential gate depth for generic data, negating qubit savings
- **Hardware noise as regularization**: noise is not beneficial regularization; it degrades signal and adds bias
- **Quantum supremacy claims on toy problems**: 4-qubit classifiers on Iris dataset do not demonstrate practical advantage

## References
- Schuld & Petruccione, "Machine Learning with Quantum Computers" (2021) — comprehensive textbook
- Havlicek et al., "Supervised learning with quantum-enhanced feature spaces" (2019) — quantum kernels
- Schuld et al., "Circuit-centric quantum classifiers" (2020) — variational classification
- Pennylane documentation — hybrid quantum-classical ML framework
- Qiskit Machine Learning documentation — quantum kernel and QNN implementations
