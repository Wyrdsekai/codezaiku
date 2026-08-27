# DeepSpeed Training Patterns

## When to use
- Training models too large to fit on a single GPU
- Need memory-efficient distributed training without rewriting code
- Want CPU/NVMe offloading to train bigger models on fewer GPUs
- Mixed precision training with loss scaling
- Already using PyTorch or HF Trainer and need to scale up

## Pattern

### ZeRO Stages
- **ZeRO-1**: Partition optimizer states across GPUs. ~4x memory reduction for optimizer.
- **ZeRO-2**: Partition optimizer states + gradients. Good default for multi-GPU.
- **ZeRO-3**: Partition optimizer states + gradients + parameters. Maximum savings, highest communication cost.

Pick the lowest stage that fits your model in memory.

### ZeRO-2 Config (Most Common)
```json
{
  "bf16": {"enabled": true},
  "zero_optimization": {
    "stage": 2,
    "allgather_partitions": true,
    "allgather_bucket_size": 2e8,
    "overlap_comm": true,
    "reduce_scatter": true,
    "reduce_bucket_size": 2e8,
    "contiguous_gradients": true
  },
  "gradient_accumulation_steps": 4,
  "gradient_clipping": 1.0,
  "train_micro_batch_size_per_gpu": 2,
  "wall_clock_breakdown": false
}
```

### ZeRO-3 with Offloading
```json
{
  "bf16": {"enabled": true},
  "zero_optimization": {
    "stage": 3,
    "offload_optimizer": {
      "device": "cpu",
      "pin_memory": true
    },
    "offload_param": {
      "device": "cpu",
      "pin_memory": true
    },
    "overlap_comm": true,
    "contiguous_gradients": true,
    "sub_group_size": 1e9,
    "stage3_prefetch_bucket_size": 5e8,
    "stage3_param_persistence_threshold": 1e6,
    "stage3_max_live_parameters": 1e9,
    "stage3_max_reuse_distance": 1e9,
    "stage3_gather_16bit_weights_on_model_save": true
  },
  "gradient_accumulation_steps": 8,
  "train_micro_batch_size_per_gpu": 1
}
```

### HF Trainer Integration
```python
from transformers import TrainingArguments

args = TrainingArguments(
    output_dir="./output",
    deepspeed="ds_config.json",
    per_device_train_batch_size=2,
    gradient_accumulation_steps=4,
    bf16=True,
)
# Launch: deepspeed --num_gpus=4 train.py
# Or: accelerate launch --config_file accelerate_config.yaml train.py
```

### Native PyTorch Integration
```python
import deepspeed

model, optimizer, _, _ = deepspeed.initialize(
    model=model,
    model_parameters=model.parameters(),
    config="ds_config.json",
)

for batch in dataloader:
    loss = model(batch)
    model.backward(loss)
    model.step()
```

### Launching
```bash
# Direct
deepspeed --num_gpus=4 train.py --deepspeed ds_config.json

# With accelerate (recommended for HF)
accelerate launch --config_file accel_config.yaml train.py

# Multi-node
deepspeed --hostfile hostfile.txt --num_gpus=8 train.py
```

### Activation Checkpointing
```python
# In config
{"activation_checkpointing": {
    "partition_activations": true,
    "contiguous_memory_optimization": true,
    "cpu_checkpointing": true  # offload activations to CPU
}}
```

## Gotchas / Anti-patterns
- Starting with ZeRO-3 when ZeRO-2 fits — ZeRO-3 has higher communication overhead
- Mismatched batch size in config vs TrainingArguments — DeepSpeed config takes precedence
- CPU offloading without `pin_memory: true` — dramatic slowdown from unpinned transfers
- Not setting `stage3_gather_16bit_weights_on_model_save` — saves sharded weights that are hard to load
- Using DeepSpeed with FSDP simultaneously — they are alternatives, not complements
- Forgetting gradient accumulation alignment — effective batch size = micro_batch * accum_steps * num_gpus

## References
- DeepSpeed docs: https://www.deepspeed.ai/getting-started/
- ZeRO paper: https://arxiv.org/abs/1910.02054
- HF DeepSpeed integration: https://huggingface.co/docs/transformers/main/en/deepspeed
- GitHub: https://github.com/microsoft/DeepSpeed
