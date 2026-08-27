# Cost Optimization

## When to use
- Reducing GPU infrastructure spend without proportional quality loss
- Choosing between hardware options for training or inference
- Planning long-running workloads on cloud infrastructure
- Evaluating whether to upgrade, downgrade, or right-size GPU allocation

## Pattern

### Spot / Preemptible Instances

Cloud providers offer unused GPU capacity at 60-90% discount:
- **Requirement**: workload must tolerate interruption
- Training jobs: checkpoint every N minutes, resume from last checkpoint on new instance
- Batch inference: track progress, skip completed items on restart
- Not suitable for real-time inference serving (interruption = downtime)

**Strategies**:
- Use spot for training, on-demand for serving
- Diversify across GPU types and regions to reduce interruption frequency
- Set a maximum spot price to avoid cost spikes
- Combine spot workers with a small on-demand baseline for hybrid resilience

### Right-Sizing GPUs

**Do not default to the largest available GPU.** Match GPU to workload:

| Workload | GPU class | Reasoning |
|----------|-----------|-----------|
| Inference, 7B quantized INT4 | 16GB (RTX 4080, T4) | Model fits with room for KV cache |
| Inference, 7B FP16 | 24GB (RTX 4090, A10) | Full precision with batch capacity |
| Fine-tuning, 7B QLoRA | 24GB (RTX 4090, A10) | QLoRA keeps memory under 20GB |
| Fine-tuning, 7B full | 48-80GB (A6000, A100) | Full FT needs 16-18 bytes/param |
| Inference, 70B quantized | 48-80GB (A100, H100) | Even INT4 needs ~35GB |

**Utilization check**: If GPU utilization is consistently below 50%, the GPU is oversized. If GPU memory usage is below 60%, a smaller GPU may suffice.

### Mixed Precision for Inference Savings

- Serve in FP8 or INT8 instead of FP16 with minimal quality loss
- Halves memory, enabling a smaller GPU or more concurrent requests on the same GPU
- INT4 (GPTQ/AWQ) quarters memory for further savings
- Validate quality on your task before committing (see quantization.md)
- Savings compound: smaller GPU + higher throughput + more requests per dollar

### Inference Cost Reduction

**Smaller models where sufficient**:
- A well-fine-tuned 7B model often matches a generic 70B on domain tasks
- Evaluate whether the task actually needs the larger model
- Cost difference: roughly 10x between 7B and 70B at same precision

**Caching**:
- Prefix caching reduces redundant computation for shared prompt patterns
- Application-level response caching for identical or near-identical requests
- Semantic caching for similar (not identical) queries with fuzzy matching

**Request routing**:
- Route simple queries to smaller/faster models, complex queries to larger models
- Classify request complexity before inference (lightweight classifier or heuristic)
- Potential 40-60% cost reduction with 2-tier routing

### Training Cost Reduction

- **Efficient fine-tuning**: QLoRA over full fine-tuning (10x less compute)
- **Early stopping**: Monitor validation loss, stop when it plateaus (do not train full epochs)
- **Learning rate scheduling**: Cosine or linear warmup avoids wasted early epochs
- **Data quality over quantity**: Clean, deduplicated data trains faster than large noisy datasets
- **Hyperparameter search**: Use small-scale sweeps before committing to full-scale runs

### Cost Monitoring

- Track cost per training run (GPU-hours x hourly rate)
- Track cost per 1M inference tokens (or per 1K requests)
- Compare across providers monthly (pricing changes)
- Set budget alerts at 70% and 90% of monthly allocation
- Attribute costs to projects or teams for accountability

## Gotchas / Anti-patterns
- Optimizing only for GPU cost while ignoring storage, networking, and CPU costs
- Using spot instances for latency-sensitive serving (interruptions cause downtime)
- Defaulting to the latest, most expensive GPU without benchmarking cheaper options
- Optimizing price per GPU-hour instead of price per useful output (throughput matters)
- Not accounting for idle time (a 30% utilized GPU is 70% wasted spend)
- Over-quantizing to save money and degrading quality below acceptable levels
- Running hyperparameter sweeps at full scale instead of on a data subset first
- Ignoring reserved instance or committed use discounts for steady-state workloads

## References
- Cloud GPU pricing comparison: https://cloud-gpus.com/
- NVIDIA GPU product lineup: https://www.nvidia.com/en-us/data-center/
- AWS spot instance best practices: https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/using-spot-instances.html
- GCP preemptible VMs: https://cloud.google.com/compute/docs/instances/preemptible
