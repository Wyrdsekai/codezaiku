# Support Vector Machines

## When to use
- Small to medium datasets (<50k samples) with clear margin of separation
- High-dimensional data where the number of features exceeds the number of samples (text classification, genomics)
- You need a non-probabilistic classifier with strong theoretical guarantees
- Binary classification with nonlinear decision boundaries (via kernel trick)
- The problem benefits from maximizing margin rather than minimizing empirical error

## Pattern

### Kernel Selection
- **Linear kernel**: use when features >> samples, or data is linearly separable. Fastest. Equivalent to LinearSVC which is more efficient.
- **RBF (Radial Basis Function)**: default and most versatile. Good when you have no prior knowledge about the decision boundary shape.
- **Polynomial kernel**: use when interaction terms between features matter. `degree=2` or `3`; higher degrees overfit quickly.
- **Sigmoid kernel**: rarely used; can behave like a neural network but is often outperformed by RBF
- Decision guide:
  1. Try linear first (fast, interpretable)
  2. If linear underfits, try RBF
  3. If you know polynomial interactions matter, try polynomial
  4. Almost never use sigmoid in practice

### Scaling Requirements
- SVMs are sensitive to feature scale: features with large ranges dominate the distance computation
- Always standardize (zero mean, unit variance) or normalize (min-max to [0,1]) before training
- For sparse data (e.g., TF-IDF text features): use MaxAbsScaler to preserve sparsity
- The scaler must be fit on training data only and applied to both train and test

### Hyperparameter Tuning
- **C (regularization)**: controls the tradeoff between margin width and classification errors
  - High C: narrow margin, fewer misclassifications on training data, risk overfitting
  - Low C: wide margin, more misclassifications allowed, better generalization
  - Search range: logarithmic scale, e.g., `[0.001, 0.01, 0.1, 1, 10, 100, 1000]`
- **gamma (RBF and polynomial)**: controls the influence radius of each support vector
  - High gamma: each point influences only nearby points, complex boundary, risk overfitting
  - Low gamma: each point influences far-away points, smooth boundary, risk underfitting
  - `gamma='scale'` (default: `1 / (n_features * X.var())`) is a good starting point
  - Search range: `[0.001, 0.01, 0.1, 1, 10]`
- **degree (polynomial)**: usually 2 or 3. Higher degrees are computationally expensive and overfit
- Tune C and gamma jointly with grid search or randomized search; they interact strongly

### Large-Scale SVM
- Standard SVM (SVC with RBF kernel) is O(n^2) to O(n^3) in training time and memory
- **LinearSVC**: uses liblinear, O(n) training for linear kernels, handles millions of samples
- **SGDClassifier with hinge loss**: stochastic gradient descent SVM, scales to very large datasets, online learning capable
- **Nystroem approximation**: approximate the kernel matrix with a low-rank representation, then use LinearSVC. Brings kernel SVM to large-scale.
- **Kernel approximation**: `RBFSampler` or `AdditiveChi2Sampler` generate explicit feature maps that approximate the kernel, enabling linear methods on nonlinear problems
- For >100k samples: use LinearSVC or SGDClassifier. For >10k with nonlinear boundaries: Nystroem + LinearSVC.

### Probability Estimates
- SVM does not naturally output probabilities; `probability=True` enables Platt scaling (sigmoid calibration)
- Platt scaling adds a cross-validation step during `fit()`, significantly slowing training
- Probabilities from Platt scaling are approximate and can be poorly calibrated, especially near the decision boundary
- If you need calibrated probabilities: train SVM without `probability=True`, then calibrate separately with `CalibratedClassifierCV`

### Multi-Class Strategies
- `SVC` uses one-vs-one (OvO) by default: trains `n_classes * (n_classes - 1) / 2` classifiers
- `LinearSVC` uses one-vs-rest (OvR) by default: trains `n_classes` classifiers
- OvO is better for smaller datasets (each classifier sees a subset of data)
- OvR is faster for many classes and works well when classes are well-separated
- For many classes (>10): consider a different model family; SVMs scale poorly with class count

## Gotchas / Anti-patterns
- Training SVM on unscaled features: the model effectively ignores low-magnitude features
- Using RBF kernel on >50k samples without approximation: training time becomes impractical
- Setting C very high on noisy data: the model tries to correctly classify every training point, including noise
- `probability=True` with `SVC` makes `fit()` 2-3x slower due to internal cross-validation for Platt scaling
- Interpreting SVM coefficients from `coef_` is only meaningful for linear kernels; nonlinear kernels operate in transformed space
- SVM does not handle missing values; impute before training
- `LinearSVC` and `SVC(kernel='linear')` are not identical: different solvers, different default parameters, different handling of multi-class

## References
- Cortes & Vapnik (1995), "Support-Vector Networks"
- Scholkopf & Smola (2002), "Learning with Kernels"
- Chang & Lin (2011), "LIBSVM: A Library for Support Vector Machines"
- scikit-learn SVM documentation: sklearn.svm module
