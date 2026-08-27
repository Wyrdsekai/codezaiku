# Experiment Tracking

## When to use
- Running multiple training or fine-tuning experiments with varying hyperparameters
- Needing to reproduce a previous training run exactly
- Comparing model quality across different configurations
- Managing artifacts (checkpoints, evaluation results, configs) across experiments

## Pattern

### Core Components

**Run logging**:
- Record every hyperparameter at run start (learning rate, batch size, rank, epochs, seed)
- Log metrics at regular intervals (loss, eval metrics, learning rate schedule)
- Capture system metrics (GPU utilization, memory usage, throughput)
- Store the exact command or config used to launch the run

**Metadata to always capture**:
- Base model identifier and version (hash or tag)
- Dataset identifier and version (hash or split info)
- Hardware description (GPU type, count, VRAM)
- Software versions (framework, CUDA, driver)
- Random seeds (all of them: Python, NumPy, torch, CUDA)
- Git commit hash of training code

### Reproducibility Checklist

1. Pin all random seeds (model init, data shuffling, dropout)
2. Record exact dependency versions (requirements.txt or lock file)
3. Use deterministic operations where possible (torch.use_deterministic_algorithms)
4. Store the complete configuration, not just diffs from defaults
5. Track data preprocessing steps and any filtering applied
6. Save the tokenizer version alongside the model

### Metric Comparison

- Define a primary metric before starting experiments (do not choose after)
- Track both training loss and held-out validation metrics
- Log at consistent step intervals for apples-to-apples comparison
- Use the same evaluation dataset and split across all runs
- Record wall-clock time and compute cost alongside quality metrics

### Artifact Management

**Checkpoints**: Save periodically (every N steps) and at end of training. Keep best-K by validation metric. Delete intermediates after training completes.

**Evaluation outputs**: Store model predictions on evaluation set for error analysis. Save alongside the run, not in a separate disconnected location.

**Configs**: Treat as code. Store in version control or as run artifacts. Never rely on reconstructing settings from memory.

### Tool-Agnostic Practices

Whether using MLflow, Weights & Biases, Aim, or simple file-based logging:
- Structure runs in projects/experiments/runs hierarchy
- Tag runs with meaningful labels (baseline, ablation-no-mlp, final)
- Write a short description for each run explaining what changed and why
- Export critical results to a durable format (CSV, JSON) independent of the tool

## Gotchas / Anti-patterns
- Starting experiments without defining success criteria and a primary metric
- Logging only final metrics without intermediate training curves (hides instability)
- Relying on tool-specific storage without any export strategy (vendor lock-in)
- Running experiments without version-controlling the training code
- Overwriting previous checkpoints instead of versioning them
- Tracking hyperparameters but not the data version or preprocessing pipeline
- Having no naming convention for runs (run_1, run_2 is not useful after 50 runs)
- Skipping system metric logging and then being unable to diagnose slow runs

## References
- MLflow documentation: https://mlflow.org/docs/latest/
- Weights & Biases documentation: https://docs.wandb.ai/
- Aim (open-source experiment tracker): https://aimstack.io/
- PyTorch reproducibility guide: https://pytorch.org/docs/stable/notes/randomness.html
