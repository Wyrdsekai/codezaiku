# Trackio Local-First Experiment Tracking

## When to use
- Need lightweight, local-first experiment tracking without SaaS accounts
- Want a simple API that replaces W&B/MLflow for solo or small-team use
- MCP integration for AI agent access to experiment data
- SQLite-backed storage that works offline and requires zero infrastructure
- Quick setup for research or prototyping without server deployment

## Pattern

### Basic Tracking
```python
import trackio as tr

# Initialize a run
run = tr.init(
    project="my-project",
    name="experiment-v1",
    config={
        "learning_rate": 2e-4,
        "batch_size": 16,
        "model": "qwen3-8b-qlora",
    },
)

# Log metrics during training
for step in range(1000):
    loss = train_step()
    tr.log({"loss": loss, "step": step})

# Log evaluation results
tr.log({"eval/accuracy": 0.87, "eval/f1": 0.84})

# Finish the run
tr.finish()
```

### Context Manager Usage
```python
with tr.init(project="my-project", name="run-1", config=config) as run:
    for epoch in range(epochs):
        metrics = train_epoch()
        tr.log(metrics)
    # automatically calls finish()
```

### Viewing Results
```bash
# Launch local dashboard
trackio ui

# View in terminal
trackio list --project my-project
trackio show --run experiment-v1
```

### Comparing Runs
```python
# Query runs programmatically
runs = tr.list_runs(project="my-project")
for run in runs:
    print(f"{run.name}: {run.config} -> {run.summary}")
```

### MCP Integration
- Trackio exposes an MCP server for AI agent access to experiment data
- Agents can query past runs, compare metrics, retrieve configurations
- Enables automated experiment analysis and hyperparameter recommendations

```bash
# Start MCP server
trackio mcp-server

# Configure in MCP client
# {
#   "trackio": {
#     "command": "trackio",
#     "args": ["mcp-server"]
#   }
# }
```

### Storage
- All data stored in local SQLite database
- Default location: `~/.trackio/` or project-local `.trackio/`
- Portable — copy the database file to share experiments
- No network access required for any operation

### HF Trainer Integration
```python
from trackio.integrations import TrackioCallback

trainer = Trainer(
    model=model,
    args=training_args,
    callbacks=[TrackioCallback(project="my-project")],
)
```

### Artifact Logging
```python
# Log files alongside metrics
tr.log_artifact("model.safetensors", type="model")
tr.log_artifact("config.yaml", type="config")
tr.log_artifact("eval_results.json", type="evaluation")
```

### Tags and Notes
```python
run = tr.init(
    project="my-project",
    name="ablation-dropout",
    tags=["ablation", "dropout", "v2"],
    notes="Testing dropout=0.3 vs baseline",
    config=config,
)
```

## Gotchas / Anti-patterns
- Not calling `tr.finish()` — incomplete runs with missing summary metrics
- Logging too many keys per step — clutters the dashboard; group related metrics with prefixes
- Using Trackio for large-team coordination — better suited for individual or small-team use
- Not backing up the SQLite database — losing experiments if disk fails
- Logging large binary artifacts every step — store checkpoints separately, log paths as metadata

## References
- Trackio GitHub: https://github.com/trackio-dev/trackio
- Trackio docs: https://trackio.dev/docs
- MCP protocol: https://modelcontextprotocol.io/
