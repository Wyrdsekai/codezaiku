# Benchmark Design

## When to use
- Creating evaluation suites for a specific domain or capability
- Ensuring your evaluation reflects real-world usage, not just toy problems
- Preventing data contamination in the era of large-scale web-scraped training data

## Pattern

### Principles of Representative Benchmarks
- **Coverage**: include the full range of difficulty levels, domains, and edge cases
- **Balance**: avoid over-representing easy or common cases; stratify by difficulty and category
- **Realism**: samples should mirror actual deployment conditions, not synthetic perfection
- **Size**: large enough for statistical power; small enough for practical iteration (100-1000 samples typical for LLM eval)
- **Discriminative power**: the benchmark should separate good models from great ones, not just bad from acceptable

### Task Taxonomy
- Define a clear taxonomy of capabilities being tested before selecting examples
- Map each benchmark sample to one or more capability categories
- Ensure minimum coverage per category (at least 10-20 samples each)
- Include "distractor" categories that test model boundaries (what it should refuse or flag as unanswerable)

### Sample Construction
- Draw from real data where possible, not hand-crafted synthetic examples
- For held-out creation: collect data after the training cutoff date
- For manual curation: use domain experts, not the model builder
- Include graduated difficulty: easy (baseline sanity), medium (typical), hard (frontier), adversarial
- Write clear, unambiguous ground-truth annotations with documented criteria

### Preventing Data Contamination
- **Temporal isolation**: use data generated after model training cutoff
- **Canary strings**: embed unique identifiers in benchmark samples; search for them in model outputs to detect memorization
- **Paraphrase detection**: check if benchmark questions appear verbatim or paraphrased in common web crawls
- **Held-out variants**: create multiple versions of each question with different surface forms, same underlying reasoning
- **Private test sets**: keep a portion of the benchmark undisclosed; only accept model outputs through a controlled API
- **N-gram overlap analysis**: measure overlap between benchmark text and known training corpora

### Annotation Quality
- Write detailed annotation guidelines with examples and edge cases
- Use multiple annotators per sample; compute inter-annotator agreement
- Resolve disagreements through adjudication, not majority vote on ambiguous cases
- Pilot on 20-30 samples before full annotation run; refine guidelines based on disagreements
- Version control the guidelines alongside the benchmark

### Scoring Design
- Define scoring at the benchmark design stage, not after results come in
- Prefer decomposed scoring: separate sub-scores per capability category
- Avoid single aggregate leaderboard numbers that hide capability gaps
- Handle partial credit explicitly (is a half-right answer 0 or 0.5?)
- Document tie-breaking rules

### Benchmark Lifecycle
1. Define scope, taxonomy, and success criteria
2. Collect and annotate samples with quality controls
3. Pilot with 2-3 known models to calibrate difficulty and check discrimination
4. Run contamination checks against known corpora
5. Publish with documentation: intended use, limitations, known biases
6. Establish a versioning cadence; retire and replace when contamination becomes widespread

## Gotchas / Anti-patterns
- Releasing all benchmark data publicly and expecting it to remain uncontaminated
- Testing only the capabilities you expect the model to have (confirmation bias)
- Using ambiguous questions where reasonable annotators disagree on the answer
- Over-indexing on benchmark size at the expense of annotation quality
- Designing benchmarks that only the creator's model architecture can excel at
- Not documenting the intended scope; users apply the benchmark to unrelated tasks
- Treating benchmark creation as a one-time effort; benchmarks decay as models and data evolve

## References
- Bowman & Dahl, "What Will it Take to Fix Benchmarking in Natural Language Understanding?" (2021)
- Jacovi et al., "Stop Uploading Test Data in Plain Text" (2023)
- Ott et al., "Avoiding the Streetlight Effect in NLP" (2022)
- HELM: Holistic Evaluation of Language Models (Stanford CRFM)
