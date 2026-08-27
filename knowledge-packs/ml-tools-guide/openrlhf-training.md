# OpenRLHF Training Patterns

## When to use
- Training reward models and aligning LLMs with RLHF/RLAIF
- GRPO (Group Relative Policy Optimization) for reasoning model alignment
- DPO/SimPO/KTO preference optimization at scale
- Need distributed RL training with Ray for large models
- Iterative RLHF with online sample generation

## Pattern

### Supported Algorithms
- **PPO** — classic RLHF with reward model + value model
- **DPO** — direct preference optimization (no reward model needed)
- **GRPO** — group relative policy optimization (reward model, no value model)
- **SimPO** — simple preference optimization (reference-free DPO variant)
- **KTO** — Kahneman-Tversky optimization (works with binary feedback)
- **Rejection Sampling** — best-of-N with reward model scoring
- **Reward Model Training** — Bradley-Terry preference modeling

### Reward Model Training
```bash
openrlhf train_rm \
  --pretrain meta-llama/Llama-3.1-8B-Instruct \
  --dataset your_preference_data \
  --input_key prompt \
  --chosen_key chosen \
  --rejected_key rejected \
  --train_batch_size 64 \
  --micro_train_batch_size 2 \
  --max_len 2048 \
  --learning_rate 9e-6 \
  --output_path ./reward_model \
  --bf16 \
  --flash_attn \
  --gradient_checkpointing
```

### GRPO Training
```bash
openrlhf train_grpo \
  --pretrain meta-llama/Llama-3.1-8B-Instruct \
  --reward_pretrain ./reward_model \
  --dataset your_prompts \
  --input_key prompt \
  --train_batch_size 128 \
  --micro_train_batch_size 2 \
  --rollout_batch_size 128 \
  --max_len 2048 \
  --max_samples 100000 \
  --group_size 8 \
  --num_episodes 1 \
  --learning_rate 5e-7 \
  --kl_coef 0.01 \
  --output_path ./grpo_model \
  --bf16 \
  --flash_attn \
  --gradient_checkpointing
```

### DPO Training
```bash
openrlhf train_dpo \
  --pretrain meta-llama/Llama-3.1-8B-Instruct \
  --dataset your_preference_data \
  --input_key prompt \
  --chosen_key chosen \
  --rejected_key rejected \
  --train_batch_size 64 \
  --micro_train_batch_size 2 \
  --max_len 2048 \
  --learning_rate 5e-7 \
  --beta 0.1 \
  --output_path ./dpo_model \
  --bf16 \
  --flash_attn
```

### Ray Distributed Training
```bash
# Launch Ray cluster first, then:
ray job submit -- python -m openrlhf.cli.train_ppo_ray \
  --pretrain meta-llama/Llama-3.1-70B-Instruct \
  --reward_pretrain ./reward_model \
  --ref_num_nodes 1 \
  --ref_num_gpus_per_node 4 \
  --actor_num_nodes 1 \
  --actor_num_gpus_per_node 4 \
  --reward_num_nodes 1 \
  --reward_num_gpus_per_node 4 \
  --vllm_num_engines 2 \
  --vllm_tensor_parallel_size 2 \
  --dataset your_prompts \
  --output_path ./ppo_model
```

### Data Format
```json
// Preference data (for DPO/RM)
{"prompt": "Explain quantum computing", "chosen": "good response...", "rejected": "bad response..."}

// Prompt-only data (for PPO/GRPO)
{"prompt": "Write a function to sort a list"}

// Multi-turn
{"prompt": [{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]}
```

### Key Configuration Options
- `--group_size 8` — GRPO group size (number of completions per prompt)
- `--kl_coef 0.01` — KL penalty coefficient (higher = stay closer to reference)
- `--beta 0.1` — DPO temperature parameter
- `--flash_attn` — enable FlashAttention-2
- `--gradient_checkpointing` — trade compute for memory
- `--packing_samples` — pack short samples into longer sequences
- `--LoRA_rank 16` — use LoRA instead of full fine-tuning

### Iterative RLHF (Online)
- Generate samples with current policy using vLLM
- Score with reward model
- Train on scored samples
- Repeat — OpenRLHF handles this loop with `--num_episodes`

## Gotchas / Anti-patterns
- Training reward model and policy on the same data — reward model should generalize beyond training prompts
- KL coefficient too low — policy collapses to reward hacking
- KL coefficient too high — no meaningful learning from reward signal
- Not using `--gradient_checkpointing` for large models — immediate OOM
- Skipping reward model evaluation — a bad reward model makes alignment worse
- Using PPO when DPO/GRPO suffice — PPO is more complex, harder to tune, and needs a value model

## References
- OpenRLHF GitHub: https://github.com/OpenRLHF/OpenRLHF
- OpenRLHF docs: https://openrlhf.readthedocs.io/
- GRPO paper: https://arxiv.org/abs/2402.03300
- DPO paper: https://arxiv.org/abs/2305.18290
