# Optuna Hyperparameter Optimization

## When to use
- Bayesian hyperparameter search that outperforms grid/random search
- Need early stopping of bad trials (pruning) to save compute
- Multi-objective optimization (e.g., minimize loss AND latency)
- Want a persistent study that can be resumed across sessions
- Framework-agnostic — works with PyTorch, TensorFlow, sklearn, XGBoost, LLM training

## Pattern

### Basic Study
```python
import optuna

def objective(trial):
    lr = trial.suggest_float("learning_rate", 1e-5, 1e-2, log=True)
    batch_size = trial.suggest_categorical("batch_size", [8, 16, 32])
    dropout = trial.suggest_float("dropout", 0.0, 0.5)
    n_layers = trial.suggest_int("n_layers", 2, 8)

    model = build_model(n_layers=n_layers, dropout=dropout)
    val_loss = train_and_evaluate(model, lr=lr, batch_size=batch_size)
    return val_loss

study = optuna.create_study(direction="minimize")
study.optimize(objective, n_trials=100, timeout=3600)

print(f"Best params: {study.best_params}")
print(f"Best value: {study.best_value}")
```

### Parameter Types
- `suggest_float(name, low, high, log=False, step=None)` — continuous
- `suggest_int(name, low, high, log=False, step=1)` — integer
- `suggest_categorical(name, choices)` — discrete set
- Use `log=True` for parameters spanning orders of magnitude (learning rates)

### Pruning (Early Stopping)
```python
from optuna.pruners import MedianPruner

study = optuna.create_study(
    direction="minimize",
    pruner=MedianPruner(n_startup_trials=5, n_warmup_steps=10),
)

def objective(trial):
    model = build_model(trial)
    for epoch in range(50):
        loss = train_one_epoch(model)
        trial.report(loss, epoch)        # report intermediate value
        if trial.should_prune():         # check if we should stop
            raise optuna.TrialPruned()
    return loss
```

### Persistent Storage
```python
# SQLite for single-machine persistence
study = optuna.create_study(
    study_name="my-experiment",
    storage="sqlite:///optuna.db",
    load_if_exists=True,
)

# PostgreSQL for distributed optimization
study = optuna.create_study(
    study_name="distributed-search",
    storage="postgresql://user:pass@host/optuna",
    load_if_exists=True,
)
# Run same script on multiple machines — they share the study
```

### Multi-Objective
```python
def objective(trial):
    lr = trial.suggest_float("lr", 1e-5, 1e-2, log=True)
    accuracy = train_and_get_accuracy(lr)
    latency = measure_inference_latency()
    return accuracy, latency  # multiple return values

study = optuna.create_study(
    directions=["maximize", "minimize"],
)
study.optimize(objective, n_trials=50)

# Pareto-optimal trials
for trial in study.best_trials:
    print(trial.values, trial.params)
```

### HF Trainer Integration
```python
from optuna import Trial

def optuna_hp_space(trial: Trial):
    return {
        "learning_rate": trial.suggest_float("lr", 1e-5, 5e-4, log=True),
        "per_device_train_batch_size": trial.suggest_categorical("bs", [4, 8, 16]),
        "warmup_ratio": trial.suggest_float("warmup", 0.0, 0.2),
    }

best_run = trainer.hyperparameter_search(
    direction="minimize",
    hp_space=optuna_hp_space,
    n_trials=20,
    backend="optuna",
)
```

### Visualization
```python
from optuna.visualization import plot_optimization_history, plot_param_importances

plot_optimization_history(study).show()
plot_param_importances(study).show()
```

## Gotchas / Anti-patterns
- Using grid search when Bayesian (TPE) would find better params faster
- Not using pruning — wastes compute on obviously bad trials
- Too few `n_startup_trials` in pruner — prunes before sampler has learned the space
- Not persisting studies — losing results when the process crashes
- Searching too wide a range — narrow based on domain knowledge first
- Ignoring `study.best_trials` for multi-objective — no single "best" exists

## References
- Optuna docs: https://optuna.readthedocs.io/
- Pruning: https://optuna.readthedocs.io/en/stable/tutorial/10_key_features/003_efficient_optimization_algorithms.html
- GitHub: https://github.com/optuna/optuna
