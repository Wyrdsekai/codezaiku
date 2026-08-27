# Embedding Layers

## When to use
- Converting discrete tokens (words, subwords, categorical features) into continuous vector representations
- Adding positional information to sequence models
- Any transformer or sequence model requires embeddings as the first layer
- Representing structured entities (users, items, nodes) in learned vector spaces

## Pattern

### Token / Word Embeddings
- Lookup table: each token ID maps to a learned d-dimensional vector
- Parameters: vocab_size x d_model — can be a significant fraction of total model parameters
- Initialized randomly (normal or uniform distribution), then learned during training
- Weight tying: share embedding weights with the output projection layer — reduces parameters, often improves quality
- Typical dimensions: 256 (small), 768 (base), 1024-4096 (large)

### Positional Encodings — Learned
- Add a learned embedding for each position (0, 1, 2, ... max_seq_len)
- Parameters: max_seq_len x d_model
- Cannot extrapolate beyond max_seq_len seen during training
- Used by: GPT-2, BERT, most early transformers

### Positional Encodings — Sinusoidal (Fixed)
- Fixed sine/cosine functions at different frequencies for each dimension
- No learned parameters — generalize to any sequence length in theory
- Used by: original Transformer (Vaswani et al.)
- Rarely used in modern models — outperformed by learned or rotary alternatives

### Rotary Position Embeddings (RoPE)
- Encode position by rotating query/key vectors in 2D subspaces
- Relative position information emerges naturally from the rotation angles
- Extrapolates to longer sequences better than learned absolute positions
- Used by: LLaMA, Mistral, Qwen, most modern LLMs
- NTK-aware scaling and YaRN enable further context extension

### ALiBi (Attention with Linear Biases)
- No positional embeddings — instead adds a linear bias to attention scores based on distance
- Bias slope varies per head (geometric sequence)
- Excellent length extrapolation with zero additional parameters
- Used by: BLOOM, MPT

### Dimensionality considerations
- Higher dimensions: more expressive, more parameters, more compute
- Lower dimensions: faster, fewer parameters, but limited representational capacity
- Rule of thumb: d_model should be large enough that the embedding space is not a bottleneck
- For downstream tasks (classification, retrieval), embedding dimension affects the quality/speed tradeoff

### Embedding initialization
- Random normal: mean=0, std=1/sqrt(d_model) or std=0.02
- From pretrained: load embeddings from a pretrained model, freeze or fine-tune
- When extending vocabulary: initialize new token embeddings as the mean of existing embeddings or random

## Gotchas / Anti-patterns
- Not scaling embeddings by sqrt(d_model) when required — original transformer multiplies embeddings by this factor to balance with positional encodings
- Using learned absolute positions and expecting length generalization — model will degrade beyond training length
- Forgetting to resize embeddings after adding special tokens — shape mismatch crashes
- Very large vocabulary with large d_model — embedding layer dominates parameter count (250k vocab x 4096 dim = 1B params just for embeddings)
- Not tying input/output embeddings when appropriate — wastes parameters and can hurt performance
- Initializing new token embeddings to zeros — they receive no gradient signal initially

## References
- "Attention Is All You Need" (Vaswani et al., 2017) — sinusoidal positional encoding
- "RoFormer: Enhanced Transformer with Rotary Position Embedding" (Su et al., 2021)
- "Train Short, Test Long: Attention with Linear Biases" (Press et al., 2022) — ALiBi
- "YaRN: Efficient Context Window Extension" (Peng et al., 2023) — RoPE scaling
