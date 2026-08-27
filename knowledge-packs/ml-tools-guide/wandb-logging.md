# Weights & Biases Integration Patterns

## When to use
- Need centralized experiment tracking across team members
- Want automatic hyperparameter sweep orchestration
- Tracking model artifacts (checkpoints, datasets, predictions) with lineage
- Real-time training monitoring with rich dashboards
- Comparing runs across different configurations systematically

## Pattern

### Basic Integration
```python
import wandb

run = wandb.init(
    project="my-project",
    config={
        "learning_rate": 2e-4,
        "batch_size": 32,
        "epochs": 10,
        "model": "resnet50",
    },
)

for epoch in range(config.epochs):
    train_loss = train_one_epoch()
    val_loss = evaluate()
    wandb.log({
        "train/loss": train_loss,
        "val/loss": val_loss,
        "epoch": epoch,
    })

wandb.finish()
```

### HF Trainer Integration
```python
from transformers import TrainingArguments

args = TrainingArguments(
    output_dir="./output",
    report_to="wandb",
    run_name="llama-finetune-v1",
    # W&B picks up all TrainingArguments as config automatically
)
# wandb.init() is called automatically by Trainer
```

### Hyperparameter Sweeps
```yaml
# sweep.yaml
method: bayes
metric:
  name: val/loss
  goal: minimize
parameters:
  learning_rate:
    min: 1e-5
    max: 1e-3
    distribution: log_uniform_values
  batch_size:
    values: [8, 16, 32]
  dropout:
    min: 0.0
    max: 0.5
```
```bash
wandb sweep sweep.yaml          # creates sweep, prints ID
wandb agent <sweep-id>          # launches agents (run on N machines)
```

### Artifact Tracking
```python
# Log a dataset
artifact = wandb.Artifact("training-data-v2", type="dataset")
artifact.add_dir("./data/processed")
run.log_artifact(artifact)

# Log a model checkpoint
model_artifact = wandb.Artifact("model-checkpoint", type="model")
model_artifact.add_file("./output/model.safetensors")
run.log_artifact(model_artifact)

# Use an artifact in another run
artifact = run.use_artifact("training-data-v2:latest")
artifact.download("./data")
```

### Tables and Media
```python
# Log predictions as a table
table = wandb.Table(columns=["input", "predicted", "expected"])
for inp, pred, exp in zip(inputs, predictions, expected):
    table.add_data(inp, pred, exp)
wandb.log({"predictions": table})

# Log images
wandb.log({"examples": [wandb.Image(img, caption=cap) for img, cap in samples]})
```

### Config Best Practices
- Pass all hyperparameters via `wandb.config` — enables sweep compatibility
- Use `config = wandb.config` after init, then reference `config.learning_rate` everywhere
- Tag runs: `wandb.init(tags=["baseline", "4bit"])`
- Group related runs: `wandb.init(group="ablation-study-1")`

## Gotchas / Anti-patterns
- Forgetting `wandb.finish()` — causes hanging processes and incomplete runs
- Logging too frequently (every step) on slow metrics — use step intervals
- Not using `wandb.config` — makes sweeps impossible and comparisons harder
- Hardcoding project names — use environment variables or CLI args for portability
- Logging large artifacts every checkpoint — use `save_steps` intervals
- Leaving API keys in code — use `WANDB_API_KEY` env var or `wandb login`

## References
- W&B docs: https://docs.wandb.ai/
- Sweeps guide: https://docs.wandb.ai/guides/sweeps
- HF integration: https://docs.wandb.ai/guides/integrations/huggingface
