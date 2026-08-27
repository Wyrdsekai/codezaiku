# Anomaly Detection

## When to use
- Labeled anomalies are rare or nonexistent (unsupervised or semi-supervised setting)
- Fraud detection, intrusion detection, manufacturing defect identification
- Data quality monitoring and outlier flagging
- The "normal" class is well-represented but anomalies are diverse and hard to enumerate

## Pattern

### Isolation Forest
- Works by randomly partitioning data; anomalies require fewer partitions to isolate
- `contamination` parameter sets the expected anomaly fraction: if unknown, start with `'auto'` or 0.01-0.05
- `n_estimators=100` is sufficient for most datasets; more trees improve stability, not accuracy
- `max_samples='auto'` subsamples 256 points per tree by default — efficient on large datasets
- Scores range from -1 (anomaly) to 1 (normal); threshold at 0 by default
- Handles high-dimensional data better than distance-based methods
- Does not require feature scaling

### One-Class SVM
- Learns a boundary around "normal" data in kernel space
- Requires feature scaling (StandardScaler) — sensitive to feature magnitudes
- RBF kernel is the default and most common choice
- `nu` parameter is an upper bound on the fraction of outliers and a lower bound on the fraction of support vectors; set it near your expected contamination rate
- `gamma` controls the RBF kernel width: `'scale'` (default) is usually adequate
- Slow on large datasets: O(n^2) to O(n^3) training; consider subsampling or SGD-based one-class SVM
- Better than Isolation Forest when the normal data lies on a clear manifold

### Statistical Methods
- **Z-score**: flag points beyond 3 standard deviations; assumes normality, sensitive to outliers in the mean/std
- **Modified Z-score**: uses median and MAD (Median Absolute Deviation); robust to existing outliers
- **IQR method**: anomalies outside [Q1 - 1.5*IQR, Q3 + 1.5*IQR]; works well for skewed distributions
- **Grubbs' test / Dixon's Q test**: formal statistical tests for single outliers; require normality assumption
- **Mahalanobis distance**: accounts for feature correlations; flag points with high distance from the centroid. Requires invertible covariance matrix (fails with p > n without regularization).

### PyOD Patterns
- PyOD provides a unified API (`.fit()`, `.predict()`, `.decision_function()`) across 40+ detectors
- Ensemble approach: run multiple detectors and aggregate scores
  - `LSCP` (Locally Selective Combination): selects best detector per local region
  - `SUOD` (Scalable Unsupervised Outlier Detection): parallelized ensemble with approximation
- Recommended starting ensemble: Isolation Forest + LOF + COPOD (fast, covers different assumptions)
- `COPOD` (Copula-Based Outlier Detection): parameter-free, fast, handles skewed data
- `ECOD` (Empirical Cumulative Distribution): also parameter-free, good for high-dimensional data
- All PyOD models expose `decision_scores_` (raw scores) and `labels_` (binary predictions)

### Choosing a Method
- **No assumptions, general purpose**: Isolation Forest
- **Known manifold structure**: One-Class SVM
- **Univariate or low-dimensional**: Statistical methods (Z-score, IQR)
- **Need an ensemble**: PyOD's LSCP or SUOD
- **High-dimensional, parameter-free**: ECOD or COPOD
- **Local density matters**: LOF (Local Outlier Factor) — detects anomalies relative to local neighborhood density
- **Streaming data**: Half-Space Trees or online Isolation Forest variants

### Evaluation Without Labels
- Silhouette-like scores: measure how well anomaly scores separate from normal scores
- Domain expert review: sample top-k anomalies and verify manually
- Stability analysis: check if the same points are flagged across different methods or random seeds
- If some labels exist: precision at k (precision of the top-k scored anomalies)

## Gotchas / Anti-patterns
- Setting `contamination` too high dilutes the anomaly signal; too low misses real anomalies. Start conservative.
- Isolation Forest can underperform on datasets where anomalies are clustered together (they are no longer easy to isolate)
- One-Class SVM trained on data that already contains anomalies will learn a boundary that includes them
- Z-score with mean/std is unreliable when the data already contains outliers — the outliers inflate std. Use robust variants.
- LOF scores are relative and not comparable across datasets; a score of 1.5 means different things in different contexts
- Anomaly detection models degrade silently as data distributions shift; retrain periodically or monitor score distributions
- Do not threshold anomaly scores by a fixed cutoff across different feature spaces or time periods — recalibrate the threshold

## References
- Liu, Ting, Zhou (2008), "Isolation Forest"
- Scholkopf et al. (2001), "Estimating the Support of a High-Dimensional Distribution" (One-Class SVM)
- Zhao et al. (2019), "PyOD: A Python Toolbox for Scalable Outlier Detection"
- Aggarwal (2017), "Outlier Analysis" (2nd edition, Springer)
