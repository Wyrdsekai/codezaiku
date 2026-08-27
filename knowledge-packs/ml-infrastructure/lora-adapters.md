# LoRA Adapters

## When to use
- Fine-tuning large models when full fine-tuning exceeds available VRAM
- Maintaining a single base model with multiple task-specific adaptations
- Rapid iteration on fine-tuning experiments with limited hardware
- Serving multiple fine-tuned variants from one loaded base model

## Pattern

### LoRA Fundamentals

LoRA (Low-Rank Adaptation) freezes base model weights and injects small trainable matrices:
- Original weight W (d x d) stays frozen
- Two small matrices: A (d x r) and B (r x d) where r << d
- Effective weight: W + BA (merged at inference or applied dynamically)
- Trainable parameters: 2 x d x r per adapted layer (typically <1% of base model)

### Rank Selection

| Rank (r) | Trainable params | Use case |
|-----------|-----------------|----------|
| 4-8       | Minimal         | Style transfer, simple instruction following |
| 16-32     | Moderate        | Domain adaptation, task-specific tuning |
| 64-128    | Larger          | Complex tasks, significant behavior change |
| 256+      | Approaching full FT | Rarely needed; consider full fine-tuning |

Higher rank is not always better. Start with r=16 and increase only if validation loss plateaus.

**Alpha parameter**: scaling factor, typically set to 1x or 2x the rank. The effective scaling is alpha/rank. Higher alpha = stronger adapter influence.

**Target modules**: Apply LoRA to attention projections (q_proj, v_proj) as baseline. Add k_proj, o_proj, and MLP layers (gate_proj, up_proj, down_proj) for more capacity. Targeting all linear layers is common for QLoRA.

### QLoRA

Combines 4-bit quantized base model with LoRA adapters:
- Base model loaded in NF4 (4-bit NormalFloat) quantization
- LoRA adapters trained in BF16/FP16
- Double quantization reduces memory further (quantize the quantization constants)
- Enables fine-tuning 7B models on 16GB GPUs, 70B on 48GB

Training flow:
1. Load base model in 4-bit with NF4 + double quantization
2. Attach LoRA adapters to target modules
3. Train adapters with paged optimizers (handles memory spikes)
4. Save adapter weights only (small, typically 10-100 MB)

### Adapter Management

**Storage**: Save adapters separately from base model. Each adapter is a small file (safetensors or bin) plus config.
**Versioning**: Track adapter + base model hash together. An adapter is only valid for its specific base.
**Merging**: Adapters can be permanently merged into base weights for deployment simplicity. Merging is lossy (cannot un-merge) and prevents multi-LoRA serving.

### Multi-LoRA Serving

Load one base model, dynamically apply different adapters per request:
- SGLang and vLLM both support multi-LoRA with adapter hot-loading
- Base model weights shared in GPU memory, each adapter adds minimal overhead
- Route requests to the correct adapter by name or identifier
- Useful for per-tenant or per-task customization from a single deployment

Overhead per adapter in memory: approximately 2 x r x hidden_dim x num_adapted_layers x dtype_bytes.

## Gotchas / Anti-patterns
- Setting rank too high without checking if lower rank achieves comparable results
- Forgetting to set the base model to evaluation mode (freeze) before attaching adapters
- Using a LoRA adapter with a different base model than it was trained on (silent quality degradation)
- Merging adapters prematurely when you may need multi-LoRA serving later
- Training QLoRA with FP16 instead of BF16 on hardware that supports BF16 (unnecessary instability)
- Not evaluating adapter quality against full fine-tuning to confirm LoRA sufficiency
- Applying LoRA to too few layers (only q_proj) when the task requires broader adaptation
- Ignoring learning rate sensitivity (LoRA typically needs higher LR than full fine-tuning, 1e-4 to 3e-4)

## References
- LoRA paper: https://arxiv.org/abs/2106.09685
- QLoRA paper: https://arxiv.org/abs/2305.14314
- PEFT library: https://huggingface.co/docs/peft/
- Unsloth (fast LoRA training): https://github.com/unslothai/unsloth
