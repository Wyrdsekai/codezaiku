# Data Cleaning

## When to use
- Raw data has been collected from real-world sources (always assume it needs cleaning)
- Exploratory analysis reveals missing values, outliers, or inconsistencies
- Model performance is poor and data quality is suspected
- Merging datasets from multiple sources with different schemas or conventions
- Before any feature engineering or model training

## Pattern

### Missing values
- **Detect**: check null counts, sentinel values (-1, 999, "N/A", empty strings), implicit missingness
- **Diagnose**: determine mechanism — MCAR (random), MAR (depends on observed), MNAR (depends on unobserved)
- **Handle**:
  - Drop rows only if missingness is <5% and MCAR
  - Impute with median (numeric, robust to outliers) or mode (categorical)
  - Use model-based imputation (KNN, iterative) for MAR patterns
  - Add a binary "was_missing" indicator feature alongside imputed values
  - For time series: forward-fill, backward-fill, or interpolation

### Outliers
- **Detect**: IQR method (1.5x IQR beyond Q1/Q3), z-score (>3 std devs), isolation forest for multivariate
- **Diagnose**: determine if outlier is data error, rare-but-valid, or domain-relevant signal
- **Handle**:
  - Fix data entry errors (e.g., age=999 is clearly wrong)
  - Winsorize (clip to percentile bounds) for valid-but-extreme values
  - Keep and flag rare-but-meaningful outliers; let the model decide
  - Never blindly remove outliers without understanding their source

### Duplicates
- **Exact duplicates**: hash rows and remove; keep first occurrence
- **Near-duplicates**: fuzzy matching on key fields (edit distance, cosine similarity)
- **Semantic duplicates**: same entity, different representation (e.g., "NYC" vs "New York City")
- Always deduplicate before splitting into train/test to prevent data leakage

### Encoding and format errors
- Detect encoding issues: try utf-8, then latin-1, then chardet/charset-normalizer for auto-detection
- Normalize Unicode: NFC normalization, strip zero-width characters
- Fix inconsistent date formats: parse to ISO 8601 immediately on ingestion
- Standardize categorical values: lowercase, strip whitespace, map known aliases

### Data validation pipeline
1. Schema validation: expected columns, types, value ranges
2. Statistical validation: distribution checks against a reference profile
3. Referential validation: foreign key consistency, cross-field logic rules
4. Output a cleaning report: counts of issues found and actions taken per column

## Gotchas / Anti-patterns
- **Cleaning test data with training statistics**: fit imputers/scalers on train only, then transform test
- **Silent data corruption**: always log what was changed and why; never clean in-place without audit trail
- **Over-cleaning**: removing all outliers and missing data can introduce bias and shrink the dataset
- **String normalization after splitting**: do text normalization before dedup and split, not after
- **Assuming clean because recent**: new data pipelines break in new ways; validate continuously

## References
- Great Expectations: data validation framework
- pandas-profiling / ydata-profiling: automated EDA and quality reports
- Cleanlab: find label errors in datasets
- "Tidy Data" (Hadley Wickham): foundational principles for data structure
