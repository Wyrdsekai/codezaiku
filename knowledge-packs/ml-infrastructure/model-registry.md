# Model Registry

## When to use
- Managing multiple model versions across development, staging, and production
- Implementing promotion workflows with approval gates
- Enabling rollback to a previous model version
- Tracking lineage between training runs, datasets, and deployed models

## Pattern

### Core Concepts

A model registry provides:
- **Versioned storage**: each model version is an immutable artifact with a unique identifier
- **Stage transitions**: models move through stages (e.g., dev -> staging -> production)
- **Metadata**: each version carries training config, eval metrics, lineage, and tags
- **Access control**: who can promote, demote, or archive model versions

### Version Naming

Use semantic versioning or sequential numbering tied to the registry, not ad-hoc names:
- `model-name/v1`, `model-name/v2` (sequential)
- `model-name/1.0.0`, `model-name/1.1.0` (semver for breaking vs non-breaking changes)
- Always include the base model identifier and adapter version separately if using LoRA

Each version must record:
- Git commit hash of training code
- Training run ID (links to experiment tracker)
- Dataset version or hash
- Evaluation metrics on standard benchmarks
- Hardware and software environment description

### Promotion Workflow

```
training run completes
  -> artifact published to registry as "candidate"
  -> automated evaluation gate (CI quality tests)
  -> candidate promoted to "staging"
  -> integration testing with downstream systems
  -> manual review and approval
  -> promoted to "production"
  -> previous production version becomes "archived" (not deleted)
```

Key principles:
- Promotion is an explicit action, never automatic
- Every stage transition is logged with who, when, and why
- At least one human approval before production (for critical models)
- Keep previous production version available for immediate rollback

### Rollback Strategies

**Instant rollback**: Keep the previous production version loaded or warm. Switch routing.
**Registry rollback**: Demote current production version, re-promote the previous one. Requires reloading.
**Canary rollback**: Route a percentage of traffic to the previous version while investigating.

Rollback prerequisites:
- Previous model version artifacts must not be garbage-collected
- Inference server must support loading a specific model version by ID
- Monitoring must detect the need for rollback quickly (see monitoring-inference.md)

### Storage Backend Options

- **Object storage** (S3, GCS, MinIO): scalable, durable, cost-effective for large artifacts
- **OCI registries**: treat models as container image layers, leverage existing container infra
- **DVC**: git-like versioning backed by object storage, good for smaller teams
- **Git LFS**: works for smaller models, breaks down at scale

### Artifact Contents

A complete model registry entry should include:
- Model weights (SafeTensors preferred)
- Tokenizer files and chat template
- Configuration file (architecture parameters)
- Adapter weights if applicable (separate from base)
- Evaluation report from quality gate
- README or card with intended use and limitations

## Gotchas / Anti-patterns
- Using file paths or timestamps as version identifiers (fragile, non-portable)
- Deleting old production versions to save storage (eliminates rollback capability)
- Promoting models without recorded evaluation metrics (no basis for comparison)
- Storing only weights without tokenizer and config (model is not self-contained)
- Having no rollback plan and discovering the process during an incident
- Mixing base model versions and adapter versions without clear dependency tracking
- Running the registry without access control (anyone can promote to production)
- Not testing the rollback process before you need it in production

## References
- MLflow Model Registry: https://mlflow.org/docs/latest/model-registry.html
- Hugging Face Hub (as registry): https://huggingface.co/docs/hub/
- OCI artifacts for ML: https://github.com/opencontainers/artifacts
- DVC model versioning: https://dvc.org/doc/use-cases/model-registry
