# Training Infrastructure

## When to use
- Planning hardware for model training or fine-tuning workloads
- Choosing between local and cloud GPU resources
- Estimating costs and timelines for training runs
- Scaling from single-GPU experiments to multi-GPU training

## Pattern

### Single GPU Training

Suitable for:
- Fine-tuning models up to ~13B with QLoRA (24GB VRAM)
- Full fine-tuning of models up to ~3B (24GB VRAM)
- Rapid prototyping and hyperparameter sweeps on smaller models

Maximize single-GPU efficiency before scaling out:
- Mixed precision (BF16) to halve memory and increase throughput
- Gradient checkpointing for larger effective model size
- Gradient accumulation for larger effective batch size
- Flash Attention for memory-efficient attention computation
- Optimal micro-batch size: increase until GPU compute is saturated (watch GPU utilization %)

### Multi-GPU Training

**Data Parallel (DP/DDP)**:
- Same model replicated on each GPU, data split across GPUs
- Linear throughput scaling with GPU count (minus communication overhead)
- Use DDP (DistributedDataParallel) not plain DP (DataParallel)
- Effective batch size = micro_batch x GPUs x accumulation_steps

**Model Parallel (TP/PP)**:
- Tensor Parallelism: split individual layers across GPUs
- Pipeline Parallelism: split sequential layers across GPUs
- Use when model does not fit on a single GPU even with optimization
- Higher communication overhead than data parallelism

**FSDP (Fully Sharded Data Parallel)**:
- Shards model weights, gradients, and optimizer states across GPUs
- Each GPU holds a fraction of the model, gathers as needed
- Effective for training models larger than single-GPU VRAM
- Three sharding strategies: FULL_SHARD, SHARD_GRAD_OP, NO_SHARD

**DeepSpeed ZeRO**:
- Stage 1: shard optimizer states
- Stage 2: shard optimizer + gradients
- Stage 3: shard optimizer + gradients + weights
- CPU offload available at all stages
- Choose the lowest stage that fits your memory budget

### Local vs Cloud

**Local advantages**: No per-hour cost after purchase, no data egress concerns, full control, always available.
**Local disadvantages**: Fixed capacity, maintenance burden, upfront capital, hardware depreciation.

**Cloud advantages**: Elastic scaling, access to latest hardware, no maintenance, pay-per-use.
**Cloud disadvantages**: Ongoing cost, data transfer latency and cost, availability uncertainty for spot/preemptible.

**Decision heuristic**: If GPU utilization will exceed 40-50% over the hardware's useful life (2-3 years), local ownership is typically cheaper than on-demand cloud. Below that, cloud is more cost-effective.

### Cost Estimation

Training cost drivers:
- GPU-hours = (dataset_tokens x epochs) / (throughput_tokens_per_second x 3600)
- Throughput depends on model size, batch size, hardware, and parallelism strategy
- Add 10-20% overhead for evaluation, checkpointing, and restarts

Rule of thumb for fine-tuning cost:
- QLoRA 7B model, 10K examples, 3 epochs: ~1-2 GPU-hours on A100/H100
- Full fine-tune 7B model, 100K examples, 3 epochs: ~20-40 GPU-hours on A100
- Scale roughly linearly with dataset size and model parameter count

### NVLink and Interconnect

- Multi-GPU training is bottlenecked by inter-GPU communication
- NVLink provides 600-900 GB/s (vs PCIe at 64 GB/s)
- Critical for tensor parallelism; less impactful for pure data parallelism
- For cloud: check that multi-GPU instances have NVLink, not just PCIe

## Gotchas / Anti-patterns
- Jumping to multi-GPU before exhausting single-GPU optimizations
- Using DataParallel instead of DistributedDataParallel (DP has GIL bottleneck)
- Not accounting for GPU idle time during checkpointing and evaluation
- Comparing cloud pricing without including data transfer and storage costs
- Running long training jobs on spot instances without checkpoint-and-resume logic
- Ignoring interconnect bandwidth when selecting multi-GPU cloud instances
- Over-provisioning GPU memory headroom (leaving 30%+ unused wastes capacity)

## References
- PyTorch distributed training: https://pytorch.org/tutorials/intermediate/ddp_tutorial.html
- DeepSpeed documentation: https://www.deepspeed.ai/
- PyTorch FSDP guide: https://pytorch.org/tutorials/intermediate/FSDP_tutorial.html
- Hugging Face multi-GPU training: https://huggingface.co/docs/transformers/perf_train_gpu_many
