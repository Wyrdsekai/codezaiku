# Class Imbalance

## When to use
- Target class distribution is skewed (e.g., 95% negative, 5% positive)
- Model predicts the majority class almost exclusively
- Minority class is the class of interest (fraud, disease, defect)
- Evaluation metrics like accuracy are misleading due to class proportions
- Any classification task where false negatives have high cost

## Pattern

### Resampling techniques
- **Random oversampling**: duplicate minority samples; simple but risks overfitting to exact copies
- **SMOTE** (Synthetic Minority Oversampling Technique): interpolate between minority neighbors in feature space
  - Variants: Borderline-SMOTE (only oversample near decision boundary), ADASYN (adaptive density)
  - Works well for tabular data; less meaningful for raw text or images
- **Random undersampling**: remove majority samples; fast but discards potentially useful data
- **Tomek links / Edited Nearest Neighbors**: clean overlap between classes after oversampling
- **Hybrid**: combine SMOTE with undersampling (e.g., SMOTE + Tomek links)

### Algorithm-level techniques
- **Class weights**: multiply loss by inverse class frequency; supported by most frameworks
  - `weight_i = total_samples / (n_classes * count_i)` as a starting formula
  - Tune weights as a hyperparameter rather than using raw inverse frequency
- **Focal loss**: down-weights easy (well-classified) examples, focuses on hard examples
  - Controlled by gamma parameter; gamma=0 reduces to standard cross-entropy
  - Originally designed for object detection (RetinaNet), broadly applicable
- **Cost-sensitive learning**: assign different misclassification costs per class pair

### Evaluation strategy
- **Do not use accuracy** as primary metric for imbalanced data
- Use: precision, recall, F1 (per-class and macro/weighted), AUROC, AUPRC
- **AUPRC** (area under precision-recall curve) is most informative for severe imbalance
- Report confusion matrix; inspect false positive and false negative rates explicitly
- Use stratified splits to maintain class proportions (see data-splitting.md)

### Threshold tuning
- Default 0.5 threshold is rarely optimal for imbalanced problems
- Use precision-recall curve to select threshold based on business requirements
- Consider cost-based threshold: set threshold where expected cost is minimized

### Data-level strategies
- Collect more minority-class data if feasible (targeted collection campaigns)
- Data augmentation for minority class (see data-augmentation.md)
- Synthetic data generation (see synthetic-data.md)

### Ensemble approaches
- **Balanced bagging**: each base learner trains on a balanced bootstrap sample
- **EasyEnsemble**: multiple undersampled subsets, one learner per subset, average predictions
- Gradient boosting with sample weights adjusts naturally across iterations

## Gotchas / Anti-patterns
- **Resampling before splitting**: apply resampling only to training set, never to validation/test
- **SMOTE on test data**: evaluation must reflect real-world distribution; never resample evaluation sets
- **Oversampling then cross-validating**: resample inside each fold, not before splitting folds
- **Ignoring the problem**: "accuracy is 97%" when 97% of data is one class means the model learned nothing
- **Over-correcting**: forcing perfect 50/50 balance can degrade performance; experiment with ratios
- **SMOTE on high-dimensional sparse data**: interpolation in sparse space creates meaningless points; reduce dimensionality first

## References
- imbalanced-learn library: https://imbalanced-learn.org
- "SMOTE: Synthetic Minority Over-sampling Technique" (Chawla et al., 2002)
- "Focal Loss for Dense Object Detection" (Lin et al., 2017)
- "Learning from Imbalanced Data" (He & Garcia, 2009) — comprehensive survey
