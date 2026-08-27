# Overfitting Diagnosis

## When to use
- Training loss keeps decreasing but validation loss plateaus or increases
- Model performs well on training data but poorly on held-out test data
- You suspect the model is memorizing rather than generalizing
- After any training run as part of standard evaluation hygiene

## Pattern

### Detection signals
- **Train/val loss divergence**: The clearest signal. Plot both curves — overfitting starts where they diverge
- **Validation metric plateau then degradation**: Val loss initially improves alongside train loss, then reverses
- **Near-zero training loss**: On non-trivial tasks, a model that perfectly fits training data is almost certainly overfitting
- **High confidence on wrong predictions**: Model outputs extreme probabilities even when incorrect
- **Performance gap increases with training duration**: Longer training makes val performance worse

### Severity assessment
- **Mild**: Small train/val gap, val performance still acceptable — may not need intervention
- **Moderate**: Noticeable gap, val performance plateauing — apply regularization
- **Severe**: Val loss actively increasing, large gap — stop training, restructure approach

### Remediation (ordered by ease of implementation)
1. **Early stopping**: Stop at the epoch with best validation metric. Zero implementation cost
2. **Reduce training duration**: If val metric peaked at epoch 5 of 20, you are overtraining
3. **Add dropout**: Start with p=0.1 for transformers, p=0.3-0.5 for MLPs
4. **Increase weight decay**: Bump by 2-5x from current value
5. **Data augmentation**: Increase effective training set diversity
6. **Reduce model capacity**: Fewer layers, smaller hidden dimensions, fewer parameters
7. **Get more data**: The most effective regularizer, but often the most expensive
8. **Label smoothing**: Prevents overconfident predictions (epsilon=0.1)

### Diagnostic workflow
1. Plot train and val loss curves on the same chart
2. Identify the divergence point (epoch where val stops improving)
3. Check if train loss is near zero — if so, model has excess capacity
4. Apply early stopping using the divergence point
5. If the best val performance is still insufficient, add regularization and retrain
6. If regularization helps, gradually increase model capacity back up

### Monitoring best practices
- Always track both train and val metrics — never just one
- Use a held-out test set that is never used for any decision during training
- Log per-sample loss distributions, not just averages — reveals whether memorization is uniform or concentrated
- Track metrics per class/category — overfitting often affects minority classes first

## Gotchas / Anti-patterns
- Using validation set for hyperparameter tuning then reporting it as test performance — this is data leakage
- Reducing model capacity as first response — often unnecessary when regularization suffices
- Ignoring early stopping because "more training is always better" — it is not
- Applying all regularization techniques at maximum strength simultaneously — causes underfitting
- Assuming data augmentation always helps — bad augmentations can hurt (see regularization.md)
- Not shuffling data before train/val split — temporal or ordering biases leak into splits

## References
- "Understanding Deep Learning Requires Rethinking Generalization" (Zhang et al., 2017)
- "Train longer, generalize better" (Hoffer et al., 2017) — nuances of training duration
- See also: `regularization.md`, `batch-size-selection.md`, `underfitting-diagnosis.md`
