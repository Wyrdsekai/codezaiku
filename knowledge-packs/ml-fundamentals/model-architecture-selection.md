# Model Architecture Selection

## When to use
- Starting a new ML project and choosing the backbone architecture
- Evaluating whether to use a standard architecture or something domain-specific
- Deciding between sequence models, convolutional models, and attention-based models
- Assessing whether a pretrained model exists that fits your task

## Pattern

### Transformers
- **Use when**: NLP (all tasks), code generation, long-range sequence dependencies, vision (ViT), multimodal
- Self-attention captures global dependencies in O(n^2) time
- Scale extremely well — performance improves predictably with parameters and data
- Dominant architecture for LLMs, code models, and increasingly for vision and audio
- Downsides: O(n^2) attention cost, large memory footprint, need substantial data to train from scratch
- Variants: encoder-only (BERT, classification), decoder-only (GPT, generation), encoder-decoder (T5, seq2seq)

### Convolutional Neural Networks (CNNs)
- **Use when**: Image classification, object detection, segmentation, any spatial/grid-structured data
- Local receptive fields capture spatial hierarchies efficiently
- Translation-equivariant — features are detected regardless of position
- Inductive bias toward locality is beneficial when spatial structure matters
- Lighter weight than transformers for equivalent tasks — faster inference on edge devices
- Modern CNNs (ConvNeXt) close the gap with ViT on many vision benchmarks
- Not suitable for: tasks requiring global context without sufficient depth, variable-length sequences

### Recurrent Neural Networks (RNNs) / LSTMs / GRUs
- **Use when**: Sequential data with modest length, real-time streaming, low-latency requirements
- Process sequences step-by-step — O(n) computation, constant memory per step
- LSTMs and GRUs mitigate vanishing gradients through gating mechanisms
- Largely superseded by transformers for offline/batch tasks due to lack of parallelism
- Still relevant for: on-device streaming (speech, sensor data), low-latency requirements, tiny models
- Not suitable for: long sequences (>1k) where context needs to be preserved, tasks where parallelism matters

### State-Space Models (SSMs) / Mamba
- **Use when**: Long sequences (>8k tokens), linear-time scaling requirements, efficiency-sensitive deployment
- O(n) computation and O(1) memory per step (like RNNs) but parallelizable during training (like transformers)
- Selective state spaces (Mamba) provide input-dependent gating for content-aware processing
- Competitive with transformers on language modeling at smaller scales
- Hybrid architectures (Mamba + attention layers) combine strengths of both
- Maturing rapidly (2024-2026) — performance gap with transformers narrowing

### Graph Neural Networks (GNNs)
- **Use when**: Data has explicit graph structure — molecular property prediction, social networks, knowledge graphs
- Message-passing framework: nodes aggregate information from neighbors
- Variants: GCN (spectral), GAT (attention-weighted), GraphSAGE (sampling-based)
- Not suitable for: data without natural graph structure, very large graphs without sampling

### Mixture of Experts (MoE)
- **Use when**: You need large model capacity without proportional compute cost
- Only a subset of parameters (experts) are activated for each input — sparse computation
- Enables training models with many more total parameters at similar FLOPs to a dense model
- Used by: Mixtral, DeepSeek, Switch Transformer
- Adds complexity: load balancing, expert routing, higher memory for total parameters

### Decision framework
1. **What is your data modality?** Text -> Transformer. Images -> CNN or ViT. Graph -> GNN. Multimodal -> Transformer
2. **How much data do you have?** Little data -> pretrained model + fine-tuning. Lots of data -> train from scratch
3. **What are your latency/compute constraints?** Tight -> CNN, SSM, or small transformer. Flexible -> large transformer
4. **How long are your sequences?** Short (<512) -> any. Medium (512-8k) -> transformer. Long (>8k) -> SSM or efficient attention
5. **Is there a strong pretrained model for your task?** If yes -> use it. Architecture choice matters less than pretraining

## Gotchas / Anti-patterns
- Training a transformer from scratch on a small dataset — will underperform a simpler model. Use pretrained
- Choosing architecture based on hype rather than task characteristics
- Using RNNs for tasks that need long-range dependencies — attention-based models are strictly better
- Using full attention on very long sequences without considering efficient alternatives
- Assuming bigger model = better — without sufficient data, larger models overfit
- Ignoring inference cost when selecting architecture — training is one-time, inference is forever

## References
- "Attention Is All You Need" (Vaswani et al., 2017) — Transformers
- "An Image Is Worth 16x16 Words" (Dosovitskiy et al., 2020) — ViT
- "Mamba: Linear-Time Sequence Modeling" (Gu & Dao, 2023) — State-space models
- "A ConvNet for the 2020s" (Liu et al., 2022) — ConvNeXt
- "Mixtral of Experts" (Jiang et al., 2024) — MoE architecture
