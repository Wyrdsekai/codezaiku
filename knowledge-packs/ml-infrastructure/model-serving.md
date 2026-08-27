# Model Serving

## When to use
- Deploying LLMs or other models behind an API for inference
- Choosing between serving frameworks based on workload characteristics
- Designing request handling for varying latency and throughput requirements
- Moving from development inference to production-grade serving

## Pattern

### Serving Modes

**Real-time (online) inference**:
- Single request, low latency target (sub-second to a few seconds)
- Use continuous batching to amortize overhead across concurrent requests
- Stream tokens as they are generated for perceived responsiveness
- Size the model and hardware so P99 latency meets SLA

**Streaming inference**:
- Server-sent events or WebSocket delivering tokens incrementally
- Client renders partial output as it arrives
- First-token latency (TTFT) matters more than total generation time
- Requires server support for incremental decoding and mid-stream cancellation

**Batch (offline) inference**:
- Collect requests, process in bulk, return results asynchronously
- Maximize throughput (tokens/second) over latency
- Use large batch sizes and longer queue depths
- Cost-effective for non-interactive workloads (see batch-inference.md)

### Server Selection

**SGLang**:
- RadixAttention for automatic prefix caching across requests
- Strong structured output support (constrained decoding, JSON mode)
- Native parsers for major model families
- Best for orchestration-heavy workloads with repeated prompt prefixes
- Good multi-LoRA support

**vLLM**:
- PagedAttention for efficient KV cache management
- Broad model coverage and active community
- Production-hardened with wide deployment base
- Tensor parallelism for multi-GPU serving out of the box
- Check parser availability for your specific model before committing

**TGI (Text Generation Inference)**:
- Tight Hugging Face ecosystem integration
- Flash Attention and continuous batching built in
- Simpler deployment model (single Docker container)
- Good default for teams already in the HF ecosystem

**Ollama**:
- Local-first, single-binary deployment
- GGUF quantized models, easy model management
- Suitable for development, testing, and single-user scenarios
- Not designed for multi-user production throughput

### Deployment Considerations

- Place a load balancer or reverse proxy in front of serving instances
- Health checks should verify model is loaded and responsive, not just process alive
- Use model warmup requests after startup before accepting traffic
- Set request timeouts that account for maximum generation length
- Monitor both TTFT and total generation time separately

## Gotchas / Anti-patterns
- Choosing a server based on benchmarks without verifying it supports your model's chat template and tool format
- Running inference servers with HTTP/2 when the client or proxy expects HTTP/1.1 (common vLLM issue)
- Not testing with realistic concurrent load (single-request benchmarks hide batching behavior)
- Deploying without a graceful shutdown path (in-flight requests get killed)
- Using a heavyweight serving stack for single-user dev workflows where Ollama suffices
- Ignoring model-specific quirks (e.g., thinking mode tokens, special EOS handling) that differ across servers
- Assuming all servers handle streaming cancellation the same way

## References
- SGLang documentation: https://sgl-project.github.io/
- vLLM documentation: https://docs.vllm.ai/
- TGI documentation: https://huggingface.co/docs/text-generation-inference/
- Ollama documentation: https://ollama.com/
