# Model Interpretability

## When to use
- Regulatory or compliance requirements demand explainable predictions (finance, healthcare)
- Debugging model behavior: understanding why a model makes specific predictions
- Building trust with stakeholders who need to validate model logic
- Feature engineering insights: discovering which features and interactions matter
- Comparing models beyond accuracy metrics

## Pattern

### SHAP (SHapley Additive exPlanations)
- Grounded in cooperative game theory: each feature's contribution is its Shapley value
- Properties: local accuracy (contributions sum to prediction), consistency, and missingness
- **TreeSHAP**: exact, fast computation for tree-based models (XGBoost, LightGBM, RF). O(TLD) per prediction.
- **KernelSHAP**: model-agnostic but approximate; slower, works with any model
- **DeepSHAP**: for neural networks, combines DeepLIFT with Shapley values
- Key plots:
  - `summary_plot`: beeswarm showing feature importance and direction of effect
  - `dependence_plot`: single feature vs SHAP value, colored by interaction feature
  - `waterfall_plot`: single-prediction explanation showing each feature's push/pull
  - `force_plot`: compact single-prediction explanation
- Use `shap.Explainer(model)` which auto-selects the right algorithm based on model type
- Background dataset: KernelSHAP needs a reference dataset; use `shap.sample(X_train, 100)` for speed

### LIME (Local Interpretable Model-agnostic Explanations)
- Explains individual predictions by fitting a local linear model around the instance
- Perturbs the input, gets model predictions on perturbed samples, fits a weighted linear model
- Model-agnostic: works with any classifier or regressor
- `num_features` controls how many features appear in the explanation (default 10)
- `num_samples` controls perturbation count: more samples = more stable explanations (default 5000)
- For tabular data: `LimeTabularExplainer`; for text: `LimeTextExplainer`; for images: `LimeImageExplainer`
- LIME explanations vary between runs due to random perturbations; average multiple runs for stability
- Local fidelity: the linear model is only valid near the explained instance, not globally

### Permutation Importance
- Measure how much model performance drops when a feature's values are randomly shuffled
- Model-agnostic, uses the trained model without retraining
- Compute on validation/test set, never on training set (training set importance can be inflated by overfitting)
- Multipass permutation: shuffle one feature at a time, repeat 10+ times, report mean and std of performance drop
- Handles feature interactions partially: permuting a correlated feature may not show importance because its partner compensates
- Faster than SHAP for getting a global feature ranking; does not provide per-prediction explanations
- Negative importance means the feature adds noise; the model would perform better without it

### Partial Dependence Plots (PDP)
- Show the marginal effect of one or two features on the predicted outcome, averaging over all other features
- Assumes feature independence: if features are correlated, PDP can show unrealistic feature combinations
- **Individual Conditional Expectation (ICE)**: one line per sample instead of averaging; reveals heterogeneous effects that PDP hides
- Use ICE + PDP together: PDP as the average trend, ICE lines showing variation across samples
- Two-feature PDP (contour/heatmap) shows interaction effects but becomes hard to read with more than 2 features
- Compute on a grid of feature values; 50-100 grid points is typically sufficient

### Choosing the Right Method
- **Global importance ranking**: permutation importance or mean |SHAP|
- **Understanding feature effects**: PDP/ICE for global trends, SHAP dependence plots for direction + interactions
- **Explaining a single prediction**: SHAP waterfall or LIME
- **Tree models**: TreeSHAP (fast, exact)
- **Any model, quick ranking**: permutation importance
- **Any model, detailed explanations**: KernelSHAP or LIME
- **Stakeholder presentations**: SHAP summary plots and waterfall plots are the most intuitive

### Interpretation Workflow
1. Start with permutation importance for a quick global view
2. Use SHAP summary plot to see both importance and direction of effect
3. Investigate top features with SHAP dependence plots or PDP/ICE
4. For specific predictions of interest, use SHAP waterfall or LIME
5. Validate findings against domain knowledge: if a feature's effect contradicts domain understanding, investigate data quality or leakage

## Gotchas / Anti-patterns
- SHAP values computed on training data reflect what the model memorized, not what generalizes; use validation data
- LIME explanations are unstable for high-dimensional data or complex decision boundaries; always check consistency across runs
- PDP with correlated features produces misleading plots: it averages over impossible feature combinations. Use Accumulated Local Effects (ALE) instead.
- Permutation importance underestimates correlated features: each correlated feature appears less important because its partners compensate
- Interpreting feature importance as causal effect: importance shows predictive association, not causation
- Using SHAP on a poorly performing model: the explanations faithfully explain the model, which may not reflect reality
- TreeSHAP with `feature_perturbation='tree_path_dependent'` (default) can produce unintuitive results for correlated features; use `'interventional'` if features are correlated

## References
- Lundberg & Lee (2017), "A Unified Approach to Interpreting Model Predictions" (SHAP)
- Ribeiro, Singh, Guestrin (2016), "'Why Should I Trust You?': Explaining the Predictions of Any Classifier" (LIME)
- Molnar (2022), "Interpretable Machine Learning" (online book: christophm.github.io/interpretable-ml-book)
- Friedman (2001), "Greedy Function Approximation" (Partial Dependence)
