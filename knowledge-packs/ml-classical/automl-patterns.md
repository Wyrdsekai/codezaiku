# AutoML Patterns

## When to use
- Establishing a strong baseline quickly before investing in manual tuning
- Non-ML-expert teams that need production-quality models
- Competitions or time-constrained projects where breadth of exploration matters
- Benchmarking manual models against automated alternatives
- Wide search across model families, preprocessing, and hyperparameters simultaneously

## Pattern

### AutoGluon
- Tabular-focused: `TabularPredictor` trains and ensembles multiple model families automatically
- Default `quality='best_quality'` trains ~10+ models and builds a multi-layer stack ensemble
- `time_limit` in seconds is the primary control: 60s for quick baseline, 3600s for thorough search
- `presets` control the accuracy/speed tradeoff:
  - `'best_quality'`: full stacking, slowest, highest accuracy
  - `'high_quality'`: reduced stacking, good tradeoff
  - `'good_quality'`: faster, single-layer ensemble
  - `'medium_quality'`: no stacking, fast
- AutoGluon handles missing values, categorical encoding, and feature engineering internally
- `leaderboard()` shows per-model performance; `feature_importance()` gives permutation-based importance
- Supports custom models via `hyperparameters` dict; pass your own estimator class
- For deployment: `clone_for_deployment()` strips training artifacts to reduce model size

### auto-sklearn
- Built on scikit-learn: searches over sklearn estimators and preprocessors
- Uses Bayesian optimization (SMAC) for hyperparameter search + meta-learning for warm-starting
- `time_left_for_this_task` (seconds) and `per_run_time_limit` (seconds per model) are the key controls
- Automatically builds an ensemble from the Pareto-optimal models found during search
- `include` / `exclude` parameters let you restrict the search space to specific model families
- Requires careful memory management: set `memory_limit` (MB) to avoid OOM on large datasets
- Version 0.15+ supports successive halving for faster evaluation of unpromising configurations

### When to Use AutoML vs Manual Tuning
- **Use AutoML when**:
  - You have limited ML expertise on the team
  - The dataset is well-structured tabular data
  - You need a baseline quickly (hours, not days)
  - You want to explore model families you would not have considered
  - The problem is a standard classification or regression task
- **Use manual tuning when**:
  - Domain-specific preprocessing or feature engineering is critical
  - The loss function is custom and not supported by the AutoML framework
  - Inference latency or model size constraints require specific architectures
  - You need full control over the training pipeline for reproducibility or auditability
  - The data has complex structure (graphs, sequences, multi-modal) that AutoML does not handle

### General AutoML Best Practices
- Always hold out a test set that AutoML never sees; do not rely solely on AutoML's internal validation
- Set a reasonable time budget: too short misses good models, too long wastes compute on marginal gains
- Inspect the leaderboard: if a single simple model (e.g., LightGBM) dominates, manual tuning of that model may outperform the full ensemble
- Check for data leakage: AutoML will happily learn from leaked features and report inflated scores
- Feature engineering still matters: AutoML explores model space, not feature space. Meaningful domain features improve AutoML results too.
- For production: extract the best single model if the ensemble is too large or slow for deployment

### Other AutoML Frameworks
- **FLAML**: Microsoft's fast AutoML, optimized for low-compute budgets; good when time_limit < 600s
- **H2O AutoML**: JVM-based, good for enterprise deployment; supports distributed training
- **TPOT**: genetic programming over sklearn pipelines; produces readable Python code as output
- **Optuna**: not full AutoML but excellent hyperparameter optimization; use when you know the model family

## Gotchas / Anti-patterns
- Treating AutoML as a black box and deploying without understanding what it built — always inspect the ensemble composition
- Running AutoML on data with leakage: it will exploit the leak and report artificially high scores
- Assuming AutoML handles all preprocessing: some frameworks do not handle time-series ordering, text, or image features
- Using AutoML ensembles in latency-sensitive production without checking inference time — stacked ensembles can be 10-100x slower than a single model
- Comparing AutoML results across frameworks without controlling for time budget, hardware, and data splits
- Auto-sklearn's meta-learning assumes your dataset resembles OpenML benchmarks; highly domain-specific data may not benefit
- AutoGluon's `best_quality` preset can consume significant disk space (10+ GB) for large datasets with many stacking layers

## References
- Erickson et al. (2020), "AutoGluon-Tabular: Robust and Accurate AutoML for Structured Data"
- Feurer et al. (2015), "Efficient and Robust Automated Machine Learning" (auto-sklearn)
- Wang et al. (2021), "FLAML: A Fast and Lightweight AutoML Library"
- He, Zhao, Chu (2021), "AutoML: A Survey of the State-of-the-Art"
