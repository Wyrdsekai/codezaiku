# Gradient Accumulation

## When to use
- Your target effective batch size does not fit in GPU memory
- You want large-batch training dynamics on limited hardware (single GPU or small cluster)
- Fine-tuning large models where even batch size 1 barely fits
- Distributed training where you want to further scale effective batch size beyond what data parallelism provides

## Pattern

### Core mechanism
- Run N forward+backward passes without calling the optimizer
- Accumulate (sum) gradients across these N micro-batches
- After N steps, divide accumulated gradients by N (or handle via loss averaging), then optimizer step, then zero gradients
- Effective batch size = micro-batch size x accumulation steps x number of GPUs

### Implementation outline
```
accumulation_steps = N
for i, batch in enumerate(dataloader):
    loss = model(batch) / accumulation_steps   # scale loss
    loss.backward()                             # accumulate gradients
    if (i + 1) % accumulation_steps == 0:
        optimizer.step()                        # update weights
        optimizer.zero_grad()                   # reset gradients
```

### Loss scaling with accumulation
- Divide loss by accumulation_steps before backward — ensures gradient magnitudes match a true large batch
- Alternative: accumulate unscaled, then divide gradients before optimizer step (equivalent but less common)
- With mixed precision: loss scaling interacts with accumulation — scale before division

### Sync patterns in distributed training
- Without accumulation: all-reduce gradients every step
- With accumulation: all-reduce only on the final micro-batch (skip sync for intermediate steps)
- Use `no_sync()` context manager (or equivalent) to disable gradient synchronization on non-update steps
- Saves N-1 communication rounds per effective batch

### Choosing accumulation steps
- Target effective batch size / (micro-batch size x GPU count) = accumulation steps
- Micro-batch size: largest that fits in memory with some headroom
- More accumulation steps = slower training (more sequential forward/backward passes)
- Diminishing returns beyond the critical batch size for your task

## Gotchas / Anti-patterns
- Forgetting to scale the loss by 1/N — gradients are N times too large, equivalent to a massive learning rate
- Not zeroing gradients after optimizer step — gradients from previous effective batch contaminate the next
- BatchNorm with gradient accumulation — statistics are computed per micro-batch, not per effective batch. Use GroupNorm or LayerNorm instead, or sync BN stats
- Gradient clipping applied per micro-batch instead of per effective batch — clips too aggressively
- Logging loss per micro-batch without accounting for accumulation — reported loss is 1/N of actual
- Forgetting `no_sync()` in distributed training — N unnecessary all-reduce operations per effective batch

## References
- Hugging Face Trainer `gradient_accumulation_steps` parameter documentation
- DeepSpeed documentation on gradient accumulation
- "Accurate, Large Minibatch SGD" (Goyal et al., 2017) — batch size scaling context
