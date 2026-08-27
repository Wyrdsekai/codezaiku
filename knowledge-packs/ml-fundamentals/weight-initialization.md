# Weight Initialization

## When to use
- Every neural network must have its weights initialized before training begins
- Bad initialization causes vanishing/exploding gradients, slow convergence, or complete training failure
- Initialization strategy should match the activation function and architecture

## Pattern

### Xavier / Glorot Initialization
- Weights drawn from N(0, 2/(fan_in + fan_out)) or Uniform[-sqrt(6/(fan_in+fan_out)), sqrt(6/(fan_in+fan_out))]
- Designed to preserve variance of activations and gradients through layers
- **Use when**: Layers followed by sigmoid, tanh, or linear activations
- Assumes symmetric activation functions — not optimal for ReLU

### He / Kaiming Initialization
- Weights drawn from N(0, 2/fan_in) for fan-in mode, or N(0, 2/fan_out) for fan-out mode
- Accounts for the fact that ReLU zeros out half the activations
- **Use when**: Layers followed by ReLU, Leaky ReLU, or other asymmetric activations
- fan_in mode: preserves forward pass variance. fan_out mode: preserves backward pass variance
- fan_in is the default and most common choice

### Orthogonal Initialization
- Initialize weight matrices as random orthogonal matrices
- Preserves gradient norms exactly — prevents vanishing/exploding in very deep or recurrent networks
- **Use when**: Very deep networks, RNNs/LSTMs, when gradient stability is critical
- More expensive to compute than Gaussian initialization but provides stronger guarantees

### Small Constant / Zero Initialization
- Biases: typically initialized to zero
- LayerNorm/BatchNorm scale: initialized to 1, shift to 0
- Final residual layers: sometimes initialized to zero ("zero init residual") so the residual block starts as identity
- Never initialize all weights to zero — symmetry breaking fails, neurons learn identical features

### Transformer-Specific Patterns
- Embeddings: N(0, 0.02) or N(0, 1/sqrt(d_model))
- Attention projections (Q, K, V, O): Xavier or N(0, 1/sqrt(d_model))
- FFN layers: Xavier or He depending on activation
- Output projection of residual blocks: scale by 1/sqrt(2*num_layers) — prevents residual stream from growing
- GPT-style: initialize residual output projections with std = 0.02/sqrt(2*num_layers)

### Pretrained Initialization
- When fine-tuning, use pretrained weights — far superior to random initialization
- For new layers (classification head): Xavier or He, or small random
- See `transfer-learning.md` for detailed fine-tuning strategies

## Gotchas / Anti-patterns
- All-zero initialization — all neurons compute the same gradient, no symmetry breaking, model cannot learn
- Using Xavier with ReLU — underestimates required variance, activations shrink through layers
- Using He initialization with sigmoid/tanh — overestimates variance, saturates activations
- Large random initialization (std=1.0 without scaling) — exploding activations in deep networks
- Ignoring initialization for the output projection in residual blocks — residual stream magnitudes grow with depth
- Not re-initializing the classification head when fine-tuning — head weights from pretraining are for a different task

## References
- "Understanding the difficulty of training deep feedforward neural networks" (Glorot & Bengio, 2010) — Xavier init
- "Delving Deep into Rectifiers" (He et al., 2015) — Kaiming/He init
- "Exact solutions to the nonlinear dynamics of learning in deep linear neural networks" (Saxe et al., 2014) — orthogonal init
- "Language Models are Few-Shot Learners" (Brown et al., 2020) — GPT-3 initialization details
