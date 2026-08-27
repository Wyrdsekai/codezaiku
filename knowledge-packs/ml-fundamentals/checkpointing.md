# Checkpointing

## When to use
- Any training run that takes more than a few minutes — checkpoints are your only recovery path
- You want to select the best model across training, not just the final one
- Training on preemptible/spot instances where interruption is expected
- Experimenting with different training durations or comparing epochs

## Pattern

### What to save
- Model weights (state_dict)
- Optimizer state (momentum buffers, adaptive learning rate accumulators)
- Learning rate scheduler state
- Current epoch/step number
- RNG states (for reproducibility on resume)
- Training metrics at checkpoint time (validation loss, accuracy)
- Any custom state (gradient scaler for mixed precision, EMA weights)

### Checkpoint frequency strategies
- **Every N epochs**: Simple, predictable. Good for short training runs
- **Every N steps**: Better for long epochs or streaming data
- **On validation improvement**: Save whenever validation metric improves — guarantees you always have the best model
- **Periodic + best**: Save at fixed intervals AND whenever a new best is achieved. Most robust approach
- **Time-based**: Save every T minutes — useful when step duration varies

### Checkpoint selection
- **Best validation metric**: Most common — select the checkpoint with lowest validation loss or highest task metric
- **Last K checkpoints**: Keep a rolling window, delete older ones to save disk space
- **Exponential moving average (EMA)**: Maintain a separate EMA of weights — often better than any single checkpoint
- **Checkpoint averaging**: Average weights from last K checkpoints — smooths noise, improves generalization
- **Soup**: Average weights from multiple fine-tuning runs — model soups often outperform individual runs

### Resuming training
1. Load model weights, optimizer state, scheduler state, RNG states
2. Set dataloader to skip to the correct position (or accept re-shuffled data)
3. Verify a few steps produce similar loss to pre-interruption — sanity check
4. Continue training as normal

### Storage considerations
- Large models produce large checkpoints (7B model ~ 14GB in FP16)
- Use sharded checkpoints for models that exceed single-file limits or for parallel loading
- Compress if storage-constrained (gzip/zstd), but adds save/load latency
- Async checkpoint saving: write to disk in a background thread to avoid stalling training

## Gotchas / Anti-patterns
- Saving only model weights without optimizer state — fine for inference, but resume will behave like restarting training with warm weights and cold optimizer
- Overwriting a single checkpoint file — if training corrupts mid-save, you lose everything
- Saving too infrequently on spot instances — losing hours of training to preemption
- Not validating checkpoint integrity — corrupted files from interrupted writes
- Selecting the final checkpoint instead of the best — final is often past the point of overfitting
- Forgetting to save the gradient scaler state when using mixed precision — resume diverges immediately

## References
- PyTorch "Saving and Loading Models" documentation
- Hugging Face `save_pretrained` / `from_pretrained` patterns
- DeepSpeed checkpoint documentation (sharded saving)
- "Model Soups: Averaging Weights of Multiple Fine-tuned Models" (Wortsman et al., 2022)
