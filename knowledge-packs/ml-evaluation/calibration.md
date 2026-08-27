# Model Calibration

## When to use
- When predicted probabilities are used for decision-making, not just rankings
- Risk-sensitive applications where "80% confident" must genuinely mean correct 80% of the time
- Combining predictions from multiple models (ensembles, cascading systems)
- Any system where humans act on confidence scores (medical triage, content moderation queues)

## Pattern

### What is Calibration?
- A model is calibrated if P(Y=1 | predicted probability = p) = p for all p
- Calibration and discrimination (ranking quality) are independent properties
- A model can have excellent AUC-ROC but terrible calibration (and vice versa)
- Most neural networks are overconfident after training: predicted probabilities are more extreme than warranted

### Reliability Diagrams
- Bin predictions by predicted probability (e.g., 10 bins: 0-0.1, 0.1-0.2, ...)
- For each bin, compute the actual fraction of positives
- Plot: x-axis = mean predicted probability per bin, y-axis = observed frequency
- Perfect calibration = diagonal line
- Above diagonal = underconfident; below diagonal = overconfident
- Always inspect the diagram visually; summary statistics can miss localized miscalibration

### Expected Calibration Error (ECE)
- Weighted average of |predicted probability - observed frequency| across bins
- ECE = sum over bins: (n_bin / N) * |accuracy_bin - confidence_bin|
- Lower is better; 0 = perfectly calibrated
- Sensitive to bin count and binning strategy
- Report alongside the reliability diagram, not as a standalone number
- Adaptive ECE: use equal-mass bins instead of equal-width for more stable estimates

### Platt Scaling (Parametric)
- Fit a logistic regression on the model's raw logits using a held-out calibration set
- Learns two parameters: A (scale) and B (shift) in sigmoid(A * logit + B)
- Fast, simple, effective for binary classification
- Requires a dedicated calibration set (not train, not test): use a split or cross-validation
- Extends to multi-class via temperature scaling (single parameter T applied to all logits)

### Temperature Scaling
- Special case of Platt scaling for multi-class: divide all logits by a learned temperature T
- T > 1 softens probabilities (reduces overconfidence), T < 1 sharpens
- Preserves the model's ranking (argmax unchanged); only adjusts confidence
- Recommended as the first approach for deep learning classifiers
- Fit T on the calibration set by minimizing negative log-likelihood

### Isotonic Regression (Non-Parametric)
- Fits a non-decreasing step function mapping predicted probabilities to calibrated probabilities
- No distributional assumptions; more flexible than Platt scaling
- Requires more calibration data to avoid overfitting (at least 1000+ samples recommended)
- Can change rankings (unlike temperature scaling)
- Use when the miscalibration pattern is non-monotonic or complex

### Calibration for LLMs
- Token-level log-probabilities can be used as confidence scores
- Verbalized confidence ("I am 90% sure") is poorly calibrated in most models
- Calibrate via:
  - Multiple sampling: generate N responses, measure consistency as a proxy for confidence
  - Probe-based: extract internal representations and train a calibration head
  - Post-hoc binning: collect (confidence, correctness) pairs and apply standard calibration
- Selective prediction: abstain when calibrated confidence is below threshold

### Calibration Workflow
1. Train the model as usual; do not modify training for calibration
2. Hold out a calibration set (15-20% of validation data, separate from test)
3. Generate predictions (logits or probabilities) on the calibration set
4. Plot reliability diagram; compute ECE
5. Apply calibration method (temperature scaling first; isotonic if needed)
6. Verify on the test set: plot new reliability diagram, compute new ECE
7. Deploy the calibration transform as part of the inference pipeline

## Gotchas / Anti-patterns
- Calibrating on the training set (overfitting the calibration transform)
- Using the test set for both calibration and final evaluation
- Assuming calibration transfers across domains or data distributions
- Ignoring calibration because the model "has good accuracy"
- Over-binning reliability diagrams (too many bins with few samples each)
- Applying isotonic regression with insufficient calibration data
- Not recalibrating after fine-tuning or distributional shift

## References
- Guo et al., "On Calibration of Modern Neural Networks" (2017)
- Platt, "Probabilistic Outputs for Support Vector Machines" (1999)
- Niculescu-Mizil & Caruana, "Predicting Good Probabilities With Supervised Learning" (2005)
- Kadavath et al., "Language Models (Mostly) Know What They Know" (2022)
