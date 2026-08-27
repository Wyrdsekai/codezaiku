# Hugging Face Transformers

## When to use
- Working with pretrained language models (text, vision, audio, multimodal)
- Need standardized model loading, tokenization, and inference pipelines
- Fine-tuning pretrained models on domain-specific data
- Rapid prototyping with state-of-the-art architectures

## Pattern

### Model Loading
- `AutoModelForCausalLM.from_pretrained("model-name")` — auto-detects architecture
- Use `torch_dtype=torch.bfloat16` to halve memory on supported hardware
- For large models: `device_map="auto"` distributes layers across available GPUs
- Load quantized: `load_in_4bit=True` or `load_in_8bit=True` via bitsandbytes integration
- Use `trust_remote_code=True` only for models that ship custom code (review it first)

### Tokenizers
- Always use `AutoTokenizer.from_pretrained()` matched to your model
- Set `padding_side="left"` for batch generation with causal LMs
- Use `return_tensors="pt"` to get PyTorch tensors directly
- For chat models: `tokenizer.apply_chat_template(messages, tokenize=True, add_generation_prompt=True)`
- Watch `max_length` vs `max_new_tokens` — the former is total, the latter is generation-only

### Pipelines (Quick Inference)
- `pipeline("text-generation", model=model, tokenizer=tokenizer)` — high-level wrapper
- Good for prototyping and single-sample inference
- Not ideal for batch throughput — use direct `model.generate()` for production

### Trainer API
- `Trainer(model, args, train_dataset, eval_dataset, tokenizer, data_collator)`
- `TrainingArguments` controls LR, batch size, epochs, logging, checkpointing
- Key args: `per_device_train_batch_size`, `gradient_accumulation_steps`, `learning_rate`, `bf16=True`
- Use `data_collator=DataCollatorForLanguageModeling(tokenizer, mlm=False)` for causal LM
- Supports callbacks: `EarlyStoppingCallback`, custom `TrainerCallback` subclasses

### PEFT / LoRA Integration
- `from peft import get_peft_model, LoraConfig`
- Attach LoRA after loading base model, then pass to Trainer normally
- Merge adapters for deployment: `model = model.merge_and_unload()`

### Saving and Sharing
- `model.save_pretrained("./output")` + `tokenizer.save_pretrained("./output")`
- Push to Hub: `model.push_to_hub("username/model-name")`
- SafeTensors is the default serialization format

## Gotchas / Anti-patterns
- Mismatched tokenizer and model — always load both from the same checkpoint
- Forgetting `add_generation_prompt=True` in chat templates — model generates malformed output
- Using `max_length` when you mean `max_new_tokens` — truncates input instead of limiting output
- Not setting `pad_token` — many models lack one; set `tokenizer.pad_token = tokenizer.eos_token`
- Loading full precision on consumer GPUs — always specify `torch_dtype` or quantization
- Using `pipeline()` in production loops — overhead per call; use batched `model.generate()` instead

## References
- Transformers docs: https://huggingface.co/docs/transformers
- PEFT docs: https://huggingface.co/docs/peft
- Model Hub: https://huggingface.co/models
