# Tokenization

## When to use
- Any NLP or code model that processes text as discrete tokens
- Choosing or configuring a tokenizer for a new model or domain
- Adapting an existing model to a new language or domain (vocabulary mismatch)
- Understanding throughput implications — token count directly affects compute cost

## Pattern

### Byte-Pair Encoding (BPE)
- Iteratively merges the most frequent byte/character pairs into tokens
- Vocabulary is built bottom-up from training corpus statistics
- Handles unseen words by decomposing into known subword units
- Used by: GPT family, LLaMA, Mistral, most modern LLMs
- Deterministic: same text always produces the same token sequence

### SentencePiece
- Language-agnostic tokenizer that operates on raw text (no pre-tokenization needed)
- Supports both BPE and Unigram algorithms
- Unigram: probabilistic model, selects tokenization that maximizes likelihood
- Treats input as raw byte stream — handles any language/script without preprocessing
- Used by: T5, LLaMA, multilingual models

### WordPiece
- Similar to BPE but uses likelihood-based merging criterion instead of frequency
- Prefixes subword continuations with `##` (e.g., "playing" -> "play" + "##ing")
- Used by: BERT, DistilBERT, ELECTRA

### Byte-Level Tokenization
- Operate directly on UTF-8 bytes — vocabulary of 256 base tokens
- No unknown tokens possible — any input is representable
- Sequences are longer (more tokens per word) — increases compute
- Byte-level BPE: start from bytes, merge upward. Used by GPT-2+

### Vocabulary Size Tradeoffs
- **Small vocab (8k-16k)**: More tokens per text, longer sequences, slower inference. Better for morphologically rich languages
- **Medium vocab (32k-64k)**: Standard range for most LLMs. Good balance of sequence length and coverage
- **Large vocab (100k-256k)**: Shorter sequences, faster inference, but more parameters in embedding layer and output projection
- Embedding layer size = vocab_size x d_model — large vocab adds significant parameter count
- Code models often benefit from larger vocab to capture common code patterns as single tokens

### Special Tokens
- `[BOS]` / `<s>`: Beginning of sequence — signals sequence start
- `[EOS]` / `</s>`: End of sequence — signals generation should stop
- `[PAD]`: Padding token for batching variable-length sequences
- `[UNK]`: Unknown token — fallback for byte-level tokenizers is unnecessary
- `[SEP]`: Separator for multi-segment inputs (e.g., premise + hypothesis)
- `[MASK]`: Masked token for MLM pretraining (BERT-style)
- Chat/instruction templates add role tokens (`<|user|>`, `<|assistant|>`, etc.)

### Domain adaptation
- Pretrained tokenizer may poorly represent domain-specific terms (medical, legal, code)
- Options: train new tokenizer on domain data, extend existing vocab with domain tokens, or accept subword decomposition
- Extending vocab requires resizing the embedding layer and training new embeddings (others frozen initially)

## Gotchas / Anti-patterns
- Using a tokenizer trained on English for a non-Latin-script language — extreme token inflation (5-10x more tokens per word)
- Ignoring tokenization when estimating costs — LLM API costs are per-token, tokenizer choice directly affects price
- Assuming one character = one token — common misconception. Average is ~4 characters per token for English
- Adding special tokens without updating model embedding dimensions — silent errors or crashes
- Not accounting for chat template tokens in context window budget
- Training a new tokenizer on too little data — poor merge statistics, suboptimal vocabulary

## References
- "Neural Machine Translation of Rare Words with Subword Units" (Sennrich et al., 2016) — BPE paper
- SentencePiece repository and documentation (Google)
- Hugging Face Tokenizers library documentation
- "Language Models are Unsupervised Multitask Learners" (Radford et al., 2019) — byte-level BPE
