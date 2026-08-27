# Ensemble Methods

## When to use
- A single model is not sufficient and you need to reduce variance, bias, or both
- You have multiple models that make different kinds of errors
- Competition or production settings where marginal accuracy gains matter
- Combining heterogeneous model families for robustness

## Pattern

### Bagging (Bootstrap Aggregating)
- Train multiple instances of the same model on bootstrap samples (random subsets with replacement)
- Reduces variance without increasing bias; most effective on high-variance models (trees, KNN)
- Random Forest is bagging applied to decision trees with additional feature randomization
- `n_estimators`: 100-500 is typical; more estimators smooth predictions but with diminishing returns
- `max_samples`: fraction of data per bootstrap sample; 0.7-1.0 is standard
- OOB (out-of-bag) score provides free validation estimate from the ~37% of data not sampled per tree
- Bagging a low-bias model (deep tree) gives a low-bias, low-variance ensemble
- Parallelizable: each base learner trains independently

### Boosting
- Sequential training where each model corrects errors of the previous ensemble
- Reduces bias by focusing on hard-to-predict examples
- **AdaBoost**: re-weights misclassified samples; sensitive to noise and outliers
- **Gradient Boosting**: fits new models to the gradient of the loss function; more flexible than AdaBoost
- **XGBoost / LightGBM / CatBoost**: optimized gradient boosting implementations (see dedicated cards)
- Key hyperparameters: learning rate (shrinkage), number of estimators, max depth
- Learning rate and number of estimators are inversely related: lower rate + more trees = better generalization, slower training
- Always use early stopping; fixed iteration counts risk overfitting or underfitting

### Stacking (Stacked Generalization)
- Train diverse base models (level-0), then train a meta-model (level-1) on their out-of-fold predictions
- Level-0: choose diverse models (e.g., LightGBM + logistic regression + SVM + random forest)
- Level-1 (meta-learner): typically a simple model — logistic regression or linear regression with regularization
- Generate level-0 predictions via k-fold cross-validation on training data to avoid leakage
- Multi-layer stacking: level-0 predictions feed level-1, whose predictions feed level-2. Rarely improves beyond 2 layers.
- Include original features alongside level-0 predictions as input to the meta-learner (optional but often helps)
- Stacking is the most powerful ensemble method when base models are truly diverse

### Blending
- Simplified stacking: split training data into a train portion and a blend portion
- Train level-0 models on the train portion, generate predictions on the blend portion
- Train the meta-learner on the blend portion predictions
- Faster than stacking (no cross-validation) but uses less data for both stages
- Use blending when dataset is large enough that holding out 20-30% for blending does not hurt base model training

### Voting Classifiers
- **Hard voting**: majority vote across classifiers; each model gets one vote
- **Soft voting**: average predicted probabilities; requires all models to output calibrated probabilities
- Soft voting generally outperforms hard voting because it uses more information
- Weight models by validation performance: `weights` parameter lets you upweight better models
- Effective when models have comparable accuracy but different error patterns
- Simplest ensemble method; use as a first step before trying stacking

### Practical Ensemble Design
1. Start with a strong single model (usually gradient boosting on tabular data)
2. Add diversity: different model families, different feature subsets, different hyperparameters
3. Evaluate ensemble lift: if the ensemble does not improve over the best single model on validation, keep the single model
4. For production: balance accuracy gain vs inference cost — a 0.1% accuracy lift is not worth 5x latency
5. Diversity matters more than individual model accuracy: three mediocre but diverse models often beat three strong but similar models

## Gotchas / Anti-patterns
- Stacking without cross-validation for level-0 predictions: the meta-learner overfits to the base models' training-set predictions
- Using a complex model (e.g., gradient boosting) as the meta-learner in stacking — it overfits to the small set of level-0 predictions
- Boosting on noisy data with too many iterations: it memorizes noise. Use early stopping and regularization.
- Assuming more models in a voting ensemble is always better: adding a weak, correlated model dilutes the ensemble
- Ensembling models that all use the same algorithm and hyperparameters — no diversity, no benefit
- Ignoring inference latency: a 10-model stacked ensemble may be impractical for real-time serving
- Bagging already-low-variance models (e.g., linear regression) provides negligible improvement

## References
- Breiman (1996), "Bagging Predictors"
- Freund & Schapire (1997), "A Decision-Theoretic Generalization of On-Line Learning" (AdaBoost)
- Wolpert (1992), "Stacked Generalization"
- Zhou (2012), "Ensemble Methods: Foundations and Algorithms"
