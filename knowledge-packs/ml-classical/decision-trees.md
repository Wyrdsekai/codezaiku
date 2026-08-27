# Decision Trees

## When to use
- You need an interpretable model that non-technical stakeholders can understand
- The dataset has mixed feature types (numerical + categorical) without heavy preprocessing
- Feature interactions matter and you want the model to discover them automatically
- You need a baseline before trying more complex approaches
- The problem has axis-aligned decision boundaries

## Pattern

### Single Decision Tree
- Start with `max_depth=None` to see how deep the tree grows, then prune
- Use cost-complexity pruning (CCP) over pre-pruning: grow full tree, then find optimal alpha via cross-validation on the pruning path
- For classification, Gini impurity and entropy produce nearly identical trees in practice; Gini is marginally faster
- For regression, MSE splitting is standard; MAE splitting is more robust to outliers but slower

### Random Forests
- Default to 100-500 trees; diminishing returns beyond that for most datasets
- Set `max_features=sqrt(n_features)` for classification, `max_features=n_features/3` for regression as starting points
- Out-of-bag (OOB) score is a free validation estimate; use it to skip a CV fold during early exploration
- Feature importance from RF is biased toward high-cardinality features; use permutation importance instead for trustworthy rankings

### Gradient Boosting Selection
- **Small-medium tabular data (<100k rows)**: scikit-learn's GradientBoosting or XGBoost with `tree_method=exact`
- **Medium-large tabular data (100k-10M rows)**: XGBoost or LightGBM with histogram-based splitting
- **Very large data (>10M rows)**: LightGBM preferred for speed; CatBoost if heavy categorical features
- **Ranking tasks**: XGBoost or LightGBM have native ranking objectives (lambdarank, ndcg)

### Hyperparameter Tuning Priority
1. Number of trees / iterations (with early stopping — always use early stopping)
2. Learning rate (lower = more trees needed, but better generalization)
3. Max depth / num_leaves
4. Min samples per leaf / min_child_weight
5. Subsample ratio and column sampling

## Gotchas / Anti-patterns
- Single decision trees overfit aggressively on noisy data; always validate with pruning or ensembles
- Feature importance from `feature_importances_` in tree models measures impurity reduction, not predictive value — it inflates importance of continuous and high-cardinality features
- Trees are unstable: small data changes produce very different splits. Ensembles fix this.
- Do not use single decision trees for extrapolation; they predict constants outside training range
- One-hot encoding high-cardinality categoricals before tree fitting wastes splits; use native categorical support (LightGBM, CatBoost) or target encoding instead
- Random forests are embarrassingly parallel at training but consume O(n_trees) memory at inference
- Gradient boosting is sequential at training; parallelism is within each tree, not across trees

## References
- Breiman (2001), "Random Forests" — original RF paper
- Friedman (2001), "Greedy Function Approximation: A Gradient Boosting Machine"
- scikit-learn Decision Trees documentation: sklearn.tree module
- Elements of Statistical Learning, Ch. 9-10 (Hastie, Tibshirani, Friedman)
