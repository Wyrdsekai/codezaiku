# LightGBM Patterns

## When to use
- Large tabular datasets (>100k rows) where training speed matters
- Datasets with high-cardinality categorical features
- You need histogram-based splitting for memory efficiency
- Distributed training across multiple machines
- You want leaf-wise growth for deeper, more accurate trees with fewer splits

## Pattern

### Histogram-Based Splitting
- LightGBM bins continuous features into 255 discrete bins by default (`max_bin=255`)
- Increasing `max_bin` improves accuracy at the cost of speed and memory; 255 is optimal for most cases
- Histogram subtraction: LightGBM computes one child's histogram and derives the other by subtraction, halving computation
- For very large datasets (>10M rows), GOSS (Gradient-based One-Side Sampling) keeps high-gradient instances and samples low-gradient ones, trading negligible accuracy for significant speedup

### Leaf-Wise Growth
- LightGBM grows leaf-wise (best-first) instead of level-wise (breadth-first)
- This produces deeper, asymmetric trees that capture complex patterns with fewer total leaves
- Control complexity with `num_leaves` (default 31), not `max_depth`
- Rule of thumb: `num_leaves` should be less than `2^max_depth` to avoid overfitting
- Start with `num_leaves=31`, increase to 63-127 for complex data, decrease to 15-20 for noisy data

### Native Categorical Handling
- Pass categorical columns via `categorical_feature` parameter instead of one-hot encoding
- LightGBM partitions categorical values into two groups optimally at each split
- For high-cardinality features (>50 categories), native handling dramatically outperforms one-hot
- Set `cat_smooth` (default 10) higher for noisy categoricals, lower for clean ones
- `min_data_per_group=100` prevents rare categories from forming their own split

### Large Dataset Strategies
- **Data parallelism**: split data across workers, each builds local histograms, merge via reduce
- **Feature parallelism**: split features across workers; useful when features >> rows
- **Voting parallelism**: for very large data, workers vote on top-k splits instead of communicating full histograms
- Use `bin_construct_sample_cnt=200000` to subsample for histogram bin construction on huge datasets
- `feature_pre_filter=true` (default) skips features that cannot produce a valid split, saving time

### Tuning Priority
1. `num_leaves` and `max_depth` — tree complexity
2. `learning_rate` with `n_estimators` via early stopping
3. `min_child_samples` (min data in a leaf) — start at 20, increase for noisy data
4. `subsample` (bagging_fraction) and `colsample_bytree` (feature_fraction) — 0.7-0.9
5. `reg_alpha` (L1) and `reg_lambda` (L2) — regularization
6. `min_split_gain` — minimum gain to make a split; 0.0 default, increase to prune

### Speed Optimization
- `feature_fraction_bynode` samples features per split, not per tree — more randomness, faster
- `max_cat_threshold=32` limits categorical split search; lower for speed on very high-cardinality
- `enable_bundle=true` (default) bundles mutually exclusive features (EFB), reducing effective feature count
- For prediction: `predict_disable_shape_check=true` skips shape validation for faster batch inference

## Gotchas / Anti-patterns
- Do not set `num_leaves` equal to `2^max_depth`; you lose the advantage of leaf-wise growth and overfit
- Label encoding categoricals with integers before passing as `categorical_feature` can confuse the model if integers imply ordinality — use native support or explicit declaration
- LightGBM categorical support works only with the native API or properly configured sklearn wrapper; some pipeline tools silently convert to float
- `is_unbalance=true` and `scale_pos_weight` are mutually exclusive; using both produces unexpected behavior
- LightGBM parameter names have aliases (`num_iterations` = `n_estimators` = `num_boost_round`); mixing aliases in config files causes silent overwrites
- Thread contention: `num_threads` should match physical cores, not hyperthreads; over-subscribing hurts performance
- LightGBM models saved with `save_model()` are text-based and human-readable but version-sensitive; always record the LightGBM version

## References
- Ke et al. (2017), "LightGBM: A Highly Efficient Gradient Boosting Decision Tree"
- LightGBM documentation: https://lightgbm.readthedocs.io/
- LightGBM parameter tuning guide: https://lightgbm.readthedocs.io/en/latest/Parameters-Tuning.html
- Meng et al. (2016), "A Communication-Efficient Parallel Algorithm for Decision Tree" (GOSS/EFB theory)
