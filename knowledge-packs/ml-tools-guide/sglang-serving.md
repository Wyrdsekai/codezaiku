# SGLang Deployment Patterns

## When to use
- High-throughput LLM serving with RadixAttention KV cache reuse
- Multi-LoRA serving from a single base model
- Structured output generation (JSON, regex-constrained decoding)
- Serving models with complex multi-turn or branching prompt patterns
- Need OpenAI-compatible API for drop-in integration

## Pattern

### Basic Server Launch
```bash
# Single GPU
python -m sglang.launch_server \
  --model-path Qwen/Qwen3-8B \
  --port 30000 \
  --host 0.0.0.0

# With quantization
python -m sglang.launch_server \
  --model-path Qwen/Qwen3-8B \
  --quantization fp8 \
  --port 30000

# Multi-GPU tensor parallelism
python -m sglang.launch_server \
  --model-path Qwen/Qwen3-Coder-30B-A3B \
  --tp 2 \
  --port 30000
```

### Key Server Options
- `--quantization fp8` — FP8 weight-only quantization (Ampere+ GPUs)
- `--quantization awq` — AWQ 4-bit quantization
- `--tp N` — tensor parallelism across N GPUs
- `--mem-fraction-static 0.85` — fraction of GPU memory for KV cache (default 0.88)
- `--max-running-requests 64` — concurrent request limit
- `--context-length 8192` — override model's default context window
- `--chat-template chatml` — set chat template (auto-detected for most models)
- `--enable-torch-compile` — compile model for faster inference (startup cost)

### RadixAttention
- Automatic KV cache sharing for requests with common prefixes
- No configuration needed — enabled by default
- Benefits compound with: system prompts, few-shot examples, multi-turn conversations
- Most effective when many requests share long common prefixes

### Multi-LoRA Serving
```bash
python -m sglang.launch_server \
  --model-path base-model/ \
  --lora-paths adapter1=./lora1 adapter2=./lora2 \
  --max-loras-per-batch 4 \
  --port 30000
```
```python
# Request with specific adapter
response = client.chat.completions.create(
    model="adapter1",
    messages=[{"role": "user", "content": "..."}],
)
```

### Structured Output
```python
import openai

client = openai.Client(base_url="http://localhost:30000/v1", api_key="none")

# JSON schema constraint
response = client.chat.completions.create(
    model="default",
    messages=[{"role": "user", "content": "List 3 colors with hex codes"}],
    response_format={
        "type": "json_schema",
        "json_schema": {
            "name": "colors",
            "schema": {
                "type": "object",
                "properties": {
                    "colors": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "name": {"type": "string"},
                                "hex": {"type": "string"}
                            }
                        }
                    }
                }
            }
        }
    },
)

# Regex constraint
response = client.chat.completions.create(
    model="default",
    messages=[{"role": "user", "content": "Give me a date"}],
    extra_body={"regex": r"\d{4}-\d{2}-\d{2}"},
)
```

### Qwen3 Thinking Mode
- Disable thinking for deterministic tool use: `chat_template_kwargs={"enable_thinking": false}`
- Or via server flag: `--chat-template-kwargs '{"enable_thinking": false}'`

### Health and Metrics
```bash
# Health check
curl http://localhost:30000/health

# Prometheus metrics
curl http://localhost:30000/metrics
```

## Gotchas / Anti-patterns
- Not setting `--mem-fraction-static` appropriately — too high causes OOM, too low wastes capacity
- Ignoring RadixAttention benefits — restructure prompts to maximize shared prefixes
- Using HTTP/2 with some clients — SGLang works best with HTTP/1.1 in some configurations
- Not disabling thinking mode for Qwen3 tool calls — produces unreliable structured output
- Setting `--context-length` beyond model's training length — degraded output quality
- Running FP8 on pre-Ampere GPUs — falls back to FP16 silently or errors

## References
- SGLang docs: https://docs.sglang.ai/
- SGLang GitHub: https://github.com/sgl-project/sglang
- RadixAttention paper: https://arxiv.org/abs/2312.07104
