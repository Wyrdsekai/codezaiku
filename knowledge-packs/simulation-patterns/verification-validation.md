# Verification and Validation Patterns

## When to use
- Building computational models intended for prediction or decision-making
- Regulatory or safety contexts requiring evidence that a model is trustworthy
- Any simulation where stakeholders need confidence in the results
- Publishing computational results that must be reproducible and defensible

## Pattern

### Code Verification ("Solving the equations right")
- Confirm the code correctly implements the mathematical model
- **Method of Manufactured Solutions (MMS)**: choose an arbitrary analytical solution, compute the corresponding source term, verify the code reproduces the chosen solution to expected order of accuracy
- **Order of accuracy test**: refine the mesh by 2x, observe error decreases at the theoretical rate (2nd order scheme: error halves with 2x refinement in each dimension)
- **Symmetry tests**: symmetric inputs must produce symmetric outputs
- **Conservation tests**: verify mass, energy, momentum are conserved to machine precision (or discretization order)
- Regression tests: known answers from previous verified runs — detect code changes that break correctness

### Solution Verification ("How accurate is this specific result")
- Quantify numerical error in a specific simulation (not a code-level test)
- Grid convergence study: run on 3+ mesh resolutions, fit Richardson extrapolation
- Richardson extrapolation: estimate exact solution and discretization error from convergence rate
- Grid Convergence Index (GCI): standardized uncertainty estimate from Richardson extrapolation
- Iterative convergence: verify that solver residuals are small enough that the solution is not contaminated
- Time step convergence: for transient problems, verify results are insensitive to time step size

### Validation ("Solving the right equations")
- Compare model predictions to experimental or observational data
- Validation hierarchy: unit problems (simple, well-characterized) → benchmark problems → system-level
- Validation metric: quantitative comparison (L2 norm of difference, prediction interval overlap)
- Account for experimental uncertainty: model prediction within experimental error bars is necessary but not sufficient
- Blind validation: run simulation before seeing experimental results — prevents unconscious tuning

### Uncertainty Quantification (UQ)
- **Aleatory uncertainty**: inherent randomness (material variability, turbulence) — irreducible
- **Epistemic uncertainty**: lack of knowledge (unknown parameters, model form) — reducible with data
- Forward UQ: propagate input uncertainties through the model to output uncertainties
- Methods: Monte Carlo sampling, polynomial chaos expansion, stochastic collocation
- Sensitivity analysis: which input uncertainties contribute most to output uncertainty (Sobol indices)
- Report results as distributions or confidence intervals, not single numbers

### Model Form Uncertainty
- No model is exact — the mathematical model is itself an approximation of reality
- Model form error: difference between the true physics and the equations being solved
- Multi-model approaches: run multiple models of varying fidelity, bracket the truth
- Bayesian model averaging: weight predictions from multiple models by their evidence
- Document all modeling assumptions explicitly — reviewers need to evaluate what was simplified

### Documentation and Reproducibility
- Record: code version, compiler, optimization flags, input files, mesh, machine
- Input files versioned alongside code in the same repository
- Automated verification test suite run with every code change (CI)
- V&V plan written before the simulation campaign, not after
- Follow community standards: ASME V&V 10 (solid mechanics), V&V 20 (CFD), AIAA G-077

## Gotchas / Anti-patterns
- Claiming a model is "validated" in general — validation is specific to a domain and conditions
- Skipping code verification and going straight to comparison with experiment — conflating code bugs with model error
- Single mesh result with no convergence study — unknown numerical error
- Tuning model parameters to match validation data, then claiming the match validates the model (circular)
- Ignoring experimental uncertainty — perfect agreement with a noisy experiment is suspicious
- Not reporting what didn't work — survivorship bias in published results
- Verification test that passes but with wrong convergence order — masked compensating errors

## References
- "Verification and Validation in Scientific Computing" (Oberkampf & Roy) — definitive V&V text
- ASME V&V 10: https://www.asme.org/codes-standards/find-codes-standards/v-v-10-standard-verification-validation-computational-solid-mechanics
- ASME V&V 20 (CFD): https://www.asme.org/codes-standards/find-codes-standards/v-v-20-standard-verification-validation-computational-fluid-dynamics-heat-transfer
- "Code Verification by the Method of Manufactured Solutions" (Roache): https://doi.org/10.1115/1.1436090
- Sandia V&V Challenge Problems: https://www.sandia.gov/verification-validation/
- NASA CFD V&V: https://www.grc.nasa.gov/www/wind/valid/
