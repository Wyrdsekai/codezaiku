# GPU Memory Management

## When to use
- Estimating VRAM requirements before selecting hardware or model size
- Training or fine-tuning runs that OOM on available GPUs
- Serving large models on consumer or mid-range GPUs
- Deciding between model size, batch size, and sequence length tradeoffs

## Pattern

### VRAM Estimation

Model weights memory (inference):
- FP32: parameters x 4 bytes
- FP16/BF16: parameters x 2 bytes
- INT8: parameters x 1 byte
- INT4: parameters x 0.5 bytes

Training adds optimizer states and gradients:
- AdamW: ~16 bytes per parameter (weights + gradients + 2 momentum states in FP32)
- With mixed precision: ~18 bytes per parameter (FP32 master weights + FP16 working copy + optimizer)

KV cache per token per layer:
- 2 x hidden_dim x num_layers x bytes_per_param
- For a 7B model at FP16 with 2048 context: roughly 1-2 GB additional

Activation memory scales with batch_size x sequence_length x hidden_dim x num_layers.

### Memory Optimization Techniques

**Gradient checkpointing (activation recomputation)**:
- Trades compute for memory by recomputing activations during backward pass
- Reduces activation memory from O(n) to O(sqrt(n)) for n layers
- Typical cost: 20-35% slower training, 60-70% less activation memory
- Apply selectively to transformer blocks, not the entire model

**CPU offloading**:
- Move optimizer states to CPU RAM, copy back during update step
- Effective when CPU-GPU bandwidth is not the bottleneck
- Works well with large models and small batch sizes
- DeepSpeed ZeRO-Offload and FSDP CPU offload both implement this

**Mixed precision training**:
- Maintain FP32 master weights, compute forward/backward in FP16/BF16
- BF16 preferred over FP16 when hardware supports it (no loss scaling needed)
- Halves activation and gradient memory
- Use dynamic loss scaling with FP16 to prevent underflow

**Gradient accumulation**:
- Simulate large batch sizes without proportional memory cost
- Effective batch = micro_batch x accumulation_steps x num_gpus
- No memory increase beyond the micro-batch footprint

### Serving Optimization

- Use quantized weights (INT4/INT8/FP8) to reduce weight memory
- PagedAttention (vLLM-style) eliminates KV cache fragmentation
- Continuous batching reuses freed KV slots across requests
- Prefix caching shares KV entries across prompts with common prefixes

## Gotchas / Anti-patterns
- Estimating only weight memory and forgetting KV cache, activations, and CUDA overhead (~500MB-1GB)
- Using FP16 on hardware that natively supports BF16 (unnecessary loss scaling complexity)
- Setting gradient checkpointing globally when only a subset of layers are memory-critical
- Offloading everything to CPU when the bottleneck is actually batch size, not model size
- Forgetting that CUDA context itself consumes 300-800 MB before any model is loaded
- Assuming reported "fits on X GB" numbers include realistic batch sizes and sequence lengths

## References
- PyTorch memory management docs: https://pytorch.org/docs/stable/notes/cuda.html
- DeepSpeed ZeRO documentation: https://www.deepspeed.ai/tutorials/zero/
- Hugging Face efficient training guide: https://huggingface.co/docs/transformers/perf_train_gpu_one
- NVIDIA mixed precision training: https://docs.nvidia.com/deeplearning/performance/mixed-precision-training/
