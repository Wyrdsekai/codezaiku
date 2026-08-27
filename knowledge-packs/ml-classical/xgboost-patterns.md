# XGBoost Patterns

## When to use
- Tabular data where you need state-of-the-art predictive accuracy
- Datasets with missing values (native handling, no imputation required)
- You need GPU-accelerated tree training
- Ranking, survival analysis, or custom objective functions
- Production deployment requiring serialized model portability

## Pattern

### Core Tuning Strategy
- Always use early stopping: set a high `n_estimators` (e.g., 10000) and let `early_stopping_rounds` (50-100) find the right count
- Start with `learning_rate=0.1`, tune other params, then lower learning rate and retrain
- `max_depth=6` is the default and a good starting point; for wide-shallow data try 3-4, for deep interactions try 8-10
- `min_child_weight` controls leaf size: increase for noisy data (10-50), keep low (1-5) for clean data
- `subsample` and `colsample_bytree` at 0.7-0.9 reduce overfitting and speed up training
- `gamma` (min split loss) is a regularization param; start at 0, increase if overfitting

### Feature Importance
- `weight`: how many times a feature appears in splits — biased toward continuous features
- `gain`: average gain from splits using that feature — most useful for understanding predictive contribution
- `cover`: average number of samples affected — useful for understanding breadth of impact
- For trustworthy importance: use SHAP values or permutation importance, not built-in importance
- `xgb.plot_importance()` defaults to `weight`; explicitly pass `importance_type='gain'`

### Missing Value Handling
- XGBoost learns optimal missing-value direction at each split during training
- Do not impute missing values before feeding to XGBoost unless you have domain-specific imputation logic
- Mark genuinely missing values as `NaN` (float) or use the `missing` parameter for sentinel values
- This works for tree-based methods only; linear booster still requires imputation

### GPU Training
- Set `tree_method='gpu_hist'` (XGBoost <2.0) or `device='cuda'` (XGBoost >=2.0)
- GPU training is fastest with `max_bin=256` (default); increasing it slows GPU more than CPU
- GPU memory scales with dataset size; for large datasets use `subsample` to fit in VRAM
- Multi-GPU: use `device='cuda:0'` etc. with Dask or Spark for distributed training
- CPU prediction is fine for inference unless batch-scoring millions of rows

### Objective Selection
- Binary classification: `binary:logistic` (probabilities) or `binary:hinge` (hard labels)
- Multi-class: `multi:softprob` (probability matrix) over `multi:softmax` (labels only)
- Regression: `reg:squarederror` (default), `reg:absoluteerror` (robust to outliers)
- Ranking: `rank:ndcg` or `rank:pairwise`; requires group information
- Custom: implement `gradient` and `hessian` functions for any differentiable loss

### Serialization and Deployment
- Use `save_model()` / `load_model()` with `.json` format for portability and inspection
- `.ubj` (Universal Binary JSON) is faster to load than `.json` for large models
- Avoid pickle for XGBoost models: it breaks across versions
- For ONNX export: `onnxmltools` converts XGBoost models for cross-platform inference

## Gotchas / Anti-patterns
- Do not set `n_estimators` to a fixed number without early stopping; you will either underfit or overfit
- `scale_pos_weight` for imbalanced classes should be `count(negative) / count(positive)`, not the inverse
- Column names with special characters (`[`, `]`, `<`) break XGBoost; sanitize feature names
- `eval_metric` is for monitoring only, not for optimization; the `objective` function drives training
- XGBoost's `feature_names` must match exactly at prediction time; missing or reordered columns cause silent errors in older versions
- The `verbose` parameter and `verbosity` parameter are different; `verbosity=0` silences system messages, not training output
- `DMatrix` creation is expensive; cache it if training multiple configurations on the same data

## References
- Chen & Guestrin (2016), "XGBoost: A Scalable Tree Boosting System"
- XGBoost documentation: https://xgboost.readthedocs.io/
- XGBoost GPU support guide: https://xgboost.readthedocs.io/en/latest/gpu/
- SHAP library for model interpretation: https://shap.readthedocs.io/
