# LLM Evaluation

## When to use
- Evaluating language model quality for generation tasks (summarization, Q&A, code, dialogue)
- Comparing foundation models or fine-tuned variants
- Establishing quality baselines before and after model changes

## Pattern

### Perplexity
- Measures how well the model predicts a held-out token sequence
- Lower is better; exponential of the average negative log-likelihood per token
- Only meaningful for comparing models with the same tokenizer/vocabulary
- Useful for: pre-training evaluation, comparing model sizes within a family
- Not useful for: generation quality, instruction-following, safety

### BLEU (Bilingual Evaluation Understudy)
- N-gram precision between generated text and reference(s)
- Originally designed for machine translation; widely used but widely criticized
- Corpus-level metric; sentence-level BLEU is unreliable
- Does not capture semantic equivalence, fluency, or factual correctness
- Use as a coarse filter, never as a sole metric

### ROUGE (Recall-Oriented Understudy for Gisting Evaluation)
- Measures n-gram recall against reference summaries
- ROUGE-1 (unigram), ROUGE-2 (bigram), ROUGE-L (longest common subsequence)
- Better suited for summarization than BLEU
- Same fundamental limitation: surface-level overlap, not semantic understanding

### BERTScore and Semantic Similarity
- Computes token-level cosine similarity using contextual embeddings
- Captures paraphrase and semantic equivalence that n-gram metrics miss
- Model-dependent: results vary with the embedding model chosen
- More compute-intensive than BLEU/ROUGE

### LLM-as-Judge
- Use a capable LLM to evaluate another model's output against criteria
- Provide explicit rubrics with scoring dimensions and examples
- Patterns:
  - **Pointwise scoring**: rate a single response on defined criteria (1-5 scale)
  - **Pairwise comparison**: "which response is better and why" (reduces scale bias)
  - **Reference-guided**: provide a gold answer and ask the judge to compare
- Mitigations for judge bias:
  - Randomize response order (position bias: judges prefer the first/last response)
  - Use multiple judge models and aggregate
  - Include a "tie" option to avoid forced choices
  - Validate judge agreement against human labels on a calibration set
- Cost-effective for rapid iteration; not a replacement for human eval on high-stakes tasks

### Task-Specific Evaluation
- Code generation: pass@k (functional correctness on test cases), not BLEU
- Instruction following: constraint satisfaction rate (did the output follow all instructions)
- Factual Q&A: exact match, F1 over answer tokens, or claim-level verification
- Dialogue: user satisfaction ratings, task completion rate, turn efficiency

### Multi-Dimensional Assessment
- Always evaluate on multiple axes: correctness, helpfulness, harmlessness, conciseness
- A model can score well on fluency while hallucinating facts
- Weight dimensions by deployment context

## Gotchas / Anti-patterns
- Relying solely on BLEU/ROUGE for any task beyond machine translation or extractive summarization
- Using perplexity to compare models with different tokenizers
- LLM-as-judge without calibrating against human ratings
- Ignoring position bias in pairwise LLM evaluation
- Evaluating only on "happy path" inputs; always include adversarial and edge cases
- Treating benchmark scores as deployment-quality indicators without task-specific validation
- Averaging scores across fundamentally different capabilities (math + creative writing)

## References
- Papineni et al., "BLEU: a Method for Automatic Evaluation of Machine Translation" (2002)
- Lin, "ROUGE: A Package for Automatic Evaluation of Summaries" (2004)
- Zheng et al., "Judging LLM-as-a-Judge with MT-Bench and Chatbot Arena" (2023)
- Chang et al., "A Survey on Evaluation of Large Language Models" (2024)
