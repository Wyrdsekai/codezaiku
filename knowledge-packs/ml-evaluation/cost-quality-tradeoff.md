# Cost-Quality Tradeoff

## When to use
- Selecting model size and architecture under compute or latency constraints
- Deciding between a large accurate model and a smaller faster one
- Optimizing inference cost for production deployments at scale
- Building multi-model systems (routing, cascading, distillation)

## Pattern

### Pareto Frontier Analysis
- Plot models on a 2D chart: x-axis = cost (latency, compute, dollars), y-axis = quality metric
- The Pareto frontier is the set of models where no other model is both cheaper and better
- Models below the frontier are dominated; candidates on the frontier are the only rational choices
- Decision depends on where you sit on the frontier: how much quality per unit of cost is acceptable?
- Extend to 3+ dimensions (latency, cost, quality) but visualization becomes harder

### Cost Dimensions
- **Inference latency**: time to first token, time to last token, tokens per second
- **Compute cost**: GPU-hours per request, cost per 1M tokens
- **Memory footprint**: VRAM required, determines hardware tier
- **Throughput**: requests per second per GPU at target latency
- **Operational cost**: complexity of deployment, monitoring, on-call burden
- Measure all dimensions; optimizing one often degrades another

### Model Size Selection
- Larger models generally score higher on benchmarks but cost more to serve
- Diminishing returns: the jump from 1B to 7B parameters matters more than 70B to 200B for most tasks
- Task complexity determines the minimum viable model size:
  - Classification, extraction, simple Q&A: small models often suffice (1-7B)
  - Multi-step reasoning, code generation, complex analysis: larger models required (30B+)
  - Creative generation, open-ended dialogue: benefits from scale but with diminishing returns
- Always benchmark your specific task; general leaderboard rankings are poor proxies

### Quantization Tradeoffs
- FP16 -> INT8: ~2x memory reduction, minimal quality loss for most tasks
- INT8 -> INT4: ~2x further reduction, measurable quality loss on reasoning-heavy tasks
- GPTQ, AWQ, GGUF: different quantization schemes with different quality-size tradeoffs
- Always evaluate quantized models on your specific eval suite; do not assume general claims hold
- Quantization sensitivity varies by model architecture and task type

### Cascading and Routing
- **Cascade**: try a cheap model first; escalate to an expensive model if confidence is low
  - Requires calibrated confidence scores from the cheap model
  - 60-80% of requests handled by cheap model = significant cost reduction
- **Routing**: classify the request difficulty upfront; send to the appropriate model tier
  - Train a lightweight classifier on (query features -> required model tier)
  - Risk: misrouting hard queries to weak models degrades user experience
- **Speculative decoding**: small draft model proposes tokens, large model verifies in batches
  - Maintains large model quality with reduced latency
  - Effectiveness depends on draft model's acceptance rate

### Distillation
- Train a smaller student model to mimic a larger teacher model's outputs
- Student can approach teacher quality at a fraction of the inference cost
- Works best when: you have abundant unlabeled data, task is well-defined, quality bar is clear
- Evaluate the student on the full eval suite, not just the distillation loss

### Caching and Batching
- Semantic caching: cache responses for similar queries; dramatically reduces cost for repetitive workloads
- Prompt caching: reuse KV cache for shared prefixes across requests
- Dynamic batching: accumulate requests and process in batches for higher throughput
- These are pure cost reductions with no quality impact (when implemented correctly)

### Decision Framework
1. Define the minimum acceptable quality threshold on your eval suite
2. Profile all candidate models: quality, latency, cost, memory
3. Plot the Pareto frontier
4. Identify the cheapest model above the quality threshold
5. Evaluate whether cascading/routing can further reduce cost while maintaining the threshold
6. Load test at target throughput; verify latency SLAs hold under production-like conditions
7. Monitor quality in production; model performance can degrade with distribution shift

## Gotchas / Anti-patterns
- Choosing the largest model "to be safe" without measuring whether smaller models suffice
- Optimizing for benchmark quality without measuring production latency
- Assuming quantization quality loss is uniform across all task types
- Building a cascade without calibrated confidence scores (garbage in, garbage routing)
- Ignoring operational complexity: a cascade of three models costs more to maintain than one
- Not re-evaluating tradeoffs as new models are released (the frontier shifts frequently)
- Measuring latency on a single request instead of under realistic concurrent load

## References
- Sardana & Frankle, "Beyond Chinchilla-Optimal: Accounting for Inference" (2023)
- Leviathan et al., "Fast Inference from Transformers via Speculative Decoding" (2023)
- Ding et al., "Efficiency Benchmarking of LLMs" (2024)
