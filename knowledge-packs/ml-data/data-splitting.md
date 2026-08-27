# Data Splitting

## When to use
- Every supervised ML project requires proper train/validation/test splits
- Evaluating model generalization and tuning hyperparameters
- Data has temporal ordering, group structure, or class imbalance that affects split strategy
- Comparing multiple models or configurations fairly

## Pattern

### Standard splits
- **Train / Validation / Test**: typical ratios 70/15/15 or 80/10/10
- **Train / Test only**: acceptable when using cross-validation for hyperparameter tuning
- **Hold-out test set**: sacred; never use for tuning, never peek until final evaluation
- Shuffle before splitting (unless temporal data)

### Cross-validation
- **K-Fold** (k=5 or k=10): each fold serves as validation once; average metrics across folds
- **Stratified K-Fold**: preserves class distribution in each fold (essential for imbalanced data)
- **Repeated K-Fold**: run k-fold multiple times with different random seeds for tighter confidence intervals
- **Leave-One-Out**: k=N; useful for tiny datasets, computationally expensive

### Time-series splitting
- **Never shuffle** time-series data; temporal ordering must be preserved
- **Expanding window**: train on all data up to time t, validate on t+1 to t+n
- **Sliding window**: fixed-size training window moves forward through time
- **Embargo/gap**: leave a gap between train and validation to prevent lookahead leakage from lagged features
- **Purging**: remove training samples that overlap temporally with validation targets

### Group-aware splitting
- When samples are grouped (e.g., multiple images per patient, multiple transactions per user)
- **GroupKFold**: ensures all samples from one group stay in the same split
- Prevents data leakage where the model memorizes group-level patterns
- Combine with stratification if groups are imbalanced across classes

### Stratification
- Preserve target class proportions in every split
- For regression: bin the target into quantiles and stratify on bins
- For multi-label: use iterative stratification (each label combination as a pseudo-class)

### Practical workflow
1. Set aside test set first (before any exploration or feature engineering)
2. Use train set for all EDA, feature engineering, and hyperparameter tuning
3. Use validation set (or cross-validation) to select the best model
4. Evaluate on test set exactly once for the final reported metric
5. Record split random seed and strategy in experiment metadata

## Gotchas / Anti-patterns
- **Data leakage across splits**: preprocessing (scaling, imputation, encoding) must be fit on training data only
- **Splitting after augmentation**: augmented samples of the same original must stay in the same split
- **Small dataset, single split**: high variance in evaluation; use cross-validation instead
- **Shuffling time-series**: destroys temporal dependencies and creates future-to-past leakage
- **Peeking at the test set**: using test set metrics to make decisions (even informally) invalidates the evaluation
- **Ignoring group structure**: if a patient appears in both train and test, the model may memorize patient-level features

## References
- scikit-learn splitters: https://scikit-learn.org/stable/modules/cross_validation.html
- "Time Series Split" patterns in scikit-learn
- Iterative stratification: "On the Stratification of Multi-Label Data" (Sechidis et al.)
- Kaggle best practices for competition data splitting
