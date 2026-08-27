# Activation Functions

## When to use
- Every neural network requires nonlinear activations between linear layers — without them, stacked linear layers collapse to a single linear transformation
- The choice affects training dynamics, gradient flow, and computational cost
- Modern defaults exist — deviate only with reason

## Pattern

### ReLU (Rectified Linear Unit)
- f(x) = max(0, x)
- Simple, fast to compute, sparse activations (outputs zero for negative inputs)
- **Use when**: CNNs, general-purpose networks, when compute efficiency matters
- Enables sparse representations — can be beneficial for generalization
- Gradient is exactly 1 for positive inputs, 0 for negative — simple backpropagation
- Leaky ReLU: f(x) = max(alpha*x, x) with small alpha (0.01) — avoids dead neurons

### GELU (Gaussian Error Linear Unit)
- f(x) = x * Phi(x) where Phi is the Gaussian CDF
- Smooth approximation that weights inputs by their magnitude
- **Use when**: Transformers — this is the standard activation for BERT, GPT, and most transformer FFN layers
- Slightly more expensive than ReLU but consistently improves transformer training
- Approximate GELU (tanh-based) is common for performance; exact GELU uses erf

### SiLU / Swish
- f(x) = x * sigmoid(x)
- Smooth, non-monotonic — allows small negative outputs
- **Use when**: Vision models (EfficientNet, ConvNeXt), LLM FFN layers (LLaMA uses SiLU in gated FFN)
- Very similar to GELU in practice — the two are nearly interchangeable
- Self-gated: the sigmoid acts as a learned gate on the linear component

### GLU Variants (Gated Linear Units)
- Split input into two halves: one passes through an activation, then element-wise multiplied
- SwiGLU: combines Swish gating with linear unit — used in LLaMA, Mistral, PaLM
- GeGLU: GELU-gated variant
- **Use when**: Transformer FFN blocks in modern LLMs — SwiGLU is the current default
- Requires 50% more parameters in the FFN for the same hidden size (third weight matrix)

### Sigmoid
- f(x) = 1 / (1 + exp(-x))
- Outputs in (0, 1) — natural for binary classification output or gating
- **Use when**: Output layer for binary classification, gating mechanisms (LSTM gates, attention gates)
- Not for hidden layers — vanishing gradients for large/small inputs, not zero-centered

### Tanh
- f(x) = tanh(x)
- Outputs in (-1, 1) — zero-centered, which helps optimization
- **Use when**: LSTM/GRU hidden states (historical), output layer when bounded symmetric output is needed
- Same vanishing gradient issues as sigmoid for deep networks

### Softmax
- Converts a vector of logits to a probability distribution
- **Use when**: Multi-class classification output, attention weight computation
- Not an activation for hidden layers — it is a normalization operation
- Temperature parameter controls sharpness: lower T = more peaked distribution

## Gotchas / Anti-patterns
- Using sigmoid or tanh in hidden layers of deep networks — vanishing gradients stall training
- Dead ReLU problem: if a neuron's input is always negative, its gradient is permanently zero. Use Leaky ReLU or GELU to avoid
- Using ReLU in transformer FFN blocks — GELU or SiLU/SwiGLU consistently outperform
- Forgetting that GLU variants need a third weight matrix — parameter budget changes
- Applying softmax to already-normalized values — double normalization

## References
- "Gaussian Error Linear Units (GELUs)" (Hendrycks & Gimpel, 2016)
- "Searching for Activation Functions" (Ramachandran et al., 2017) — Swish discovery
- "GLU Variants Improve Transformer" (Shazeer, 2020) — SwiGLU, GeGLU
- "Rectified Linear Units Improve Restricted Boltzmann Machines" (Nair & Hinton, 2010)
