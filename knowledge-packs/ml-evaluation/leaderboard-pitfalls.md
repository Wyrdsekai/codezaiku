# Leaderboard Pitfalls

## When to use
- Interpreting public benchmark leaderboards for model selection decisions
- Understanding why leaderboard rankings may not predict real-world performance
- Designing evaluation processes that resist gaming and overfitting
- Communicating to stakeholders why "top of the leaderboard" is insufficient

## Pattern

### Goodhart's Law
- "When a measure becomes a target, it ceases to be a good measure"
- Models (and their creators) optimize for benchmark scores, not the underlying capability
- The more widely used a benchmark, the stronger the optimization pressure against it
- Leaderboard position measures benchmark performance, which is a noisy proxy for real utility

### Benchmark Gaming Mechanisms
- **Training on test data**: benchmark questions appear in web-scraped training corpora
  - Subtle: model memorizes answers without explicit intent from the trainer
  - Deliberate: including benchmark datasets in training mixtures
- **Prompt engineering for benchmarks**: system prompts or few-shot examples tuned to specific benchmarks
  - Model scores higher on the benchmark format than on equivalent real-world queries
- **Benchmark-specific fine-tuning**: optimizing specifically for benchmark tasks during post-training
- **Cherry-picking evaluation conditions**: reporting the best score across multiple runs, temperatures, or prompts
- **Architecture overfitting**: design choices that exploit benchmark-specific patterns

### Data Contamination
- Modern LLMs train on massive web crawls that inevitably contain benchmark data
- Contamination detection is hard: paraphrased or reformatted questions evade n-gram checks
- Models may memorize answers without the test-taker understanding the concept
- Even partial contamination inflates scores in unpredictable ways
- Temporal contamination: old benchmarks are more contaminated than new ones

### Overfitting to Test Sets
- With enough submissions, random variation produces a "winning" configuration
- Each submission is an implicit hyperparameter search over the test set
- Adaptive overfitting: researchers unconsciously adjust methods based on leaderboard feedback
- The more submissions allowed, the more inflated the top scores become
- Private test sets help but do not eliminate this if the public/private split is predictable

### Single-Number Rankings Hide Diversity
- A single aggregate score hides capability-specific strengths and weaknesses
- Model A may beat Model B on average but fail catastrophically on a specific capability
- Averaging across diverse tasks (math, coding, language, reasoning) obscures trade-offs
- Task weights in the aggregate are arbitrary and may not match your use case

### Leaderboard Decay
- Benchmarks that were discriminative two years ago may be saturated today
- Near-100% scores mean the benchmark no longer differentiates models
- Saturated benchmarks persist on leaderboards long after losing diagnostic value
- New benchmarks are harder, but their difficulty often comes from trick questions or ambiguity

### Reproducibility Issues
- Leaderboard scores are often not reproducible by third parties
- Differences in: inference settings, prompt formatting, tokenization, batch processing
- Many leaderboards accept self-reported scores without independent verification
- Small implementation details (chat template, system prompt presence) swing scores significantly

### Healthier Evaluation Practices
- **Evaluate on your own data**: build task-specific benchmarks reflecting actual deployment conditions
- **Use multiple benchmarks**: no single benchmark covers all relevant capabilities
- **Check per-category scores**: always look at disaggregated results, not just the aggregate
- **Run your own evaluations**: do not trust self-reported numbers; reproduce with your own pipeline
- **Freshness matters**: prefer recently created benchmarks with contamination controls
- **Private held-out sets**: maintain evaluation data that has never been published
- **Focus on your error distribution**: which mistakes matter most in your deployment context?

### Reading a Leaderboard Critically
1. When was the benchmark created? (Older = more contaminated)
2. Are scores self-reported or independently verified?
3. Is the test set public? (Public = higher contamination risk)
4. What are the aggregate weights? Do they match your use case?
5. Look at per-category breakdowns, not just overall rank
6. Check the score gap: is the difference between rank 1 and rank 5 statistically meaningful?
7. Does the top model also dominate on independently administered evals?

## Gotchas / Anti-patterns
- Selecting a model solely based on its leaderboard rank
- Assuming leaderboard improvements generalize to your specific domain
- Trusting self-reported scores without independent verification
- Ignoring the age and contamination risk of the benchmark
- Treating a 0.5% score difference as meaningful without statistical testing
- Using saturated benchmarks (near 100% scores) as selection criteria
- Presenting leaderboard rank to stakeholders as evidence of deployment readiness
- Ignoring capability-specific weaknesses hidden by strong aggregate performance

## References
- Goodhart, "Problems of Monetary Management" (1975) (original Goodhart's Law)
- Recht et al., "Do ImageNet Classifiers Generalize to ImageNet?" (2019)
- Zhou et al., "Don't Make Your LLM an Evaluation Benchmark Cheater" (2024)
- Kiela et al., "Dynabench: Rethinking Benchmarking in NLP" (2021)
- Bowman, "Eight Things to Know about Large Language Models" (2023)
