# Classification Metrics

## When to use
- Evaluating models that predict discrete class labels (binary or multi-class)
- Comparing classifier performance across different operating points
- Communicating model quality to stakeholders with domain-specific cost concerns

## Pattern

### Accuracy
- Fraction of correct predictions over total predictions
- Only meaningful when classes are roughly balanced
- A model predicting the majority class in a 95/5 split achieves 95% accuracy while being useless

### Precision (Positive Predictive Value)
- Of all predicted positives, how many are actually positive
- Optimize when false positives are expensive (spam filtering, fraud alerts)
- High precision = low false positive rate

### Recall (Sensitivity / True Positive Rate)
- Of all actual positives, how many did we catch
- Optimize when false negatives are expensive (disease screening, security threats)
- High recall = low false negative rate

### F1 Score
- Harmonic mean of precision and recall
- Use when you need a single number balancing both concerns
- F-beta generalizes: beta > 1 weights recall higher, beta < 1 weights precision higher
- Always report F1 alongside precision and recall, never alone

### AUC-ROC
- Area under the Receiver Operating Characteristic curve (TPR vs FPR)
- Threshold-independent: measures ranking quality, not calibration
- 0.5 = random, 1.0 = perfect separation
- Use when you need to compare models before choosing an operating threshold
- Prefer AUC-PR (precision-recall curve) for highly imbalanced datasets; AUC-ROC can be misleadingly optimistic when negatives dominate

### Confusion Matrix
- The foundational artifact: rows = actual class, columns = predicted class
- Always inspect the matrix before deriving any single metric
- For multi-class: compute per-class precision/recall, then aggregate (macro, micro, weighted)
  - Macro: unweighted mean across classes (treats rare classes equally)
  - Micro: global TP/FP/FN (equivalent to accuracy for single-label)
  - Weighted: mean weighted by class support (accounts for imbalance)

### Decision Threshold Tuning
- Default 0.5 threshold is arbitrary; tune it to your cost function
- Plot precision-recall and ROC curves, pick threshold by domain need
- Report the chosen threshold alongside metrics

## Gotchas / Anti-patterns
- Reporting accuracy on imbalanced data without class breakdown
- Using F1 without stating whether it is macro, micro, or weighted
- Comparing AUC-ROC across datasets with different class ratios
- Ignoring the confusion matrix and relying solely on aggregate numbers
- Training threshold on test data (use validation set for threshold selection)
- Averaging metrics across folds incorrectly (average the metric, not the confusion matrices, unless you want micro-averaging)

## References
- Sokolova & Lapalme, "A systematic analysis of performance measures for classification tasks" (2009)
- scikit-learn classification metrics documentation
- Google ML Crash Course: Classification section
