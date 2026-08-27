# A/B Testing Models

## When to use
- Validating that offline metric improvements translate to real-world gains
- Deploying a new model alongside the incumbent with controlled traffic splitting
- Measuring user-facing outcomes (engagement, satisfaction, revenue) that offline evals cannot capture

## Pattern

### Offline vs Online Evaluation
- **Offline**: evaluate on held-out datasets before deployment; fast, cheap, reproducible
- **Online**: evaluate with real users/traffic in production; ground truth for business metrics
- The gap between offline and online is often significant
- Always validate offline winners with online experiments before full rollout

### Canary Deployment
- Route a small percentage (1-5%) of traffic to the new model
- Monitor error rates, latency, and key business metrics
- Automated rollback if error rate exceeds threshold or latency degrades
- Gradually increase traffic share if metrics remain healthy (1% -> 5% -> 25% -> 50% -> 100%)
- Canary is a safety mechanism, not a statistical experiment

### A/B Testing (Randomized Controlled Experiment)
- Randomly assign users (not requests) to control (model A) or treatment (model B)
- User-level assignment prevents inconsistent experience within a session
- Pre-register: define primary metric, sample size, duration, and success criteria before starting
- Minimum detectable effect (MDE): the smallest improvement worth detecting; drives sample size
- Run until the pre-computed sample size is reached; do not peek and stop early without correction

### Sample Size and Duration
- Required sample size depends on: baseline metric, MDE, significance level (alpha), power (1-beta)
- Typical: alpha=0.05, power=0.80
- Duration must cover at least one full business cycle (weekly patterns, seasonal effects)
- Short experiments miss slow-moving metrics (retention, churn)

### Interleaving Experiments
- Both models serve results within the same request; user interaction determines preference
- Much more statistically efficient than A/B (requires 10-100x fewer samples to detect same effect)
- Team Draft interleaving: alternate picking results from each model into a combined list
- Credit system: clicks on a model's contributed result count toward that model
- Best suited for ranking/recommendation tasks
- Cannot measure per-model latency or full-page experience differences

### Multi-Armed Bandits
- Adaptive traffic allocation: shift traffic toward the better-performing model during the experiment
- Thompson Sampling or Upper Confidence Bound (UCB) policies
- Reduces regret (serving inferior model to users) during experimentation
- Trade-off: less statistically clean than fixed-allocation A/B; harder to reach definitive conclusions
- Use when opportunity cost of serving the worse model is high

### Metrics Taxonomy
- **Guardrail metrics**: must not degrade (latency, error rate, safety violations); trigger auto-rollback
- **Primary metric**: the one metric the experiment is powered to detect a change in
- **Secondary metrics**: informational, not used for go/no-go decisions (avoid multiple testing issues)
- **Counter metrics**: metrics that should not improve (e.g., engagement from addictive dark patterns)

### Experiment Lifecycle
1. Define hypothesis, metrics, MDE, sample size
2. Implement traffic splitting with consistent user assignment
3. Run dark (shadow mode) first: new model runs but results are not served; compare outputs
4. Run canary: small traffic share, monitor guardrails
5. Run full A/B: pre-computed duration, no early stopping without sequential testing correction
6. Analyze: primary metric with confidence interval; check guardrails; inspect segments
7. Ship or iterate

## Gotchas / Anti-patterns
- Splitting by request instead of by user (inconsistent experience, inflated sample sizes)
- Peeking at results daily and stopping when p < 0.05 (inflates false positive rate)
- Running too many concurrent experiments with overlapping users without accounting for interaction effects
- Ignoring novelty/primacy effects (users may prefer the new model simply because it is different)
- Using bandit allocation and then reporting p-values as if it were a fixed-allocation experiment
- Not running long enough to observe delayed effects (retention, long-term satisfaction)

## References
- Kohavi, Tang & Xu, "Trustworthy Online Controlled Experiments" (2020)
- Chapelle et al., "Large-scale Validation and Analysis of Interleaved Search Evaluation" (2012)
- Microsoft ExP Platform documentation
