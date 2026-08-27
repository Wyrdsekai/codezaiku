# Monte Carlo Methods

## When to use
- Estimating quantities that are intractable to compute analytically
- High-dimensional integration where quadrature methods fail (curse of dimensionality)
- Uncertainty quantification: propagating input uncertainty through complex models
- Optimization under uncertainty, risk analysis, and stochastic modeling

## Pattern

### Random Sampling
- Core idea: estimate an expected value by averaging random samples
- `E[f(X)] ≈ (1/N) * sum(f(x_i))` where x_i drawn from the distribution of X
- Convergence rate: O(1/sqrt(N)) regardless of dimension — this is Monte Carlo's key advantage
- Confidence interval: standard error decreases as sqrt(N) — 4x samples halves the error
- Law of large numbers guarantees convergence — CLT provides error distribution

### Variance Reduction
- **Antithetic variates**: for each sample x, also evaluate at 1-x (for uniform) — introduces negative correlation
- **Control variates**: use a correlated quantity with known mean to adjust the estimate
- **Importance sampling**: sample from a distribution closer to the integrand's shape — reduces variance dramatically
- **Stratified sampling**: divide domain into strata, sample proportionally from each — guarantees coverage
- **Latin Hypercube Sampling**: stratify each dimension independently — better space coverage than pure random
- Variance reduction can improve efficiency by orders of magnitude for the same sample count

### Quasi-Random (Low-Discrepancy) Sequences
- Deterministic sequences that fill space more uniformly than pseudo-random: Sobol, Halton, Niederreiter
- Convergence rate: O(1/N) up to logarithmic factors — faster than O(1/sqrt(N)) random
- Better for smooth integrands in moderate dimensions (up to ~20-40)
- Scrambled quasi-random: randomized for error estimation while retaining low discrepancy
- Not suitable for MCMC or methods requiring independent samples

### Convergence Monitoring
- Running mean and standard error: track as N grows, stop when standard error meets target
- Effective sample size: for correlated samples (MCMC), effective N < actual N
- Visual convergence: plot running estimate vs sample count — should stabilize
- Batch means: divide samples into batches, compute batch means, estimate error from batch variance
- Gelman-Rubin diagnostic (MCMC): compare within-chain and between-chain variance

### Markov Chain Monte Carlo (MCMC)
- Sample from complex distributions by constructing a Markov chain with the target as its stationary distribution
- **Metropolis-Hastings**: propose a move, accept/reject based on likelihood ratio
- **Gibbs sampling**: sample each variable conditionally on current values of all others
- **Hamiltonian Monte Carlo (HMC)**: use gradient information for efficient exploration of continuous spaces
- Burn-in: discard initial samples before chain reaches stationary distribution
- Thinning: keep every k-th sample to reduce autocorrelation — controversial, sometimes wasteful

### Parallelization
- Embarrassingly parallel: run independent chains or sample batches on different processors
- Combine results: average estimates, pool samples, or use parallel tempering
- Reproducibility: assign separate PRNG streams per worker with known seeds
- Reduction: use compensated (Kahan) summation for numerical stability when summing many partial results

## Gotchas / Anti-patterns
- Too few samples for the required precision — 100 samples gives ~10% standard error
- Importance sampling with a poor proposal distribution — can increase variance catastrophically
- Using quasi-random sequences in high dimensions without testing — benefit diminishes
- MCMC without burn-in — initial samples biased by starting point
- Not checking MCMC convergence — chain may be stuck in a mode
- Pseudo-random generator with short period or poor quality — biased results
- Reporting point estimates without confidence intervals — precision is the whole point

## References
- "Monte Carlo Statistical Methods" (Robert & Casella) — comprehensive theory
- "Bayesian Data Analysis" (Gelman et al.) — MCMC in practice
- NumPy/SciPy Random Sampling: https://numpy.org/doc/stable/reference/random/
- Stan (probabilistic programming): https://mc-stan.org/ — state-of-the-art HMC
- Sobol Sequence: https://en.wikipedia.org/wiki/Sobol_sequence — quasi-random reference
- Art Owen, "Monte Carlo Theory, Methods and Examples": https://artowen.su.domains/mc/
