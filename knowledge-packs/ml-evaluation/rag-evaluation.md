# RAG Evaluation

## When to use
- Evaluating retrieval-augmented generation pipelines end-to-end
- Diagnosing whether failures originate in retrieval, context assembly, or generation
- Comparing RAG configurations (chunk size, embedding model, retriever, generator)

## Pattern

### Component-Level Decomposition
RAG evaluation must separate retrieval quality from generation quality:
1. **Retrieval**: did we fetch the right documents?
2. **Context assembly**: did we present the right information to the generator?
3. **Generation**: did the model produce a correct, grounded answer?

Evaluating only end-to-end output makes root-cause analysis impossible.

### Retrieval Metrics
- **Context Recall**: fraction of relevant information present in retrieved context vs ground truth
- **Context Precision**: fraction of retrieved chunks that are actually relevant
- **Hit Rate / Recall@K**: did the correct document appear in the top-K results
- **Mean Reciprocal Rank (MRR)**: how high does the first relevant result rank
- Evaluate retrieval in isolation with known query-document relevance pairs

### Generation Metrics (Grounded in Context)
- **Faithfulness**: does the answer only contain claims supported by the retrieved context
  - Decompose the answer into atomic claims, verify each against context
  - Hallucination = claim not grounded in any retrieved chunk
- **Answer Relevancy**: does the answer address the original question
  - Generate synthetic questions from the answer; compare to the original question
- **Answer Correctness**: does the answer match the ground-truth answer (when available)
  - Combines factual accuracy with completeness

### RAGAS Methodology
- Framework for reference-free RAG evaluation using LLM-based judges
- Core metrics: faithfulness, answer relevancy, context precision, context recall
- Workflow:
  1. Prepare an evaluation dataset: (question, ground_truth_answer, contexts, generated_answer)
  2. Run each metric independently (each uses a different LLM judge prompt)
  3. Aggregate scores; identify which component is the bottleneck
- Strengths: does not require human labels for every question; scales well
- Limitations: dependent on judge model quality; validate on a labeled subset

### Building an Eval Dataset
- Start with 50-100 representative questions spanning your document corpus
- Include: factoid questions, multi-hop reasoning, questions requiring synthesis
- Include negative examples: questions the corpus cannot answer (tests refusal behavior)
- Record ground-truth answers and the specific source passages
- Version the dataset alongside the corpus; update when documents change

### Diagnostic Patterns
- Low context recall + high faithfulness = retrieval problem (right answer not fetched)
- High context recall + low faithfulness = generation problem (model hallucinating despite good context)
- Low answer relevancy + high faithfulness = the model answered a different question using the context
- High recall@K but low precision = too many irrelevant chunks diluting the context window

## Gotchas / Anti-patterns
- Evaluating only end-to-end accuracy without isolating retrieval vs generation
- Using BLEU/ROUGE for RAG answers (surface overlap is a poor proxy for grounded correctness)
- Testing only on questions the system was tuned for; always hold out novel queries
- Ignoring chunk boundary effects: the right paragraph split across two chunks may reduce recall
- Not testing with adversarial queries that resemble real questions but have no answer in the corpus
- Evaluating with a judge model weaker than the generator model
- Assuming faithfulness implies correctness (the context itself may be outdated or wrong)

## References
- Es et al., "RAGAS: Automated Evaluation of Retrieval Augmented Generation" (2023)
- Chen et al., "Benchmarking Large Language Models in Retrieval-Augmented Generation" (2024)
- LlamaIndex and LangChain evaluation module documentation
