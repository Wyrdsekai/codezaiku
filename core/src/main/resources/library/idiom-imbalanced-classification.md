# Imbalanced classification — actually wire the imbalance handling (don't just comment it)

When the positive class is rare (fraud, churn, anomaly, defect, disease — anything well under ~10%
positive), a model trained the naive way predicts the majority class and looks "accurate" while being
useless. Handle the imbalance for real, and **match the technique to the estimator you chose**:

- **`RandomForestClassifier`, `LogisticRegression`, `LinearSVC`, `SGDClassifier`** support
  `class_weight="balanced"` — pass it in the constructor:

      model = RandomForestClassifier(class_weight="balanced", n_estimators=300, random_state=0)
      model = LogisticRegression(class_weight="balanced", max_iter=1000)

- **`XGBClassifier`** uses `scale_pos_weight = (#negatives / #positives)`:

      spw = (y == 0).sum() / max(1, (y == 1).sum())
      model = XGBClassifier(scale_pos_weight=spw, eval_metric="aucpr")

- **`GradientBoostingClassifier` and `HistGradientBoostingClassifier`**: the OLD `GradientBoostingClassifier`
  has **no `class_weight`** — passing it raises `TypeError`. Either use `HistGradientBoostingClassifier(class_weight="balanced")`
  (newer sklearn) or pass per-sample weights to `fit`:

      w = y.map({0: 1.0, 1: float((y == 0).sum()) / max(1, (y == 1).sum())})
      model.fit(X, y, sample_weight=w)
  Or resample first with imbalanced-learn (`SMOTE`, `RandomUnderSampler`).

The trap that silently fails: **writing `class_weight='balanced'` in a comment/docstring but not in the
constructor call** — the imbalance is then unhandled. The intent in the comment must appear in the code.

Evaluate with imbalance-aware metrics, NEVER accuracy (99.8% accuracy = predicting "negative" always):
use **ROC-AUC** and **PR-AUC / average_precision**, and report **precision at a fixed recall** so the
operating point is explicit.

Serving parity: fit the scaler/encoder on train, **persist it with the model** (one artifact), and apply
the *same* transform at predict time. Build the feature vector in the **same column order** at train and
serve — a reordered vector silently produces garbage scores.

Litmus before done: on a held-out split the model must beat a trivial baseline on **ROC-AUC** (≥ ~0.9 is
easy for a real model on separable data; ~0.5 means it didn't learn), and `predict_proba` must vary across
inputs (a constant probability = degenerate). If replacing the model with `return 0.0` wouldn't change the
metric, it isn't learning.
