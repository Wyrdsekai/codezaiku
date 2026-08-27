# Caching Strategies

## When to use
- Serving workloads with repeated or overlapping prompt prefixes
- Multi-turn conversations sharing system prompts and context
- Reducing time to first token for common request patterns
- Improving throughput on inference servers under high load

## Pattern

### KV Cache Fundamentals

During autoregressive generation, each transformer layer produces key and value tensors for every token. Rather than recomputing these for all previous tokens at each step, they are cached:

- **Size per token**: 2 x num_layers x hidden_dim x bytes_per_param (key + value)
- **Total KV cache**: tokens_in_context x size_per_token
- For a 7B model at FP16 with 4096 context: roughly 1-2 GB
- KV cache is often the largest variable memory consumer during inference

### PagedAttention

Manages KV cache like virtual memory pages:
- Allocates KV cache in fixed-size blocks (pages) rather than contiguous memory
- Eliminates internal fragmentation from pre-allocated maximum-length buffers
- Enables sharing of KV pages across requests (for common prefixes)
- Near-zero waste: memory allocated proportional to actual sequence length
- Implemented in vLLM, adopted by SGLang and others

### Prefix Caching

Reuses KV cache entries for shared prompt prefixes across requests:
- System prompts, few-shot examples, and shared context are computed once
- Subsequent requests matching the same prefix skip prefill for that portion
- TTFT reduction proportional to the shared prefix length

**Automatic prefix caching (APC)**:
- Server detects matching prefixes and reuses cached KV entries
- No client-side changes needed
- Effectiveness depends on request ordering and cache eviction policy
- vLLM and SGLang both support this

### RadixAttention (SGLang)

Extends prefix caching using a radix tree data structure:
- Organizes cached KV blocks in a prefix tree for efficient lookup
- Supports sharing at any prefix boundary, not just predefined breakpoints
- Handles branching patterns (one prompt, multiple completions)
- LRU eviction at the block level when memory pressure occurs
- Particularly effective for:
  - Multi-turn chat (each turn extends the shared prefix)
  - Tree-of-thought and branching generation
  - Batch requests with shared system prompts

### Prompt Caching (API-level)

Some providers cache processed prompts server-side:
- Client sends a cache key or the provider hashes the prompt prefix
- Reduces both latency and cost for repeated prompts
- Cache has a TTL (typically minutes to hours)
- Useful for applications with stable system prompts and few-shot examples

### Cache Sizing and Eviction

- Allocate KV cache memory = total GPU memory - model weights - overhead
- LRU (Least Recently Used) is the standard eviction policy
- Monitor cache hit rate: below 30% means the cache is too small or workload has low prefix overlap
- For multi-LoRA serving, KV caches are adapter-specific (same prefix with different adapter = different cache entry)

### Quantized KV Cache

- Store KV cache in FP8 or INT8 instead of FP16
- Halves KV cache memory, enabling longer contexts or more concurrent requests
- Small quality impact that should be validated on your workload
- SGLang and vLLM both support FP8 KV cache

## Gotchas / Anti-patterns
- Pre-allocating maximum sequence length KV cache per request (wastes memory, use paged allocation)
- Assuming prefix caching helps when requests have no shared prefixes (randomized prompts get zero benefit)
- Not monitoring cache hit rates (cache may be thrashing with no benefit)
- Caching KV entries across different quantization levels or model versions (invalidation required)
- Using FP8 KV cache without validating quality impact on your specific task
- Ignoring cache warm-up time after server restart (first requests after restart are slower)
- Setting cache size too large and starving the model of memory for batch processing

## References
- PagedAttention paper: https://arxiv.org/abs/2309.06180
- SGLang RadixAttention paper: https://arxiv.org/abs/2312.07104
- vLLM automatic prefix caching: https://docs.vllm.ai/en/latest/features/automatic_prefix_caching.html
- Flash Attention: https://arxiv.org/abs/2205.14135
