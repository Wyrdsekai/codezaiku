# Quantization

## When to use
- Reducing model memory footprint to fit on smaller GPUs
- Improving inference throughput without changing hardware
- Deploying models to edge or consumer devices
- Trading small quality degradation for large efficiency gains

## Pattern

### Quantization Approaches

**Post-Training Quantization (PTQ)**:
- Applied after training is complete, no retraining required
- Requires a small calibration dataset (128-512 samples typical)
- Fast to apply (minutes to hours depending on model size)
- Use when you cannot afford to retrain or fine-tune

**Quantization-Aware Training (QAT)**:
- Simulates quantization during training or fine-tuning
- Produces higher quality quantized models than PTQ
- Requires access to training infrastructure and data
- Use when quality at low bit-widths is critical

### Format Guide

**GPTQ (GPU-targeted PTQ)**:
- Layer-wise quantization using approximate second-order information
- INT4/INT3 with grouping (group_size=128 typical)
- Requires calibration dataset
- Fast inference on GPU, well-supported by serving frameworks
- Best for: GPU inference where you want good quality at 4-bit

**AWQ (Activation-Aware Weight Quantization)**:
- Preserves salient weight channels identified by activation magnitudes
- Generally better quality than GPTQ at same bit-width
- INT4 with grouping
- Best for: GPU inference when quality matters more than quantization speed

**GGUF (CPU/hybrid inference)**:
- Successor to GGML, used primarily by llama.cpp and Ollama
- Supports Q2_K through Q8_0 quantization levels
- Can split layers between CPU and GPU (partial offload)
- Best for: Local/CPU inference, consumer hardware, development

**FP8 (8-bit floating point)**:
- E4M3 (4 exponent, 3 mantissa) for weights and activations
- E5M2 (5 exponent, 2 mantissa) for gradients
- Native hardware support on Ada Lovelace (RTX 4090) and Hopper (H100) GPUs
- Minimal quality loss compared to FP16/BF16
- Best for: Modern GPU inference with near-lossless compression

### Bit-Width Selection

| Bit-width | Typical quality loss | Memory ratio vs FP16 | Use case |
|-----------|---------------------|----------------------|----------|
| FP8       | Negligible          | 0.5x                | Default for supported hardware |
| INT8      | Very small          | 0.5x                | Broad compatibility |
| INT4      | Small to moderate   | 0.25x               | Memory-constrained serving |
| INT3      | Moderate            | 0.19x               | Extreme memory constraints |
| INT2      | Significant         | 0.125x              | Research only |

### Quality Validation

- Run a standard benchmark (MMLU, HumanEval, or domain-specific) before and after
- Test on your actual use case, not just generic benchmarks
- Check edge cases: long context, structured output, code generation
- Compare perplexity on a held-out calibration set

## Gotchas / Anti-patterns
- Quantizing a model without validating quality on your specific task
- Using GPTQ/AWQ for CPU inference (designed for GPU, use GGUF instead)
- Applying FP8 on hardware without native FP8 support (falls back to emulation, slower)
- Assuming a single "best" quantization method exists for all models and tasks
- Quantizing already-small models (under 3B parameters) where the quality loss outweighs savings
- Stacking quantization with other compression (pruning + quantization) without careful evaluation
- Using very small calibration datasets that do not represent your actual workload distribution
- Ignoring group size effects (smaller group_size = better quality but more overhead)

## References
- GPTQ paper: https://arxiv.org/abs/2210.17323
- AWQ paper: https://arxiv.org/abs/2306.00978
- llama.cpp GGUF format: https://github.com/ggerganov/llama.cpp
- FP8 training paper: https://arxiv.org/abs/2209.05433
- Hugging Face quantization guide: https://huggingface.co/docs/transformers/quantization
