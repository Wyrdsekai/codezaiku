# Feature Selection

## When to use
- Reducing model complexity to improve generalization and interpretability
- Removing irrelevant or redundant features that add noise
- Speeding up training and inference by reducing input dimensionality
- Understanding which features drive predictions for domain insight
- Complying with regulations that require explainable, minimal models

## Pattern

### Filter Methods
- Compute a score per feature independently, then rank and select top-k
- Fast, model-agnostic, run once before training
- **Variance threshold**: remove features with variance below a cutoff (e.g., near-constant columns)
- **Correlation filter**: compute pairwise Pearson/Spearman correlation; drop one from each pair with |r| > 0.90-0.95
- **Chi-squared test**: for categorical features vs categorical target; tests independence
- **ANOVA F-test**: for numerical features vs categorical target; tests mean differences across groups
- **Mutual information**: measures nonlinear dependence between feature and target. Works for both classification and regression. More general than correlation but slower.
- Limitations: filter methods evaluate features in isolation, missing important interactions

### Wrapper Methods
- Use model performance as the objective to evaluate feature subsets
- **Forward selection**: start with no features, greedily add the one that most improves the model, repeat
- **Backward elimination**: start with all features, greedily remove the one whose removal least hurts performance, repeat
- **Recursive Feature Elimination (RFE)**: train model, remove the least important feature(s), retrain, repeat until desired count
- RFE with cross-validation (`RFECV`) automatically finds the optimal number of features
- Wrapper methods find interaction effects that filter methods miss but are computationally expensive: O(n_features) model trainings minimum
- Use a fast base model (linear model or small tree) inside the wrapper to keep runtime manageable

### Embedded Methods
- Feature selection happens during model training as a byproduct of regularization or tree building
- **L1 regularization (Lasso)**: drives irrelevant coefficients to zero; features with non-zero coefficients are selected
- **Tree-based importance**: features that appear in splits are selected; features never used are candidates for removal
- **ElasticNet**: combines L1 sparsity with L2 stability; better than pure L1 when features are correlated
- **LightGBM/XGBoost feature importance**: `gain` importance is more reliable than `split` importance for selection
- Embedded methods balance speed (single model training) with interaction awareness (model considers all features jointly)

### Mutual Information
- Estimates how much knowing a feature's value reduces uncertainty about the target
- Non-parametric: captures nonlinear relationships that correlation misses
- `mutual_info_classif` for classification, `mutual_info_regression` for regression
- Sensitive to `n_neighbors` parameter (used in k-NN density estimation); default of 3 is usually fine
- Normalize features before computing MI for consistent scale behavior
- Compute MI for each feature vs target independently — does not capture feature interactions
- Use as a filter to shortlist features, then refine with wrapper or embedded methods

### SHAP-Based Feature Selection
- SHAP values quantify each feature's contribution to each prediction
- Mean absolute SHAP value across samples gives global feature importance
- More reliable than tree-based importance: accounts for feature interactions and is theoretically grounded
- Workflow: train model, compute SHAP values, rank features by mean |SHAP|, select top-k
- For iterative selection: remove lowest-SHAP features, retrain, recompute SHAP, repeat until validation performance drops
- SHAP importance is model-specific: a feature important for XGBoost may not be important for logistic regression
- Computationally expensive for large datasets; use `shap.sample()` or background summarization to reduce computation

### Practical Workflow
1. Remove zero/near-zero variance features (filter)
2. Remove highly correlated feature pairs (filter, keep the one with higher MI to target)
3. Compute mutual information to rank remaining features
4. Train a model with embedded selection (L1 or tree importance) on the filtered set
5. Validate with SHAP or permutation importance on a held-out set
6. Final selection: keep features that appear important across multiple methods

## Gotchas / Anti-patterns
- Performing feature selection on the full dataset before train/test split — this leaks information from the test set
- Removing features based on low correlation with target when the relationship is nonlinear — use mutual information instead
- L1 selection with correlated features: Lasso arbitrarily picks one from each correlated group, varying across runs
- Using tree-based `feature_importances_` (split count) for selection: high-cardinality features appear disproportionately important
- Removing a feature that is individually weak but participates in important interactions — wrapper methods catch this, filter methods do not
- Over-selecting: removing too many features harms performance. Always validate on held-out data after selection.
- Running SHAP on the training set instead of a validation set to determine importance — measures what the model learned, not what generalizes

## References
- Guyon & Elisseeff (2003), "An Introduction to Variable and Feature Selection"
- Lundberg & Lee (2017), "A Unified Approach to Interpreting Model Predictions" (SHAP)
- scikit-learn feature selection module: sklearn.feature_selection
- Kraskov, Stogbauer, Grassberger (2004), "Estimating Mutual Information" (k-NN MI estimator)
