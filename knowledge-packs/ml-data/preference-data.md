# Preference Data

## When to use
- Fine-tuning language models with RLHF (Reinforcement Learning from Human Feedback) or DPO (Direct Preference Optimization)
- Aligning model outputs with human values, style, or quality standards
- Building reward models for rejection sampling or best-of-N selection
- Collecting structured human judgments on model-generated content
- Any scenario where "better vs worse" is easier to judge than absolute quality scores

## Pattern

### Preference pair structure
- A preference pair consists of: **prompt**, **chosen response**, **rejected response**
- Optionally includes: annotator ID, confidence score, timestamp, metadata
- The signal is relative: chosen is better than rejected for this specific prompt
- Common formats: JSON lines with `prompt`, `chosen`, `rejected` fields; HuggingFace datasets format; Anthropic HH-style format

### Collection methods

#### Human annotation
- Present annotators with a prompt and two (or more) responses; ask which is better
- **Pairwise comparison**: simpler, more reliable than Likert scales for subjective quality
- **Best-of-N ranking**: show N responses, annotator ranks them; generates N*(N-1)/2 pairwise preferences
- **Criteria-based evaluation**: define specific axes (helpfulness, harmlessness, honesty) and collect per-axis preferences
- Use clear rubrics: annotators must know what "better" means for your task

#### LLM-as-judge
- Use a strong model to generate preference labels for a weaker model's outputs
- Prompt the judge model with a rubric and both responses; ask for a structured verdict
- **Position bias mitigation**: evaluate each pair twice with swapped order; discard if judge is inconsistent
- **Calibration**: validate LLM judge against human preferences on a held-out set; report agreement rate
- Cost-effective for scale but introduces the judge model's biases

#### Implicit preferences
- User behavior signals: clicks, dwell time, regeneration requests, thumbs up/down
- Noisier than explicit annotation; requires more volume to be useful
- Filter for clear signals: "user chose response A over response B" is stronger than "user read response A longer"

### Quality filtering
- **Agreement filtering**: if multiple annotators disagree, the pair may be ambiguous; consider excluding or using soft labels
- **Consistency checks**: include duplicate pairs to measure annotator reliability; remove annotators below threshold
- **Length bias detection**: annotators often prefer longer responses; analyze whether preference correlates with length independent of quality
- **Prompt diversity**: ensure prompts cover the target distribution; a preference dataset skewed toward one topic biases the model
- **Difficulty filtering**: very easy pairs (one response is clearly broken) provide weak training signal; include challenging pairs where both responses are reasonable

### Annotation formats

#### Standard formats
```json
{"prompt": "Explain photosynthesis", "chosen": "Plants convert...", "rejected": "Photosynthesis is when..."}
```

#### With metadata
```json
{
  "prompt": "Explain photosynthesis",
  "chosen": "Plants convert...",
  "rejected": "Photosynthesis is when...",
  "annotator": "expert_42",
  "criteria": "accuracy",
  "confidence": "high",
  "source_model": "llama-3-8b"
}
```

#### Multi-turn
```json
{
  "conversation_prefix": [{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}],
  "prompt": "Follow-up question",
  "chosen": "Good continuation...",
  "rejected": "Poor continuation..."
}
```

### Dataset size guidelines
- DPO: effective from ~1,000 high-quality pairs for narrow tasks; 10,000-100,000 for general alignment
- RLHF reward model: typically needs more data than DPO (10,000+ pairs minimum)
- Quality beats quantity: 5,000 expert-annotated pairs often outperforms 50,000 noisy pairs
- Iterate: start small, train, evaluate, then collect more data targeting model weaknesses

### Reward model validation
- Hold out 10-20% of preference pairs for reward model evaluation
- Metric: preference prediction accuracy (does the reward model agree with human preference?)
- Baseline: 50% is random; good reward models achieve 65-75%+ on diverse data
- Stratify evaluation by difficulty: easy pairs inflate accuracy

## Gotchas / Anti-patterns
- **Contaminated pairs**: chosen response copied from rejected with minor edits; model learns superficial differences
- **Single annotator**: one person's preferences are idiosyncratic; use multiple annotators and measure agreement
- **Only using model-generated rejected responses**: if rejected is always clearly bad, the model learns to avoid obvious failures but not to distinguish good from great
- **Ignoring position bias**: both humans and LLM judges are biased toward the first/last option; randomize presentation order
- **Length as a proxy for quality**: longer responses are not inherently better; control for length in analysis and annotation guidelines
- **No prompt diversity**: preferences collected on a narrow prompt set do not generalize; cover the full intended use case distribution
- **Static preference data**: human preferences evolve; periodically refresh the dataset, especially after model updates

## References
- "Training Language Models to Follow Instructions with Human Feedback" (Ouyang et al., 2022) — InstructGPT/RLHF
- "Direct Preference Optimization" (Rafailov et al., 2023) — DPO method
- Anthropic HH-RLHF dataset: https://huggingface.co/datasets/Anthropic/hh-rlhf
- "Judging LLM-as-a-Judge" (Zheng et al., 2023) — MT-Bench and Chatbot Arena methodology
- Open Assistant dataset: community-collected preference data
- UltraFeedback: LLM-as-judge preference dataset at scale
