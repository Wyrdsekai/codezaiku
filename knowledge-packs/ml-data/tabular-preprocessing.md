# Tabular Preprocessing

## When to use
- Working with structured/relational data (CSV, database tables, spreadsheets)
- Preparing features for tree-based models (XGBoost, LightGBM, Random Forest) or linear/neural models
- Dataset contains a mix of numeric and categorical columns
- Categorical features have high cardinality (thousands of unique values)
- Building a preprocessing pipeline that works consistently in training and inference

## Pattern

### Encoding categorical features

#### Low cardinality (<20 categories)
- **One-hot encoding**: creates binary columns per category; sparse but interpretable
- **Ordinal encoding**: map to integers; only when categories have natural order (e.g., low/medium/high)
- Tree models handle ordinal encoding natively; linear models need one-hot or similar

#### Medium cardinality (20-100)
- **Target encoding**: replace category with mean of the target for that category
  - Must use k-fold cross-validation to avoid leakage: encode each fold using statistics from the other folds
  - Add smoothing to handle rare categories: `encoded = (count * mean_cat + prior_weight * global_mean) / (count + prior_weight)`
- **Leave-one-out encoding**: similar to target encoding but excludes the current row
- **Binary encoding**: convert ordinal integer to binary representation; fewer columns than one-hot

#### High cardinality (>100 categories)
- **Hashing trick**: hash category to a fixed number of buckets; collisions are acceptable for high cardinality
- **Embedding layers**: for neural networks; learn dense representations during training
- **Frequency encoding**: replace category with its occurrence count; captures popularity signal
- **Target encoding with regularization**: smoothing becomes critical; rare categories need strong prior pull

### Scaling numeric features
- **StandardScaler** (z-score): subtract mean, divide by std; assumes roughly Gaussian distribution
- **MinMaxScaler**: scale to [0,1] range; sensitive to outliers
- **RobustScaler**: uses median and IQR instead of mean/std; resilient to outliers
- **Log transform**: for right-skewed features (income, prices, counts); apply log1p for zero-safe transform
- **Quantile transform**: maps to uniform or Gaussian distribution; non-linear but powerful for heavily skewed data
- **Tree models typically need no scaling**: they split on thresholds, not magnitudes
- **Neural networks and linear models require scaling**: gradient descent is sensitive to feature magnitudes

### Handling high cardinality
- Combine rare categories into an "OTHER" bucket (categories with <1% frequency)
- Group semantically similar categories (e.g., merge "Toyota Camry 2019" and "Toyota Camry 2020" into "Toyota Camry")
- Use embedding approaches for truly high-cardinality IDs (user IDs, product IDs)
- Monitor new categories in production; define a fallback encoding for unseen values

### Handling mixed types
- Separate numeric and categorical columns early in the pipeline
- Apply type-specific transforms in parallel, then concatenate
- Document the expected type of each column in a schema file

### Pipeline construction
1. Define column groups: numeric, categorical-low, categorical-high, passthrough
2. Fit all transformers on training data only
3. Serialize the fitted pipeline (not just the model) for inference
4. Include input validation: check for expected columns, types, and value ranges
5. Log transform statistics (means, category mappings) for debugging

### Null handling for tabular data
- Numeric: impute with median (robust) or train a model-based imputer; add "is_null" indicator
- Categorical: treat null as its own category ("MISSING") or impute with mode
- Tree-based models (XGBoost, LightGBM) handle nulls natively; you may skip imputation for these
- Document the null strategy per column

## Gotchas / Anti-patterns
- **Fitting transformers on full dataset**: scaling stats, encoding maps, and imputation values must come from training set only
- **One-hot encoding high cardinality**: 10,000 categories = 10,000 sparse columns; use target encoding or hashing instead
- **Target encoding without cross-validation**: creates massive leakage; the model memorizes the target through the encoded feature
- **Scaling after one-hot encoding**: binary indicators (0/1) should not be z-scored; scale only the numeric columns
- **Ignoring new categories at inference**: a category unseen during training crashes the pipeline; define a default encoding for unknowns
- **Applying same preprocessing to train and test independently**: use `.fit()` on train, `.transform()` on both; never fit on test

## References
- scikit-learn preprocessing: https://scikit-learn.org/stable/modules/preprocessing.html
- category_encoders library: target, binary, hashing, and other encoders
- "Feature Engineering and Selection" (Kuhn & Johnson)
- XGBoost missing value handling: https://xgboost.readthedocs.io
