# Learning Rate Warmup and Scheduling

## When to use
- Training transformers or large models — warmup is essentially mandatory
- Any training run where early instability is observed (loss spikes, NaN gradients)
- When using adaptive optimizers (Adam, AdamW) that need time to build accurate moment estimates
- When you want to squeeze maximum performance from a fixed compute budget

## Pattern

### Linear Warmup
- Linearly increase LR from 0 (or a small value) to the target LR over N warmup steps
- **Use when**: Default warmup strategy for transformers. Simple and effective
- Typical warmup: 1-10% of total training steps
- For fine-tuning: shorter warmup (100-500 steps). For pretraining: longer (1-5% of total)
- Prevents early gradient explosion when optimizer moment estimates are cold

### Cosine Annealing
- After warmup, decay LR following a cosine curve from peak to near-zero (or a minimum LR)
- LR(t) = lr_min + 0.5 * (lr_max - lr_min) * (1 + cos(pi * t / T))
- **Use when**: LLM pretraining, vision transformer training — the dominant schedule for modern training
- Smooth decay avoids sharp transitions. Final LR is typically 10% of peak or lower
- Can combine with warmup: linear warmup -> cosine decay

### Cosine Annealing with Warm Restarts
- Periodic cosine decay that resets to peak LR at fixed intervals
- Each restart allows the model to escape local minima
- **Use when**: Snapshot ensembles, training where you want diverse checkpoints
- Less common in LLM training but useful for vision and smaller models

### Step Decay
- Reduce LR by a factor (typically 0.1) at predefined milestones
- Example: decay at epoch 30, 60, 90 for a 100-epoch run
- **Use when**: CNN training with SGD — classic ImageNet schedule
- Sharp transitions can cause temporary training instability

### Linear Decay
- After warmup, linearly decrease LR to zero over remaining training steps
- **Use when**: Fine-tuning pretrained language models. Simple and effective for short runs
- BERT/RoBERTa fine-tuning standard: linear warmup (6% of steps) + linear decay

### Inverse Square Root Decay
- LR(t) = lr_peak / sqrt(t) after warmup
- **Use when**: Original transformer schedule (Vaswani et al.). Provides aggressive early decay, slow late decay
- Largely superseded by cosine annealing in modern practice

### One-Cycle Policy (1cycle)
- Ramp LR from low to high (warmup), then from high to low (annealing), in a single cycle
- Maximum LR found via LR range test
- **Use when**: Fast convergence with SGD. Reaches good performance in fewer epochs
- Combine with high momentum at low LR, low momentum at high LR

### Practical guidance
1. Start with linear warmup + cosine decay — this works for most tasks
2. Warmup duration: 1-5% of total training for pretraining, 100-500 steps for fine-tuning
3. Peak LR: use LR range test if unsure, or known defaults (1e-4 to 5e-4 for AdamW + transformers)
4. Minimum LR: 0 or 10% of peak (prevents complete learning stop)
5. For fine-tuning: linear warmup + linear decay is a safe default

## Gotchas / Anti-patterns
- No warmup with Adam/AdamW — moment estimates are zero-initialized, first steps have wildly inaccurate adaptive rates
- Warmup too long — wastes training budget at sub-optimal learning rates
- Warmup too short — fails to stabilize, early training instability persists
- Setting minimum LR to exactly 0 for very long training — model can stall in final phase. Use a small floor
- Using step decay with Adam — Adam's adaptive rates already provide per-parameter decay, step schedule adds little value
- Confusing warmup steps with warmup epochs — at different batch sizes, the same number of epochs is a very different number of steps

## References
- "Attention Is All You Need" (Vaswani et al., 2017) — inverse sqrt schedule
- "SGDR: Stochastic Gradient Descent with Warm Restarts" (Loshchilov & Hutter, 2017)
- "Super-Convergence" (Smith & Topin, 2018) — 1cycle policy
- "Chinchilla: Training Compute-Optimal Large Language Models" (Hoffmann et al., 2022) — cosine schedule for LLM pretraining
