# Promptfoo Evaluation Suites

## When to use
- Systematic evaluation of LLM prompts, models, or RAG pipelines
- Regression testing prompts across model changes
- Comparing multiple models or prompt variants side by side
- CI/CD integration for prompt quality gates
- Red-teaming and adversarial prompt testing

## Pattern

### YAML Configuration
```yaml
# promptfooconfig.yaml
description: "Code generation eval"

prompts:
  - "Write a {{language}} function that {{task}}"
  - "You are an expert {{language}} developer. Implement: {{task}}"

providers:
  - id: openai:gpt-4o
  - id: ollama:qwen3-8b
    config:
      temperature: 0.1

tests:
  - vars:
      language: Python
      task: reverses a linked list
    assert:
      - type: contains
        value: "def "
      - type: llm-rubric
        value: "Code is correct, handles edge cases, and is idiomatic"
      - type: python
        value: "len(output) < 2000"

  - vars:
      language: Java
      task: implements binary search
    assert:
      - type: contains
        value: "public"
      - type: not-contains
        value: "TODO"
```

### Assertion Types
- `contains` / `not-contains` — substring matching
- `equals` / `is-json` — exact match or structural validation
- `llm-rubric` — LLM-as-judge with natural language criteria
- `python` / `javascript` — custom assertion logic returning bool
- `similar` — cosine similarity above threshold (requires embedding provider)
- `cost` / `latency` — performance budget assertions
- `regex` — pattern matching

### LLM-as-Judge
```yaml
defaultTest:
  options:
    provider: openai:gpt-4o  # judge model
assert:
  - type: llm-rubric
    value: |
      Rate the response on:
      1. Correctness (0-5)
      2. Completeness (0-5)
      3. Code quality (0-5)
      Score must be >= 12 total to pass.
```

### Dataset-Driven Tests
```yaml
tests: file://tests/eval_cases.csv
# CSV columns map to template variables
# language,task,expected_pattern
# Python,fibonacci,"def fib"
```

### Running
```bash
# Interactive evaluation
promptfoo eval

# View results
promptfoo view

# CI mode (exits non-zero on failure)
promptfoo eval --no-cache --output results.json
promptfoo eval --grader openai:gpt-4o

# Compare specific configs
promptfoo eval -c config_a.yaml -c config_b.yaml
```

### CI Integration
```yaml
# GitHub Actions example
- name: Run prompt evals
  run: |
    npx promptfoo eval --no-cache --output results.json
    npx promptfoo eval --output-format json | jq '.results.stats.failures == 0'
```

### Custom Providers
```yaml
providers:
  - id: exec:python my_provider.py
  - id: http://localhost:8080/v1/chat/completions
    config:
      headers:
        Authorization: "Bearer {{env.API_KEY}}"
```

## Gotchas / Anti-patterns
- Over-relying on `contains` assertions — brittle to formatting changes; prefer `llm-rubric` for semantic checks
- Using expensive judge models for every assertion — mix cheap deterministic checks with selective LLM grading
- Not setting `--no-cache` in CI — stale results mask regressions
- Testing only happy paths — include adversarial inputs, edge cases, refusals
- Giant monolithic config files — split by feature area, use `file://` references
- Not versioning eval configs alongside prompts — eval drift defeats the purpose

## References
- Promptfoo docs: https://www.promptfoo.dev/docs/intro
- Assertion reference: https://www.promptfoo.dev/docs/configuration/expected-outputs
- GitHub: https://github.com/promptfoo/promptfoo
