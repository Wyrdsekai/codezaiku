# Feature Engineering

## When to use
- Raw features are not directly useful for modeling (e.g., raw timestamps, free text, IDs)
- Model performance plateaus and hyperparameter tuning yields diminishing returns
- Domain knowledge suggests interactions or transformations that the model cannot learn efficiently
- Dimensionality is too high and feature selection is needed
- Transitioning from a prototype to a production model

## Pattern

### Feature creation techniques
- **Numeric transforms**: log, sqrt, Box-Cox for skewed distributions; binning for non-linear relationships
- **Date/time decomposition**: year, month, day-of-week, hour, is_weekend, days_since_event, cyclical encoding (sin/cos)
- **Interaction features**: product, ratio, or difference of two features (e.g., price_per_sqft = price / sqft)
- **Aggregation features**: group-by statistics (mean, count, std) over a categorical key (e.g., avg_spend_per_customer)
- **Window features**: rolling mean, rolling std, lag features for time-series
- **Text-derived**: TF-IDF, character n-gram counts, string length, regex-extracted patterns
- **Geospatial**: haversine distance, geohash clustering, proximity to landmarks
- **Embedding features**: use pretrained embeddings (text, image) as dense feature vectors

### Feature selection
- **Filter methods**: correlation with target (Pearson, mutual information), variance threshold
- **Wrapper methods**: recursive feature elimination (RFE), forward/backward selection
- **Embedded methods**: L1 regularization (Lasso), tree-based feature importance (Gini, permutation)
- **Stability selection**: run selection multiple times on subsamples; keep consistently selected features

### Feature importance analysis
- **Permutation importance**: model-agnostic; shuffle one feature and measure performance drop
- **SHAP values**: game-theoretic attribution; explains individual predictions and global patterns
- **Partial dependence plots**: show marginal effect of a feature on predictions
- Use importance to prune, debug, and explain — not just to rank

### Automated feature discovery
- **Featuretools / tsfresh**: libraries that generate features from relational or time-series data
- **Target encoding with cross-validation**: encode categoricals using target statistics, but use k-fold to avoid leakage
- Automated approaches generate many candidates; always follow with selection to avoid bloat

## Gotchas / Anti-patterns
- **Data leakage through features**: any feature derived from target or future data invalidates the model (e.g., using "was_returned" to predict "will_purchase")
- **Fit on full dataset**: always compute feature statistics (means, encodings) on training set only
- **Too many features, too few samples**: curse of dimensionality; more features than observations causes overfitting
- **Ignoring feature distributions**: tree models handle skew; linear models need normalization
- **One-hot encoding high cardinality**: 10,000 categories = 10,000 columns; use target encoding, hashing, or embeddings instead
- **Not documenting feature logic**: every derived feature needs a clear definition and rationale for reproducibility

## References
- "Feature Engineering for Machine Learning" (Zheng & Casari, O'Reilly)
- Featuretools: automated feature engineering for relational data
- tsfresh: automated time-series feature extraction
- SHAP documentation: https://shap.readthedocs.io
- scikit-learn feature selection: https://scikit-learn.org/stable/modules/feature_selection.html
