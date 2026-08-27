# Unsloth QLoRA Fine-Tuning

## When to use
- Fine-tuning LLMs on consumer GPUs (16-24GB VRAM)
- Need 2-5x speedup over standard HF Trainer for LoRA/QLoRA
- Training models up to 70B parameters with aggressive memory optimization
- Want drop-in replacement for HF training with minimal code changes

## Pattern

### Model Loading
- `FastLanguageModel.from_pretrained(model_name, max_seq_length, dtype, load_in_4bit=True)`
- `max_seq_length` — set to your training context length (e.g., 2048, 4096)
- `dtype=None` lets Unsloth auto-detect (bf16 on Ampere+, fp16 otherwise)
- `load_in_4bit=True` enables NF4 quantization via bitsandbytes

### LoRA Configuration
- `FastLanguageModel.get_peft_model(model, r=16, lora_alpha=16, lora_dropout=0, target_modules=[...])`
- Common targets: `q_proj, k_proj, v_proj, o_proj, gate_proj, up_proj, down_proj`
- `r=16` is a practical default; `r=64` for more capacity at cost of speed
- `lora_alpha` typically equals `r` (effective LR multiplier = alpha/r)
- `lora_dropout=0` is recommended — Unsloth applies its own regularization

### Training
- Uses standard HF `SFTTrainer` from `trl`:
```python
trainer = SFTTrainer(
    model=model,
    tokenizer=tokenizer,
    train_dataset=dataset,
    args=TrainingArguments(
        per_device_train_batch_size=2,
        gradient_accumulation_steps=4,
        warmup_steps=5,
        max_steps=60,
        learning_rate=2e-4,
        bf16=True,
        output_dir="outputs",
    ),
    dataset_text_field="text",
    max_seq_length=max_seq_length,
)
trainer.train()
```

### Dataset Preparation
- Format as chat template: `tokenizer.apply_chat_template(conversation)`
- Or use `dataset_text_field` pointing to a pre-formatted text column
- ShareGPT format works directly with `formatting_func` parameter

### Memory Savings
- 4-bit QLoRA: ~4GB for 7B models, ~10GB for 13B, ~24GB for 70B
- Gradient checkpointing enabled by default (trades compute for memory)
- Unsloth kernels reduce memory by fusing operations in attention and MLP layers

### Supported Models
- Llama family (Llama 2/3/3.1/3.2/3.3/4, Code Llama)
- Mistral, Mixtral, Qwen2/2.5/3, Gemma, Phi-3/4, DeepSeek
- Check Unsloth GitHub for the current compatibility matrix

### Export
- Save LoRA adapter: `model.save_pretrained("lora_model")`
- Merge and save full model: `model.save_pretrained_merged("merged", tokenizer)`
- GGUF export: `model.save_pretrained_gguf("gguf_model", tokenizer, quantization_method="q4_k_m")`
- ONNX export: `model.save_pretrained_onnx("onnx_model", tokenizer)` — for Java/C++ inference runtimes (ONNX Runtime)
- vLLM-ready: merge to fp16/bf16, save as SafeTensors

## Gotchas / Anti-patterns
- Using `lora_dropout > 0` — Unsloth recommends 0; non-zero disables some kernel optimizations
- Setting `max_seq_length` higher than needed — wastes memory on attention buffers
- Not using gradient accumulation — small effective batch sizes cause unstable training
- Exporting 4-bit weights directly for inference — merge to higher precision or use GGUF conversion
- Mixing Unsloth model loading with vanilla HF `from_pretrained` — use `FastLanguageModel` consistently
- Training for too many epochs on small datasets — 1-3 epochs usually sufficient; watch eval loss

## References
- Unsloth GitHub: https://github.com/unslothai/unsloth
- Unsloth docs: https://docs.unsloth.ai/
- QLoRA paper: https://arxiv.org/abs/2305.14314
