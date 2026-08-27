# CI/CD for ML

## When to use
- Automating model testing as part of a development pipeline
- Detecting quality regressions before deploying model updates
- Versioning models alongside code in a release process
- Ensuring reproducibility of training and evaluation across environments

## Pattern

### Testing Models in CI

**Smoke tests (every commit)**:
- Load the model and run inference on 3-5 fixed inputs
- Verify output shape, type, and basic format correctness
- Assert that known inputs produce non-degenerate outputs (not empty, not all zeros)
- Runtime target: under 2 minutes, CPU-only if possible

**Quality gate tests (pre-merge or nightly)**:
- Run a curated evaluation suite (50-500 examples covering key capabilities)
- Compare against stored baseline metrics with defined thresholds
- Fail the pipeline if primary metric drops below threshold
- Store results as pipeline artifacts for later analysis

**Full evaluation (nightly or weekly)**:
- Run comprehensive benchmarks (HumanEval, MMLU, domain-specific)
- Generate comparison reports against previous runs
- Track trends over time, not just pass/fail

### Regression Detection

Define explicit thresholds:
- Absolute threshold: metric must be >= X (e.g., accuracy >= 0.85)
- Relative threshold: metric must not drop more than Y% from baseline (e.g., no more than 2% drop)
- Use both: absolute catches catastrophic failures, relative catches gradual degradation

Baseline management:
- Store baseline metrics in version control (JSON or YAML)
- Update baselines explicitly when a new model version is promoted
- Never auto-update baselines on pass (defeats the purpose)

### Model Versioning in Pipelines

- Tag model artifacts with the git commit hash or pipeline run ID
- Store model metadata (training config, eval results, base model) alongside weights
- Use a model registry (see model-registry.md) for promotion between stages
- Pipeline should produce: model artifact + eval report + metadata bundle

### Pipeline Structure

```
code change
  -> lint + unit tests (code quality)
  -> smoke test (model loads, basic inference works)
  -> quality gate (evaluation suite against baseline)
  -> build artifact (model + metadata + eval report)
  -> publish to registry (staging)
  -> manual or automated promotion (production)
```

### Environment Reproducibility

- Pin CUDA, driver, and framework versions in CI environment
- Use container images for GPU-enabled CI runners
- Cache model weights to avoid re-downloading on every run
- Store evaluation datasets as versioned artifacts, not downloaded at runtime

## Gotchas / Anti-patterns
- Running GPU-intensive evaluations on every commit (expensive, slow, blocks pipeline)
- Testing only happy-path inputs without adversarial or edge-case examples
- Using non-deterministic inference in CI without accounting for variance (set seeds, use greedy decoding)
- Auto-updating baseline metrics when tests pass (masks gradual degradation)
- Storing large model files in git (use Git LFS, DVC, or external artifact storage)
- Not separating code tests from model quality tests (different cadence, different infrastructure)
- Skipping evaluation when "only code changed" (code changes affect model behavior)
- Running evaluation against a moving test set (pin the dataset version)

## References
- DVC (Data Version Control): https://dvc.org/doc
- CML (Continuous ML): https://cml.dev/
- MLflow model registry: https://mlflow.org/docs/latest/model-registry.html
- GitHub Actions GPU runners: https://docs.github.com/en/actions/using-github-hosted-runners/using-larger-runners
