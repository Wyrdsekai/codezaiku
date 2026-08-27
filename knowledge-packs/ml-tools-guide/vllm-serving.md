# vLLM Deployment Patterns

## When to use
- Production LLM serving with PagedAttention memory optimization
- Multi-LoRA serving with dynamic adapter loading
- Tensor parallelism across multiple GPUs
- Need OpenAI-compatible API server
- Speculative decoding for latency reduction

## Pattern

### Basic Server Launch
```bash
# Single GPU
vllm serve Qwen/Qwen3-8B \
  --port 8000 \
  --host 0.0.0.0

# With quantization
vllm serve Qwen/Qwen3-Coder-30B-A3B-AWQ \
  --quantization awq \
  --port 8000

# Multi-GPU
vllm serve meta-llama/Llama-4-Scout-17B-16E-Instruct \
  --tensor-parallel-size 4 \
  --port 8000
```

### Key Server Options
- `--dtype auto` — auto-detect precision (bf16 on Ampere+, fp16 otherwise)
- `--quantization awq|gptq|fp8` — load quantized models
- `--tensor-parallel-size N` — split model across N GPUs
- `--max-model-len 8192` — limit context length to save memory
- `--gpu-memory-utilization 0.9` — fraction of GPU memory for KV cache (default 0.9)
- `--max-num-seqs 256` — maximum concurrent sequences
- `--enable-prefix-caching` — cache common prefixes (similar to RadixAttention)
- `--enforce-eager` — disable CUDA graphs (useful for debugging)
- `--tool-call-parser` — enable tool calling support (model-specific parser)

### PagedAttention
- Manages KV cache as virtual memory pages — eliminates fragmentation
- No configuration needed — enabled automatically
- Allows near-optimal memory utilization for variable-length sequences
- Enables higher batch sizes than naive pre-allocation
- PagedAttention v2 improves performance with multi-query/grouped-query attention models and reduces synchronization overhead in multi-GPU setups

### LoRA Serving
```bash
vllm serve base-model/ \
  --enable-lora \
  --lora-modules adapter1=./lora1 adapter2=./lora2 \
  --max-loras 4 \
  --max-lora-rank 64 \
  --port 8000
```
```python
response = client.chat.completions.create(
    model="adapter1",
    messages=[{"role": "user", "content": "..."}],
)
```

### Structured Output
```python
from openai import OpenAI

client = OpenAI(base_url="http://localhost:8000/v1", api_key="none")

# JSON mode
response = client.chat.completions.create(
    model="Qwen/Qwen3-8B",
    messages=[{"role": "user", "content": "List 3 fruits as JSON"}],
    response_format={"type": "json_object"},
)

# Guided decoding with JSON schema
response = client.chat.completions.create(
    model="Qwen/Qwen3-8B",
    messages=[{"role": "user", "content": "..."}],
    extra_body={
        "guided_json": {
            "type": "object",
            "properties": {"name": {"type": "string"}, "age": {"type": "integer"}},
            "required": ["name", "age"],
        }
    },
)
```

### Tool Calling
```bash
# Launch with tool call parser
vllm serve Qwen/Qwen3-Coder-30B-A3B \
  --tool-call-parser qwen3_coder \
  --enable-auto-tool-choice
```

### Speculative Decoding
```bash
# Use a smaller draft model
vllm serve large-model/ \
  --speculative-model small-model/ \
  --num-speculative-tokens 5
```

### Offline Batch Inference
```python
from vllm import LLM, SamplingParams

llm = LLM(model="Qwen/Qwen3-8B")
params = SamplingParams(temperature=0.1, max_tokens=512)

prompts = ["Explain recursion", "Write a haiku about code"]
outputs = llm.generate(prompts, params)

for output in outputs:
    print(output.outputs[0].text)
```

## Gotchas / Anti-patterns
- Not checking tool-call-parser compatibility for your model — not all models have parsers
- Setting `gpu-memory-utilization` too high — leaves no room for activation memory, causes OOM
- Forgetting `--enable-prefix-caching` for multi-turn workloads — misses easy throughput gains
- Using `--enforce-eager` in production — disables CUDA graphs, significant throughput penalty
- Mismatched quantization flag and model format — silently loads wrong precision
- Not setting `--max-model-len` for long-context models — allocates more KV cache than needed (Llama 4 Scout supports 10M context but you almost certainly want to cap this)
- **Parser compatibility (v0.16)**: vLLM v0.16 has no working `qwen` parser for Qwen3 base models; `qwen3_xml` has known bugs. Only `qwen3_coder` parser works reliably, and only with Qwen3-Coder models. Test parser compatibility before deploying.
- Needing to force HTTP/1.1 (`--uvicorn-settings http=h11`) in some proxy/load-balancer configurations — vLLM defaults to HTTP/2 which can cause issues with certain reverse proxies

## References
- vLLM docs: https://docs.vllm.ai/
- vLLM GitHub: https://github.com/vllm-project/vllm
- PagedAttention paper: https://arxiv.org/abs/2309.06180
