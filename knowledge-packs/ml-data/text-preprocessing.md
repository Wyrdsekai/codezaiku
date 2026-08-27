# Text Preprocessing

## When to use
- Building any NLP pipeline (classification, NER, summarization, search, embeddings)
- Raw text data comes from heterogeneous sources (web scrapes, user input, OCR, logs)
- Training or fine-tuning language models on custom corpora
- Preparing text for traditional ML (TF-IDF, bag-of-words) or neural approaches
- Deduplicating large text datasets for training data quality

## Pattern

### Cleaning
- **HTML/markup removal**: strip tags, decode entities (`&amp;` to `&`); keep meaningful structure if needed
- **Unicode normalization**: NFC form; strip zero-width characters, control characters, BOM markers
- **Whitespace normalization**: collapse multiple spaces/newlines; trim leading/trailing whitespace
- **Encoding repair**: detect and fix mojibake (e.g., `Ã©` should be `e`); use chardet/ftfy
- **Boilerplate removal**: headers, footers, navigation text, cookie banners from web scrapes
- **PII redaction**: mask emails, phone numbers, SSNs, IP addresses with regex + validation

### Tokenization
- **For transformer models**: use the model's own tokenizer (BPE, WordPiece, SentencePiece); never substitute a different tokenizer
- **For traditional ML**: word-level tokenization, optionally with stemming or lemmatization
- **Sentence segmentation**: use rule-based (Pragmatic Segmenter) or model-based (spaCy) splitters; regex on periods fails on abbreviations
- **Subword tokenization** (BPE, Unigram): handles out-of-vocabulary words; standard for modern NLP
- **Language-specific concerns**: Chinese/Japanese/Thai lack whitespace word boundaries; use language-aware tokenizers

### Language detection
- Detect per-document or per-paragraph language before processing
- Libraries: fastText lid.176.bin (fast, 176 languages), langdetect, lingua
- Filter or route by language: monolingual model gets monolingual data
- Short texts (<20 characters) have unreliable language detection; handle with fallback rules

### Deduplication
- **Exact dedup**: hash full text (SHA-256) or normalized text; remove identical documents
- **Near-dedup**: MinHash + LSH (Locality-Sensitive Hashing) for approximate duplicate detection at scale
  - Jaccard similarity threshold of 0.8-0.9 is typical for near-duplicate removal
- **Substring dedup**: detect and remove boilerplate paragraphs repeated across many documents
- **Dedup before splitting**: duplicates across train/test cause data leakage
- For large-scale corpora (billions of docs), use tools like deduplicate-text-datasets or SlimPajama pipeline

### Normalization (task-dependent)
- **Lowercasing**: appropriate for classification, not for NER or case-sensitive tasks
- **Accent stripping**: only if the downstream task and language warrant it
- **Number normalization**: replace specific numbers with `<NUM>` token for generalization
- **URL/email normalization**: replace with placeholder tokens if content is not relevant
- **Stopword removal**: useful for bag-of-words/TF-IDF; harmful for transformer models (they use context)

### Corpus-level quality filtering
- Filter by document length (too short = low signal, too long = potential dumps/logs)
- Perplexity filtering: use a language model to score; remove very high perplexity documents (gibberish, OCR errors)
- Decontamination: remove documents that overlap with benchmark test sets

## Gotchas / Anti-patterns
- **Over-preprocessing for transformers**: modern LLMs handle casing, punctuation, and stopwords natively; excessive cleaning removes useful signal
- **Tokenizer mismatch**: using a different tokenizer than the model was trained with breaks the vocabulary mapping
- **Deduplicating after splitting**: duplicates in train and test leak information; always dedup before any split
- **Ignoring encoding issues**: UTF-8 should be the standard; silently replacing unknown bytes (`errors='replace'`) hides data quality problems
- **Language detection on mixed-language docs**: per-paragraph detection is more robust than per-document for multilingual content
- **Stemming for neural models**: stemming loses information and is unnecessary when using subword tokenization

## References
- ftfy: fixes text encoding issues (https://github.com/rspeer/python-ftfy)
- HuggingFace Tokenizers: fast BPE/WordPiece/Unigram implementations
- deduplicate-text-datasets: MinHash dedup at scale
- fastText language identification model
- "The Pile" and "RedPajama" data processing pipelines as reference implementations
