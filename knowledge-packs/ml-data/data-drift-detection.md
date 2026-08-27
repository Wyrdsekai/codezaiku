# Data Drift Detection

## When to use
- Model is deployed in production and predictions may degrade over time
- Input data distribution changes due to seasonality, user behavior shifts, or upstream pipeline changes
- Regulatory requirements mandate monitoring for model fairness and reliability
- Retraining decisions need to be data-driven, not calendar-based
- Debugging unexpected model performance drops

## Pattern

### Types of drift
- **Data drift (covariate shift)**: input feature distributions change, but the relationship between features and target remains the same
- **Concept drift**: the relationship between features and target changes (e.g., "spam" definition evolves)
- **Label drift**: target variable distribution shifts (e.g., fraud rate increases)
- **Upstream drift**: data source schema, format, or quality changes (silent pipeline failures)

### Statistical tests for drift detection

#### Univariate (per-feature)
- **Kolmogorov-Smirnov (KS) test**: non-parametric; compares two continuous distributions; good default
- **Chi-squared test**: for categorical features; compares observed vs expected frequencies
- **Population Stability Index (PSI)**: bins feature values and measures divergence; widely used in finance
  - PSI < 0.1: no significant shift; 0.1-0.2: moderate; > 0.2: significant
- **Wasserstein distance (Earth Mover's Distance)**: measures how much "work" to transform one distribution into another; more sensitive than KS for subtle shifts

#### Multivariate
- **Maximum Mean Discrepancy (MMD)**: kernel-based test; captures joint distribution shifts missed by univariate tests
- **Domain classifier**: train a model to distinguish reference from production data; high accuracy = significant drift
- **PCA / autoencoder reconstruction error**: project to lower dimensions; increased reconstruction error signals distributional change

### Monitoring architecture
1. **Reference window**: a baseline dataset (typically a slice of training data or recent validated production data)
2. **Analysis window**: recent production data (sliding window: last N hours/days)
3. **Comparison**: run statistical tests between reference and analysis windows periodically
4. **Alerting**: threshold-based alerts (e.g., PSI > 0.2 triggers investigation)
5. **Dashboard**: visualize feature distributions over time, drift scores, and model performance correlation

### Concept drift detection
- Monitor model performance metrics directly (accuracy, precision, recall) when ground truth is available
- **Delayed ground truth**: use proxy metrics or partial labels while waiting for true labels
- **ADWIN** (Adaptive Windowing): detects change points in a streaming metric by adjusting window size
- **Page-Hinkley test**: sequential test for detecting mean shifts in a stream
- Compare predictions distribution over time even without ground truth (prediction drift as a proxy)

### Response strategies
- **Retrain on recent data**: most common response; use a sliding window of recent data
- **Retrain on all data**: when drift is gradual and historical patterns still hold
- **Alert and investigate**: not all drift requires retraining; some is expected (seasonality)
- **Fallback model**: maintain a simpler, more robust model for when the primary model degrades
- **Feature-level response**: if one feature drifts due to upstream bug, fix the pipeline rather than retrain

## Gotchas / Anti-patterns
- **Monitoring only model outputs, not inputs**: input drift is detectable before performance drops; do both
- **Too-sensitive thresholds**: alert fatigue from minor, benign distribution shifts; tune thresholds empirically
- **Single-feature monitoring only**: multivariate drift can occur even when individual features look stable
- **No reference baseline**: drift is relative; you need a clear, versioned reference to compare against
- **Calendar-based retraining instead of drift-triggered**: wastes resources when data is stable; misses sudden shifts between scheduled retrains
- **Ignoring upstream schema changes**: a renamed column or changed unit can look like catastrophic drift

## References
- Evidently AI: open-source ML monitoring (https://evidentlyai.com)
- NannyML: monitoring without ground truth (https://nannyml.com)
- "Failing Loudly: An Empirical Study of Methods for Detecting Dataset Shift" (Rabanser et al.)
- "A Survey on Concept Drift Adaptation" (Gama et al.)
- WhyLabs / whylogs: lightweight data profiling and monitoring
