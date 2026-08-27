# Eval Suite Management

## When to use
- Maintaining evaluation infrastructure across model iterations and team members
- Detecting regressions early when models, data, or configurations change
- Ensuring reproducibility of evaluation results over months and years

## Pattern

### Eval Suite as Code
- Store evaluation datasets, scoring logic, and configuration in version control
- Treat eval code with the same rigor as production code: reviews, tests, CI
- Structure:
  ```
  eval/
    datasets/         # versioned test data (or pointers to immutable storage)
    configs/          # model configs, prompt templates, scoring parameters
    scorers/          # metric implementations
    runners/          # orchestration scripts
    baselines/        # recorded baseline results per model version
    reports/          # generated comparison reports
  ```
- Pin all dependencies (scoring libraries, judge model versions, embedding models)

### Dataset Versioning
- Every change to the eval dataset gets a new version tag
- Record what changed and why (added edge cases, removed ambiguous samples, corrected labels)
- Never silently modify existing samples; append new versions
- Store datasets in immutable object storage with content-addressable hashes
- Keep a changelog mapping dataset version to the rationale for changes

### Baseline Tracking
- Record a baseline result for each model version x dataset version x config combination
- Store: metric values, date, model identifier, dataset version, config hash, raw predictions
- Baselines are immutable; new runs produce new entries, not overwrites
- Enable comparison: new model vs baseline on the same dataset version

### Regression Detection
- Define regression thresholds per metric (e.g., accuracy must not drop more than 1%)
- Run eval suite in CI on every model checkpoint or configuration change
- Automated alerts when any metric crosses the regression threshold
- Distinguish between:
  - **Hard regression**: primary metric degrades beyond threshold (blocks deployment)
  - **Soft regression**: secondary metric degrades (investigation required, not blocking)
  - **Capability regression**: specific category degrades even if aggregate holds (check subcategories)

### Comparison Reports
- Generate standardized comparison reports: new model vs baseline vs previous best
- Include: aggregate metrics, per-category breakdown, failure case samples, statistical significance
- Diff-style view: which specific samples changed from correct to incorrect (and vice versa)
- Store reports alongside the run artifacts for future reference

### Eval Suite Evolution
- Schedule periodic reviews (quarterly) to assess benchmark relevance
- Add new test cases for capabilities discovered in production failures
- Retire test cases that no longer discriminate (all models score 100%)
- Track coverage: map eval samples to a capability taxonomy; identify blind spots
- When the eval suite changes, re-run baselines on the new version before comparing new models

### Reproducibility Checklist
- Model weights/checkpoint identifier and exact version
- Inference configuration (temperature, top-p, max tokens, system prompt)
- Dataset version hash
- Scoring code version (git SHA)
- Hardware and runtime environment (for latency-sensitive evals)
- Random seed (if any stochastic element exists in evaluation)
- Judge model version (if using LLM-as-judge)

### Multi-Environment Evaluation
- Run the same eval suite across environments: local dev, CI, staging, production shadow
- Ensure environment does not affect results (deterministic inference settings)
- Production shadow eval: run on real traffic without serving results; compare to offline eval

## Gotchas / Anti-patterns
- Modifying eval datasets without versioning (silent benchmark drift)
- Comparing results across different dataset versions without noting the change
- Running evals only before launch, not continuously in CI
- Checking only aggregate metrics; a 0% aggregate change can hide a +5%/-5% swap across categories
- Losing track of which model version corresponds to which eval run
- Allowing eval suite to grow unboundedly without retiring obsolete tests
- Not pinning the judge model version (LLM-as-judge results shift with model updates)

## References
- Breck et al., "The ML Test Score: A Rubric for ML Production Readiness" (2017)
- Ribeiro et al., "Beyond Accuracy: Behavioral Testing of NLP Models with CheckList" (2020)
- OpenAI Evals framework documentation
- Promptfoo evaluation framework documentation
