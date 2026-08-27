# Mixed Precision Training

## When to use
- You want to reduce memory usage and increase throughput without sacrificing model quality
- Your hardware has dedicated low-precision compute units (tensor cores on NVIDIA Ampere+, AMD MI-series)
- Training large models where memory is the bottleneck
- Nearly all modern training benefits from mixed precision — it should be the default, not the exception

## Pattern

### FP16 (Half Precision)
- 16-bit floating point: 5 exponent bits, 10 mantissa bits
- ~2x memory reduction for activations and weights during training
- ~2-3x throughput increase on tensor-core-equipped GPUs
- Requires loss scaling to prevent gradient underflow (small gradients round to zero)
- Supported on: NVIDIA Volta (V100) and later, AMD MI-series
- Master weights kept in FP32 — forward/backward in FP16, optimizer step in FP32

### BF16 (Brain Floating Point)
- 16-bit with 8 exponent bits, 7 mantissa bits — same range as FP32, less precision
- No loss scaling needed (same exponent range as FP32 prevents underflow)
- Simpler to use than FP16 — fewer numerical issues
- Supported on: NVIDIA Ampere (A100) and later, AMD MI250+, Google TPUs
- Preferred over FP16 when hardware supports it — fewer gotchas, comparable performance

### FP8 (8-bit Floating Point)
- Two formats: E4M3 (range-focused) for forward pass, E5M2 (precision-focused) for gradients
- ~4x memory reduction, significant throughput gains
- Supported on: NVIDIA Hopper (H100) and later, AMD MI300+
- Requires per-tensor scaling factors — more complex calibration
- Best for inference; training support is maturing (2025-2026)

### Loss Scaling (FP16 only)
- Multiply loss by a large factor before backward pass → gradients stay in representable range
- Dynamic loss scaling: start high, halve on NaN/Inf, double periodically
- Static loss scaling: fixed factor (e.g., 1024) — simpler but less robust
- Not needed for BF16 or FP8 (handled differently)

### Implementation pattern
1. Keep a master copy of weights in FP32
2. Cast weights to low precision for forward and backward passes
3. Compute loss, apply loss scaling (FP16)
4. Backward pass in low precision
5. Unscale gradients, clip if needed
6. Optimizer step in FP32 on master weights

## Gotchas / Anti-patterns
- Using FP16 without loss scaling — gradients silently underflow, training appears to work but learns nothing
- Using BF16 on hardware that does not support it — falls back to FP32 emulation, slower than pure FP32
- Casting the entire model to FP16 including optimizer states — optimizer must stay FP32
- Ignoring NaN checks — mixed precision amplifies numerical instability in certain architectures
- Accumulating FP16 values in long reductions (e.g., large vocabulary softmax) — use FP32 for reductions
- Assuming FP8 is drop-in — it requires architecture-aware placement and per-tensor scaling

## References
- "Mixed Precision Training" (Micikevicius et al., 2018) — foundational FP16 paper
- NVIDIA Mixed Precision Training documentation
- "FP8 Formats for Deep Learning" (Micikevicius et al., 2022)
- Hugging Face Trainer `fp16` and `bf16` flags documentation
