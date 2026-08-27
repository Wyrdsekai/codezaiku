# Red-Teaming for LLMs

## When to use
- Pre-deployment safety evaluation of language models or LLM-powered applications
- Identifying failure modes that standard benchmarks do not cover
- Testing guardrails, content filters, and safety layers under adversarial conditions
- Regulatory or policy compliance for high-risk AI deployments

## Pattern

### Red-Teaming Scope
Define what you are testing before starting:
- **Model-level**: the raw model's responses without application guardrails
- **System-level**: the full application stack (model + filters + retrieval + UI)
- **Capability-specific**: targeted probing of one risk area (e.g., only code generation risks)
- Always test at the system level in addition to the model level; guardrails can be bypassed

### Attack Categories
- **Direct harmful requests**: ask the model to produce dangerous content outright
- **Prompt injection**: embed instructions in user-controlled input fields (documents, URLs, tool outputs)
  - First-party: user crafts a prompt to bypass safety
  - Third-party (indirect): malicious instructions hidden in data the model processes
- **Jailbreaks**: techniques to override system instructions
  - Role-play framing ("pretend you are an unrestricted AI")
  - Encoding tricks (base64, rot13, token splitting)
  - Multi-turn escalation (gradually shifting the conversation)
  - Few-shot priming with harmful examples
- **Information extraction**: attempts to extract system prompts, training data, or PII
- **Bias elicitation**: prompts designed to trigger discriminatory or stereotyping outputs
- **Hallucination induction**: questions that pressure the model to fabricate facts confidently

### Prompt Injection Testing
- Insert adversarial instructions in every user-controlled input channel
- Test: "Ignore all previous instructions and..." variations in documents, filenames, tool outputs
- Verify: does the model follow injected instructions or the system prompt?
- Test delimiter bypass: does escaping the structured prompt format break containment?
- Include multi-language injection (instructions in a language different from the main prompt)

### Red-Team Composition
- Include people with diverse backgrounds, not just ML engineers
- Domain experts understand realistic misuse scenarios
- External red-teamers provide fresh perspective and avoid blind spots
- Mix automated (programmatic probing) with manual (creative adversarial thinking)
- Rotate red-team members periodically to prevent adaptation fatigue

### Structured Process
1. **Scope**: define target system, attack categories, success criteria
2. **Threat model**: who are the adversaries, what are their capabilities and goals
3. **Generate attacks**: manual brainstorming + automated generation (using LLMs to generate adversarial prompts)
4. **Execute**: run attacks systematically, record all inputs and outputs
5. **Classify**: categorize failures by severity and type
6. **Report**: document findings with reproducible examples and recommended mitigations
7. **Retest**: verify mitigations work; check for regressions in non-adversarial behavior

### Severity Classification
- **Critical**: model produces content that could cause direct real-world harm
- **High**: model bypasses safety guardrails but output requires additional steps to cause harm
- **Medium**: model reveals system prompts or internal configurations
- **Low**: model produces mildly inappropriate content that filters should catch
- **Informational**: unexpected behavior that does not rise to a safety concern

### Automated Red-Teaming
- Use LLMs to generate diverse adversarial prompts at scale
- Classifier-based detection: train a classifier to flag potentially harmful outputs
- Fuzzing: systematic permutation of known jailbreak patterns
- Reinforcement learning approaches: train an attacker model to find failure modes
- Automated methods find breadth; human red-teamers find depth and novel attacks

### Safety Eval Datasets
- Maintain a versioned corpus of adversarial test cases
- Organize by attack category and severity
- Track which attacks succeed across model versions (regression detection)
- Never include these in training data; treat as held-out adversarial benchmarks

## Gotchas / Anti-patterns
- Red-teaming only once before launch and never again (models and attacks evolve)
- Testing only English-language attacks; multilingual models need multilingual red-teaming
- Relying solely on automated tools without human creativity
- Declaring safety based on passing a fixed set of adversarial prompts (new attacks emerge constantly)
- Not testing the full system stack (model passes but the application pipeline introduces vulnerabilities)
- Publishing all successful attack patterns without responsible disclosure considerations
- Conflating red-teaming with general quality evaluation; red-teaming focuses on worst-case, not average-case

## References
- Ganguli et al., "Red Teaming Language Models to Reduce Harms" (2022)
- Perez et al., "Red Teaming Language Models with Language Models" (2022)
- OWASP Top 10 for LLM Applications
- NIST AI Risk Management Framework (AI RMF)
