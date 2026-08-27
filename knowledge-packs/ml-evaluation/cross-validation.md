# Cross-Validation

## When to use
- Estimating model generalization performance when data is limited
- Comparing models or hyperparameter configurations reliably
- Detecting overfitting beyond a simple train/test split

## Pattern

### K-Fold Cross-Validation
- Split data into K equally-sized folds; train on K-1, evaluate on the held-out fold; rotate K times
- Standard choice: K=5 or K=10 (higher K = lower bias, higher variance, more compute)
- Report mean and standard deviation of the metric across folds
- Each data point appears in the test set exactly once

### Stratified K-Fold
- Preserves the class distribution in each fold (for classification tasks)
- Always prefer over standard K-fold when class imbalance exists
- Also applicable to regression by binning the target variable and stratifying on bins
- Default choice for classification problems

### Repeated K-Fold
- Run K-fold multiple times with different random splits
- Reduces variance of the performance estimate
- Typical: 5x2 CV or 10x10 CV (repetitions x folds)
- More reliable for model comparison; useful when making close calls between models

### Leave-One-Out (LOO)
- Special case: K = N (number of samples)
- Nearly unbiased estimate of generalization error
- Extremely expensive for large datasets
- High variance: each training set differs by only one sample
- Use only for very small datasets (N < 100) or when every sample matters

### Time-Series Split
- Standard K-fold violates temporal ordering; future data leaks into training
- Expanding window: train on [0, t], test on [t+1, t+w]; slide forward
- Sliding window: train on [t-L, t], test on [t+1, t+w]; fixed training size
- Gap period: insert a gap between train and test to prevent information leakage from lag features
- Never shuffle time-series data before splitting

### Group K-Fold
- When samples are not independent (e.g., multiple measurements per patient, per user, per device)
- Ensures all samples from a group appear in the same fold
- Prevents optimistic estimates caused by group-level leakage
- Combine with stratification when possible (StratifiedGroupKFold)

### Nested Cross-Validation
- Outer loop: model evaluation (estimates generalization)
- Inner loop: hyperparameter tuning (selects best config per outer fold)
- Prevents optimistic bias from tuning on the same data used for evaluation
- Required when reporting final performance of a tuned model
- Expensive: K_outer x K_inner training runs

### Practical Workflow
1. Hold out a final test set (10-20%) that is never touched during development
2. Use cross-validation on the remaining data for model selection and tuning
3. Report CV scores for model comparison; report test set score only once as the final estimate
4. If using nested CV, the outer loop score is the final estimate (no separate test set needed)

## Gotchas / Anti-patterns
- Performing feature selection or preprocessing before splitting (data leakage)
- Using standard K-fold on time-series or grouped data
- Tuning hyperparameters using the same CV loop that reports final performance (use nested CV)
- Reporting only mean CV score without standard deviation
- Choosing K=2 (high variance, pessimistically biased)
- Shuffling time-series data
- Applying LOO on large datasets where K=5 or K=10 would suffice with lower variance

## References
- Hastie, Tibshirani & Friedman, "Elements of Statistical Learning" Ch. 7
- Varma & Simon, "Bias in error estimation when using cross-validation for model selection" (2006)
- Bergmeir & Benitez, "On the use of cross-validation for time series predictor evaluation" (2012)
