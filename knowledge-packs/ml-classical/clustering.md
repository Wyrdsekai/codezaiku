# Clustering

## When to use
- Discovering natural groupings in unlabeled data
- Customer segmentation, document grouping, anomaly detection via cluster membership
- Data exploration before supervised modeling
- Reducing a large dataset to representative prototypes

## Pattern

### K-Means
- Best for spherical, equally-sized clusters with roughly equal variance
- Initialize with `k-means++` (default in most libraries) to avoid poor convergence
- Run multiple initializations (`n_init=10`) and keep the best (lowest inertia)
- Scale features before K-Means: it uses Euclidean distance, so unscaled features dominate
- For very large datasets: Mini-Batch K-Means trades ~1% accuracy for significant speed gains
- K-Means always converges but to a local optimum; global optimality is not guaranteed

### DBSCAN
- Best for arbitrary-shaped clusters with noise/outlier points
- Two parameters: `eps` (neighborhood radius) and `min_samples` (minimum points to form a cluster)
- Tuning `eps`: plot k-distance graph (distance to k-th nearest neighbor, sorted), look for the elbow. k = `min_samples`
- `min_samples` rule of thumb: `2 * n_features` as a starting point, increase for noisy data
- DBSCAN does not require specifying the number of clusters — it discovers them
- Points not assigned to any cluster are labeled as noise (-1)
- Struggles with clusters of very different densities; consider HDBSCAN instead

### Hierarchical Clustering
- Agglomerative (bottom-up) is standard: each point starts as its own cluster, merge closest pairs
- Linkage methods:
  - `ward`: minimizes variance, produces compact clusters, requires Euclidean distance
  - `complete`: uses maximum pairwise distance, tends toward spherical clusters
  - `average`: uses mean pairwise distance, compromise between single and complete
  - `single`: uses minimum pairwise distance, finds elongated/chain clusters but is noise-sensitive
- Use dendrograms to visually select the number of clusters (cut at the largest vertical gap)
- Scales as O(n^2) memory and O(n^3) time for naive implementations; not suitable for >10k points without approximation

### Choosing K
- **Elbow method**: plot inertia vs K, look for diminishing returns. Subjective and often ambiguous.
- **Silhouette analysis**: silhouette coefficient ranges from -1 to 1. Values >0.5 indicate good separation, <0.25 indicates overlapping clusters. Plot per-cluster silhouette to check for uneven quality.
- **Gap statistic**: compares within-cluster dispersion to that expected under a null reference distribution. More principled than the elbow method but computationally expensive.
- **Calinski-Harabasz index**: ratio of between-cluster to within-cluster variance. Higher is better. Fast to compute.
- **Domain knowledge** always trumps metrics: if the business needs 5 segments, evaluate K=4,5,6 and pick the most interpretable.

### Silhouette Analysis
- Compute per-sample silhouette coefficients: `s(i) = (b(i) - a(i)) / max(a(i), b(i))` where `a` is mean intra-cluster distance, `b` is mean nearest-cluster distance
- Plot silhouette values sorted by cluster and colored by cluster assignment
- Warning signs: negative silhouette values (points closer to another cluster), wide fluctuations within a cluster, very thin clusters
- Average silhouette width across all samples summarizes overall clustering quality

### Preprocessing
- Always scale features: most clustering algorithms use distance metrics sensitive to scale
- Reduce dimensionality before clustering if n_features > 50; PCA or UMAP preserves structure
- Handle outliers before K-Means (they distort centroids); DBSCAN handles them natively

## Gotchas / Anti-patterns
- K-Means forces every point into a cluster, even outliers — use DBSCAN or soft clustering if noise exists
- Silhouette score can be misleading with non-convex clusters; visual inspection is essential
- Clustering results are sensitive to feature scaling and selection; different features produce different clusters
- High-dimensional data causes distance metrics to lose discriminative power (curse of dimensionality); reduce dimensions first
- Do not evaluate clustering with external labels unless the task is specifically about recovering known groups
- K-Means with K=1 always has inertia > 0; the elbow method requires K >= 2
- Hierarchical clustering dendrograms become unreadable above ~1000 points; subsample for visualization

## References
- Kaufman & Rousseeuw (1990), "Finding Groups in Data: An Introduction to Cluster Analysis"
- Ester et al. (1996), "A Density-Based Algorithm for Discovering Clusters" (DBSCAN)
- scikit-learn clustering documentation: sklearn.cluster module
- Tibshirani, Walther, Hastie (2001), "Estimating the Number of Clusters" (Gap Statistic)
