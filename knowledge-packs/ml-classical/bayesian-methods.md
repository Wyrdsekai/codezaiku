# Bayesian Methods

## When to use
- Uncertainty quantification is as important as point predictions
- Small datasets where you need to incorporate prior knowledge
- Hyperparameter optimization where evaluation is expensive (Bayesian optimization)
- Spatial or temporal data with smooth, continuous relationships (Gaussian processes)
- Sequential decision-making where you update beliefs as new data arrives

## Pattern

### Gaussian Processes (GP)
- Non-parametric model that defines a distribution over functions
- Predictions come with calibrated uncertainty: mean prediction plus confidence interval
- **Kernel (covariance function)** determines the shape of functions the GP can represent:
  - `RBF` (squared exponential): smooth functions, the default choice
  - `Matern`: controls smoothness via `nu` parameter. `nu=1.5` (once differentiable) and `nu=2.5` (twice differentiable) are common.
  - `RationalQuadratic`: mixture of RBFs at different length scales; good when patterns vary at multiple scales
  - `Periodic`: for data with repeating patterns; combine with RBF for locally periodic behavior
- Kernel composition: add kernels for additive effects, multiply for interactions. E.g., `RBF + Periodic` for trend + seasonality.
- **Length scale**: controls how quickly the function varies. Longer = smoother. Optimize via marginal likelihood.
- Training: maximize log marginal likelihood to fit kernel hyperparameters. No separate train/validation needed.
- Prediction complexity: O(n^3) training (matrix inversion), O(n^2) prediction. Limits practical use to ~5-10k data points.

### Scalable GP Approximations
- **Sparse GP (inducing points)**: select m << n inducing points, approximate the full GP. O(nm^2) training.
- **SVGP (Stochastic Variational GP)**: mini-batch training with inducing points. Scales to millions of points.
- **KISS-GP (Kernel Interpolation)**: structured kernel interpolation on a grid; fast for low-dimensional data (d < 5)
- Frameworks: GPyTorch (PyTorch-based, best for scalable GPs), GPflow (TensorFlow-based), scikit-learn (small-scale only)

### Bayesian Optimization
- Global optimization of expensive black-box functions (hyperparameter tuning, experimental design)
- Components:
  1. **Surrogate model**: GP that models the objective function (maps inputs to outputs with uncertainty)
  2. **Acquisition function**: decides where to evaluate next, balancing exploration and exploitation
- Acquisition functions:
  - `Expected Improvement (EI)`: most common, works well in practice
  - `Upper Confidence Bound (UCB)`: tunable exploration via `kappa` parameter
  - `Probability of Improvement (PI)`: greedy, tends to exploit too early
  - `Knowledge Gradient`: theoretically optimal for finite budgets, more expensive to compute
- Workflow: evaluate a few random points (5-10), fit GP, select next point via acquisition function, evaluate, repeat
- Budget: 10-50 evaluations of the objective function is the typical range where Bayesian optimization shines
- Libraries: Optuna (tree-structured Parzen estimators, not GP-based but conceptually similar), BoTorch (GP-based, PyTorch), scikit-optimize

### Prior Selection
- Prior encodes your belief about the function before seeing data
- **Uninformative / weakly informative**: use when you have no domain knowledge. Wide Normal or Uniform priors.
- **Informative**: encode domain constraints. E.g., a parameter must be positive (use LogNormal or HalfNormal).
- **Regularizing**: prevent extreme values. E.g., Normal(0, 1) on coefficients is analogous to L2 regularization.
- GP priors:
  - Mean function prior: constant mean (default) or linear mean for data with a trend
  - Kernel hyperparameter priors: place a prior on length scale to prevent it from collapsing to zero or exploding
- Sensitivity analysis: check how much predictions change with different reasonable priors. If they change a lot, you need more data.
- With enough data, the prior is overwhelmed by the likelihood; prior choice matters most for small datasets

### Bayesian Linear Regression
- Closed-form posterior for linear models with Gaussian likelihood and conjugate prior
- Naturally provides posterior distributions over weights and predictions with uncertainty
- Equivalent to Ridge regression at the posterior mean, but also gives the full posterior
- Use when you need calibrated uncertainty intervals on a linear model
- Scales well: conjugate posterior update is O(d^3) where d is the number of features

### Practical Bayesian Workflow
1. Start with a simple model and weakly informative priors
2. Perform prior predictive checks: sample from the prior and verify predictions are in a plausible range
3. Fit the model and check convergence diagnostics (R-hat, effective sample size for MCMC)
4. Posterior predictive checks: compare model predictions to observed data
5. Sensitivity analysis: vary priors and check if conclusions change
6. Report full posterior (credible intervals), not just point estimates

## Gotchas / Anti-patterns
- Standard GPs on more than 10k points without approximation: O(n^3) makes training impractical
- Using a smooth kernel (RBF) for rough or discontinuous data: the GP will over-smooth and have poor uncertainty estimates
- Bayesian optimization with >20 continuous dimensions: the surrogate GP becomes unreliable. Use random search or evolutionary methods instead.
- Treating GP uncertainty as exact: it is model uncertainty conditional on the kernel choice. Wrong kernel = wrong uncertainty.
- Overconfident priors on small datasets: the posterior cannot escape a strong prior with limited data
- Running MCMC without checking convergence (R-hat, trace plots): unconverged chains produce unreliable posteriors
- Using Bayesian optimization for cheap-to-evaluate functions: grid search or random search is faster when evaluations take <1 second

## References
- Rasmussen & Williams (2006), "Gaussian Processes for Machine Learning" (free online: gaussianprocess.org/gpml)
- Snoek, Larochelle, Adams (2012), "Practical Bayesian Optimization of Machine Learning Algorithms"
- Gelman et al. (2013), "Bayesian Data Analysis" (3rd edition)
- GPyTorch documentation: https://gpytorch.ai/
