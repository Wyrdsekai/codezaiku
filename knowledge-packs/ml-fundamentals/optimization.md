# Optimization

## When to use
- Every training run requires an optimizer — the choice affects convergence speed, stability, and final performance
- Optimizer selection interacts heavily with learning rate, batch size, and model architecture
- No single optimizer wins everywhere; the right choice depends on task, scale, and compute budget

## Pattern

### SGD + Momentum
- **Use when**: Training CNNs, when you have compute budget for hyperparameter tuning, when generalization matters most
- Momentum (typically 0.9) smooths gradient updates and accelerates through flat regions
- Nesterov momentum provides lookahead — slightly better convergence in practice
- Often generalizes better than Adam-family on vision tasks, but requires careful LR tuning
- Typical LR: 0.01-0.1 with decay schedule

### Adam (Adaptive Moment Estimation)
- **Use when**: Default starting point for most tasks, especially NLP and generative models
- Maintains per-parameter adaptive learning rates via first and second moment estimates
- Less sensitive to initial learning rate choice than SGD
- Typical LR: 1e-4 to 3e-4. Betas: (0.9, 0.999). Epsilon: 1e-8
- Can converge to sharper minima than SGD — sometimes worse generalization

### AdamW (Adam with Decoupled Weight Decay)
- **Use when**: Training transformers, LLM fine-tuning, any Adam use case where you also want regularization
- Fixes Adam's incorrect weight decay implementation — applies decay directly to weights, not gradients
- Standard choice for transformer training. Typical weight decay: 0.01-0.1
- Preferred over Adam in nearly all modern training pipelines

### LAMB (Layer-wise Adaptive Moments for Batch training)
- **Use when**: Large-batch distributed training (batch size > 8k)
- Applies per-layer learning rate scaling to AdamW
- Enables stable training with very large batch sizes that would diverge with AdamW alone
- Primarily used in large-scale pretraining (BERT, vision models at scale)

### Learning Rate Scheduling
- **Constant with warmup**: Simple, effective baseline — warm up for 5-10% of training, then hold
- **Cosine annealing**: Smooth decay to near-zero — standard for transformer pretraining
- **Step decay**: Reduce LR by factor at fixed milestones — common in vision (SGD)
- **Linear decay**: Gradual linear reduction — common in fine-tuning
- **1cycle**: Ramp up then ramp down — fast convergence, good with SGD
- Always include warmup when using Adam/AdamW to stabilize early gradient estimates

## Gotchas / Anti-patterns
- Using Adam with L2 regularization instead of AdamW with weight decay — they are not equivalent
- Setting learning rate too high with Adam ("it's adaptive, it'll adapt") — Adam still diverges with bad LR
- No learning rate warmup with transformers — causes training instability in first few hundred steps
- Using LAMB at small batch sizes — overhead without benefit, AdamW is sufficient
- Forgetting to exclude bias and normalization parameters from weight decay
- Tuning optimizer while ignoring batch size — they interact strongly (linear scaling rule)

## References
- "Decoupled Weight Decay Regularization" (Loshchilov & Hutter, 2019) — AdamW paper
- "Large Batch Optimization for Deep Learning" (You et al., 2019) — LAMB paper
- "Super-Convergence" (Smith & Topin, 2018) — 1cycle policy
- "Fixing Weight Decay Regularization in Adam" — why AdamW matters
