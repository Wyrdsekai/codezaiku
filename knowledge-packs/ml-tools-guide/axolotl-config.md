# Axolotl YAML-Driven Training

## When to use
- Want declarative, config-driven fine-tuning without writing training scripts
- Need a single tool that handles SFT, DPO, RLHF, and continued pretraining
- Multi-GPU training with DeepSpeed or FSDP via config flags
- Reproducible training runs defined entirely by a YAML file

## Pattern

### Core YAML Structure
```yaml
base_model: meta-llama/Llama-3.1-8B-Instruct
model_type: LlamaForCausalLM
load_in_4bit: true
adapter: qlora
lora_r: 32
lora_alpha: 16
lora_target_linear: true

datasets:
  - path: your_dataset.jsonl
    type: sharegpt
    conversation: chatml

sequence_len: 4096
sample_packing: true

micro_batch_size: 2
gradient_accumulation_steps: 4
num_epochs: 3
learning_rate: 2e-4
optimizer: adamw_torch
lr_scheduler: cosine
warmup_steps: 10

bf16: auto
tf32: true
gradient_checkpointing: true

output_dir: ./output
save_strategy: steps
save_steps: 100
```

### Dataset Formats
- `sharegpt` — `{"conversations": [{"from": "human", "value": "..."}, {"from": "gpt", "value": "..."}]}`
- `alpaca` — `{"instruction": "...", "input": "...", "output": "..."}`
- `completion` — raw text for continued pretraining
- Custom: define `type:` as a Jinja2 template or Python function path

### Sample Packing
- `sample_packing: true` — packs multiple short samples into one sequence
- Significantly improves throughput when samples are shorter than `sequence_len`
- Requires `pad_to_sequence_len: true` for correct padding behavior

### RL / Alignment Training (via TRL integration)
- DPO: set `rl: dpo` — direct preference optimization with chosen/rejected pairs
- IPO: set `rl: ipo` — identity preference optimization, regularized variant of DPO
- KTO: set `rl: kto` — Kahneman-Tversky optimization, works with binary (good/bad) feedback instead of pairs
- ORPO: set `rl: orpo` — combines SFT + preference in one pass
- GRPO: set `rl: grpo` — group relative policy optimization, reward-model-free RL
- All RL methods require dataset format with `chosen` and `rejected` fields (except KTO which uses binary labels and GRPO which uses reward scores)

### Multi-GPU
- DeepSpeed: `deepspeed: deepspeed_configs/zero2.json`
- FSDP: `fsdp:` block with sharding strategy and auto_wrap policy
- Launch: `accelerate launch -m axolotl.cli.train config.yaml`

### Key Config Options
- `flash_attention: true` — use FlashAttention-2 (requires compatible GPU)
- `val_set_size: 0.05` — hold out 5% for validation
- `eval_steps: 50` — evaluate every 50 steps
- `early_stopping_patience: 3` — stop if eval loss plateaus
- `special_tokens:` — override pad, bos, eos tokens
- `chat_template: chatml` — apply specific chat format

### Running
```bash
# Train
accelerate launch -m axolotl.cli.train config.yaml

# Merge LoRA adapter
python -m axolotl.cli.merge_lora config.yaml --lora_model_dir="./output"

# Inference test
python -m axolotl.cli.inference config.yaml --lora_model_dir="./output"
```

## Gotchas / Anti-patterns
- Not setting `sample_packing` for short-sequence datasets — major throughput waste
- Forgetting `gradient_checkpointing` with large models — OOM on first backward pass
- Using `lora_target_modules` list when `lora_target_linear: true` covers all linear layers
- Mismatched `conversation` format and actual dataset structure — silent data corruption
- Setting `num_epochs` too high without `val_set_size` — no signal for overfitting
- Not pinning the base model revision — training becomes non-reproducible over time

## References
- Axolotl GitHub: https://github.com/axolotl-ai-cloud/axolotl
- Axolotl docs: https://axolotl-ai-cloud.github.io/axolotl/
- Example configs: https://github.com/axolotl-ai-cloud/axolotl/tree/main/examples
