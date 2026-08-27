# Distributed Training

## When to use
- Model does not fit on a single GPU (model parallelism needed)
- Training is too slow on a single GPU and you have multiple GPUs available
- You want to scale batch size beyond single-GPU memory limits
- Pretraining or fine-tuning large models (> 1B parameters)

## Pattern

### Data Parallel (DDP — Distributed Data Parallel)
- **Use when**: Model fits on a single GPU, you want faster training via larger effective batch size
- Each GPU holds a full copy of the model
- Data is split across GPUs; each computes gradients on its shard
- Gradients are all-reduced (averaged) across GPUs, then each GPU updates independently
- Near-linear speedup up to communication bottleneck
- Simplest distributed approach — start here unless the model does not fit

### Fully Sharded Data Parallel (FSDP / ZeRO)
- **Use when**: Model fits on one GPU for inference but not for training (optimizer states are 2-3x model size)
- Shards model parameters, gradients, and optimizer states across GPUs
- Each GPU only holds 1/N of the full training state
- Parameters are gathered (all-gathered) on demand for forward/backward, then re-sharded
- ZeRO stages: Stage 1 (optimizer), Stage 2 (+ gradients), Stage 3 (+ parameters)
- Trade-off: more communication overhead than DDP, but enables much larger models
- FSDP Stage 2 is the sweet spot for most use cases

### Tensor Parallelism
- **Use when**: Single layers are too large for one GPU (very wide models)
- Splits individual weight matrices across GPUs (column-parallel, row-parallel)
- Requires high-bandwidth interconnect (NVLink/NVSwitch) — very communication-intensive
- Typically within a single node (8 GPUs with NVLink)

### Pipeline Parallelism
- **Use when**: Model is too deep for one GPU, layers can be partitioned into stages
- Assigns consecutive groups of layers to different GPUs
- Micro-batching fills the pipeline to reduce bubble (idle time)
- GPipe: synchronous, accumulate micro-batches then update. PipeDream: asynchronous, 1F1B schedule
- Bubble overhead is unavoidable — efficiency depends on number of micro-batches vs stages

### Combining strategies (3D parallelism)
- Large-scale training (100B+ models) combines all three: DP x TP x PP
- TP within a node (NVLink), PP across nodes, DP across node groups
- Extremely complex to configure — use frameworks (Megatron-LM, DeepSpeed) rather than rolling your own

### Communication patterns
- **All-reduce**: DDP gradient sync — O(model_size), bandwidth-bound
- **All-gather**: FSDP parameter reconstruction — O(model_size / N) per GPU
- **Point-to-point**: Pipeline parallelism stage boundaries
- Overlap communication with computation wherever possible

## Gotchas / Anti-patterns
- Using DDP when the model does not fit — fails with OOM. Use FSDP instead
- Using tensor parallelism across nodes with slow interconnect — communication overhead kills throughput
- Not scaling learning rate with effective batch size when adding GPUs
- Forgetting to set random seeds per rank for data sampling but same seed for model init
- Pipeline parallelism with too few micro-batches — bubble overhead dominates, little speedup
- Mixing DDP and gradient accumulation without `no_sync()` — redundant communication

## References
- PyTorch Distributed Training documentation (DDP, FSDP)
- DeepSpeed ZeRO documentation (Stages 1-3, Infinity)
- Megatron-LM documentation (3D parallelism)
- "ZeRO: Memory Optimizations Toward Training Trillion Parameter Models" (Rajbhandari et al., 2020)
- "GPipe: Efficient Training of Giant Neural Networks" (Huang et al., 2019)
