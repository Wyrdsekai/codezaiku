# Nearest Neighbors

## When to use
- Non-parametric classification or regression where decision boundaries are complex and irregular
- Recommendation systems based on item or user similarity
- Anomaly detection via distance to k-th nearest neighbor
- Baseline model that requires no training phase
- The dataset is small to medium (<100k samples) or you can use approximate methods

## Pattern

### KNN for Classification
- Predict the majority class among the k nearest neighbors of a query point
- `k` selection: start with `k=5`, tune via cross-validation. Odd values avoid ties in binary classification.
- Small k: complex boundary, sensitive to noise. Large k: smoother boundary, biased toward majority class.
- Rule of thumb: `k = sqrt(n_samples)` as an upper bound, then search downward
- Weight by distance (`weights='distance'`) so closer neighbors have more influence; usually improves over uniform weights
- For imbalanced classes: distance weighting helps, but also consider oversampling or class-weighted voting

### KNN for Regression
- Predict the mean (or weighted mean) of the k nearest neighbors' target values
- Distance-weighted averaging (`weights='distance'`) reduces sensitivity to k choice
- KNN regression cannot extrapolate: predictions are bounded by the training target range
- For smooth functions: larger k produces smoother predictions; for noisy data: larger k reduces noise

### Distance Metrics
- **Euclidean (L2)**: default, assumes all features are on the same scale. Scale features first.
- **Manhattan (L1)**: more robust to outliers in individual features; better for sparse data
- **Minkowski (Lp)**: generalizes L1 and L2 via `p` parameter. `p=1` = Manhattan, `p=2` = Euclidean.
- **Cosine distance**: measures angle between vectors, ignoring magnitude. Best for text (TF-IDF), embeddings.
- **Hamming distance**: for binary or categorical features (counts the proportion of differing attributes)
- **Mahalanobis distance**: accounts for feature correlations; requires covariance matrix estimation
- When features have different types (numerical + categorical): use Gower distance or encode categoricals numerically
- Curse of dimensionality: in high dimensions (>50 features), distances become nearly uniform and KNN degrades. Reduce dimensionality first.

### Scaling and Preprocessing
- Feature scaling is mandatory for distance-based methods: unscaled features with large ranges dominate
- StandardScaler (z-score) for normally distributed features
- MinMaxScaler for bounded features
- For sparse data: MaxAbsScaler preserves sparsity
- Missing values: impute before computing distances; KNN-based imputation (`KNNImputer`) is itself a valid strategy

### Approximate Nearest Neighbors (ANN)
- Exact KNN is O(n*d) per query; impractical for large datasets
- **Annoy** (Spotify): builds a forest of random projection trees. Fast queries, memory-mapped files. Good for static datasets.
- **FAISS** (Meta): GPU-accelerated, supports IVF (inverted file) and HNSW indexing. Best for million-scale datasets.
- **ScaNN** (Google): quantization-aware search with anisotropic quantization. High recall at high speed.
- **HNSW (Hierarchical Navigable Small World)**: graph-based, excellent recall/speed tradeoff. Available in FAISS, hnswlib, and many vector databases.
- **Ball tree / KD tree**: exact methods with O(d * log n) average query time. KD tree degrades in high dimensions (>20); ball tree handles higher dimensions better.
- ANN recall tradeoff: accept 95-99% recall for 10-100x speed improvement. Always measure recall on a sample.

### Practical Considerations
- KNN has no training phase (lazy learner): all computation happens at prediction time
- Store the training data efficiently: KD-tree or ball-tree index for exact queries under 20 dimensions
- Memory: KNN stores the entire training set; compress features or use approximate methods for large datasets
- For real-time serving: precompute approximate nearest neighbor indices offline, query online

## Gotchas / Anti-patterns
- Using KNN without scaling features: the feature with the largest range dominates all distance computations
- High-dimensional data (>50 features) without dimensionality reduction: distances become meaningless (curse of dimensionality)
- Choosing k=1: maximally sensitive to noise; a single mislabeled neighbor produces wrong predictions
- KD-tree with high-dimensional data: degrades to brute force above ~20 dimensions. Use ball tree or ANN instead.
- Using Euclidean distance on categorical features encoded as integers: the numeric distance has no meaningful interpretation
- KNN regression for time series without respecting temporal order: future data leaks into predictions if neighbors come from the future
- Forgetting that KNN predictions are bounded by the training set range: it cannot predict values higher or lower than any training target

## References
- Cover & Hart (1967), "Nearest Neighbor Pattern Classification"
- Indyk & Motwani (1998), "Approximate Nearest Neighbors: Towards Removing the Curse of Dimensionality"
- Johnson, Douze, Jegou (2017), "Billion-scale Similarity Search with GPUs" (FAISS)
- scikit-learn neighbors module: sklearn.neighbors
