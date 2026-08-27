# Batch Size Selection

## When to use
- Every training run requires a batch size choice — it affects memory usage, training speed, and generalization
- Batch size interacts with learning rate, optimizer, and hardware constraints
- The "best" batch size is a tradeoff, not a single correct answer

## Pattern

### Small batches (8-64)
- Noisier gradients act as implicit regularization — often better generalization
- Lower GPU memory usage — fits larger models or longer sequences
- Slower wall-clock time per epoch (more optimizer steps, less parallelism)
- Preferred when: dataset is small, generalization is the priority, memory is constrained

### Medium batches (64-512)
- Good balance of speed and generalization for most tasks
- Standard range for fine-tuning pretrained models
- Saturates single-GPU utilization on modern hardware

### Large batches (512-64k+)
- Maximum hardware utilization — fastest wall-clock training
- Requires learning rate scaling (linear scaling rule: LR proportional to batch size)
- Needs warmup to prevent early divergence
- Tends toward sharper minima — may generalize worse without additional techniques (LAMB, warmup)
- Preferred when: distributed training across many GPUs, pretraining at scale

### The linear scaling rule
- When multiplying batch size by k, multiply learning rate by k
- Works up to a critical batch size (task-dependent), beyond which returns diminish
- Use warmup (gradual LR increase over first 1-5% of training) when scaling up

### Gradient accumulation for effective batch size
- Accumulate gradients over N micro-batches, then update weights once
- Effective batch size = micro-batch size x accumulation steps
- Allows large effective batch sizes on limited hardware
- See `gradient-accumulation.md` for detailed patterns

### Finding the right batch size
1. Start with the largest batch that fits in memory (with some headroom)
2. If generalization suffers, reduce batch size or add regularization
3. If training is too slow, increase batch size with LR scaling
4. Use gradient accumulation if memory-limited but wanting larger effective batch

## Gotchas / Anti-patterns
- Increasing batch size without scaling learning rate — training converges slower or to worse solutions
- Using very large batches for fine-tuning — often degrades performance vs small batches
- Assuming bigger batch = better — beyond the critical batch size, you pay more compute for no gain
- Ignoring the generalization gap — large-batch training can produce models that memorize more
- Not accounting for gradient accumulation in effective batch size calculations
- Batch size 1: gradient estimates are extremely noisy, BatchNorm breaks entirely

## References
- "Don't Decay the Learning Rate, Increase the Batch Size" (Smith et al., 2018)
- "Accurate, Large Minibatch SGD" (Goyal et al., 2017) — linear scaling rule
- "An Empirical Model of Large-Batch Training" (McCandlish et al., 2018) — critical batch size
- "Large Batch Optimization for Deep Learning: Training BERT in 76 Minutes" (You et al., 2019)
