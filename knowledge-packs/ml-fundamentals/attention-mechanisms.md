# Attention Mechanisms

## When to use
- Processing sequences where long-range dependencies matter (text, audio, time series)
- Tasks requiring alignment between two sequences (translation, summarization)
- When you need the model to dynamically focus on relevant parts of the input
- Vision tasks where global context improves over local receptive fields (ViT)

## Pattern

### Self-Attention
- Each position attends to all other positions in the same sequence
- Computes Query, Key, Value projections from the same input: Q=XWq, K=XWk, V=XWv
- Attention weights: softmax(QK^T / sqrt(d_k)) * V
- O(n^2) in sequence length — the fundamental scaling challenge
- Foundation of transformer encoders (BERT-style) and decoders (GPT-style)

### Cross-Attention
- Queries come from one sequence, Keys and Values from another
- Used in encoder-decoder models: decoder attends to encoder output
- Applications: machine translation, image captioning (text queries, image keys/values)
- Also used to condition generation on external context (retrieval-augmented generation)

### Multi-Head Attention
- Run multiple attention operations in parallel with different learned projections
- Each head can attend to different aspects (syntax, semantics, position)
- Concatenate head outputs, project back to model dimension
- Typical head count: d_model / 64 (e.g., 12 heads for d=768, 32 heads for d=2048)
- Grouped-Query Attention (GQA): share K/V heads across query heads — reduces KV cache size

### Causal (Masked) Attention
- Mask future positions so each token can only attend to itself and preceding tokens
- Required for autoregressive generation (language models, code generation)
- Implemented via upper-triangular mask applied before softmax

### Flash Attention
- Memory-efficient exact attention — computes attention without materializing the full N^2 matrix
- Uses tiling and kernel fusion to keep computation in fast SRAM
- O(N) memory instead of O(N^2), significantly faster on modern GPUs
- Drop-in replacement — same mathematical result, just faster and leaner
- Flash Attention 2/3: further optimizations for different head dimensions and hardware

### Linear / Efficient Attention Variants
- Approximate O(n^2) attention with O(n) alternatives
- Linformer: low-rank projection of K/V. Performer: random feature approximation
- Sparse attention: attend to fixed patterns (local + stride) instead of all positions
- Mamba / state-space models: replace attention entirely with recurrent-style computation
- Trade-off: faster but often lose some quality on tasks requiring precise long-range attention

### Multi-Query Attention (MQA) and Grouped-Query Attention (GQA)
- MQA: all query heads share a single K/V head — minimal KV cache, some quality loss
- GQA: groups of query heads share K/V heads — middle ground between MHA and MQA
- Critical for inference efficiency in large language models (smaller KV cache = longer contexts)

## Gotchas / Anti-patterns
- Using full attention on very long sequences (>8k tokens) without Flash Attention — OOM or extremely slow
- Forgetting causal mask in autoregressive models — model sees future tokens, inflated training metrics
- Too many attention heads with too small head dimension — each head has insufficient capacity
- Assuming linear attention is always a good substitute — quality drops on tasks needing precise token-to-token matching
- Not using Flash Attention when available — there is rarely a reason not to use it

## References
- "Attention Is All You Need" (Vaswani et al., 2017) — foundational transformer paper
- "FlashAttention: Fast and Memory-Efficient Exact Attention" (Dao et al., 2022)
- "GQA: Training Generalized Multi-Query Transformer Models" (Ainslie et al., 2023)
- "Mamba: Linear-Time Sequence Modeling with Selective State Spaces" (Gu & Dao, 2023)
