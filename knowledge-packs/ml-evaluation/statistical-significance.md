# Statistical Significance for Model Comparison

## When to use
- Determining whether one model truly outperforms another vs random variation
- Publishing results or making deployment decisions based on metric differences
- Comparing models across multiple datasets or evaluation splits

## Pattern

### Paired t-Test (Parametric)
- Compares per-sample or per-fold metric differences between two models
- Assumption: differences are approximately normally distributed
- Paired design: both models evaluated on the same data splits
- Procedure:
  1. Compute metric for each model on each fold/sample: (a_1, b_1), (a_2, b_2), ...
  2. Compute differences: d_i = a_i - b_i
  3. Test H0: mean(d) = 0 using a one-sample t-test on the differences
- Report: t-statistic, p-value, degrees of freedom
- Reject H0 if p < alpha (typically 0.05)
- Limitation: assumes independence of folds, which K-fold CV violates (overlapping training sets)

### Corrected Resampled t-Test
- Addresses the non-independence problem of K-fold paired t-test
- Nadeau & Bengio (2003) correction: adjusts variance estimate for training set overlap
- Variance correction factor: (1/K + n_test / n_train) per fold
- More conservative (wider confidence intervals) but less likely to produce false positives
- Preferred over naive paired t-test for cross-validation comparisons

### Wilcoxon Signed-Rank Test (Non-Parametric)
- Distribution-free alternative when normality assumption is questionable
- Tests whether the median difference is zero
- Requires at least 6-8 paired observations for meaningful results
- Use when: few folds, heavy-tailed metric distributions, ordinal metrics

### Bootstrap Confidence Intervals
- Resample the test set predictions with replacement (1000-10000 times)
- Compute the metric on each bootstrap sample
- Derive confidence interval from the bootstrap distribution (percentile method or BCa)
- For comparing two models: bootstrap the difference in metrics
  - If the CI for the difference excludes zero, the difference is significant
- Advantages: no distributional assumptions, works for any metric, intuitive
- Recommended as the default approach for single test set evaluation

### McNemar's Test
- For paired binary classification: tests whether two models disagree in a systematic way
- Build a 2x2 contingency table of correct/incorrect predictions per sample
- Tests whether the off-diagonal counts (model A right / model B wrong vs vice versa) differ
- More powerful than comparing accuracies when sample sizes are moderate

### Multiple Comparisons Correction
- When comparing K > 2 models, p-values must be corrected for multiple testing
- **Bonferroni**: divide alpha by number of comparisons (conservative)
- **Holm-Bonferroni**: step-down procedure, less conservative than Bonferroni
- **Benjamini-Hochberg**: controls false discovery rate (FDR) instead of family-wise error rate
- Without correction: comparing 10 models pairwise (45 tests) yields ~2 false positives at alpha=0.05
- Use Friedman test + Nemenyi post-hoc as an alternative for comparing multiple classifiers across multiple datasets

### Effect Size
- Statistical significance alone is insufficient; always report effect size
- Cohen's d: standardized mean difference (small: 0.2, medium: 0.5, large: 0.8)
- Practical significance: is a 0.3% accuracy improvement worth the added complexity?
- Report confidence intervals on the metric difference, not just p-values

## Gotchas / Anti-patterns
- Using unpaired tests when paired data is available (wastes statistical power)
- Naive paired t-test on CV folds without the Nadeau-Bengio correction
- Comparing many models without multiple comparison correction
- Reporting p < 0.05 without effect size or confidence intervals
- Using statistical significance to justify deploying a trivially better but more complex model
- Running significance tests on the training set metrics
- Cherry-picking the fold split or random seed that yields the best result

## References
- Dietterich, "Approximate Statistical Tests for Comparing Supervised Classification Learning Algorithms" (1998)
- Nadeau & Bengio, "Inference for the Generalization Error" (2003)
- Demsar, "Statistical Comparisons of Classifiers over Multiple Data Sets" (2006)
- Efron & Tibshirani, "An Introduction to the Bootstrap" (1993)
