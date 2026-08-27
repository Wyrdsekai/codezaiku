# Batch Inference

## When to use
- Processing large datasets through a model offline (not real-time)
- Maximizing throughput when latency per request is not critical
- Running evaluation suites, data labeling, or bulk transformations
- Cost optimization by using hardware at peak efficiency

## Pattern

### Core Approach

Batch inference prioritizes throughput (total tokens/second) over latency (per-request time):
1. Collect all inputs upfront
2. Group into batches sized for GPU memory and compute
3. Process batches sequentially or in parallel across GPUs
4. Collect and store all outputs

### Batch Size Selection

**GPU memory constraint**:
- Estimate per-request memory: model weights + KV cache for max sequence length + activations
- Maximum batch = (available VRAM - model weights) / per-request overhead
- Leave 10-15% headroom for memory fragmentation and spikes

**Compute saturation**:
- Increase batch size until GPU compute utilization plateaus (typically 85-95%)
- Beyond this point, larger batches increase latency without improving throughput
- Measure with actual data, not theoretical calculations

**Practical starting points**:
- 7B model on 24GB GPU (FP16): batch 8-16
- 7B model quantized INT4 on 24GB: batch 32-64
- 70B model on 80GB GPU (FP16): batch 2-4
- Adjust based on actual sequence lengths in your data

### Chunking Strategies

**Fixed-size chunks**: Split input dataset into N equal pieces. Simple, but ignores sequence length variation.

**Length-sorted batching**: Sort inputs by token count, then batch similar lengths together. Reduces padding waste. Most impactful optimization for variable-length inputs.

**Dynamic batching**: Fill batches up to a token budget rather than a fixed count. Each batch has roughly equal total tokens, giving consistent processing time.

### Throughput Optimization

- **Continuous batching**: Do not wait for the longest sequence in a batch to finish. As shorter sequences complete, backfill their slots with new requests.
- **Speculative decoding**: Use a small draft model to propose tokens, verified by the large model in parallel. 2-3x speedup when draft model is accurate.
- **Tensor parallelism**: Split model across GPUs to increase per-request speed, then batch across the combined capacity.
- **Quantization**: INT4/INT8 models process more tokens per second at moderate quality cost (see quantization.md).

### Output Management

- Write results incrementally (per-batch, not all-at-end) to avoid data loss on failure
- Include input identifiers in output for alignment (request_id, row_index)
- Store failed inputs separately for retry
- Log batch processing statistics (tokens/sec, time per batch, error rate)

### Checkpoint and Resume

For large batch jobs:
- Track which inputs have been processed (checkpoint file or database)
- On restart, skip already-completed inputs
- Idempotent processing: reprocessing an input produces the same output
- Set a maximum runtime with checkpoint-on-timeout for cluster jobs

### Cost Optimization

- Use spot/preemptible instances with checkpoint-and-resume
- Process during off-peak hours if using shared infrastructure
- Right-size the GPU: a smaller quantized model may give sufficient quality at higher throughput
- Compare cost per 1M tokens across hardware options before committing to a large run

## Gotchas / Anti-patterns
- Using online inference servers for batch workloads (unnecessary overhead, suboptimal batching)
- Not sorting by sequence length (extreme padding waste with variable-length inputs)
- Processing all data without a checkpoint mechanism (failure at 95% means restarting from zero)
- Setting batch size based on model documentation without testing on actual data
- Writing all outputs to a single file from parallel workers (corruption, contention)
- Not validating a sample of outputs before processing the full dataset
- Ignoring the cost difference between batch and real-time serving for the same workload
- Running batch inference on a GPU that is simultaneously serving online traffic

## References
- vLLM offline batched inference: https://docs.vllm.ai/en/latest/getting_started/examples/offline_inference.html
- SGLang batch processing: https://sgl-project.github.io/
- Speculative decoding paper: https://arxiv.org/abs/2211.17192
- Hugging Face pipeline batching: https://huggingface.co/docs/transformers/main_classes/pipelines
