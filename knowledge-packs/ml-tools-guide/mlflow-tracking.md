# MLflow Experiment Tracking

## When to use
- Need self-hosted, open-source experiment tracking (no SaaS dependency)
- Want a model registry for staging, production, and archival lifecycle
- Serving models via a standardized REST API
- Tracking experiments across frameworks (PyTorch, sklearn, XGBoost, LLMs)
- Organizational requirement for on-premise ML infrastructure

## Pattern

### Experiment Tracking
```python
import mlflow

mlflow.set_tracking_uri("http://localhost:5000")  # or file:///path/to/mlruns
mlflow.set_experiment("code-generation-models")

with mlflow.start_run(run_name="qwen3-8b-qlora"):
    mlflow.log_params({
        "model": "qwen3-8b",
        "lora_r": 16,
        "learning_rate": 2e-4,
        "epochs": 3,
    })

    for epoch in range(3):
        metrics = train_epoch()
        mlflow.log_metrics({
            "train_loss": metrics["loss"],
            "eval_accuracy": metrics["accuracy"],
        }, step=epoch)

    mlflow.log_artifact("./output/config.yaml")
```

### Auto-Logging
```python
# PyTorch Lightning
mlflow.pytorch.autolog()

# Hugging Face Transformers
# Set in TrainingArguments:
#   report_to="mlflow"
# MLflow picks up params, metrics, and model automatically

# scikit-learn
mlflow.sklearn.autolog()
```

### Model Registry
```python
# Log model during training
mlflow.pytorch.log_model(model, "model", registered_model_name="codegen-v1")

# Set model version alias (replaces deprecated transition_model_version_stage)
client = mlflow.tracking.MlflowClient()
client.set_registered_model_alias("codegen-v1", alias="production", version=3)

# Load model by alias
model = mlflow.pytorch.load_model("models:/codegen-v1@production")
```

### Model Serving
```bash
# Serve a registered model
mlflow models serve -m "models:/codegen-v1/Production" -p 5001

# Build Docker container
mlflow models build-docker -m "models:/codegen-v1/3" -n codegen-serving
```

### Tracking Server Setup
```bash
# Local file store
mlflow server --backend-store-uri sqlite:///mlflow.db \
              --default-artifact-root ./mlartifacts \
              --host 0.0.0.0 --port 5000

# Production: PostgreSQL + S3
mlflow server --backend-store-uri postgresql://user:pass@host/mlflow \
              --default-artifact-root s3://mlflow-artifacts/ \
              --host 0.0.0.0
```

### Comparing Runs
```python
# Query runs programmatically
runs = mlflow.search_runs(
    experiment_names=["code-generation-models"],
    filter_string="metrics.eval_accuracy > 0.8",
    order_by=["metrics.eval_accuracy DESC"],
)
```

### LLM Tracking (MLflow 3.x)
```python
# Log LLM interactions
with mlflow.start_run():
    mlflow.log_table(
        data={"prompt": prompts, "response": responses, "latency_ms": latencies},
        artifact_file="llm_interactions.json",
    )
```

## Gotchas / Anti-patterns
- Using default `./mlruns` directory for team projects — set up a shared tracking server
- Not setting `run_name` — runs become unidentifiable in the UI
- Logging metrics without `step` parameter — creates flat lines instead of curves
- Giant artifacts logged every run — use artifact versioning and log diffs
- Forgetting to end runs (without context manager) — `mlflow.end_run()` or use `with` block
- Mixing `autolog` with manual logging — can cause duplicate or conflicting entries
- Using deprecated `transition_model_version_stage` — use the aliases API (`set_registered_model_alias`) instead; stages are removed in MLflow 3.x

### MCP Server (MLflow 3.10+)
- MLflow includes a built-in MCP server for AI agent access to experiment data
- Enables agents to query runs, compare metrics, and retrieve artifacts via MCP protocol
- Useful for integrating experiment tracking into agentic workflows

## References
- MLflow 3.x docs: https://mlflow.org/docs/latest/index.html
- Model Registry (aliases API): https://mlflow.org/docs/latest/model-registry.html
- LLM tracking: https://mlflow.org/docs/latest/llms/index.html
- MLflow MCP server: https://mlflow.org/docs/latest/llms/mcp/index.html
