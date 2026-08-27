# Linear Models

## When to use
- The relationship between features and target is approximately linear
- You need interpretable coefficients (e.g., "feature X increases outcome by Y per unit")
- High-dimensional data where regularization is essential (p >> n)
- You need a fast, low-overhead baseline model
- Feature sparsity is expected (L1 for automatic feature selection)

## Pattern

### Linear Regression
- Ordinary Least Squares (OLS) is the default; no hyperparameters to tune
- Always check residual plots: residuals vs fitted, Q-Q plot, residuals vs each predictor
- Non-constant variance (heteroscedasticity) in residuals means coefficient standard errors are unreliable; use robust standard errors or weighted least squares
- For polynomial relationships, add polynomial features explicitly rather than switching to a nonlinear model — keeps interpretability

### Logistic Regression
- Default to L2 regularization (`penalty='l2'`) unless you need feature selection
- `C` is the inverse of regularization strength: smaller C = stronger regularization
- For multi-class: `multinomial` with `lbfgs` solver for >2 classes; `ovr` (one-vs-rest) if classes are truly independent
- Calibration: logistic regression outputs are well-calibrated by default, unlike tree models — do not apply Platt scaling on top
- For imbalanced classes: use `class_weight='balanced'` which adjusts weights inversely proportional to class frequency

### Regularization
- **L1 (Lasso)**: drives small coefficients to exactly zero — use for feature selection or sparse models
- **L2 (Ridge)**: shrinks all coefficients toward zero but never to zero — use when all features may be relevant
- **ElasticNet**: combines L1 and L2 via `l1_ratio` parameter (0 = pure Ridge, 1 = pure Lasso). Default `l1_ratio=0.5` is a reasonable start
- When to choose:
  - Known sparse signal: L1
  - Correlated features with shared signal: L2 (L1 arbitrarily picks one from a correlated group)
  - Correlated features + sparsity desired: ElasticNet with `l1_ratio` 0.1-0.5
- Tune `alpha` (or `C`) via cross-validation; use the `CV` variants (e.g., `LassoCV`, `RidgeCV`, `ElasticNetCV`) for efficient built-in search

### Preprocessing Requirements
- **Scale features** before regularization: L1 and L2 penalties treat all coefficients equally, so unscaled features with large ranges dominate
- Use StandardScaler (zero mean, unit variance) for dense data, MaxAbsScaler for sparse data
- One-hot encode categoricals; drop one level to avoid multicollinearity (the "dummy variable trap")
- For high-cardinality categoricals: target encoding or hashing is better than one-hot

### Solver Selection
- `lbfgs`: default, good for small-medium datasets, supports L2 and no penalty
- `saga`: large datasets, supports all penalties including ElasticNet, needs scaled features
- `liblinear`: good for small datasets with L1, does not support multinomial
- `sag`: fast for large datasets but only L2; `saga` is strictly more capable

## Gotchas / Anti-patterns
- Fitting linear models without scaling features first invalidates regularization — coefficients of large-scale features are penalized less
- Multicollinearity inflates coefficient variance; check VIF (Variance Inflation Factor) > 10 as a warning sign
- Do not interpret L1-selected features as "the true important features" — L1 is unstable with correlated features, randomly selecting one from each correlated group across runs
- Logistic regression with `solver='lbfgs'` and `penalty='l1'` silently falls back or errors depending on the library version
- R-squared can be misleading for prediction quality; always check RMSE or MAE on held-out data
- Adding polynomial features without regularization leads to rapid overfitting in high-dimensional spaces
- Logistic regression assumes linear decision boundaries in feature space; no amount of tuning fixes this if the boundary is nonlinear — add interaction/polynomial features or switch models

## References
- Hastie, Tibshirani, Friedman — Elements of Statistical Learning, Ch. 3-4
- Tibshirani (1996), "Regression Shrinkage and Selection via the Lasso"
- Zou & Hastie (2005), "Regularization and Variable Selection via the Elastic Net"
- scikit-learn linear models documentation: sklearn.linear_model module
