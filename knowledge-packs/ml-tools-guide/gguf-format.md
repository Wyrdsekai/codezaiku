# GGUF Quantization Format

## When to use
- Running LLMs on CPU or consumer hardware with limited VRAM
- Deploying models via llama.cpp, Ollama, or other GGML-based runtimes
- Need aggressive quantization (2-8 bit) with acceptable quality trade-offs
- Single-file model distribution (weights + metadata + tokenizer in one file)
- Edge deployment where Python/CUDA dependencies are impractical

## Pattern

### Quantization Levels
| Method | Bits | Quality | Size (7B) | Use case |
|--------|------|---------|-----------|----------|
| Q2_K   | 2.5  | Low     | ~2.7GB    | Experimentation only |
| Q3_K_M | 3.5  | Fair    | ~3.3GB    | Tight memory budget |
| Q4_K_M | 4.5  | Good    | ~4.1GB    | Best balance for most users |
| Q5_K_M | 5.5  | Better  | ~4.8GB    | Higher quality, moderate size |
| Q6_K   | 6.5  | High    | ~5.5GB    | Near-FP16 quality |
| Q8_0   | 8    | Highest | ~7.2GB    | Minimal quality loss |
| F16    | 16   | Full    | ~14GB     | Reference / no quantization |

`Q4_K_M` is the most common choice — good quality-to-size ratio.

### Converting from HF to GGUF
```bash
# Clone llama.cpp
git clone https://github.com/ggml-org/llama.cpp && cd llama.cpp

# Convert HF model to GGUF (FP16 base)
python convert_hf_to_gguf.py /path/to/hf-model --outfile model-f16.gguf --outtype f16

# Quantize to Q4_K_M
./llama-quantize model-f16.gguf model-q4_k_m.gguf Q4_K_M
```

### Converting from Unsloth
```python
# Unsloth has built-in GGUF export
model.save_pretrained_gguf(
    "model-gguf",
    tokenizer,
    quantization_method="q4_k_m",
)
# Also supports: q8_0, q5_k_m, f16, and others
```

### Running with llama.cpp
```bash
# Interactive chat
./llama-cli -m model-q4_k_m.gguf -p "You are a helpful assistant" --interactive

# Server mode (OpenAI-compatible API)
./llama-server -m model-q4_k_m.gguf --host 0.0.0.0 --port 8080

# Batch inference
./llama-cli -m model-q4_k_m.gguf -f prompts.txt -n 256
```

### Running with Ollama
```dockerfile
# Modelfile
FROM ./model-q4_k_m.gguf
TEMPLATE """{{ .System }}
{{ .Prompt }}"""
PARAMETER temperature 0.1
PARAMETER num_ctx 4096
```
```bash
ollama create my-model -f Modelfile
ollama run my-model
```

### Key llama.cpp Server Options
- `-ngl N` — offload N layers to GPU (use 999 for all layers)
- `-c 8192` — context length
- `-t 8` — number of CPU threads
- `--mlock` — lock model in RAM (prevents swapping)
- `-b 512` — batch size for prompt processing

### GGUF File Structure
- Single file containing: model metadata, tokenizer data, tensor data
- Metadata is key-value pairs (architecture, context length, vocab size, etc.)
- Self-describing — no need for separate config.json or tokenizer files
- Can be inspected with `gguf-py`: `python -m gguf.gguf_reader model.gguf`

### Importance Matrix (imatrix) Quantization
```bash
# Generate importance matrix from calibration data
./llama-imatrix -m model-f16.gguf -f calibration.txt -o imatrix.dat

# Quantize with importance matrix (better quality at low bits)
./llama-quantize --imatrix imatrix.dat model-f16.gguf model-q4_k_m.gguf Q4_K_M
```
- Most impactful for Q2/Q3 quantizations
- Use representative text from your domain as calibration data

## Gotchas / Anti-patterns
- Using Q2_K for production — quality loss is significant; Q4_K_M minimum for real use
- Not using imatrix for sub-4-bit quantizations — large quality improvement for free
- Quantizing already-quantized models (e.g., AWQ to GGUF) — double quantization degrades quality
- Setting `-ngl 0` when GPU is available — misses significant speedup from partial offload
- Not checking model architecture support — llama.cpp does not support all architectures
- Assuming GGUF quality equals FP16 — always benchmark on your task after quantization

## References
- GGUF spec: https://github.com/ggml-org/ggml/blob/master/docs/gguf.md
- llama.cpp: https://github.com/ggml-org/llama.cpp
- Quantization comparison: https://github.com/ggml-org/llama.cpp/discussions/2094
