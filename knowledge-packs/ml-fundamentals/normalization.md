# Normalization

## When to use
- Stabilizing training of deep networks — normalization prevents internal covariate shift and gradient explosion
- Nearly every modern deep network uses some form of normalization
- The choice depends on architecture, batch size, and whether you need batch-level or instance-level statistics

## Pattern

### Batch Normalization (BatchNorm)
- Normalizes across the batch dimension: compute mean/variance over (N, H, W) for each channel
- Learned affine parameters (scale and shift) per channel
- Maintains running statistics for inference (exponential moving average)
- **Use when**: Training CNNs with reasonably large batch sizes (>= 16)
- Works poorly with small batches — statistics are noisy. Breaks entirely with batch size 1
- Not suitable for variable-length sequences or autoregressive models

### Layer Normalization (LayerNorm)
- Normalizes across the feature dimension: compute mean/variance over (C, H, W) for each sample independently
- No dependency on batch size — works with any batch size including 1
- **Use when**: Transformers (standard choice), RNNs, any architecture where batch size is small or variable
- Pre-norm (normalize before attention/FFN) is now standard over post-norm (original transformer)
- Pre-norm improves training stability, especially for deep models

### RMSNorm (Root Mean Square Normalization)
- Simplified LayerNorm: normalizes by RMS of activations, no mean centering
- Fewer operations than LayerNorm — ~10-15% faster
- Empirically matches LayerNorm performance in transformers
- **Use when**: LLM training/inference where compute efficiency matters
- Used by: LLaMA, Mistral, Qwen, most modern LLMs

### GroupNorm
- Divides channels into groups, normalizes within each group per sample
- Interpolates between LayerNorm (1 group) and InstanceNorm (C groups)
- Independent of batch size — stable with small batches
- **Use when**: Vision tasks with small batch sizes (detection, segmentation) where BatchNorm is unstable
- Typical: 32 groups for models with >= 256 channels

### InstanceNorm
- Normalizes each channel independently for each sample: mean/var over (H, W)
- **Use when**: Style transfer, image generation — removes instance-specific style information
- Rarely used outside these specific applications

### Normalization placement
- **Pre-norm**: Normalize before the main computation (attention, FFN). Easier to train, more stable gradients
- **Post-norm**: Normalize after the residual connection. Original transformer style, harder to train deep models
- Modern consensus: pre-norm for stability. Post-norm can achieve slightly better performance with careful tuning

## Gotchas / Anti-patterns
- Using BatchNorm with batch size 1 or very small batches — statistics are meaningless, training is unstable
- BatchNorm in distributed training without syncing statistics across GPUs — each GPU sees different batch statistics
- Forgetting to set the model to eval mode at inference — BatchNorm uses batch statistics instead of running averages
- Using BatchNorm in autoregressive models — future tokens in the batch leak through batch statistics
- Placing dropout between normalization and the operation it normalizes — disrupts the normalized distribution
- Mixing pre-norm and post-norm in the same model without understanding the residual stream effects

## References
- "Batch Normalization: Accelerating Deep Network Training" (Ioffe & Szegedy, 2015)
- "Layer Normalization" (Ba et al., 2016)
- "Root Mean Square Layer Normalization" (Zhang & Sennrich, 2019)
- "Group Normalization" (Wu & He, 2018)
