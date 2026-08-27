# Adapter Deployment Guide

## Standard Pipeline: Train → Merge → Export → Deploy

The standard 2026 workflow for deploying fine-tuned models:

```
QLoRA/DoRA Train → LoRA Adapter
    → merge adapter + base → FP16 Checkpoint
    → quantize to GGUF (Q4_K_M, Q5_K_M, Q8_0, F16)
    → deploy to inference engine (Ollama, vLLM, SGLang)
```

## Engine-Specific Deployment

### Ollama

Ollama requires GGUF format. After merge + GGUF export:

```bash
# Via CodeZaiku pipeline
codezaiku-finetune merge --adapter output/adapter/final --output output/merged
codezaiku-finetune gguf-export --model output/merged --quantization Q4_K_M
codezaiku-finetune deploy --model output/gguf/merged-Q4_K_M.gguf --engine ollama --model-name my-model

# Manual equivalent
cat > Modelfile <<EOF
FROM /path/to/model-Q4_K_M.gguf
EOF
ollama create my-model -f Modelfile
ollama run my-model
```

### vLLM

vLLM supports both GGUF and HF checkpoint formats. For adapter hot-loading without merging:

```bash
# Hot-load LoRA adapters (no merge needed)
vllm serve base-model --enable-lora --lora-modules my-adapter=output/adapter/final

# Merged model deployment
codezaiku-finetune merge --adapter output/adapter/final --output output/merged
codezaiku-finetune deploy --model output/merged --engine vllm --model-name my-model

# GGUF deployment
codezaiku-finetune deploy --model output/gguf/model-Q4_K_M.gguf --engine vllm --model-name my-model
```

### SGLang

SGLang supports LoRA hot-loading via `--lora-paths`:

```bash
# Hot-load LoRA adapters (no merge needed)
python -m sglang.launch_server --model-path base-model --lora-paths my-adapter=output/adapter/final

# Merged model deployment
codezaiku-finetune merge --adapter output/adapter/final --output output/merged
codezaiku-finetune deploy --model output/merged --engine sglang --model-name my-model
```

## Adapter Hot-Loading vs Merging

### Hot-Loading (no merge)
- **Pros**: No merge step, can swap adapters per-request, saves disk space
- **Cons**: Slightly higher latency, requires engine support
- **Engines**: vLLM (`--enable-lora`), SGLang (`--lora-paths`)
- **Not supported**: Ollama (requires GGUF merge)

### Merging (full pipeline)
- **Pros**: Single model file, maximum inference speed, works with all engines
- **Cons**: Loses adapter swappability, requires disk space per variant
- **Use when**: Deploying to Ollama, or when inference speed is critical

## Quantization Types

| Type | Size (7B) | Quality | Speed | Use Case |
|------|-----------|---------|-------|----------|
| Q4_K_M | ~4.1 GB | Good | Fast | Default for most deployments |
| Q5_K_M | ~4.8 GB | Better | Fast | Good quality/size balance |
| Q8_0 | ~7.2 GB | High | Medium | When quality matters |
| F16 | ~14.2 GB | Highest | Slower | Reference, further quantization |

### Importance Matrix Quantization

For higher quality at the same size, use calibration-weighted quantization:

```bash
# Generate importance matrix from representative data
codezaiku-finetune imatrix --model output/gguf/model-F16.gguf \
    --calibration-data data/calibration.txt

# Export with importance matrix
codezaiku-finetune gguf-export --model output/merged \
    --quantization Q4_K_M \
    --importance-matrix output/imatrix.dat
```

This identifies which weights are most important and allocates higher precision to them during quantization.

## Full Fine-Tuning vs QLoRA vs DoRA

```bash
# Standard QLoRA (default, ~6GB VRAM for 8B model)
codezaiku-finetune train --dataset data/train.jsonl

# DoRA — better quality LoRA variant (same VRAM as QLoRA)
codezaiku-finetune train --dataset data/train.jsonl --dora

# Full fine-tuning (no LoRA, ~32GB+ VRAM for 8B model)
codezaiku-finetune train --dataset data/train.jsonl --full-finetune
```

- **QLoRA**: Default. 4-bit quantized base + LoRA adapters. Best VRAM efficiency.
- **DoRA**: Weight-Decomposed LoRA. Decomposes into magnitude + direction components. Better quality than standard LoRA at same rank, minimal overhead.
- **Full fine-tune**: Updates all parameters. Highest quality ceiling but requires much more VRAM. No adapter to merge — output is already a full checkpoint.
