# Dimensionality Reduction

## When to use
- Feature count exceeds sample count (p >> n) and you need to reduce before modeling
- Visualization of high-dimensional data in 2D or 3D
- Removing multicollinearity before feeding into linear models
- Speeding up downstream algorithms that scale poorly with feature count
- Noise reduction by projecting onto principal axes of variation

## Pattern

### PCA (Principal Component Analysis)
- Linear method that finds orthogonal axes of maximum variance
- Always scale features (zero mean, unit variance) before PCA; otherwise high-variance features dominate
- Select number of components by cumulative explained variance: 90-95% is a common threshold
- Plot the scree plot (explained variance per component) to identify the "elbow"
- For sparse data, use TruncatedSVD (equivalent to PCA without centering, avoids densifying)
- Incremental PCA (`IncrementalPCA`) handles datasets that do not fit in memory by processing in batches
- PCA components are linear combinations of original features; inspect `components_` to understand what each axis represents

### Variance Explained Thresholds
- 80%: aggressive reduction, good for speed-sensitive applications
- 90%: balanced trade-off, most common default
- 95%: conservative, retains fine-grained signal
- 99%: minimal information loss, mostly just removes noise and linear dependencies
- In practice, set `n_components=0.95` (float) to automatically select the number of components

### t-SNE
- Nonlinear method for visualization only (2D or 3D); do not use t-SNE output as features for downstream models
- `perplexity` controls effective neighborhood size: try 5-50, default 30. Small datasets need lower perplexity.
- Run for enough iterations (1000+ is standard); early termination produces misleading layouts
- t-SNE is stochastic: different runs produce different layouts. Set `random_state` for reproducibility.
- Cluster distances in t-SNE plots are not meaningful — only local neighborhood structure is preserved
- For >10k points, use Barnes-Hut approximation (`method='barnes_hut'`, default) for O(n log n) instead of O(n^2)

### UMAP
- Nonlinear method that preserves both local and global structure better than t-SNE
- `n_neighbors` controls local vs global focus: 5-15 for local detail, 50-200 for global structure
- `min_dist` controls point spacing: 0.0-0.1 for tight clusters, 0.5-1.0 for spread-out layout
- Faster than t-SNE for large datasets and scales better to millions of points
- Unlike t-SNE, UMAP output can be used as features for downstream models (with caution)
- UMAP supports supervised and semi-supervised modes: pass labels to `fit()` to guide the embedding
- Supports incremental `transform()` for new data points, which t-SNE does not

### Feature Selection (as dimensionality reduction)
- Removes features entirely rather than projecting into new axes — preserves interpretability
- **Variance threshold**: remove features with near-zero variance (no signal)
- **Correlation filter**: remove one of each pair with |correlation| > 0.95
- **Mutual information**: keeps features with highest information about the target
- Combines well with PCA: select features first, then apply PCA to the remaining set
- See `feature-selection.md` for detailed patterns

### Choosing the Right Method
- **Linear relationships, need invertibility**: PCA
- **Visualization only**: t-SNE (small data), UMAP (any size)
- **Preprocessing for ML pipeline**: PCA or feature selection
- **Sparse data**: TruncatedSVD
- **Manifold structure matters**: UMAP
- **Need to transform new data**: PCA or UMAP (t-SNE has no `transform`)

## Gotchas / Anti-patterns
- Applying PCA to unscaled data: the first component will just capture the feature with the largest range
- Using t-SNE embeddings as input features for classifiers — t-SNE distorts distances and is not deterministic
- Interpreting t-SNE cluster sizes or between-cluster distances as meaningful — they are artifacts of the algorithm
- Running t-SNE with default perplexity on very small (<50 samples) or very large (>50k) datasets without adjusting
- PCA on one-hot encoded features is technically valid but produces components that are hard to interpret
- Forgetting to apply the same PCA transform (fit on training data) to test data — always `fit` on train, `transform` on both
- UMAP with very small `n_neighbors` can create artificial fragmentation; with very large values it collapses clusters

## References
- Jolliffe (2002), "Principal Component Analysis" (2nd edition)
- van der Maaten & Hinton (2008), "Visualizing Data using t-SNE"
- McInnes, Healy, Melville (2018), "UMAP: Uniform Manifold Approximation and Projection"
- scikit-learn decomposition module: sklearn.decomposition
